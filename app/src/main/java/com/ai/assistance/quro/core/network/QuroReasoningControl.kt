package com.ai.assistance.quro.core.network

/**
 * 云端「思考（reasoning）」控制参数的**编译层**。
 *
 * ## 为什么需要这一层
 *
 * 在本次改造之前，「深度思考」开关的全部作用只是往 system prompt 里塞一段自然语言
 * （见 `QuroAssistant.buildDeepThinkDirective`）—— **请求体与开关关闭时逐字节相同**。
 * 对以下几类模型完全无效，甚至反向失效：
 *
 * - OpenAI o 系 / GPT-5.x：不传 `reasoning_effort` 就听服务端默认，而 GPT-5.x 的默认档位
 *   跨版本反复变更（5→medium、5.1→none、5.2→none、5.5→medium），等于交给运气；
 * - Anthropic Claude：不传 `thinking` 压根不进思考模式；
 * - Qwen3（vLLM/SGLang/百炼）：**默认就是开思考的**，用户关不掉，只能显式传 `enable_thinking=false`。
 *
 * ## 为什么四家字段不一样（这就是本层存在的理由）
 *
 * | 家族 | 下发字段 |
 * |---|---|
 * | OpenAI o 系 / GPT-5 | 顶层 `reasoning_effort: "low"\|"medium"\|"high"\|"none"` |
 * | Anthropic | `thinking: {type: "enabled", budget_tokens: N}` |
 * | Qwen3（vLLM/SGLang） | `chat_template_kwargs: {enable_thinking: bool}` |
 * | DashScope 兼容端点 | 顶层 `enable_thinking: bool` |
 *
 * ## 设计原则（与端侧 N8/N9 同源）
 *
 * 1. **能力探测优先于硬编码名单** —— 端侧读 `jinja.chat_template`，这里按 baseUrl + 模型名特征判定；
 * 2. **探测不到就什么都不做** —— `Family.NONE` 返回空 plan，行为与改造前**完全一致**
 *    （端侧 `addMarkers` 传空集同样是 no-op）；
 * 3. **绝不因赌错把 400 送给上游** —— 宁可少传一个字段。例如 Claude 的
 *    `budget_tokens` 不满足 `1024 <= N < max_tokens` 时，宁可不传 `thinking`；
 * 4. **本类不碰 `org.json`** —— 只产出纯 Kotlin 数据。JSON 组装留在 `QuroLlmClient`。
 *    这样单元测试不依赖 Android 的 org.json（单测里它是会抛 "not mocked" 的桩）。
 */
object QuroReasoningControl {

    /** 思考强度档位。与 UI 的「深度思考」开关对接（关 → [OFF]，开 → [HIGH]）。 */
    enum class ThinkingLevel {
        /**
         * 不干预：**不发任何思考参数**，由模型/服务端按自身默认行为决定。
         *
         * 这是「深度思考」开关**关闭**时映射到的档位，也是全链路默认值 ——
         * 与本次改造之前的行为逐字节一致。
         *
         * 与 [OFF] 的区别很重要：用户关掉一个叫「深度思考」的开关，
         * 意思是「别额外命令模型想更深」，**不是**「禁止 o 系/GPT-5 原生推理」。
         * 若把关闭映射成 [OFF]，就会悄悄改变老用户的行为（o 系从默认思考变成被关掉）。
         */
        AUTO,

        /** 明确关闭思考。对恒思考模型无意义（见 [Family.ALWAYS_ON]）。 */
        OFF,

        /** 轻量思考：为工具调用/多步决策这类场景保留最低限度的推理。 */
        LOW,

        /** 均衡档：复杂问答、规划、研究。 */
        MEDIUM,

        /** 深度档：困难调试、深层规划、高价值长任务。「深度思考」开关打开时用这一档。 */
        HIGH,
    }

    /** 模型家族的思考控制形态。 */
    enum class Family {
        /** OpenAI o 系 / GPT-5.x：顶层 `reasoning_effort`。 */
        OPENAI_EFFORT,

        /** Anthropic Claude：`thinking: {type, budget_tokens}`。 */
        ANTHROPIC_THINKING,

        /** Qwen3 部署在 vLLM / SGLang / 通用 OpenAI 兼容端点：`chat_template_kwargs.enable_thinking`。 */
        QWEN3_TEMPLATE,

        /** DashScope（百炼）OpenAI 兼容端点：顶层 `enable_thinking`。 */
        DASHSCOPE_TOGGLE,

        /** 恒思考模型（DeepSeek-R1 / QwQ / GLM-Z1 等）：没有开关，开关对其无效。 */
        ALWAYS_ON,

        /** 未识别：不发任何思考参数，行为与改造前一致。 */
        NONE,
    }

    /**
     * 编译结果：一批「可能为空」的字段 + 两条**互斥保证**。
     *
     * 全部为 null / false 时表示「本次不发任何思考参数」——这是未识别家族的正常结果。
     */
    data class ReasoningPlan(
        /** 编译本 plan 的家族。放进主构造而非类体，才能被 `copy`/`equals` 带上、也能被单测直接断言。 */
        val family: Family = Family.NONE,

        /** 编译本 plan 时请求的思考档位。 */
        val level: ThinkingLevel = ThinkingLevel.OFF,

        /**
         * 顶层 `reasoning_effort`（OpenAI o 系 / GPT-5）。
         *
         * 只用这一种写法：部分网关另支持 `reasoning: {effort: ...}` 对象形式，
         * 但两种**同一请求同时发 = 400**。顶层写法被官方与网关共同支持，故固定走它。
         * 需要精确预算的家族各有自己的字段（Anthropic 走 [thinkingBudgetTokens]）。
         */
        val reasoningEffort: String? = null,

        /** Anthropic `thinking.type`：`enabled`（手动预算）。 */
        val thinkingType: String? = null,

        /** Anthropic `thinking.budget_tokens`：必须 `>= 1024` 且 `< max_tokens`。 */
        val thinkingBudgetTokens: Int? = null,

        /** Qwen3 `chat_template_kwargs.enable_thinking`。null = 不发。 */
        val chatTemplateEnableThinking: Boolean? = null,

        /** DashScope 顶层 `enable_thinking`。null = 不发。 */
        val topLevelEnableThinking: Boolean? = null,

        /**
         * 本档位下**必须省略** `temperature`。
         *
         * GPT-5 系明确不接受 `temperature`；Anthropic 开启 thinking 后同样不接受。
         * 不省略的后果是上游 400 —— 用户表现为「换个模型就直接报错」。
         */
        val suppressTemperature: Boolean = false,

        /** 本家族要求用 `max_completion_tokens` 而非 `max_tokens`。 */
        val useMaxCompletionTokens: Boolean = false,

        /** 人类可读的编译依据，直接写进 `QuroDiag` / Logcat，便于线上定位「为什么这个模型没生效」。 */
        val notes: List<String> = emptyList(),
    ) {
        /** 是否真的下发了思考控制（用于诊断日志）。 */
        val controlsReasoning: Boolean
            get() = reasoningEffort != null || thinkingType != null ||
                chatTemplateEnableThinking != null || topLevelEnableThinking != null

        /** 一行式摘要。 */
        fun summary(): String = buildString {
            append("family=").append(family)
            append(" level=").append(level)
            if (reasoningEffort != null) append(" effort=").append(reasoningEffort)
            if (thinkingType != null) {
                append(" thinking=").append(thinkingType)
                thinkingBudgetTokens?.let { append("/").append(it) }
            }
            if (chatTemplateEnableThinking != null) append(" chat_template_kwargs.enable_thinking=").append(chatTemplateEnableThinking)
            if (topLevelEnableThinking != null) append(" enable_thinking=").append(topLevelEnableThinking)
            if (suppressTemperature) append(" [省略 temperature]")
            if (useMaxCompletionTokens) append(" [用 max_completion_tokens]")
            if (!controlsReasoning) append(" [不下发思考参数]")
        }

    }

    /** 模型名片段 → 家族。顺序敏感：**先判定更具体的**（dashscope 端点要先看 baseUrl）。 */
    private val ANTHROPIC_PATTERN = Regex("(?i)claude|anthropic")
    private val OPENAI_EFFORT_PATTERN = Regex("(?i)(^|/)(o[1-9])(-|$)|gpt-5|gpt5")
    private val QWEN3_PATTERN = Regex("(?i)qwen3|qwen-3")
    private val ALWAYS_ON_PATTERN = Regex("(?i)deepseek-r|deepseek-reasoner|qwq|glm-z|magistral|reasoner")

    /**
     * 判定模型家族。
     *
     * @param provider 配置里的 provider 名（`OPENAI` / `OTHER` / …），仅作为弱信号。
     * @param baseUrl 用户填的端点地址 —— DashScope 的判定**只能**靠它（同一模型名在
     *   百炼上走顶层 `enable_thinking`，在自建 vLLM 上走 `chat_template_kwargs`）。
     * @param model 模型名。
     */
    fun detectFamily(provider: String, baseUrl: String, model: String): Family {
        val m = model.trim()
        val url = baseUrl.lowercase()
        val isDashScope = url.contains("dashscope") || url.contains("aliyuncs.com")

        // 1. 恒思考模型优先：它们没有开关，无论怎么配都只能「什么都不发」。
        if (ALWAYS_ON_PATTERN.containsMatchIn(m)) return Family.ALWAYS_ON

        // 2. Anthropic：端点与模型名任一命中即可（中转常把模型名写成 anthropic/claude-*）。
        if (ANTHROPIC_PATTERN.containsMatchIn(m) || url.contains("anthropic")) {
            return Family.ANTHROPIC_THINKING
        }

        // 3. Qwen3 系：端点决定用哪套字段。
        //    这里的取舍很关键 —— 百炼兼容端点只认顶层 enable_thinking，
        //    而 vLLM/SGLang 只认 chat_template_kwargs；发错那一侧会被静默忽略
        //    （不报错但也不生效），比报错更难查。
        if (QWEN3_PATTERN.containsMatchIn(m)) {
            return if (isDashScope) Family.DASHSCOPE_TOGGLE else Family.QWEN3_TEMPLATE
        }

        // 4. OpenAI 推理系。
        if (OPENAI_EFFORT_PATTERN.containsMatchIn(m)) return Family.OPENAI_EFFORT

        // 5. 其余模型：可能支持 reasoning_effort（新版网关对 gpt-oss 等也认），
        //    但我们**不赌** —— 探测不到就什么都不发，避免把 400 甩给用户。
        return Family.NONE
    }

    /**
     * 编译思考控制参数。
     *
     * @param level 目标思考档位。关思考时并不是所有家族都能表达 ——
     *   [Family.ALWAYS_ON] 表达不了（返回空 plan），会在 [ReasoningPlan.notes] 里写明。
     * @param maxTokens 本轮 `max_tokens`。Anthropic 的 `budget_tokens` 必须 `< max_tokens`，
     *   故预算是它的函数而非固定值。
     */
    fun plan(
        provider: String,
        baseUrl: String,
        model: String,
        level: ThinkingLevel,
        maxTokens: Int,
    ): ReasoningPlan {
        val family = detectFamily(provider, baseUrl, model)
        val notes = mutableListOf<String>()
        val result = when (family) {
            Family.NONE -> {
                notes += "未识别的模型家族，不下发思考参数（与改造前行为一致）"
                ReasoningPlan()
            }

            Family.ALWAYS_ON -> {
                notes += "恒思考模型：没有开关可拨，思考强度由模型自身决定"
                // 关思考对恒思考模型是无意义的承诺 —— 主动告知，而不是装作已生效。
                if (level == ThinkingLevel.OFF) {
                    notes += "⚠️ 该模型无法关闭思考，「关」在本模型上不生效"
                }
                ReasoningPlan()
            }

            Family.OPENAI_EFFORT -> compileOpenAi(level, notes)

            Family.ANTHROPIC_THINKING -> compileAnthropic(level, maxTokens, notes)

            Family.QWEN3_TEMPLATE -> ReasoningPlan(
                chatTemplateEnableThinking =
                    if (level == ThinkingLevel.AUTO) null else level != ThinkingLevel.OFF,
                notes = notes.apply {
                    add("Qwen3 系（vLLM/SGLang）：chat_template_kwargs.enable_thinking")
                    when (level) {
                        // AUTO 刻意不发：Qwen3 默认开思考，不干预 = 保持它的默认（开）。
                        ThinkingLevel.AUTO -> add("不干预（AUTO）：不下发，保持 Qwen3 自身默认（开）")
                        ThinkingLevel.OFF -> add("显式关闭 —— Qwen3 默认开思考，不显式关就关不掉")
                        else -> add("显式开启")
                    }
                },
            )

            Family.DASHSCOPE_TOGGLE -> ReasoningPlan(
                topLevelEnableThinking =
                    if (level == ThinkingLevel.AUTO) null else level != ThinkingLevel.OFF,
                notes = notes.apply {
                    add("DashScope 兼容端点：顶层 enable_thinking")
                    if (level == ThinkingLevel.AUTO) add("不干预（AUTO）：不下发")
                },
            )
        }
        return result.copy(
            family = family,
            level = level,
            // 各分支自己造的 plan 不带 notes（notes 是它们构造时用的局部量）；
            // 少数分支会自带（如 ALWAYS_ON 的"无法关闭"警告），此时以自带为准。
            notes = result.notes.ifEmpty { notes },
        )
    }

    /**
     * OpenAI o 系 / GPT-5.x。
     *
     * 两个历史包袱必须带上：
     * - 只支持 `max_completion_tokens`，发 `max_tokens` 直接 400；
     * - 不支持 `temperature`，发了同样 400。
     * 这两条在改造前只对 `^o[0-9]` 生效，GPT-5 全系被漏掉 —— 于是「选 GPT-5 就报错」。
     */
    private fun compileOpenAi(level: ThinkingLevel, notes: MutableList<String>): ReasoningPlan {
        // 🔴 下面两个标志是**模型固有能力**，与思考档位无关：GPT-5 / o 系无论拨到哪一档，
        //    都不接受 temperature、且只认 max_completion_tokens。
        //    改造前这段判断只对 `^o[0-9]` 生效，GPT-5 全系漏网 ——
        //    直接后果就是用户「一选 GPT-5 就报错」。所以 AUTO 档也必须带走这两个标志。
        if (level == ThinkingLevel.AUTO) {
            notes += "不干预思考（AUTO）：不传 reasoning_effort，沿用服务端默认档位"
            notes += "GPT-5/o 系不接受 temperature，本档位省略之"
            return ReasoningPlan(suppressTemperature = true, useMaxCompletionTokens = true)
        }
        // OFF 时下发 "none"：这是显式关闭，比「不传、听服务端默认」可控得多。
        // 注意老 GPT-5 只认 minimal 不认 none、新版反之 —— 我们取 none 并在 notes 里留痕，
        // 因为「用户明确关思考」的语义必须被尊重，值不被接受时至少报错可见、可改。
        val effort = when (level) {
            ThinkingLevel.OFF -> "none"
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            // AUTO 已在函数开头提前 return，这里不可达 —— 列出来只为让 when 穷尽。
            ThinkingLevel.AUTO -> "medium"
        }
        notes += "OpenAI 推理系：顶层 reasoning_effort=$effort（显式下发，不依赖跨版本会变的默认档位）"
        if (level == ThinkingLevel.OFF) {
            notes += "⚠️ 若上游只认 minimal 不认 none，会返回 unsupported_value —— 那是上游档位差异，非本应用错误"
        }
        notes += "GPT-5/o 系不接受 temperature，本档位省略之"
        return ReasoningPlan(
            reasoningEffort = effort,
            suppressTemperature = true,
            useMaxCompletionTokens = true,
        )
    }

    /**
     * Anthropic Claude。
     *
     * 约束很硬，违反了就是 400：
     * - `budget_tokens >= 1024`；
     * - `budget_tokens < max_tokens`（思考 token 计入 max_tokens）；
     * - 开启 thinking 后不接受 `temperature` / `top_p` / `top_k`，也不支持强制工具调用。
     *
     * 因此预算取 `maxTokens * 0.6`（官方建议 40%–60%），并双向钳制；钳不出合法值就**放弃下发**。
     */
    private fun compileAnthropic(
        level: ThinkingLevel,
        maxTokens: Int,
        notes: MutableList<String>,
    ): ReasoningPlan {
        if (level == ThinkingLevel.AUTO) {
            notes += "不干预思考（AUTO）：Claude 默认不开思考，不传 thinking"
            return ReasoningPlan()
        }
        if (level == ThinkingLevel.OFF) {
            // Claude 默认不开思考，不传即关闭 —— 无需（也不应）传 disabled，
            // 部分新模型（Fable/Mythos 系）收到 disabled 会直接 400。
            notes += "Claude：默认不开思考，不传 thinking 即关闭"
            return ReasoningPlan()
        }

        val minBudget = 1024
        // budget 必须严格小于 max_tokens；留 1024 给最终答复，避免「想完了没 token 说话」。
        val upper = maxTokens - minBudget
        if (upper < minBudget) {
            notes += "⚠️ max_tokens=$maxTokens 太小，无法满足 budget_tokens(<$maxTokens 且 >=$minBudget)，本次不下发 thinking"
            return ReasoningPlan()
        }
        val raw = (maxTokens * ANTHROPIC_BUDGET_RATIO).toInt()
        val budget = raw.coerceIn(minBudget, upper)
        notes += "Claude：thinking.enabled budget_tokens=$budget（max_tokens=$maxTokens 的 ${(ANTHROPIC_BUDGET_RATIO * 100).toInt()}%，钳在 [$minBudget, $upper]）"
        notes += "Claude 开启 thinking 后不接受 temperature，本档位省略之"
        return ReasoningPlan(
            thinkingType = "enabled",
            thinkingBudgetTokens = budget,
            suppressTemperature = true,
        )
    }

    /** Anthropic 思考预算占 max_tokens 的比例（官方建议区间 0.4–0.6，取中上）。 */
    private const val ANTHROPIC_BUDGET_RATIO = 0.6

    /**
     * 把「深度思考」开关（bool）映射为档位。
     *
     * 开关的文案是「更慢但更深」，因此打开即 [ThinkingLevel.HIGH] —— 若映射到 MEDIUM，
     * 用户会得到一个「开了但没感觉」的开关，正是本次要消灭的那类问题。
     */
    fun levelForDeepThink(deepThink: Boolean): ThinkingLevel =
        if (deepThink) ThinkingLevel.HIGH else ThinkingLevel.AUTO
}
