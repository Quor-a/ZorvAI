package com.ai.assistance.quro.browser

import android.content.Context
import android.os.Environment
import com.ai.assistance.quro.browser.consolekit.AciConsoleContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 受控浏览器「控制台」后端业务状态（SDUI 范式）。
 *
 * 这是把主应用 LAN 控制台的「后端驱动 UI」范式移植到受控浏览器（后端）的实现：
 * - buildUiSnapshot() 生成 UI 描述 JSON（组件化，前端免发版渲染）；
 * - applyAction() 处理前端回传的 action，真正驱动浏览器（不再由 UI 直连 BrowserCore）。
 *
 * 经 ACI 能力 console_ui / console_action 暴露给通用前端（ZorvAI 主程序 / 手动控制台）。
 * 手动控制台（BrowserActivity）与 AI 走的是同一个 ConsoleBackend —— 一份真相，统一通道。
 *
 * 组件词汇（heading/text/card/button/divider/input/listitem/spacer）与主应用一致，
 * 以便任意通用前端/受控 App 都能直接渲染。
 *
 * 线程约束：buildUiSnapshot / applyAction 必须在非 UI 线程调用（内部会同步等待 WebView 主线程）。
 */
object ConsoleBackend : AciConsoleContract {

    @Volatile private var appCtx: Context? = null
    @Volatile private var findText: String = ""
    @Volatile private var captureOn: Boolean = false
    @Volatile private var lastResult: String = ""

    fun attachContext(ctx: Context) { appCtx = ctx.applicationContext }

    // ── AciConsoleContract 适配（服务仍调用 buildUiSnapshot/applyAction，这里做薄委派）──
    override fun getSnapshot(): JSONObject = buildUiSnapshot()
    override fun sendAction(action: String, payload: Map<String, String>): JSONObject {
        val p = JSONObject().apply { payload.forEach { (k, v) -> put(k, v) } }
        return applyAction(action, p)
    }

    /** 生成当前 UI 快照（后端下发给前端渲染的描述）。非 UI 线程调用。 */
    fun buildUiSnapshot(): JSONObject {
        val url = BrowserCore.getUrl()
        val title = BrowserCore.getTitle()
        val canBack = BrowserCore.canGoBack()
        val canFwd = BrowserCore.canGoForward()
        val ready = url != null

        val components = JSONArray()
        components.put(JSONObject().put("type", "heading").put("text", "ZorvAI 浏览器控制台"))
        components.put(
            JSONObject().put("type", "text")
                .put("text", if (ready) "当前页面: $url" else "（无活动页面，请先打开网址）")
        )
        components.put(JSONObject().put("type", "text").put("text", "标题: ${title ?: "—"}"))
        components.put(
            JSONObject().put("type", "text")
                .put("text", "可后退 $canBack · 可前进 $canFwd · 抓包 ${if (captureOn) "开" else "关"}")
        )

        // 页内查找卡片
        components.put(
            JSONObject().put("type", "card")
                .put("title", "页内查找")
                .put("body", "当前关键词: ${if (findText.isNotEmpty()) findText else "无"}")
        )
        components.put(
            JSONObject().put("type", "input")
                .put("key", "find").put("label", "页内查找").put("placeholder", "关键词")
                .put("value", findText).put("action", "find")
        )
        components.put(JSONObject().put("type", "button").put("action", "find_next").put("label", "下一个匹配"))
        components.put(JSONObject().put("type", "button").put("action", "find_clear").put("label", "清除查找"))

        // 打开网址
        components.put(
            JSONObject().put("type", "input")
                .put("key", "url").put("label", "打开网址").put("placeholder", "https://...")
                .put("value", "").put("action", "open")
        )
        // 运行 JS
        components.put(
            JSONObject().put("type", "input")
                .put("key", "code").put("label", "运行 JS").put("placeholder", "JavaScript 代码")
                .put("value", "").put("action", "js")
        )

        components.put(JSONObject().put("type", "divider"))
        components.put(JSONObject().put("type", "button").put("action", "read").put("label", "读 HTML"))
        components.put(JSONObject().put("type", "button").put("action", "crawl").put("label", "爬取正文"))
        components.put(JSONObject().put("type", "button").put("action", "nav_back").put("label", "后退"))
        components.put(JSONObject().put("type", "button").put("action", "nav_forward").put("label", "前进"))
        components.put(JSONObject().put("type", "button").put("action", "nav_reload").put("label", "刷新"))
        components.put(JSONObject().put("type", "button").put("action", "screenshot").put("label", "截图"))
        components.put(JSONObject().put("type", "button").put("action", "capture_toggle").put("label", "抓包:开/关"))
        components.put(JSONObject().put("type", "button").put("action", "capture_clear").put("label", "清抓包"))

        components.put(JSONObject().put("type", "divider"))
        components.put(
            JSONObject().put("type", "text")
                .put("text", if (lastResult.isNotEmpty()) "上次结果: $lastResult" else "（暂无操作结果）")
        )
        components.put(JSONObject().put("type", "listitem").put("text", "受控浏览器: com.ai.assistance.quro.browser"))
        components.put(JSONObject().put("type", "listitem").put("text", "经 ACI console_ui / console_action 驱动（手动/AI 同后端）"))

        return JSONObject()
            .put("title", "ZorvAI 浏览器控制台")
            .put("subtitle", "后端驱动渲染 · 前端免发版（ACI）")
            .put("updatedAt", System.currentTimeMillis())
            .put("components", components)
    }

    /** 处理前端回传的 action，真正驱动浏览器。非 UI 线程调用。 */
    fun applyAction(action: String, payload: JSONObject?): JSONObject {
        val p = payload ?: JSONObject()
        val msg = when (action) {
            "open" -> {
                val raw = p.optString("url").trim()
                if (raw.isEmpty()) "请输入网址" else {
                    BrowserCore.loadUrl(normalizeUrl(raw))
                    "打开 $raw"
                }
            }
            "read" -> {
                val html = runCatching { BrowserCore.readHtml() }.getOrDefault("")
                lastResult = "读HTML ${html.length} 字"
                "读HTML ${html.length} 字"
            }
            "crawl" -> {
                val text = runCatching { BrowserCore.crawlPage() }.getOrDefault("")
                lastResult = "爬取 ${text.length} 字"
                "爬取 ${text.length} 字"
            }
            "js" -> {
                val code = p.optString("code").ifEmpty { p.optString("js") }.trim()
                if (code.isEmpty()) "请输入 JS 代码" else {
                    val r = runCatching { BrowserCore.evalScript(code) }.getOrDefault("")
                    lastResult = "JS ${r.length} 字: ${r.take(200)}"
                    "JS 返回 ${r.length} 字"
                }
            }
            "find" -> {
                val t = p.optString("find").trim()
                if (t.isEmpty()) "请输入关键词" else {
                    findText = t
                    val n = runCatching { BrowserCore.findInPage(t) }.getOrDefault(0)
                    "查找「$t」命中 $n 处"
                }
            }
            "find_next" -> { BrowserCore.findNext(true); "下一个匹配" }
            "find_clear" -> { BrowserCore.clearFind(); findText = ""; "已清除查找" }
            "nav_back" -> { BrowserCore.navBack(); "后退" }
            "nav_forward" -> { BrowserCore.navForward(); "前进" }
            "nav_reload" -> { BrowserCore.navReload(); "刷新" }
            "screenshot" -> {
                val path = screenshotPath()
                val got = runCatching { BrowserCore.screenshot(path) }.getOrDefault("")
                if (got.isNotEmpty()) { lastResult = "截图 $got"; "截图已保存" } else "截图失败（WebView 尺寸为 0 或未渲染）"
            }
            "capture_toggle" -> {
                captureOn = !captureOn
                BrowserCore.setCaptureEnabled(captureOn)
                "抓包已${if (captureOn) "开启" else "关闭"}"
            }
            "capture_clear" -> { BrowserCore.clearCapture(); "抓包记录已清空" }
            else -> "未知 action: $action"
        }
        return JSONObject().put("ok", true).put("action", action).put("message", msg)
    }

    private fun normalizeUrl(raw: String): String {
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) return raw
        return "https://$raw"
    }

    private fun screenshotPath(): String {
        val base = appCtx?.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.absolutePath
            ?: appCtx?.cacheDir?.absolutePath ?: "/sdcard"
        val dir = "$base/QuroAI_screenshots"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$dir/screenshot_$stamp.png"
    }
}
