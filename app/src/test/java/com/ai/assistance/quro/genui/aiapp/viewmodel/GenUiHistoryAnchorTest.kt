package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「历史里要不要留模型自己的原文」这条分流的回归测试。
 *
 * ## 为什么这是本轮查出的根因
 *
 * 用户反证：「ZorvAI 对话框体量更大，却可以完整执行，而 Genui Agent 不行」。
 * 这条反证推翻了「提示词体积是根因」的结论 —— 主对话同样全量下发 258 个工具
 * （`QuroToolRouter.PROGRESSIVE = false`），体量一样大却能跑通。
 *
 * 真正的差异是**历史写法**：
 * · 主对话（`QuroChatViewModel`）：`QuroMessage(role="assistant", content=replyText)`
 *   → **原文完整进历史**，模型能延续格式、能自我修正；
 * · GenUI（`78bebff` / 09-23 起）：`historyNote()` 把输出剥成 prose，
 *   历史里只剩「〔已生成：xxx〕」→ 模型**不知道自己上一轮写对了什么** → 每轮盲猜格式
 *   → 「所有类型界面都画不出来」+「有时候模型都不通」。
 *
 * `78bebff` 的动机（防「模型模仿历史里的残缺格式」）是对的，手段（一刀切剥掉全部原文）错了。
 * 现在改成**按是否完整分流**：完整 → 原文照存（最强格式锚点）；残缺 → 才降级 prose。
 *
 * 这些测试钉住分流判据本身，防止再退化回「一刀切剥光」。
 */
class GenUiHistoryAnchorTest {

    /** 一段**完整闭合**的 genui 输出（模型写对了的样本）。 */
    private val completeGenUiOutput = """
        <intent>做一个待办清单</intent>
        <plan>用列布局 + 三个卡片</plan>
        <generate>```genui
{"id":"todo","title":"待办","root":{"type":"column","children":[
  {"type":"text","properties":{"text":"今天做三件事"}},
  {"type":"button","properties":{"label":"添加"},"action":{"type":"intent","name":"add"}}
]}}
```</generate>
    """.trimIndent()

    /** 一段**残缺**输出（围栏未闭合 —— 被 max_tokens 截断，正是 78bebff 要防的场景）。 */
    private val truncatedGenUiOutput = """
        <intent>做一个待办清单</intent>
        <generate>```genui
{"id":"todo","title":"待办","root":{"type":"column","children":[
  {"type":"text","properties":{"text":"今天做三件事"}},
  {"type":"button","properties":{"label":"添
    """.trimIndent()

    // ── 分流判据 ────────────────────────────────────────────────────────

    /**
     * 降级函数用与生产同源的逻辑（`ChatHistory.compressAssistant` 走的是同两条：
     * `foldFences` + `stripJsonBlobs`），单测里不依赖 ViewModel 实例。
     */
    private fun degrade(text: String): String =
        ChatHistory.stripJsonBlobs(ChatHistory.foldFences(text)).trim()

    @Test
    fun `完整输出必须原文进历史而不是剥成prose`() {
        val out = GenUiPromptProbe.historyAssistant(completeGenUiOutput, "待办", complete = true)
        assertEquals(
            "完整输出必须原样进历史（这是模型唯一的格式锚点）",
            completeGenUiOutput.trim(),
            out,
        )
        assertTrue("原文里必须保留 genui 围栏", out.contains("```genui"))
        assertTrue("原文里必须保留 root 键", out.contains("\"root\""))
    }

    @Test
    fun `残缺输出必须降级成prose以免教坏格式`() {
        val out = GenUiPromptProbe.historyAssistant(truncatedGenUiOutput, "待办", complete = false, degrade = ::degrade)
        assertFalse("残缺输出不得把围栏留给模型模仿，实际=$out", out.contains("```genui"))
        assertFalse("残缺输出不得留半截 JSON 当样本，实际=$out", out.contains("\"root\""))
    }

    @Test
    fun `残缺输出里没有可读人话时退回到占位提示`() {
        // 纯围栏 + 纯 JSON，剥完什么都不剩 → 必须给占位提示而不是空串
        val onlyFence = "```genui\n{\"id\":\"x\",\"root\":{\"type\":\"column\",\"children\":[]}}\n```"
        val out = GenUiPromptProbe.historyAssistant(onlyFence, "某界面", complete = false, degrade = ::degrade)
        assertTrue("必须给出占位提示而不是空串，实际=[$out]", out.isNotBlank())
        assertFalse(out.contains("```genui"))
        assertTrue("占位提示必须明确叫模型不要复述，实际=$out", out.contains("不要"))
    }

    @Test
    fun `分流判据只看是否完整不看是不是界面`() {
        // 完整 → 留原文；残缺 → 降级。判据里没有任何「是不是界面」的条件
        assertTrue(GenUiPromptProbe.keepOriginalInHistory(complete = true))
        assertFalse(GenUiPromptProbe.keepOriginalInHistory(complete = false))
    }

    // ── 「原文能不能真的到达模型」── 这是最容易被忽略的一环 ──────────────

    /**
     * 钉住 [ChatHistory.FULL_TURNS] 的语义：**最近 2 轮的原文必须原样进 API 请求**。
     *
     * 如果这一环断了（谁都以为原文留在历史里，但真正发给 API 时被折掉），
     * 上一轮的修复就等于没做 —— 而这种「看起来改了、实际没生效」最难查。
     */
    @Test
    fun `最近两轮的原文必须原样进入API请求不被折叠`() {
        val history = buildList {
            add(GenUIChatMessage("system", "系统提示词"))
            // 3 轮用户交互，每轮的 assistant 都是完整原文
            repeat(3) { i ->
                add(GenUIChatMessage("user", "第${i + 1}个需求"))
                add(GenUIChatMessage("assistant", "第${i + 1}轮原文```genui\n{\"id\":\"a$i\"}\n```"))
            }
        }
        val api = ChatHistory.toApiMessages(history, maxTurns = 10, fullTurns = 2)

        val assistants = api.filter { it.role == "assistant" }
        // 3 轮全在保留范围内（3 ≤ MAX_TURNS），但只有最近 2 轮是「原文轮」
        assertEquals("应保留 3 条 assistant", 3, assistants.size)
        val withFence = assistants.count { it.content.contains("```genui") }
        assertEquals(
            "最近 FULL_TURNS=2 轮必须带原文围栏，更早那轮才折叠",
            2,
            withFence,
        )
        // 更早那轮必须是折叠提示，不能留半截 JSON
        val oldest = assistants.first()
        assertFalse("最老那轮不得保留围栏", oldest.content.contains("```genui"))
        assertFalse("最老那轮不得留半截 JSON 当样本", oldest.content.contains("\"root\""))
    }

    @Test
    fun `系统提示词必须恒为一条且在开头`() {
        val history = buildList {
            add(GenUIChatMessage("system", "系统提示词"))
            add(GenUIChatMessage("user", "需求"))
            add(GenUIChatMessage("assistant", "原文```genui\n{}\n```"))
            // 模拟旧 bug：后面又追加了一条 system
            add(GenUIChatMessage("system", "渲染规则"))
        }
        val api = ChatHistory.toApiMessages(history)
        assertEquals("system 必须只有一条", 1, api.count { it.role == "system" })
        assertEquals("system 必须在开头", "system", api.first().role)
    }

    /**
     * 体积红线：留原文后历史不能失控。
     *
     * `FULL_TURNS=2` 是「原文进请求」与「历史不膨胀」之间的闸门 ——
     * 关掉它两条都不成立，所以钉死这个数不被后人改大。
     */
    @Test
    fun `留原文后历史体积仍受FULL_TURNS闸门约束`() {
        assertTrue(
            "FULL_TURNS 必须 ≤ 3，否则历史里的原文会开始挤占输入预算（实测 FULL_TURNS=${ChatHistory.FULL_TURNS}）",
            ChatHistory.FULL_TURNS <= 3,
        )
        assertTrue(
            "MAX_TURNS 必须 ≥ FULL_TURNS，否则语义矛盾",
            ChatHistory.MAX_TURNS >= ChatHistory.FULL_TURNS,
        )

        // 造 10 轮，每轮 3,000 字符原文 —— 全量保留会是 30,000 字符
        val history = buildList {
            add(GenUIChatMessage("system", "系统"))
            repeat(10) { i ->
                add(GenUIChatMessage("user", "需求$i"))
                add(GenUIChatMessage("assistant", "```genui\n" + "x".repeat(3_000) + "\n```"))
            }
        }
        val api = ChatHistory.toApiMessages(history)
        val full = api.count { it.role == "assistant" && it.content.contains("```genui") }
        assertEquals("只有最近 FULL_TURNS 轮带原文", ChatHistory.FULL_TURNS, full)
    }
}