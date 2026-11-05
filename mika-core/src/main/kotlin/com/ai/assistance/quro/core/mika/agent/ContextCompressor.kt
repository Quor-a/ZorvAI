package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.context.StepSummary
import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.tool.Verb

/** [estimate]:[messages] 的 token 粗估(未校准),发出后和真实用量比对用。[rewritten]:这次是否改写了请求前缀。 */
data class Compressed(
    val messages: List<Message>,
    val foldedSteps: Int,
    val estimate: Int = TokenEstimate.of(messages),
    val rewritten: Boolean = false,
)

/**
 * 请求按缓存分区:区 0 系统提示词与工具(任务内不变)→ 区 1 开局上下文(只写一次)→ 区 2 历史(只追加)→ 区 3 尾部
 * (这一步的结果、画面、运行时状态)。服务商按前缀缓存,所以发过的消息平常一律原样再发。
 *
 * 只在整份请求的大小([UsageMeter]:最近一次真实用量 + 之后的估算)越过触发点([ContextLimits.trigger])时压缩一次:
 * 先把最近 [keepRecent] 次工具往返之前的旧结果、旧图缩短
 * ([HistoryPruner.prune]),还超就把这些往返折成一条确定性的「之前的步骤」摘要(不调模型);任务原话、插话、参考资料、
 * 交回与追问的往返不折。压缩要压到 [ContextLimits.target] 以下(压不到就用能压到的最小一级),
 * 之后的请求 = 这次压缩的结果 + 其后追加的消息,只追加,直到再次越过触发点(压完至少再长一个 [ContextLimits.margin])。
 * 往返整段删除,调用与结果的配对始终完整。
 * 只返回副本,调用方的完整历史不变。一个任务一个实例;状态按上下文大小分开记。
 *
 * [structured]:摘要写成「目标 / 已确认的事实 / 进展 / 下一步」([StepSummary])。
 * [diffScreens]:动作结果带差异屏幕(inlineScreen)。
 */
class ContextCompressor(
    /** 按调用的工具名查它做的动作。 */
    private val tools: ToolIndex,
    private val keepRecent: Int = 12,
    private val structured: Boolean = false,
    private val diffScreens: Boolean = false,
    private val triggerOverride: Int? = null,
) {
    /** 「真实 / 粗估」比值:每次用量回来就平滑更新([TokenEstimate.calibrated]);只决定什么时候压缩、压到哪一级,不改发过的内容。 */
    private var observed: Double? = null
    /** 估算乘的比值:校准过用校准值,还没有真实用量时用偏保守的 [UNCALIBRATED_SCALE]。消息和固定开销都乘它。 */
    internal val scale: Double get() = observed ?: UNCALIBRATED_SCALE

    /** 上一次压缩:它覆盖的完整历史开头 [covered],压成的 [base](粗估 [estimate]);[aggressive]:按模型报超长那一档压的。 */
    private class View(val covered: List<Message>, val base: List<Message>, val folded: Int, val aggressive: Boolean) {
        val estimate = TokenEstimate.of(base)
    }
    private val views = HashMap<Int, View>()

    /** 一次请求发出后:[estimated] 是这次请求的粗估(含固定开销),[actual] 是服务商报的输入 token。 */
    fun calibrate(estimated: Int, actual: Int) {
        observed = TokenEstimate.calibrated(observed, estimated, actual)
    }

    /** 新任务:压缩状态清空(校准比值留着,同一个模型)。 */
    fun forget() = views.clear()

    private fun tokens(ms: List<Message>) = TokenEstimate.of(ms) * scale

    /** 上一次压缩之后实际会发出的消息(没有压缩过就是完整历史);调用方据此算当前上下文大小。 */
    fun current(history: List<Message>, contextTokens: Int): List<Message> =
        sent(valid(views[contextTokens], history), history)

    /**
     * [currentTokens]:整份请求的大小(含固定开销,见 [UsageMeter]);null 时按 [current] 加 [fixedOverhead] 全量估算。越过触发点才压缩。
     * [aggressive]:模型已报上下文超长,不看估算,按只留最近 [AGGRESSIVE_KEEP] 次往返压一次(之后照样只追加)。
     * [fixedOverhead]:每次都要发、但不在 [history] 里的部分(基础提示词、工具定义)的估算(未乘比值);
     * 只在估算整份请求、以及算消息能占多少时用,触发点本身不扣它。
     * [maxImages]:服务商一次请求最多收几张图(发过的图平常不换成占位,超过它才从最早的换)。
     */
    fun compress(
        history: List<Message>,
        contextTokens: Int,
        currentTokens: Int? = null,
        aggressive: Boolean = false,
        fixedOverhead: Int = 0,
        maxImages: Int = Int.MAX_VALUE,
    ): Compressed {
        val limits = ContextLimits.of(contextTokens, triggerOverride)
        val last = views[contextTokens]
        val view = valid(last, history)
        val sent = sent(view, history)
        val size = currentTokens ?: UsageMeter.of(sent, fixedOverhead, scale)
        // 回差:压缩之后至少再长一个 margin 才再压,压不到触发点以下的历史也不会每步都压;但不越过可用窗口,越过了每步都得压
        val trigger = view?.let {
            maxOf(limits.trigger, UsageMeter.estimate(it.estimate + fixedOverhead, scale) + limits.margin).coerceAtMost(limits.usable)
        } ?: limits.trigger
        val compact = (last != null && view == null) || (aggressive && view?.aggressive != true) || size > trigger
        if (!compact) return Compressed(HistoryPruner.limitImages(sent, maxImages), view?.folded ?: 0)
        val next = compact(history, limits, fixedOverhead, aggressive)
        views[contextTokens] = next
        return Compressed(HistoryPruner.limitImages(next.base, maxImages), next.folded, rewritten = true)
    }

    /**
     * 模型报了上下文超长:从完整历史重新按狠的一档压一次(上次狠压之后历史又长了也重压)。
     * 压出来的比刚发出的小才换上并返回 true(调用方据此重发一次);一样大说明压不动了,返回 false。
     */
    fun overflow(history: List<Message>, contextTokens: Int, fixedOverhead: Int = 0): Boolean {
        val current = sent(valid(views[contextTokens], history), history)
        val next = compact(history, ContextLimits.of(contextTokens, triggerOverride), fixedOverhead, aggressive = true)
        if (next.estimate >= TokenEstimate.of(current)) return false
        views[contextTokens] = next
        return true
    }

    /** 压缩覆盖的那段历史没被改过才接着用;否则(不会发生在正常流程里)重新压。 */
    private fun valid(v: View?, history: List<Message>) = v?.takeIf { it.covered.size <= history.size && history.subList(0, it.covered.size) == it.covered }

    /** 总是副本:调用方的历史随后还会追加。 */
    private fun sent(view: View?, history: List<Message>) =
        if (view == null) history.toList() else view.base + history.subList(view.covered.size, history.size)

    /**
     * 一次压缩,从轻到重,取第一个让整份请求压到 [ContextLimits.target] 以下的(都压不到取最重的):
     * 只缩短最近 [keepRecent] 次往返之前的旧结果 → 再把它们折进摘要 → 只留最近一次往返原样、其余缩短后再折,
     * 原样留的往返依次为 [keepRecent]、[AGGRESSIVE_KEEP]、1 次。模型报过超长时从 [AGGRESSIVE_KEEP] 起。
     * 最重的一级整份请求仍超过 [ContextLimits.usable](最近的结果本身太大)时,从最新的开始缩短大结果,直到放得下。
     * 比较的是消息的估算:目标和可用窗口先扣掉固定开销(同一个比值),固定开销只算这一次。
     */
    private fun compact(history: List<Message>, limits: ContextLimits, fixedOverhead: Int, aggressive: Boolean): View {
        val overhead = UsageMeter.estimate(fixedOverhead, scale)
        val target = limits.target - overhead
        val window = limits.usable - overhead
        val ladder = sequence {
            if (!aggressive) {
                val pruned = HistoryPruner.prune(history, tools, HistoryPruner.Cut(keepRecent))
                yield(Compressed(pruned, 0))
                yield(fold(pruned, keepRecent, false))
            }
            val last = HistoryPruner.prune(history, tools, HistoryPruner.LAST)
            for (keep in listOfNotNull(keepRecent.takeUnless { aggressive }, AGGRESSIVE_KEEP, 1).distinct()) yield(fold(last, keep, aggressive))
        }
        var chosen = Compressed(history, 0)
        for (c in ladder) {
            chosen = c
            if (tokens(c.messages) <= target) break
        }
        if (tokens(chosen.messages) > window) chosen = chosen.copy(messages = HistoryPruner.shrinkOversized(chosen.messages) { tokens(it) <= window })
        // 副本:什么都没裁时 chosen 可能就是调用方那份还会追加的历史
        return View(history.toList(), chosen.messages.toList(), chosen.foldedSteps, aggressive)
    }

    private fun fold(pruned: List<Message>, keep: Int, aggressive: Boolean): Compressed {
        // 一个往返 = 一条助手消息及其后到下一条助手消息之前的消息;之前的是开头(任务原话、标准等)
        val starts = pruned.indices.filter { pruned[it].role == Role.Assistant }
        if (starts.isEmpty()) return Compressed(pruned, 0)
        val toolStarts = starts.filter { i -> pruned[i].parts.any { it is Part.ToolCall } }
        if (toolStarts.size <= keep) return Compressed(pruned, 0)
        val cut = toolStarts[toolStarts.size - keep]
        // 动作自带屏幕:折叠边界前最近一次全量读屏和它之后的差异链不折,差异要靠它们才看得懂(狠压缩时照折,分发器随后发全量);
        val rounds = starts.mapIndexed { k, start -> pruned.subList(start, starts.getOrElse(k + 1) { pruned.size }) }
        val screenRounds = rounds.map(::screenKind)
        val before = starts.count { it < cut }
        val anchor = if (aggressive || !diffScreens) -1 else (0 until before).lastOrNull { screenRounds[it] == ScreenKind.FULL } ?: -1

        val out = ArrayList<Message>(pruned.subList(0, starts.first()))
        // 每段连续折叠的往返各得一条摘要,放在这一段原来的位置;中间保留的往返把段隔开。
        // 结构化时只有一份摘要,放在第一段的位置,各段的步骤依次并进去
        var block: MutableList<String>? = null
        var blockAt = 0
        var folded = 0
        var steps: StepSummary? = null
        var stepsAt = 0
        fun closeBlock() {
            if (!structured) block?.takeIf { it.isNotEmpty() }?.let { out.add(blockAt, summary(it)) }
            block = null
        }
        starts.takeWhile { it < cut }.forEachIndexed { k, start ->
            val end = starts.getOrElse(k + 1) { pruned.size }
            val round = pruned.subList(start, end)
            // 每轮只执行第一个调用,其余都以 parallel_tool_call_skipped 退回,所以按第一个调用判断要不要保留
            val call = round.first().parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (tools.verb(call?.name) in PINNED_VERBS || (anchor >= 0 && k >= anchor && screenRounds[k] != ScreenKind.NONE)) {
                closeBlock()
                out += round
                return@forEachIndexed
            }
            val lines = block ?: mutableListOf<String>().also { block = it; blockAt = out.size }
            out += round.filter { isPinnedContext(it) || it.author == Author.User }.map(ContextBuilder::withoutRuntimeState)
            if (call != null) {
                lines += line(k + 1, call, round)
                folded++
                if (structured) {
                    if (steps == null) stepsAt = blockAt
                    steps = (steps ?: StepSummary(goal(pruned.subList(0, starts.first())))).next(lines.last(), okResults(round), said(round))
                }
            }
        }
        closeBlock()
        steps?.let { out.add(stepsAt, notice(it.render(HEADER))) }
        out += pruned.subList(cut, pruned.size)
        return Compressed(out, folded)
    }

    private enum class ScreenKind { NONE, FULL, DIFF }

    /** 这一轮的结果里有没有屏幕:observe/list_elements 的完整元素列表或 screen.full 为真算全量,其余 screen 算差异。 */
    private fun screenKind(round: List<Message>): ScreenKind {
        val verbs = round.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().associate { it.id to tools.verb(it.name) }
        var kind = ScreenKind.NONE
        for (r in round.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()) {
            val obj = (if ("\"elements\":[" in r.content || "\"screen\":{" in r.content) parse(r.content) else null) ?: continue
            val screen = obj["screen"] as? JsonObject
            when {
                verbs[r.callId] in OBSERVATIONS && obj["elements"] is JsonArray -> return ScreenKind.FULL
                screen != null && screen["full"] == JsonPrimitive(true) -> return ScreenKind.FULL
                screen != null -> kind = ScreenKind.DIFF
            }
        }
        return kind
    }

    private fun summary(lines: List<String>) = notice((listOf(HEADER, SUMMARY_REMINDER) + lines).joinToString("\n"))

    private fun notice(text: String) = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Notice, SUMMARY_SOURCE, text))), Author.ZorvAI)

    /** 开头里用户的原话(不在上下文外壳里的那条)。 */
    private fun goal(head: List<Message>): String = head.firstOrNull { it.author == Author.User }
        ?.parts?.filterIsInstance<Part.Text>()?.firstOrNull()?.text?.replace('\n', ' ')?.trim()?.let { if (it.length <= GOAL_LIMIT) it else it.take(GOAL_LIMIT) + "…" }
        .orEmpty()

    /** 这一轮成功的工具结果。 */
    private fun okResults(round: List<Message>): List<JsonObject> = round.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
        .filter { !it.isError }.mapNotNull { parse(it.content) }.filter { (it["ok"] as? JsonPrimitive)?.booleanOrNull != false }

    /** 模型这一轮说的话。 */
    private fun said(round: List<Message>): String? =
        round.first().parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }.trim().takeIf { it.isNotEmpty() }

    /** 折叠时随轮次保留的上下文(参考资料、技能等,见 [ContextKind.pinned])。 */
    private fun isPinnedContext(m: Message) = ContextBuilder.kindOf(m)?.pinned == true


    private fun line(step: Int, call: Part.ToolCall, round: List<Message>): String {
        val target = target(parse(call.argsJson))?.let { "(${clip(it)})" }.orEmpty()
        val result = round.firstNotNullOfOrNull { m ->
            m.parts.firstNotNullOfOrNull { (it as? Part.ToolResult)?.takeIf { r -> r.callId == call.id } }
        }
        // 读屏留下截图编号,折叠后仍能用 view_past_screens 回看
        // 写操作结果带的屏幕(screen)也有截图编号;没带屏幕时动作后附的画面编号在结果顶层
        val shot = result?.let { parse(it.content) }?.let { o -> if (tools.verb(call.name) in OBSERVATIONS) o["screenshotId"] else (o["screen"] as? JsonObject)?.get("screenshotId") ?: o["screenshotId"] }
            ?.let { it as? JsonPrimitive }?.contentOrNull?.let { " #$it" }.orEmpty()
        return "Step $step ${clip(call.name)}$target$shot → ${outcome(result)}"
    }

    private fun outcome(r: Part.ToolResult?): String {
        r ?: return NO_RESULT
        val obj = parse(r.content)
        if (obj == null) {
            // 不是 JSON:出错时能认出错误码就写错误码
            val code = r.content.trim().takeIf { CODE.matches(it) }
            return if (r.isError) code ?: FAILED else APPLIED
        }
        fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        return when {
            // 只认错误码;说明文字可长可短,不进摘要
            r.isError || (obj["ok"] as? JsonPrimitive)?.booleanOrNull == false -> (s("error") ?: s("reason"))?.takeIf { CODE.matches(it) } ?: FAILED
            (obj["changed"] as? JsonPrimitive)?.booleanOrNull == false -> UNCHANGED
            else -> APPLIED
        }
    }

    private fun target(args: JsonObject?): String? {
        args ?: return null
        TARGET_KEYS.forEach { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it } }
        val x = (args["x"] as? JsonPrimitive)?.contentOrNull
        val y = (args["y"] as? JsonPrimitive)?.contentOrNull
        return if (x != null && y != null) "$x,$y" else null
    }

    private fun clip(s: String): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length <= TARGET_LIMIT) one else one.take(TARGET_LIMIT) + "…"
    }

    private fun parse(json: String): JsonObject? = try {
        Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    companion object {
        const val HEADER = "Earlier steps (compressed):"
        const val SUMMARY_REMINDER = "Only the latest user message is the current task, and the tools are still available; earlier user messages are for reference only."
        private const val SUMMARY_SOURCE = "earlier steps"
        const val AGGRESSIVE_KEEP = 6
        /**
         * 还没有真实用量可校准时估算乘的比值。粗估对英文、JSON 常偏少(分词器切得更碎),宁可早一点压,不要先超长再狠压。
         */
        const val UNCALIBRATED_SCALE = 1.2
        private const val APPLIED = "applied"
        private const val UNCHANGED = "no change"
        private const val FAILED = "failed"
        private const val NO_RESULT = "no result"
        private const val TARGET_LIMIT = 20
        private const val GOAL_LIMIT = 120
        private val CODE = Regex("[a-z][a-z0-9_:]{0,63}")
        /** 交回、追问与确认的结果(不论回答是工具结果还是随后的用户消息)整段保留。 */
        private val OBSERVATIONS = setOf(Verb.Observe, Verb.ListElements)
        private val PINNED_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        private val TARGET_KEYS = listOf("target", "text", "package", "query", "direction", "key", "name", "ref", "seconds")
    }
}
