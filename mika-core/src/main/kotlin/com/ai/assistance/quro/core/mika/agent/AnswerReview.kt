package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.verify.AnswerSource
import com.ai.assistance.quro.core.mika.verify.AnswerSources
import com.ai.assistance.quro.core.mika.verify.Criteria
import com.ai.assistance.quro.core.mika.verify.Grounding
import com.ai.assistance.quro.core.mika.verify.Verdict
import com.ai.assistance.quro.core.mika.verify.Verification
import com.ai.assistance.quro.core.mika.verify.Verifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 回答交出前核对要看的任务情况。 */
internal data class ReviewFacts(
    /** 本任务调过工具(没调过的是聊天、常识,没有可核对的材料)。 */
    val acted: Boolean,
    /** 做过对外或不可逆的事(放行过这样的动作,或把付款交给了用户)。 */
    val outward: Boolean,
    /** 最近一次改了状态的动作之后没有重新读过:回答说的结果没人看过。 */
    val unreadChange: Boolean,
)

/**
 * 最终回答交出前的核对。按风险来,不来回退回:
 * - 本任务一个工具也没调过(聊天、常识、对话里就能答的):不核,算没核对过。
 * - 字面核对([Grounding.check]):回答里的数、编号、日期、引文和写明的算式(a 运算符 b = c)要在本任务读到的材料里有原文。
 *   回答里有这类事实、全都对上了,算核实过;回答里没有这类事实,字面上没什么可核的,算没核对过。
 * - 做过对外或不可逆的事、最近一次改状态的动作之后没有重新读过、或字面核对有对不上的,另请核查模型([Verifier]);它判 pass 才算核实过。
 * 结论里有没对上的([Review.gaps]、[Review.objections])时,由循环退回给模型一次;再不行就把这几处标成未确认交出(见 AgentLoop)。
 * 来源([Review.sources])也在这里由代码找([AnswerSources])。
 */
internal class AnswerReview(
    private val tools: ToolDispatcher,
    private val verifier: Verifier?,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
) {
    /** 产品路径只整理出处，不再为收尾答复发起另一轮核查。 */
    suspend fun sourcesOnly(goal: String, answer: String, media: Map<Int, List<Int>>): List<AnswerSource> =
        AnswerSources.of(tools.evidence(fresh = false).ledger, goal, answer, media)

    /**
     * [gaps] 是回答里在读到的材料里找不到的那几处,[objections] 是核查模型判没做成的理由;两者合起来是 [unconfirmed]。
     * [reviewed] 为 false 表示什么也没核对(没有可核对的、也没请核查模型);[verified] 只在核对过而且都对上时为 true。
     * [note] 是核查器没能核实的原因;[verdict] 是核查模型的结论(没请它为 null)。
     */
    class Review(
        val gaps: List<String> = emptyList(), val objections: List<String> = emptyList(), val sources: List<AnswerSource> = emptyList(),
        val reviewed: Boolean = true, val verified: Boolean = true, val note: String? = null, val verdict: Verdict? = null,
    ) {
        val unconfirmed: List<String> get() = (gaps + objections).distinct()
    }

    /** 核第 [step] 步给出的回答 [answer]。[media]:每一步存下的画面编号;[verifying]:要请核查模型时先调它(界面上显示「正在核实」)。 */
    suspend fun review(
        goal: String, answer: String, step: Int, facts: ReviewFacts, earlierGoals: List<String>, media: Map<Int, List<Int>>, verifying: () -> Unit,
    ): Review {
        if (!facts.acted) return Review(reviewed = false, verified = false)
        val v = verifier
        // 字面核对只看账本(最近一次读取已在里面),不重新读;要请核查模型时才重新读此刻的界面
        val read = tools.evidence(fresh = false)
        val sources = AnswerSources.of(read.ledger, goal, answer, media)
        val g = Grounding.check(goal, answer, read.sources(read.ledger.forAnswer(goal, answer)), read.today)
        val gaps = g.unmatched.map { it.text }.distinct()
        val risky = facts.outward || facts.unreadChange
        emit(RunEvent.AnswerCheck(now(), step, g.claims.size, gaps.map(::detail), risky))
        val literalOk = gaps.isEmpty() && g.claims.isNotEmpty()
        if (!risky && gaps.isEmpty()) return Review(sources = sources, reviewed = literalOk, verified = literalOk)
        if (v == null) return Review(gaps = gaps, sources = sources, reviewed = literalOk || gaps.isNotEmpty(), verified = literalOk)
        verifying()
        val ev = tools.evidence(withImage = v.wantsImage)
        val r = v.verify(goal, Criteria.of(goal, facts.outward), answer, ev, earlierGoals)
        emit(RunEvent.Verifier(now(), r.verdict.wire, RunEvent.clip(Redactor.text(detailOf(r).toString())), step))
        return when (r.verdict) {
            Verdict.Pass -> Review(sources = sources, verdict = r.verdict)
            // 标准只是用户原话,没满足的「标准」就是原话本身:说清楚的是核查器给的理由
            Verdict.Fail -> Review(gaps = gaps, objections = listOf(r.reason).filter(String::isNotBlank), sources = sources, verified = false, verdict = r.verdict)
            Verdict.CannotVerify -> (gaps + r.notFound).distinct().let { missing ->
                Review(gaps = missing, sources = sources, verified = false, note = r.reason, verdict = r.verdict)
            }
        }
    }

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    private fun detailOf(v: Verification) = buildJsonObject {
        put("reason", v.reason)
        v.groundedBy?.let { put("groundedBy", it) }
        if (v.notFound.isNotEmpty()) put("notFound", JsonArray(v.notFound.map(::JsonPrimitive)))
        put("checks", JsonArray(v.checks.map { c ->
            buildJsonObject { put("criterion", c.criterion); put("status", c.status); c.quote?.let { put("quote", it) } }
        }))
    }
}
