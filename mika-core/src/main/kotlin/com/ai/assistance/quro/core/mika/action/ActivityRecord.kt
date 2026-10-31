package com.ai.assistance.quro.core.mika.action

import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 从真实执行结果生成活动,避免由模型的进度文字猜测做了什么。 */
internal object ActivityRecord {
    private val controls = setOf(
        Verb.Wait, Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser,
        Verb.HandleObstacle, Verb.EndNotDone,
    )
    // 元素编号(ref)只对模型有意义,不当作给人看的对象
    private val targetKeys = listOf("package", "url", "path", "query")

    fun of(
        tools: ToolDispatcher, tool: String, args: JsonObject, exec: ToolExecution,
        step: Int, source: String, callId: String, atMs: Long, outward: Boolean = false,
    ): RunEvent.Activity? {
        val verb = tools.verb(tool)
        if (verb in controls) return null
        val target = RunEvent.placeLabel(exec.place)
            ?: targetKeys.firstNotNullOfOrNull { (args[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        val content = exec.content
        val failed = content["ok"] == JsonPrimitive(false)
        val effect = if (failed) {
            (content["error"] as? JsonPrimitive)?.contentOrNull ?: "failed"
        } else {
            ((content["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
                ?: when (exec.changed) { true -> "changed"; false -> "unchanged"; null -> "completed" }
        }
        return RunEvent.Activity(
            atMs, step, if (tools.declaredRisk(tool) == Risk.Read) "observe" else "act",
            tool, target?.let { RunEvent.clip(Redactor.text(it)) }, effect,
            exec.observations.map { it.id }.filter { it > 0 }.distinct(), source, callId,
            exec.observations.lastOrNull { !it.title.isNullOrBlank() }?.title?.let { RunEvent.clip(Redactor.text(it)) },
            failed, outward,
        )
    }
}
