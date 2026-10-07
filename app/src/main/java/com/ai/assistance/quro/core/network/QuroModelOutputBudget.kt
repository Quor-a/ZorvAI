package com.ai.assistance.quro.core.network

/**
 * 模型**输出**上限钳制（纯逻辑，不依赖 Android / `org.json`，可穷举单测）。
 *
 * ## 为什么需要它（这是「GenUI 写不完整 / 部分模型不能用」的真凶之一）
 *
 * `QuroModelConfig.maxTokens` 默认 **65536**，`GenUILlmClient.MAX_OUTPUT_TOKENS` 上限131072。
 * 但绝大多数模型/中转的 `max_tokens` **真实上限远低于此**：
 *
 * - `gpt-4o` / `gpt-4o-mini`：16384
 * - `gpt-4-turbo` / `gpt-3.5-turbo`：4096
 * - `claude-3.x`：8192（不开 1M beta 时）
 * - `qwen` / `deepseek` / `glm` / `gemma` 多数中转档：4096 ~ 8192
 *
 * 上游收到超出自身上限的 `max_tokens`，**行为极不统一**：
 * 有的直接 `400 max_tokens too large`，有的静默夹到自己的上限（于是输出被腰斩），
 * 有的**整个请求 500**（中转转发层校验失败）。
 * 这正好解释了用户看到的两个现象：
 *
 * 1. 「**部分模型不能用**」—— 同一个 App换个模型就HTTP 500 / 400，因为那个模型的上限比 65536 小；
 * 2. 「**GenUI 写不完整**」—— 上游静默夹到4096，长报告/长JSON 被腰斩，
 *    而 GenUI 的续写判定只看「genui 围栏是否闭合」，**markdown 报告被腰斩时围栏本来就是闭合的**
 *    → 不触发续写 → 用户看到「AI 能力自检报告」写到一半就断了（图1 症状）。
 *
 * ## 与 [QuroModelContextBudget] 的分工
 *
 * - [QuroModelContextBudget] 管**输入**（system + 历史能占多少）；
 * - 本类管**输出**（`max_tokens` 能填多少）。
 *
 * 两者都必须**向下钳到模型真实能力**，绝不能信任用户/默认配的大值。
 *
 * ## 取值原则
 *
 * 与输入预算一致：**不确定时取保守偏小值**。因为：
 *
 * - 输出被腰斩 → 内容不完整（用户可见的坏体验），但本类配合 GenUI 的
 *   `finish_reason == "length"` 续写可自愈；
 * - 填太大 → 上游 400/500（整轮直接作废，不可自愈）。
 *
 * 宁可自愈，不可作废。
 */
object QuroModelOutputBudget {

    /** 绝对天花板：任何来源的值都不会超过它。 */
    const val ABSOLUTE_MAX_OUTPUT_TOKENS: Int = 131_072

    /**
     * 保守兜底输出上限。
     *
     * 选 4096 的理由：这是**当前所有主流云端模型的公共下限**
     * （gpt-3.5-turbo / gpt-4-turbo / claude-3 / qwen-turbo / deepseek 中转档 均 = 4096）。
     * 命中它意味着「我们不知道这个模型能输出多少」—— 此时必须选一个**处处合法**的值，
     * 否则就是拿用户的请求去赌上游会不会 500。
     * 内容被腰斩的问题由 GenUI 的 `finish_reason=length` 续写机制兜住。
     */
    const val CONSERVATIVE_OUTPUT_TOKENS: Int = 4_096

    /** 再小的值没有实际意义（连一句话都说不完），视为无效。 */
    private const val MIN_SENSIBLE_TOKENS: Int = 256

    /** 预算来源，用于诊断日志。 */
    enum class Source {
        /** 用户显式设置且小于硬上限（用户主动省token）。 */
        USER_SETTING,

        /** 由模型名族表推断。 */
        MODEL_TABLE,

        /** 无任何线索，保守回落。 */
        CONSERVATIVE,
    }

    /**
     * @param outputTokens 最终生效的 `max_tokens`，恒 >= [MIN_SENSIBLE_TOKENS]。
     * @param hardLimit 该模型的输出硬上限（能力）。
     * @param source 最终值来源。
     * @param familyHint 命中的族关键字，未命中为 null —— 诊断用。
     */
    data class Budget(
        val outputTokens: Int,
        val hardLimit: Int,
        val source: Source,
        val familyHint: String? = null,
    ) {
        /** 是否为「猜出来的」（元数据缺失，用户应知悉可能被腰斩）。 */
        val inferred: Boolean get() = source == Source.MODEL_TABLE || source == Source.CONSERVATIVE
    }

    /**
     * 解析输出上限。
     *
     * @param modelName 模型名（可含厂商前缀与日期后缀）。
     * @param requestedMaxTokens 调用方请求的值（用户配置 / 默认值 / 上层钳制后的值）。<= 0 视为未指定。
     */
    fun resolve(modelName: String, requestedMaxTokens: Int = 0): Budget {
        val hit = matchFamily(modelName)
        if (hit != null) {
            val hard = hit.second.coerceIn(MIN_SENSIBLE_TOKENS, ABSOLUTE_MAX_OUTPUT_TOKENS)
            return applyRequested(hard, Source.MODEL_TABLE, hit.first, requestedMaxTokens)
        }
        return applyRequested(CONSERVATIVE_OUTPUT_TOKENS, Source.CONSERVATIVE, null, requestedMaxTokens)
    }

    /**
     * 请求值与硬上限取小。
     *
     * 用户/上层给得更小 → 采纳（尊重调用方，可能是有意省 token）；给得更大 → 被硬上限钳住。
     */
    private fun applyRequested(hard: Int, source: Source, hint: String?, requested: Int): Budget =
        if (requested in MIN_SENSIBLE_TOKENS until hard) {
            Budget(requested, hard, Source.USER_SETTING, hint)
        } else {
            Budget(hard, hard, source, hint)
        }

    /**
     * 模型名族表（有序，**先具体后宽泛**）。
     *
     * 排序铁律与 `QuroModelContextBudget` 一致：`gpt-4o` 必须排在 `gpt-4` 之前，
     * `o3` / `o1` 必须在 `o` 之前，否则宽泛关键字会抢先命中并给出偏大值（**偏大 = 上游 400/500**）。
     */
    private val FAMILIES: List<Pair<String, Int>> = listOf(
        // ── OpenAI ────────────────────────────────────────────────────────────
        // GPT-5 系输出上限 128K，但需 max_completion_tokens（由 QuroReasoningControl 负责字段名）
        "gpt-5" to 128_000,
        "gpt-4.1" to 32_768,
        "gpt-4o" to 16_384,
        "gpt-4-turbo" to 4_096,
        "gpt-4-32k" to 4_096,
        "chatgpt-4o" to 16_384,
        "gpt-4" to 8_192,
        "gpt-3.5" to 4_096,
        // o 系推理模型
        "o3" to 100_000,
        "o1" to 100_000,
        "o4" to 100_000,
        // ── Anthropic ─────────────────────────────────────────────────────────
        // claude-3/3.5 标准档8192；claude-3-5-sonnet 后续版本给到 64K，这里取保守值
        "claude" to 8_192,
        // ── Google ────────────────────────────────────────────────────────────
        "gemini-2.5" to 65_536,
        "gemini-2" to 8_192,
        "gemini-1.5" to 8_192,
        "gemini" to 8_192,
        "gemma" to 8_192,
        // ── 阿里 Qwen（长输出变体必须先于通用关键字）──────────────────────────
        "qwen-long" to 6_000,
        "qwen3" to 32_768,
        "qwen2.5" to 32_768,
        "qwen2" to 8_192,
        "qwen-max" to 8_192,
        "qwen-plus" to 8_192,
        "qwen-turbo" to 8_192,
        "qwen" to 8_192,
        // ── DeepSeek ──────────────────────────────────────────────────────────
        "deepseek" to 8_192,
        // ── 智谱 GLM ──────────────────────────────────────────────────────────
        "glm-4" to 8_192,
        "glm" to 8_192,
        "chatglm" to 8_192,
        // ── 月之暗面 Kimi / Moonshot ──────────────────────────────────────────
        "kimi" to 16_384,
        "moonshot" to 16_384,
        // ── xAI / Mistral / Meta ──────────────────────────────────────────────
        "grok" to 32_768,
        "mixtral" to 8_192,
        "mistral" to 8_192,
        "llama" to 8_192,
        "phi-" to 4_096,
        "command-r" to 8_192,
        // ── MiniMax ───────────────────────────────────────────────────────────
        "minimax" to 32_768,
        "abab" to 32_768,
        // ── 国内其余厂商（多为 4K~8K 档，保守取之）────────────────────────────
        "hunyuan" to 8_192,
        "ernie" to 4_096,
        "doubao" to 8_192,
        "step-" to 8_192,
        "spark" to 4_096,
        "mimo" to 8_192,
        "internlm" to 4_096,
        "yi-" to 4_096,
        "baichuan" to 4_096,
        "sensetime" to 4_096,
    )

    /** 在族表中按「先具体后宽泛」的顺序找第一个命中的关键字。返回 (关键字, 上限)。 */
    internal fun matchFamily(modelName: String): Pair<String, Int>? {
        val n = modelName.lowercase()
        if (n.isBlank()) return null
        for ((key, tokens) in FAMILIES) {
            if (matchesKey(n, key)) return key to tokens
        }
        return null
    }

    /**
     * 关键字命中判定（与 [QuroModelContextBudget.matchesKey] 同规则）。
     *
     * 短关键字（<= 3 字符且全为字母数字，如 `o1` / `o3` / `glm`）必须落在**词边界**上，
     * 否则 `o1` 会命中 `foo1-bar` 并给出 100000 的荒谬上限 —— 而「猜大了」正是会触发上游 400/500 的方向。
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

    /** 诊断摘要（日志用）：把「输出上限从哪来」讲清楚。 */
    fun describe(budget: Budget): String = buildString {
        append("输出上限=").append(budget.outputTokens)
        append(" 硬上限=").append(budget.hardLimit)
        append(" 来源=").append(
            when (budget.source) {
                Source.USER_SETTING -> "调用方请求值(更小，采纳)"
                Source.MODEL_TABLE -> "模型表推断(${budget.familyHint})"
                Source.CONSERVATIVE -> "保守兜底(未识别模型)"
            }
        )
        if (budget.source == Source.CONSERVATIVE) {
            append(" ⚠️ 未能识别该模型的输出上限，已按最保守值下发；")
            append("内容过长时会被腰斩（GenUI 会自动续写）。")
        }
    }
}
