package com.ai.assistance.quro.core.agent.orchestration

import com.ai.assistance.quro.core.agent.loop.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongHorizonOrchestratorTest {

    private val step = TaskStep("s1", "do it")

    @Test
    fun `with planner and deliverable judge returns Delivered`() = runBlocking {
        val planner = TaskPlanner { _: String, _: String -> TaskPlan(strategy = "拆解目标", steps = listOf(step)) }
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success("完成") }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", planner = planner, stepExecutor = exec, maxIterations = 3,
        )
        assertTrue(out is TaskResult.Delivered)
        assertEquals(1, out.iterations)
    }

    @Test
    fun `no planner uses fallback steps`() = runBlocking {
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success("完成") }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec, maxIterations = 3,
        )
        assertTrue(out is TaskResult.Delivered)
    }

    @Test
    fun `not deliverable first then deliverable continues orchestration`() = runBlocking {
        var execCalls = 0
        var iter = 0
        val judge = DeliverabilityJudge { _: String, _: String, _: String ->
            // 第 1 轮不可交付，第 2 轮可交付
            if (iter < 2) Deliverability.NotDeliverable("未达标", "还差一步", "继续") else Deliverability.Deliverable("通过")
        }
        val exec = suspend { _: TaskStep, it: Int ->
            iter = it
            execCalls++
            ToolOutcome.Success("步骤结果")
        }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec, judge = judge, maxIterations = 4,
        )
        assertTrue(out is TaskResult.Delivered)
        assertEquals(2, out.iterations)
        assertEquals(2, execCalls) // 第 1、2 轮各执行一次
    }

    @Test
    fun `always not deliverable escalates after budget`() = runBlocking {
        var execCalls = 0
        val judge = DeliverabilityJudge { _: String, _: String, _: String ->
            Deliverability.NotDeliverable("始终不达标", "原因", "继续")
        }
        val exec = suspend { _: TaskStep, _: Int ->
            execCalls++
            ToolOutcome.Success("步骤结果")
        }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec, judge = judge, maxIterations = 3,
        )
        assertTrue(out is TaskResult.Escalated)
        assertEquals(3, execCalls)
        assertEquals(3, out.iterations)
    }

    @Test
    fun `step failure still goes through deliverability gate`() = runBlocking {
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Failure(
            com.ai.assistance.quro.core.agent.loop.FailureType.BUSINESS, "业务失败") }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec, maxIterations = 2,
        )
        // AlwaysDeliverable → 即便步骤失败也按"交付"（判定交给上层），编排器只负责串起阶段
        assertTrue(out is TaskResult.Delivered)
    }

    /* ── issue #11 问题6：确定性失败（terminal）立即升级，绝不重跑 ── */

    @Test
    fun `terminal failure escalates immediately without rerunning`() = runBlocking {
        var execCalls = 0
        // 闸门即便判「不可交付」（旧行为会 continue 重跑），也不允许再跑一趟
        val judge = DeliverabilityJudge { _: String, _: String, _: String ->
            Deliverability.NotDeliverable("不可交付", "还差一步", "继续")
        }
        val exec = suspend { _: TaskStep, _: Int ->
            execCalls++
            ToolOutcome.Failure(
                com.ai.assistance.quro.core.agent.loop.FailureType.BUSINESS,
                "⚠️ 本次内容超出模型上下文上限（上游：maximum context length is 8192 tokens）",
                terminal = true,
            )
        }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec, judge = judge, maxIterations = 3,
        )
        assertTrue(out is TaskResult.Escalated)
        assertEquals(1, execCalls)          // 关键：只跑一趟，绝不重跑（界面不再持续转圈）
        assertEquals(1, out.iterations)
        assertTrue((out as TaskResult.Escalated).reason.contains("上下文"))
    }

    @Test
    fun `non-terminal failure still retries via gate`() = runBlocking {
        // 对照组：普通失败（terminal 默认 false）行为不变——闸门打回仍重跑到预算上限，
        // 证明「确定性失败短路」没有把普通失败也一刀切。
        var execCalls = 0
        val judge = DeliverabilityJudge { _: String, _: String, _: String ->
            Deliverability.NotDeliverable("不可交付", "原因", "继续")
        }
        val exec = suspend { _: TaskStep, _: Int ->
            execCalls++
            ToolOutcome.Failure(com.ai.assistance.quro.core.agent.loop.FailureType.BUSINESS, "普通业务失败")
        }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec, judge = judge, maxIterations = 3,
        )
        assertTrue(out is TaskResult.Escalated)
        assertEquals(3, execCalls)
    }
}
