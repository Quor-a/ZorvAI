package com.ai.assistance.quro.core.mika.skills

import com.ai.assistance.quro.core.mika.agent.FailKind
import com.ai.assistance.quro.core.mika.agent.LearnGate
import com.ai.assistance.quro.core.mika.agent.RunOutcome
import com.ai.assistance.quro.core.mika.agent.TaskState

@Suppress("EnumEntryName")
/**
 * [verified]:办成了([LearnGate]),试用据此转正式(存下的名字沿用,旧记录照样读);
 * [unverified]:判下来办成,核查却没通过;[unreviewed]:没判成结局,不说明技能灵不灵,停用时不算。
 */
enum class SkillResult { verified, unverified, failed, verifier_rejected, unreviewed }

/**
 * 结果卡片下的技能提示:「ZorvAI 学会了一项技能:… · 看看」([draftId])、
 * 「这项技能最近不灵,先停用了 · 恢复 · 删除」([suspended] 是技能名)。
 */
data class SkillNote(val text: String, val draftId: String? = null, val suspended: String? = null)

/** 个人技能的最近使用结果与自动停用(运行中不灵就停用)。 */
object SkillOutcomes {
    const val KEEP = 5
    private const val WINDOW = 3
    private const val BAD_IN_WINDOW = 2
    /** 结果卡片与核查退回:照着技能做却被核查退回,判为 verifier_rejected。 */
    const val VERIFIER_REJECTED_MARK = "核查未通过"

    /** 追加一次结果并只留最近 [KEEP] 次;按规则可能转为停用。 */
    fun record(s: PersonalSkill, r: SkillResult, followed: Boolean): PersonalSkill {
        val outcomes = (s.outcomes + r.name).takeLast(KEEP)
        if (s.status == SkillStatus.trial) return s.copy(outcomes = outcomes, status = when (r) {
            SkillResult.verified -> SkillStatus.active
            SkillResult.failed, SkillResult.verifier_rejected -> SkillStatus.suspended
            else -> SkillStatus.trial
        })
        val suspend = s.status == SkillStatus.active &&
            shouldSuspend(outcomes, followed && r == SkillResult.verifier_rejected)
        return s.copy(outcomes = outcomes, status = if (suspend) SkillStatus.suspended else s.status)
    }

    /** 最近 3 次(没核对过的不算)里有 2 次不是核实过的,或这次照着做被核查退回。 */
    fun shouldSuspend(all: List<String>, lastFollowedRejected: Boolean): Boolean {
        val outcomes = all.filter { it != SkillResult.unreviewed.name }
        return lastFollowedRejected || outcomes.takeLast(WINDOW).count { it != SkillResult.verified.name } >= BAD_IN_WINDOW
    }

    /**
     * 任务结局对应的结果;取消的不记(null)。[outcome] 是交出回答后判的结局([RunOutcome]):
     * 回答如实说没办成、只办了一部分的算 failed;没判成的不说明技能灵不灵,算 unreviewed;
     * 办成的按 [LearnGate] 算 verified(没跑核查也算),核查跑过却没通过的算 unverified;
     * 失败的只有放弃、步数用完算 failed,模型出错、环境出问题、操作保护停下的算 unreviewed。
     */
    fun resultOf(end: TaskState, outcome: RunOutcome): SkillResult? = when (end) {
        is TaskState.Succeeded -> when {
            outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible -> SkillResult.failed
            // 退回一次后仍有没对上的:照着技能做也没做对,和以前被核查退回一样(判没判成都是)
            end.unconfirmed.isNotEmpty() -> SkillResult.verifier_rejected
            outcome == RunOutcome.Unknown -> SkillResult.unreviewed
            LearnGate.done(end, outcome) -> SkillResult.verified
            else -> SkillResult.unverified
        }
        is TaskState.Failed -> when {
            VERIFIER_REJECTED_MARK in end.reason -> SkillResult.verifier_rejected
            end.kind == FailKind.GaveUp || end.kind == FailKind.StepLimit -> SkillResult.failed
            // 模型出错、运行环境出问题、操作保护停下:和照着做灵不灵无关
            else -> SkillResult.unreviewed
        }
        else -> null
    }
}
