package com.ai.assistance.quro.kaleidobox.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as ComposeArrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ai.assistance.quro.kaleidobox.core.ui.*

/**
 * UiNode 树 → Jetpack Compose 渲染器。
 *
 * 这是"UI 与宿主深度融合"的落点，也是与 ToolPkg 的 Compose DSL 方案的本质差异：
 *
 *   ToolPkg：插件用 JS 写 Compose DSL，宿主解释执行 → 只能用 JS 写，且绑死宿主的 DSL 版本。
 *   Kaleido：插件（任何语言）返回**纯数据树**，宿主用原生 Compose 渲染 →
 *            语言无关、可静态校验、可 diff、可热重载，宿主换渲染器插件零改动。
 *
 * 渲染器只认识 tag 字符串，遇到不认识的 tag 降级成占位块 ——
 * 所以宿主升级新增组件后，老插件不会崩。
 */
object KaleidoCompose {

    /** 渲染一棵 UiNode 树。state 是宿主持有的可变状态，供 Bound.Ref 读取。 */
    @Composable
    fun Render(
        node: UiNode?,
        state: Map<String, Any?> = emptyMap(),
        onAction: (UiAction) -> Unit = {},
        surfaceId: String = "",
    ) {
        if (node == null) {
            Text("（空界面）", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        RenderNode(node, state, onAction, surfaceId)
    }

    init {
        // 原生 WebView 组件：供 WebUI 插件在应用内渲染网页。
        // props: url(String) — 要加载的地址；变化时自动重新加载。
        // 内置后退/前进/刷新工具条，规避插件无法持有 WebView 引用的问题。
        NativeComponentRegistry.register("webview") { props, _, _ ->
            val ctxUrl = (props["url"] as? String)?.takeIf { it.isNotEmpty() } ?: "about:blank"
            var webView by remember { mutableStateOf<WebView?>(null) }
            Column(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = WebViewClient()
                            loadUrl(ctxUrl)
                        }.also { webView = it }
                    },
                    update = { wv ->
                        val u = (props["url"] as? String)?.takeIf { it.isNotEmpty() }
                        if (u != null && u != wv.url) wv.loadUrl(u)
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { webView?.goBack() }) { Text("←") }
                    Button(onClick = { webView?.goForward() }) { Text("→") }
                    Button(onClick = { webView?.reload() }) { Text("⟳") }
                    Text(
                        (props["url"] as? String) ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun RenderNode(
        node: UiNode,
        state: Map<String, Any?>,
        onAction: (UiAction) -> Unit,
        surfaceId: String,
    ) {
        when (node) {
            is UiNode.Column -> Column(modifier = node.modifier.toModifier(state) { act ->
                onAction(act.toUiAction(surfaceId, node.id, state))
            }, verticalArrangement = node.arrangement.toVertical()) {
                node.children.forEach { child ->
                    val w = child.modifier.weight
                    if (w != null) Box(Modifier.weight(w)) { RenderNode(child, state, onAction, surfaceId) }
                    else RenderNode(child, state, onAction, surfaceId)
                }
            }

            is UiNode.Row -> Row(modifier = node.modifier.toModifier(state) { act ->
                onAction(act.toUiAction(surfaceId, node.id, state))
            }, horizontalArrangement = node.arrangement.toHorizontal()) {
                node.children.forEach { child ->
                    val w = child.modifier.weight
                    if (w != null) Box(Modifier.weight(w)) { RenderNode(child, state, onAction, surfaceId) }
                    else RenderNode(child, state, onAction, surfaceId)
                }
            }

            is UiNode.Box -> Box(modifier = node.modifier.toModifier(state) { act ->
                onAction(act.toUiAction(surfaceId, node.id, state))
            }) {
                node.children.forEach { RenderNode(it, state, onAction, surfaceId) }
            }

            is UiNode.Card -> Card(
                modifier = node.modifier.toModifier(state) { act ->
                    onAction(act.toUiAction(surfaceId, node.id, state))
                }.padding(4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = node.elevation.dp)
            ) {
                Column(Modifier.padding(4.dp)) {
                    node.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    node.child?.let { RenderNode(it, state, onAction, surfaceId) }
                }
            }

            is UiNode.Scroll -> {
                if (node.vertical) {
                    val s = rememberScrollState()
                    Column(
                        modifier = node.modifier.toModifier(state) { }.verticalScroll(s)
                    ) { node.child?.let { RenderNode(it, state, onAction, surfaceId) } }
                } else {
                    val s = rememberScrollState()
                    Row(
                        modifier = node.modifier.toModifier(state) { }.horizontalScroll(s),
                        horizontalArrangement = ComposeArrangement.Start
                    ) { node.child?.let { RenderNode(it, state, onAction, surfaceId) } }
                }
            }

            is UiNode.Text -> {
                val text = node.text.resolve(state)
                val style = when (node.style) {
                    TypeStyle.DISPLAY -> MaterialTheme.typography.displaySmall
                    TypeStyle.HEADLINE -> MaterialTheme.typography.headlineSmall
                    TypeStyle.TITLE -> MaterialTheme.typography.titleMedium
                    TypeStyle.LABEL -> MaterialTheme.typography.labelLarge
                    TypeStyle.CAPTION -> MaterialTheme.typography.bodySmall
                    TypeStyle.MONO -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    TypeStyle.BODY -> MaterialTheme.typography.bodyMedium
                }
                Text(
                    text = text,
                    style = style,
                    maxLines = node.maxLines ?: Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    color = node.color?.parseColor() ?: Color.Unspecified,
                    modifier = node.modifier.toModifier(state) { act ->
                        onAction(act.toUiAction(surfaceId, node.id, state))
                    }
                )
            }

            is UiNode.Button -> {
                val enabled = node.enabled.resolve(state)
                when (node.variant) {
                    UiNode.Button.Variant.FILLED -> Button(
                        onClick = { onAction(node.action.toUiAction(surfaceId, node.id, state)) },
                        enabled = enabled,
                        modifier = node.modifier.toModifier(state) {}
                    ) { Text(node.label.resolve(state)) }
                    UiNode.Button.Variant.OUTLINED -> OutlinedButton(
                        onClick = { onAction(node.action.toUiAction(surfaceId, node.id, state)) },
                        enabled = enabled,
                        modifier = node.modifier.toModifier(state) {}
                    ) { Text(node.label.resolve(state)) }
                    UiNode.Button.Variant.TEXT -> TextButton(
                        onClick = { onAction(node.action.toUiAction(surfaceId, node.id, state)) },
                        enabled = enabled,
                        modifier = node.modifier.toModifier(state) {}
                    ) { Text(node.label.resolve(state)) }
                    UiNode.Button.Variant.TONAL -> FilledTonalButton(
                        onClick = { onAction(node.action.toUiAction(surfaceId, node.id, state)) },
                        enabled = enabled,
                        modifier = node.modifier.toModifier(state) {}
                    ) { Text(node.label.resolve(state)) }
                }
            }

            is UiNode.Icon -> {
                // 图标名映射到宿主图标注册表由宿主侧负责；渲染器无图标资源时降级为文字占位，避免编译期依赖全套图标。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                    modifier = node.modifier.toModifier(state) {}
                ) {
                    Box(Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                        Text(
                            node.name, style = MaterialTheme.typography.labelSmall,
                            color = node.tint?.parseColor() ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            is UiNode.Image -> AsyncImageSafe(
                url = node.src.resolve(state),
                modifier = node.modifier.toModifier(state) {},
                blurhash = node.blurhash
            )

            is UiNode.TextField -> {
                val bound = node.value
                // 输入框值不能为 null，且必须随 state 单一来源回灌。
                // Bound.Ref 解析缺失 state 键时返回 null → 用 ?: "" 兜底。
                var value by remember(node.id) { mutableStateOf(bound.resolve(state) ?: "") }
                val external = bound.resolve(state) ?: ""
                LaunchedEffect(external) { value = external }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onAction(node.onValueChange.toUiAction(surfaceId, node.id, state, mapOf("value" to it)))
                    },
                    label = node.label?.let { { Text(it) } },
                    singleLine = node.singleLine,
                    modifier = node.modifier.toModifier(state) {}
                )
            }

            is UiNode.Switch -> {
                var checked by remember(node.id) { mutableStateOf(node.checked.resolve(state)) }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = node.modifier.toModifier(state) {}) {
                    Switch(checked = checked, onCheckedChange = {
                        checked = it
                        onAction(node.onToggle.toUiAction(surfaceId, node.id, state, mapOf("value" to it)))
                    })
                    node.label?.let { Spacer(Modifier.width(8.dp)); Text(it) }
                }
            }

            is UiNode.Slider -> {
                var v by remember(node.id) { mutableStateOf(node.value.resolve(state).toFloat()) }
                Slider(
                    value = v,
                    onValueChange = {
                        v = it
                        onAction(node.onValueChange.toUiAction(surfaceId, node.id, state, mapOf("value" to it)))
                    },
                    valueRange = node.range.start.toFloat()..node.range.endInclusive.toFloat(),
                    modifier = node.modifier.toModifier(state) {}
                )
            }

            is UiNode.Progress -> {
                val v = node.value?.resolve(state)
                if (v == null) CircularProgressIndicator(modifier = node.modifier.toModifier(state) {})
                else LinearProgressIndicator(progress = { v.toFloat().coerceIn(0f, 1f) },
                    modifier = node.modifier.toModifier(state) {})
            }

            is UiNode.Divider -> HorizontalDivider(modifier = node.modifier.toModifier(state) {})
            is UiNode.Spacer -> Spacer(Modifier.size(node.size.dp))

            is UiNode.Lazy -> {
                // 列表：items 是数据，itemTemplate 是每项的模板
                Column(modifier = node.modifier.toModifier(state) {}) {
                    node.items.forEach { row ->
                        val merged = state + row
                        node.itemTemplate?.let { RenderNode(it, merged, onAction, surfaceId) }
                    }
                }
            }

            is UiNode.RichBlock -> RichBlockView(node, state, surfaceId, onAction)

            is UiNode.Canvas -> CanvasView(node, state)

            is UiNode.Native -> {
                // 逃生舱：宿主注册的原生组件
                NativeComponentSlot(node, state, surfaceId, onAction)
            }
        }
    }

    // ---------------------------------------------------------------- 子组件

    @Composable
    private fun RichBlockView(
        node: UiNode.RichBlock,
        state: Map<String, Any?>,
        surfaceId: String,
        onAction: (UiAction) -> Unit,
    ) {
        when (node.kind) {
            UiNode.RichBlock.RichKind.MARKDOWN ->
                // 宿主应接入自己的 Markdown 渲染器；这里用等宽文本保证可用性
                Text(node.content, style = MaterialTheme.typography.bodyMedium,
                    modifier = node.modifier.toModifier(state) {})
            UiNode.RichBlock.RichKind.CODE -> Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = node.modifier.toModifier(state) {}
            ) {
                Text(node.content, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp))
            }
            else -> Text(node.content, modifier = node.modifier.toModifier(state) {})
        }
    }

    @Composable
    private fun CanvasView(node: UiNode.Canvas, state: Map<String, Any?>) {
        Canvas(
            modifier = node.modifier.toModifier(state) {}.height(120.dp).fillMaxWidth()
        ) {
            node.ops.forEach { op ->
                val c = when (op) {
                    is DrawOp.Line -> op.color
                    is DrawOp.Rect -> op.color
                    is DrawOp.Circle -> op.color
                    is DrawOp.Path -> op.color
                    is DrawOp.TextAt -> op.color
                }.parseColor()
                when (op) {
                    is DrawOp.Line -> drawLine(c, Offset(op.x1, op.y1), Offset(op.x2, op.y2), op.width)
                    is DrawOp.Rect -> if (op.filled)
                        drawRect(c, Offset(op.x, op.y), ComposeSize(op.w, op.h))
                    else
                        drawRect(c, Offset(op.x, op.y), ComposeSize(op.w, op.h), style = androidx.compose.ui.graphics.drawscope.Stroke(op.width))
                    is DrawOp.Circle -> if (op.filled)
                        drawCircle(c, op.r, Offset(op.cx, op.cy))
                    else
                        drawCircle(c, op.r, Offset(op.cx, op.cy), style = androidx.compose.ui.graphics.drawscope.Stroke(op.width))
                    is DrawOp.Path -> if (op.points.size >= 2) {
                        val p = ComposePath().apply {
                            moveTo(op.points.first().first, op.points.first().second)
                            op.points.drop(1).forEach { lineTo(it.first, it.second) }
                            if (op.close) close()
                        }
                        drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(op.width))
                    }
                    is DrawOp.TextAt -> drawContext.canvas.nativeCanvas.drawText(
                        op.text, op.x, op.y,
                        android.graphics.Paint().apply {
                            color = c.toArgbCompat()
                            textSize = op.size
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun NativeComponentSlot(
        node: UiNode.Native,
        state: Map<String, Any?>,
        surfaceId: String,
        onAction: (UiAction) -> Unit,
    ) {
        val renderer = NativeComponentRegistry.rendererFor(node.component)
        if (renderer != null) {
            renderer(node.props, state) { actionId, payload ->
                onAction(UiAction(surfaceId, node.id, actionId, payload, state))
            }
        } else {
            // 未知组件 → 可见的占位，而不是静默空白（便于插件作者排查）
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = node.modifier.toModifier(state) {}
            ) {
                Text("未注册的原生组件: ${node.component}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp))
            }
        }
    }

    @Composable
    private fun AsyncImageSafe(url: String, modifier: Modifier, blurhash: String?) {
        // 生产实现建议接 coil / glide；此处给出无依赖的安全占位实现
        Surface(
            modifier = modifier.height(120.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    blurhash?.let { "🖼 $url" } ?: url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }
    }

    // ---------------------------------------------------------------- 转换

    private fun Mod.toModifier(state: Map<String, Any?>, onClick: (Action) -> Unit): Modifier {
        var m: Modifier = when (val w = width) {
            Size.Fill -> Modifier.fillMaxWidth()
            is Size.Dp -> Modifier.width(w.v.dp)
            is Size.Fraction -> Modifier.fillMaxWidth(w.v)
            Size.Wrap -> Modifier
        }
        m = when (val h = height) {
            Size.Fill -> m.fillMaxHeight()
            is Size.Dp -> m.height(h.v.dp)
            is Size.Fraction -> m.fillMaxHeight(h.v)
            Size.Wrap -> m
        }
        if (padding != Edges()) m = m.padding(
            start = padding.start.dp, top = padding.top.dp,
            end = padding.end.dp, bottom = padding.bottom.dp
        )
        if (margin != Edges()) m = m.padding(
            start = margin.start.dp, top = margin.top.dp,
            end = margin.end.dp, bottom = margin.bottom.dp
        )
        background?.let { m = m.background(it.parseColor(), RoundedCornerShape(cornerRadius.dp)) }
        border?.let { m = m.border(it.width.dp, it.color.parseColor(), RoundedCornerShape(cornerRadius.dp)) }
        if (alpha < 1f) m = m.alpha(alpha)
        minWidth?.let { m = m.defaultMinSize(minWidth = it.dp) }
        minHeight?.let { m = m.defaultMinSize(minHeight = it.dp) }
        clickable?.let { m = m.clickable { onClick(it) } }
        return m
    }

    private fun Action.toUiAction(
        surfaceId: String, nodeId: String, state: Map<String, Any?>,
        extra: Map<String, Any?> = emptyMap()
    ) = UiAction(surfaceId, nodeId, id, payload + extra, state)

    private fun Arrangement.toVertical(): ComposeArrangement.Vertical = when (this) {
        Arrangement.TOP -> ComposeArrangement.Top
        Arrangement.BOTTOM -> ComposeArrangement.Bottom
        Arrangement.CENTER -> ComposeArrangement.Center
        Arrangement.SPACE_BETWEEN -> ComposeArrangement.SpaceBetween
        Arrangement.SPACE_AROUND -> ComposeArrangement.SpaceAround
        Arrangement.SPACE_EVENLY -> ComposeArrangement.SpaceEvenly
        else -> ComposeArrangement.Top
    }

    private fun Arrangement.toHorizontal(): ComposeArrangement.Horizontal = when (this) {
        Arrangement.START -> ComposeArrangement.Start
        Arrangement.END -> ComposeArrangement.End
        Arrangement.CENTER -> ComposeArrangement.Center
        Arrangement.SPACE_BETWEEN -> ComposeArrangement.SpaceBetween
        Arrangement.SPACE_AROUND -> ComposeArrangement.SpaceAround
        Arrangement.SPACE_EVENLY -> ComposeArrangement.SpaceEvenly
        else -> ComposeArrangement.Start
    }

    private fun <T> Bound<T>.resolve(state: Map<String, Any?>): T = when (this) {
        is Bound.Lit -> value
        is Bound.Ref -> {
            @Suppress("UNCHECKED_CAST")
            (state[path] as? T) ?: (fallback as T)
        }
        is Bound.Computed -> fallback as T  // computed 需宿主在渲染前回调，此处用 fallback
    }

    /** 支持 #RRGGBB / #AARRGGBB / 常用颜色名 */
    internal fun String.parseColor(): Color = try {
        when {
            startsWith("#") && length == 7 -> Color(android.graphics.Color.parseColor(this))
            startsWith("#") && length == 9 -> Color(android.graphics.Color.parseColor(this))
            else -> when (lowercase()) {
                "red" -> Color.Red; "green" -> Color.Green; "blue" -> Color.Blue
                "black" -> Color.Black; "white" -> Color.White
                "gray", "grey" -> Color.Gray; "transparent" -> Color.Transparent
                else -> Color.Unspecified
            }
        }
    } catch (_: Throwable) {
        Color.Unspecified
    }

    private fun Color.toArgbCompat(): Int =
        android.graphics.Color.argb(
            (alpha * 255).toInt(), (red * 255).toInt(),
            (green * 255).toInt(), (blue * 255).toInt()
        )
}

/**
 * 宿主原生组件注册表（UiNode.Native 的逃生舱）。
 * 宿主把自有组件注册进来，插件就能在 UI 树里直接引用，而不必等 Kaleido 增加新节点类型。
 */
object NativeComponentRegistry {

    typealias Renderer = @Composable (props: Map<String, Any?>, state: Map<String, Any?>,
                                      onAction: (String, Map<String, Any?>) -> Unit) -> Unit

    private val registry = mutableMapOf<String, Renderer>()

    fun register(name: String, renderer: Renderer) {
        registry[name] = renderer
    }

    fun rendererFor(name: String): Renderer? = registry[name]
    fun names(): Set<String> = registry.keys.toSet()
}
