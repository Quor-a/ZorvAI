package com.ai.assistance.quro.genui.app.agent.tools

/**
 * HTML 清洗：把搜索结果片段 / 网页正文转成可读纯文本。
 *
 * 为什么单独抽出来：搜索解析和正文抓取都需要它，而且实体解码和块级换行处理是最容易
 * 出错、最影响"可读性"的部分——集中一处才不会两边行为不一致。
 */
object Text {

    /** 去掉标签 + 解码实体 + 折叠空白 + 修剪。用于搜索结果里的标题/摘要片段。 */
    fun extract(html: String): String = decode(
        html.replace(Regex("""(?s)<(script|style|noscript)[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""<br\s*/?>"""), " ")
            .replace(Regex("""</(p|div|li|h[1-6]|tr|section|article)>"""), " ")
            .replace(Regex("""<[^>]+>"""), "")
    ).replace(Regex("""\s+"""), " ").trim()

    /**
     * 网页正文抽取。比 [extract] 多做两件事：
     * 1. 优先取 `<article>` / `<main>` / `#content` 区域，避开导航与页脚噪声；
     * 2. 块级标签之间插入换行，保留段落结构（纯文本读起来才有层次）。
     */
    fun article(html: String): String {
        val body = pickMainRegion(html)
        val spaced = body
            .replace(Regex("""(?s)<(script|style|noscript|svg|iframe|nav|footer|header|form)[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?s)<!--.*?-->"""), " ")
            .replace(Regex("""<br\s*/?>"""), "\n")
            .replace(Regex("""</(p|div|li|h[1-6]|tr|section|article|blockquote|pre)>"""), "\n")
            .replace(Regex("""<(p|div|li|h[1-6]|tr|section|article|blockquote|pre)\b[^>]*>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
        return decode(spaced)
            .lineSequence()
            .map { it.replace(Regex("""[ \t\u00A0\u3000]+"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** 挑出最可能是正文的片段；都找不到就退回整个 body。 */
    private fun pickMainRegion(html: String): String {
        for (re in MAIN_RES) {
            val m = re.find(html)
            if (m != null && m.value.length >= 400) return m.value
        }
        val bodyMatch = Regex("""(?s)<body[^>]*>(.*)</body>""").find(html)
        return bodyMatch?.groupValues?.get(1) ?: html
    }

    /** 解码常见 HTML 实体，含数字实体。 */
    fun decode(s: String): String {
        if ('&' !in s) return s
        var out = s
        // 命名实体
        for ((k, v) in NAMED) out = out.replace(k, v)
        // 十进制 &#123; 与十六进制 &#x1F600;
        out = NUM_DEC.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            cp(code)
        }
        out = NUM_HEX.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            cp(code)
        }
        return out
    }

    private fun cp(code: Int?): String =
        if (code == null || code <= 0 || code > 0x10FFFF) ""
        else String(Character.toChars(code))

    private val MAIN_RES = listOf(
        Regex("""(?s)<article[^>]*>.*?</article>"""),
        Regex("""(?s)<main[^>]*>.*?</main>"""),
        Regex("""(?s)<div[^>]*(?:id|class)="[^"]*(?:content|article|post|entry|main)[^"]*"[^>]*>.*?</div>\s*</div>"""),
    )

    private val NUM_DEC = Regex("""&#(\d{1,7});""")
    private val NUM_HEX = Regex("""&#[xX]([0-9a-fA-F]{1,6});""")

    private val NAMED = listOf(
        "&nbsp;" to " ", "&ensp;" to " ", "&emsp;" to " ", "&thinsp;" to " ",
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&apos;" to "'", "&#39;" to "'", "&mdash;" to "—", "&ndash;" to "–",
        "&hellip;" to "…", "&middot;" to "·", "&bull;" to "•", "&times;" to "×",
        "&divide;" to "÷", "&deg;" to "°", "&plusmn;" to "±", "&laquo;" to "«",
        "&raquo;" to "»", "&ldquo;" to "“", "&rdquo;" to "”", "&lsquo;" to "‘",
        "&rsquo;" to "’", "&copy;" to "©", "&reg;" to "®", "&trade;" to "™",
        "&euro;" to "€", "&pound;" to "£", "&yen;" to "¥", "&sect;" to "§",
        "&para;" to "¶", "&dagger;" to "†", "&permil;" to "‰", "&prime;" to "′",
        "&Prime;" to "″", "&oline;" to "‾", "&frasl;" to "⁄", "&larr;" to "←",
        "&rarr;" to "→", "&uarr;" to "↑", "&darr;" to "↓", "&harr;" to "↔",
        "&crarr;" to "↵", "&lsaquo;" to "‹", "&rsaquo;" to "›", "&fnof;" to "ƒ",
    )
}
