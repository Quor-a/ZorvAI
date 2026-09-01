package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroBrowserBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * AI 操控内置浏览器（接管前台正在显示的 WebView）。
 *
 * 推荐组合（不可见内容由 snapshot 暴露给 AI，AI 据此决策）：
 *   open(关键词/网址) → status() 确认 loaded=true → snapshot() 拿 quro-id → click(id)/fill(id) → read(selector) 回读 → 必要时 scroll/wait
 */
class BrowserActTool : QuroTool {
    override val name = "browser_act"
    override val description = "AI 操控应用内置浏览器（接管当前显示的 WebView，页面已加载时可直接看到/操作内容）。" +
        "动作清单：open(网址或关键词,关键词会自动走搜索引擎) / status(返回 attached+loaded+url+title，先调它确认页面就绪) / " +
        "snapshot(取 url/title/ready + 所有可交互元素的 quro-id 列表 + 简化 DOM) / click(按 quro-id 点击) / " +
        "fill(按 quro-id 输入文本) / click_selector(按 CSS 选择器点击) / fill_selector(按 CSS 选择器输入) / " +
        "read(按 CSS 选择器取 outerHTML) / html(整页 HTML) / text(页面正文) / links(所有外链) / " +
        "eval(执行任意 JS 表达式) / wait(等页面就绪, ms) / scroll(dy 像素,to=top|bottom 滚到顶/底) / " +
        "find(页面内查找文本,返回是否命中) / back(后退) / forward(前进) / reload(刷新) / stop(停止加载) / " +
        "screenshot(截当前窗口存 PNG,返回路径) / " +
        "capture(抓包：列出当前页 fetch/xhr 请求，含 请求体 + 响应头/状态码/响应体，可用于 API 数据分析与重放) / " +
        "capture_clear(清空抓包缓冲)。" +
        "必须先用 snapshot 拿可交互元素的 quro-id 列表，再按 id 操作；不要靠 CSS 选择器（页面结构易变）。" +
        "若浏览器未在前台，先 action=open 打开（支持直接搜关键词）；本工具不打开新窗口，仅操控当前活跃的 WebView。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"open/status/snapshot/click/fill/click_selector/fill_selector/read/html/text/links/eval/wait/scroll/find/back/forward/reload/stop/screenshot/capture/capture_clear"},
            "url":{"type":"string","description":"open 时的网址或关键词（关键词自动搜索）"},
            "id":{"type":"string","description":"click/fill 时的稳定 quro-id（snapshot 返回）"},
            "value":{"type":"string","description":"fill 时写入的文本"},
            "selector":{"type":"string","description":"click_selector/fill_selector/read 时的 CSS 选择器"},
            "code":{"type":"string","description":"eval 时要执行的 JS 表达式（字符串化返回结果）"},
            "dy":{"type":"integer","description":"scroll 时向下滚动的像素，默认 300；为负向上"},
            "to":{"type":"string","description":"scroll 时滚到 top(顶) 或 bottom(底)"},
            "text":{"type":"string","description":"find 时查找的文本"},
            "ms":{"type":"integer","description":"wait 时等待毫秒，默认 1500，最大 15000"},
            "limit":{"type":"integer","description":"capture 返回条数上限，默认 200"},
            "filter":{"type":"string","description":"capture 按 url/方法/请求体/响应体 关键字过滤"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String = runBlocking {
        val jo = JSONObject(arguments)
        val action = jo.optString("action", "").trim().lowercase()
        if (action.isEmpty()) return@runBlocking "缺少 action"
        when (action) {
            "open" -> {
                val url = jo.optString("url", "").trim()
                if (url.isEmpty()) return@runBlocking "open 缺少 url"
                if (QuroBrowserController.isAttached()) {
                    QuroBrowserController.navigate(url)
                    "已在当前浏览器打开：${QuroBrowserController.resolveBrowserInput(url)}（关键词已转搜索）；操作前请先 status 确认 loaded，或 snapshot 拿元素。"
                } else {
                    QuroBrowserBridge.open(url)
                    "已打开应用内置浏览器（前台）：$url；操作前请先 status 确认 loaded，或 snapshot 拿元素。"
                }
            }
            "status" -> QuroBrowserController.status()
            "url" -> {
                val u = QuroBrowserController.currentUrl()
                    ?: return@runBlocking "当前没有活跃的内置浏览器（请先 action=open）"
                "current_url=$u"
            }
            "title" -> {
                val t = QuroBrowserController.currentTitle()
                    ?: return@runBlocking "当前没有活跃的内置浏览器"
                "title=$t"
            }
            "snapshot" -> {
                val snap = QuroBrowserController.snapshot()
                    ?: return@runBlocking "当前没有活跃的内置浏览器（请先 action=open）"
                buildString {
                    append("url=").append(snap.url).append('\n')
                    append("title=").append(snap.title).append('\n')
                    append("ready=").append(snap.ready).append('\n')
                    append("element_count=").append(snap.elements.size).append("\n\n")
                    append("elements:\n")
                    snap.elements.forEach { e ->
                        append("  ").append(e["quro_id"]).append("  ")
                            .append(e["tag"]).append('[').append(e["type"]).append("]  ")
                            .append(e["name"]).append("  href=").append(e["href"]).append("  text=").append(e["text"]).append('\n')
                    }
                    if (snap.dom.isNotBlank()) {
                        append("\ndom_first_2k:\n").append(snap.dom.take(2000))
                    }
                }
            }
            "click" -> {
                val id = jo.optString("id", "").trim()
                if (id.isEmpty()) return@runBlocking "click 缺少 id"
                if (QuroBrowserController.clickById(id)) "click $id ok" else "click $id 失败（id 不存在或未在 snapshot 列表中）"
            }
            "fill" -> {
                val id = jo.optString("id", "").trim()
                if (id.isEmpty()) return@runBlocking "fill 缺少 id"
                val v = jo.optString("value", "")
                if (QuroBrowserController.fillById(id, v)) "fill $id ok（已触发 input/change 事件）" else "fill $id 失败"
            }
            "click_selector" -> {
                val sel = jo.optString("selector", "").trim()
                if (sel.isEmpty()) return@runBlocking "click_selector 缺少 selector"
                if (QuroBrowserController.clickBySelector(sel)) "click_selector $sel ok" else "click_selector 未命中或失败"
            }
            "fill_selector" -> {
                val sel = jo.optString("selector", "").trim()
                if (sel.isEmpty()) return@runBlocking "fill_selector 缺少 selector"
                val v = jo.optString("value", "")
                if (QuroBrowserController.fillBySelector(sel, v)) "fill_selector $sel ok" else "fill_selector 未命中或失败"
            }
            "read" -> {
                val sel = jo.optString("selector", "").trim()
                if (sel.isEmpty()) return@runBlocking "read 缺少 selector"
                val html = QuroBrowserController.readBySelector(sel) ?: return@runBlocking "没有活跃浏览器或 read 超时"
                if (html.isBlank()) "selector=$sel 未命中元素" else html.take(8000)
            }
            "html" -> {
                val html = QuroBrowserController.pageHtml() ?: return@runBlocking "没有活跃浏览器或 html 超时"
                if (html.isBlank()) "（页面无 HTML）" else html.take(16000)
            }
            "text" -> {
                val t = QuroBrowserController.pageText() ?: return@runBlocking "没有活跃浏览器或 text 超时"
                if (t.isBlank()) "（页面无正文）" else t.take(8000)
            }
            "links" -> {
                val links = QuroBrowserController.collectLinks() ?: return@runBlocking "没有活跃浏览器或 links 超时"
                if (links.isEmpty()) "（页面无外链）" else links.joinToString("\n") { "- $it" }.take(8000)
            }
            "eval" -> {
                val code = jo.optString("code", "")
                if (code.isEmpty()) return@runBlocking "eval 缺少 code"
                val r = QuroBrowserController.eval(code) ?: return@runBlocking "没有活跃浏览器或 eval 超时"
                "eval_result=$r"
            }
            "wait" -> {
                val ms = jo.optInt("ms", 1500).coerceIn(0, 15000)
                val ok = QuroBrowserController.waitReady(ms.toLong())
                "wait_ready=$ok"
            }
            "scroll" -> {
                val to = jo.optString("to", "").trim().lowercase()
                val dy = jo.optInt("dy", 300)
                val ok = when (to) {
                    "top" -> QuroBrowserController.scrollToTop()
                    "bottom" -> QuroBrowserController.scrollToBottom()
                    else -> QuroBrowserController.scrollBy(dy)
                }
                "scroll(${if (to.isNotEmpty()) to else "dy=$dy"})=$ok"
            }
            "find" -> {
                val t = jo.optString("text", "").trim()
                if (t.isEmpty()) return@runBlocking "find 缺少 text"
                when (val r = QuroBrowserController.find(t)) {
                    true -> "find '$t' 命中（已高亮）"
                    false -> "find '$t' 未命中"
                    null -> "find 执行失败（没有活跃浏览器或页面不支持）"
                }
            }
            "back" -> if (QuroBrowserController.goBack()) "back ok" else "back 失败（没有活跃浏览器或无历史）"
            "forward" -> if (QuroBrowserController.goForward()) "forward ok" else "forward 失败（没有活跃浏览器或无历史）"
            "reload" -> if (QuroBrowserController.reload()) "reload ok" else "reload 失败（没有活跃浏览器）"
            "stop" -> if (QuroBrowserController.stopLoading()) "stop ok" else "stop 失败（没有活跃浏览器）"
            "screenshot" -> {
                val path = QuroBrowserController.screenshot(context)
                    ?: return@runBlocking "截图失败（没有活跃浏览器或界面未布局完成）"
                "screenshot=$path"
            }
            "capture" -> {
                if (!QuroBrowserController.isAttached()) return@runBlocking "当前没有活跃的内置浏览器（请先 action=open）"
                val limit = jo.optInt("limit", 200).coerceIn(1, 1000)
                val filter = jo.optString("filter", "")
                val json = QuroBrowserController.getCaptureSnapshotJson(limit, filter)
                "📡 抓包记录（含 请求体 / 响应头 / 状态码 / 响应体）：\n$json"
            }
            "capture_clear" -> {
                QuroBrowserController.clearCapture()
                "已清空抓包缓冲"
            }
            else -> "未知 action: $action（支持 open/status/snapshot/click/fill/click_selector/fill_selector/read/html/text/links/eval/wait/scroll/find/back/forward/reload/stop/screenshot/capture/capture_clear）"
        }
    }
}
