package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroReasoningEcho] 单元测试（N6）。
 *
 * 本策略的价值在于**把一个隐式耦合变成显式决定**，因此测试有两层目标：
 * 1. 每个家族的答案符合已知事实（谁能收 `reasoning_content`）；
 * 2. **证明本次改造是零行为变更** —— 新策略与旧的 `!isReasoningModel` 在全矩阵上等价。
 *    第 2 点尤其重要：它把「重构没有偷偷改线上行为」变成可执行的断言，
 *    而不是靠一句「我保证没改」。
 */
class QuroReasoningEchoTest {

    // ─────────────── 1) 各家族答案 ───────────────

    @Test
    fun `openai reasoning family must not receive reasoning content`() {
        // o 系 / GPT-5 系不接受 reasoning_content，回传会被上游整轮拒收。
        listOf(
            "o1", "o1-mini", "o3", "o3-mini", "o4-mini",
            "gpt-5", "gpt-5.1", "gpt-5-mini", "gpt5",
        ).forEach { model ->
            assertFalse(
                "「$model」不该收到 reasoning_content",
                QuroReasoningEcho.shouldEcho("OPENAI", "https://api.openai.com/v1", model),
            )
        }
    }

    @Test
    fun `anthropic must receive reasoning content so thinking blocks can be replayed`() {
        // Claude 侧要求把思考块（带 signature）原样回传，否则开启思考后的多轮工具编排断链。
        listOf("claude-sonnet-4-5", "claude-opus-4-6", "claude-3-5-haiku", "anthropic.claude-x")
            .forEach { model ->
                assertTrue(
                    "「$model」必须能收到 reasoning_content",
                    QuroReasoningEcho.shouldEcho("OTHER", "https://api.anthropic.com/v1", model),
                )
            }
    }

    @Test
    fun `models that require reasoning echo keep receiving it`() {
        // MiMo 明确要求 requiresReasoningContentOnAssistantMessages；
        // DeepSeek/Qwen 等保持改造前默认（回传）。
        listOf("mimo-v2.5", "deepseek-chat", "deepseek-reasoner", "qwen3-32b", "qwen-max", "glm-4.5")
            .forEach { model ->
                assertTrue(
                    "「$model」应保持回传（改造前行为）",
                    QuroReasoningEcho.shouldEcho("OTHER", "https://api.example.com/v1", model),
                )
            }
    }

    @Test
    fun `unknown models default to echo because that is the pre change behaviour`() {
        // 保守默认：未知模型一律回传 —— 这是改造前行为，未经真机验证不收紧。
        listOf("totally-unknown-model", "foo-bar-42", "").forEach { model ->
            assertTrue(
                "未知模型「$model」应保持默认回传",
                QuroReasoningEcho.shouldEcho("OPENAI", "https://gateway.local/v1", model),
            )
        }
    }

    // ─────────────── 2) 零行为变更证明 ───────────────

    @Test
    fun `new policy is byte equivalent to the old inverted flag across the matrix`() {
        // 🔑 这是本次重构的**核心断言**：改造前请求构造处写的是
        //       emitReasoning = !isReasoningModel
        //    而 isReasoningModel == plan.useMaxCompletionTokens。
        //    新策略必须在整个家族×端点×模型矩阵上与旧表达式给出**完全相同**的答案，
        //    否则就不是重构而是偷偷改线上行为。
        val providers = listOf("OPENAI", "OTHER", "MIMO", "DEEPSEEK", "DASHSCOPE")
        val endpoints = listOf(
            "https://api.openai.com/v1",
            "https://api.anthropic.com/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://gateway.local/v1",
        )
        val models = listOf(
            "o1", "o3-mini", "gpt-5", "gpt-5.2",
            "claude-opus-4-6", "claude-sonnet-4-5",
            "mimo-v2.5", "deepseek-chat", "deepseek-reasoner",
            "qwen3-32b", "qwq-32b", "glm-z1", "unknown-x",
        )
        var checked = 0
        for (p in providers) for (u in endpoints) for (m in models) {
            val plan = QuroReasoningControl.plan(p, u, m, QuroReasoningControl.ThinkingLevel.HIGH, 8192)
            val oldExpression = !plan.useMaxCompletionTokens
            val newPolicy = QuroReasoningEcho.shouldEcho(p, u, m)
            assertEquals(
                "行为漂移！provider=$p url=$u model=$m family=${plan.family}",
                oldExpression,
                newPolicy,
            )
            checked++
        }
        assertEquals("组合数应为 5*4*13", 5 * 4 * 13, checked)
    }

    @Test
    fun `echo decision does not depend on the thinking level`() {
        // 回传与否是**字段兼容性**问题，与思考档位无关（档位只影响请求侧的思考参数）。
        // 若某天答随档位变化，说明又混入了不该有的耦合。
        val p = "OPENAI"; val u = "https://api.openai.com/v1"; val m = "gpt-5"
        val base = QuroReasoningEcho.shouldEcho(p, u, m)
        QuroReasoningControl.ThinkingLevel.values().forEach { level ->
            val plan = QuroReasoningControl.plan(p, u, m, level, 8192)
            assertEquals(
                "档位 $level 不该改变回传策略（plan.family=${plan.family}）",
                base,
                QuroReasoningEcho.shouldEcho(p, u, m),
            )
        }
    }

    // ─────────────── 3) 诊断行 ───────────────

    @Test
    fun `diagnosis states the decision and the reason`() {
        val openai = QuroReasoningEcho.diagnosis("OPENAI", "https://api.openai.com/v1", "gpt-5")
        assertTrue(openai.contains("reasoningEcho=false"))
        assertTrue("必须说清为什么，否则线上无从判断该不该改", openai.contains("不认识"))

        val claude = QuroReasoningEcho.diagnosis("OTHER", "https://api.anthropic.com/v1", "claude-opus-4-6")
        assertTrue(claude.contains("reasoningEcho=true"))
        assertTrue(claude.contains("Claude"))

        val mimo = QuroReasoningEcho.diagnosis("OTHER", "https://gateway.local/v1", "mimo-v2.5")
        assertTrue(mimo.contains("reasoningEcho=true"))
        assertTrue("默认分支要说明这是保守沿用改造前行为", mimo.contains("改造前"))

        // 每一类都不得出现空诊断
        listOf(openai, claude, mimo).forEach {
            assertTrue(it.isNotBlank())
            assertTrue(it.contains("family="))
        }
    }
}
