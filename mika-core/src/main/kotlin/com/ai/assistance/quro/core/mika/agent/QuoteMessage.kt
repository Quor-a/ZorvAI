package com.ai.assistance.quro.core.mika.agent

/**
 * 用户的话里引用了前面的一条消息时怎么写:话的最前面一行标明引的是谁的话(固定的英文,模型一看就懂;对话页认这一行),
 * 接着每行以「> 」开头的是引的内容,空一行后是用户这次说的话。例如
 * ```
 * > [Quoting your earlier reply]
 * > 明天上午有小雨……
 *
 * 那要带伞吗
 * ```
 * 这句话原样存进对话、交给任务(新任务、插话都一样),模型看到的就是这样;对话页据此把引的内容画成气泡上方一小块淡色。
 * 引的那一段:语言提示和回答的语言检查不算;记忆、技能的引文只在引的是用户自己说过的话时算(见 [UserWords])。旧对话里的话没有这一行,照原样显示。
 */
object QuoteMessage {
    /** 引的是谁的话:ZorvAI 的回答,还是用户自己说过的话。 */
    enum class From(val header: String) {
        ZorvAI("> [Quoting your earlier reply]"),
        You("> [Quoting my earlier message]"),
    }

    /** 引的内容最多交给模型这么多字,多的截掉、末尾加「…」。 */
    const val MAX_CHARS = 1000

    data class Quote(val from: From, val text: String)

    /** 拆开的一句话:[quote] 是引的那条(没引为 null),[words] 是用户这次说的话(没引时是原话)。 */
    data class Parsed(val quote: Quote?, val words: String)

    /** 引的内容:去掉首尾空白,超过 [MAX_CHARS] 截断加「…」。 */
    fun clip(text: String): String {
        val t = text.trim()
        return if (t.length <= MAX_CHARS) t else t.take(MAX_CHARS).trimEnd() + "…"
    }

    /** 引用在前,话在后;没有引用或引的内容是空的时就是原话。 */
    fun compose(quote: Quote?, words: String): String {
        val body = quote?.let { clip(it.text) }?.takeIf { it.isNotEmpty() } ?: return words
        val block = (listOf(quote.from.header) + body.lines().map { if (it.isBlank()) ">" else "> $it" }).joinToString("\n")
        return if (words.isEmpty()) block else "$block\n\n$words"
    }

    fun parse(text: String): Parsed {
        val lines = text.lines()
        val from = From.entries.firstOrNull { it.header == lines.firstOrNull()?.trim() } ?: return Parsed(null, text)
        var i = 1
        val quoted = mutableListOf<String>()
        while (i < lines.size && lines[i].startsWith(">")) {
            quoted += lines[i].removePrefix(">").removePrefix(" ")
            i++
        }
        return Parsed(Quote(from, quoted.joinToString("\n").trim()), lines.drop(i).joinToString("\n").trim())
    }

    /** 去掉引用后的话;没引用的原样返回。 */
    fun words(text: String): String = parse(text).words
}
