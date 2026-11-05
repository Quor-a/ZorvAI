package com.ai.assistance.quro.core.soul

import com.ai.assistance.quro.core.QuroPersona

/**
 * 每攒够 N 轮「成功完成的回复」，在满足冷却间隔后触发一次 LLM 总结，
 * 产出两份增量——「人格画像（Soul）」与「关键事实（Memory）」，
 * 回填到 [QuroPersona.soul] 与记忆库。让 AI 的人格随相处持续成长，而非停留在静态 roleSetting。
 *
 * 设计边界：
 * - 本文件只负责**纯逻辑**（计数、冷却、触发判定、频率档位换算），不碰 Android 存储 / 网络。
 *   实际的 LLM 调用与落盘由 ViewModel 注入的 `summarize` 回调完成，便于单测。
 * - 计数与冷却的持久化状态由 [EvolutionState] 表达，由调用方（ViewModel）持有并落盘。
 */

/** 进化频率三档（对齐 bot_evolution_frequency_quiet/standard/active）。 */
enum class EvolutionFrequency(val turnsPerCheck: Int, val minIntervalHours: Int) {
    /** 安静：攒更多轮才查、冷却更久。 */
    QUIET(turnsPerCheck = 20, minIntervalHours = 24),

    /** 标准：默认档。 */
    STANDARD(turnsPerCheck = 10, minIntervalHours = 6),

    /** 积极：攒少轮即查、冷却短。 */
    ACTIVE(turnsPerCheck = 5, minIntervalHours = 1);
}

/**
 * 由调用方在每轮回复完成时推进，并在触发/冷却/失败时更新。
 */
data class EvolutionState(
    /** 已累计的「成功完成回复」轮数。 */
    val completedTurns: Int = 0,
    /** 最近一次成功进化检查的时刻（毫秒）。0 = 尚未检查过。 */
    val lastCheckedAt: Long = 0,
    /** 最近一次检查失败的文本原因（用于进度展示）。空 = 无失败。 */
    val lastFailure: String = "",
    /** 失败后的下次可重试时刻（毫秒）。0 = 无重试等待。 */
    val retryAfter: Long = 0,
)

/**
 */
data class EvolutionOutput(
    /** 人格画像增量（Markdown 片段），回填/追加到 Soul。 */
    val soulDelta: String,
    /** 关键事实条目（每条作为一条记忆入库）。 */
    val memoryFacts: List<String>,
    /**
     * 🔴 待学清单（2026-11-04 孵化/进化合体，学习进步导向）：
     * 每条格式 `待办|待学 :: 标题 :: 建议`。产出写到 persona.openItems，
     * 作为下一阶段的学习计划——下次进化时回喂给引擎检查完成情况。
     */
    val openItems: List<String> = emptyList(),
    /** 产出时刻。 */
    val generatedAt: Long = System.currentTimeMillis(),
    val source: String = "soul_memory",
)

object BotEvolution {
    /** 冷却判定：距离上次检查是否已过 [minIntervalHours]（0 = 不限）。 */
    fun isCoolingDown(
        state: EvolutionState,
        frequency: EvolutionFrequency,
        now: Long = System.currentTimeMillis(),
    ): Boolean = isCoolingDown(state, frequency.turnsPerCheck, frequency.minIntervalHours, now)

    /**
     * 每张人格卡自带「最少间隔小时数 + 触发轮数」，调用方直接按 Bot 传值，
     * 不再被写死的 EvolutionFrequency 档位限死（2026-11-04）。
     */
    fun isCoolingDown(
        state: EvolutionState,
        @Suppress("UNUSED_PARAMETER") turnsPerCheck: Int,
        minIntervalHours: Int,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (minIntervalHours <= 0) return false
        if (state.lastCheckedAt == 0L) return false
        val minGap = minIntervalHours.toLong() * 3600_000L
        return now - state.lastCheckedAt < minGap
    }

    /** 失败重试等待判定：是否还在失败后的退避窗口内。 */
    fun isRetryWaiting(state: EvolutionState, now: Long = System.currentTimeMillis()): Boolean =
        state.retryAfter > 0L && now < state.retryAfter

    /**
     * 本轮回复完成后，是否应当触发一次进化检查。
     * 条件（全部满足）：
     *  - evolutionEnabled（调用方先判，这里不重复）；
     *  - 已攒够 [frequency.turnsPerCheck] 轮；
     *  - 不在冷却窗口；
     *  - 不在失败重试等待窗口。
     */
    fun shouldCheck(
        state: EvolutionState,
        frequency: EvolutionFrequency,
        now: Long = System.currentTimeMillis(),
    ): Boolean = shouldCheck(state, frequency.turnsPerCheck, frequency.minIntervalHours, now)

    /** 🔴 Bot 级调度重载：轮数 + 最小间隔小时数由人格卡直接给出。 */
    fun shouldCheck(
        state: EvolutionState,
        turnsPerCheck: Int,
        minIntervalHours: Int,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (state.completedTurns < turnsPerCheck) return false
        if (isCoolingDown(state, turnsPerCheck, minIntervalHours, now)) return false
        if (isRetryWaiting(state, now)) return false
        return true
    }

    /** 推进一轮成功回复的计数。返回新状态（未到阈值时仅 +1）。 */
    fun onTurnCompleted(state: EvolutionState): EvolutionState =
        state.copy(completedTurns = state.completedTurns + 1)

    /** 一次成功检查完成后重置计数、记录检查时刻、清空失败痕迹。 */
    fun onChecked(state: EvolutionState, now: Long = System.currentTimeMillis()): EvolutionState =
        state.copy(
            completedTurns = 0,
            lastCheckedAt = now,
            lastFailure = "",
            retryAfter = 0,
        )

    /** 一次检查失败：记录失败原因，并进入退避（默认 1 小时后可重试）。 */
    fun onFailed(
        state: EvolutionState,
        reason: String,
        now: Long = System.currentTimeMillis(),
        backoffMs: Long = 3600_000L,
    ): EvolutionState =
        state.copy(lastFailure = reason, retryAfter = now + backoffMs)

    /**
     * 从人格卡 + 最近对话片段，构建「进化检查」用的提示词。
     * 产出要求：一段 Markdown 的人格画像增量（Soul）+ 若干条关键事实（Memory），
     * 用固定分隔标记让调用方可靠解析。
     */
    fun buildPrompt(persona: QuroPersona, recentTranscript: String): String = buildString {
        append("你是「${persona.name}」这一人格的自我进化引擎。\n")
        append("下面这段是你与用户最近几轮的真实对话片段。请从这段相处中，总结出三点：\n\n")
        append("1. 人格画像增量（Soul，🔴 只写你自己的成长）：这段时间**你自己**学会了什么、")
        append("哪些方面做得更好了、形成了什么新的行为方式或能力、吸取了什么教训。")
        append("用 1-3 句中文写出，作为对现有画像的补充。")
        append("🔴 **禁止写用户的喜好/信息/约定** —— 用户相关的事实属于第 2 点，写进 Soul 就是错误。")
        append("如果没有关于自身成长的新发现，写「无」。\n")
        append("2. 关键事实（Memory）：值得长期记住的关于**用户**的事实/偏好/约定，每条一句，")
        append("用换行分隔；如果没有，写「无」。\n")
        append("3. 待学清单（Open Items，🔴 学习进步导向——这是孵化的核心产出）：从对话里发现的")
        append("「我还不擅长/没答好/值得下次做得更好」的点，以及用户待办/约定。每条一行，")
        append("格式严格为 `- 待办|待学 :: 一句话标题 :: 下次怎么做的建议`（:: 两侧有空格，类型只能是「待办」或「待学」），")
        append("最多 5 条，按重要度排序；没有就写「无」。它们会成为你的学习计划，驱动你下一阶段进步——")
        append("这不是修改设定，而是给自己列作业。\n\n")
        append("严格按下面格式输出，不要任何额外解释或标题：\n")
        append("[SOUL]\n<人格画像增量>\n[FACTS]\n<事实1>\n<事实2>\n[OPEN]\n- 待学 :: 学会主动追问澄清 :: 用户描述模糊时先确认再动手\n\n")
        if (persona.soul.isNotBlank()) {
            append("你现有的人格画像如下，增量应与其互补而非重复：\n")
            append(persona.soul.take(2000)).append("\n\n")
        }
        if (persona.openItems.isNotBlank()) {
            append("你上一阶段的待学清单（本次应检查完成情况，已完成的不再列出，长期未完成的换一种学法）：\n")
            append(persona.openItems.take(1200)).append("\n\n")
        }
        append("对话片段：\n").append(recentTranscript.take(16000))
    }

    /** 解析 LLM 返回的 [SOUL]/[FACTS]/[OPEN] 结构。返回 null 表示无法解析（视为失败）。 */
    fun parseOutput(text: String): EvolutionOutput? {
        val soulMarker = "[SOUL]"
        val factsMarker = "[FACTS]"
        val openMarker = "[OPEN]"
        val si = text.indexOf(soulMarker)
        val fi = text.indexOf(factsMarker)
        if (si < 0 || fi <= si) return null

        val soulRaw = text.substring(si + soulMarker.length, fi).trim()
        val oi = text.indexOf(openMarker)
        val factsRaw = if (oi > fi) text.substring(fi + factsMarker.length, oi).trim()
        else text.substring(fi + factsMarker.length).trim()
        val openRaw = if (oi > fi) text.substring(oi + openMarker.length).trim() else ""

        val soulDelta = if (soulRaw.isBlank() || soulRaw == "无") "" else soulRaw
        val memoryFacts = factsRaw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "无" }
            .map { it.removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotBlank() }
        // 🔴 待学清单（学习进步导向，2026-11-04 孵化/进化合体）：`- 待办|待学 :: 标题 :: 建议`
        val openItems = openRaw.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotBlank() && it != "无" && it.contains("::") }
            .take(5)

        // 两者皆空 → 本次没有可沉淀的增量，仍算「成功但空产出」，返回空产出。
        return EvolutionOutput(soulDelta = soulDelta, memoryFacts = memoryFacts, openItems = openItems)
    }

    /** 把人格画像增量合并进现有 Soul（增量追加，避免覆盖用户手写内容）。 */
    fun mergeSoul(existing: String, delta: String): String {
        val trimmed = delta.trim()
        if (trimmed.isBlank()) return existing
        return if (existing.isBlank()) trimmed else "$existing\n$trimmed"
    }
}
