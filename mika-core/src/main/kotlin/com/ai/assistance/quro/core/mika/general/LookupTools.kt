package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.knowledge.GuideView
import com.ai.assistance.quro.core.mika.knowledge.KNOWLEDGE_KINDS
import com.ai.assistance.quro.core.mika.knowledge.KnowledgeIndex
import com.ai.assistance.quro.core.mika.knowledge.PERSONAL_KIND
import com.ai.assistance.quro.core.mika.knowledge.SKILL_KINDS
import com.ai.assistance.quro.core.mika.knowledge.toolsNote
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Concurrency
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 查 App 与网站知识;只读目录,有目录才提供。 */
internal object KnowledgeSearch : AgentTool {
    override val verb = Verb.KnowledgeSearch
    override val label = "查找 App/网站知识"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    private val KINDS = KnowledgeIndex.KINDS.filter { it in KNOWLEDGE_KINDS }
    override val def = ToolDef("knowledge_search",
        Prompts.tool("knowledge_search"),
        mapOf(
            "query" to query("App or website name, alias or topic words, 1-200 characters."),
            "kind" to str("Only this kind: app (a phone app) or site (a website).", KINDS),
        ), listOf("query"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 200)!!
        val kind = a.str("kind")
        if (kind != null && kind !in KINDS) throw ArgError("kind", "must be ${KINDS.joinToString(", ")}")
        return matches(ctx, q, kind, KNOWLEDGE_KINDS)
    }
}

/** 查技能(内置技能与用户在用的个人技能);只读目录,有目录才提供。 */
internal object SkillSearch : AgentTool {
    override val verb = Verb.SkillSearch
    override val label = "查找技能"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("skill_search", Prompts.tool("skill_search"), mapOf("query" to query("Words for the kind of task, 1-200 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution =
        matches(ctx, a.str("query", max = 200)!!, null, SKILL_KINDS)
}

/** 读 App 或网站知识的正文或附件。 */
internal object KnowledgeRead : AgentTool {
    override val verb = Verb.KnowledgeRead
    override val label = "查看 App/网站知识"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("knowledge_read",
        Prompts.tool("knowledge_read"),
        mapOf(
            "name" to str("Name from knowledge_search or from the knowledge given to you, optionally as app:<name> or site:<domain>."),
            "file_path" to FILE_PATH,
        ), listOf("name"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = read(a, ctx, KNOWLEDGE_KINDS)
}

/** 读技能的正文或附件,含用户的个人技能。 */
internal object SkillRead : AgentTool {
    override val verb = Verb.SkillRead
    override val label = "查看技能"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("skill_read",
        Prompts.tool("skill_read"),
        mapOf(
            "name" to str("Skill name from skill_search or from a skill list given to you, optionally as skill:<name> or personal:<name>."),
            "file_path" to FILE_PATH,
        ), listOf("name"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = read(a, ctx, SKILL_KINDS)
}

private fun query(description: String): JsonObject = buildJsonObject {
    put("type", "string"); put("description", description); put("minLength", 1); put("maxLength", 200)
}
private val FILE_PATH = str("Optional relative path of an attachment listed in files, e.g. references/stations.md.")

private fun matches(ctx: ToolContext, query: String, kind: String?, among: Set<String>): ToolExecution {
    val found = ctx.config.skills!!.find(query, kind, among = among)
    return ok {
        put("matches", JsonArray(found.map {
            buildJsonObject { put("name", it.name); put("display", it.display); put("kind", it.kind); put("description", it.description) }
        }))
    }
}

private suspend fun read(a: Args, ctx: ToolContext, among: Set<String>): ToolExecution {
    val name = a.str("name", max = 200)!!
    val file = a.str("file_path", max = 500)
    val catalog = ctx.config.skills!!
    val runner = ctx.scripts
    val ready = runner?.ready() == true
    return when (val v = catalog.view(name, file, toolsNote(runner?.offered() == true), among)) {
        is GuideView.Failed -> fail(v.error, v.message)
        is GuideView.Found -> {
            // 能跑脚本时列出这一条带的脚本和此刻能不能跑;scriptSource 是跑脚本时 source 要填的值
            // 个人技能只有文字,不带脚本
            val dir = if (v.info.kind == PERSONAL_KIND) "" else catalog.scriptSource(v.info)
            val scripts = if (ready && dir.isNotEmpty()) runner!!.scripts(dir) else emptyList()
            ok {
                put("name", v.info.name); put("kind", v.info.kind); put("file", v.file)
                put("content", v.content); put("truncated", v.truncated)
                put("files", JsonArray(v.info.files.map(::JsonPrimitive)))
                if (scripts.isNotEmpty()) {
                    put("scriptSource", dir)
                    put("scripts", JsonArray(scripts.map { e ->
                        buildJsonObject { put("file", e.file); put("runnable", e.runnable); e.reason?.let { put("reason", it) } }
                    }))
                }
            }
        }
    }
}
