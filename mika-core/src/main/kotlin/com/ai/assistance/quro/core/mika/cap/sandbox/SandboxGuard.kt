package com.ai.assistance.quro.core.mika.cap.sandbox

import com.ai.assistance.quro.core.mika.model.Part
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.guard.GuardVerdict
import com.ai.assistance.quro.core.mika.guard.Severity
import com.ai.assistance.quro.core.mika.guard.actionSignature
import com.ai.assistance.quro.core.mika.guard.OffScreenGuard

/** 一个任务里沙箱命令累计最多跑这么久。 */
const val SANDBOX_BUDGET_MS = 30 * 60_000L
private val SANDBOX_COMMANDS = setOf(RunCommand.name, RunBundledScript.name)
private const val SANDBOX_LOOP_WARN = 3
private const val SANDBOX_LOOP_STOP = 5
private const val SANDBOX_LOOP_HINT = "This exact command has failed several times in a row. Do not repeat it unchanged: read the error, then try a different approach."

/** 沙箱命令的守卫(每次任务一份):同一条命令原样重复且失败的次数,和累计运行时长。屏幕守卫不看沙箱工具。 */
class SandboxGuard(private val budgetMs: Long = SANDBOX_BUDGET_MS) : OffScreenGuard {
    private var usedMs = 0L
    private var lastSignature: String? = null
    private var repeats = 0

    override var stop: GuardVerdict? = null
        private set

    /** 时长用完时代替执行的结果;没用完为 null。 */
    override fun exhausted(tool: String): JsonObject? {
        if (tool !in SANDBOX_COMMANDS || usedMs < budgetMs) return null
        return buildJsonObject {
            put("ok", false); put("error", "sandbox_budget_exhausted")
            put("message", "Sandbox commands in this task have used their 30 minutes. Finish with what you have, or end_not_done.")
            put("recoverable", false); put("usedMs", usedMs)
        }
    }

    /** 命令的时限不超过剩下的时长,一条命令不会把累计时长冲过上限。参数不对的原样交给工具层去拒。 */
    override fun capTimeout(call: Part.ToolCall, args: JsonObject): Part.ToolCall {
        if (call.name !in SANDBOX_COMMANDS || args.isEmpty()) return call
        val leftSec = ((budgetMs - usedMs) / 1000).coerceAtLeast(1)
        val given = args["timeoutSec"]
        val requested = if (given == null) DEFAULT_TIMEOUT_SEC.toLong()
        else (given as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.toLong() ?: return call
        if (requested !in 1..600 || requested <= leftSec) return call
        return call.copy(argsJson = JsonObject(args + ("timeoutSec" to JsonPrimitive(leftSec))).toString())
    }

    override fun result(tool: String, args: JsonObject, content: JsonObject): List<GuardVerdict> {
        if (tool !in SANDBOX_COMMANDS) return emptyList()
        (content["durationMs"] as? JsonPrimitive)?.longOrNull?.let { usedMs += it }
        if ((content["ok"] as? JsonPrimitive)?.booleanOrNull != false) {
            lastSignature = null; repeats = 0
            return emptyList()
        }
        val sig = actionSignature(tool, args)
        repeats = if (sig == lastSignature) repeats + 1 else 1
        lastSignature = sig
        if (repeats < SANDBOX_LOOP_WARN) return emptyList()
        val v = GuardVerdict("sandbox_loop", if (repeats >= SANDBOX_LOOP_STOP) Severity.Stop else Severity.Warn, buildJsonObject {
            put("tool", tool); put("repeatCount", repeats); put("hint", SANDBOX_LOOP_HINT)
        })
        if (v.severity == Severity.Stop) stop = v
        return listOf(v)
    }
}
