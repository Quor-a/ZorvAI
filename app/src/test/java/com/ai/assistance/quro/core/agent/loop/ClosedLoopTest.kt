package com.ai.assistance.quro.core.agent.loop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedLoopTest {

    @Test
    fun `default policy decides escalate for permission`() {
        val p = ScenarioFailurePolicy("default")
        assertEquals(RecoveryAction.ESCALATE, p.decide(FailureType.PERMISSION, 1))
    }

    @Test
    fun `business failure retries first then escalates when no correct channel`() {
        val p = ScenarioFailurePolicy("default")
        assertEquals(RecoveryAction.RETRY, p.decide(FailureType.BUSINESS, 1))
        assertEquals(RecoveryAction.RETRY, p.decide(FailureType.BUSINESS, 2))
        // 第 3 次默认 CORRECT，但无修正通道时退化为 ESCALATE（由 executor 处理）
        assertEquals(RecoveryAction.CORRECT, p.decide(FailureType.BUSINESS, 3))
    }

    @Test
    fun `timeout retries twice then escalates`() {
        val p = ScenarioFailurePolicy("default")
        assertEquals(RecoveryAction.RETRY, p.decide(FailureType.TIMEOUT, 1))
        assertEquals(RecoveryAction.RETRY, p.decide(FailureType.TIMEOUT, 2))
        assertEquals(RecoveryAction.ESCALATE, p.decide(FailureType.TIMEOUT, 3))
    }

    @Test
    fun `unknown tool is terminal and never retried`() = runBlocking {
        val exec = suspend { _: Int -> ExecResult.Terminal(FailureType.UNKNOWN, "未知工具: foo") }
        val out = ClosedLoopExecutor().dispatch(null, "tool", "foo", "{}", exec)
        assertTrue(out is ToolOutcome.Failure)
        assertEquals(FailureType.UNKNOWN, (out as ToolOutcome.Failure).failureType)
    }

    @Test
    fun `business failure retries then succeeds`() = runBlocking {
        var calls = 0
        val exec = suspend { _: Int ->
            calls++
            if (calls < 2) ExecResult.Failed(FailureType.BUSINESS, "临时业务失败")
            else ExecResult.Completed("OK 数据")
        }
        val out = ClosedLoopExecutor().dispatch(null, "tool", "t", "{}", exec)
        assertTrue(out is ToolOutcome.Success)
        assertEquals("OK 数据", (out as ToolOutcome.Success).raw)
        assertEquals(2, calls) // 第 1 次失败→重试，第 2 次成功
    }

    @Test
    fun `timeout failing repeatedly escalates after budget`() = runBlocking {
        var calls = 0
        val exec = suspend { _: Int ->
            calls++
            ExecResult.Failed(FailureType.TIMEOUT, "超时")
        }
        val out = ClosedLoopExecutor().dispatch(null, "tool", "t", "{}", exec)
        assertTrue(out is ToolOutcome.Failure)
        assertEquals(FailureType.TIMEOUT, (out as ToolOutcome.Failure).failureType)
        // 默认 TIMEOUT 序列 [RETRY, RETRY, ESCALATE]，故共执行 3 次后升级
        assertEquals(3, calls)
    }

    @Test
    fun `empty result is caught by verifier and escalates`() = runBlocking {
        val exec = suspend { _: Int -> ExecResult.Completed("") }
        val out = ClosedLoopExecutor().dispatch(null, "tool", "t", "{}", exec)
        assertTrue(out is ToolOutcome.Failure)
        assertEquals(FailureType.BUSINESS, (out as ToolOutcome.Failure).failureType)
    }
}
