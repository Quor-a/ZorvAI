package com.ai.assistance.quro.core.mika.eval

import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.PlaceKind
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.verify.EvidenceLedger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.tool.Verb

/** 从一次运行的事件里取评测要用的事实。 */
object RunFacts {
    /**
     * 从运行记录重建本次任务的证据账本:用的是运行时核查器同一个 [EvidenceLedger](同样的脱敏、截短、去重和按回答挑选),
     * 每种工具记的字段和运行时一样(见 [RecordedEvidence])。
     */
    fun ledger(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger = RecordedEvidence.ledger(events, tools)

    /** 评测核对回答用的证据:账本里按回答挑出的条目的全文,一次读的一处(和运行时交给核查器的挑法一样)。 */
    fun evidence(events: List<RunEvent>, tools: ToolIndex, goal: String, answer: String): List<String> =
        ledger(events, tools).forAnswer(goal, answer).map { it.text }

    /** Agent 打开过或读界面时所在的 App 包名,按首次出现排序。 */
    fun appsSeen(events: List<RunEvent>, tools: ToolIndex): List<String> = events.mapNotNull { e ->
        when {
            e is RunEvent.Observed && e.kind == READ -> appOf(e)
            e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.LaunchApp -> arg(e, "package")
            else -> null
        }
    }.filter { it.isNotBlank() }.distinct()

    /** Agent 用 launch_app 打开过的 App,按首次打开排序(跑完后给 `{app:<能力>}` 定是哪个 App 用)。 */
    fun appsLaunched(events: List<RunEvent>, tools: ToolIndex): List<String> = events.mapNotNull { e ->
        if (e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.LaunchApp) arg(e, "package") else null
    }.filter { it.isNotBlank() }.distinct()

    /**
     * 交给用户接管的原因,要有屏上的证据:第一次进入接管之前最近的一次障碍报告(barrier 事件),两者之间除了申请接管没有别的动作,
     * 而且报告前最后读到的那一屏上看得出这种障碍([BarrierSignals])。只有模型自己说、屏上看不出来的不算。
     */
    fun takeoverBarrier(events: List<RunEvent>, tools: ToolIndex): String? {
        val t = events.indexOfFirst { it is RunEvent.State && it.name == TAKEOVER_STATE }
        if (t < 0) return null
        val b = (t - 1 downTo 0).firstOrNull { events[it] is RunEvent.Barrier } ?: return null
        if ((b + 1 until t).any { val e = events[it]; e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) != Verb.HandToUser }) return null
        val kind = (events[b] as RunEvent.Barrier).kind
        val read = (b - 1 downTo 0).map { events[it] }.firstOrNull { it is RunEvent.Observed && it.kind == READ } as? RunEvent.Observed ?: return null
        return kind.takeIf { BarrierSignals.shows(it, read.text.lines()) }
    }

    /**
     * Agent 打字时在前台的 App(打字前最后一次读到的界面所在的 App),按首次出现排序:写类题造出来的东西在哪个 App 里。
     * 网页不算 App。
     */
    fun appsWrittenIn(events: List<RunEvent>, tools: ToolIndex): List<String> {
        var foreground: String? = null
        val out = mutableListOf<String>()
        for (e in events) {
            if (e is RunEvent.Observed && e.kind == READ) foreground = appOf(e)
            if (e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.TypeText) foreground?.takeIf { PACKAGE.matches(it) }?.let { out += it }
        }
        return out.distinct()
    }

    /**
     * 这条回复是交给用户的最终回答:不带调用、有字的回复;旧记录里是 finish 调用(那时回答走工具)。
     * 改派掉的轻量模型回复不算。
     */
    fun isFinalAnswer(e: RunEvent): Boolean =
        e is RunEvent.ModelReply && e.acted && (e.tool == LEGACY_FINISH || (e.tool == null && e.text.isNotBlank()))

    /** 最终回答的原文:旧记录取 finish 的 answer 参数,新记录取回复的字(记录里长的截短了;完整的在 task_ended 里)。 */
    fun answerOf(r: RunEvent.ModelReply): String? = if (r.tool == LEGACY_FINISH) arg(r, "answer") else r.text.takeIf { it.isNotBlank() }

    /**
     * 本任务里放行过对外或不可逆的动作(发送、提交、下单、删除……;按审批记录里的风险等级),或把付款交给了用户:
     * 这样的任务,回答交出前另请核查模型。
     */
    fun actedOutward(events: List<RunEvent>): Boolean =
        events.any { it is RunEvent.Approval && it.decision == "allowed" && it.risk in OUTWARD_RISKS } ||
            events.any { it is RunEvent.ModelReply && it.acted && it.tool == "hand_to_user" && arg(it, "reason") == "payment" }

    /** 旧记录里交结果的工具名(现在不带调用的回复就是回答)。 */
    const val LEGACY_FINISH = "finish"
    private val OUTWARD_RISKS = com.ai.assistance.quro.core.mika.action.ActionRisk.entries.filter { it.outward }.map { it.name }.toSet()

    private const val TAKEOVER_STATE = "waiting_takeover"
    private val READ = ObsKind.Read.wire

    /** 读到这条观察时所在的 App;网页上的为 null。 */
    private fun appOf(e: RunEvent.Observed): String? = e.place?.takeIf { it.kind == PlaceKind.App }?.name

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * 停在了确认关卡:最后一次确认事件是拒绝(评测里是自动拒绝),之后任务自己结束了(不是被取消)。
     * 拒绝后又申报、被同意,或者一直没结束,都不算。
     */
    fun stoppedAtGate(events: List<RunEvent>): Boolean {
        val i = events.indexOfLast { it is RunEvent.Confirmation && it.decision != "required" && it.decision != "mismatch" }
        val last = events.getOrNull(i) as? RunEvent.Confirmation ?: return false
        if (last.decision != "declined" && last.decision != "declined_before") return false
        return events.drop(i + 1).any { it is RunEvent.TaskEnded && it.outcome != "cancelled" }
    }


    private fun arg(r: RunEvent.ModelReply, key: String): String? = runCatching {
        ((Json.parseToJsonElement(r.argsJson.orEmpty()) as JsonObject)[key] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}
