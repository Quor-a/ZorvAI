package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.prompt.Feedback
import kotlinx.serialization.json.JsonObject
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.FAILURE_MESSAGES
import com.ai.assistance.quro.core.mika.tool.fail
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.lastSnapshot

/**
 * 页面脚本:自己不动页面,脚本里的每个动作经执行入口回来(守卫、关卡、不可逆判定、记录照常);
 * 不过锁屏关卡,也不能作为只读调用。
 */
internal object WebScriptTool : WebTool {
    override val verb = Verb.Script
    override val risk = Risk.Write
    override val composite = true
    override val def = ToolDef("web_script",
        Prompts.tool("web_script", "max_actions" to SCRIPT_MAX_ACTIONS, "max_reads" to SCRIPT_MAX_READS, "max_seconds" to SCRIPT_MAX_TIMEOUT_MS / 1000),
        mapOf(
            "code" to buildJsonObject {
                put("type", "string"); put("minLength", 1); put("maxLength", SCRIPT_CODE_MAX_CHARS)
                put("description", "Body of an async function, e.g. `const s = await quro.snapshot(); return s.elements.filter(e => e.role === 'link').map(e => e.name);`")
            },
            "timeoutMs" to buildJsonObject {
                put("type", "integer"); put("minimum", 1000); put("maximum", SCRIPT_MAX_TIMEOUT_MS)
                put("description", "Time limit for the whole script, 1000-$SCRIPT_MAX_TIMEOUT_MS ms (default $SCRIPT_DEFAULT_TIMEOUT_MS).")
            },
        ), listOf("code"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val code = a.str("code", max = SCRIPT_CODE_MAX_CHARS)!!
        PageScriptProtocol.codeProblem(code)?.let { throw ArgError("code", it) }
        val timeoutMs = (a.int("timeoutMs", 1000, SCRIPT_MAX_TIMEOUT_MS) ?: SCRIPT_DEFAULT_TIMEOUT_MS).toLong()
        val calls = ScriptCalls(ctx.callId, ctx.sub, ctx.tools)
        val run = try {
            // 时限由端口管:只算脚本自己的时间,动作(含等锁屏关卡)在做时不走表,也不会被掐在半中间
            ctx.web!!.runScript(code, timeoutMs, calls)
        } catch (e: WebTabCrashed) {
            ScriptRun.Failed("tab_crashed", FAILURE_MESSAGES.getValue("tab_crashed"))
        } catch (e: WebTabHandedOver) {
            ScriptRun.Failed("handed_over", FAILURE_MESSAGES.getValue("handed_over"))
        } catch (e: WebDialogOpen) {
            ScriptRun.Failed(DIALOG_OPEN, FAILURE_MESSAGES.getValue(DIALOG_OPEN))
        } catch (e: ScriptAbort) {
            ScriptRun.Failed(e.error, e.message.orEmpty())
        }
        val content = calls.content(run)
        val result = (content["result"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
        val seen = result?.let { webRead("页面脚本的结果(${ctx.lastSnapshot?.web?.url ?: "当前网页"})\n$it", ctx.lastSnapshot?.web?.url) }
        // 脚本的结果来自网页:交给模型时套一层不可信的标记
        val shown = if (result == null) content else JsonObject(content +
            ("result" to JsonPrimitive(Untrusted.wrapWithin(result, EVAL_RESULT_MAX_CHARS))) +
            ("truncated" to JsonPrimitive(content["truncated"] == JsonPrimitive(true) || result.length > EVAL_RESULT_MAX_CHARS - Untrusted.OVERHEAD)))
        return ToolExecution(shown).observing(seen)
    }
}
