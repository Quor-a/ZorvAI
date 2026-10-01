package com.ai.assistance.quro.core.network

/**
 * 模型上下文预算解析器（纯逻辑，不依赖 Android / `org.json`，可穷举单测）。
 *
 * ## 为什么需要它
 *
 * 改造前，`QuroAssistant` 计算「单次请求输入预算」的方式是：
 *
 * ```
 * val hardMax = if (cfg.modelContextLength > 0) cfg.modelContextLength else MODEL_MAX_INPUT_TOKENS
 * ```
 *
 * 其中 `MODEL_MAX_INPUT_TOKENS = 1048576`。于是当 `/models` 接口**没有**返回 `context_length`
 * （大量第三方中转站、自建网关、部分厂商兼容端点都不返回）时，`modelContextLength = 0`
 *  → 预算被当成 **1M** → 上下文裁剪**几乎永远不会触发** → 而该模型的真实上限可能只有 32K
 *  → 请求体一旦超过真实上限，上游直接 `400 / 500 context length exceeded`。
 *
 * 换句话说：**这个「安全网」在它最该生效的场景（元数据缺失）里等于不存在。**
 *
 * 本组件把「未知」从「1M」改为「**保守推断**」：
 *
 * 1. 接口回填的真实值（最可信）；
 * 2. 按模型名族前缀表推断（`gpt-4o` → 128K、`claude` → 200K、`qwen` → 32K …）；
 * 3. 都拿不到 → **保守回落 32768**（而非 1M）。宁可多裁一段早期历史（软降级，对话能继续），
 *    也不要超限被上游 500（硬失败，整轮作废）。
 *
 * ## 与「用户设置」的关系
 *
 * 用户设置的 `contextWindow` 是**预算**（愿意花多少），本组件算出的硬上限是**能力**（模型能吃多少）。
 * 两者取小：用户设得更大 → 被硬上限钳住；用户设得更小 → 尊重用户（省 token）。
 *
 * ## 另：为什么不用模型表去钳 `max_tokens`
 *
 * 输出上限（`max_tokens`）猜小了会直接砍掉长回复，用户感知强烈且难归因；
 * 而输入预算猜小了只是多裁早期历史，可自愈。故本组件**只解析输入预算**，
 * 输出上限仍待接口回填后再行钳制（避免用不可靠的表去制造新问题）。
 */
object QuroModelContextBudget {

    /** 绝对天花板（=1M）：任何来源的值都不会超过它。Gemini 1.5/2.x 是当前唯一达此量级的族。 */
    const val ABSOLUTE_MAX_INPUT_TOKENS: Int = 1_048_576

    /**
     * 保守兜底输入预算。
     *
     * 选 32768 的理由：它是当前主流云端模型的**公共下限**（qwen-max 32K、豆包 32K、
     * 混元 32K、gpt-4-32k 32K … 均 ≥ 此值），同时远高于任何模型的「不够用」阈值。
     * 命中它意味着「我们不知道这个模型能吃多少」，此时宁可稳。
     */
    const val CONSERVATIVE_INPUT_TOKENS: Int = 32_768

    /** 再小的值没有实际意义（system 提示词本身就常超），视为无效输入。 */
    private const val MIN_SENSIBLE_TOKENS: Int = 1_024

    /** 预算来源，用于诊断日志（让「为什么上下文被裁了」可归因）。 */
    enum class Source {
        /** `/models` 接口回填的真实值。 */
        API_META,

        /** 由模型名族前缀表推断。 */
        MODEL_TABLE,

        /** 用户显式设置的预算更小，按其执行。 */
        USER_SETTING,

        /** 无任何线索，保守回落。 */
        CONSERVATIVE,
    }

    /**
     * @param inputTokens 最终生效的输入预算（token），恒 > 0。
     * @param hardLimit 该模型的硬上限（能力），未考虑用户预算的收缩。
     * @param source 最终值的来源（[Source.USER_SETTING] 表示是用户的预算起了作用）。
     * @param familyHint 命中的族关键字（如 `claude` / `qwen3`），未命中为 null —— 诊断用。
     */
    data class Budget(
        val inputTokens: Int,
        val hardLimit: Int,
        val source: Source,
        val familyHint: String? = null,
    ) {
        /** 是否为「猜出来的」预算（元数据缺失，用户应知悉裁剪是推断所致）。 */
        val inferred: Boolean get() = source == Source.MODEL_TABLE || source == Source.CONSERVATIVE
    }

    /**
     * 解析输入预算。
     *
     * @param modelName 模型名，可含厂商前缀与日期后缀（`openai/gpt-4o-2024-08-06`）。
     * @param provider 厂商标识（`OPENAI` / `ANTHROPIC` / `MNN` …），仅在模型名匹配不到时兜底。
     * @param apiContextLength `/models` 回填的真实上下文长度；`<= 0` 表示未知。
     * @param userContextWindow 用户设置的预算；`<= 0` 表示未设置（用硬上限）。
     */
    fun resolve(
        modelName: String,
        provider: String = "",
        apiContextLength: Int = 0,
        userContextWindow: Int = 0,
    ): Budget {
        // ① 接口实测值优先 —— 它是唯一「事实」，不受任何推断影响。
        if (apiContextLength >= MIN_SENSIBLE_TOKENS) {
            val hard = apiContextLength.coerceAtMost(ABSOLUTE_MAX_INPUT_TOKENS)
            return applyUserBudget(hard, Source.API_META, null, userContextWindow)
        }

        // ② 按模型名族推断。
        val hit = matchFamily(modelName)
        if (hit != null) {
            return applyUserBudget(hit.second, Source.MODEL_TABLE, hit.first, userContextWindow)
        }

        // ③ 模型名没线索时，试试厂商标识（比模型名粗，但胜过没有）。
        val byProvider = matchProvider(provider)
        if (byProvider != null) {
            return applyUserBudget(byProvider, Source.MODEL_TABLE, "provider:$provider", userContextWindow)
        }

        // ④ 保守回落 —— 绝不回落 1M（那等于不设防）。
        return applyUserBudget(CONSERVATIVE_INPUT_TOKENS, Source.CONSERVATIVE, null, userContextWindow)
    }

    /**
     * 用户预算与硬上限取小。
     *
     * 用户没设（`<= 0`）或设得不合理时，沿用硬上限。
     * 用户设了更小的值 → 采纳，来源标记为 [Source.USER_SETTING]；
     * 用户设了更大的值 → 被硬上限钳住，来源保持原样（能力才是决定项）。
     */
    private fun applyUserBudget(
        hard: Int,
        source: Source,
        hint: String?,
        userContextWindow: Int,
    ): Budget {
        val clampedHard = hard.coerceIn(MIN_SENSIBLE_TOKENS, ABSOLUTE_MAX_INPUT_TOKENS)
        if (userContextWindow < MIN_SENSIBLE_TOKENS) {
            return Budget(clampedHard, clampedHard, source, hint)
        }
        return if (userContextWindow < clampedHard) {
            Budget(userContextWindow, clampedHard, Source.USER_SETTING, hint)
        } else {
            Budget(clampedHard, clampedHard, source, hint)
        }
    }

    /**
     * 模型名族前缀表（有序，**先具体后宽泛**）。
     *
     * 排序铁律：`gpt-4.1` 必须排在 `gpt-4` 之前，`qwen-long`/`qwen-max` 必须排在 `qwen` 之前，
     * 否则宽泛关键字会抢先命中并给出错误的预算（`gpt-4` 的 8K 会把 `gpt-4.1` 的 1M 吃掉）。
     *
     * 取值原则：**同族取已知的典型值，不确定时取偏小值**。偏小只会多裁早期历史（可自愈），
     * 偏大会直接触发上游超限（整轮失败）。
     */
    private val FAMILIES: List<Pair<String, Int>> = listOf(
        // ── OpenAI ────────────────────────────────────────────────────────────
        "gpt-4.1" to 1_047_576,
        "gpt-4o" to 128_000,
        "gpt-4-turbo" to 128_000,
        "gpt-4-32k" to 32_768,
        "gpt-5" to 400_000,
        "gpt-4" to 8_192,
        "gpt-3.5" to 16_385,
        "chatgpt-4o" to 128_000,
        // o 系推理模型（200K 档）
        "o1" to 200_000,
        "o3" to 200_000,
        "o4" to 200_000,
        // ── Anthropic ─────────────────────────────────────────────────────────
        "claude" to 200_000,
        // ── Google ────────────────────────────────────────────────────────────
        "gemini-2.5" to 1_048_576,
        "gemini-2" to 1_048_576,
        "gemini-1.5" to 1_048_576,
        "gemini" to 32_768,
        "gemma" to 8_192,
        // ── 阿里 Qwen（长上下文变体必须先于通用关键字）──────────────────────────
        "qwen-long" to 10_000_000,
        "qwen3" to 131_072,
        "qwen2.5" to 131_072,
        "qwen2" to 131_072,
        "qwen-max" to 32_768,
        "qwen-plus" to 131_072,
        "qwen-turbo" to 1_000_000,
        "qwen" to 32_768,
        // ── DeepSeek ──────────────────────────────────────────────────────────
        "deepseek" to 65_536,
        // ── 智谱 GLM ──────────────────────────────────────────────────────────
        "glm-4" to 131_072,
        "glm" to 32_768,
        "chatglm" to 32_768,
        // ── 月之暗面 Kimi / Moonshot ──────────────────────────────────────────
        "kimi" to 131_072,
        "moonshot" to 131_072,
        // ── xAI / Mistral / Meta ──────────────────────────────────────────────
        "grok" to 131_072,
        "mixtral" to 32_768,
        "mistral" to 131_072,
        "llama" to 131_072,
        "phi-" to 131_072,
        "command-r" to 131_072,
        // ── MiniMax ───────────────────────────────────────────────────────────
        "minimax" to 245_760,
        "abab" to 245_760,
        // ── 国内其余厂商（多为 32K 档，保守取之）──────────────────────────────
        "hunyuan" to 32_768,
        "ernie" to 32_768,
        "doubao" to 32_768,
        "step-" to 32_768,
        "spark" to 32_768,
        "mimo" to 32_768,
        "internlm" to 32_768,
        "yi-" to 32_768,
        "baichuan" to 32_768,
        "sensetime" to 32_768,
    )

    /** 厂商标识兜底表（模型名完全无线索时使用）。 */
    private val PROVIDERS: Map<String, Int> = mapOf(
        "ANTHROPIC" to 200_000,
        "CLAUDE" to 200_000,
        "GOOGLE" to 1_048_576,
        "GEMINI" to 1_048_576,
        "DEEPSEEK" to 65_536,
        "MOONSHOT" to 131_072,
        "ZHIPU" to 131_072,
        "XAI" to 131_072,
        "MISTRAL" to 131_072,
        "MINIMAX" to 245_760,
        // 本地引擎不接受 token 预算（n_ctx 由原生层按 prompt 自适应），此处仅防误用。
        "MNN" to CONSERVATIVE_INPUT_TOKENS,
        "LLAMA_CPP" to CONSERVATIVE_INPUT_TOKENS,
    )

    /** 在族表中按「先具体后宽泛」的顺序找第一个命中的关键字。返回 (关键字, 预算)。 */
    internal fun matchFamily(modelName: String): Pair<String, Int>? {
        val n = modelName.lowercase()
        if (n.isBlank()) return null
        for ((key, tokens) in FAMILIES) {
            if (matchesKey(n, key)) return key to tokens
        }
        return null
    }

    /**
     * 关键字命中判定。
     *
     * - 长关键字（> 3 字符）用朴素 `contains`：`gpt-4o` / `deepseek` / `claude` 这类词
     *   不会误伤别的模型名。
     * - 短关键字（≤ 3 字符且全为字母数字，如 `o1` / `o3` / `o4` / `glm`）必须落在**词边界**上。
     *   否则 `o1` 会命中 `foo1-bar` 这类完全无关的名字，给出 200K 的错误预算 ——
     *   而「猜大了」正是会触发上游超限的那个方向。
     * - 含非字母数字的关键字（如 `yi-`）自身已带分隔符，走 `contains` 即可。
     */
    private fun matchesKey(normalized: String, key: String): Boolean {
        val needBoundary = key.length <= 3 && key.all { it.isLetterOrDigit() }
        if (!needBoundary) return normalized.contains(key)
        var from = 0
        while (true) {
            val i = normalized.indexOf(key, from)
            if (i < 0) return false
            val beforeOk = i == 0 || !normalized[i - 1].isLetterOrDigit()
            val end = i + key.length
            val afterOk = end == normalized.length || !normalized[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
    }

    private fun matchProvider(provider: String): Int? =
        PROVIDERS[provider.trim().uppercase()]

    /**
     * 诊断摘要（日志用）。不改行为，只把「预算从哪来」讲清楚 ——
     * 用户报「AI 丢上下文」时，第一件事就是看这里是不是 [Source.CONSERVATIVE]。
     */
    fun describe(budget: Budget): String = buildString {
        append("预算=${budget.inputTokens}")
        append(" 硬上限=${budget.hardLimit}")
        append(" 来源=").append(
            when (budget.source) {
                Source.API_META -> "接口回填"
                Source.MODEL_TABLE -> "模型表推断(${budget.familyHint})"
                Source.USER_SETTING -> "用户设置"
                Source.CONSERVATIVE -> "保守兜底(未识别模型)"
            }
        )
        if (budget.source == Source.CONSERVATIVE) {
            append(" ⚠️ 未能识别该模型的上下文长度，已按最保守值裁剪；")
            append("如该模型支持更长上下文，请在「模型配置」手动设置上下文窗口。")
        }
    }
}
