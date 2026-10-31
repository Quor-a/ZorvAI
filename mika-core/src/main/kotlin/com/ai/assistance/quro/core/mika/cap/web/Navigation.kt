package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.tool.Entry
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.uistate.dialogJson
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.EvidenceLog
import com.ai.assistance.quro.core.mika.tool.HINT_NOT_SETTLED
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.session
import com.ai.assistance.quro.core.mika.uistate.DwellStop
import com.ai.assistance.quro.core.mika.uistate.dwellStopped

/** 打开网页。跳 App 的链接(含应用商店的网页链接)不在内置浏览器里打开。 */
internal object Navigate : WebTool {
    override val verb = Verb.Navigate
    override val label = "打开网页"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("web_navigate",
        Prompts.tool("web_navigate"),
        mapOf(
            "url" to str("Full http:// or https:// address."),
            "newTab" to buildJsonObject { put("type", "boolean"); put("description", "Open in a new tab instead of the current one (default false).") },
        ), listOf("url"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val url = a.str("url", max = 4000)!!.trim()
        val newTab = a.bool("newTab", false)
        WebUrlPolicy.external(url)?.let { return externalLink(it.url, it.packageName) }
        WebUrlPolicy.checkNavigable(url)?.let { throw ArgError("url", it) }
        // 同一个网站上一页还没看够:先补足停留(停留里可以轻轻滚几下眼前这一页);这个网站本任务里的页到了上限就不打开
        try { ctx.dwell?.before(url, ctx.web) } catch (e: DwellStop) { return dwellStopped(e) }
        val r = try { ctx.web!!.navigate(url, newTab) } catch (e: WebTabCrashed) { return failCode("tab_crashed") } catch (e: WebTabHandedOver) { return failCode("handed_over") } catch (e: WebDialogOpen) { return failCode(DIALOG_OPEN) }
        r.externalLink?.let { return externalLink(it.url, it.packageName) }
        // 换了页面:上一张读屏的编号不再能换算坐标
        ctx.session.lastSnapshot = null
        if (r.loaded) ctx.dwell?.seen(r.tab.url)
        // 网址是个文件:打开变成了下载,照实说下载的状态,不当成打开了网页
        r.download?.let { d ->
            return ok {
                put("loaded", false); put("tabId", r.tab.tabId)
                put("download", buildJsonObject {
                    put("file", d.file); put("status", d.status); put("bytes", d.bytes)
                    d.mime?.let { put("mime", it) }; d.error?.let { put("error", it) }
                })
                put("hint", Feedback.format("navigate_became_download", "file" to JsonPrimitive(d.file).toString(), "status" to d.status))
            }
        }
        // 主文档没打开:把浏览器的错误码交给模型,不报成功
        r.netError?.let { e ->
            return ToolExecution(buildJsonObject {
                put("ok", false); put("error", "navigation_failed"); put("netError", e); put("url", r.upgradedFrom ?: url)
                r.upgradedFrom?.let { put("triedHttps", true) }
                put("message", Feedback.format("navigation_failed", "code" to e))
                put("recoverable", true)
            })
        }
        return ok {
            r.dialog?.let { put("dialog", dialogJson(it)) }
            r.upgradedFrom?.let { put("upgradedToHttps", true); put("requestedUrl", it) }
            put("tabId", r.tab.tabId); put("url", r.tab.url); put("title", r.tab.title)
            r.httpStatus?.let { put("httpStatus", it) }
            put("loaded", r.loaded); put("newTab", r.newTab)
            if (r.settled == false) put("settled", false)
            if (r.dialog != null) put("hint", Feedback["page_dialog"])
            else if (!r.loaded) put("hint", Feedback["page_loading"])
            else if (r.settled == false) put("hint", HINT_NOT_SETTLED)
        }
    }

    private fun externalLink(url: String, pkg: String?) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", "external_app_link"); put("url", url)
        pkg?.let { put("package", it) }
        put("message", Feedback["external_app_link"])
        put("recoverable", true)
    })

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("url")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) = listOfNotNull(pageFact(name, r))
}

/** 打开的网页的网址、标题、HTTP 状态也是可引用的事实。 */
internal fun pageFact(name: String, r: ToolExecution): Observation? {
    if (r.content["ok"] != JsonPrimitive(true)) return null
    fun res(k: String) = (r.content[k] as? JsonPrimitive)?.contentOrNull
    return webFact("$name:网址 ${res("url")},网页标题「${res("title").orEmpty()}」" + (res("httpStatus")?.let { ",HTTP 状态 $it" } ?: ""))
}

/** 网页给出的一条事实(网址、标题、标签)。 */
internal fun webFact(text: String) = Observation(ObsKind.Fact, text, source = WEB)

internal object Tabs : WebTool {
    override val verb = Verb.Tabs
    override val label = "切换网页标签"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("web_tabs",
        Prompts.tool("web_tabs"),
        mapOf(
            "action" to str("What to do.", listOf("list", "switch", "close")),
            "tabId" to str("Tab to switch to or close; required for switch and close."),
        ), listOf("action"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val action = a.str("action")!!
        val id = a.str("tabId", max = 200)
        val w = ctx.web!!
        return when (action) {
            "list" -> {
                val all = w.tabs()
                ok {
                    put("tabs", JsonArray(all.map { t ->
                        buildJsonObject { put("tabId", t.tabId); put("title", t.title); put("url", t.url); put("current", t.current) }
                    }))
                }
            }
            "switch" -> {
                if (id == null) throw ArgError("tabId", "required for switch")
                val t = try { w.switchTab(id) } catch (e: WebTabHandedOver) { return failCode("handed_over") }
                    ?: return fail("tab_not_found", Feedback.format("tab_not_found", "id" to id))
                // 换了标签:上一张读屏不是这个页面了
                ctx.session.lastSnapshot = null
                ok { put("tabId", t.tabId); put("title", t.title); put("url", t.url) }
            }
            "close" -> {
                if (id == null) throw ArgError("tabId", "required for close")
                if (!w.closeTab(id)) fail("tab_not_found", Feedback.format("tab_not_found", "id" to id))
                else { ctx.session.lastSnapshot = null; ok { put("closed", id) } }
            }
            else -> throw ArgError("action", "must be list, switch or close")
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("action"), o.argStr("tabId")).joinToString(" ")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val tabs = (r.content["tabs"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(r.content)
        return listOfNotNull(tabs.mapNotNull { t ->
            val title = (t["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            "标签 ${(t["tabId"] as? JsonPrimitive)?.contentOrNull}:网址 ${(t["url"] as? JsonPrimitive)?.contentOrNull},网页标题「$title」"
        }.takeIf { it.isNotEmpty() }?.let { webFact(it.joinToString("\n", "$name:\n")) })
    }
}

internal object SiteMode : WebTool {
    override val verb = Verb.SiteMode
    override val label = "切换网页版本"
    override val risk = Risk.Write
    override val def = ToolDef("web_site_mode",
        Prompts.tool("web_site_mode"),
        mapOf("mode" to str("Version of the site to use.", listOf("desktop", "mobile"))),
        listOf("mode"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val mode = a.str("mode")!!
        if (mode != "desktop" && mode != "mobile") throw ArgError("mode", "must be desktop or mobile")
        val r = try { ctx.web!!.setSiteMode(mode == "mobile") } catch (e: WebTabCrashed) { return failCode("tab_crashed") } catch (e: WebTabHandedOver) { return failCode("handed_over") }
            catch (e: UnsupportedOperationException) { return failCode("site_mode_unavailable") }
            ?: return failCode("no_tab_open")
        // 重新加载了:上一张读屏的编号都不算数
        ctx.session.lastSnapshot = null
        return ok { put("site", r.host); put("mode", if (r.mobile) "mobile" else "desktop"); put("reloaded", r.reloaded) }
    }
}
