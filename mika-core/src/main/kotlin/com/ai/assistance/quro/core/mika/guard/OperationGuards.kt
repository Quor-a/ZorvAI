package com.ai.assistance.quro.core.mika.guard

import com.ai.assistance.quro.core.mika.prompt.Feedback
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

enum class Severity(val wire: String) { Warn("warn"), Stop("stop") }

/**
 * 守卫的一条结论。提醒([Severity.Warn])带 [nudge]:说清卡在哪、给几条出路的一段话(发给模型);叫停的没有。
 */
data class GuardVerdict(val code: String, val severity: Severity, val evidence: JsonObject, val nudge: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", code); put("severity", severity.wire); put("evidence", evidence)
        nudge?.let { put("nudge", it) }
    }
}

/** 键排序后的稳定序列化,使参数顺序不影响签名。 */
private fun stable(e: JsonElement): String = when (e) {
    is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { "${JsonPrimitive(it)}:${stable(e[it]!!)}" }
    is JsonArray -> e.joinToString(",", "[", "]") { stable(it) }
    else -> e.toString()
}

private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private val SWIPE_KEYS = listOf("startX", "startY", "endX", "endY")

/** 动作签名:忽略 reason,坐标按 50 取整,滑动只记方向。 */
fun actionSignature(tool: String, args: JsonObject): String {
    val n = args.toMutableMap()
    n.remove("reason")
    val x = num(n["x"]); val y = num(n["y"])
    if (x != null && y != null) {
        n["x"] = JsonPrimitive(floor(x / 50).toLong())
        n["y"] = JsonPrimitive(floor(y / 50).toLong())
    }
    val s = SWIPE_KEYS.map { num(n[it]) }
    if (s.all { it != null }) {
        val dx = s[2]!! - s[0]!!; val dy = s[3]!! - s[1]!!
        val vertical = abs(dy) >= abs(dx)
        n["swipeDirection"] = JsonPrimitive("${if (vertical) "V" else "H"}${if ((if (vertical) dy else dx) < 0) "-" else "+"}")
        SWIPE_KEYS.forEach { n.remove(it) }
    }
    return "$tool|${stable(JsonObject(n))}"
}

/**
 * 每次任务一份的操作守卫。只有动作确实没有效果、重复读取没有新内容或工具连续失败时才提醒并最终叫停。
 * 相同的点击/滚动和已见过的界面指纹本身不代表停滞；新的页面或读取内容会清掉无进展计数。
 */
class OperationGuards(private val stepBudget: Int? = null) {

    private var frameId: String? = null
    private var frameHash: Long? = null
    private var acted = false
    /** 上一个动作已按它报的实际效果记过「有没有效果」:接着的界面不再按指纹重复记。 */
    private var effectNoted = false

    /** 连续没有效果的动作数。 */
    private var unchanged = 0
    private var unchangedWarnedAt = 0

    /** 到过的每个界面状态(元素表指纹)。 */
    private val seenStates = HashSet<Long>()
    /** 自上次到过新状态起,回到各状态的次数与已提醒到的门槛。 */
    private val visits = HashMap<Long, Int>()
    private val visitsWarnedAt = HashMap<Long, Int>()
    /** 先后看到的各状态(相邻的不同),最近几个。 */
    private val trail = ArrayDeque<Long>()
    private var cycleWarnedAt = 0
    /** 自上次到过新状态起做了几个动作。 */
    private var sinceNewState = 0
    private var sinceNewWarnedAt = 0

    /** 任务的计数;画面每变一次 [epoch] 加一,各段读的计数据此作废。 */
    private val reads = GuardScope(canStop = true)
    /** 跨页面记住读过的内容；返回同一页时不把旧内容当作任务新进展。 */
    private val taskReads = HashSet<Long>()
    private var epoch = 0

    private var consecutiveFailures = 0
    private var unrecoverableFailures = 0
    private val recentFailures = ArrayDeque<JsonObject>()

    var stop: GuardVerdict? = null
        private set
    val unchangedFrames: Int get() = unchanged
    var progressVersion: Long = 0
        private set

    /** 提醒附上卡在哪(guard.<code> 那条,填 [count])和出路;叫停的不附。 */
    private fun verdict(code: String, severity: Severity, evidence: JsonObject, count: Int, waysOut: Boolean = true, period: Int? = null): List<GuardVerdict> {
        val nudge = if (severity == Severity.Warn) listOfNotNull(
            if (period != null) Feedback.format(LEADS.getValue(code), "count" to count, "states" to period) else Feedback.format(LEADS.getValue(code), "count" to count),
            Feedback["guard.ways_out"].takeIf { waysOut },
        ).joinToString(" ") else null
        val v = GuardVerdict(code, severity, evidence, nudge)
        if (severity == Severity.Stop) stop = v
        return listOf(v)
    }

    private fun <T> ArrayDeque<T>.pushCapped(v: T, cap: Int = 10) { addLast(v); if (size > cap) removeFirst() }

    /** 保留调用入口；动作签名不参与停滞判定。 */
    fun beforeAction(tool: String, args: JsonObject, diagnostic: Boolean = false, scope: GuardScope = reads): List<GuardVerdict> {
        // 相同动作可以逐页读取新内容；仅凭动作签名不能判断任务停滞。
        return emptyList()
    }

    /** 一个会改变界面的动作要做了。 */
    fun noteAction() {
        acted = true
        effectNoted = false
        sinceNewState += 1
    }

    /**
     * 动作报回的实际效果(effect 的 kind,见 core.uistate.EffectKind):no_effect 记一次「没有效果」,
     * 明显变了、换了 App 或换了页就从头计;小的局部变化两样都不算。
     */
    fun effect(kind: String): List<GuardVerdict> {
        effectNoted = true
        when (kind) {
            NO_EFFECT -> unchanged += 1
            else -> { unchanged = 0; unchangedWarnedAt = 0; return emptyList() }
        }
        return noProgress()
    }

    private fun noProgress(): List<GuardVerdict> {
        val rung = GuardRules.climb(unchanged, GuardRules.NO_EFFECT_LADDER, unchangedWarnedAt, canStop = true) ?: return emptyList()
        unchangedWarnedAt = rung.threshold
        return verdict("no_progress", rung.severity, buildJsonObject { put("unchanged", unchanged) }, unchanged)
    }

    /**
     * 模型拿到了一份界面(读界面,或动作结果里带的),[hash] 是它的指纹。只有「动过之后界面仍一样」才算没有效果
     * (动作已报过效果的不再重复记);单纯反复看不算。动作之后到了哪个状态还用来看回到同一状态、兜圈、一直没到新状态。
     */
    fun frame(id: String, hash: Long): List<GuardVerdict> {
        if (frameId == id) return emptyList()
        val same = frameHash == hash
        val didAct = acted
        val noted = effectNoted
        acted = false
        effectNoted = false
        frameId = id
        frameHash = hash
        val out = mutableListOf<GuardVerdict>()
        if (!same) {
            pageChanged()
            if (seenStates.add(hash)) progress()
        }
        if (didAct && !noted) {
            if (same) { unchanged += 1; out += noProgress() } else { unchanged = 0; unchangedWarnedAt = 0 }
        }
        // 旧界面本身不能证明失败，只提醒模型评估是否有新内容或换路线。
        if (didAct) out += noNewState()
        return out
    }

    /** 动作之后回到一个已到过的状态。正在兜圈时由 [cycle] 说,这里不重复。 */
    private fun arrived(hash: Long): List<GuardVerdict> {
        val n = (visits[hash] ?: 1) + 1
        visits[hash] = n
        if (GuardRules.cycle(trail.toList()) != null) return emptyList()
        val rung = GuardRules.climb(n, GuardRules.sameStateLadder(stepBudget), visitsWarnedAt[hash] ?: 0, canStop = true) ?: return emptyList()
        visitsWarnedAt[hash] = rung.threshold
        return verdict("same_state", rung.severity, buildJsonObject { put("visits", n) }, n)
    }

    private fun cycle(): List<GuardVerdict> {
        val c = GuardRules.cycle(trail.toList())
        if (c == null) { cycleWarnedAt = 0; return emptyList() }
        val rung = GuardRules.climb(c.rounds, GuardRules.cycleLadder(stepBudget), cycleWarnedAt, canStop = true) ?: return emptyList()
        cycleWarnedAt = rung.threshold
        return verdict("state_cycle", rung.severity, buildJsonObject { put("states", c.period); put("rounds", c.rounds) }, c.rounds, period = c.period)
    }

    private fun noNewState(): List<GuardVerdict> {
        val rung = GuardRules.climb(sinceNewState, GuardRules.noNewStateLadder(stepBudget), sinceNewWarnedAt, canStop = false) ?: return emptyList()
        sinceNewWarnedAt = rung.threshold
        return verdict("no_new_state", rung.severity, buildJsonObject { put("actions", sinceNewState) }, sinceNewState)
    }

    /**
     * 一次读屏/读网页/网页脚本:画面没变、也没做有效操作时反复读,读不出新东西(要找的东西不在这一页上)。
     * [fresh] 为 true 表示这次读到了之前没读到过的内容(如读整页换了起点),不算重读。
     * 换着工具、换着脚本读也算:页面没变,答案就是页面上现有的这些。
     * [window] 给了就在它里面单独计数(页面脚本一次调用里的读),不动任务的计数。
     */
    fun read(tool: String, fresh: Boolean, window: GuardScope = reads, fingerprint: Long? = null): List<GuardVerdict> {
        if (fresh) {
            if (fingerprint == null || taskReads.add(fingerprint)) progress()
            return emptyList()
        }
        window.sync(epoch)
        window.rereads += 1
        val n = window.rereads
        val rung = GuardRules.climb(n, GuardRules.REREAD_LADDER, window.warnedAt, window.canStop) ?: return emptyList()
        window.warnedAt = rung.threshold
        return verdict("read_loop", rung.severity, buildJsonObject {
            put("tool", tool); put("rereadsOfUnchangedPage", n)
        }, n)
    }

    /** 这份读到的结果是不是画面上次变化以来头一回读到。 */
    fun firstSeen(fingerprint: Long, window: GuardScope = reads): Boolean {
        window.sync(epoch)
        return window.seen.add(fingerprint)
    }

    /** 画面变了(或做了有效的操作):之前读到的不再作数(所有读的计数一起作废)。 */
    fun pageChanged() {
        epoch += 1
    }

    private fun progress() {
        progressVersion += 1
        unchanged = 0
        unchangedWarnedAt = 0
        sinceNewState = 0
        sinceNewWarnedAt = 0
        if (stop?.code == "no_progress" || stop?.code == "read_loop") stop = null
    }

    fun result(tool: String, content: JsonObject, neutral: Boolean = false): List<GuardVerdict> {
        val ok = (content["ok"] as? JsonPrimitive)?.booleanOrNull
        val countsAsFailure = (content["counts_as_failure"] as? JsonPrimitive)?.booleanOrNull
        if (neutral || countsAsFailure == false || ok != false) {
            consecutiveFailures = 0
            unrecoverableFailures = 0
            return emptyList()
        }
        val recoverable = (content["recoverable"] as? JsonPrimitive)?.booleanOrNull != false
        consecutiveFailures += 1
        if (!recoverable) unrecoverableFailures += 1
        recentFailures.pushCapped(buildJsonObject {
            put("tool", tool); put("recoverable", recoverable)
            (content["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { put("error", it.content) }
        })
        val severity = GuardRules.failures(consecutiveFailures, unrecoverableFailures) ?: return emptyList()
        return verdict("too_many_failures", severity, buildJsonObject {
            put("consecutive", consecutiveFailures)
            put("unrecoverable", unrecoverableFailures)
            put("recent", JsonArray(recentFailures.toList().takeLast(5)))
        }, consecutiveFailures, waysOut = consecutiveFailures >= GuardRules.FAILURE_WAYS_OUT || unrecoverableFailures > 0)
    }

    private companion object {
        const val NO_EFFECT = "no_effect"
        /** 记最近多少个状态(够看出最长的兜圈安全网)。 */
        const val TRAIL_KEEP = 64

        /** 各条提醒开头说卡在哪的那句(prompts/feedback/tools.md)。 */
        val LEADS = mapOf(
            "repeating_action" to "guard.repeating_action", "no_progress" to "guard.no_progress", "same_state" to "guard.same_state",
            "state_cycle" to "guard.state_cycle", "no_new_state" to "guard.no_new_state", "read_loop" to "guard.read_loop",
            "too_many_failures" to "guard.too_many_failures",
        )
    }
}

/**
 * 一段计数:最近的动作签名(重复操作),读到过的结果指纹、又读了一遍没变的页面的次数。
 * [canStop] 为 false 时最多提醒,不叫停任务。读的计数在画面变了([OperationGuards.pageChanged])时作废重计。
 */
class GuardScope(val canStop: Boolean) {
    internal val loop = ArrayDeque<String>()
    internal var loopWarnedAt = 0
    internal val seen = HashSet<Long>()
    internal var rereads = 0
    internal var warnedAt = 0
    private var epoch = 0

    internal fun sync(now: Int) {
        if (now == epoch) return
        epoch = now
        seen.clear(); rereads = 0; warnedAt = 0
    }
}
