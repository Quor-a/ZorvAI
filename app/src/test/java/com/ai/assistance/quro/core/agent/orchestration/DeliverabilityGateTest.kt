package com.ai.assistance.quro.core.agent.orchestration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 认知决策层：交付闸门 + 默认策划器。
 *
 * 闸门在 [com.ai.assistance.quro.core.QuroAssistant] 里已**常驻生效**（不再是可关开关），
 * 所以这里的测试重点不是「判得准」，而是**「绝不误杀正常答复」**——
 * 一次误杀 = 白烧 2~3 轮 LLM，还可能把正确结论换成更差的版本。
 */
class DeliverabilityGateTest {

    private fun judgeOf(answer: String): Deliverability =
        HeuristicDeliverabilityJudge.judge("随便什么任务", answer, "")

    // ---------- 不可交付 ----------

    @Test
    fun empty_answer_is_not_deliverable() {
        assertTrue(judgeOf("") is Deliverability.NotDeliverable)
        assertTrue(judgeOf("   \n  ") is Deliverability.NotDeliverable)
    }

    @Test
    fun placeholder_answer_is_not_deliverable() {
        val v = judgeOf("(已思考完毕)")
        assertTrue(v is Deliverability.NotDeliverable)
        assertEquals("产物为空", (v as Deliverability.NotDeliverable).summary)
    }

    @Test
    fun bare_failure_echo_is_not_deliverable() {
        // 典型失败回执：短、且失败短语就在开头 —— 这才是真正的「产物不可交付」
        for (echo in listOf(
            "工具执行失败",
            "工具执行超时",
            "工具执行异常",
            "未知工具",
            "需要权限",
        )) {
            val v = judgeOf(echo)
            assertTrue("「$echo」应判不可交付", v is Deliverability.NotDeliverable)
        }
    }

    @Test
    fun short_answer_starting_with_failure_marker_is_not_deliverable() {
        val v = judgeOf("工具执行失败：请检查网络后重试。")
        assertTrue(v is Deliverability.NotDeliverable)
        assertTrue((v as Deliverability.NotDeliverable).suggestion!!.isNotEmpty())
    }

    // ---------- 🔴 回归：绝不误杀 ----------

    @Test
    fun explanation_mentioning_failure_must_still_be_deliverable() {
        // 真实场景：模型向用户解释「刚才失败了，我换了个方式，成功了」。
        // 这段是**完全可交付**的答复，只是叙述里提到了失败 ——
        // 若按 contains 判会被打回重做，白烧 2~3 轮 LLM。
        val good = "刚才那个工具执行失败了，原因是目录不存在。我改用 ls 确认路径后重试，已成功拿到结果。"
        assertTrue(
            "解释性答复不得被判不可交付",
            judgeOf(good) is Deliverability.Deliverable,
        )
    }

    @Test
    fun recovered_signal_always_passes_regardless_of_length() {
        val long = buildString { repeat(30) { append("步骤说明。") } } + " 已成功"
        assertTrue(judgeOf(long) is Deliverability.Deliverable)
        for (sig in listOf("已成功", "已恢复", "重试成功", "已修复", "改用")) {
            val v = judgeOf("工具执行失败，$sig")
            assertTrue("含「$sig」应放行", v is Deliverability.Deliverable)
        }
    }

    @Test
    fun long_explanation_starting_with_failure_marker_is_deliverable() {
        // 开头有失败词但展开了 200+ 字 → 模型在讲解，不是在甩回执
        val detailed = "工具执行失败" + "，详细原因如下：" + "x".repeat(300)
        assertTrue(judgeOf(detailed) is Deliverability.Deliverable)
    }

    @Test
    fun normal_answers_pass() {
        for (a in listOf(
            "你好！今天想做什么？",
            "已经帮你把 README 重写完了，中英文两个版本，主 README 是功能与架构总纲。",
            "结果如下：\n1. 构建通过\n2. 19 个基线失败未新增",
            // 含「需要权限」但不在开头 → 必须放行
            "这个目录读不了，需要权限才能访问，你可以在设置里授权。",
        )) {
            assertTrue("正常答复被误杀：$a", judgeOf(a) is Deliverability.Deliverable)
        }
    }

    // ---------- AlwaysDeliverable 仍是彻底放行 ----------

    @Test
    fun always_deliverable_never_blocks() {
        assertTrue(AlwaysDeliverable.judge("t", "", "") is Deliverability.Deliverable)
        assertTrue(AlwaysDeliverable.judge("t", "工具执行失败", "") is Deliverability.Deliverable)
    }

    @Test
    fun custom_judge_can_be_injected() {
        val strict = DeliverabilityJudge { _, _, _ ->
            Deliverability.NotDeliverable("太短了", "产物过短", "补充细节")
        }
        val v = strict.judge("brief", "短", "")
        assertTrue(v is Deliverability.NotDeliverable)
        // NotDeliverable 的构造顺序是 (summary, reason, suggestion)
        assertEquals("太短了", (v as Deliverability.NotDeliverable).summary)
    }

    // ---------- 默认策划器 ----------

    @Test
    fun default_planner_asks_the_three_questions() = runBlocking {
        val p = DefaultTaskPlanner.plan("帮我把这个 APK 装到手机上", "")
        assertEquals("目标：帮我把这个 APK 装到手机上", p.strategy)
        assertTrue("必须问「怎么验证」", p.plan.contains("怎么验证"))
        assertTrue("必须问「依赖什么」", p.plan.contains("依赖什么"))
        assertTrue("必须问「要做什么」", p.plan.contains("要做什么"))
    }

    @Test
    fun default_planner_does_not_fabricate_steps_or_design() = runBlocking {
        // 本地实现没有工具目录与代码上下文，编步骤/设计只会误导模型
        val p = DefaultTaskPlanner.plan("改一下登录逻辑", "")
        assertTrue("不得编造步骤", p.steps.isEmpty())
        assertEquals("不得编造设计", "", p.design)
    }

    @Test
    fun default_planner_handles_empty_brief() = runBlocking {
        val p = DefaultTaskPlanner.plan("   ", "")
        assertEquals("", p.strategy)
    }
}
