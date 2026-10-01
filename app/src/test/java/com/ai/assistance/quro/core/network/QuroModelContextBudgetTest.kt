package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N13 回归：模型上下文预算解析。
 *
 * 最重要的三条断言（改造前后的行为分界）：
 *  - 元数据缺失 + 模型名可识别 → 用**推断值**，绝不用 1M；
 *  - 元数据缺失 + 模型名不可识别 → 用**保守值 32768**，绝不回落 1M；
 *  - 族表顺序正确：`gpt-4.1` / `gpt-4o` 不被宽泛的 `gpt-4` 抢先吃掉。
 */
class QuroModelContextBudgetTest {

    private fun resolve(
        model: String = "",
        provider: String = "",
        api: Int = 0,
        user: Int = 0,
    ) = QuroModelContextBudget.resolve(model, provider, api, user)

    // ── ① 接口实测值优先 ──────────────────────────────────────────────────────

    @Test
    fun `api context length wins over model table`() {
        // 模型名能匹配到 gpt-4o(128K)，但接口给了 65536 → 以接口为准
        val b = resolve(model = "gpt-4o", api = 65_536)
        assertEquals(65_536, b.inputTokens)
        assertEquals(QuroModelContextBudget.Source.API_META, b.source)
        assertFalse(b.inferred)
    }

    @Test
    fun `api context length is clamped to absolute ceiling`() {
        // 上游偶尔报出天文数字（或单位搞错），必须钳到 1M，否则预算形同虚设
        val b = resolve(model = "gpt-4o", api = 999_999_999)
        assertEquals(QuroModelContextBudget.ABSOLUTE_MAX_INPUT_TOKENS, b.inputTokens)
        assertEquals(QuroModelContextBudget.ABSOLUTE_MAX_INPUT_TOKENS, b.hardLimit)
    }

    @Test
    fun `too small api value is treated as unknown and falls back to table`() {
        // 512 不足以装 system 提示词，视为脏数据 → 走模型表（gpt-4o → 128K）
        val b = resolve(model = "gpt-4o", api = 512)
        assertEquals(QuroModelContextBudget.Source.MODEL_TABLE, b.source)
        assertEquals(128_000, b.inputTokens)
    }

    @Test
    fun `negative api value is treated as unknown`() {
        val b = resolve(model = "claude-sonnet-4", api = -1)
        assertEquals(QuroModelContextBudget.Source.MODEL_TABLE, b.source)
        assertEquals(200_000, b.inputTokens)
    }

    // ── ② 族表匹配与排序 ─────────────────────────────────────────────────────

    @Test
    fun `gpt-4_1 is matched before generic gpt-4`() {
        // 若排序写错，gpt-4(8192) 会抢先命中并吃掉 gpt-4.1(1M) —— 这是族表最容易犯的错
        val b = resolve(model = "gpt-4.1-mini")
        assertEquals("gpt-4.1", b.familyHint)
        assertEquals(1_047_576, b.inputTokens)
    }

    @Test
    fun `gpt-4o is matched before generic gpt-4`() {
        val b = resolve(model = "gpt-4o-mini")
        assertEquals("gpt-4o", b.familyHint)
        assertEquals(128_000, b.inputTokens)
    }

    @Test
    fun `gpt-4-turbo is matched before generic gpt-4`() {
        val b = resolve(model = "gpt-4-turbo-2024-04-09")
        assertEquals("gpt-4-turbo", b.familyHint)
        assertEquals(128_000, b.inputTokens)
    }

    @Test
    fun `plain gpt-4 still falls back to its own small window`() {
        val b = resolve(model = "gpt-4")
        assertEquals("gpt-4", b.familyHint)
        assertEquals(8_192, b.inputTokens)
    }

    @Test
    fun `qwen-long is matched before generic qwen and clamped by the ceiling`() {
        // 表里的原始值（1000 万）本身就超过我们的绝对天花板
        assertEquals(10_000_000, QuroModelContextBudget.matchFamily("qwen-long")!!.second)
        val b = resolve(model = "qwen-long")
        assertEquals("qwen-long", b.familyHint)
        // 生效值被钳到 1M —— 这是刻意的：把所有来源都收进同一个可知范围
        assertEquals(QuroModelContextBudget.ABSOLUTE_MAX_INPUT_TOKENS, b.inputTokens)
    }

    @Test
    fun `qwen-max is matched before generic qwen`() {
        val b = resolve(model = "qwen-max-latest")
        assertEquals("qwen-max", b.familyHint)
        assertEquals(32_768, b.inputTokens)
    }

    @Test
    fun `qwen3 family detected`() {
        val b = resolve(model = "qwen3-32b")
        assertEquals("qwen3", b.familyHint)
        assertEquals(131_072, b.inputTokens)
    }

    @Test
    fun `claude family resolved to 200k`() {
        val b = resolve(model = "claude-3-5-sonnet-20241022")
        assertEquals(200_000, b.inputTokens)
    }

    @Test
    fun `gemini 2_5 resolved to 1M`() {
        val b = resolve(model = "gemini-2.5-flash")
        assertEquals(1_048_576, b.inputTokens)
    }

    @Test
    fun `o series reasoning models resolved to 200k`() {
        assertEquals(200_000, resolve(model = "o1-preview").inputTokens)
        assertEquals(200_000, resolve(model = "o3-mini").inputTokens)
    }

    @Test
    fun `vendor prefixed and dated model names still match`() {
        val b = resolve(model = "openai/gpt-4o-2024-08-06")
        assertEquals("gpt-4o", b.familyHint)
        assertEquals(128_000, b.inputTokens)
    }

    @Test
    fun `uppercase model names are matched case insensitively`() {
        val b = resolve(model = "GPT-4O-MINI")
        assertEquals("gpt-4o", b.familyHint)
    }

    @Test
    fun `domestic vendors default to 32k tier`() {
        assertEquals(32_768, resolve(model = "doubao-pro-32k").inputTokens)
        assertEquals(32_768, resolve(model = "hunyuan-turbo").inputTokens)
        assertEquals(32_768, resolve(model = "mimo-7b").inputTokens)
    }

    // ── ③ 未知 → 保守兜底（本次修复的核心）────────────────────────────────────

    @Test
    fun `unknown model falls back to conservative not to one million`() {
        // 🔴 改造前这里是 1048576（= 不设防）。本断言锁死新行为。
        val b = resolve(model = "some-gateway-custom-v9")
        assertEquals(QuroModelContextBudget.Source.CONSERVATIVE, b.source)
        assertEquals(QuroModelContextBudget.CONSERVATIVE_INPUT_TOKENS, b.inputTokens)
        assertEquals(32_768, b.inputTokens)
        assertTrue(b.inferred)
        // 关键否证：绝不是 1M
        assertFalse(b.inputTokens == 1_048_576)
    }

    @Test
    fun `blank model name falls back to conservative`() {
        val b = resolve(model = "", provider = "")
        assertEquals(QuroModelContextBudget.Source.CONSERVATIVE, b.source)
        assertEquals(32_768, b.inputTokens)
        assertNull(b.familyHint)
    }

    @Test
    fun `provider is used when model name has no clue`() {
        val b = resolve(model = "my-private-deploy", provider = "ANTHROPIC")
        assertEquals(QuroModelContextBudget.Source.MODEL_TABLE, b.source)
        assertEquals(200_000, b.inputTokens)
        assertEquals("provider:ANTHROPIC", b.familyHint)
    }

    @Test
    fun `provider fallback is case and whitespace insensitive`() {
        val b = resolve(model = "x", provider = " deepseek ")
        assertEquals(65_536, b.inputTokens)
    }

    @Test
    fun `model name wins over provider fallback`() {
        // 契约：族表按「模型名」识别，厂商标识只在模型名完全无线索时兜底。
        // 本地部署的 qwen2.5 依然是 qwen2.5 —— 不该因为 provider=MNN 就被降成保守值。
        assertEquals(131_072, resolve(model = "qwen2.5-1.5b-instruct", provider = "MNN").inputTokens)
        // 模型名无线索时才轮到 provider
        assertEquals(32_768, resolve(model = "some-gguf", provider = "LLAMA_CPP").inputTokens)
        assertEquals(32_768, resolve(model = "my-local-model", provider = "MNN").inputTokens)
    }

    @Test
    fun `local engine providers never exceed the conservative tier`() {
        // 本地引擎不接受 token 预算（n_ctx 由原生层按 prompt 自适应），
        // 万一被误用，也必须是保守值而非 1M —— 否则会把 prompt 撑爆。
        listOf("MNN", "LLAMA_CPP").forEach { p ->
            val b = resolve(model = "totally-unknown", provider = p)
            assertTrue("provider=$p 的预算必须保守", b.inputTokens <= QuroModelContextBudget.CONSERVATIVE_INPUT_TOKENS)
        }
    }

    // ── ④ 用户预算与硬上限取小 ───────────────────────────────────────────────

    @Test
    fun `smaller user budget is honoured and reported as user setting`() {
        val b = resolve(model = "claude-sonnet-4", user = 16_384)
        assertEquals(16_384, b.inputTokens)
        assertEquals(200_000, b.hardLimit)
        assertEquals(QuroModelContextBudget.Source.USER_SETTING, b.source)
        // 用户主动收缩不算「推断」
        assertFalse(b.inferred)
    }

    @Test
    fun `larger user budget is clamped by hard limit`() {
        // 用户以为「设大点就能装下」——实际能力上限才是决定项
        val b = resolve(model = "claude-sonnet-4", user = 1_000_000)
        assertEquals(200_000, b.inputTokens)
        assertEquals(QuroModelContextBudget.Source.MODEL_TABLE, b.source)
    }

    @Test
    fun `default user budget of one million is clamped instead of disabling trimming`() {
        // 🔴 真实默认配置：QuroModelConfig.contextWindow = 1048576。
        // 改造前它会让「裁剪」永不触发；现在被硬上限钳住。
        val b = resolve(model = "qwen-plus", user = 1_048_576)
        assertEquals(131_072, b.inputTokens)
    }

    @Test
    fun `unset or nonsense user budget keeps hard limit`() {
        assertEquals(128_000, resolve(model = "gpt-4o", user = 0).inputTokens)
        assertEquals(128_000, resolve(model = "gpt-4o", user = -5).inputTokens)
        assertEquals(128_000, resolve(model = "gpt-4o", user = 100).inputTokens)
    }

    @Test
    fun `user budget smaller than minimum is ignored rather than producing an unusable payload`() {
        val b = resolve(model = "gpt-4o", user = 1)
        assertEquals(128_000, b.inputTokens)
        assertEquals(QuroModelContextBudget.Source.MODEL_TABLE, b.source)
    }

    @Test
    fun `hard limit is never below the minimum sensible value`() {
        // 无论走哪条分支，硬上限都不能小到装不下 system 提示词
        listOf(
            resolve(model = "who-knows"),
            resolve(model = "who-knows", api = 1),
            resolve(model = "gemma-2b"),
            resolve(model = "gpt-4"),
        ).forEach {
            assertTrue("${it.source} 的硬上限过小：${it.hardLimit}", it.hardLimit >= 1_024)
        }
    }

    // ── ⑤ 诊断输出 ───────────────────────────────────────────────────────────

    @Test
    fun `describe reports source and warns only on conservative fallback`() {
        val conservative = QuroModelContextBudget.describe(resolve(model = "who-knows"))
        assertTrue(conservative.contains("保守兜底"))
        assertTrue(conservative.contains("未能识别"))
        assertTrue(conservative.contains("模型配置"))

        val fromApi = QuroModelContextBudget.describe(resolve(model = "gpt-4o", api = 65_536))
        assertTrue(fromApi.contains("接口回填"))
        assertFalse(fromApi.contains("未能识别"))

        val fromTable = QuroModelContextBudget.describe(resolve(model = "gpt-4o"))
        assertTrue(fromTable.contains("模型表推断"))
        assertTrue(fromTable.contains("gpt-4o"))
    }

    @Test
    fun `describe always carries both numbers`() {
        val d = QuroModelContextBudget.describe(resolve(model = "claude-sonnet-4", user = 8_192))
        assertTrue(d.contains("预算=8192"))
        assertTrue(d.contains("硬上限=200000"))
        assertTrue(d.contains("用户设置"))
    }

    // ── ⑥ 内部工具函数契约 ───────────────────────────────────────────────────

    @Test
    fun `matchFamily returns null for unrecognised name`() {
        assertNull(QuroModelContextBudget.matchFamily("totally-made-up-model"))
        assertNull(QuroModelContextBudget.matchFamily(""))
    }

    @Test
    fun `matchFamily returns first hit in declaration order`() {
        val hit = QuroModelContextBudget.matchFamily("gpt-4.1")
        assertNotNull(hit)
        assertEquals("gpt-4.1", hit!!.first)
    }

    @Test
    fun `short keywords require a word boundary`() {
        // "o1" 若按朴素 contains 匹配，会命中 foo1-bar 这种无关名字并给出 200K 的错误预算
        assertNull(QuroModelContextBudget.matchFamily("foo1-bar"))
        // 但真正以 o1 为词的名字必须命中
        assertEquals("o1", QuroModelContextBudget.matchFamily("o1-preview")!!.first)
        assertEquals("o1", QuroModelContextBudget.matchFamily("openai/o1-mini")!!.first)
        assertEquals("o3", QuroModelContextBudget.matchFamily("o3-mini")!!.first)
    }

    @Test
    fun `numeric suffixes do not accidentally trigger short keyword families`() {
        // 网关常见做法：同名模型加版本号后缀。不应被 o1/o3/o4/glm 误吃。
        val b = resolve(model = "my-model-2024-01-03")
        assertEquals(QuroModelContextBudget.Source.CONSERVATIVE, b.source)
        assertEquals(QuroModelContextBudget.CONSERVATIVE_INPUT_TOKENS, b.inputTokens)
    }

    @Test
    fun `budget exposes positive values for every source`() {
        listOf(
            resolve(model = "gpt-4o", api = 65_536),
            resolve(model = "gpt-4o"),
            resolve(model = "gpt-4o", user = 4_096),
            resolve(model = "who-knows"),
        ).forEach {
            assertTrue("${it.source} 的预算必须为正", it.inputTokens > 0)
            assertTrue("${it.source} 的硬上限必须为正", it.hardLimit > 0)
            assertTrue("${it.source} 的预算不得超过硬上限", it.inputTokens <= it.hardLimit)
        }
    }
}
