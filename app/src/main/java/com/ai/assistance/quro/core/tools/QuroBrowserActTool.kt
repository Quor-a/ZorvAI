package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroBrowserBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * AI 操控内置浏览器（接管前台正在显示的 WebView）。
 *
 * 用法（按 ID 操作，不要分多步 navigate 后再读）：
 *  1) action="open"   + url  ：在应用内置浏览器打开 URL（前台显示，与 ai_browser.action=open 等价，但带回到前台焦点）
 *  2) action="snapshot"      ：取页面快照，返回 url/title/ready + 所有可点击/可输入元素的 quro-id 列表 + 简化 DOM（AI 据此决定下一步）
 *  3) action="click"   + id ：按 quro-id 派发 click
 *  4) action="fill"    + id + value ：往 input/textarea 写入文本（input 事件触发）
 *  5) action="eval"    + code：在页面作用域内执行任意 JS（返回字符串化结果）
 *  6) action="wait"    + ms  ：等待 readyState==='complete' 或 ms 毫秒（默认 1500）
 *  7) action="read"    + selector：按 CSS 选择器取 outerHTML
 *  8) action="url"|"title"   ：当前 URL / 标题
 *
 * 推荐组合（不可见内容由 snapshot 暴露给 AI，AI 据此决策）：
 *   snapshot → click(id) → snapshot → ... 直到目标达成。
 */
class BrowserActTool : QuroTool {
    override val name = "browser_act"
    override val description = "AI 操控应用内置浏览器（接管当前显示的 WebView）。" +
        "支持 open / snapshot / click(按稳定 quro-id) / fill(按 id) / eval / wait / read / url / title。" +
        "必须先用 snapshot 拿到可交互元素的 quro-id 列表，再按 id 操作；不要靠 CSS 选择器（页面结构易变）。" +
        "若浏览器未在前台，先 action=open 打开；本工具不打开新窗口，仅操控当前活跃的 WebView。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"open/snapshot/click/fill/eval/wait/read/url/title"},
            "url":{"type":"string","description":"open 时必填，目标 URL"},
            "id":{"type":"string","description":"click/fill 时的稳定 quro-id（snapshot 返回）"},
            "value":{"type":"string","description":"fill 时写入的文本"},
            "code":{"type":"string","description":"eval 时要执行的 JS 表达式（字符串化返回结果）"},
            "selector":{"type":"string","description":"read 时的 CSS 选择器"},
            "ms":{"type":"integer","description":"wait 时等待毫秒，默认 1500，最大 15000"}
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
                    "已在当前浏览器导航到：$url"
                } else {
                    QuroBrowserBridge.open(url)
                    "已打开应用内置浏览器（前台）：$url；操作前请等页面加载完成再用 snapshot。"
                }
            }
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
            "read" -> {
                val sel = jo.optString("selector", "").trim()
                if (sel.isEmpty()) return@runBlocking "read 缺少 selector"
                val html = QuroBrowserController.readBySelector(sel) ?: return@runBlocking "没有活跃浏览器或 read 超时"
                if (html.isBlank()) "selector=$sel 未命中元素" else html.take(8000)
            }
            else -> "未知 action: $action（支持 open/snapshot/click/fill/eval/wait/read/url/title）"
        }
    }
}