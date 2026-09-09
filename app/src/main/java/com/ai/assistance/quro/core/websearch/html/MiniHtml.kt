package com.ai.assistance.quro.core.websearch.html

/**
 * MiniHtml —— 自研极简 HTML/XML 解析器。
 *
 * 设计取舍：
 * 1. 不引入 Jsoup（体积 + 与"完全自研"目标冲突），只做够用的容错解析；
 * 2. 不构建完整 DOM，只保留块级结构与文本，够密度算法使用；
 * 3. 天然兼容 XML（RSS 也是标签语言），因此搜索引擎的 RSS 输出可直接复用；
 * 4. 解析期即剔除 script/style 等噪声子树，避免后续反复过滤。
 */
class HNode(val tag: String) {
    var attrs: Map<String, String> = emptyMap()
    val children = mutableListOf<HNode>()
    var text: String = ""
    var parent: HNode? = null

    fun attr(k: String): String = attrs[k].orEmpty()
    /** class + id 合并，用于样板识别 */
    val identity: String get() = (attr("class") + " " + attr("id")).lowercase()
}

object MiniHtml {

    private val DROP = setOf(
        "script", "style", "noscript", "svg", "iframe", "form",
        "button", "select", "textarea", "template", "canvas", "object"
    )
    private val VOID = setOf(
        "br", "img", "hr", "input", "meta", "link", "source", "area", "base", "col", "embed", "track"
    )

    private val TAG_RE = Regex(
        """<(/?)([a-zA-Z][a-zA-Z0-9:_-]*)((?:[^>'"]|"[^"]*"|'[^']*')*)(/?)>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val ATTR_RE = Regex("""([^\s=/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")
    private val COMMENT_RE = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val DOCTYPE_RE = Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE)
    private val NUM_ENT = Regex("""&#(x?)([0-9a-fA-F]+);""")

    private val ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "#39" to "'", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "times" to "×", "deg" to "°", "euro" to "€", "copy" to "©", "reg" to "®"
    )

    /** 解析入口。抛出异常的可能性极低，任何畸形输入都会尽力产出可用树。 */
    fun parse(html: String): HNode {
        val src = html
        val root = HNode("#root")
        var cur = root
        var pos = 0

        val body = COMMENT_RE.replace(src, "")
        val cleaned = DOCTYPE_RE.replace(body, "")

        for (m in TAG_RE.findAll(cleaned)) {
            val chunk = cleaned.substring(pos, m.range.first)
            if (chunk.isNotBlank()) cur.text += unescape(chunk)
            pos = m.range.last + 1

            val closing = m.groupValues[1].isNotEmpty()
            val tag = m.groupValues[2].lowercase()
            val attrStr = m.groupValues[3]
            val selfClose = m.groupValues[4].isNotEmpty()

            if (tag in DROP) {
                if (!selfClose) skipToClose(cleaned, tag, pos)?.let { pos = it }
                continue
            }

            if (closing) {
                var n: HNode? = cur
                while (n != null && n !== root && n.tag != tag) n = n.parent
                if (n != null && n !== root) cur = n.parent ?: root
            } else {
                val node = HNode(tag)
                node.attrs = parseAttrs(attrStr)
                node.parent = cur
                cur.children.add(node)
                if (!selfClose && tag !in VOID) cur = node
            }
        }
        val tail = cleaned.substring(pos.coerceIn(0, cleaned.length))
        if (tail.isNotBlank()) cur.text += unescape(tail)
        return root
    }

    /** 跳过整个噪声子树：从 from 起找到对应的闭合标签 */
    private fun skipToClose(src: String, tag: String, from: Int): Int? {
        val re = Regex("""</$tag\s*>""", RegexOption.IGNORE_CASE)
        val start = from.coerceIn(0, src.length)
        return re.find(src, start)?.let { it.range.last + 1 }
    }

    private fun parseAttrs(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val out = HashMap<String, String>(4)
        for (m in ATTR_RE.findAll(s)) {
            val key = m.groupValues[1].lowercase()
            if (key.isEmpty()) continue
            val v = when {
                m.groupValues[2].isNotEmpty() -> m.groupValues[2]
                m.groupValues[3].isNotEmpty() -> m.groupValues[3]
                else -> m.groupValues[4]
            }
            out[key] = unescape(v)
        }
        return out
    }

    fun unescape(s: String): String {
        if ('&' !in s) return s
        var out = s
        for ((k, v) in ENTITIES) out = out.replace("&$k;", v)
        out = NUM_ENT.replace(out) { m ->
            val v = m.groupValues[2]
            val code = if (m.groupValues[1].isNotEmpty()) v.toIntOrNull(16) else v.toIntOrNull()
            code?.let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else "" } ?: ""
        }
        // 兜底清掉未识别实体
        return out.replace(Regex("""&[a-zA-Z#][a-zA-Z0-9#]*;"""), "")
    }

    /** 递归收集整棵子树的纯文本 */
    fun textOf(n: HNode, sb: StringBuilder = StringBuilder()): String {
        sb.append(n.text)
        for (c in n.children) textOf(c, sb)
        return sb.toString()
    }

    /** 子树内所有 <a> 锚文本的累计长度 */
    fun linkText(n: HNode): String {
        val sb = StringBuilder()
        walk(n) { if (it.tag == "a") sb.append(textOf(it)) }
        return sb.toString()
    }

    fun countTag(n: HNode, tag: String): Int {
        var c = 0
        walk(n) { if (it.tag == tag) c++ }
        return c
    }

    /** 深度优先遍历（含自身）。非 inline：递归 inline 会触发编译器告警且收益为负 */
    fun walk(n: HNode, cb: (HNode) -> Unit) {
        cb(n)
        for (c in n.children) walk(c, cb)
    }

    /** 取 <title> */
    fun title(root: HNode): String {
        var t = ""
        walk(root) {
            if (it.tag == "title" && t.isEmpty()) t = textOf(it).trim()
        }
        return t
    }

    /** 取 meta[name=description] 或 og:description，用于正文抽取失败时降级 */
    fun description(root: HNode): String {
        var d = ""
        walk(root) {
            if (it.tag == "meta" && d.isEmpty()) {
                val key = (it.attr("name") + it.attr("property")).lowercase()
                if (key in setOf("description", "og:description")) d = it.attr("content").trim()
            }
        }
        return d
    }
}
