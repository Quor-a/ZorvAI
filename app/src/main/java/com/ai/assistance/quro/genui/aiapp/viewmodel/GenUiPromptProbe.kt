package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage

/**
 * GenUI 提示词构建的**测试探针**。
 *
 * 只做一件事：把 [ChatViewModel] 里的私有 `renderRulesMessage` / `replyLanguageMessage`
 * 以可测形式暴露出来，供 `GenUiRenderPipelineTest` 断言「渲染通道提示词不自相矛盾」。
 *
 * 为什么不直接把 `renderRulesMessage` 从 private 改成 internal：
 * 它是 ViewModel 的实例方法，而构造 ViewModel 需要 `Application`（单测里没有），
 * 提成纯函数又会牵动调用点。探针把逻辑复制一份反而会漂移 ——
 * 所以这里**委托**给一份与生产同源的纯函数实现（见 `renderRulesContent`），
 * 并由 `ChatViewModel.renderRulesMessage` 同样调用它，保证测试断言的就是生产文案。
 */
internal object GenUiPromptProbe {

    /**
     * 历史 assistant 消息的写法分流（本轮真机对照查出的根因）。
     *
     * ## 背景：为什么这需要单独一条判据
     *
     * 用户反证「ZorvAI 对话框体量更大却能完整执行，而 GenUI Agent 不行」推翻了
     * 「提示词体积是根因」—— 主对话同样全量下发 258 个工具（`PROGRESSIVE = false`），
     * 体量一样大却跑得通。真正的差异在**历史写法**：
     * · 主对话把模型原文完整写进历史 → 模型能延续格式、能自我修正；
     * · GenUI 自 `78bebff`（09-23）起把输出剥成 prose → 历史里只剩「〔已生成：xxx〕」
     *   → 模型不知道自己上一轮写对了什么 → 每轮盲猜围栏格式
     *   → 「所有类型界面都画不出来」+「有时候模型都不通」。
     *
     * `78bebff` 的动机（防「模型模仿历史里的残缺格式」）对，手段（一刀切剥光）错。
     * 所以判据从「是不是界面」改成「**是否完整**」。
     *
     * @param complete 本轮输出是否**确实完整**（已成功解析出 JSON / 围栏已闭合）
     * @return `true` = 原文照存（格式锚点）；`false` = 该降级成 prose
     */
    fun keepOriginalInHistory(complete: Boolean): Boolean = complete

    /**
     * 历史 assistant 消息的**唯一实现**（生产侧 [ChatViewModel.historyAssistant] 委托它）。
     *
     * @param fullText 本轮模型原始输出
     * @param label 兜底占位用的标题
     * @param complete 本轮输出是否完整（见 [keepOriginalInHistory]）
     * @param degrade 残缺时的降级函数（生产侧传剥围栏/剥 JSON 的 `historyNote`；
     *        单测里传一个简化版，避免依赖 ViewModel 实例）
     */
    fun historyAssistant(
        fullText: String,
        label: String,
        complete: Boolean,
        degrade: (String) -> String = { fallbackNote(label) },
    ): String {
        if (keepOriginalInHistory(complete)) return fullText.trim()
        return degrade(fullText)
    }

    /** 残缺输出在没有可读人话时的占位提示。 */
    fun fallbackNote(label: String): String = "〔已生成：$label〕"

    /**
     * 渲染规则的**唯一实现**。`ChatViewModel.renderRulesMessage` 与本探针都调它，
     * 测试断言的文案与真机发出的文案因此永远一致。
     */
    fun renderRulesContent(forced: String?): String = buildString {
        append("# 上下文用法（每轮都读）\n")
        append("上面的历史消息只是背景资料。你只执行【最后一条】用户消息。\n")
        append("禁止把历史里的旧指令重新执行、复述、编号或汇总成清单；")
        append("禁止回复「您发出了很多条指令」「我来逐条理解」这类元话术。\n")
        append("用户这一轮没提的事就不要提，直接用 UI 回应最后那条消息。\n\n")
        if (forced == "genui") {
            append("# 本轮通道已锁定：GenUI SDK（用户已在询问弹窗里确认，最高优先级）\n")
            append("用户本轮选的是 GenUI SDK 原生通道，必须走 GenUI 流程，按下面的输出格式生成界面。\n")
            // 与下面 else-if 分支同理：禁列动态生成 = 「除本通道外全部」，
            // 这样将来新增通道时不会漏改、也不会把本通道误禁。
            val others = com.ai.assistance.quro.genui.aiapp.data.RenderChannel.values()
                .filter { it.key != forced }
                .joinToString(" / ") { it.key }
            append("绝对禁止输出其它通道的围栏（").append(others).append("）；不许用一篇文章代替界面。\n")
        } else if (forced != null) {
            append("# 本轮通道已由用户锁定（最高优先级）\n")
            append("用户明确指定本轮必须使用三反引号").append(forced).append("围栏输出。\n")
            // 🔴 禁列必须排除本通道：写死四个会让 forced=html 时变成
            // 「必须用 ```html」+「```html 都不行」的自相矛盾（真机模型已在吐槽这段）。
            val others = com.ai.assistance.quro.genui.aiapp.data.RenderChannel.values()
                .filter { it.key != forced }
                .joinToString(" / ") { "```" + it.key }
            append("绝对禁止输出其他通道的围栏（").append(others).append(" 都不行）；\n")
            append("不要输出 intent/plan/generate 结构；围栏外不得有任何文字。\n")
        } else {
            append("# 输出通道选择（第一优先级）\n")
            append("- GenUI SDK 通道是主力默认：生成式界面/交互应用一律走 GenUI 流程\n")
            append("- 用户点名要某通道（用 markdown/a2ui/html 写）→ 必须按用户指定的通道输出\n")
            append("- 用户未点名时，按内容类型自选：纯文章/攻略/新闻/长文 → markdown 围栏；")
            append("独立网页/复杂样式/可玩小游戏 → html 围栏；简单结构化展示/省 token → a2ui 围栏\n")
            append("选择非 GenUI 通道时：直接输出对应围栏，围栏外不得输出任何文字，不要输出 intent/plan/generate。\n")
        }
        append("\n")
        if (forced == null || forced == "genui") {
            append("# GenUI 流程输出格式（生成式界面时适用）\n")
            append("1. content 通道结构固定：<intent>简短思考</intent> → <plan>规划</plan> → ")
            append("<generate>```genui\n{完整 JSON}\n```</generate>")
        }
    }

    /** 便捷入口：拿渲染规则的纯文本（测试用） */
    fun renderRules(forced: String?): String = renderRulesContent(forced)

    /** 与 ChatViewModel.replyLanguageMessage 同型的空实现占位（中文下恒为空） */
    fun languageDirectiveOrNull(): GenUIChatMessage? = null

    // ═══════════════ 未闭合思考标签：破坏性截断的判定 ═══════════════

    /**
     * 思考标签清单（与 `ChatViewModel.stripThinkingTags` 第 1 步同源）。
     * 抽成常量是为了让 [findUnclosedThinkingTag] 可在 JVM 单测里跑（它不碰 Android 资源）。
     */
    val THINKING_TAGS: List<String> = listOf(
        "thinking", "reasoning", "thought", "intent", "intention", "intint",
        "retrieve", "retrieval", "search",
        "plan", "planning",
        "tool_call", "toolcall", "tool", "tool_result", "toolResult",
        "decision", "decide",
        "self_correct", "self_correction", "selfcorrect", "self-correct",
        "journal", "log",
        "analysis", "analyze", "analyse",
        "reflection", "reflect",
        "observation", "observe",
        "action", "step",
        "context", "memory",
        "clarify", "question",
        "draft", "outline",
        "verify", "validation", "validate", "check",
        "debug", "trace", "inspect",
        "component_check", "type_check", "schema_check"
    )

    /**
     * 找出**第一个没有后继闭标签**的开标签位置；没有则 null。
     *
     * 🔴 旧实现用 `openMatches.last()`（最后一次出现），当同一标签出现多次时截断起点会算到
     * 更靠后的位置，判定错位。现改为「首个无后继闭标签者」。
     */
    fun findUnclosedThinkingTag(text: String): Int? {
        var earliestOpen: Int? = null
        for (tag in THINKING_TAGS) {
            val openRegex = Regex("<$tag(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
            val closeRegex = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)
            val opens = openRegex.findAll(text).map { it.range.first }.toList()
            val closes = closeRegex.findAll(text).map { it.range.first }.toList()
            if (opens.size <= closes.size) continue
            val unmatched = opens.firstOrNull { o -> closes.none { it > o } } ?: continue
            if (earliestOpen == null || unmatched < earliestOpen) earliestOpen = unmatched
        }
        return earliestOpen
    }

    /**
     * 截断点之后是否仍含**可渲染内容**（围栏 / JSON 根键）。
     *
     * 🔴 这是「界面画不出来」的最后一道闸：未闭合思考标签曾被无条件截断，
     * 而系统提示词恰恰在教模型输出 `<intent>/<plan>/<generate>` 结构，
     * 模型少闭合一个标签时，围栏与整份 JSON 就被一起丢掉 → 画布空/只显示源码。
     * 只要截断点之后还有围栏或 JSON 根键，就**不能**截断。
     */
    fun tailHasRenderablePayload(text: String, from: Int): Boolean {
        if (from !in 0..text.length) return false
        val tail = text.substring(from)
        return tail.contains("```") ||
            tail.contains("\"root\"") || tail.contains("\"properties\"")
    }

    /**
     * 段首（[0, endExclusive)）是否已含可渲染内容。
     * 用于「孤立闭合标签」那一刀：模型把正式输出放在思考内容**前面**时不能再截。
     */
    fun headHasRenderablePayload(text: String, endExclusive: Int): Boolean {
        if (endExclusive <= 0) return false
        val head = text.substring(0, endExclusive.coerceAtMost(text.length))
        return head.contains("```") ||
            head.contains("\"root\"") || head.contains("\"properties\"")
    }
}
