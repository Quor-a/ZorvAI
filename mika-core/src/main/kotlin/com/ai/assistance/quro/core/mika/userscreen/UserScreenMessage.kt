package com.ai.assistance.quro.core.mika.userscreen

/**
 * 用户的一句话里带着他此刻的屏幕时怎么写:单独一行 `[[screen:<编号>]]`,其余各行是附言(可以没有)。
 * 这句话照常存进对话、交给任务、作为插话或回答;对话页、卡片据此画成截图气泡,给模型时据此换成说明(见 [UserScreenPrompt])。
 */
object UserScreenMessage {
    private val LINE = Regex("^\\[\\[screen:([A-Za-z0-9_-]{1,64})]]$")

    /** 一句话拆开:[screens] 是里面带的屏幕编号(按出现先后),[words] 是附言(去掉首尾空白,没有为空串)。 */
    data class Parsed(val screens: List<String>, val words: String) {
        val hasScreen: Boolean get() = screens.isNotEmpty()
    }

    fun line(id: String): String {
        require(LINE.matches("[[screen:$id]]")) { "bad screen id" }
        return "[[screen:$id]]"
    }

    /** 屏幕那一行在前,附言在后;附言为空时只有那一行。 */
    fun compose(id: String, words: String): String = listOf(line(id), words.trim()).filter(String::isNotEmpty).joinToString("\n")

    fun parse(text: String): Parsed {
        val screens = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) screens += m.groupValues[1] else rest += l
        }
        return Parsed(screens, if (screens.isEmpty()) text.trim() else rest.joinToString("\n").trim())
    }

    fun has(text: String): Boolean = text.lineSequence().any { LINE.matches(it.trim()) }
}
