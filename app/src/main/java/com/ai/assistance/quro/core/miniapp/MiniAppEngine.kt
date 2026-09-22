package com.ai.assistance.quro.core.miniapp

import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 小程序引擎（移植自 MiniAppFramework 并适配 QuroAI）。
 *
 * 职责（与框架一致）：
 *  1. 解析项目目录下的 app.json（全局配置 + pages 路由表）；
 *  2. 配置 WebView：启用 JS、注入 `native` 桥对象（复用 QuroAI 的 MiniAppBridgeInterface）、
 *     拦截 `app://miniapp.local/...` 请求映射到项目目录内的本地资源；
 *  3. 管理页面路由（pageStack + navigateTo / navigateBack），并在每个页面 <head> 注入桥接运行时
 *     （assets/bridge/bridge.js，提供 Page/Component 运行时 + JSBridge SDK）。
 *
 * 与框架的差异：框架从 assets/miniapp 读取，这里从磁盘项目目录（filesDir/studio/miniapp/<name>）读取，
 * 以支持 AI 通过 miniapp 工具动态写入的小程序工程。
 */
class MiniAppEngine(
    private val webView: WebView,
    private val bridge: MiniAppBridgeInterface,
) {
    private var projectDir: File? = null
    private var config: AppConfig? = null
    private val pageStack = mutableListOf<String>()

    /** 配置 WebView：启用 JS、注入 native 桥、拦截小程序本地资源请求。 */
    fun configure() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webView.addJavascriptInterface(bridge, "native")
        // 引擎路由模块覆盖默认的 stub router，接管页面跳转
        bridge.registerModule(object : MiniAppBridgeModule {
            override val name = "router"
            override fun invoke(method: String, params: JSONObject, cb: (Int, Any?, String?) -> Unit) {
                when (method) {
                    "navigateTo" -> { navigateTo(params.optString("url", "")); cb(0, true, null) }
                    "navigateBack" -> {
                        val ok = navigateBack()
                        cb(if (ok) 0 else -1, ok, if (ok) null else "no history")
                    }
                    else -> cb(-1, null, "method not found: $method")
                }
            }
        })
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? = intercept(url)
        }
    }

    /** 启动框架：解析配置、加载首页。 */
    fun start(projectDir: File) {
        this.projectDir = projectDir
        config = loadAppConfig(projectDir)
        val first = config?.pages?.firstOrNull() ?: "index"
        navigateTo(first)
    }

    /** 跳转到指定页面（pages 表中的路径，如 "pages/about/about" 或带 query "pages/about/about?id=1"）。 */
    fun navigateTo(pagePath: String) {
        val clean = pagePath.substringBefore("?")
            .removePrefix("/").removeSuffix("/").removeSuffix(".html")
        val query = parseQuery(pagePath)
        pageStack.add(clean)
        loadPageHtml(clean, query)
    }

    /** 返回上一页；仅当存在历史页面时接管返回键。 */
    fun navigateBack(): Boolean {
        return if (pageStack.size > 1) {
            pageStack.removeAt(pageStack.lastIndex)
            loadPageHtml(pageStack.last(), emptyMap())
            true
        } else false
    }

    /** 系统返回键回调：交给框架处理，返回 false 表示框架不接管。 */
    fun handleBack(): Boolean = navigateBack()

    private fun loadPageHtml(cleanPath: String, query: Map<String, String>) {
        val dir = projectDir ?: return
        val htmlFile = File(dir, "$cleanPath.html")
        val html = if (htmlFile.exists()) {
            htmlFile.readText(StandardCharsets.UTF_8)
        } else {
            "<html><body style='font-family:sans-serif;padding:24px'><h2>页面不存在</h2><p>$cleanPath.html</p></body></html>"
        }
        val injected = injectBridge(html, query)
        val sub = cleanPath.substringBeforeLast("/", "")
        val base = if (sub.isEmpty()) "app://miniapp.local/" else "app://miniapp.local/$sub/"
        webView.loadDataWithBaseURL(base, injected, "text/html", "utf-8", null)
    }

    /** 在页面 <head> 注入桥接运行时脚本 + 当前页 query。 */
    private fun injectBridge(html: String, query: Map<String, String>): String {
        val js = try {
            webView.context.assets.open("bridge/bridge.js")
                .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) { "" }
        if (js.isEmpty()) return html
        val q = JSONObject()
        query.forEach { (k, v) -> q.put(k, v) }
        val queryScript = "<script>window.__pageQuery = $q;</script>"
        val script = "<script>\n$js\n</script>\n$queryScript"
        val idx = html.indexOf("<head", ignoreCase = true)
        if (idx < 0) return script + "\n" + html
        val end = html.indexOf(">", idx)
        if (end < 0) return html
        return html.substring(0, end + 1) + script + html.substring(end + 1)
    }

    /** 拦截小程序本地资源请求：app://miniapp.local/... -> 项目目录内的文件。 */
    private fun intercept(url: String?): WebResourceResponse? {
        if (url == null) return null
        val rel = if (url.startsWith("app://miniapp.local/")) url.removePrefix("app://miniapp.local/") else return null
        val file = File(projectDir, rel)
        if (!file.exists() || file.isDirectory) return null
        return runCatching {
            WebResourceResponse(mimeType(rel), "utf-8", file.inputStream())
        }.getOrNull()
    }

    private fun mimeType(p: String): String = when {
        p.endsWith(".html", true) -> "text/html"
        p.endsWith(".js", true) -> "application/javascript"
        p.endsWith(".css", true) -> "text/css"
        p.endsWith(".json", true) -> "application/json"
        p.endsWith(".svg", true) -> "image/svg+xml"
        p.endsWith(".png", true) -> "image/png"
        p.endsWith(".jpg", true) || p.endsWith(".jpeg", true) -> "image/jpeg"
        p.endsWith(".webp", true) -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun loadAppConfig(dir: File): AppConfig {
        val f = File(dir, "app.json")
        if (!f.exists()) {
            return AppConfig("", "1.0.0", dir.name, listOf("index"),
                WindowConfig("", "#1A73E8", "#FFFFFF"))
        }
        val obj = JSONObject(f.readText(StandardCharsets.UTF_8))
        val w = obj.optJSONObject("window") ?: JSONObject()
        return AppConfig(
            appId = obj.optString("appId", ""),
            version = obj.optString("version", ""),
            name = obj.optString("name", dir.name),
            pages = jsonArrayToList(obj.optJSONArray("pages")),
            window = WindowConfig(
                navigationBarTitle = w.optString("navigationBarTitle", ""),
                navigationBarColor = w.optString("navigationBarColor", "#1A73E8"),
                backgroundColor = w.optString("backgroundColor", "#FFFFFF"),
            ),
        )
    }

    private fun jsonArrayToList(a: JSONArray?): List<String> {
        val list = mutableListOf<String>()
        if (a == null) return list
        for (i in 0 until a.length()) list.add(a.optString(i))
        return list
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val q = raw.substringAfter("?", "")
        if (q.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        q.split("&").forEach { pair ->
            val kv = pair.split("=", limit = 2)
            val k = kv[0]
            if (k.isNotEmpty()) map[k] = if (kv.size > 1) kv[1] else ""
        }
        return map
    }

    /** 小程序全局配置（对应 app.json）。 */
    data class AppConfig(
        val appId: String,
        val version: String,
        val name: String,
        val pages: List<String>,
        val window: WindowConfig,
    )

    data class WindowConfig(
        val navigationBarTitle: String,
        val navigationBarColor: String,
        val backgroundColor: String,
    )
}
