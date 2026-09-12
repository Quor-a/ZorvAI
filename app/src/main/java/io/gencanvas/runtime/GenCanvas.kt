package io.gencanvas.runtime

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.gencanvas.layout.YogaLayout
import io.gencanvas.model.AnimValues
import io.gencanvas.model.LaidNode
import io.gencanvas.model.Node
import io.gencanvas.model.UiDocument
import io.gencanvas.paint.ImageProvider
import io.gencanvas.paint.PaintContext
import io.gencanvas.paint.SvgCache
import io.gencanvas.paint.drawNodeOps
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * 渲染入口：把 [UiDocument] 变成原生像素。
 *
 * 渲染策略（混合分层）：
 *  - **像素**：整棵树在一个 Canvas 里递归绘制 —— 一次 draw pass，装饰再多也不掉帧。
 *  - **交互/无障碍**：布局层已算出每个节点的 frame，据此在上方叠一层透明热区，
 *    挂 pointerInput 与 semantics。热区不带任何绘制内容，成本可忽略。
 *
 * 这样既拿到"接近单 Canvas"的性能，又保留原生的可点击与 TalkBack 能力。
 */
@Composable
fun GenCanvas(
    document: UiDocument,
    modifier: Modifier = Modifier,
    imageProvider: ImageProvider = ImageProvider { null },
    onAction: (name: String, arg: JsonElement?) -> Unit = { _, _ -> },
    onError: (Throwable) -> Unit = {},
) {
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    val svgCache = remember { SvgCache() }

    // 内部状态：支持 { "set": { "/path": v } } 这类自包含交互
    var vars by remember(document) { mutableStateOf(document.vars) }
    val resolved = remember(document, vars) { resolveVars(document, vars) }

    // 单个时钟驱动全部 appear 动画：避免每节点一个 Animatable 的开销
    val total = remember(resolved) { maxDuration(resolved.root) }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(resolved) {
        clock.snapTo(0f)
        clock.animateTo(1f, tween(durationMillis = total.coerceIn(0, 10_000)))
    }
    val t = clock.value

    var pressedId by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        val tree = remember(resolved, w, h, density) {
            runCatching { YogaLayout.layout(resolved.root, w / density, h / density, density) }
                .onFailure { onError(it) }
                .getOrNull()
        }
        if (tree == null) {
            ErrorBox(onError, IllegalStateException("layout failed"))
            return@BoxWithConstraints
        }

        val ctx = remember(density, textMeasurer, imageProvider, resolved) {
            PaintContext(density, textMeasurer, imageProvider, resolved.theme.colors, svgCache)
        }

        val anims = remember(tree, t, pressedId) { computeAnim(tree, t * total, pressedId) }

        // ---- 像素层：整树一次绘制 ----
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            runCatching { drawTree(tree, ctx, anims) }
                .onFailure { onError(it) }
        }

        // ---- 交互 + 无障碍层 ----
        val hot = remember(tree) { collectHot(tree) }
        hot.forEach { (absX, absY, node) ->
            val f = node.frame
            Box(
                Modifier
                    .offset { IntOffset(absX.roundToInt(), absY.roundToInt()) }
                    .size((f.w / density).dp, (f.h / density).dp)
                    .semantics { node.node.contentDesc?.let { contentDescription = it } }
                    .pointerInput(node) {
                        detectTapGestures(
                            onPress = {
                                pressedId = node.node.id
                                tryAwaitRelease()
                                pressedId = null
                            },
                            onTap = { dispatch(node.node, onAction) { vars = it } },
                            onLongPress = { /* onLongPress action */ },
                        )
                    },
            )
        }
    }
}

@Composable
private fun ErrorBox(onError: (Throwable) -> Unit, e: Throwable) {
    LaunchedEffect(e) { onError(e) }
    Box(Modifier)
}

// ------------------------------------------------------------------ 绘制递归

private fun DrawScope.drawTree(node: LaidNode, ctx: PaintContext, anims: Map<String, AnimValues>) {
    val a = anims[node.node.id]
    translate(node.frame.x, node.frame.y) {
        if (a != null && (a.scaleX != 1f || a.scaleY != 1f || a.rotate != 0f || a.tx != 0f || a.ty != 0f)) {
            withTransform({
                translate(a.tx, a.ty)
                rotate(a.rotate, Offset(node.frame.w / 2f, node.frame.h / 2f))
                scale(a.scaleX, a.scaleY, Offset(node.frame.w / 2f, node.frame.h / 2f))
            }) {
                drawNodeOps(node, ctx)
            }
        } else {
            drawNodeOps(node, ctx)
        }
    }
    node.children.forEach { drawTree(it, ctx, anims) }
}

/** 每个节点按自己的 delay/dur 从全局时钟里取局部进度。 */
private fun computeAnim(root: LaidNode, elapsed: Float, pressedId: String?): Map<String, AnimValues> {
    val out = HashMap<String, AnimValues>(64)
    fun walk(n: LaidNode) {
        val spec = n.node.anim
        if (spec != null) {
            val local = ((elapsed - spec.delay).coerceAtLeast(0f) / spec.dur.coerceAtLeast(1))
                .coerceIn(0f, 1f)
            val pressed = pressedId == n.node.id
            val p = if (spec.trigger == "press") (if (pressed) 1f else 0f) else local
            out[n.node.id ?: n.hashCode().toString()] = lerpAV(
                AnimValues.from(spec.from),
                AnimValues.from(spec.to, AnimValues.from(spec.from)),
                p)
        }
        n.children.forEach(::walk)
    }
    walk(root)
    return out
}

private fun maxDuration(n: Node): Int {
    var m = 0
    fun walk(x: Node) {
        x.anim?.let { m = maxOf(m, it.delay + it.dur) }
        x.children.forEach(::walk)
    }
    walk(n)
    return m
}

/** 收集带 event 的节点及其绝对坐标（用于叠加透明热区）。 */
private fun collectHot(root: LaidNode): List<Triple<Float, Float, LaidNode>> {
    val out = mutableListOf<Triple<Float, Float, LaidNode>>()
    fun walk(n: LaidNode, ox: Float, oy: Float) {
        val x = ox + n.frame.x; val y = oy + n.frame.y
        if (n.node.event != null) out += Triple(x, y, n)
        n.children.forEach { walk(it, x, y) }
    }
    walk(root, 0f, 0f)
    return out
}

private fun dispatch(node: Node, onAction: (String, JsonElement?) -> Unit, setVars: (Map<String, JsonElement>) -> Unit) {
    val ev = node.event ?: return
    when (val c = ev.onClick) {
        is JsonPrimitive -> onAction(c.content, null)
        is JsonObject -> {
            val set = c["set"] as? JsonObject
            if (set != null) setVars(set.toMap())
        }
        else -> Unit
    }
}

// ------------------------------------------------------------------ 数据绑定

/** 把 {{/json/pointer}} 替换为 vars 中的实际值。 */
private fun resolveVars(doc: UiDocument, vars: Map<String, JsonElement>): UiDocument {
    if (vars.isEmpty()) return doc
    fun str(s: String): String {
        if (!s.contains("{{")) return s
        var out = s
        var i = out.indexOf("{{")
        while (i >= 0) {
            val j = out.indexOf("}}", i)
            if (j < 0) break
            val path = out.substring(i + 2, j).trim()
            val v = lookup(vars, path)
            out = out.substring(0, i) + (v ?: "") + out.substring(j + 2)
            i = out.indexOf("{{")
        }
        return out
    }
    fun el(e: JsonElement): JsonElement = when (e) {
        is JsonPrimitive -> JsonPrimitive(str(e.content))
        is JsonObject -> JsonObject(e.mapValues { (_, v) -> el(v) })
        is kotlinx.serialization.json.JsonArray ->
            kotlinx.serialization.json.JsonArray(e.map { el(it) })
    }
    fun node(n: Node): Node = n.copy(
        ops = n.ops.map { it.copy(p = JsonObject(it.p.mapValues { (_, v) -> el(v) })) },
        children = n.children.map(::node),
    )
    return doc.copy(root = node(doc.root))
}

private fun lookup(vars: Map<String, JsonElement>, pointer: String): String? {
    val parts = pointer.trim('/').split('/').filter { it.isNotEmpty() }
    var cur: JsonElement? = vars[parts.firstOrNull()] ?: return null
    for (p in parts.drop(1)) cur = (cur as? JsonObject)?.get(p) ?: return null
    return (cur as? JsonPrimitive)?.content
}

private fun lerpAV(a: AnimValues, b: AnimValues, t: Float) = AnimValues(
    alpha = a.alpha + (b.alpha - a.alpha) * t,
    scaleX = a.scaleX + (b.scaleX - a.scaleX) * t,
    scaleY = a.scaleY + (b.scaleY - a.scaleY) * t,
    rotate = a.rotate + (b.rotate - a.rotate) * t,
    tx = a.tx + (b.tx - a.tx) * t,
    ty = a.ty + (b.ty - a.ty) * t,
)
