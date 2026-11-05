package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 留下的那一页为什么关了;[wire] 记进对话。到时间、内存不够被系统收回、渲染进程崩了、用户关的、留了另一页、ZorvAI 退出了。
 */
enum class KeptPageClose(val wire: String) {
    Expired("expired"), Memory("memory"), Crashed("crashed"), User("user"), Replaced("replaced"), AppExit("app_exit");

    companion object {
        fun of(wire: String?): KeptPageClose? = entries.firstOrNull { it.wire == wire }
    }
}

/** 任务结束后留下的那一页至多留几个小时(到时自动关)。 */
const val KEEP_OPEN_HOURS = 2

/**
 * 任务结束时默认关掉这个任务的全部网页;要页面在回答之后接着运行(接着播放、挂着、等它自己走完)时,用它把当前这一页留下。
 * 只是记下「结束时留下」,此刻什么也不动;全局只留一页,留新的会关掉旧的;撤销用 keep=false。
 */
internal object KeepOpen : WebTool {
    override val verb = Verb.KeepOpen
    override val label = "保持网页开着"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("web_keep_open",
        Prompts.tool("web_keep_open", "hours" to KEEP_OPEN_HOURS),
        mapOf(
            "reason" to str("Why this page must keep running after the task, in a few words (e.g. the user wants the video to keep playing)."),
            "keep" to buildJsonObject { put("type", "boolean"); put("description", "false takes back an earlier keep in this task (default true).") },
        ), listOf("reason"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val reason = a.str("reason", max = 200)!!.trim()
        val keep = a.bool("keep", true)
        val r = try { ctx.web!!.keepOpen(keep, reason) } catch (e: WebTabCrashed) { return failCode("tab_crashed") }
            catch (e: WebTabIsUsers) { return failCode("users_page") }
            catch (e: UnsupportedOperationException) { return failCode("keep_open_unavailable") }
            ?: return failCode("no_tab_open")
        return ok {
            put("keep", r.keep)
            r.tabId?.let { put("tabId", it) }
            r.host?.let { put("site", it) }
            r.title?.let { put("title", it) }
            if (r.keep) {
                put("hours", r.hours)
                r.replaces?.let { put("replaces", it) }
                put("hint", if (r.replaces != null) Feedback.format("keep_open.replaces", "site" to r.replaces, "hours" to r.hours.toString())
                    else Feedback.format("keep_open.kept", "hours" to r.hours.toString()))
            } else put("hint", Feedback["keep_open.taken_back"])
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("reason")
}
