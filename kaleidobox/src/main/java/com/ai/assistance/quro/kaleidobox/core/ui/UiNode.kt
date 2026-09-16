package com.ai.assistance.quro.kaleidobox.core.ui

import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 声明式 UI 节点树 —— Kaleido 的"UI 与宿主深度融合"的另一半。
 *
 * ToolPkg 的做法：JS 里写 Compose DSL，宿主解释执行。问题有三：
 *   1. 只能用 JS 写（Python/Lua/Rust 插件写不了 UI）；
 *   2. UI 依赖宿主里那套 DSL 的版本，宿主升级就可能不兼容；
 *   3. 无法做跨端/跨宿主的静态校验与服务端预渲染。
 *
 * Kaleido 的做法：**任何语言只负责产出一棵 UiNode 树（纯数据），宿主负责渲染**。
 * 好处：
 *   - 语言无关：JS/Python/Lua/Rust 都能写 UI；
 *   - 宿主可换成 Compose / SwiftUI / Flutter / Web 任意渲染器，插件零改动；
 *   - 树是纯数据 → 可校验、可 diff、可热重载、可录制回放测试；
 *   - 双向绑定由 [Bound] 描述，宿主把事件按 action id 回灌给插件。
 */

sealed class UiNode {
    abstract val id: String
    abstract val modifier: Mod
    abstract fun children(): List<UiNode>

    // ---- 布局 ----
    data class Column(
        override val id: String, val children: List<UiNode> = emptyList(),
        override val modifier: Mod = Mod(), val arrangement: Arrangement = Arrangement.TOP,
    ) : UiNode() {
        override fun children() = children
    }

    data class Row(
        override val id: String, val children: List<UiNode> = emptyList(),
        override val modifier: Mod = Mod(), val arrangement: Arrangement = Arrangement.START,
    ) : UiNode() {
        override fun children() = children
    }

    data class Box(
        override val id: String, val children: List<UiNode> = emptyList(), override val modifier: Mod = Mod()
    ) : UiNode() {
        override fun children() = children
    }

    data class Card(
        override val id: String, val child: UiNode? = null, override val modifier: Mod = Mod(),
        val elevation: Int = 1, val title: String? = null
    ) : UiNode() {
        override fun children() = listOfNotNull(child)
    }

    data class Scroll(
        override val id: String, val child: UiNode? = null, override val modifier: Mod = Mod(),
        val vertical: Boolean = true
    ) : UiNode() {
        override fun children() = listOfNotNull(child)
    }

    data class Lazy(
        override val id: String, val itemTemplate: UiNode? = null, val items: List<Map<String, Any?>> = emptyList(),
        override val modifier: Mod = Mod(), val vertical: Boolean = true
    ) : UiNode() {
        override fun children() = listOfNotNull(itemTemplate)
    }

    // ---- 基础组件 ----
    data class Text(
        override val id: String, val text: Bound<String>, val style: TypeStyle = TypeStyle.BODY,
        override val modifier: Mod = Mod(), val maxLines: Int? = null, val color: String? = null
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    data class Button(
        override val id: String, val label: Bound<String>, val action: Action,
        override val modifier: Mod = Mod(), val enabled: Bound<Boolean> = Bound.Lit(true),
        val variant: Variant = Variant.FILLED
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
        enum class Variant { FILLED, OUTLINED, TEXT, TONAL }
    }

    data class Icon(
        override val id: String, val name: String, override val modifier: Mod = Mod(),
        val tint: String? = null, val contentDesc: String? = null
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    data class Image(
        override val id: String, val src: Bound<String>, override val modifier: Mod = Mod(),
        val contentScale: String = "fit", val blurhash: String? = null
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    data class TextField(
        override val id: String, val value: Bound<String>, val onValueChange: Action,
        override val modifier: Mod = Mod(), val label: String? = null, val singleLine: Boolean = true,
        val keyboard: Keyboard = Keyboard.TEXT
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
        enum class Keyboard { TEXT, NUMBER, PASSWORD, EMAIL, URI }
    }

    data class Switch(
        override val id: String, val checked: Bound<Boolean>, val onToggle: Action,
        override val modifier: Mod = Mod(), val label: String? = null
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    data class Slider(
        override val id: String, val value: Bound<Double>, val onValueChange: Action,
        val range: ClosedFloatingPointRange<Double> = 0.0..1.0, override val modifier: Mod = Mod()
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    data class Progress(
        override val id: String, val value: Bound<Double>?, override val modifier: Mod = Mod()
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    data class Divider(override val id: String, override val modifier: Mod = Mod()) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    data class Spacer(override val id: String, val size: Int = 8, override val modifier: Mod = Mod()) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    /** 宿主原生组件逃生舱：插件声明一个宿主已注册的原生组件名 + 参数。 */
    data class Native(
        override val id: String, val component: String, val props: Map<String, Any?> = emptyMap(),
        override val modifier: Mod = Mod()
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }

    /** 在聊天流里渲染的富块（Markdown / 代码 / 表格 / 自定义 XML）。 */
    data class RichBlock(
        override val id: String, val kind: RichKind, val content: String,
        val language: String? = null, override val modifier: Mod = Mod()
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
        enum class RichKind { MARKDOWN, CODE, TABLE, HTML, XML, LATEX, DIFF }
    }

    /** 插件自绘（Canvas）：宿主给一块画布 + 一串绘制指令，用于图表/波形等。 */
    data class Canvas(
        override val id: String, val ops: List<DrawOp>, override val modifier: Mod = Mod()
    ) : UiNode() {
        override fun children() = emptyList<UiNode>()
    }
}

enum class TypeStyle { DISPLAY, HEADLINE, TITLE, BODY, LABEL, CAPTION, MONO }
enum class Arrangement { TOP, BOTTOM, CENTER, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY, START, END }

/**
 *  Modifier。刻意做成命名参数的数据类而非链式 DSL，
 * 因为它是要跨语言序列化的 —— 数据比代码好传。
 */
data class Mod(
    val width: Size = Size.Wrap, val height: Size = Size.Wrap,
    val padding: Edges = Edges(), val margin: Edges = Edges(),
    val weight: Float? = null, val background: String? = null,
    val cornerRadius: Int = 0, val border: Border? = null,
    val clickable: Action? = null, val alpha: Float = 1f,
    val minWidth: Int? = null, val minHeight: Int? = null,
    val scrollable: Boolean = false, val testTag: String? = null,
)

sealed class Size {
    object Wrap : Size()
    object Fill : Size()
    data class Dp(val v: Int) : Size()
    data class Fraction(val v: Float) : Size()
}

data class Edges(val start: Int = 0, val top: Int = 0, val end: Int = 0, val bottom: Int = 0) {
    companion object {
        fun all(v: Int) = Edges(v, v, v, v)
        fun symmetric(horizontal: Int = 0, vertical: Int = 0) = Edges(horizontal, vertical, horizontal, vertical)
    }
}

data class Border(val width: Int, val color: String)

/**
 * 绑定值：要么是字面量，要么指向宿主状态树里的一个路径。
 * 这是双向绑定的关键 —— 宿主改状态 → UI 自动刷新；UI 事件 → 回灌插件。
 */
sealed class Bound<T> {
    data class Lit<T>(val value: T) : Bound<T>()
    /** state 路径，如 "form.name" / "list[0].title"，也支持 "res:string/hello" 取包内资源。 */
    data class Ref<T>(val path: String, val fallback: T? = null) : Bound<T>()
    /** 由插件函数计算（宿主在渲染前回调一次，结果进缓存）。 */
    data class Computed<T>(val action: Action, val fallback: T? = null) : Bound<T>()
}

/** 动作：宿主把 id + payload 回灌给插件声明的 onAction 函数。 */
data class Action(val id: String, val payload: Map<String, Any?> = emptyMap()) {
    companion object {
        fun of(id: String, vararg pairs: Pair<String, Any?>) = Action(id, mapOf(*pairs))
    }
}

/** 宿主回灌给插件的一次 UI 事件。 */
data class UiAction(
    val surfaceId: String,
    val nodeId: String,
    val actionId: String,
    val payload: Map<String, Any?> = emptyMap(),
    val state: Map<String, Any?> = emptyMap(),
)

sealed class DrawOp {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: String, val width: Float = 1f) : DrawOp()
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val color: String, val width: Float = 1f, val filled: Boolean = true) : DrawOp()
    data class Circle(val cx: Float, val cy: Float, val r: Float, val color: String, val width: Float = 1f, val filled: Boolean = true) : DrawOp()
    data class Path(val points: List<Pair<Float, Float>>, val color: String, val width: Float = 1f, val close: Boolean = false) : DrawOp()
    data class TextAt(val x: Float, val y: Float, val text: String, val color: String, val size: Float = 12f) : DrawOp()
}

/**
 * UiNode ↔ 纯数据 的编解码。
 * 插件（任何语言）产出的是这个 JSON 结构；宿主渲染器消费这棵树。
 * 用 tag 字段区分类型，保证新增组件时旧宿主也能安全降级（不认识的 tag 渲染成占位块）。
 */
object UiCodec {

    fun encode(node: UiNode): Map<String, Any?> = when (node) {
        is UiNode.Column -> mapOf(
            "tag" to "column", "id" to node.id, "arrangement" to node.arrangement.name,
            "mod" to encodeMod(node.modifier), "children" to node.children.map { encode(it) })
        is UiNode.Row -> mapOf(
            "tag" to "row", "id" to node.id, "arrangement" to node.arrangement.name,
            "mod" to encodeMod(node.modifier), "children" to node.children.map { encode(it) })
        is UiNode.Box -> mapOf(
            "tag" to "box", "id" to node.id, "mod" to encodeMod(node.modifier),
            "children" to node.children.map { encode(it) })
        is UiNode.Card -> mapOf(
            "tag" to "card", "id" to node.id, "mod" to encodeMod(node.modifier),
            "elevation" to node.elevation, "title" to node.title,
            "child" to (node.child?.let { encode(it) }))
        is UiNode.Text -> mapOf(
            "tag" to "text", "id" to node.id, "text" to encodeBound(node.text),
            "style" to node.style.name, "maxLines" to node.maxLines, "color" to node.color,
            "mod" to encodeMod(node.modifier))
        is UiNode.Button -> mapOf(
            "tag" to "button", "id" to node.id, "label" to encodeBound(node.label),
            "action" to encodeAction(node.action), "enabled" to encodeBound(node.enabled),
            "variant" to node.variant.name, "mod" to encodeMod(node.modifier))
        is UiNode.TextField -> mapOf(
            "tag" to "textfield", "id" to node.id, "value" to encodeBound(node.value),
            "onValueChange" to encodeAction(node.onValueChange), "label" to node.label,
            "singleLine" to node.singleLine, "keyboard" to node.keyboard.name,
            "mod" to encodeMod(node.modifier))
        is UiNode.Switch -> mapOf(
            "tag" to "switch", "id" to node.id, "checked" to encodeBound(node.checked),
            "onToggle" to encodeAction(node.onToggle), "label" to node.label,
            "mod" to encodeMod(node.modifier))
        is UiNode.Image -> mapOf(
            "tag" to "image", "id" to node.id, "src" to encodeBound(node.src),
            "contentScale" to node.contentScale, "blurhash" to node.blurhash,
            "mod" to encodeMod(node.modifier))
        is UiNode.Icon -> mapOf(
            "tag" to "icon", "id" to node.id, "name" to node.name, "tint" to node.tint,
            "desc" to node.contentDesc, "mod" to encodeMod(node.modifier))
        is UiNode.Slider -> mapOf(
            "tag" to "slider", "id" to node.id, "value" to encodeBound(node.value),
            "onValueChange" to encodeAction(node.onValueChange),
            "min" to node.range.start, "max" to node.range.endInclusive,
            "mod" to encodeMod(node.modifier))
        is UiNode.Progress -> mapOf(
            "tag" to "progress", "id" to node.id, "value" to (node.value?.let { encodeBound(it) }),
            "mod" to encodeMod(node.modifier))
        is UiNode.Divider -> mapOf("tag" to "divider", "id" to node.id, "mod" to encodeMod(node.modifier))
        is UiNode.Spacer -> mapOf("tag" to "spacer", "id" to node.id, "size" to node.size)
        is UiNode.Scroll -> mapOf(
            "tag" to "scroll", "id" to node.id, "vertical" to node.vertical,
            "mod" to encodeMod(node.modifier), "child" to (node.child?.let { encode(it) }))
        is UiNode.Lazy -> mapOf(
            "tag" to "lazy", "id" to node.id, "vertical" to node.vertical,
            "items" to node.items, "mod" to encodeMod(node.modifier),
            "item" to (node.itemTemplate?.let { encode(it) }))
        is UiNode.Native -> mapOf(
            "tag" to "native", "id" to node.id, "component" to node.component,
            "props" to node.props, "mod" to encodeMod(node.modifier))
        is UiNode.RichBlock -> mapOf(
            "tag" to "rich", "id" to node.id, "kind" to node.kind.name, "content" to node.content,
            "language" to node.language, "mod" to encodeMod(node.modifier))
        is UiNode.Canvas -> mapOf(
            "tag" to "canvas", "id" to node.id, "ops" to node.ops.map { encodeOp(it) },
            "mod" to encodeMod(node.modifier))
    }

    private fun encodeMod(m: Mod) = mapOf(
        "width" to encodeSize(m.width), "height" to encodeSize(m.height),
        "padding" to encodeEdges(m.padding), "margin" to encodeEdges(m.margin),
        "weight" to m.weight, "background" to m.background, "radius" to m.cornerRadius,
        "border" to (m.border?.let { mapOf("width" to it.width, "color" to it.color) }),
        "clickable" to (m.clickable?.let { encodeAction(it) }),
        "alpha" to m.alpha, "minWidth" to m.minWidth, "minHeight" to m.minHeight,
        "scrollable" to m.scrollable, "testTag" to m.testTag,
    )

    private fun encodeSize(s: Size) = when (s) {
        Size.Wrap -> "wrap"; Size.Fill -> "fill"
        is Size.Dp -> mapOf("dp" to s.v); is Size.Fraction -> mapOf("frac" to s.v)
    }

    private fun encodeEdges(e: Edges) = mapOf("s" to e.start, "t" to e.top, "e" to e.end, "b" to e.bottom)

    private fun <T> encodeBound(b: Bound<T>) = when (b) {
        is Bound.Lit -> mapOf("lit" to b.value)
        is Bound.Ref -> mapOf("ref" to b.path, "fallback" to b.fallback)
        is Bound.Computed -> mapOf("computed" to encodeAction(b.action), "fallback" to b.fallback)
    }

    private fun encodeAction(a: Action) = mapOf("id" to a.id, "payload" to a.payload)

    private fun encodeOp(o: DrawOp) = when (o) {
        is DrawOp.Line -> mapOf("op" to "line", "x1" to o.x1, "y1" to o.y1, "x2" to o.x2, "y2" to o.y2, "color" to o.color, "w" to o.width)
        is DrawOp.Rect -> mapOf("op" to "rect", "x" to o.x, "y" to o.y, "w" to o.w, "h" to o.h, "color" to o.color, "filled" to o.filled)
        is DrawOp.Circle -> mapOf("op" to "circle", "cx" to o.cx, "cy" to o.cy, "r" to o.r, "color" to o.color, "filled" to o.filled)
        is DrawOp.Path -> mapOf("op" to "path", "pts" to o.points.map { listOf(it.first, it.second) }, "color" to o.color, "w" to o.width, "close" to o.close)
        is DrawOp.TextAt -> mapOf("op" to "text", "x" to o.x, "y" to o.y, "text" to o.text, "color" to o.color, "size" to o.size)
    }

    // ---------------- decode ----------------

    @Suppress("UNCHECKED_CAST")
    fun decode(raw: Any?): UiNode? {
        val m = raw as? Map<*, *> ?: return null
        val map = m as Map<String, Any?>
        val tag = map["tag"] as? String ?: return null
        val id = map["id"] as? String ?: "n_${System.nanoTime()}"
        val mod = decodeMod(map["mod"])
        fun kids() = (map["children"] as? List<*>)?.mapNotNull { decode(it) } ?: emptyList()

        return try {
            when (tag) {
                "column" -> UiNode.Column(id, kids(), mod, enumOf(map["arrangement"], Arrangement.TOP))
                "row" -> UiNode.Row(id, kids(), mod, enumOf(map["arrangement"], Arrangement.START))
                "box" -> UiNode.Box(id, kids(), mod)
                "card" -> UiNode.Card(id, map["child"]?.let { decode(it) }, mod,
                    (map["elevation"] as? Number)?.toInt() ?: 1, map["title"] as? String)
                "scroll" -> UiNode.Scroll(id, map["child"]?.let { decode(it) }, mod, map["vertical"] as? Boolean ?: true)
                "text" -> UiNode.Text(id, decodeBound(map["text"]) ?: Bound.Lit(""),
                    enumOf(map["style"], TypeStyle.BODY), mod,
                    (map["maxLines"] as? Number)?.toInt(), map["color"] as? String)
                "button" -> UiNode.Button(id, decodeBound(map["label"]) ?: Bound.Lit(""),
                    decodeAction(map["action"]) ?: Action("noop"), mod,
                    decodeBound(map["enabled"]) ?: Bound.Lit(true),
                    enumOf(map["variant"], UiNode.Button.Variant.FILLED))
                "textfield" -> UiNode.TextField(id, decodeBound(map["value"]) ?: Bound.Lit(""),
                    decodeAction(map["onValueChange"]) ?: Action("noop"), mod,
                    map["label"] as? String, map["singleLine"] as? Boolean ?: true,
                    enumOf(map["keyboard"], UiNode.TextField.Keyboard.TEXT))
                "switch" -> UiNode.Switch(id, decodeBound(map["checked"]) ?: Bound.Lit(false),
                    decodeAction(map["onToggle"]) ?: Action("noop"), mod, map["label"] as? String)
                "image" -> UiNode.Image(id, decodeBound(map["src"]) ?: Bound.Lit(""), mod,
                    map["contentScale"] as? String ?: "fit", map["blurhash"] as? String)
                "icon" -> UiNode.Icon(id, map["name"] as? String ?: "", mod,
                    map["tint"] as? String, map["desc"] as? String)
                "slider" -> UiNode.Slider(id, decodeBound(map["value"]) ?: Bound.Lit(0.0),
                    decodeAction(map["onValueChange"]) ?: Action("noop"),
                    ((map["min"] as? Number)?.toDouble() ?: 0.0)..((map["max"] as? Number)?.toDouble() ?: 1.0), mod)
                "progress" -> UiNode.Progress(id, map["value"]?.let { decodeBound(it) }, mod)
                "divider" -> UiNode.Divider(id, mod)
                "spacer" -> UiNode.Spacer(id, (map["size"] as? Number)?.toInt() ?: 8)
                "native" -> UiNode.Native(id, map["component"] as? String ?: "",
                    map["props"] as? Map<String, Any?> ?: emptyMap(), mod)
                "rich" -> UiNode.RichBlock(id, enumOf(map["kind"], UiNode.RichBlock.RichKind.MARKDOWN),
                    map["content"] as? String ?: "", map["language"] as? String, mod)
                "canvas" -> UiNode.Canvas(id, (map["ops"] as? List<*>)?.mapNotNull { decodeOp(it) } ?: emptyList(), mod)
                "lazy" -> UiNode.Lazy(id, map["item"]?.let { decode(it) },
                    (map["items"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList(),
                    mod, map["vertical"] as? Boolean ?: true)
                else -> UiNode.Native(id, "unsupported", mapOf("tag" to tag), mod)
            }
        } catch (t: Throwable) {
            // 单节点解析失败不该让整棵树崩掉 —— 降级成占位，保证 UI 至少能出
            UiNode.Text(id, Bound.Lit("⚠ UI 节点解析失败: ${t.message}"), TypeStyle.CAPTION, mod)
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(v: Any?, d: E): E =
        (v as? String)?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: d

    private fun decodeMod(raw: Any?): Mod {
        val m = raw as? Map<*, *> ?: return Mod()
        @Suppress("UNCHECKED_CAST")
        val map = m as Map<String, Any?>
        return Mod(
            width = decodeSize(map["width"]), height = decodeSize(map["height"]),
            padding = decodeEdges(map["padding"]), margin = decodeEdges(map["margin"]),
            weight = (map["weight"] as? Number)?.toFloat(),
            background = map["background"] as? String,
            cornerRadius = (map["radius"] as? Number)?.toInt() ?: 0,
            border = (map["border"] as? Map<*, *>)?.let {
                @Suppress("UNCHECKED_CAST")
                val b = it as Map<String, Any?>
                Border((b["width"] as? Number)?.toInt() ?: 1, b["color"] as? String ?: "#888888")
            },
            clickable = decodeAction(map["clickable"]),
            alpha = (map["alpha"] as? Number)?.toFloat() ?: 1f,
            minWidth = (map["minWidth"] as? Number)?.toInt(),
            minHeight = (map["minHeight"] as? Number)?.toInt(),
            scrollable = map["scrollable"] as? Boolean ?: false,
            testTag = map["testTag"] as? String,
        )
    }

    private fun decodeSize(v: Any?): Size = when {
        v == "fill" -> Size.Fill
        v == "wrap" -> Size.Wrap
        v is Map<*, *> -> when {
            v.containsKey("dp") -> Size.Dp((v["dp"] as Number).toInt())
            v.containsKey("frac") -> Size.Fraction((v["frac"] as Number).toFloat())
            else -> Size.Wrap
        }
        v is Number -> Size.Dp(v.toInt())
        else -> Size.Wrap
    }

    private fun decodeEdges(v: Any?): Edges {
        val m = v as? Map<*, *> ?: return Edges()
        val g = { k: String -> (m[k] as? Number)?.toInt() ?: 0 }
        return Edges(g("s"), g("t"), g("e"), g("b"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> decodeBound(v: Any?): Bound<T>? {
        // 非对象一律当作字面量 —— 插件传错类型时安全降级，而不是让整棵树解析失败
        val m = v as? Map<*, *> ?: return Bound.Lit(v as T)
        val map = m as Map<String, Any?>
        return when {
            map.containsKey("lit") -> Bound.Lit(map["lit"] as T)
            map.containsKey("ref") -> Bound.Ref(map["ref"] as? String ?: "", map["fallback"] as? T)
            map.containsKey("computed") -> decodeAction(map["computed"])?.let { Bound.Computed(it, map["fallback"] as? T) }
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeAction(v: Any?): Action? {
        val m = v as? Map<*, *> ?: return null
        val map = m as Map<String, Any?>
        val id = map["id"] as? String ?: return null
        return Action(id, map["payload"] as? Map<String, Any?> ?: emptyMap())
    }

    private fun decodeOp(v: Any?): DrawOp? {
        val m = v as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val map = m as Map<String, Any?>
        fun f(k: String) = (map[k] as? Number)?.toFloat() ?: 0f
        val color = map["color"] as? String ?: "#000000"
        return when (map["op"]) {
            "line" -> DrawOp.Line(f("x1"), f("y1"), f("x2"), f("y2"), color, f("w").takeIf { it > 0 } ?: 1f)
            "rect" -> DrawOp.Rect(f("x"), f("y"), f("w"), f("h"), color, f("width").takeIf { it > 0 } ?: 1f, map["filled"] as? Boolean ?: true)
            "circle" -> DrawOp.Circle(f("cx"), f("cy"), f("r"), color, f("width").takeIf { it > 0 } ?: 1f, map["filled"] as? Boolean ?: true)
            "path" -> DrawOp.Path(
                (map["pts"] as? List<*>)?.mapNotNull { p ->
                    (p as? List<*>)?.let { (it[0] as Number).toFloat() to (it[1] as Number).toFloat() }
                } ?: emptyList(), color, f("w").takeIf { it > 0 } ?: 1f, map["close"] as? Boolean ?: false)
            "text" -> DrawOp.TextAt(f("x"), f("y"), map["text"] as? String ?: "", color, f("size").takeIf { it > 0 } ?: 12f)
            else -> null
        }
    }

    /** 从插件返回的 KValue 里取出 UI 树。允许插件直接返回 JSON 字符串或对象。 */
    fun fromK(v: KValue): UiNode? = when (v) {
        is KValue.Obj -> decode(v.value.mapValues { Json.fromK(it.value) })
        is KValue.Str -> decode(runCatching { Json.parse(v.value) }.getOrNull())
        else -> null
    }
}

/**
 * UI 树静态校验 —— 这也是"纯数据"带来的额外好处：
 * 在把树交给渲染器之前就能发现重复 id、非法 action、过深嵌套。
 */
object UiLint {
    fun check(root: UiNode, maxDepth: Int = 40): List<String> {
        val issues = mutableListOf<String>()
        val ids = mutableSetOf<String>()
        fun walk(n: UiNode, depth: Int) {
            if (depth > maxDepth) issues += "嵌套过深 (>${maxDepth}) 于节点 ${n.id}"
            if (!ids.add(n.id)) issues += "重复节点 id: ${n.id}"
            if (n is UiNode.Button && n.action.id.isBlank()) issues += "button ${n.id} 缺少 action.id"
            n.children().forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return issues
    }
}
