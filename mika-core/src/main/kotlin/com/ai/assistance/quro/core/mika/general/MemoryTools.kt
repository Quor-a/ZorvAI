package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.memory.MemoryIndex
import com.ai.assistance.quro.core.mika.memory.MemoryKind
import com.ai.assistance.quro.core.mika.memory.MemoryRejected
import com.ai.assistance.quro.core.mika.memory.MemorySource
import com.ai.assistance.quro.core.mika.memory.MemoryStore
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Concurrency
import com.ai.assistance.quro.core.mika.tool.EvidenceLog
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val MEMORY_SEARCH_LIMIT = 8

/** 记忆和用户本次说过的话都给了才提供。 */
private fun memoryOn(ctx: ToolContext) = ctx.config.memory != null && ctx.config.utterances != null

internal object MemorySearch : AgentTool {
    override val verb = Verb.MemorySearch
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("memory_search",
        Prompts.tool("memory_search"),
        mapOf("query" to str("Words to look for, 1-100 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 100)!!
        val found = MemoryIndex(ctx.config.memory!!.items.value).search(q, MEMORY_SEARCH_LIMIT)
        return ok {
            put("memories", JsonArray(found.map { (m, _) ->
                buildJsonObject { put("id", m.id); put("kind", m.kind.name); put("content", m.content) }
            }))
        }
    }

    /** 记忆里的是用户以前说的:作为用户给的事实交给核查。 */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((r.content["memories"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { list ->
            Observation(ObsKind.UserSaid, list.joinToString("\n", "记忆检索「${o.argStr("query")}」:\n") { m -> ((m as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty() },
                source = "memory")
        })
    }
}

/** 只改 ZorvAI 自己的记忆,不动屏幕和外部世界。 */
internal object MemorySave : AgentTool {
    override val verb = Verb.MemorySave
    override val risk = Risk.Read
    override val def = ToolDef("memory_save",
        Prompts.tool("memory_save"),
        mapOf(
            "kind" to str("fact = about the user, preference = what the user likes, procedure = how the user wants a kind of task done.", listOf("fact", "preference", "procedure")),
            "content" to str("One sentence in the third person, at most 200 characters, e.g. 用户点外卖不要香菜."),
            "quote" to str("The user's exact words this is based on."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "Optional, up to 8 search words incl. synonyms.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("kind", "content", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val kind = a.str("kind")!!.let { k -> MemoryKind.entries.find { it.name == k } ?: throw ArgError("kind", "must be fact, preference or procedure") }
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)!!
        val quote = a.str("quote", max = 1000)!!
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30) ?: emptyList()
        val utterances = ctx.config.utterances!!
        // 引文要出自用户本次说的话;屏幕和网页上的字不能当成用户说的
        if (!utterances.contains(quote)) return fail("quote_not_from_user",
            Feedback["memory.quote_not_from_user"], recoverable = false)
        // 引文对上了,内容里的具体值(地址、号码、名字)也得是用户说过的,不能从屏幕上补
        if ((listOf(content) + keywords).any { utterances.unsupportedSpecifics(it, quote).isNotEmpty() }) return fail("content_not_from_user",
            Feedback["memory.content_not_from_user"], recoverable = false)
        val store = ctx.config.memory!!
        val added = store.add(kind, content, keywords, MemorySource("user_said", quote.trim(), ctx.config.conversationId, store.clock(),
            ctx.config.runSpec?.provenance?.origin?.name))
        added.getOrNull()?.let(ctx::noteSaved)
        return added.fold(
            onSuccess = { c -> ok { put("id", c.item.id); put("kind", c.item.kind.name); put("status", c.item.status.name) } },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> fail("sensitive", Feedback["memory.sensitive"], recoverable = false)
                    "memory_full" -> fail("memory_full", Feedback["memory.full"], recoverable = false)
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }
}
