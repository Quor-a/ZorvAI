package com.ai.assistance.quro.core.mika.replay

import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * 把一次办成的运行记成路线。只看 [RunEvent.ActionTarget] 与接管、追问、确认、障碍、结局一类事件;
 * 有任何一项说明这次不该记(要用户出手、付款、密码框、防截屏、用了浏览器工具或沙箱工具),返回 null。
 */
object RouteRecorder {
    /** 用过就说明这次做法离不开用户或别的通道,照着回放不安全。 */
    private val NO_ROUTE_VERBS = setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)
    private val WAITING_STATES = setOf("waiting_input", "waiting_takeover", "no_answer")
    private val PROTECTED = Regex("\"protected\"\\s*:\\s*true|\"state\"\\s*:\\s*\"protected\"")
    private val NOT_SECRET = Regex("\"secret\"\\s*:\\s*false")

    /** 槽值至少这么长:太短的字在原话里容易碰巧出现。 */
    private const val MIN_SLOT = 2

    /**
     * 输入的字只能是本次原话里恰好出现一次的片段(记成槽);不是的(固定词、追问来的、从别处抄来的)整条不记,
     * 路线里永远不存用户没在原话里说过的字。目标元素的名字在原话里出现时也必须绑成槽,回放时按新原话认。
     * 办没办成由调用方按 [com.ai.assistance.quro.core.mika.agent.LearnGate] 判过才来记,这里只看路线本身记不记得下。
     */
    fun record(goal: String, events: List<RunEvent>, nowMs: Long, tools: ToolIndex): Route? {
        if (events.lastOrNull { it is RunEvent.TaskEnded }?.let { (it as RunEvent.TaskEnded).outcome } != "succeeded") return null
        if (events.any { spoils(it, tools) }) return null
        if (events.any { it is RunEvent.ActionTarget && it.incomplete }) return null

        val acts = events.filterIsInstance<RunEvent.ActionTarget>().filter { it.effective && tools[it.tool]?.routable == true }
        if (acts.isEmpty() || acts.size > MAX_ROUTE_STEPS || acts.any { it.protected }) return null
        val results = events.filterIsInstance<RunEvent.ToolResult>()

        // 槽值按第一次用到的先后编号
        val slots = mutableListOf<String>()
        fun bind(v: String): Boolean {
            if (v in slots) return true
            if (v.trim().length < MIN_SLOT || occurrences(goal, v) != 1) return false
            slots += v
            return true
        }
        val args = mutableListOf<JsonObject>()
        val targets = mutableListOf<Selector?>()
        for (a in acts) {
            val o = parse(a.argsJson) ?: return null
            var cleaned = o.filterKeys { it != "ref" && it != "snapshotId" && it != "target" }
            if (tools.verb(a.tool) == Verb.TypeText) {
                val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                // 端口没明确说不是密码框的输入一律不记
                if (text == Redactor.MASK) return null
                val r = a.callId?.let { id -> results.lastOrNull { it.callId == id } } ?: return null
                if (!NOT_SECRET.containsMatchIn(r.resultJson)) return null
                if (!bind(text)) return null
                cleaned = cleaned + ("text" to JsonPrimitive("{${slots.indexOf(text) + 1}}"))
            }
            args += JsonObject(cleaned)
            targets += a.target?.let { t ->
                val n = t.name
                when {
                    n.isEmpty() -> t
                    n in goal -> if (bind(n)) t.copy(name = "{${slots.indexOf(n) + 1}}") else return null
                    // 名字里夹着本次的参数(如「发给张三」):不按名字认
                    slots.any { it in n } -> t.copy(name = "", uniqueAtRecord = false)
                    else -> t
                }
            }
        }
        // 槽在原话里的位置不能互相重叠(短的被长的吞掉就没法单独换)
        val ranges = slots.map { v -> goal.indexOf(v).let { it until it + v.length } }
        if (ranges.indices.any { i -> ranges.indices.any { j -> i < j && ranges[i].first <= ranges[j].last && ranges[j].first <= ranges[i].last } }) return null
        var template = goal
        ranges.withIndex().sortedByDescending { it.value.first }.forEach { (i, r) -> template = template.replaceRange(r, "{${i + 1}}") }

        fun anchors(a: Anchors, target: Selector?) = Anchors(a.packageName, a.texts.map(String::trim).filter { s ->
            RouteAnchors.stable(s) && s != target?.name?.trim() && slots.none { it.trim() in s }
        }.distinct().take(RouteAnchors.MAX))

        val steps = acts.mapIndexed { i, a -> RouteStep(a.tool, args[i].toString(), targets[i], anchors(a.before, a.target), anchors(a.after, null)) }
        val packages = (acts.mapNotNull { a -> if (tools.verb(a.tool) == Verb.LaunchApp) parse(a.argsJson)?.get("package")?.let { (it as? JsonPrimitive)?.contentOrNull } else null } +
            acts.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) }).distinct()
        val route = Route(UUID.randomUUID().toString(), template, slots.size, packages, emptyMap(), steps, lastUsedMs = nowMs)
        return route.takeIf { RouteCodec.encode(it).toByteArray().size <= MAX_ROUTE_BYTES }
    }

    private fun occurrences(s: String, v: String): Int {
        var n = 0; var i = s.indexOf(v)
        while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
        return n
    }

    private fun spoils(e: RunEvent, tools: ToolIndex): Boolean = when (e) {
        is RunEvent.Takeover, is RunEvent.Confirmation -> true
        is RunEvent.State -> e.name in WAITING_STATES
        is RunEvent.Barrier -> e.kind == "payment"
        is RunEvent.ModelReply -> e.tool?.let { barred(it, tools) } == true
        is RunEvent.ToolResult -> barred(e.tool, tools) || PROTECTED.containsMatchIn(e.resultJson)
        is RunEvent.ActionTarget -> barred(e.tool, tools)
        else -> false
    }

    /** 路线只记能回放的那种界面上的做法:用过别的能力(网页、沙箱)的工具,照着回放不完整。 */
    private fun barred(tool: String, tools: ToolIndex): Boolean {
        val t = tools[tool] ?: return false
        if (t.verb in NO_ROUTE_VERBS) return true
        val routed = tools.names.mapNotNull { tools[it]?.takeIf { i -> i.routable }?.capability }.toSet()
        return t.capability != null && t.capability !in routed
    }

    private fun parse(s: String): JsonObject? = try { Json.parseToJsonElement(s) as? JsonObject } catch (e: Exception) { null }
}
