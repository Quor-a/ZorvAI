package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Concurrency
import com.ai.assistance.quro.core.mika.tool.RECALL_KEEP
import com.ai.assistance.quro.core.mika.tool.RECALL_MAX_IDS
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 回看之前的读屏(哪块屏的都行):能看图的主模型拿到当时的截图,其余给当时的元素文字。 */
internal object ViewPastScreens : AgentTool {
    override val verb = Verb.ViewPastScreens
    override val risk = Risk.Read
    override val withScreens = true
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("view_past_screens",
        Prompts.tool("view_past_screens", "keep" to RECALL_KEEP),
        mapOf("ids" to buildJsonObject {
            put("type", "array"); put("description", "1-$RECALL_MAX_IDS screenshotIds from earlier screen reads.")
            put("minItems", 1); put("maxItems", RECALL_MAX_IDS)
            put("items", buildJsonObject { put("type", "integer"); put("minimum", 1) })
        }), listOf("ids"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ids = a.intList("ids", 1, RECALL_MAX_IDS)!!.distinct()
        val images = mutableListOf<ByteArray>()
        val imageIds = mutableListOf<Int?>()
        val recalls = ctx.shots.recalls
        val found = ids.mapNotNull { id -> recalls.firstOrNull { it.id == id } }
        val missing = ids.filter { id -> found.none { it.id == id } }
        val content = buildJsonObject {
            put("ok", missing.isEmpty())
            put("screenshots", JsonArray(found.map { r ->
                buildJsonObject {
                    put("id", r.id); put("step", r.step); put("app", r.app)
                    if (r.png != null) { images += r.png; imageIds += r.id; put("imageAttached", true) } else put("elementsText", r.elementsText)
                }
            }))
            if (missing.isNotEmpty()) {
                put("missing", JsonArray(missing.map(::JsonPrimitive)))
                put("error", "screenshot_not_found")
                put("message", Feedback.format("recall_not_found", "keep" to RECALL_KEEP))
                put("recoverable", true)
                // 找不到的是旧画面,不是手机操作失败
                put("counts_as_failure", false)
            }
        }
        return ToolExecution(content, images = images, imageIds = imageIds)
    }
}
