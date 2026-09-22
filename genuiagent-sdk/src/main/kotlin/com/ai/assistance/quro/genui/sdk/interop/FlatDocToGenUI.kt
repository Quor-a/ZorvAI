package com.ai.assistance.quro.genui.sdk.interop

import com.ai.assistance.quro.genui.sdk.dsl.EdgeInsets
import com.ai.assistance.quro.genui.sdk.dsl.GenUIAction
import com.ai.assistance.quro.genui.sdk.dsl.GenUIEvent
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.dsl.UIStyle
import com.ai.assistance.quro.genui.sdk.dsl.UISpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A2UI FlatDoc → GenUI UISpec 转换器（通道对接层）
 *
 * 让 A2UI 文档复用 GenUI SDK 的 300+ 组件与效果引擎（渐变/辉光/纹理/圆角/胶囊形）。
 * A2UI 节点（text/heading/h1-3/title/sub/row/column/scroll/card/panel/button/input/
 * divider/line/spacer/image/progress/chip）→ GenUI 对应组件；
 * props（bg/color/size/radius/padding/shape/bold）→ UIStyle。
 */
object FlatDocToGenUI {

    /** A2UI 节点的最小结构镜像（避免 app 层依赖方向反转） */
    data class Node(
        val id: String,
        val type: String,
        val text: String = "",
        val kids: List<String> = emptyList(),
        val props: Map<String, String> = emptyMap()
    )

    fun convert(root: String, nodes: Map<String, Node>): UISpec? {
        val rootNode = nodes[root] ?: return null
        val component = build(rootNode, nodes, depth = 0) ?: return null
        // 根非 scroll 时包一层 scroll，保证内层自滚、长文可看全
        val finalRoot = if (component.type == "scroll") component
            else component.copy(type = "scroll", children = listOf(component))
        return UISpec(
            id = "a2ui-doc",
            title = rootNode.props["title"] ?: rootNode.text.ifBlank { "A2UI 页面" },
            root = finalRoot
        )
    }

    private fun build(node: Node, nodes: Map<String, Node>, depth: Int): UIComponent? {
        if (depth > 24) return null // 深度保护
        val style = styleOf(node.props)
        val type = normalizeType(node.type, node.props)
        val children = node.kids.mapNotNull { nodes[it] }
            .mapNotNull { build(it, nodes, depth + 1) }

        val properties = buildJsonObject {
            val text = node.text.ifBlank { node.props["content"] ?: node.props["value"] ?: "" }
            when (type) {
                "button" -> {
                    put("text", JsonPrimitive(text))
                    put("action", JsonPrimitive((node.props["action"] ?: text).ifBlank { node.id }))
                }
                "text_field" -> put("placeholder", JsonPrimitive(node.props["hint"] ?: text))
                "image" -> put("url", JsonPrimitive(node.props["url"] ?: node.text))
                "progress" -> put("value", JsonPrimitive(progressValue(node.props["value"])))
                else -> if (text.isNotBlank()) put("text", JsonPrimitive(text))
            }
            // 容器子间距：SDK 读的是**组件属性** spacing（默认 8dp），不是样式。
            // 模型按上游 flat 习惯写的是 `gap`（FlatRenderer 里就是读 gap），此前一路丢到默认 8dp——
            // 该松的地方没松开，卡片内元素挤成一片。这里两种写法都收。
            if (type in CONTAINER_TYPES) {
                spacingOf(node.props)?.let { put("spacing", JsonPrimitive(it)) }
            }
        }

        val events = if (type == "button") mapOf(
            "onClick" to GenUIEvent(
                actions = listOf(
                    GenUIAction.Custom(
                        handlerId = (node.props["action"] ?: node.text).ifBlank { node.id },
                        payload = JsonObject(emptyMap())
                    )
                )
            )
        ) else emptyMap()

        return UIComponent(
            type = type,
            properties = properties,
            style = style,
            children = children,
            events = events
        )
    }

    /** 需要子间距的容器类型 */
    private val CONTAINER_TYPES = setOf("column", "row", "scroll")

    /**
     * 子间距：节点上写 `gap` 或 `spacing` 都收（上游 flat 方言用 gap，SDK 用 spacing）。
     * 返回 null 时交给 SDK 默认值（8dp），不硬塞。
     */
    private fun spacingOf(props: Map<String, String>): Float? =
        (props["gap"] ?: props["spacing"])
            ?.toFloatOrNull()?.takeIf { it > 0f && it <= 120f }

    /**
     * 进度值归一化到 0..1。
     *
     * 模型两种写法都有：`value:0.6`（比例）与 `value:60`（百分比）。上游转换器一律除以 100，
     * 于是写 0.6 的进度条几乎不动；上游自己的 FlatRenderer 又一律按比例读，于是写 60 的会溢出到 100%。
     * 这里按数值本身判断：>1 视为百分比，≤1 视为比例——两种写法都能画对。
     */
    private fun progressValue(raw: String?): Float {
        val v = raw?.toFloatOrNull() ?: 0f
        return (if (v > 1f) v / 100f else v).coerceIn(0f, 1f)
    }

    /**
     * A2UI 类型 → SDK 组件类型。
     *
     * ⚠️ 标题级别必须落到**组件类型**上，不能只放在 props 里：
     * SDK 的 HeadingRenderer 用 `typographyConfig(component.type)` 决定字号，而它只认
     * `heading1..heading6`。`h1/h2/h3/heading/title` 若统一映射成裸 `"heading"`，
     * typographyConfig 会落到 `else -> 14sp / Normal` —— **所有标题和正文一样大**，
     * 版式层次直接消失（这正是 A2UI 页面「看着很平、没有设计感」的主因）。
     * 上游 FlatRenderer 的意图是 level 1/2/3 三档递降，这里对上 SDK 自己的排版刻度。
     */
    private fun normalizeType(t: String, props: Map<String, String> = emptyMap()): String {
        val base = t.lowercase().trim()
        // 显式 h1..h6 直接对号入座
        if (base.length == 2 && base[0] == 'h' && base[1] in '1'..'6') return "heading${base[1]}"
        return when (base) {
            // 裸 heading / title：读 level（扁平邻接表与官方协议两条路都会带 level），
            // 缺省按上游 FlatRenderer 的默认值 level=2 —— 保证绝不再掉进 14sp 正文档。
            "heading", "title", "headline" -> {
                val lv = props["level"]?.toFloatOrNull()?.toInt()?.coerceIn(1, 6) ?: 2
                "heading$lv"
            }
            "subhead", "subtitle", "sub" -> "text"
            "input", "textfield", "text_field" -> "text_field"
            "line", "hr" -> "divider"
            "panel", "container" -> "card"
            "btn" -> "button"
            "list" -> "column"
            "caption", "label", "body", "paragraph" -> "text"
            else -> base.ifBlank { "text" }
        }
    }

    /**
     * flat 方言 props → UIStyle。**键名必须宽进**。
     *
     * 模型写的键名五花八门：ZorvAI 短名（bg / color / radius）、A2UI 官方名（backgroundColor /
     * textColor）、下划线写法（corner_radius / border_radius）混着来。此前只认 bg/color/radius/
     * size/padding/shape 五个 —— 于是模型写 `backgroundColor` 的卡片底色、`corner_radius` 的圆角、
     * `textColor` 的字色**全部被静默丢掉**，界面上只剩没有底色、没有层次的方块。
     * 用户看到"组件不好看"，有相当一部分是这里丢属性丢出来的，不是模型没写。
     *
     * 所以这里把所有已知别名归一化后查表（大小写、下划线、连字符一律压平）。
     */
    private fun styleOf(props: Map<String, String>): UIStyle {
        val p = HashMap<String, String>(props.size * 2)
        props.forEach { (k, v) -> p[k.lowercase().replace("_", "").replace("-", "")] = v }
        fun s(vararg keys: String): String? = keys.firstNotNullOfOrNull { p[it]?.takeIf { v -> v.isNotBlank() } }
        fun f(vararg keys: String): Float? = s(*keys)?.toFloatOrNull()

        var out = UIStyle()
        // 底色 / 字色
        s("backgroundcolor", "bg", "fill", "surfacecolor")?.let { out = out.copy(backgroundColor = it) }
        s("textcolor", "color", "foreground", "fontcolor")?.let { out = out.copy(textColor = it) }
        // 字号 / 字重 / 排版
        f("fontsize", "textsize", "size")?.let { out = out.copy(textSize = it) }
        s("fontweight", "weight")?.let { out = out.copy(fontWeight = it) }
        if (p["bold"] == "true") out = out.copy(fontWeight = "bold")
        s("textalign", "align")?.let { out = out.copy(textAlign = it) }
        s("overflow", "textoverflow")?.let { out = out.copy(overflow = it) }
        s("maxlines")?.toFloatOrNull()?.let { out = out.copy(maxLines = it.toInt()) }
        // 形状 / 圆角 / 高度
        s("shape", "cornershape")?.let { out = out.copy(shape = it) }
        f("cornerradius", "radius", "borderradius", "rounded")?.let { out = out.copy(cornerRadius = it) }
        f("elevation", "shadow", "shadowelevation")?.let { out = out.copy(elevation = it) }
        f("opacity", "alpha")?.let { out = out.copy(opacity = it) }
        // 边框：既支持 "border":"1,outline" 合写，也支持 borderWidth + borderColor 分写
        s("border", "stroke")?.let { raw ->
            val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            parts.firstOrNull()?.toFloatOrNull()?.let { out = out.copy(borderWidth = it) }
            parts.getOrNull(1)?.let { out = out.copy(borderColor = it) }
        }
        f("borderwidth", "strokewidth")?.let { out = out.copy(borderWidth = it) }
        s("bordercolor", "strokecolor")?.let { out = out.copy(borderColor = it) }
        // 内/外边距：数字，或 "水平,垂直"
        s("padding")?.let { out = out.copy(padding = edge(it)) }
        s("margin")?.let { out = out.copy(margin = edge(it)) }
        // 尺寸：`h`/`w` 是上游 flat 方言的写法（image 高度、spacer 高度），别名一并收
        f("width", "w")?.let { out = out.copy(width = com.ai.assistance.quro.genui.sdk.dsl.Dimension.Fixed(it)) }
        f("height", "h")?.let { out = out.copy(height = com.ai.assistance.quro.genui.sdk.dsl.Dimension.Fixed(it)) }
        // 效果引擎（模型常用，此前全丢）
        s("gradient", "backgroundgradient")?.let { out = out.copy(gradient = it) }
        f("gradientangle")?.let { out = out.copy(gradientAngle = it) }
        s("glow", "glowcolor")?.let { out = out.copy(glow = it) }
        f("glowradius")?.let { out = out.copy(glowRadius = it) }
        s("pattern")?.let { out = out.copy(pattern = it) }
        s("patterncolor")?.let { out = out.copy(patternColor = it) }
        return out
    }

    /** "16" → 四边 16；"16,8" → 水平 16 / 垂直 8 */
    private fun edge(raw: String): EdgeInsets {
        val parts = raw.split(",").map { it.trim() }
        val a = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
        val b = parts.getOrNull(1)?.toFloatOrNull() ?: a
        return if (parts.size >= 2) EdgeInsets(a, b, a, b) else EdgeInsets(a, a, a, a)
    }
}
