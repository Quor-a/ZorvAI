package com.ai.assistance.quro.core.agent.orchestration

import com.ai.assistance.quro.core.agent.loop.FailureType
import com.ai.assistance.quro.core.agent.loop.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排层：**`runTask` 真正接进主循环后**的任务级闭环行为。
 *
 * ## 这个测试在守什么
 * `LongHorizonOrchestrator.runTask` 此前全仓零调用（死代码），而`ask()` 内自己实现了
 * 一套补丁式重试。接线后，`runTask` 成了「策划 + 交付闸门 + 重规划」的唯一归属，
 * 于是下面几条就成了整个Agent 编排的承重墙：
 *
 * 1. **闸门判的是产物原文**，不是带`OK: ` 前缀且被 `take(200)` 截断的诊断摘要
 *    —— 否则闸门永久失明（`startsWith("工具执行失败")` 永远不成立）。
 * 2. **第 2 轮起`plan()` 拿到记忆**：打回原因 + 修正建议 + 最近失败步骤。
 *    首轮必须是**空串**（否则每次普通问答的策划提示都塞无用前缀）。
 * 3. 外部记忆（工具层失败明细）能经 `extraPlanContext` 追加进去。
 * 4. 超预算 → `Escalated`，且**仍带最后一趟产物**（不能把用户的话弄丢）。
 */
class LongHorizonWiringTest {

    private val step = TaskStep("s1", "do it")

    // ── 1. 闸门必须看到产物原文 ────────────────────────────────────────

    @Test
    fun `judge sees raw product not the OK-prefixed truncated summary`() = runBlocking {
        // 一段「以失败回执开头」的产物：闸门必须判不可交付。
        // 若传进去的是 `OK: ${raw.take(200)}`，startsWith 永远不成立 → 闸门失明。
        val bad = "工具执行失败：read_file 权限不足"
        var judgedAnswer: String? = null
        val judge = DeliverabilityJudge { _, answer, _ ->
            judgedAnswer = answer
            Deliverability.Deliverable("捕获")
        }
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success(bad) }

        LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec,
            judge = judge, maxIterations = 1,
        )
        assertEquals("闸门必须拿到未加前缀的产物原文", bad, judgedAnswer)
    }

    @Test
    fun `long product is not truncated before judging`() = runBlocking {
        // 失败短语在第 3000 字符处（远超 take(200)）：闸门判「开头是否失败回执」需要全文。
        val long = "x".repeat(3000) + "工具执行失败"
        var judgedAnswer: String? = null
        val judge = DeliverabilityJudge { _, answer, _ ->
            judgedAnswer = answer
            Deliverability.Deliverable("捕获")
        }
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success(long) }

        LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec,
            judge = judge, maxIterations = 1,
        )
        assertEquals("判定输入不应被截断", long.length, judgedAnswer?.length)
    }

    @Test
    fun `heuristic judge actually rejects a real failure receipt via runTask`() = runBlocking {
        // 端到端：默认启发式闸门 + 产物是失败回执 → 判定不可交付 → 走重规划而非直接交付。
        var planCalls = 0
        val planner = TaskPlanner { _, _ -> planCalls++; TaskPlan(strategy = "s") }
        val exec = suspend { _: TaskStep, _: Int ->
            ToolOutcome.Success("工具执行失败：read_file 权限不足")
        }

        val out = LongHorizonOrchestrator().runTask(
            null, "任务", planner = planner, steps = listOf(step), stepExecutor = exec,
            judge = HeuristicDeliverabilityJudge, maxIterations = 2,
        )
        assertTrue("失败回执不可交付", out is TaskResult.Escalated)
        assertEquals("必须重新策划过", 2, planCalls)
    }

    @Test
    fun `failure outcome product is judged by its message not dropped`() = runBlocking {
        // 步骤是Failure：产物取 message。若取错成空串，闸门会把「失败」判成「产物为空」，
        // 掩盖真实原因。
        var judgedAnswer: String? = null
        val judge = DeliverabilityJudge { _, answer, _ ->
            judgedAnswer = answer
            Deliverability.Deliverable("捕获")
        }
        val exec = suspend { _: TaskStep, _: Int ->
            ToolOutcome.Failure(FailureType.BUSINESS, "业务失败：文件不存在")
        }

        LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec,
            judge = judge, maxIterations = 1,
        )
        assertEquals("业务失败：文件不存在", judgedAnswer)
    }

    // ── 2. 重规划必须带记忆 ────────────────────────────────────────────

    @Test
    fun `first round planner gets empty context`() = runBlocking {
        // 首轮零噪声：gate/failures/external 皆空 → 空串。
        assertEquals("", LongHorizonOrchestrator.buildPlanContext(null, null, null))
        assertEquals("", LongHorizonOrchestrator.buildPlanContext(null, "", "   "))
    }

    @Test
    fun `second round planner gets gate reason suggestion and failures`() = runBlocking {
        val contexts = mutableListOf<String>()
        val planner = TaskPlanner { _, ctx -> contexts.add(ctx); TaskPlan(strategy = "s") }
        val judge = DeliverabilityJudge { _, _, _ ->
            if (contexts.size < 3) Deliverability.NotDeliverable("产物为空", "没有实质内容", "先给结论")
            else Deliverability.Deliverable("通过")
        }
        val exec = suspend { _: TaskStep, _: Int ->
            if (contexts.size < 3) ToolOutcome.Failure(FailureType.PARSE, "参数解析失败")
            else ToolOutcome.Success("最终答案")
        }

        val out = LongHorizonOrchestrator().runTask(
            null, "任务", planner = planner, steps = listOf(step), stepExecutor = exec,
            judge = judge, maxIterations = 4,
        )
        assertTrue(out is TaskResult.Delivered)
        assertTrue("应有多轮策划", contexts.size >= 2)
        val second = contexts[1]
        assertTrue("带上打回原因：$second", second.contains("没有实质内容"))
        assertTrue("带上修正建议：$second", second.contains("先给结论"))
        assertTrue("带上最近失败：$second", second.contains("参数解析失败"))
        assertTrue("以换行收尾", second.endsWith("\n"))
    }

    @Test
    fun `external memory is appended into planner context`() = runBlocking {
        val contexts = mutableListOf<String>()
        val planner = TaskPlanner { _, ctx -> contexts.add(ctx); TaskPlan(strategy = "s") }
        val judge = DeliverabilityJudge { _, _, _ ->
            if (contexts.size < 2) Deliverability.NotDeliverable("产物含失败标记", "重试", null)
            else Deliverability.Deliverable("通过")
        }
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success("答") }

        LongHorizonOrchestrator().runTask(
            null, "任务", planner = planner, steps = listOf(step), stepExecutor = exec,
            judge = judge, maxIterations = 3,
            extraPlanContext = { "最近工具失败：terminal_exec 退出码 1\n" },
        )
        assertTrue("外部记忆应注入：${contexts.getOrNull(1)}", contexts[1].contains("terminal_exec"))
    }

    @Test
    fun `external memory without trailing newline is still line terminated`() = runBlocking {
        val ctx = LongHorizonOrchestrator.buildPlanContext(null, null, "外部记忆")
        assertTrue("必须以换行收尾，否则 planner 追加的内容会粘在最后一行", ctx.endsWith("\n"))
    }

    // ── 3. 超预算不得丢产物 ────────────────────────────────────────────

    @Test
    fun `escalated still carries last product`() = runBlocking {
        // 始终不可交付：Escalated 必须**仍带最后一趟产物**，
        // 否则用户看到的是「Agent 跑完但什么都没说」。
        val judge = DeliverabilityJudge { _, _, _ ->
            Deliverability.NotDeliverable("始终不达标", "原因", "继续")
        }
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success("我尽力产出的东西") }

        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec,
            judge = judge, maxIterations = 3,
        )
        assertTrue(out is TaskResult.Escalated)
        assertEquals("我尽力产出的东西", out.finalSummary)
        assertEquals("原因", (out as TaskResult.Escalated).reason)
    }

    @Test
    fun `delivered product is exactly what step produced`() = runBlocking {
        val answer = "这是最终答复"
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success(answer) }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec, maxIterations = 2,
        )
        assertTrue(out is TaskResult.Delivered)
        assertEquals(answer, out.finalSummary)
    }

    @Test
    fun `planner is not called when it is null`() = runBlocking {
        // planner=null → 跳过策划，直接执行（不能 NPE）。
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success("ok") }
        val out = LongHorizonOrchestrator().runTask(
            null, "任务", steps = listOf(step), stepExecutor = exec, maxIterations = 2,
        )
        assertTrue(out is TaskResult.Delivered)
    }

    @Test
    fun `empty plan context is not injected as noise on first round`() = runBlocking {
        val contexts = mutableListOf<String>()
        val planner = TaskPlanner { _, ctx -> contexts.add(ctx); TaskPlan(strategy = "s") }
        val exec = suspend { _: TaskStep, _: Int -> ToolOutcome.Success("好") }

        LongHorizonOrchestrator().runTask(
            null, "任务", planner = planner, steps = listOf(step),
            stepExecutor = exec, maxIterations = 1,
        )
        assertEquals("首轮策划上下文必须零噪声", "", contexts[0])
        assertFalse(contexts[0].contains("最近工具失败"))
    }
}