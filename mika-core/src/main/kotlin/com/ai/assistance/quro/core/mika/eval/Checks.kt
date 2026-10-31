package com.ai.assistance.quro.core.mika.eval

import com.ai.assistance.quro.core.mika.verify.Grounding

/** 一次任务跑完后评测器收集到的事实,打分只看它。 */
data class Observed(
    /** succeeded|unverified|failed|cancelled|confirmation|takeover|timeout|skipped */
    val outcome: String,
    val answer: String,
    /** 证据账本里按回答挑出的材料,一次读的一处(和运行时交给核查器的同一套,见 [RunFacts.evidence]),已去掉 Agent 自己输入的回显。 */
    val evidence: List<String>,
    /** 确认卡片字段:action / target / content / amount。 */
    val confirmation: Map<String, String>?,
    /** 键为 [Checks.screenKey]。 */
    val screenHas: Map<String, Boolean>,
    /** 任务结束后执行的探针:名字 → 输出。 */
    val probes: Map<String, String>,
    val steps: Int,
    val wallMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val failReason: String?,
    /** Agent 碰过、但不在题目 apps 里的 App;只记录,不算失败。 */
    val leftScope: List<String> = emptyList(),
    /** 题目原文:回答里复述题目里的词(App 名、算式)不算要从屏上读到的值。 */
    val goal: String = "",
    /** 完成前核查的结论(pass / fail / cannot_verify);没核查过为 null。 */
    val verdict: String? = null,
    /** 主循环的模型调用次数(ModelReply 事件数;回放、批量执行的步不算)。 */
    val modelCalls: Int = 0,
    /** 模型等待总时长(各 ModelReply.latencyMs 之和)。 */
    val modelMs: Long = 0,
    /** 工具执行总时长(各 ToolResult.durationMs 之和)。 */
    val toolMs: Long = 0,
    /** 其中等界面稳定的部分(各 ToolResult.waitMs 之和)。 */
    val waitMs: Long = 0,
    /** 回放走完的路线数 / 中途交还给模型的路线数。 */
    val replayHits: Int = 0,
    val replayHandBacks: Int = 0,
    /** 任务开头带上的记忆条数(memory_used 事件)。 */
    val memoryUsed: Int = 0,
    /** 这次用到的个人技能名(skill_used 事件,去重)。 */
    val skillsUsed: List<String> = emptyList(),
    /** 键为 [Checks.webKey]。 */
    val webHas: Map<String, Boolean> = emptyMap(),
    /** 紧挨在接管之前报告、而且那一屏上看得出来的障碍(见 [RunFacts.takeoverBarrier]);没有为 null。 */
    val takeoverBarrier: String? = null,
    /** Agent 向用户追问(ask_user)的次数。 */
    val askedUser: Int = 0,
    /** 任务那天(运行时钟的日期):回答把「明天」写成具体日期时按它核对(见 [Grounding.check]);不知道为 null。 */
    val today: java.time.LocalDate? = null,
    /** 交出回答后判的结局(运行记录里的 outcome 事件:done / partial / not_possible / unknown);没判过为 null。打分按它,见 [Scoring.recorded]。 */
    val judged: String? = null,
    /** 这次运行的小结(用时去向、第一次反馈、来回次数等,见 [com.ai.assistance.quro.core.mika.record.RunMetrics]);没有运行记录为 null。 */
    val metrics: com.ai.assistance.quro.core.mika.record.RunMetrics? = null,
)

object Checks {
    fun screenKey(c: Check.ScreenHas): String = "${c.packageName}|${c.deepLink.orEmpty()}|${c.text}"

    fun webKey(c: Check.WebHas): String = "${c.url}|${c.text}"

    fun passes(c: Check, o: Observed): Boolean = when (c) {
        is Check.AnswerContains -> c.all.all { it in o.answer }
        is Check.AnswerRegex -> Regex(c.pattern).containsMatchIn(o.answer)
        is Check.AnswerGrounded -> grounded(o.answer, o.evidence, c.minItems, o.goal, o.today)
        is Check.ScreenHas -> o.screenHas[screenKey(c)] == true
        is Check.MemoryUsed -> o.memoryUsed >= c.min
        is Check.AskedUser -> (o.askedUser > 0) == c.expected
        is Check.WebHas -> o.webHas[webKey(c)] == true
        is Check.ProbeEquals -> o.probes[c.probe]?.trim()?.let { actual ->
            if (c.contains) c.expected.trim() in actual else actual == c.expected.trim()
        } ?: false
        is Check.ConfirmationShows -> o.confirmation?.let { card ->
            listOf("action" to c.action, "target" to c.target, "content" to c.content, "amount" to c.amount)
                .all { (k, want) -> want == null || want in card[k].orEmpty() }
        } ?: false
    }

    /**
     * 回答有据可查:和核查兜底同一条字面规则([Grounding.check]:引文、带单位的数、编号、日期都在材料里找得到,依据里的引文定位得到),
     * 而且有据可查的条目([Grounding.Result.items])不少于 [minItems]。题目 [goal] 里的数和文字视为已知。
     * 值是不是属于回答说的那个东西(哪一行、哪一页)代码不判,由核查模型判(评测里看结局是不是 succeeded)。
     */
    internal fun grounded(answer: String, screens: List<String>, minItems: Int, goal: String = "", today: java.time.LocalDate? = null): Boolean =
        Grounding.check(goal, answer, screens, today).let { it.found != false && it.items >= minItems }
}
