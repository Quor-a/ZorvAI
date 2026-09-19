package com.yuanbao.miniapp.view

import com.yuanbao.miniapp.render.Style

/**
 * Parser for the WXSS subset (a CSS subset) used by mini programs.
 * Supported selectors: tag, .class, #id, comma groups, descendant combinators.
 */
class WxssParser {

    data class Rule(val selectors: List<Selector>, val declarations: Map<String, String>) {
        /** Higher specificity wins; later rules win on ties. */
        fun specificity(): Int = selectors.maxOf { it.specificity() }
    }

    data class Selector(val parts: List<Part>) {
        data class Part(val tag: String?, val id: String?, val classes: List<String>)

        fun specificity(): Int {
            var s = 0
            for (p in parts) {
                if (p.id != null) s += 100
                s += p.classes.size * 10
                if (p.tag != null) s += 1
            }
            return s
        }

        /** True when the selector's last part matches [node] and ancestors satisfy the prefix. */
        fun matches(node: TemplateNode): Boolean {
            if (parts.isEmpty()) return false
            if (!partMatches(parts.last(), node)) return false
            var i = parts.size - 2
            var cur = node.parent
            while (i >= 0) {
                if (cur == null) return false
                if (!partMatches(parts[i], cur)) {
                    // skip ancestors until one matches
                    var found = false
                    var up = cur.parent
                    while (up != null) {
                        if (partMatches(parts[i], up)) { found = true; cur = up.parent; break }
                        up = up.parent
                    }
                    if (!found) return false
                } else {
                    cur = cur.parent
                }
                i--
            }
            return true
        }

        private fun partMatches(part: Part, node: TemplateNode): Boolean {
            if (part.tag != null && node.tag != part.tag) return false
            if (part.id != null && node.attributes["id"] != part.id) return false
            for (c in part.classes) {
                val nodeClasses = (node.attributes["class"] ?: "").split(Regex("\\s+"))
                if (c !in nodeClasses) return false
            }
            return true
        }
    }

    fun parse(css: String): List<Rule> {
        val rules = ArrayList<Rule>()
        var i = 0
        while (i < css.length) {
            // strip comments
            if (css.startsWith("/*", i)) {
                val end = css.indexOf("*/", i + 2)
                i = if (end == -1) css.length else end + 2
                continue
            }
            val ch = css[i]
            if (ch.isWhitespace()) { i++; continue }
            val brace = css.indexOf('{', i)
            if (brace == -1) break
            val selectorText = css.substring(i, brace).trim()
            val close = css.indexOf('}', brace)
            if (close == -1) break
            val body = css.substring(brace + 1, close)
            i = close + 1
            if (selectorText.isEmpty() || selectorText.startsWith("@")) continue
            val decls = parseDeclarations(body)
            for (sel in selectorText.split(",")) {
                val s = sel.trim()
                if (s.isEmpty()) continue
                val parsed = parseSelector(s) ?: continue
                rules.add(Rule(listOf(parsed), decls))
            }
        }
        return rules
    }

    private fun parseDeclarations(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (chunk in body.split(";")) {
            val idx = chunk.indexOf(':')
            if (idx <= 0) continue
            val k = chunk.substring(0, idx).trim().lowercase()
            val v = chunk.substring(idx + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    private fun parseSelector(sel: String): Selector? {
        val parts = ArrayList<Selector.Part>()
        for (raw in sel.split(Regex("\\s+"))) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            var tag: String? = null
            var id: String? = null
            val classes = ArrayList<String>()
            val buf = StringBuilder()
            var i = 0
            while (i < token.length) {
                when (token[i]) {
                    '.' -> {
                        if (buf.isNotEmpty()) { tag = buf.toString(); buf.clear() }
                        i++
                        while (i < token.length && (token[i].isLetterOrDigit() || token[i] == '-' || token[i] == '_')) buf.append(token[i++])
                        classes.add(buf.toString())
                        buf.clear()
                    }
                    '#' -> {
                        if (buf.isNotEmpty()) { tag = buf.toString(); buf.clear() }
                        i++
                        while (i < token.length && (token[i].isLetterOrDigit() || token[i] == '-' || token[i] == '_')) buf.append(token[i++])
                        id = buf.toString()
                        buf.clear()
                    }
                    else -> {
                        buf.append(token[i])
                        i++
                    }
                }
            }
            if (buf.isNotEmpty()) tag = buf.toString()
            parts.add(Selector.Part(tag?.ifEmpty { null }, id, classes))
        }
        return if (parts.isEmpty()) null else Selector(parts)
    }

    /**
     * Computes the style for [node] by applying every matching rule, then inline style.
     */
    fun styleFor(node: TemplateNode, rules: List<Rule>): Style {
        val matched = rules.filter { it.selectors.any { s -> s.matches(node) } }
            .sortedWith(compareBy({ it.specificity() }))
        val merged = Style()
        for (rule in matched) {
            merged.merge(Style.fromDeclarations(rule.declarations))
        }
        val inline = node.attributes["style"]
        if (!inline.isNullOrEmpty()) {
            val decls = parseDeclarations(inline)
            merged.merge(Style.fromDeclarations(decls))
        }
        return merged
    }
}

/**
 * A parsed template node (WXML). Holds raw attributes; data binding is resolved later.
 */
class TemplateNode(
    val tag: String,
    val attributes: MutableMap<String, String> = LinkedHashMap(),
    var text: String = ""
) {
    var parent: TemplateNode? = null
    val children = ArrayList<TemplateNode>()
    /** wx:for / wx:if metadata. */
    var forExpr: String? = null
    var forItem: String = "item"
    var forIndex: String = "index"
    var ifExpr: String? = null
    var elseIfExpr: String? = null
    var isElse: Boolean = false

    fun addChild(n: TemplateNode) {
        n.parent = this
        children.add(n)
    }
}
