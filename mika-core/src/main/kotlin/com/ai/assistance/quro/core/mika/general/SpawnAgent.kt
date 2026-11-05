package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.child.ChildPhase
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Trust
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.run.Attendance
import com.ai.assistance.quro.core.mika.run.RunId
import com.ai.assistance.quro.core.mika.run.RunOrigin
import com.ai.assistance.quro.core.mika.run.RunProvenance
import com.ai.assistance.quro.core.mika.run.RunSpec
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.str
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SpawnAgent : AgentTool {
    /** 子任务默认的步数、步数上限。 */
    const val DEFAULT_STEPS = 15
    const val MAX_STEPS = 40
    /** 子任务结果默认留多少字、最多留多少字、最少留多少字。 */
    const val DEFAULT_RESULT_CHARS = 4_000
    const val MAX_RESULT_CHARS = 8_000
    const val MIN_RESULT_CHARS = 500

    override val verb = Verb.SpawnAgent
    override val risk = Risk.Write
    override val offScreen = true
    override val def = ToolDef("spawn_agent", Prompts.tool("spawn_agent",
        "steps" to DEFAULT_STEPS, "maxSteps" to MAX_STEPS, "chars" to DEFAULT_RESULT_CHARS, "maxChars" to MAX_RESULT_CHARS), mapOf(
        "goal" to str("A bounded research question for the child agent, using web and sandbox reads only."),
        "format" to str("Optional output format.", listOf("text", "json")),
        "maxSteps" to int("Optional step budget for the child, 1-$MAX_STEPS (default $DEFAULT_STEPS); larger values are lowered to $MAX_STEPS.", 1),
        "maxResultChars" to int("Optional length limit of the returned result in characters, $MIN_RESULT_CHARS-$MAX_RESULT_CHARS (default $DEFAULT_RESULT_CHARS); values outside are moved into range.", 1),
    ), listOf("goal"))

    override fun available(ctx: ToolContext) = ctx.config.childHost != null && ctx.config.runSpec != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val parent = ctx.config.runSpec ?: error("no parent RunSpec")
        val host = ctx.config.childHost ?: error("no child host")
        val goal = a.str("goal", max = 4000)!!
        val format = a.str("format")
        // 只校验是整数;超出范围的拉回范围内,不当错误(子任务照样能派)
        val steps = (a.int("maxSteps", Int.MIN_VALUE, Int.MAX_VALUE) ?: DEFAULT_STEPS).coerceIn(1, MAX_STEPS)
        val limit = (a.int("maxResultChars", Int.MIN_VALUE, Int.MAX_VALUE) ?: DEFAULT_RESULT_CHARS).coerceIn(MIN_RESULT_CHARS, MAX_RESULT_CHARS)
        val child = RunSpec(RunId(UUID.randomUUID().toString()), goal, parent.threadId,
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended),
            capabilities = setOf("web", "sandbox"), readOnly = true, parent = parent.id, stepBudget = steps)
        val result = host.spawn(child, format)
        val text = result.text.take(limit)
        return ToolExecution(buildJsonObject {
            put("ok", result.phase == ChildPhase.Completed)
            put("childRunId", result.id.value)
            put("status", result.phase.name.lowercase())
            put("maxSteps", steps)
            put("result", Untrusted.wrap(text))
            if (result.text.length > limit) {
                put("truncated", true)
                put("fullLength", result.text.length)
            }
        }, observations = listOf(Observation(ObsKind.Reply, text, source = "child", trust = Trust.Untrusted)))
    }
}
