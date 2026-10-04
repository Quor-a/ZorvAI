package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.agent.loop.ClosedLoopExecutor
import com.ai.assistance.quro.core.agent.loop.ExecResult
import com.ai.assistance.quro.core.agent.loop.FailurePolicyRegistry
import com.ai.assistance.quro.core.agent.loop.FailureType
import com.ai.assistance.quro.core.agent.loop.RecoveryAction
import com.ai.assistance.quro.core.agent.loop.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排与容错层：`onModelCorrect` 修正通道（此前完全没接）。
 *
 * ## 背景：为什么这个测试存在
 * [ClosedLoopExecutor] 的失败策略表里 [RecoveryAction.CORRECT] / [ROLLBACK] 是
 * 「让上层修正后重试」与「不应用本次结果」两档。此前 [QuroToolEngine.execute]
 * **不传 onModelCorrect**，于是：
 *  - CORRECT 分支永远走 else「模型修正通道不可用 → 升级」
 *  - ROLLBACK 与 ESCALATE 返回同一个 [ToolOutcome.Failure]
 * **三档塌成一档，按场景自愈的设计等于没生效**，实际只剩「裸重试到 maxAttempts」。
 *
 * 这里的断言逐条钉住「塌缩已解除」：修正指令能到达、CORRECT 立即交还控制权
 * （不再用同一份错参数空转）、ROLLBACK/ESCALATE 行为可区分。
 */
class ClosedLoopCorrectionChannelTest {

    private fun failureOf(type: FailureType, msg: String = "boom") =
        ToolOutcome.Failure(type, msg, attempt = 1)

    // ---------- CORRECT 必须真的到达修正通道 ----------

    @Test
    fun correct_action_reaches_model_correction_callback() = kotlinx.coroutines.runBlocking {
        var reached = 0
        var seenType: FailureType? = null
        val out = ClosedLoopExecutor().dispatch(
            context = null,
            scenario = "tool",
            name = "read_file",
            arguments = """{"path":1}""",
            execOnce = { ExecResult.Failed(FailureType.PARSE, "参数不是合法 JSON") },
            onModelCorrect = { f ->
                reached++
                seenType = f.failureType
                true
            },
        )
        assertEquals("修正通道必须被调用", 1, reached)
        assertEquals(FailureType.PARSE, seenType)
        assertTrue("修正后应交还失败给上层", out is ToolOutcome.Failure)
    }

    @Test
    fun correct_action_does_not_spin_on_same_bad_arguments() = kotlinx.coroutines.runBlocking {
        // 🔴 核心回归：修正需要模型改参数后**重发一次完整调用**。引擎侧拿不到新参数，
        // 若 CORRECT 继续循环，就会用同一份错参数空转到 maxAttempts（纯烧预算）。
        var execCount = 0
        ClosedLoopExecutor().dispatch(
            context = null,
            scenario = "tool",
            name = "read_file",
            arguments = """{"path":1}""",
            execOnce = {
                execCount++
                ExecResult.Failed(FailureType.PARSE, "参数不是合法 JSON")
            },
            onModelCorrect = { true },
        )
        assertEquals("PARSE 场景策略首档即 CORRECT，必须只执行一次", 1, execCount)
    }

    @Test
    fun no_correction_channel_falls_back_to_escalate() = kotlinx.coroutines.runBlocking {
        // 通道为 null 时必须退化为升级（保持旧行为），不得静默成功
        var execCount = 0
        val out = ClosedLoopExecutor().dispatch(
            context = null,
            scenario = "tool",
            name = "read_file",
            arguments = "{}",
            execOnce = {
                execCount++
                ExecResult.Failed(FailureType.PARSE, "坏参数")
            },
            onModelCorrect = null,
        )
        assertTrue(out is ToolOutcome.Failure)
        // PARSE 表是 [CORRECT, ESCALATE]，无通道 → 第一次就升级，不再重试
        assertEquals(1, execCount)
    }

    // ---------- 失败类型真的驱动不同策略 ----------

    @Test
    fun failure_type_drives_different_recovery_actions() {
        val policy = FailurePolicyRegistry.policyFor("tool")
        // PERMISSION 首档即 ESCALATE：重试无用
        assertEquals(RecoveryAction.ESCALATE, policy.decide(FailureType.PERMISSION, 1))
        // TIMEOUT 先重试两次
        assertEquals(RecoveryAction.RETRY, policy.decide(FailureType.TIMEOUT, 1))
        assertEquals(RecoveryAction.RETRY, policy.decide(FailureType.TIMEOUT, 2))
        assertEquals(RecoveryAction.ESCALATE, policy.decide(FailureType.TIMEOUT, 3))
        // PARSE 首档即 CORRECT（修正参数后重试有意义）
        assertEquals(RecoveryAction.CORRECT, policy.decide(FailureType.PARSE, 1))
    }

    @Test
    fun different_failure_types_reach_different_actions_in_dispatch() = kotlinx.coroutines.runBlocking {
        // PERMISSION 走 ESCALATE（不该触发修正通道）
        var permReached = 0
        ClosedLoopExecutor().dispatch(
            context = null, scenario = "tool", name = "read_sms", arguments = "{}",
            execOnce = { ExecResult.Failed(FailureType.PERMISSION, "权限不足") },
            onModelCorrect = { permReached++; true },
        )
        assertEquals("权限类不该进修正通道（重试无用，应直接问用户）", 0, permReached)

        // PARSE 走 CORRECT（进修正通道）
        var parseReached = 0
        ClosedLoopExecutor().dispatch(
            context = null, scenario = "tool", name = "read_file", arguments = "{}",
            execOnce = { ExecResult.Failed(FailureType.PARSE, "参数非法") },
            onModelCorrect = { parseReached++; true },
        )
        assertEquals(1, parseReached)
    }

    // ---------- ROLLBACK 与 ESCALATE 必须可区分 ----------

    @Test
    fun rollback_and_escalate_are_distinguishable() = kotlinx.coroutines.runBlocking {
        // BUSINESS 表：RETRY, RETRY, CORRECT, ROLLBACK, ESCALATE
        // 有通道 → 第 3 次进 CORRECT，闭环立即交还（不空转到 ROLLBACK/ESCALATE）
        var withChannel = 0
        var withChannelExecs = 0
        ClosedLoopExecutor().dispatch(
            context = null, scenario = "tool", name = "op", arguments = "{}",
            execOnce = { withChannelExecs++; ExecResult.Failed(FailureType.BUSINESS, "业务失败") },
            onModelCorrect = { withChannel++; true },
        )
        assertEquals("有通道时第 3 次应命中 CORRECT", 1, withChannel)
        assertEquals("命中 CORRECT 即交还，不该继续空转", 3, withChannelExecs)

        // 通道返回 false（上层表示「这条没法靠模型修正」）→ 必须终止为失败，
        // 而不是像此前那样三档全部收敛成同一个「立即返回失败」。
        var declined = 0
        var declinedExecs = 0
        val out = ClosedLoopExecutor().dispatch(
            context = null, scenario = "tool", name = "op", arguments = "{}",
            execOnce = { declinedExecs++; ExecResult.Failed(FailureType.BUSINESS, "业务失败") },
            onModelCorrect = { declined++; false },
        )
        assertEquals("修正被拒时应恰好调一次通道", 1, declined)
        assertEquals("修正被拒即终止，不继续空转", 3, declinedExecs)
        assertTrue("修正被拒后应终止为失败", out is ToolOutcome.Failure)
    }

    // ---------- 终态与预算 ----------

    @Test
    fun max_attempts_is_still_enforced() = kotlinx.coroutines.runBlocking {
        // 修正确实不空转了，但裸重试路径的 maxAttempts 上限必须仍在（防失控的最后一道）
        var execCount = 0
        val out = ClosedLoopExecutor().dispatch(
            context = null, scenario = "tool", name = "net", arguments = "{}",
            execOnce = {
                execCount++
                ExecResult.Failed(FailureType.TRANSPORT, "网络抖动")
            },
            onModelCorrect = null,
        )
        val policy = FailurePolicyRegistry.policyFor("tool")
        assertTrue("执行次数不得超过 maxAttempts", execCount <= policy.maxAttempts)
        assertTrue(out is ToolOutcome.Failure)
    }

    @Test
    fun terminal_failure_does_not_enter_correction() = kotlinx.coroutines.runBlocking {
        // Terminal（未知工具 / 权限缺失无法弹窗）不应重试也不该进修正
        var execCount = 0
        var reached = 0
        val out = ClosedLoopExecutor().dispatch(
            context = null, scenario = "tool", name = "ghost", arguments = "{}",
            execOnce = {
                execCount++
                ExecResult.Terminal(FailureType.PERMISSION, "工具不存在")
            },
            onModelCorrect = { reached++; true },
        )
        assertEquals(1, execCount)
        assertEquals(0, reached)
        assertTrue(out is ToolOutcome.Failure)
    }

    @Test
    fun success_never_enters_correction_channel() = kotlinx.coroutines.runBlocking {
        var reached = 0
        val out = ClosedLoopExecutor().dispatch(
            context = null, scenario = "tool", name = "ok_tool", arguments = "{}",
            execOnce = { ExecResult.Completed("成功输出") },
            onModelCorrect = { reached++; true },
        )
        assertEquals(0, reached)
        assertTrue(out is ToolOutcome.Success)
    }

    @Test
    fun retry_then_escalate_preserves_original_message() = kotlinx.coroutines.runBlocking {
        // 重试耗尽后交还的失败必须带原始原因，不能被吞成空消息
        val out = ClosedLoopExecutor().dispatch(
            context = null, scenario = "tool", name = "net", arguments = "{}",
            execOnce = { ExecResult.Failed(FailureType.TIMEOUT, "连接超时 30s") },
            onModelCorrect = null,
        )
        assertTrue(out is ToolOutcome.Failure)
        val f = out as ToolOutcome.Failure
        assertTrue("必须保留原始原因：${f.message}", f.message.contains("超时"))
        assertNotEquals(FailureType.UNKNOWN, f.failureType)
    }
}
