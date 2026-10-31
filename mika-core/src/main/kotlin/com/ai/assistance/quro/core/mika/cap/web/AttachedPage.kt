package com.ai.assistance.quro.core.mika.cap.web

/**
 * 用户在 ZorvAI 的浏览器里、对着正看的那一页问 ZorvAI 时,这句话怎么写:最前面一段说明是哪一页(固定的英文,模型一看就懂;
 * 对话页、卡片认这一段),空一行后是用户这次说的话(原样)。例如
 * ```
 * [Web page I'm viewing in ZorvAI's browser. It is attached to this task as your current web tab.]
 * Title: 降噪耳机 - 搜索结果
 * URL: https://shop.example.com/s?k=降噪耳机
 * Selected text:
 * | 入耳式降噪耳机 B2 Pro
 * [End of page]
 *
 * 这页里 700 以内的挑一个
 * ```
 * 这句话原样存进对话、交给任务,模型看到的就是这样;只有从浏览器页开始的任务带它,插话不带。
 * 选中的字每行以「| 」开头,和说明的其余几行分得开;页面标题、选中的字都会截短。
 */
object AttachedPage {
    const val HEADER = "[Web page I'm viewing in ZorvAI's browser. It is attached to this task as your current web tab.]"
    const val END = "[End of page]"
    private const val TITLE = "Title: "
    private const val URL = "URL: "
    private const val SELECTED = "Selected text:"
    private const val SELECTED_LINE = "| "

    /** 页面标题最多这么多字。 */
    const val MAX_TITLE = 200

    /** 选中的字最多交给模型这么多字,多的截掉、末尾加「…」。 */
    const val MAX_SELECTION = 2000

    /** 网址最多这么长(极长的跟踪参数不全带)。 */
    const val MAX_URL = 1000

    /** 正看的那一页:[selection] 是用户选中的字,没选为 null。 */
    data class Page(val url: String, val title: String, val selection: String? = null)

    /** 拆开的一句话:[page] 是那一页(没带为 null),[words] 是用户这次说的话(没带时是原话)。 */
    data class Parsed(val page: Page?, val words: String)

    /** 说明在前,话在后;话为空时只有说明。 */
    fun compose(page: Page, words: String): String {
        val lines = mutableListOf(HEADER)
        oneLine(page.title).take(MAX_TITLE).takeIf(String::isNotEmpty)?.let { lines += TITLE + it }
        lines += URL + oneLine(page.url).take(MAX_URL)
        clip(page.selection)?.let { sel ->
            lines += SELECTED
            sel.lines().forEach { lines += SELECTED_LINE + it }
        }
        lines += END
        val block = lines.joinToString("\n")
        val w = words.trim()
        return if (w.isEmpty()) block else "$block\n\n$w"
    }

    fun parse(text: String): Parsed {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return Parsed(null, text)
        val end = lines.indexOfFirst { it.trim() == END }
        if (end < 0) return Parsed(null, text)
        var title = ""
        var url = ""
        val selected = mutableListOf<String>()
        var inSelection = false
        for (l in lines.subList(1, end)) {
            when {
                inSelection && l.startsWith(SELECTED_LINE.trimEnd()) -> selected += l.removePrefix(SELECTED_LINE.trimEnd()).removePrefix(" ")
                l.startsWith(TITLE) -> title = l.removePrefix(TITLE)
                l.startsWith(URL) -> url = l.removePrefix(URL)
                l.trim() == SELECTED -> inSelection = true
            }
        }
        val selection = selected.joinToString("\n").trim().ifEmpty { null }
        return Parsed(Page(url.trim(), title.trim(), selection), lines.drop(end + 1).joinToString("\n").trim())
    }

    /** 去掉页面说明后的话;没带的原样返回。 */
    fun words(text: String): String = parse(text).words

    /** 这句话带着页面说明。 */
    fun has(text: String): Boolean = text.lineSequence().firstOrNull()?.trim() == HEADER

    /** 选中的字:去掉首尾空白,每行去掉行尾空白,超过 [MAX_SELECTION] 截断加「…」;空的为 null。说明的结束行不会出现在里面。 */
    fun clip(selection: String?): String? {
        val t = selection?.lines()?.joinToString("\n") { it.trimEnd() }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val cut = if (t.length <= MAX_SELECTION) t else t.take(MAX_SELECTION).trimEnd() + "…"
        return cut.replace(END, "[End]")
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
