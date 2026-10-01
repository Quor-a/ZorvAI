package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死「云端思考控制参数」的编译口径。
 *
 * 背景：改造前「深度思考」开关**只往 system prompt 塞文本**，请求体与关闭时逐字节相同
 * （见 `QuroAssistant.buildDeepThinkDirective` 的原注释自述）。本测试把这个开关
 * 真正落到 API 参数上的行为钉死，防止再次退化成「只影响提示词」。
 *
 * 另一个重点是**不误伤**：未识别的模型必须一个字段都不发（与改造前完全一致），
 * 宁可少传也不能因为赌错把 400 甩给用户。
 */
class QuroReasoningControlTest {

    private val vllmUrl = "https://vllm.internal:8000/v1"
    private val dashScopeUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val openAiUrl = "https://api.openai.com/v1"

    private fun plan(
        model: String,
        level: QuroReasoningControl.ThinkingLevel,
        baseUrl: String = openAiUrl,
        maxTokens: Int = 32768,
    ) = QuroReasoningControl.plan("OPENAI", baseUrl, model, level, maxTokens)

    // ───────────────────────── 家族识别 ─────────────────────────

    @Test
    fun `openai o series and gpt-5 map to effort family`() {
        assertEquals(
            QuroReasoningControl.Family.OPENAI_EFFORT,
            QuroReasoningControl.detectFamily("OPENAI", openAiUrl, "o3-mini"),
        )
        assertEquals(
            QuroReasoningControl.Family.OPENAI_EFFORT,
            QuroReasoningControl.detectFamily("OPENAI", openAiUrl, "gpt-5.1"),
        )
        assertEquals(
            QuroReasoningControl.Family.OPENAI_EFFORT,
            QuroReasoningControl.detectFamily("OPENAI", openAiUrl, "gpt5"),
        )
    }

    /** 中转常把模型名写成 `anthropic/claude-*`，只按 model 判会漏。 */
    @Test
    fun `anthropic detected from model name or endpoint`() {
        assertEquals(
            QuroReasoningControl.Family.ANTHROPIC_THINKING,
            QuroReasoningControl.detectFamily("OTHER", "https://api.anthropic.com/v1", "claude-sonnet-4-6"),
        )
        assertEquals(
            QuroReasoningControl.Family.ANTHROPIC_THINKING,
            QuroReasoningControl.detectFamily("OTHER", "https://relay.example/v1", "anthropic/claude-opus-4"),
        )
    }

    /**
     * 🔴 同一模型名、不同端点，字段完全不同：
     * 百炼兼容端点只认顶层 `enable_thinking`，vLLM/SGLang 只认 `chat_template_kwargs`。
     * 发错那一侧会被**静默忽略**（不报错也不生效），比报错更难查。
     */
    @Test
    fun `qwen3 family depends on endpoint not just model name`() {
        assertEquals(
            QuroReasoningControl.Family.QWEN3_TEMPLATE,
            QuroReasoningControl.detectFamily("OTHER", vllmUrl, "qwen3-8b"),
        )
        assertEquals(
            QuroReasoningControl.Family.DASHSCOPE_TOGGLE,
            QuroReasoningControl.detectFamily("OTHER", dashScopeUrl, "qwen3-235b-a22b"),
        )
    }

    @Test
    fun `always-on reasoners are detected`() {
        for (m in listOf("deepseek-r1", "deepseek-reasoner", "QwQ-32B", "glm-z1-air")) {
            assertEquals(
                "model=$m",
                QuroReasoningControl.Family.ALWAYS_ON,
                QuroReasoningControl.detectFamily("OPENAI", openAiUrl, m),
            )
        }
    }

    /** 普通模型必须归 NONE —— 这是「不误伤」的第一道闸。 */
    @Test
    fun `plain chat models fall back to none`() {
        for (m in listOf("gpt-4o-mini", "gpt-4.1", "qwen2.5-7b-instruct", "llama-3.3-70b")) {
            assertEquals(
                "model=$m",
                QuroReasoningControl.Family.NONE,
                QuroReasoningControl.detectFamily("OPENAI", openAiUrl, m),
            )
        }
    }

    // ───────────────────────── 互斥铁律 ─────────────────────────

    /**
     * 每个家族只走**一种**思考表达方式，混用会被上游判 400：
     * OpenAI 系 → 顶层 `reasoning_effort`；Anthropic → `thinking` 对象；
     * Qwen3 → `chat_template_kwargs`；DashScope → 顶层 `enable_thinking`。
     *
     * 穷举「家族 × 端点 × 档位」的全组合，保证将来加分支时不会一次发出两种协议。
     */
    @Test
    fun `each family emits exactly one reasoning protocol`() {
        val models = listOf(
            "o3-mini", "gpt-5.1", "claude-sonnet-4-6", "anthropic/claude-opus-4",
            "qwen3-8b", "qwen3-235b-a22b", "deepseek-r1", "gpt-4o-mini",
        )
        val urls = listOf(openAiUrl, vllmUrl, dashScopeUrl, "https://api.anthropic.com/v1")
        for (m in models) {
            for (u in urls) {
                for (lv in QuroReasoningControl.ThinkingLevel.values()) {
                    val p = QuroReasoningControl.plan("OPENAI", u, m, lv, 32768)
                    val kinds = listOfNotNull(
                        p.reasoningEffort?.let { "reasoning_effort" },
                        p.thinkingType?.let { "thinking" },
                        p.chatTemplateEnableThinking?.let { "chat_template_kwargs" },
                        p.topLevelEnableThinking?.let { "enable_thinking" },
                    )
                    assertTrue(
                        "model=$m url=$u level=$lv 同时下发了多种协议（会 400）：$kinds",
                        kinds.size <= 1,
                    )
                }
            }
        }
    }

    // ───────────────────────── OpenAI ─────────────────────────

    @Test
    fun `openai emits explicit effort and suppresses temperature`() {
        val off = plan("o3-mini", QuroReasoningControl.ThinkingLevel.OFF)
        assertEquals("none", off.reasoningEffort)
        assertTrue(off.suppressTemperature)
        assertTrue(off.useMaxCompletionTokens)
        assertTrue(off.controlsReasoning)

        assertEquals("low", plan("gpt-5.1", QuroReasoningControl.ThinkingLevel.LOW).reasoningEffort)
        assertEquals("medium", plan("gpt-5.1", QuroReasoningControl.ThinkingLevel.MEDIUM).reasoningEffort)
        assertEquals("high", plan("gpt-5.1", QuroReasoningControl.ThinkingLevel.HIGH).reasoningEffort)
    }

    /** GPT-5 系同样不接受 temperature —— 改造前只有 `^o[0-9]` 被拦住，GPT-5 全系漏网。 */
    @Test
    fun `gpt-5 also suppresses temperature and uses max_completion_tokens`() {
        val p = plan("gpt-5.1", QuroReasoningControl.ThinkingLevel.MEDIUM)
        assertTrue(p.suppressTemperature)
        assertTrue(p.useMaxCompletionTokens)
    }

    // ───────────────────────── Anthropic ─────────────────────────

    @Test
    fun `anthropic off sends nothing because default is off`() {
        val p = plan("claude-sonnet-4-6", QuroReasoningControl.ThinkingLevel.OFF)
        assertNull(p.thinkingType)
        assertFalse(p.controlsReasoning)
        // 不发 thinking 就不必省略 temperature —— 保持既有行为，避免过度改动。
        assertFalse(p.suppressTemperature)
        assertTrue(p.notes.any { it.contains("默认不开思考") })
    }

    @Test
    fun `anthropic budget stays within hard limits`() {
        val maxTokens = 32768
        val p = plan("claude-sonnet-4-6", QuroReasoningControl.ThinkingLevel.HIGH, maxTokens = maxTokens)

        assertEquals("enabled", p.thinkingType)
        val budget = p.thinkingBudgetTokens!!
        assertTrue("budget=$budget 必须 >= 1024", budget >= 1024)
        assertTrue("budget=$budget 必须 < max_tokens=$maxTokens", budget < maxTokens)
        assertTrue("开启 thinking 后 Claude 不接受 temperature", p.suppressTemperature)
    }

    /** `max_tokens` 太小时钳不出合法 budget —— 必须**放弃下发**，而不是发一个必被 400 的值。 */
    @Test
    fun `anthropic skips thinking when budget cannot satisfy constraints`() {
        val p = plan("claude-sonnet-4-6", QuroReasoningControl.ThinkingLevel.HIGH, maxTokens = 1500)
        assertNull(p.thinkingType)
        assertNull(p.thinkingBudgetTokens)
        assertFalse(p.controlsReasoning)
        assertTrue(p.notes.any { it.contains("太小") })
    }

    // ───────────────────────── Qwen3 / DashScope ─────────────────────────

    /**
     * 🔴 Qwen3 **默认开启思考**。用户关开关时，不下发 `enable_thinking=false` 就等于没关 ——
     * 这是「关了还在思考」这条用户反馈的直接根因。
     */
    @Test
    fun `qwen3 off must explicitly disable thinking`() {
        val off = QuroReasoningControl.plan(
            "OTHER", vllmUrl, "qwen3-8b", QuroReasoningControl.ThinkingLevel.OFF, 32768,
        )
        assertEquals(false, off.chatTemplateEnableThinking)
        assertTrue(off.controlsReasoning)
        assertTrue(off.notes.any { it.contains("默认开思考") })

        val on = QuroReasoningControl.plan(
            "OTHER", vllmUrl, "qwen3-8b", QuroReasoningControl.ThinkingLevel.HIGH, 32768,
        )
        assertEquals(true, on.chatTemplateEnableThinking)
    }

    @Test
    fun `dashscope uses top level enable_thinking`() {
        val off = QuroReasoningControl.plan(
            "OTHER", dashScopeUrl, "qwen3-235b-a22b", QuroReasoningControl.ThinkingLevel.OFF, 32768,
        )
        assertEquals(false, off.topLevelEnableThinking)
        assertNull(off.chatTemplateEnableThinking)

        val on = QuroReasoningControl.plan(
            "OTHER", dashScopeUrl, "qwen3-235b-a22b", QuroReasoningControl.ThinkingLevel.MEDIUM, 32768,
        )
        assertEquals(true, on.topLevelEnableThinking)
    }

    // ───────────────────────── 恒思考 / 未识别 ─────────────────────────

    /** 恒思考模型没有开关可拨 —— 不能假装「关」已生效，必须在 notes 里说明。 */
    @Test
    fun `always on reasoner reports that off is not honourable`() {
        val p = plan("deepseek-r1", QuroReasoningControl.ThinkingLevel.OFF)
        assertFalse(p.controlsReasoning)
        assertTrue(p.notes.any { it.contains("无法关闭") })
    }

    @Test
    fun `unknown model sends nothing at all`() {
        val p = plan("some-unknown-7b", QuroReasoningControl.ThinkingLevel.HIGH)
        assertEquals(QuroReasoningControl.Family.NONE, p.family)
        assertFalse(p.controlsReasoning)
        assertFalse(p.suppressTemperature)
        assertFalse(p.useMaxCompletionTokens)
        assertTrue(p.notes.any { it.contains("未识别") })
    }

    // ───────────────────────── 开关映射 ─────────────────────────

    /**
     * 开关文案是「更慢但更深」，因此打开 = HIGH。若映射成 MEDIUM，用户会得到一个
     * 「开了但没感觉」的开关 —— 正是本次要消灭的那类问题。
     */
    @Test
    fun `deep think toggle maps to high when on and auto when off`() {
        assertEquals(QuroReasoningControl.ThinkingLevel.HIGH, QuroReasoningControl.levelForDeepThink(true))
        // 关闭 → AUTO（不干预），**不是** OFF。
        // 用户关掉「深度思考」的意思是「别额外命令模型想更深」，
        // 不是「禁止 o 系/GPT-5 原生推理」—— 映射成 OFF 会悄悄改掉老用户的行为。
        assertEquals(QuroReasoningControl.ThinkingLevel.AUTO, QuroReasoningControl.levelForDeepThink(false))
    }

    // ───────────────────────── AUTO：不干预 ─────────────────────────

    /**
     * AUTO 档必须在**所有**家族上都不下发思考控制字段 —— 这是「改造前后行为一致」的保证。
     * 任何一处漏发，老用户升级后行为就会变。
     */
    @Test
    fun `auto level sends no reasoning controls on any family`() {
        val models = listOf(
            "o3-mini", "gpt-5.1", "claude-sonnet-4-6",
            "qwen3-8b", "deepseek-r1", "gpt-4o-mini",
        )
        for (m in models) {
            val p = plan(m, QuroReasoningControl.ThinkingLevel.AUTO)
            assertFalse("model=$m 在 AUTO 档不应下发任何思考控制", p.controlsReasoning)
            assertNull(p.reasoningEffort)
            assertNull(p.thinkingType)
            assertNull(p.chatTemplateEnableThinking)
            assertNull(p.topLevelEnableThinking)
        }
    }

    /**
     * 但**能力标志**与档位无关：GPT-5 / o 系即便在 AUTO 档也不能收 temperature、
     * 也只认 max_completion_tokens。改造前这套判断只覆盖 `^o[0-9]`，
     * GPT-5 全系漏网 —— 「一选 GPT-5 就报错」的根因。
     */
    @Test
    fun `auto level still applies capability flags for gpt-5 family`() {
        val p = plan("gpt-5.1", QuroReasoningControl.ThinkingLevel.AUTO)
        assertTrue("GPT-5 任何档位都不接受 temperature", p.suppressTemperature)
        assertTrue("GPT-5 任何档位都只认 max_completion_tokens", p.useMaxCompletionTokens)
    }

    /** AUTO 档下 Qwen3 不发 enable_thinking = 保持它自身的默认（开）。 */
    @Test
    fun `auto level leaves qwen3 on its own default`() {
        val p = QuroReasoningControl.plan(
            "OTHER", "https://vllm.internal:8000/v1", "qwen3-8b",
            QuroReasoningControl.ThinkingLevel.AUTO, 32768,
        )
        assertNull(p.chatTemplateEnableThinking)
        assertTrue(p.notes.any { it.contains("保持 Qwen3 自身默认") })
    }

    @Test
    fun `summary is readable for diagnostics`() {
        val s = plan("qwen3-8b", QuroReasoningControl.ThinkingLevel.HIGH, baseUrl = vllmUrl).summary()
        assertTrue(s.contains("family=QWEN3_TEMPLATE"))
        assertTrue(s.contains("level=HIGH"))
        assertTrue(s.contains("enable_thinking=true"))
        assertNotEquals("", s)
    }
}
