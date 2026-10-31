package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.memory.ProfileChange
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** 只接受本轮用户原话支持的画像修改，失败时带出现有条目供用户选择。 */
internal object ProfileEdit : AgentTool {
    override val verb = Verb.ProfileEdit
    override val risk = Risk.Read
    override val def = ToolDef("profile_edit", Prompts.tool("profile_edit"), mapOf(
        "op" to str("add, replace, or delete", listOf("add", "replace", "delete")),
        "text" to str("For add: one sentence. For replace: new text for the matched segment."),
        "match" to str("For replace or delete: unique exact segment of an existing profile sentence."),
        "quote" to str("User's exact words in this run supporting this change."),
    ), listOf("op", "quote"))

    override fun available(ctx: ToolContext) = ctx.config.profile != null && ctx.config.utterances != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val op = a.str("op")!!
        val quote = a.str("quote", max = 1000)!!
        val utterances = ctx.config.utterances!!
        if (!utterances.contains(quote)) return fail("quote_not_from_user", "The quote must be exact user words from this run.")
        val text = a.str("text", max = 160)
        val match = a.str("match", max = 160)
        if (op in setOf("add", "replace") && text == null) throw ArgError("text", "required")
        if (op in setOf("replace", "delete") && match == null) throw ArgError("match", "required")
        if (text != null && utterances.unsupportedSpecifics(text, quote).isNotEmpty())
            return fail("content_not_from_user", "The new profile text must be supported by the user's words.")
        val profile = ctx.config.profile!!
        val change = when (op) {
            "add" -> profile.add(text!!)
            "replace" -> profile.replace(match!!, text!!)
            "delete" -> profile.delete(match!!)
            else -> throw ArgError("op", "must be add, replace or delete")
        }
        return when (change) {
            is ProfileChange.Applied -> ok { put("applied", true); change.entry?.let { put("id", it.id) }; put("effective", "next_run") }
            is ProfileChange.Rejected -> fail(change.code, "Profile change rejected. Existing entries: " +
                change.existing.joinToString(" | ") { it.text })
        }
    }
}
