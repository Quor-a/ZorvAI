package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.tool.ToolDispatcher

/**
 * 用户一句话里他自己的话(开局、插话、回答追问一个口径)。语言提示和回答的语言检查只看他这次打的字([typed]);
 * 记忆、技能的引文还认他引的自己说过的话([said]),引的 ZorvAI 的回答不算。
 */
internal object UserWords {
    /**
     * [text] 是去掉屏幕说明后的那句(见 [UserScreenTurns]);再先后去掉浏览器页的页面说明([ToolDispatcher.userWords])、
     * 最前面引的那一段([QuoteMessage])和末尾的附件行([AttachmentLines])。都没有时是原话;只引了一段、只选了字、只带了附件时为空。
     */
    fun typed(text: String, tools: ToolDispatcher): String = AttachmentLines.words(QuoteMessage.words(tools.userWords(text)))

    /** [typed] 之外,引的是用户自己说过的话时连引的那段一起算(对话页引的是他当时打的字),放在前面;引的是 ZorvAI 的回答时只有 [typed]。 */
    fun said(text: String, tools: ToolDispatcher): String {
        val own = QuoteMessage.parse(tools.userWords(text)).quote?.takeIf { it.from == QuoteMessage.From.You }?.text
        return listOfNotNull(own, typed(text, tools)).filter { it.isNotBlank() }.joinToString("\n")
    }
}
