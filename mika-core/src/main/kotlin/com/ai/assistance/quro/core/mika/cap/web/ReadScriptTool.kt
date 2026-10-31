package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.knowledge.SiteCommandFile
import com.ai.assistance.quro.core.mika.net.UrlHost
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.lastSnapshot

private fun timeoutProp() = int("Time limit, 1000-$READ_SCRIPT_MAX_TIMEOUT_MS ms (default $READ_SCRIPT_DEFAULT_TIMEOUT_MS).", 1000, READ_SCRIPT_MAX_TIMEOUT_MS)

/** 读脚本(读数据脚本、站点命令)跑在端口上;结果来自网页,交给模型时套不可信标记,也作为观察交给核查。 */
private suspend fun readScript(ctx: ToolContext, code: String, args: JsonObject?, timeoutMs: Int, what: String): ToolExecution {
    val r = try {
        ctx.web!!.evaluate(code, timeoutMs.toLong(), args)
    } catch (e: WebTabCrashed) {
        return failCode("tab_crashed")
    } catch (e: WebTabHandedOver) {
        return failCode("handed_over")
    } catch (e: WebDialogOpen) {
        return failCode(DIALOG_OPEN)
    }
    val url = ctx.lastSnapshot?.web?.url
    return when (r) {
        is EvalJs.Failed -> fail(r.error, r.message)
        is EvalJs.Ok -> ok {
            put("result", Untrusted.wrap(r.json))
            put("truncated", r.truncated)
            if (r.fresh) put("state", "new")
        }.observing(webRead("$what(${url ?: "当前网页"})\n${r.json}", url))
    }
}

/** 读数据脚本:在当前页面的读取世界里只读地跑(真实 DOM、同源 GET);要动页面走动作工具。 */
internal object EvaluateJs : WebTool {
    override val verb = Verb.EvaluateJs
    override val label = "读取网页数据"
    override val risk = Risk.Read
    override val def = ToolDef("web_evaluate_js",
        Prompts.tool("web_evaluate_js", "result_max" to EVAL_RESULT_MAX_CHARS, "default_seconds" to READ_SCRIPT_DEFAULT_TIMEOUT_MS / 1000),
        mapOf(
            "script" to str("JavaScript, 1-$EVAL_SCRIPT_MAX_CHARS characters: one expression (e.g. `document.title`), or the body of an async function that returns the result."),
            "timeoutMs" to timeoutProp(),
        ),
        listOf("script"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val script = a.str("script", max = EVAL_SCRIPT_MAX_CHARS)!!
        PageScriptProtocol.codeProblem(script)?.let { throw ArgError("script", it) }
        val timeout = a.int("timeoutMs", 1000, READ_SCRIPT_MAX_TIMEOUT_MS) ?: READ_SCRIPT_DEFAULT_TIMEOUT_MS
        return readScript(ctx, script, null, timeout, "网页数据脚本的结果")
    }
}

/**
 * 站点命令:当前站点知识里声明的 `scripts/<name>.js`,在读取世界里只读地跑,参数按声明校验。
 * 只有内置知识里有站点命令时才提供;只认当前页面所属站点的命令。
 */
internal object RunSiteCommand : WebTool {
    override val verb = Verb.SiteCommand
    override val label = "运行站点命令"
    override val risk = Risk.Read
    override fun available(ctx: ToolContext) = ctx.web != null && ctx.config.skills?.hasSiteCommands() == true
    override val def = ToolDef("web_run_site_command",
        Prompts.tool("web_run_site_command", "result_max" to EVAL_RESULT_MAX_CHARS),
        mapOf(
            "name" to str("Command name, as listed for the current site."),
            "args" to buildJsonObject {
                put("type", "object"); put("description", "Arguments the command declares, name to value (omit when it takes none).")
            },
            "timeoutMs" to timeoutProp(),
        ),
        listOf("name"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val name = a.str("name", max = 40)!!.trim()
        val args = when (val v = a.o["args"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> v
            else -> throw ArgError("args", "must be an object")
        }
        val timeout = a.int("timeoutMs", 1000, READ_SCRIPT_MAX_TIMEOUT_MS) ?: READ_SCRIPT_DEFAULT_TIMEOUT_MS
        val url = currentUrl(ctx) ?: return failCode("no_tab_open")
        val host = UrlHost.of(url) ?: return fail("unknown_command", "The current page ($url) is not on a site with commands.")
        val commands = ctx.config.skills?.siteCommands(host).orEmpty()
        val command = commands.firstOrNull { it.decl.name == name }
            ?: return fail("unknown_command",
                if (commands.isEmpty()) "$host has no site commands; read the page with web_read_page or web_evaluate_js."
                else "$host has no command '$name'. Its commands: ${commands.joinToString { it.decl.name }}.")
        SiteCommandFile.checkArgs(command.decl, args)?.let { throw ArgError("args", it) }
        return readScript(ctx, command.body, args, timeout, "站点命令 ${command.site}/${command.decl.name} 的结果")
    }

    /** 当前标签的网址:按标签列表,读不到时用最近一次读屏的。 */
    private suspend fun currentUrl(ctx: ToolContext): String? {
        val tabs = try { ctx.web!!.tabs() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        return tabs.firstOrNull { it.current }?.url?.takeIf { it.isNotBlank() } ?: ctx.lastSnapshot?.web?.url
    }
}
