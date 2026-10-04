package com.ai.assistance.quro.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查点**数据层**的单测（不碰 [QuroRunCheckpoint] 的 IO —— 它依赖 `Context` 与
 * `org.json`，而 Android 单测环境里 `org.json` 是桩，调用即抛 "not mocked"）。
 *
 * 这里锁的是「快照能不能无损往返」——这是断点恢复的地基：
 * `QuroRunCheckpoint` 的 `save/load` 就是 `toJson/fromJson`，
 * 字段名写错一个字符 = 恢复出来的运行丢步骤或丢结果，且现场极难归因。
 */
class QuroRunCheckpointSnapshotTest {

    private fun round(round: Int, tool: String, ok: Boolean) = QuroRunCheckpoint.ToolRound(
        round = round,
        toolName = tool,
        arguments = """{"cmd":"ls -la"}""",
        result = "total 0\ndrwxr-xr-x 2 root root",
        success = ok,
        elapsedMs = 1234L,
    )

    @Test
    fun `tool round survives json round trip`() {
        val src = round(1, "terminal_exec", ok = true)
        val back = QuroRunCheckpoint.ToolRound.fromJson(src.toJson())

        assertEquals(src.round, back.round)
        assertEquals(src.toolName, back.toolName)
        assertEquals(src.arguments, back.arguments)
        assertEquals(src.result, back.result)
        assertEquals(src.success, back.success)
        assertEquals(src.elapsedMs, back.elapsedMs)
    }

    @Test
    fun `failed round is recorded as failed not silently ok`() {
        // 恢复时要知道「哪几步已经不必重跑」，失败与成功必须分得清。
        val back = QuroRunCheckpoint.ToolRound.fromJson(round(2, "web_fetch", ok = false).toJson())
        assertTrue(!back.success)
        assertEquals("web_fetch", back.toolName)
    }

    @Test
    fun `snapshot survives json round trip with all rounds`() {
        val src = QuroRunCheckpoint.Snapshot(
            runId = "run-1",
            conversationId = "conv-1",
            userMessage = "帮我看下这个目录",
            modelId = "gpt-5",
            rounds = listOf(round(1, "terminal_exec", true), round(2, "file_read", false)),
            partialOutput = "已经列出目录，接下来…",
            promptTokens = 1832,
            completionTokens = 418,
            startedAt = 1000L,
            updatedAt = 2000L,
        )
        val back = QuroRunCheckpoint.Snapshot.fromJson(src.toJson())

        assertEquals(src.runId, back.runId)
        assertEquals(src.conversationId, back.conversationId)
        assertEquals(src.userMessage, back.userMessage)
        assertEquals(src.modelId, back.modelId)
        assertEquals(src.partialOutput, back.partialOutput)
        assertEquals(src.promptTokens, back.promptTokens)
        assertEquals(src.completionTokens, back.completionTokens)
        assertEquals(src.startedAt, back.startedAt)
        assertEquals(2, back.rounds.size)
        assertEquals("terminal_exec", back.rounds[0].toolName)
        assertEquals("file_read", back.rounds[1].toolName)
    }

    @Test
    fun `empty rounds snapshot still round trips`() {
        // 第一轮工具还没跑完就被杀 → 快照里 rounds 为空，fromJson 不能因此崩掉或造出假轮次。
        val src = QuroRunCheckpoint.Snapshot(
            runId = "run-2",
            conversationId = "conv-2",
            userMessage = "",
            modelId = "",
            rounds = emptyList(),
            partialOutput = "",
            promptTokens = 0,
            completionTokens = 0,
            startedAt = 0L,
            updatedAt = 0L,
        )
        val back = QuroRunCheckpoint.Snapshot.fromJson(src.toJson())
        assertEquals("run-2", back.runId)
        assertEquals(0, back.rounds.size)
    }
}
