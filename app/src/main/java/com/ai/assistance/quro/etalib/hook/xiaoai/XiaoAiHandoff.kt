package com.ai.assistance.quro.etalib.hook.xiaoai

import com.ai.assistance.quro.etalib.agent.runtime.AgentExternalArchivePayload
import com.ai.assistance.quro.etalib.agent.runtime.AgentRuntimeWire
import org.json.JSONObject

internal object XiaoAiHandoff {
    const val SOURCE = "xiaoai"
    private const val ARCHIVE_TITLE_CHARS = 20

    fun create(
        runId: String,
        dialogId: String,
        prompt: String,
    ): AgentRuntimeWire.EntryHandoff =
        AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = SOURCE,
            dismissEntrySurfaceOnForegroundOperation = true,
            payload = AgentExternalArchivePayload(
                userText = prompt,
                conversationKey = dialogId,
                title = archiveTitle(prompt),
                adapterPayload = JSONObject().put("dialogId", dialogId),
            ).toJson(),
        )

    fun dialogIdFrom(handoff: AgentRuntimeWire.EntryHandoff): String {
        if (handoff.source != SOURCE) return ""
        val payload = AgentExternalArchivePayload.from(handoff.payload) ?: return ""
        return payload.adapterPayload.optString("dialogId")
    }

    private fun archiveTitle(prompt: String): String {
        val firstLine = prompt.lineSequence().firstOrNull().orEmpty().trim()
        return if (firstLine.isBlank()) {
            "超级小爱对话"
        } else {
            "超级小爱：${firstLine.take(ARCHIVE_TITLE_CHARS)}"
        }
    }
}
