package com.ai.assistance.quro.core.mika.agent

/**
 * 用户的话里带的附件怎么写:消息末尾每个附件一行「附件:/workspace/inbox/<名字>(<大小>)」,这几行原样存进对话、交给任务。
 * 对话页据此把附件画成照片格子和文件卡片(app 的 AttachmentMessage);语言提示、回答的语言检查和记忆、技能的引文都不算这几行(见 [UserWords])。
 * 旧对话里的消息也是这个写法。
 */
object AttachmentLines {
    private const val PREFIX = "附件:"

    /** 名字里可以有括号(「报表 (1).csv」):大小只认最后那对括号里的「数字 + B/KB/MB」;路径在沙箱的 /workspace 下。 */
    private val LINE = Regex("^附件:(/workspace/.+)\\((\\d+(?:\\.\\d+)? (?:B|KB|MB))\\)$")

    /** 拆开的一句话:[files] 是每个附件的路径和写在消息里的大小(「2 KB」),按出现先后;[words] 是附件行以外的话(去掉首尾空白)。 */
    data class Parsed(val files: List<Pair<String, String>>, val words: String)

    fun line(path: String, size: String): String = "$PREFIX$path($size)"

    /** 话在前,每个附件一行在后;话为空时只有附件行。 */
    fun compose(text: String, files: List<Pair<String, String>>): String =
        (listOf(text).filter { it.isNotEmpty() } + files.map { (p, s) -> line(p, s) }).joinToString("\n")

    fun parse(text: String): Parsed {
        val files = mutableListOf<Pair<String, String>>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) files += m.groupValues[1] to m.groupValues[2] else rest += l
        }
        if (files.isEmpty()) return Parsed(emptyList(), text.trim())
        return Parsed(files, rest.joinToString("\n").trim())
    }

    /** 去掉附件行后的话;没有附件行的原样返回。 */
    fun words(text: String): String = parse(text).let { if (it.files.isEmpty()) text else it.words }
}
