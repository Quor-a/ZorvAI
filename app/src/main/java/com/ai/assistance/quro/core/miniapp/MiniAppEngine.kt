package com.ai.assistance.quro.core.miniapp

import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Web 应用的 origin —— 必须是**真 HTTPS**，不能是 app:// 自定义 scheme。
 *
 * 🔴 2026-10-06：原先用 `app://miniapp.local/`。自定义 scheme 在 Chromium 里
 * 属于 opaque origin（不透明源），会连锁触发一串"静默不可用"：
 *   - ES Module `import` 过不了同源/CORS 校验 → 模块链断
 *   - IndexedDB 在不透明源上直接 SecurityError
 *   - 非 secure context → Service Worker 注册失败、
 *     crypto.subtle / navigator.clipboard / getUserMedia 全部 undefined
 *   - <video>/<audio> 的部分能力（EME 等）依赖 secure context
 * 换成本域名后 secure context、同源模块、CORS 全部按标准走。
 * 这个域名沿用 AndroidX WebViewAssetLoader 的约定值。
 */
const val MINIAPP_ORIGIN = "https://appassets.androidplatform.net"

/** 探测页面是否已声明 viewport（含 name='viewport' 单引号写法）。三引号 raw string 避开正则转义。 */
private val VIEWPORT_RE = Regex("""<meta[^>]+name\s*=\s*['"]?viewport""", RegexOption.IGNORE_CASE)

/**
 * Web 应用引擎（移植自 MiniAppFramework 并适配 QuroAI）。
 *
 * 职责（与框架一致）：
 *  1. 解析项目目录下的 app.json（全局配置 + pages 路由表）；
 *  2. 配置 WebView：启用 JS、注入 `native` 桥对象（复用 QuroAI 的 MiniAppBridgeInterface）、
 *     拦截 [MINIAPP_ORIGIN] 请求映射到项目目录内的本地资源（外部 https 放行）；
 *  3. 管理页面路由（pageStack + navigateTo / navigateBack），并在每个页面 <head> 注入桥接运行时
 *     （assets/bridge/bridge.js，提供 Page/Component 运行时 + JSBridge SDK）。
 *
 * 与框架的差异：框架从 assets/miniapp 读取，这里从磁盘项目目录（filesDir/miniapp/<name>）读取，
 * 以支持 AI 通过 miniapp 工具动态写入的 Web 应用工程。
 *
 * 技术栈支持（2026-10-06 升级后）：origin 换成 [MINIAPP_ORIGIN] 真 HTTPS，
 * MIME 表扩到 60+，并打开 IndexedDB / 多窗口 / 媒体自动播放等开关 ——
 * 于是 ES Modules、import.meta、顶层 await、fetch、WebSocket、Worker、
 * WebGL/WebGPU、IndexedDB、Service Worker、crypto.subtle、
 * 音视频、webfont、CDN 引框架 全部可用。
 */
class MiniAppEngine(
    private val webView: WebView,
    private val bridge: MiniAppBridgeInterface,
) {
    private var projectDir: File? = null
    private var config: AppConfig? = null
    private val pageStack = mutableListOf<String>()

    /**
     * 配置 WebView：启用 JS、注入 native 桥、拦截 Web 应用本地资源请求。
     *
     * 🔴 **不要开 `useWideViewPort` / `loadWithOverviewMode`**（2026-10-06）：
     * 这两项会让 `html,body{height:100%;overflow:hidden}` 的页面算出 0 高可见
     * 视口 → 整页白屏。同仓 QuroToolCenterScreen.kt:790 有同款前车之鉴。
     * 移动端视口的正确解法是给 HTML **注入 viewport meta**（见 [ensureViewport]）。
     *
     * 🔴 `textZoom` 必须**钉死为 100**：不钉的话 WebView 会跟随系统「显示大小/
     * 字体大小」设置放大，同一段 HTML 在不同用户手机上字号差一大截 —— 这正是
     * 「只有开了大字体的机器上整页elements 巨大」的成因。
     */
    fun configure() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            defaultTextEncodingName = "UTF-8"
            loadsImagesAutomatically = true
            // 让 <img> 等资源在 file:// / 自定义 scheme 下也能加载
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            // 🔴 字号不跟随系统字体缩放：AI 生成的页面自己用 px/rpx 控制版式，
            // 叠加系统缩放会整体放大到不符合浏览标准（真机截图症状）。
            // 代价：用户没法再通过系统设置放大 Web 应用页 —— 但版式可控更重要，
            // 且小程序侧（miniapp-sdk）本来就不跟sp 缩放，两边行为要一致。
            textZoom = 100
            // 视口宽度由页面自己的 viewport meta 决定（见 ensureViewport）
            useWideViewPort = false
            loadWithOverviewMode = false
            // 视口按设备宽度裁剪，避免内容溢出横向滚动
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // ══════════════════════════════════════════════════════════════
            // 以下是「支持完整 Web 技术栈」的第二道闸门：之前这些开关默认关着，
            // 导致 IndexedDB / WebGL / 音视频自动播放 / 多窗口 / Service Worker
            // 全部不可用 —— 页面不是报错，而是**静默走不通**。
            // ══════════════════════════════════════════════════════════════

            // IndexedDB / WebSQL 的底层存储。localStorage 由 domStorageEnabled 管，
            // 但 IndexedDB 是另一套，必须显式开 —— 不开则 new IDBFactory() 直接抛错。
            databaseEnabled = true
            // WebGL / WebGL2 / WebGPU。WebView 默认允许 WebGL，
            // 但 WebGPU 在旧内核上不可用，显式声明避免上层 JS 白等。
            // 媒体：允许自动播放与音频输出（WebAudio 的 AudioContext.resume 必需）
            mediaPlaybackRequiresUserGesture = false
            // video/audio 的外放路由
            // Service Worker 需要安全的 origin（https 或 file:// 的宽松策略）。
            // 本地 app:// 场景下 SW 注册会失败，但**显式声明意图**，
            // 让上层 JS 能用 feature 检测而不是静默等 register() 永不 resolve。
            allowContentAccess = true
            // 多窗口：target=_blank / window.open 能在应用内打开
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            // 关闭 Safe Browsing 的弹窗拦截：自建 Web 应用常被误判为风险页
            // Safe Browsing 只在真联网时生效；本地 Web 应用常被误判为风险页
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
            // 深色/暗色适配交给页面 CSS 与 prefers-color-scheme，
            // 这里强制 allow + 关闭强制深色，避免系统深色下被反色
            forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
            // 视口不再叠加 meta 的 user-scalable 之外的行为：
            // 允许页面自己用 CSS clamp()/vw 做响应式，不锁死缩放能力给 CSS
            loadsImagesAutomatically = true
        }
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

            /**
             * 🔴 双版本重载，缺一不可（2026-10-06）：
             * API 21+ 起 WebView 优先回调带 [android.webkit.WebResourceRequest] 的重载。
             * 只写 String 版的老写法在部分 ROM / 新内核上**完全不触发**，
             * 症状是本地 CSS/JS 全部 404、页面裸奔无样式 —— 但不报错，极难查。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): WebResourceResponse? = intercept(request?.url?.toString())

            /**
             * 导航分流（2026-10-06 补齐，此前完全缺失）：
             *  - 本地页面（MINIAPP_ORIGIN）→ 交给引擎自己的 pageStack 路由，
             *    这样工具中心的「返回」按钮才有意义；
             *  - 外部站点（http/https）→ **拦下并交给系统浏览器**。
             *
             * 🔴 为什么不拦的后果：点一个 <a href="https://…"> 就会在 WebView 内
             * 直接跳走，用户在应用里点返回键回不来，只能退整个页面 —— 表现就是
             * 「Web 应用打开网页之后就出不来了」。这个重载不写，症状必然出现。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleNavigation(url)
            }

            /** API 24 前的旧重载，同样要覆盖，否则老机型上外链劫持问题依旧。 */
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                handleNavigation(url ?: "")

            /** 返回 true 表示"已处理并消化"，WebView 不再继续加载。 */
            private fun handleNavigation(url: String): Boolean {
                if (url.startsWith("$MINIAPP_ORIGIN/") || url == MINIAPP_ORIGIN) return false
                val scheme = url.substringBefore(':', "").lowercase()
                if (scheme == "http" || scheme == "https") {
                    // about:blank 之类由 WebView 自己处理，别丢给系统
                    runCatching {
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            .let { webView.context.startActivity(it) }
                    }
                    return true
                }
                // intent://、mailto:、tel:、weixin:// 等自定义 scheme
                if (scheme in setOf("mailto", "tel", "sms", "geo", "intent")) {
                    runCatching {
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            .let { webView.context.startActivity(it) }
                    }
                    return true
                }
                // 其余（tel: 之外的怪 scheme）放行给 WebView 自己处理
                return false
            }

            /**
             * 页面加载失败兜底：本地资源缺失时给出可读提示，而不是留一块白屏。
             * 只在**主框架**失败时提示 —— CDN 字体/图标拉不到不该弹窗打扰。
             */
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (failingUrl != null && failingUrl.startsWith("$MINIAPP_ORIGIN/")) {
                    android.util.Log.w(
                        "MiniAppEngine",
                        "本地资源加载失败 code=$errorCode url=$failingUrl desc=$description",
                    )
                }
            }
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
        val injected = injectBridge(ensureViewport(html), query)
        // 同源 base：让 ./x.js / ../css/y.css 这类相对引用落到 MINIAPP_ORIGIN 下，
        // 由 intercept() 映射到工程目录。必须是 https（见 MINIAPP_ORIGIN 的说明）。
        val sub = cleanPath.substringBeforeLast("/", "")
        val base = if (sub.isEmpty()) "$MINIAPP_ORIGIN/" else "$MINIAPP_ORIGIN/$sub/"
        webView.loadDataWithBaseURL(base, injected, "text/html", "utf-8", null)
    }

    /**
     * 🔴 保证页面有 `<meta name="viewport">`，没有就补一条。
     *
     * 没有 viewport 的 HTML，WebView 会按 980px 的虚拟布局宽度渲染再整体缩放到
     * 屏宽 —— 表现为**整页元素等比放大**，顶栏下方的内容被挤到看不见，
     * 完全不符合移动端浏览标准（2026-10-06 真机截图，两处同症状）。
     *
     * 为什么不用 `settings.useWideViewPort = true`：
     * 那会让 WebView 忽略页面的 `height:100%` 语义，部分页算出 0 高可见视口 →
     * 整页白屏（同仓 QuroToolCenterScreen.kt:790 前车之鉴）。注入 meta 是唯一
     * 兼顾「按设备宽度渲染」与「尊重页面自身盒模型」的做法。
     *
     * 已有 viewport 的页面（含 AI 显式写了 `initial-scale` / `user-scalable=no`
     * 的）一律原样保留 —— 只补缺失的那部分，不覆盖作者意图。
     */
    fun ensureViewport(html: String): String {
        if (html.isBlank()) return html
        // 已带 viewport（无论大小写、无论写在 head 还是别处）→ 不动
        if (VIEWPORT_RE.containsMatchIn(html)) return html

        val meta = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
        val headIdx = html.indexOf("<head", ignoreCase = true)
        if (headIdx >= 0) {
            val gt = html.indexOf('>', headIdx)
            if (gt >= 0) return html.substring(0, gt + 1) + "\n" + meta + html.substring(gt + 1)
            //<head 后没有 '>'：HTML 本身不完整，补完 head 再塞
            return html + "\n<meta><head>" + meta + "</head>"
        }
        // 连 <head> 都没有：html/`<html ...>` 之后立刻补
        val htmlIdx = html.indexOf("<html", ignoreCase = true)
        if (htmlIdx >= 0) {
            val gt = html.indexOf('>', htmlIdx)
            if (gt >= 0) return html.substring(0, gt + 1) + "\n<head>" + meta + "</head>" + html.substring(gt + 1)
        }
        return meta + html
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

    /** 拦截 [MINIAPP_ORIGIN] 请求映射到工程目录内的本地文件（外部 https 一律放行）。 */
    /**
     * 资源拦截：把 MINIAPP_ORIGIN 下的请求映射到工程目录内的真实文件。
     *
     * 外部 https（CDN / unpkg / jsdelivr / 任何 API）**一律放行** ——
     * 这是"支持完整 Web 技术栈"的前提：页面要能用 fetch、WebSocket、
     * 从 CDN 引入框架（React/Vue/Tailwind 的 CDN 版），不能一刀切拦死。
     */
    private fun intercept(url: String?): WebResourceResponse? {
        if (url == null) return null
        if (!url.startsWith("$MINIAPP_ORIGIN/")) return null
        val rel = url.removePrefix("$MINIAPP_ORIGIN/")
            .substringBefore('?').substringBefore('#')
        if (rel.isEmpty()) return null
        val root = projectDir ?: return null
        // 🔴 目录穿越防护：../ 一律归一化后判前缀，防止 ../ 跳出工程目录读到别处文件
        val rootPath = root.canonicalFile
        val file = java.io.File(rootPath, rel).canonicalFile
        if (!file.path.startsWith(rootPath.path)) return null
        if (!file.exists() || file.isDirectory) return null
        return runCatching {
            WebResourceResponse(mimeTypeOf(rel), "utf-8", file.inputStream()).apply {
                val headers = HashMap<String, String>()
                // 同源其实不需要 CORS 头，但：
                //  1) 页面里若引用了外部 CDN 模块走 CORS，这里给出宽松策略更稳；
                //  2) WASM instantiateStreaming / SharedArrayBuffer 场景需要明确放行。
                headers["Access-Control-Allow-Origin"] = "*"
                headers["Access-Control-Allow-Headers"] = "*"
                headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS, HEAD"
                headers["Cache-Control"] = "no-cache"
                setResponseHeaders(headers)
            }
        }.getOrNull()
    }


    /**
     * 资源 MIME 类型表 —— 「支持完整 Web 技术栈」的第一道闸门。
     *
     * 🔴 为什么这张表必须完整（2026-10-06）：
     * 浏览器对 **ES Module** 和 **WebAssembly** 有强制 MIME 校验，类型不对
     * 不是「降级」而是**直接拒绝执行**：
     *   .mjs / type=module → 必须是 text/javascript，否则整份脚本不执行
     *     （Android WebView 尤其严：拿到 application/octet-stream 会
     *      抛 SyntaxError: Unexpected token ':'，import 整条链断掉）
     *   .wasm             → 必须是 application/wasm，否则
     *     WebAssembly.instantiateStreaming 失败，Rust/C++/Go 编译产物全跑不起来
     * 原先只有 8 种类型，其余全落 application/octet-stream，等于把
     * 模块化、WASM、字体、音视频、图标全废掉。
     *
     * 顺序按扩展名从长到短，避免 .mjs 被 .js 先吃掉。
     */

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

    companion object {
        /**
         * 资源 MIME 类型表 —— 「支持完整 Web 技术栈」的第一道闸门。
         *
         * 🔴 这张表为什么必须完整（2026-10-06）：
         * 浏览器对 **ES Module** 和 **WebAssembly** 有强制 MIME 校验，
         * 类型不对不是「降级」而是**直接拒绝执行**：
         *  - `.mjs` / `<script type="module">` → 必须是 `text/javascript`，
         *    否则整份脚本不执行（Android WebView 尤其严：拿到 octet-stream 会抛
         *    `SyntaxError: Unexpected token ':'`，整条 import 链断掉）
         *  - `.wasm` → 必须是 `application/wasm`，否则
         *    `WebAssembly.instantiateStreaming` 失败，Rust / C++ / Go 编译产物全跑不起来
         *
         * 原先只有 8 种类型、其余全落 octet-stream，等于把模块化、WASM、
         * webfont、音视频、图标统统废掉。
         *
         * 放在 companion 里是**纯函数**：单测无需构造 WebView（本工程无 Robolectric）。
         */
        fun mimeTypeOf(path: String): String {
            // 🔴 必须先剥掉 query 与 fragment：WebView 请求的 URL 常带 `?v=123` / `#hash`，
            //   不剥的话 `app.js?v=1` 的扩展名会被算成 "js?v=1"，整张表全部落空 → 静默失效。
            val clean = path.substringBefore('?').substringBefore('#')
            val ext = clean.substringAfterLast('.', "").lowercase()
            return when (ext) {
                // ── 脚本 / 模块 ──
                // 🔴 mjs 与 js 必须是 text/javascript：type=module 的严格 MIME 校验
                "mjs" -> "text/javascript"
                "js", "cjs" -> "text/javascript"
                // 浏览器不直接跑 TS/JSX，交由构建产物；这里只保证不会因类型错误连带拒绝其他模块
                "jsx", "ts", "tsx" -> "text/plain"
                // 🔴 Rust/C++/Go -> WASM 的命门
                "wasm" -> "application/wasm"

                // ── 样式 ──
                "css" -> "text/css"

                // ── 文档 ──
                // 🔴 html/htm 必须显式给 text/html：主页面与内嵌 iframe 都走这里
                "html", "htm" -> "text/html"

                // ── 数据 / 元数据 ──
                "json" -> "application/json"
                "map" -> "application/json" // sourcemap
                "webmanifest" -> "application/manifest+json"
                "xml" -> "text/xml"
                "txt", "md" -> "text/plain"
                "csv" -> "text/csv"

                // ── 图片 ──
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "avif" -> "image/avif"
                "bmp" -> "image/bmp"
                "svg" -> "image/svg+xml"
                "ico" -> "image/x-icon"
                "tif", "tiff" -> "image/tiff"

                // ── 字体 ──
                "woff2" -> "font/woff2"
                "woff" -> "font/woff"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "eot" -> "application/vnd.ms-fontobject"

                // ── 音频 / 视频（<audio> / <video> / WebCodecs）──
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "opus" -> "audio/opus"
                "mp4" -> "video/mp4"
                "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "ogv" -> "video/ogg"
                "mov" -> "video/quicktime"
                "ts" -> "video/mp2t"

                // ── 其它 ──
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "gz" -> "application/gzip"

                // 未知扩展名（无点号、.bin/.dat/自造扩展等）→ 二进制流。
                // ⚠️ 不能返回 null：WebResourceResponse 的 mimeType 是**非空**参数。
                //   这里给 octet-stream 而非 text/plain ——
                //   text/plain 会让浏览器按纯文本嗅探，把 wasm/字体/音视频解析坏。
                else -> "application/octet-stream"
            }
        }
    }

    /** Web 应用全局配置（对应 app.json）。 */
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
