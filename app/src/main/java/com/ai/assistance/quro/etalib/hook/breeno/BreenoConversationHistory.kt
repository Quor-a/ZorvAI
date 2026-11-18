package com.ai.assistance.quro.etalib.hook.breeno

import com.ai.assistance.quro.etalib.agent.model.AgentModelClient

internal object BreenoConversationHistory {
    private const val MAX_MESSAGES = 24
    private const val MAX_MESSAGE_CHARS = 16 * 1024
    private const val MAX_TOTAL_CHARS = 48 * 1024
    private const val TRUNCATED_SUFFIX = "\n[较早内容过长，已截断]"

    data class Entry(
        val chatType: Int,
        val recordId: String,
        val content: String,
    )

    /**
     * 小布 DataCenter 已经保存当前房间的展示顺序，这里只投影文本轮次。
     * 当前问句由 RunRequest.prompt 单独传入，必须从 history 中排除。
     */
    fun build(
        entries: Iterable<Entry>,
        currentRecordId: String,
        currentContent: String = "",
    ): List<AgentModelClient.ConversationMessage> {
        val entryList = entries.toList()
        val fallbackCurrentIndex = if (currentRecordId.isBlank() && currentContent.isNotBlank()) {
            entryList.indexOfLast { entry ->
                entry.chatType == 1 && entry.content.trim() == currentContent.trim()
            }
        } else {
            -1
        }
        val normalized = entryList.asSequence()
            .filterIndexed { index, entry ->
                val sameRecord = currentRecordId.isNotBlank() && entry.recordId == currentRecordId
                !sameRecord && index != fallbackCurrentIndex
            }
            .mapNotNull { entry ->
                val role = when (entry.chatType) {
                    1 -> "user"
                    2 -> "assistant"
                    else -> return@mapNotNull null
                }
                val content = entry.content.trim()
                if (content.isBlank()) return@mapNotNull null
                AgentModelClient.ConversationMessage(
                    role = role,
                    content = content.boundedMessage(),
                )
            }
            .fold(mutableListOf<AgentModelClient.ConversationMessage>()) { messages, message ->
                val previous = messages.lastOrNull()
                if (previous?.role == message.role) {
                    messages[messages.lastIndex] = previous.copy(
                        content = "${previous.content}\n\n${message.content}".boundedMessage(),
                    )
                } else {
                    messages += message
                }
                messages
            }

        val selected = ArrayDeque<AgentModelClient.ConversationMessage>()
        var totalChars = 0
        for (message in normalized.asReversed()) {
            if (selected.size >= MAX_MESSAGES) break
            if (selected.isNotEmpty() && totalChars + message.content.length > MAX_TOTAL_CHARS) break
            selected.addFirst(message)
            totalChars += message.content.length
        }
        while (selected.firstOrNull()?.role == "assistant") {
            selected.removeFirst()
        }
        return selected.toList()
    }

    private fun String.boundedMessage(): String {
        if (length <= MAX_MESSAGE_CHARS) return this
        val keptChars = (MAX_MESSAGE_CHARS - TRUNCATED_SUFFIX.length).coerceAtLeast(0)
        return take(keptChars) + TRUNCATED_SUFFIX
    }
}
