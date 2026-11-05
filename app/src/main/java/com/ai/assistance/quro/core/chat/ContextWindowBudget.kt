package com.ai.assistance.quro.core.chat

/**
 * 上下文窗口的**构成明细**。
 *
 * ## 为什么需要它
 *
 * 现在只有一个总数（`prompt_tokens`），用户看到「这轮用了 42k」，
 * 但没法回答三个最常问的问题：
 *
 * 1. 「我明明只说了两句话，为什么吃掉半个窗口？」—— 因为系统提示词 + 工具 schema 占了 30k。
 * 2. 「是什么把它挤爆的？」—— 是 MCP 工具太多、还是记忆文件太长、还是历史消息没清。
 * 3. 「现在还剩多少？」—— 剩余空间与自动压缩预留，直接决定「还能聊几轮」。
 *
 * ## 估算口径
 *
 * 拿不到 tokenizer（本地模型各有各的），所以按语言混合估算：
 * CJK 统一按 1 字 ≈ 1 token（偏保守），其余按 4 字符 ≈ 1 token。
 * 估算值与真实值的偏差通常在 ±10%，够用来做**结构占比**判断 ——
 * 用户需要的是「哪一块最大」，不是精确到个位的数字。
 *
 * 纯 Kotlin，可单测。不依赖 Android、不依赖 tokenizer。
 */

/** 上下文的来源分段。顺序即 UI 展示顺序（从「你改不了」到「你能改」）。 */
enum class ContextSegmentKind {
    /** 系统提示词（含回复纪律、人格、运行时约定）。 */
    SYSTEM_PROMPT,

    /** 内置工具的函数 schema。 */
    SYSTEM_TOOLS,

    /** MCP 服务器提供的工具 schema。 */
    MCP_TOOLS,

    /** 技能元数据目录；用户随消息选中下发的技能正文按消息段统计，避免重复计算。 */
    SKILLS,

    /** 记忆 / 经验沉淀 / 人格沉淀。 */
    MEMORY,

    /** 对话历史。 */
    MESSAGES,

    /** 本轮附件转写、检索结果等临时注入。 */
    INJECTED,

    /** 其他未能归类的部分。 */
    OTHER,
}

/**
 * 一段占用。
 *
 * @param kind 来源。
 * @param chars 该段的原始字符数（便于「点开看看是什么在吃 token」）。
 * @param tokens 估算 token。
 */
data class ContextSegment(
    val kind: ContextSegmentKind,
    val chars: Int,
    val tokens: Int,
)

/**
 * 一次窗口占用快照。
 *
 * @param window 模型窗口上限；`<= 0` 表示未知（此时所有百分比都不显示）。
 * @param segments 各段占用，已按 token 降序。
 * @param reservedForReply 为本轮回复预留的额度（不参与「已用」）。
 */
data class ContextWindowUsage(
    val window: Int,
    val segments: List<ContextSegment>,
    val reservedForReply: Int,
) {
    val used: Int get() = segments.sumOf { it.tokens }

    /** 已用占窗口的比例（0f..1f）；窗口未知时为 0f。 */
    val usedRatio: Float
        get() = if (window <= 0) 0f else (used.toFloat() / window).coerceIn(0f, 1f)

    /** 剩余可用（扣掉回复预留）；窗口未知时返回 0。 */
    val free: Int
        get() = if (window <= 0) 0 else (window - used - reservedForReply).coerceAtLeast(0)

    /**
     * 是否已进入「该压缩了」的区间。
     *
     * 🔴 2026-11-06 修正：旧判据 `free < reservedForReply * 2` 在默认 1M 窗口下需要
     * 用掉约 67 万 token 才为 true——绝大多数对话永远到不了，提示条/圆环/自动压缩
     * 全部形同虚设。现在改为双条件取或：
     *  - 已用比例 ≥ 70%（对齐 QuroModelConfig.contextCompressThreshold 的 80% 语义，
     *    留出提前量让摘要压缩有机会在超限前完成）；
     *  - 或剩余空间不够再聊两轮（原判据保留，覆盖小窗口模型）。
     */
    val shouldCompact: Boolean
        get() {
            if (window <= 0) return false
            if (usedRatio >= 0.70f) return true
            return free < (reservedForReply * 2)
        }

    /** 剩余空间已经不足以放下一轮正常回复。 */
    val isCritical: Boolean
        get() = window > 0 && free <= 0
}

object ContextWindowCalculator {

    /** 回复预留：窗口的 12%，且不低于 2k（小窗口模型不至于被预留吃空）。 */
    fun reserveForReply(window: Int): Int {
        if (window <= 0) return 0
        return maxOf(2048, (window * 0.12f).toInt())
    }

    /**
     * 语言混合的 token 估算。
     *
     * CJK（含全角标点）按 1 字 ≈ 1 token；ASCII 与其他按 4 字符 ≈ 1 token。
     * 空串返回 0；非空至少返回 1（避免「一个字的提示词算 0 token」）。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isCjk(cp)) cjk++ else other++
            i += Character.charCount(cp)
        }
        val est = cjk + (other + 3) / 4
        return if (est <= 0) 1 else est
    }

    /** 是否按「一字一 token」计的字符（CJK、假名、韩文、全角标点）。 */
    private fun isCjk(cp: Int): Boolean = when (cp) {
        in 0x2E80..0x9FFF,   // CJK 部首 / 标点 / 假名 / 汉字
        in 0xAC00..0xD7AF,   // 韩文音节
        in 0xF900..0xFAFF,   // CJK 兼容汉字
        in 0xFF00..0xFFEF,   // 全角形式
        in 0x3000..0x303F,   // CJK 标点
        in 0x20000..0x2FA1F, // 扩展 B~F
        -> true
        else -> false
    }

    /**
     * 组装快照。
     *
     * @param parts 各段原始文本；用 [ContextSegmentKind] 为键，缺省视为 0。
     * @param window 模型窗口上限，未知填 0。
     */
    fun build(
        parts: Map<ContextSegmentKind, String>,
        window: Int,
    ): ContextWindowUsage {
        val segs = parts.map { (kind, text) ->
            ContextSegment(kind, text.length, estimateTokens(text))
        }
            .filter { it.tokens > 0 }
            .sortedByDescending { it.tokens }
        return ContextWindowUsage(
            window = window,
            segments = segs,
            reservedForReply = reserveForReply(window),
        )
    }
}
