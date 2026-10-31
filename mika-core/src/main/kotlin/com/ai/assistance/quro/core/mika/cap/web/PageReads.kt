package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.tool.Entry
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Place
import com.ai.assistance.quro.core.mika.observation.Trust
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.CAPTURE_UNTRUSTED
import com.ai.assistance.quro.core.mika.tool.Concurrency
import com.ai.assistance.quro.core.mika.tool.DOWNLOAD_NAMES_UNTRUSTED
import com.ai.assistance.quro.core.mika.tool.EvidenceLog
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.bool
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 网页上读到的内容(整页正文、脚本结果、网络响应、下载列表):来自网站,不可信。[url] 是哪一页(不知道为 null)。 */
internal fun webRead(text: String, url: String?, title: String? = null) =
    Observation(ObsKind.Read, text, source = WEB, place = url?.let(Place::page), title = title, trust = Trust.Untrusted)

/**
 * 读网页:整页正文(读脚本见 ReadScriptTool.kt)(开着的对话框在最前,导航、页脚、链接列表收起成一行)加质量信号(字数、正文密度、判定、声明的时间)。
 * 给了 url 时在临时后台标签里读,不动当前标签。读到的都作为观察交给核查。
 */
internal object ReadPage : WebTool {
    override val verb = Verb.ReadPage
    override val label = "读网页"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val def = ToolDef("web_read_page",
        Prompts.tool("web_read_page"),
        mapOf(
            "url" to str("Read this http(s) address in a temporary background tab instead of the current tab; the current tab is not touched."),
            "maxChars" to int("Characters to return, 1-$READ_PAGE_MAX_CHARS (default $READ_PAGE_DEFAULT_CHARS).", 1, READ_PAGE_MAX_CHARS),
            "offset" to int("Where to start, from nextOffset of the previous call (default 0).", 0, Int.MAX_VALUE),
            "unfold" to bool("Give collapsed blocks (navigation, footer, link lists) in full (default false). offset/nextOffset count in the same mode, so keep it the same while paging."),
        ))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val max = a.int("maxChars", 1, READ_PAGE_MAX_CHARS) ?: READ_PAGE_DEFAULT_CHARS
        val offset = a.int("offset", 0, Int.MAX_VALUE) ?: 0
        val unfold = a.bool("unfold", false)
        val url = a.str("url", max = 4000)?.trim()
        if (url != null) {
            WebUrlPolicy.external(url)?.let { throw ArgError("url", "is an app link, not a web page") }
            WebUrlPolicy.checkNavigable(url)?.let { throw ArgError("url", it) }
        }
        val p = try {
            ctx.web!!.read(PageRead(max, offset, unfold, url))
        } catch (e: WebTabCrashed) { return failCode("tab_crashed") } catch (e: WebTabHandedOver) { return failCode("handed_over") } catch (e: WebDialogOpen) { return failCode(DIALOG_OPEN) } catch (e: UnsupportedOperationException) {
            return fail("background_read_unavailable", Feedback["background_read_unavailable"])
        }
        p.netError?.let { return fail("page_not_loaded", Feedback.format("read_url_not_loaded", "url" to url.orEmpty(), "error" to it)) }
        // 模型看到的链接和图片说明也是可引用的事实,和正文一起记
        val links = p.links.take(50).joinToString("\n") { (t, u) -> "链接「$t」$u" }
        val images = p.images.take(20).filter { it.first.isNotBlank() }.joinToString("\n") { (alt, u) -> "图片「$alt」$u" }
        val read = listOf(p.text.take(max), links, images).filter { it.isNotBlank() }.joinToString("\n")
        val seen = if (read.isNotBlank()) webRead("网页正文(${p.url},从第 $offset 字起)\n$read", p.url, p.title) else null
        val q = p.quality
        return ok {
            put("url", p.url); put("title", p.title)
            if (url != null) put("background", true)
            put("unfold", unfold)
            q?.let {
                put("verdict", it.verdict.wire)
                it.cause?.let { c -> put("verdictCause", c) }
                put("chars", it.chars); put("textDensity", it.textDensity)
                it.published?.let { v -> put("published", v) }
                it.updated?.let { v -> put("updated", v) }
                if (it.dialogs > 0) put("openDialogs", it.dialogs)
                if (it.folded > 0) put("folded", it.folded)
                // 后台标签没挂在窗口上,有的页面在那里不渲染完:读到的少就让它在当前标签里打开看
                if (url != null && (it.verdict == ReadVerdict.THIN || it.verdict == ReadVerdict.IMAGE_TEXT)) put("hint", Feedback["read_url_thin"])
            }
            put("text", Untrusted.wrap(p.text.take(max)))
            put("links", JsonArray(p.links.take(50).map { (t, u) -> buildJsonObject { put("text", t); put("url", u) } }))
            put("images", JsonArray(p.images.take(20).map { (alt, u) -> buildJsonObject { put("alt", alt); put("url", u) } }))
            put("truncated", p.truncated || p.text.length > max)
            put("nextOffset", p.nextOffset)
        }.observing(seen)
    }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) = listOfNotNull(pageFact(name, r))
}

internal object Downloads : WebTool {
    override val verb = Verb.Downloads
    override val label = "查看下载"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("web_downloads",
        Prompts.tool("web_downloads"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val all = try { ctx.web!!.downloads() } catch (e: UnsupportedOperationException) {
            return failCode("downloads_unavailable")
        }
        return ok {
            put("downloads", JsonArray(all.map { d ->
                buildJsonObject {
                    put("file", d.file); put("bytes", d.bytes); put("mime", d.mime); put("status", d.status)
                    d.error?.let { put("error", it) }
                }
            }))
            if (all.isNotEmpty()) put("note", DOWNLOAD_NAMES_UNTRUSTED)
        }
    }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((r.content["downloads"] as? JsonArray)?.let { list ->
            webRead(list.joinToString("\n", "下载列表(文件名来自网页):\n") { d ->
                val dobj = d as? JsonObject
                listOf("file", "status", "bytes", "error").mapNotNull { k -> (dobj?.get(k) as? JsonPrimitive)?.contentOrNull?.let { "$k=$it" } }.joinToString(" ")
            }, null)
        })
    }
}

/** 网络响应:列表不带正文;给 id 时取正文。网址和正文先经 [Redactor.network] 脱敏再出去(运行记录在记录处还会再脱敏一次)。 */
internal object Capture : WebTool {
    override val verb = Verb.Capture
    override val label = "查看网页收到的数据"
    override val risk = Risk.Read
    override val def = ToolDef("web_capture",
        Prompts.tool("web_capture", "list_max" to CAPTURE_LIST_MAX),
        mapOf(
            "id" to str("id from a previous list, to get that response with its body."),
            "urlContains" to str("Only responses whose url contains this text."),
            "method" to str("Only this HTTP method, e.g. GET or POST."),
            "sinceLastAction" to buildJsonObject { put("type", "boolean"); put("description", "Only requests sent since the last tap/type/navigate on the page (default false).") },
            "maxChars" to int("Body characters to return, 1-$CAPTURE_BODY_MAX_CHARS (default $CAPTURE_BODY_DEFAULT_CHARS).", 1, CAPTURE_BODY_MAX_CHARS),
        ))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val id = a.str("id", max = 200)
        val urlContains = a.str("urlContains", max = 500)
        val method = a.str("method", max = 20)
        val since = a.bool("sinceLastAction", false)
        val max = a.int("maxChars", 1, CAPTURE_BODY_MAX_CHARS) ?: CAPTURE_BODY_DEFAULT_CHARS
        val w = ctx.web!!
        fun JsonObjectBuilder.fields(c: CapturedResponse) {
            put("id", c.id); put("url", Redactor.network(url = c.url).url); put("method", c.method); put("type", c.type); put("state", c.state.wire)
            c.status?.let { put("status", it) }
            c.mime?.let { put("mime", it) }
            c.size?.let { put("size", it) }
            if (c.fromCache) put("fromCache", true)
            c.error?.let { put("error", it) }
        }
        try {
            if (id != null) {
                val c = w.capture(id) ?: return fail("capture_not_found", Feedback.format("capture_not_found", "id" to id))
                // 一次脱敏,交给模型、核查证据、运行记录的都是这一份
                val red = Redactor.network(url = c.url, body = c.body)
                val body = red.body
                val seen = body?.let { webRead("网络响应(${red.url},HTTP ${c.status})\n${it.take(max)}", red.url) }
                return ok {
                    fields(c)
                    if (body != null) {
                        val cut = body.length > max
                        put("body", Untrusted.wrap(if (cut) body.take(max) + "\n…[truncated: ${body.length - max} more characters]" else body))
                        put("truncated", cut || c.bodyTruncated)
                        // 网站自己的接口在说这次访问被风控拦下了:照实告诉模型,别接着翻
                        WebBarrier.apiRisk(body)?.let { put("riskControl", it); put("hint", Feedback["capture.risk_control"]) }
                    } else {
                        put("bodyNote", c.bodyNote ?: "unavailable")
                    }
                }.observing(seen)
            }
            val all = w.captures(urlContains, method, since)
            val shown = all.takeLast(CAPTURE_LIST_MAX)
            return ok {
                put("responses", JsonArray(shown.map { c -> buildJsonObject { fields(c) } }))
                put("total", all.size)
                if (shown.isNotEmpty()) put("note", CAPTURE_UNTRUSTED)
            }
        } catch (e: DevtoolsUnavailable) {
            return failCode("devtools_unavailable")
        } catch (e: WebTabCrashed) {
            return failCode("tab_crashed")
        } catch (e: WebTabHandedOver) {
            return failCode("handed_over")
        } catch (e: WebDialogOpen) {
            return failCode(DIALOG_OPEN)
        }
    }
}

internal object WebWaitFor : WebTool {
    override val verb = Verb.WaitFor
    override val label = "等网页变化"
    override val yieldsToSteer = true
    override val risk = Risk.Read
    override val def = ToolDef("web_wait_for",
        Prompts.tool("web_wait_for", "max_seconds" to WAIT_MAX_SECONDS),
        mapOf(
            "condition" to str("What to wait for.", listOf("text_present", "text_absent", "url_contains", "network_idle")),
            "value" to str("The text or url fragment; required except for network_idle."),
            "seconds" to buildJsonObject { put("type", "number"); put("description", "0.5-$WAIT_MAX_SECONDS seconds (default 5)."); put("minimum", 0.5); put("maximum", WAIT_MAX_SECONDS) },
        ), listOf("condition"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val cond = a.str("condition")!!
        val kind = WaitKind.of(cond) ?: throw ArgError("condition", "must be one of ${WaitKind.entries.joinToString("|") { it.wire }}")
        val value = a.str("value", max = 500)
        if (kind != WaitKind.NETWORK_IDLE && value == null) throw ArgError("value", "required for $cond")
        val seconds = a.number("seconds") ?: 5.0
        if (seconds < 0.5 || seconds > WAIT_MAX_SECONDS) throw ArgError("seconds", "must be between 0.5 and $WAIT_MAX_SECONDS")
        val r = try {
            ctx.web!!.waitFor(kind, value, (seconds * 1000).toLong())
        } catch (e: DevtoolsUnavailable) {
            return failCode("devtools_unavailable")
        } catch (e: WebTabCrashed) {
            return failCode("tab_crashed")
        } catch (e: WebTabHandedOver) {
            return failCode("handed_over")
        } catch (e: WebDialogOpen) {
            return failCode(DIALOG_OPEN)
        }
        return ok {
            put("condition", cond); put("met", r.met); put("waitedMs", r.waitedMs)
            r.url?.let { put("url", Redactor.network(url = it).url) }
            if (!r.met) put("hint", Feedback.format("wait_not_met", "seconds" to seconds))
        }
    }
}
