package com.yuanbao.miniapp.view

import com.yuanbao.miniapp.render.NodeType
import com.yuanbao.miniapp.render.RenderNode
import com.yuanbao.miniapp.util.Json

/**
 * Expands a WXML template + page data into the render tree.
 * Handles wx:for / wx:if / wx:elif / wx:else and {{ }} bindings.
 */
class VDomBuilder {

    /** Stable id per template path so scrolling / diffing survive re-renders. */
    private val pathIds = HashMap<String, Int>()
    private var nextId = 1

    private fun idFor(path: String): Int = pathIds.getOrPut(path) { nextId++ }

    fun build(root: TemplateNode, data: Json, rules: List<WxssParser.Rule>): RenderNode {
        val out = RenderNode(0, NodeType.ROOT, "root")
        out.style.display = com.yuanbao.miniapp.render.Display.FLEX
        // 页面根必须是纵向堆叠（微信 page 根为块级纵向）。旧逻辑只设 display 不设方向，
        // FlexLayout 把"显式 flex 无方向"补成 ROW → 页面顶层多个元素被横排/溢出裁切。
        out.style.flexDirection = com.yuanbao.miniapp.render.FlexDirection.COLUMN
        out.style.flexDirectionSet = true
        expandChildren(root, data, rules, out, "0")
        return out
    }

    private fun expand(node: TemplateNode, data: Json, rules: List<WxssParser.Rule>,
                       parent: RenderNode, path: String) {
        // ---- wx:for ----
        val forExpr = node.forExpr
        if (forExpr != null) {
            val list = ExpressionEvaluator().evaluate(stripBinding(forExpr), data)
            val items = if (list is Json.Arr) list.items else emptyList()
            items.forEachIndexed { index, item ->
                val ctx = Json.Obj(LinkedHashMap()).apply {
                    fields.putAll(data.asMap())
                    fields[node.forItem] = item
                    fields[node.forIndex] = Json.Num(index.toDouble())
                }
                emit(node, ctx, rules, parent, "$path.$index")
            }
            return
        }
        emit(node, data, rules, parent, path)
    }

    private fun emit(node: TemplateNode, data: Json, rules: List<WxssParser.Rule>,
                     parent: RenderNode, path: String) {
        if (node.tag == "#text") {
            val value = ExpressionEvaluator.interpolate(node.text, data)
            val textNode = RenderNode(idFor(path), NodeType.TEXT, "text")
            textNode.text = value.asString()
            textNode.style = WxssParser().styleFor(node, rules)
            parent.addChild(textNode)
            return
        }

        val type = when (node.tag) {
            "text", "label", "span" -> NodeType.TEXT
            "button", "navigator", "picker", "switch", "checkbox", "radio", "slider" -> NodeType.BUTTON
            "image", "cover-image", "video", "camera", "canvas", "map", "ad" -> NodeType.IMAGE
            "input", "textarea" -> NodeType.INPUT
            "scroll-view", "swiper", "swiper-item", "movable-area", "movable-view" -> NodeType.SCROLL
            "progress" -> NodeType.TEXT   // 进度以文本百分比呈现（AI 可用 view+style 画条）
            else -> NodeType.VIEW
        }
        val rn = RenderNode(idFor(path), type, node.tag)

        // attributes (with binding resolution)
        for ((k, raw) in node.attributes) {
            if (k == "__selfClose") continue
            val resolved = if (ExpressionEvaluator.hasBinding(raw)) {
                ExpressionEvaluator.interpolate(raw, data).asString()
            } else raw
            rn.attributes[k] = resolved
            if ((k == "value" || k == "placeholder") && type == NodeType.INPUT) {
                rn.attributes[k] = resolved
            }
        }

        // events: bindtap / catchtap / bindinput ...
        for ((k, v) in node.attributes) {
            val isBind = k.startsWith("bind") || k.startsWith("catch") || k.startsWith("mut-bind")
            if (!isBind) continue
            val eventName = when {
                k.startsWith("bind:") -> k.removePrefix("bind:")
                k.startsWith("catch:") -> k.removePrefix("catch:")
                k.startsWith("bind") -> k.removePrefix("bind")
                k.startsWith("catch") -> k.removePrefix("catch")
                else -> continue
            }
            if (eventName.isEmpty()) continue
            rn.events[eventName] = v
        }

        // style: template class + inline style matched against WXSS
        rn.style = WxssParser().styleFor(node, rules)
        if (type == NodeType.SCROLL) rn.style.overflow = com.yuanbao.miniapp.render.Overflow.SCROLL

        // text content inside container tags (e.g. <button>OK</button>)
        val directText = node.children.filter { it.tag == "#text" }
            .joinToString("") { it.text }
        if (directText.isNotBlank() && type != NodeType.VIEW) {
            rn.text = ExpressionEvaluator.interpolate(directText, data).asString()
        } else if (directText.isNotBlank()) {
            // <view> with plain text: render as an implicit text child
            val textNode = RenderNode(idFor("$path.t"), NodeType.TEXT, "text")
            textNode.text = ExpressionEvaluator.interpolate(directText, data).asString()
            textNode.style = rn.style.copy()
            rn.addChild(textNode)
        }

        parent.addChild(rn)
        expandChildren(node, data, rules, rn, path)
    }

    /**
     * Walks the children of a template node, resolving wx:if / wx:elif / wx:else
     * chains so that only the first matching branch is rendered.
     */
    private fun expandChildren(tpl: TemplateNode, data: Json, rules: List<WxssParser.Rule>,
                               parent: RenderNode, path: String) {
        var i = 0
        while (i < tpl.children.size) {
            val child = tpl.children[i]
            if (child.tag == "#text") { i++; continue }

            if (child.ifExpr != null) {
                val chain = ArrayList<TemplateNode>()
                chain.add(child)
                var j = i + 1
                while (j < tpl.children.size &&
                    (tpl.children[j].elseIfExpr != null || tpl.children[j].isElse)
                ) {
                    chain.add(tpl.children[j])
                    j++
                }
                val chosen = chain.firstOrNull { evaluateCondition(it, data) }
                if (chosen != null) expand(chosen, data, rules, parent, "$path.$i")
                i = j
                continue
            }
            expand(child, data, rules, parent, "$path.$i")
            i++
        }
    }

    private fun evaluateCondition(node: TemplateNode, data: Json): Boolean {
        val expr = node.ifExpr ?: node.elseIfExpr
        if (node.isElse) {
            // previous sibling must have been handled by the caller chain; treat as true
            return true
        }
        if (expr == null) return true
        val v = ExpressionEvaluator().evaluate(stripBinding(expr), data)
        return when (v) {
            is Json.Bool -> v.value
            is Json.Num -> v.value != 0.0
            is Json.Str -> v.value.isNotEmpty() && v.value != "false"
            is Json.Null -> false
            else -> true
        }
    }

    fun reset() {
        pathIds.clear()
        nextId = 1
    }

    companion object {
        fun stripBinding(expr: String): String {
            val t = expr.trim()
            return if (t.startsWith("{{") && t.endsWith("}}")) t.substring(2, t.length - 2).trim() else t
        }
    }
}
