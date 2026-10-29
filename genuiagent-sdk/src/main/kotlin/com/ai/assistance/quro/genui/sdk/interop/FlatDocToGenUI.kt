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
                // header_bar（HeaderBarPattern）读的是 **title**，不是 text：
                // FlatDoc 已把 properties.title 并进 text，这里回填一份 title，
                // 否则整条头栏只有默认空标题、「北京」这种城市名整块消失。
                // subtitle 一并透传（模型写 properties.subtitle 时）。
                "header_bar" -> {
                    put("text", JsonPrimitive(text))
                    if (text.isNotBlank()) put("title", JsonPrimitive(text))
                    node.props["subtitle"]?.takeIf { it.isNotBlank() }
                        ?.let { put("subtitle", JsonPrimitive(it)) }
                }
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
        // 内/外边距：整串（"16" / "16,8" / CSS 四段 "上,右,下,左"）或分写别名
        // （paddingTop/paddingStart/…/paddingHorizontal/paddingVertical）都收。
        fun edgeOf(key: String): EdgeInsets? {
            val base = s(key)?.let { edge(it) }
            val top = f("${key}top", "padtop")
            val bottom = f("${key}bottom", "padbottom")
            val start = f("${key}start", "${key}left", "padstart", "padleft")
            val end = f("${key}end", "${key}right", "padend", "padright")
            val horizontal = f("${key}horizontal", "${key}x", "padx")
            val vertical = f("${key}vertical", "${key}y", "pady")
            if (top == null && bottom == null && start == null && end == null &&
                horizontal == null && vertical == null
            ) {
                return base
            }
            val b = base ?: EdgeInsets(0f, 0f, 0f, 0f)
            return EdgeInsets(
                start = start ?: horizontal ?: b.start,
                top = top ?: vertical ?: b.top,
                end = end ?: horizontal ?: b.end,
                bottom = bottom ?: vertical ?: b.bottom
            )
        }
        edgeOf("padding")?.let { out = out.copy(padding = it) }
        edgeOf("margin")?.let { out = out.copy(margin = it) }
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

    /**
     * 边距串 → [EdgeInsets]（参数序 start=左, top=上, end=右, bottom=底）。
     *
     * 段数语义（兼容两种方言，避免"16,8"被拧反）：
     *  - `"16"` → 四边 16；
     *  - `"16,8"` → **水平 16 / 垂直 8**（Android padding(h,v) 方言，旧行为，勿改）；
     *  - `"8,16,8"` → CSS 三段：上 8 / 左右 16 / 下 8；
     *  - `"8,4,12,16"` → CSS 四段：上 8 / 右 4 / 下 12 / 左 16。
     *
     * 🔴 四段是给 [com.ai.assistance.quro.genui.aiapp.renderx.FlatDoc] 的
     * `edgeString` 兜底的：它把 style 里的边距对象（`{"top":8,"right":4,…}` /
     * `{"horizontal":16,"vertical":8}` / `{"all":16}`）一律拍成 CSS 四段串。
     * 旧 [edge] 只解析 1~2 段，四段串会被读成「水平=上 / 垂直=右」——
     * 上右下左全拧一遍，卡片内边距上下不分（用户截图里内容贴顶就是它）。
     */
    private fun edge(raw: String): EdgeInsets {
        val parts = raw.split(",").map { it.trim() }
        fun num(i: Int) = parts.getOrNull(i)?.toFloatOrNull()
        val a = num(0) ?: 0f
        return when (parts.size) {
            0, 1 -> EdgeInsets(a, a, a, a)
            2 -> {
                // 水平,垂直（Android 方言）：parts[0] 走左右、parts[1] 走上下。
                // 🔴 别写成 EdgeInsets(b, a, b, a) —— 那等于把两个轴对调，
                // "16,8" 会变成「左右 8 / 上下 16」（2026-10-29 自查抓到的拧反 bug）。
                val b = num(1) ?: a
                EdgeInsets(a, b, a, b)
            }
            3 -> {
                // CSS 三段：上,左右,下
                val b = num(1) ?: a
                val c = num(2) ?: a
                EdgeInsets(b, a, b, c)
            }
            else -> {
                // CSS 四段：上,右,下,左
                val b = num(1) ?: a
                val c = num(2) ?: a
                val d = num(3) ?: a
                EdgeInsets(d, a, b, c)
            }
        }
    }

    /**
     * 边距全量解析：整串 + 分写别名合并（分写优先，逐边覆盖）。
     *
     * 模型写边距的写法至少有四种：`padding:16`、`padding:"16,8"`、
     * `padding:{all:16}`（FlatDoc 已拍成串）、以及 CSS 分写
     * `paddingTop:8 / paddingStart:16 / paddingHorizontal:12 …`。
     * 旧实现只认整串 —— 分写的那一份被静默丢掉，元素该有的间距全没啦。
     * （以局部函数形式定义在 [styleOf] 内，直接复用它的别名查表 `f`/`s`。）
     */
}
