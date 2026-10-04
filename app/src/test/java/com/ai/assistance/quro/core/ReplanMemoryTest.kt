package com.ai.assistance.quro.core

import com.ai.assistance.quro.core.agent.orchestration.Deliverability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排层：**重规划必须带记忆**。
 *
 * ## 这个测试在守什么
 * 此前 `QuroAssistant.ask()` 里是 `taskPlanner.plan(brief, "")` —— context 恒为空串，
 * 且策划只在 `round == 1` 触发。于是：
 *  - 交付闸门把不可交付的答复打回后，**根本不会重新规划**（continue 只是再跑一轮 ReAct）；
 *  - 即使重跑，策划也拿不到「上一轮为什么被判不可交付」，重规划是盲的 ——
 *    模型不知道哪里错了，只会换个说法再答一遍同样的错。
 *
 * 现在：闸门打回会记下原因（`lastGateReason`），下一轮 `plan()` 拿到它；
 * [QuroAssistant.buildPlanContext] 是纯函数，逐条断言它真的把记忆拼进去了。
 */
class ReplanMemoryTest {

    private fun gate(reason: String, suggestion: String? = "改用更小的范围重试") =
        Deliverability.NotDeliverable("闸门摘要", reason, suggestion)

    @Test
    fun empty_context_on_first_round_keeps_zero_noise() {
        // 首轮既没被闸门打回、也没有工具失败 → 必须返回空串，
        // 否则每次普通问答的策划提示里都塞一段无用前缀。
        assertEquals("", QuroAssistant.buildPlanContext(null, null))
        assertEquals("", QuroAssistant.buildPlanContext(null, ""))
        assertEquals("", QuroAssistant.buildPlanContext(null, "   "))
    }

    @Test
    fun gate_reason_is_carried_into_replan_context() {
        val ctx = QuroAssistant.buildPlanContext(gate("产物为空"), null)
        assertTrue("必须带上不可交付原因", ctx.contains("产物为空"))
        assertTrue("必须带修正建议", ctx.contains("改用更小的范围重试"))
        assertTrue("必须标明来源是交付闸门", ctx.contains("交付闸门"))
    }

    @Test
    fun gate_without_suggestion_still_works() {
        val ctx = QuroAssistant.buildPlanContext(
            Deliverability.NotDeliverable("摘要", "产物含失败标记", null),
            null,
        )
        assertTrue(ctx.contains("产物含失败标记"))
        assertFalse("无建议时不该留空建议尾巴", ctx.contains("建议："))
    }

    @Test
    fun recent_tool_failures_are_carried_into_replan_context() {
        val ctx = QuroAssistant.buildPlanContext(null, "read_file: 权限不足 | http_request: 超时")
        assertTrue(ctx.contains("最近工具失败"))
        assertTrue(ctx.contains("read_file"))
        assertTrue(ctx.contains("http_request"))
    }

    @Test
    fun both_signals_are_present_together() {
        // 闸门打回 + 工具失败同时存在时，两段都要在（缺一段重规划就是半盲的）
        val ctx = QuroAssistant.buildPlanContext(
            gate("产物含失败标记"),
            "read_file: 权限不足",
        )
        assertTrue("闸门段", ctx.contains("交付闸门"))
        assertTrue("工具失败段", ctx.contains("最近工具失败"))
        assertTrue("闸门在工具失败之前（先说为什么被打回）",
            ctx.indexOf("交付闸门") < ctx.indexOf("最近工具失败"))
    }

    @Test
    fun context_ends_with_newline_so_planner_can_append() {
        // 拼接给 planner 的上下文必须以换行收尾，否则它自己追加的内容会粘在最后一行
        val ctx = QuroAssistant.buildPlanContext(gate("x"), "y")
        assertTrue(ctx.endsWith("\n"))
    }
}
