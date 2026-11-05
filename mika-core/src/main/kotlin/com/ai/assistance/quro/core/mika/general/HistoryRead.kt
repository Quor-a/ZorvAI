package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.agent.Turn
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Trust
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.record.RunRecords
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Concurrency
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.okObj
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val HISTORY_READ = "history_read"

/**
 * 查这个对话之前几轮的记录:不带 turn 列出之前的轮次(新的在前:第几轮、用户说了什么、结局、时刻),
 * 带 turn 读那一轮记下的东西(完整结局、ZorvAI 的进度说明、读到的原文),按条分页。
 * 只认本次运行开局带来的那几轮([com.ai.assistance.quro.core.mika.run.RunSpec.priorTurns],同一个对话),只读;
 * 原文取自运行记录(落盘时已脱敏、截短),不带截图。读到的原文作为观察交给核查,回答里可以引用。
 * 只在给了运行记录、而之前有记过运行的轮次时提供。
 */
internal object HistoryRead : AgentTool {
    /** 列表最多列几轮(新的在前)。 */
    const val LIST_MAX = 20
    /** 一页里各条原文加起来大约多少字(至少给一条)。 */
    const val PAGE_CHARS = 8_000
    /** 一条原文最多留多少字。 */
    const val ITEM_CHARS = 2_000
    /** 读一轮时结局最多留多少字。 */
    const val OUTCOME_CHARS = 3_000
    /** 列表里用户原话、结局各留多少字。 */
    private const val GIST_CHARS = 200

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

    override val verb = Verb.HistoryRead
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(HISTORY_READ,
        Prompts.tool(HISTORY_READ, "page" to PAGE_CHARS),
        mapOf(
            "turn" to int("Which earlier turn to read: 1 = the turn just before this one, 2 = the one before that, and so on. Leave out to list the earlier turns.", 1),
            "offset" to int("Item to start from, from nextOffset of the previous call (default 0).", 0),
        ), emptyList())

    override fun available(ctx: ToolContext): Boolean =
        ctx.config.runRecords != null && ctx.config.runSpec?.priorTurns.orEmpty().any { it.runId != null }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val turns = ctx.config.runSpec!!.priorTurns.asReversed()
        val records = ctx.config.runRecords!!
        val n = a.int("turn", 1, Int.MAX_VALUE)
        val offset = a.int("offset", 0, Int.MAX_VALUE) ?: 0
        if (n == null) return list(turns, records)
        val turn = turns.getOrNull(n - 1)
            ?: return fail("turn_not_found", Feedback.format("history.turn_not_found", "count" to turns.size))
        return read(n, turn, records, offset)
    }

    private suspend fun list(turns: List<Turn>, records: RunRecords): ToolExecution {
        val shown = turns.take(LIST_MAX)
        val zone = ZoneId.systemDefault()
        val summaries = shown.map { t -> t.runId?.let { id -> safe(null) { records.summary(id) } } }
        return ToolExecution(okObj {
            put("turns", JsonArray(shown.mapIndexed { i, t ->
                val summary = summaries[i]
                buildJsonObject {
                    put("turn", i + 1)
                    put("said", clip(t.userText, GIST_CHARS))
                    put("status", t.status)
                    put("outcome", clip(t.outcome, GIST_CHARS))
                    summary?.let { put("time", time(it.startedAtMs, zone)) }
                    put("recorded", summary != null)
                }
            }))
            if (turns.size > shown.size) put("older", turns.size - shown.size)
        })
    }

    private suspend fun read(n: Int, t: Turn, records: RunRecords, offset: Int): ToolExecution {
        val id = t.runId
        val summary = id?.let { safe(null) { records.summary(it) } }
        val items = if (summary != null) items(safe(emptyList()) { records.events(id) }) else emptyList()
        val page = mutableListOf<Item>()
        var chars = 0
        for (item in items.drop(offset)) {
            if (page.isNotEmpty() && chars + item.text.length > PAGE_CHARS) break
            page += item
            chars += item.text.length
        }
        val next = offset + page.size
        val content = okObj {
            put("turn", n)
            put("said", t.userText)
            put("status", t.status)
            put("outcome", clip(t.outcome, OUTCOME_CHARS))
            if (t.steers.isNotEmpty()) put("userAddedDuringTask", JsonArray(t.steers.map { JsonPrimitive(clip(it, GIST_CHARS)) }))
            summary?.let { put("time", time(it.startedAtMs, ZoneId.systemDefault())) }
            put("recorded", summary != null)
            if (summary == null) put("message", Feedback["history.not_recorded"])
            else {
                put("totalItems", items.size)
                put("items", JsonArray(page.mapIndexed { i, item ->
                    buildJsonObject {
                        put("n", offset + i)
                        put("kind", item.kind)
                        item.where?.let { put("where", it) }
                        put("text", if (item.untrusted) Untrusted.wrap(item.text) else item.text)
                    }
                }))
                if (next < items.size) put("nextOffset", next)
            }
        }
        // 读到的原文交给核查:回答里用到之前几轮读到的东西时有据可查(出处另记为 history,不指回当时的画面)
        val observations = page.mapNotNull { item ->
            item.obsKind?.let { Observation(it, item.text, source = SOURCE, title = item.where, trust = if (item.untrusted) Trust.Untrusted else Trust.Trusted) }
        }
        return ToolExecution(content, observations = observations)
    }

    /** 记录里一条可读的东西:ZorvAI 的进度说明(note,带了调用的回复里的字),或读到的原文(观察的种类)。 */
    private class Item(val kind: String, val text: String, val where: String?, val untrusted: Boolean, val obsKind: ObsKind?)

    private fun items(events: List<RunEvent>): List<Item> {
        val seen = mutableSetOf<String>()
        return events.mapNotNull { e ->
            when (e) {
                // 不带调用的回复就是那一轮的回答,已经在 outcome 里
                is RunEvent.ModelReply -> e.text.trim().takeIf { e.acted && e.tool != null && it.isNotEmpty() && seen.add(it) }
                    ?.let { Item(NOTE, clip(it, ITEM_CHARS), null, false, null) }
                is RunEvent.Observed -> e.text.trim().takeIf { it.isNotEmpty() && seen.add(it) }?.let {
                    val o = e.observation()
                    Item(o.kind.wire, clip(it, ITEM_CHARS), o.title ?: o.place?.name, e.untrusted, o.kind)
                }
                else -> null
            }
        }
    }

    private fun time(ms: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    /** 读记录出错当没有:查不到不影响任务。 */
    private suspend fun <T> safe(fallback: T, read: suspend () -> T): T =
        try { read() } catch (e: CancellationException) { throw e } catch (e: Exception) { fallback }

    private const val NOTE = "note"
    private const val SOURCE = "history"
}
