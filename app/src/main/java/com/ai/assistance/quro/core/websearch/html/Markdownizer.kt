package com.ai.assistance.quro.core.websearch.html

/**
 * Markdownizer —— 把抽取出的段落块渲染成 LLM 友好的 Markdown。
 *
 * 目标不是"还原页面"，而是"最大化信息/token 比"：
 * - 保留层级（标题 → ##/###），让模型理解结构；
 * - 保留列表与代码块，这两类内容对问答价值最高；
 * - 丢弃一切样式、class、空行噪声。
 */
object Markdownizer {

    private val WS = Regex("""[ \t　]+""")
    private val MULTI_NL = Regex("""\n{3,}""")

    fun toMarkdown(
        blocks: List<Pair<String, String>>,
        maxChars: Int = 12_000
    ): String {
        val sb = StringBuilder()
        for ((tag, raw) in blocks) {
            val t = WS.replace(raw.trim(), " ")
            if (t.isBlank()) continue
            when {
                tag.startsWith("h") && tag.length == 2 -> {
                    val level = tag[1].digitToIntOrNull() ?: 2
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("#".repeat(level.coerceIn(1, 4))).append(' ').append(t).append("\n\n")
                }
                tag == "li" -> sb.append("- ").append(t).append('\n')
                tag == "blockquote" -> sb.append("> ").append(t).append("\n\n")
                tag == "pre" -> sb.append("```\n").append(t).append("\n```\n\n")
                else -> sb.append(t).append("\n\n")
            }
            if (sb.length >= maxChars) break
        }
        return MULTI_NL.replace(sb.toString().trim(), "\n\n")
    }

    /** 按字符预算硬截断，尽量在句读处断开，避免半截句子污染上下文 */
    fun truncate(text: String, maxChars: Int): Pair<String, Boolean> {
        if (text.length <= maxChars) return text to false
        val cut = text.substring(0, maxChars)
        val stop = listOf('。', '！', '？', '.', '!', '?', '\n')
        val idx = cut.indexOfLast { it in stop }
        val safe = if (idx > maxChars * 0.6) cut.substring(0, idx + 1) else "$cut…"
        return safe to true
    }
}
