package com.yuanbao.miniapp.view

/**
 * Parser for the WXML subset:
 *   <view class="box" bindtap="onTap">{{ msg }}</view>
 *   <text wx:for="{{list}}" wx:key="id">{{item.name}}</text>
 *   <view wx:if="{{show}}">yes</view>
 * Self-developed: hand-written scanner, no XML parser dependency.
 */
class WxmlParser {

    private val selfClosing = setOf("image", "input", "icon", "br")

    fun parse(source: String): TemplateNode {
        // the virtual root answers to the "page" selector used in app.wxss
        val root = TemplateNode("page")
        parseInto(source, root)
        return root
    }

    private fun parseInto(src: String, parent: TemplateNode) {
        var i = 0
        while (i < src.length) {
            val lt = src.indexOf('<', i)
            if (lt == -1) {
                appendText(parent, src.substring(i))
                return
            }
            if (lt > i) appendText(parent, src.substring(i, lt))

            if (src.startsWith("<!--", lt)) {
                val end = src.indexOf("-->", lt)
                i = if (end == -1) src.length else end + 3
                continue
            }
            if (src.startsWith("</", lt)) {
                val gt = src.indexOf('>', lt)
                if (gt == -1) return
                i = gt + 1
                continue
            }

            val gt = findTagEnd(src, lt)
            if (gt == -1) {
                appendText(parent, src.substring(lt))
                return
            }
            val raw = src.substring(lt + 1, gt)  // e.g. `view class="a" bindtap="f"`
            val selfClose = raw.trimEnd().endsWith("/")
            val node = buildNode(raw, selfClose || src.substring(lt + 1, gt + 1).endsWith("/>"))
            i = gt + 1

            if (node.tag in selfClosing) {
                parent.addChild(node)
                continue
            }
            // find matching close tag
            val closeIdx = findMatchingClose(src, i, node.tag)
            if (closeIdx == -1) {
                parent.addChild(node)
                continue
            }
            val inner = src.substring(i, closeIdx)
            parseInto(inner, node)
            parent.addChild(node)
            i = src.indexOf('>', closeIdx) + 1
            if (i == 0) i = src.length
        }
    }

    private fun findTagEnd(src: String, from: Int): Int {
        var i = from + 1
        var inQuote = false
        var quoteChar = '"'
        while (i < src.length) {
            val c = src[i]
            if (inQuote) {
                if (c == quoteChar) inQuote = false
            } else {
                if (c == '"' || c == '\'') { inQuote = true; quoteChar = c }
                else if (c == '>') return i
            }
            i++
        }
        return -1
    }

    private fun findMatchingClose(src: String, from: Int, tag: String): Int {
        val openTag = "<$tag"
        val closeTag = "</$tag>"
        var depth = 0
        var i = from
        while (i < src.length) {
            val nextOpen = src.indexOf(openTag, i)
            val nextClose = src.indexOf(closeTag, i)
            if (nextClose == -1) return -1
            if (nextOpen != -1 && nextOpen < nextClose) {
                depth++
                i = nextOpen + openTag.length
            } else {
                if (depth == 0) return nextClose
                depth--
                i = nextClose + closeTag.length
            }
        }
        return -1
    }

    private fun appendText(parent: TemplateNode, raw: String) {
        val text = decodeEntities(raw)
        if (text.isBlank()) return
        val node = TemplateNode("#text")
        node.text = text
        parent.addChild(node)
    }

    private fun buildNode(raw: String, selfClose: Boolean): TemplateNode {
        var body = raw.trim()
        if (body.endsWith("/")) body = body.trimEnd('/').trim()
        val tokens = tokenizeAttributes(body)
        val tag = tokens.removeAt(0)
        val node = TemplateNode(tag.lowercase())
        for (t in tokens) {
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val key = t.substring(0, eq).trim()
            val value = unquote(t.substring(eq + 1).trim())
            when {
                key == "wx:for" -> node.forExpr = value
                key == "wx:for-item" -> node.forItem = value
                key == "wx:for-index" -> node.forIndex = value
                key == "wx:if" -> node.ifExpr = value
                key == "wx:elif" -> node.elseIfExpr = value
                key == "wx:else" -> node.isElse = true
                else -> node.attributes[key] = value
            }
        }
        if (selfClose) node.attributes["__selfClose"] = "1"
        return node
    }

    private fun tokenizeAttributes(body: String): MutableList<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var quoteChar = '"'
        for (c in body) {
            when {
                inQuote -> {
                    sb.append(c)
                    if (c == quoteChar) inQuote = false
                }
                c == '"' || c == '\'' -> { inQuote = true; quoteChar = c; sb.append(c) }
                c.isWhitespace() -> { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 && ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\''))) {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    private fun decodeEntities(s: String): String =
        s.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
}
