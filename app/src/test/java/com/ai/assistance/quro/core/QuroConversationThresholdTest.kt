package com.ai.assistance.quro.core

import com.ai.assistance.quro.core.model.QuroModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #22 上下文双阀值单测：压缩阀值（默认 80%）+ 软精简阀值（默认 50%）。
 *
 * 锁死四件事：
 * 1. 默认值即建议值 80 / 50（[QuroModelConfig]）；
 * 2. 软精简：估算用量**低于**阀值 → 超长工具输出一字不动；**达到**阀值 → 就地精简（头+尾+截断说明），
 *    且工具轮 call↔result 始终成对（不被孤儿剔除拆散）；
 * 3. 压缩：有效预算 =（窗口 − system）× 阀值。用量 95% 时阀值 80 → 裁最旧轮次、system 永留；
 *    阀值 100 → 同用量一条不裁（旧行为）；
 * 4. 旧行为等价：compress=100 + softTrim=0（低用量也永远精简工具输出）。
 *
 * token 估算口径 `estTokens(s) = max(1, s.length / 4)`，各用例尺寸按此口径设计；
 * 超长工具结果阈值 1600 字符（[TOOL_RESULT_CAP]），故工具结果统一取 4000 字符。
 */
class QuroConversationThresholdTest {

    private val BIG = 4000 // > TOOL_RESULT_CAP(1600) 的超长工具结果长度

    private fun storeOf(vararg msgs: QuroMessage): QuroConversationStore =
        QuroConversationStore().apply { msgs.forEach { add(it) } }

    /** 一对成对工具轮：assistant[tool_calls] + role=tool 结果（缺一则被孤儿剔除整组删掉）。 */
    private fun toolRound(id: String, name: String, resultLen: Int): List<QuroMessage> = listOf(
        QuroMessage(
            role = "assistant",
            content = "调用工具",
            toolCalls = listOf(QuroToolCall(id = id, name = name, arguments = "{}")),
        ),
        QuroMessage(role = "tool", content = "R".repeat(resultLen), toolCallId = id),
    )

    /** 低用量场景：user 250 + assistant 2 + tool 1000 + system 1 ≈ 1253 token。 */
    private fun lowUsageStore(): QuroConversationStore = storeOf(
        QuroMessage(role = "user", content = "U" + "x".repeat(1000)),
        *toolRound("c1", "terminal_exec", BIG).toTypedArray(),
    )

    private val sysMsg = QuroMessage(role = "system", content = "SYS")

    @Test
    fun `默认阀值为压缩80软精简50`() {
        val cfg = QuroModelConfig()
        assertEquals(80, cfg.contextCompressThreshold)
        assertEquals(50, cfg.contextSoftTrimThreshold)
    }

    @Test
    fun `低于软精简阀值_超长工具输出保留原文`() {
        // 用量 1253/4000 ≈ 31% < 50% → 不精简
        val out = lowUsageStore().toLlmMessages(
            system = sysMsg,
            contextWindow = 4000,
            compressThresholdPct = 100,
            softTrimThresholdPct = 50,
        )
        val tool = out.single { it.role == "tool" }
        assertEquals("R".repeat(BIG), tool.content) // 原文一字未动（无截断说明）
        assertTrue(out.any { it.toolCalls?.isNotEmpty() == true }) // 调用消息仍在
        assertTrue(out.any { it.role == "system" })
    }

    @Test
    fun `达到软精简阀值_超长工具输出就地精简且工具轮仍成对`() {
        // 用量 1253/2000 ≈ 62% ≥ 50% → 精简；compress=100 保证不裁轮次
        val out = lowUsageStore().toLlmMessages(
            system = sysMsg,
            contextWindow = 2000,
            compressThresholdPct = 100,
            softTrimThresholdPct = 50,
        )
        val tool = out.single { it.role == "tool" }
        assertTrue(tool.content, tool.content.contains("工具输出过长已截断"))
        assertTrue(tool.content.length < BIG) // 确实变短
        // 工具轮成对：assistant[tool_calls=c1] 与 tool[tool_call_id=c1] 都在
        assertTrue(out.any { m -> m.toolCalls?.any { it.id == "c1" } == true })
        assertTrue(out.any { m -> m.role == "tool" && m.toolCallId == "c1" })
        assertTrue(out.any { it.role == "system" })
    }

    @Test
    fun `压缩阀值80_用量95_裁最旧轮次且system永留`() {
        // 5 条普通消息共 1901 token，窗口 2000 → 用量 95%
        val store = storeOf(
            QuroMessage(role = "user", content = "A".repeat(2000)),
            QuroMessage(role = "assistant", content = "B".repeat(800)),
            QuroMessage(role = "user", content = "C".repeat(2000)),
            QuroMessage(role = "assistant", content = "D".repeat(800)),
            QuroMessage(role = "user", content = "E".repeat(2000)),
        )
        val out = store.toLlmMessages(
            system = QuroMessage(role = "system", content = "S"),
            contextWindow = 2000,
            compressThresholdPct = 80,
            softTrimThresholdPct = 50,
        )
        // 有效预算 = 1999×80% ≈ 1599：只保最新 4 条 + system，最旧的 A 被裁
        assertEquals(listOf("system", "assistant", "user", "assistant", "user"), out.map { it.role })
        assertTrue(out.none { it.content == "A".repeat(2000) }) // 最旧已裁
        assertTrue(out.any { it.content == "E".repeat(2000) }) // 最新仍在
    }

    @Test
    fun `压缩阀值100_同用量一条不裁_旧行为`() {
        val store = storeOf(
            QuroMessage(role = "user", content = "A".repeat(2000)),
            QuroMessage(role = "assistant", content = "B".repeat(800)),
            QuroMessage(role = "user", content = "C".repeat(2000)),
            QuroMessage(role = "assistant", content = "D".repeat(800)),
            QuroMessage(role = "user", content = "E".repeat(2000)),
        )
        val out = store.toLlmMessages(
            system = QuroMessage(role = "system", content = "S"),
            contextWindow = 2000,
            compressThresholdPct = 100,
            softTrimThresholdPct = 50,
        )
        assertEquals(6, out.size) // system + 5 条全留
        assertTrue(out.any { it.content == "A".repeat(2000) })
        assertTrue(out.any { it.role == "system" })
    }

    @Test
    fun `软精简阀值0_低用量也精简_旧行为`() {
        val out = lowUsageStore().toLlmMessages(
            system = sysMsg,
            contextWindow = 4000,
            compressThresholdPct = 100,
            softTrimThresholdPct = 0,
        )
        val tool = out.single { it.role == "tool" }
        assertTrue(tool.content, tool.content.contains("工具输出过长已截断"))
        assertTrue(out.any { m -> m.toolCalls?.any { it.id == "c1" } == true })
    }

    @Test
    fun `压缩裁剪后_保留窗口内的工具轮仍成对`() {
        // 用量 = 1+500+200+500+2+1000 = 2203，窗口 2000 → 110%：先软精简（≥50%）再按 80% 裁最旧
        val store = storeOf(
            QuroMessage(role = "user", content = "A".repeat(2000)),
            QuroMessage(role = "assistant", content = "B".repeat(800)),
            QuroMessage(role = "user", content = "C".repeat(2000)),
            *toolRound("c1", "terminal_exec", BIG).toTypedArray(),
        )
        val out = store.toLlmMessages(
            system = QuroMessage(role = "system", content = "S"),
            contextWindow = 2000,
            compressThresholdPct = 80,
            softTrimThresholdPct = 50,
        )
        // 最旧的 A 被裁；保住的窗口里工具轮整对存活（精简后的形态）
        assertEquals(listOf("system", "assistant", "user", "assistant", "tool"), out.map { it.role })
        assertTrue(out.none { it.content == "A".repeat(2000) })
        val tool = out.single { it.role == "tool" }
        assertTrue(tool.content, tool.content.contains("工具输出过长已截断"))
        assertTrue(out.any { m -> m.toolCalls?.any { it.id == "c1" } == true })
    }

    @Test
    fun `阀值越界自动收敛`() {
        // compress 5 → 收敛到 10（预算仅约 10%，只保最新）；softTrim 150 → 收敛到 100
        val store = storeOf(
            QuroMessage(role = "user", content = "A".repeat(2000)),
            QuroMessage(role = "assistant", content = "B".repeat(800)),
            QuroMessage(role = "user", content = "C".repeat(2000)),
        )
        val out = store.toLlmMessages(
            system = QuroMessage(role = "system", content = "S"),
            contextWindow = 2000,
            compressThresholdPct = 5,
            softTrimThresholdPct = 150,
        )
        assertTrue(out.any { it.content == "C".repeat(2000) }) // 最新强制保留
        assertTrue(out.none { it.content == "A".repeat(2000) }) // 最旧被裁
        assertTrue(out.any { it.role == "system" })
    }
}
