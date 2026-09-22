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
        val type = normalizeType(node.type)
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
                "progress" -> put("value", JsonPrimitive((node.props["value"]?.toFloatOrNull() ?: 0f) / 100f))
                else -> if (text.isNotBlank()) put("text", JsonPrimitive(text))
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

    private fun normalizeType(t: String): String = when (t.lowercase()) {
        "h1", "h2", "h3", "heading", "title" -> "heading"
        "sub" -> "text"
        "input" -> "text_field"
        "line" -> "divider"
        "panel" -> "card"
        else -> t.lowercase().ifBlank { "text" }
    }

    private fun styleOf(props: Map<String, String>): UIStyle {
        var out = UIStyle()
        props["bg"]?.let { out = out.copy(backgroundColor = it) }
        props["color"]?.let { out = out.copy(textColor = it) }
        props["size"]?.toFloatOrNull()?.let { out = out.copy(textSize = it) }
        props["radius"]?.toFloatOrNull()?.let { out = out.copy(cornerRadius = it) }
        props["padding"]?.toFloatOrNull()?.let { out = out.copy(padding = EdgeInsets(it, it, it, it)) }
        props["shape"]?.let { out = out.copy(shape = it) }
        if (props["bold"] == "true") out = out.copy(fontWeight = "bold")
        return out
    }
}
