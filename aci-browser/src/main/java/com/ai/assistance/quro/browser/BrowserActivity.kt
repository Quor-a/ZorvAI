package com.ai.assistance.quro.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * 可见浏览器界面（v8 · 折叠工具栏 + AI 眼睛面板 + 发给 AI 管道）。
 *
 * 相对 v6 的改动：
 * 1. 顶部工具栏可经 ≡ 折叠/展开（收缩后浮出右上角展开钮）。
 * 2. 地址栏 + Go / 刷新 自主管控导航。
 * 3. 底部「AI 眼睛」面板：眼睛指示灯随 ACI 调用点亮（青色），并显示实时状态文本。
 * 4. 「发给 AI」按钮：把当前网址 / 标题 / 页面 HTML 摘要经 ACTION_SEND 路由到 ZorvAI 主应用
 *    （传输内容管道，子目标「传输内容管到」）。
 * 5. ⌗ 切换调试 HUD（诊断条升级版，默认隐藏）。
 * 6. WebView 的权限自动授权与引擎信息由 BrowserCore 接管（注册时挂 WebChromeClient）。
 */
class BrowserActivity : Activity() {

    private var webView: WebView? = null
    private var statusView: TextView? = null

    // 新布局控件
    private var toolbar: LinearLayout? = null
    private var btnCollapse: Button? = null
    private var btnExpand: Button? = null
    private var addressBar: EditText? = null
    private var btnGo: Button? = null
    private var btnReload: Button? = null
    private var btnLog: Button? = null
    private var debugPanel: android.widget.ScrollView? = null
    private var eyeIndicator: TextView? = null
    private var aiStatus: TextView? = null
    private var btnShareAi: Button? = null

    // 手动控制台（人可直接驱动，无需经 AI）
    private var consoleInput: EditText? = null
    private var consoleOutput: TextView? = null
    private var captureOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── 1. 加载布局 ──
        try { setContentView(R.layout.activity_browser) }
        catch (e: Throwable) { fallbackError("XML布局失败: ${e.message}"); return }

        // 绑定控件
        statusView = findViewById(R.id.status_text)
        webView = findViewById(R.id.webview)
        toolbar = findViewById(R.id.toolbar)
        btnCollapse = findViewById(R.id.btn_collapse)
        btnExpand = findViewById(R.id.btn_expand)
        addressBar = findViewById(R.id.address_bar)
        btnGo = findViewById(R.id.btn_go)
        btnReload = findViewById(R.id.btn_reload)
        btnLog = findViewById(R.id.btn_log)
        debugPanel = findViewById(R.id.debug_panel)
        eyeIndicator = findViewById(R.id.eye_indicator)
        aiStatus = findViewById(R.id.ai_status)
        btnShareAi = findViewById(R.id.btn_share_ai)
        consoleInput = findViewById(R.id.console_input)
        consoleOutput = findViewById(R.id.console_output)

        if (webView == null) { showStatus("❌ 找不到WebView"); return }

        // ── 2. 配置 WebView ──
        try {
            webView?.webViewClient = object : WebViewClient() {
                /**
                 * 抓包拦截点（v1.0.12-capture 新增）：捕获每个请求的 URL / 方法 / 请求头 / 是否主框架。
                 * 返回 null = 放行，不修改/阻止任何响应，对正常浏览零影响。
                 * 仅记录元数据，不读响应体（响应体需 Chrome DevTools 协议，后续版本支持）。
                 */
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    request?.let { req ->
                        try {
                            val sb = StringBuilder()
                            req.requestHeaders?.forEach { (k, v) -> sb.append("$k: $v\n") }
                            BrowserCore.captureRequest(
                                url = req.url?.toString() ?: "",
                                method = req.method ?: "GET",
                                headers = sb.toString(),
                                isMainFrame = req.isForMainFrame
                            )
                        } catch (_: Throwable) {}
                    }
                    return null
                }
            }
            webView?.settings?.javaScriptEnabled = true
            webView?.settings?.domStorageEnabled = true
            webView?.settings?.loadsImagesAutomatically = true
            webView?.setBackgroundColor(0xFFFFFFFF.toInt())
        } catch (e: Throwable) { showStatus("⚠️ WebView配置: ${e.message}") }

        // ── 3. 注册到 BrowserCore（内含权限自动授权 WebChromeClient + 引擎信息）──
        try {
            BrowserCore.init(applicationContext)
            webView?.let { BrowserCore.registerDisplayWebView(it) }
        } catch (e: Throwable) { showStatus("⚠️ BrowserCore: ${e.message}") }

        // ── 4. 注册 AI 眼睛监听器（Service 的 ACI 调用会点亮它）──
        BrowserCore.aiEyeListener = object : BrowserCore.AiEyeListener {
            override fun onAiEyeChange(active: Boolean, message: String) {
                updateEye(active, message)
            }
        }

        // ── 5. 连线交互 ──
        wireControls()
        wireConsole()

        // ── 6. 【v8 核心】显式启动 ACI Service ──
        showStatus("🚀 正在启动 ACI Service...")
        try {
            val svcIntent = Intent(this, QuroControlledAciService::class.java)
            val result = startService(svcIntent)
            if (result != null) {
                showStatus("✅ ACI Service 启动成功！ComponentName=${result.shortClassName}")
                webView?.postDelayed({ refreshDiagDisplay() }, 1000)
            } else {
                showStatus("❌ startService 返回 null（Service 可能未声明或崩溃）")
            }
        } catch (e: SecurityException) {
            showStatus("❌ startService SecurityException: ${e.message}")
        } catch (e: Throwable) {
            showStatus("❌ startService 异常: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── 7. 显示当前诊断 + 加载页面 ──
        refreshDiagDisplay()

        val url = intent?.getStringExtra("url")
        if (!url.isNullOrEmpty()) {
            showStatus("📡 ZorvAI指令: $url")
            webView?.loadUrl(url)
            addressBar?.setText(url)
        } else {
            loadLandingPage()
        }

        // ── 8. 延迟验证 ──
        webView?.postDelayed({
            verifyRendering()
            refreshDiagDisplay()
        }, 2500)
    }

    private fun wireControls() {
        // 折叠 / 展开工具栏
        btnCollapse?.setOnClickListener {
            toolbar?.visibility = View.GONE
            btnExpand?.visibility = View.VISIBLE
            showStatus("≡ 工具栏已收起")
        }
        btnExpand?.setOnClickListener {
            toolbar?.visibility = View.VISIBLE
            btnExpand?.visibility = View.GONE
            showStatus("≡ 工具栏已展开")
        }

        // 地址栏导航
        val go: () -> Unit = {
            val u = addressBar?.text?.toString()?.trim() ?: ""
            if (u.isNotEmpty()) {
                // 【v1.0.11 地址栏搜索修复】非 URL 文本（含空格 / 无点关键词）走搜索引擎，
                // 而非被当作域名补 https:// 前缀导致 ERR_NAME_NOT_RESOLVED
                val finalUrl = smartNavigate(u)
                webView?.loadUrl(finalUrl)
                showStatus("🌐 打开: $finalUrl")
            }
        }
        btnGo?.setOnClickListener { go() }
        addressBar?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                go(); true
            } else false
        }

        // 刷新
        btnReload?.setOnClickListener {
            webView?.reload()
            showStatus("⟳ 已刷新")
        }

        // 调试 HUD 切换
        btnLog?.setOnClickListener {
            val willShow = debugPanel?.visibility != View.VISIBLE
            debugPanel?.visibility = if (willShow) View.VISIBLE else View.GONE
            if (willShow) refreshDiagDisplay()
            showStatus(if (willShow) "⌗ 调试面板已显示" else "⌗ 调试面板已隐藏")
        }

        // 发给 AI（传输内容管道）
        btnShareAi?.setOnClickListener { shareToAi() }
    }

    /**
     * 手动控制台（v8.1 新增）：让使用者不经由 AI、直接驱动浏览器。
     * 凡是会阻塞在 BrowserCore latch 上的方法（loadUrl / readHtml / crawlPage /
     * evalScript / findInPage / screenshot）都必须在后台线程调用，否则会与 UI 线程的
     * evaluateJavascript 回调死锁 → 返回空。其余方法（nav* / findNext / clearFind /
     * setCaptureEnabled / clearCapture）仅向 mainHandler post，UI 线程直接调也安全。
     */
    private fun wireConsole() {
        val consoleBody = findViewById<ScrollView>(R.id.console_body)
        val consoleToggle = findViewById<Button>(R.id.btn_console_toggle)
        val btnCOpen = findViewById<Button>(R.id.btn_c_open)
        val btnCRead = findViewById<Button>(R.id.btn_c_read)
        val btnCCrawl = findViewById<Button>(R.id.btn_c_crawl)
        val btnCJs = findViewById<Button>(R.id.btn_c_js)
        val btnCFind = findViewById<Button>(R.id.btn_c_find)
        val btnCFindNext = findViewById<Button>(R.id.btn_c_findnext)
        val btnCFindClear = findViewById<Button>(R.id.btn_c_findclear)
        val btnCBack = findViewById<Button>(R.id.btn_c_back)
        val btnCFwd = findViewById<Button>(R.id.btn_c_fwd)
        val btnCReload = findViewById<Button>(R.id.btn_c_reload)
        val btnCShot = findViewById<Button>(R.id.btn_c_shot)
        val btnCCapture = findViewById<Button>(R.id.btn_c_capture)
        val btnCCapClear = findViewById<Button>(R.id.btn_c_capclear)

        val input: () -> String = { consoleInput?.text?.toString()?.trim() ?: "" }

        consoleToggle?.setOnClickListener {
            val show = consoleBody?.visibility != View.VISIBLE
            consoleBody?.visibility = if (show) View.VISIBLE else View.GONE
            showStatus(if (show) "⌨ 手动控制台已展开" else "⌨ 手动控制台已收起")
        }

        // 打开 URL（smartNavigate 同地址栏）
        btnCOpen?.setOnClickListener {
            val u = input()
            if (u.isEmpty()) { showStatus("⚠ 控制台：请输入 URL"); return@setOnClickListener }
            val finalUrl = smartNavigate(u)
            thread(name = "c-open") {
                BrowserCore.loadUrl(finalUrl)
                runOnUiThread {
                    showStatus("🌐 打开: $finalUrl")
                    addressBar?.setText(finalUrl)
                }
            }
        }

        // 读 HTML（SPA 已修复：JS 端切片 + 8s 超时）
        btnCRead?.setOnClickListener {
            thread(name = "c-read") {
                val html = runCatching { BrowserCore.readHtml() }.getOrDefault("")
                runOnUiThread { showConsole("读HTML（${html.length} 字）：\n${html.take(8000)}") }
            }
        }

        // 爬取正文 + 链接
        btnCCrawl?.setOnClickListener {
            thread(name = "c-crawl") {
                val text = runCatching { BrowserCore.crawlPage() }.getOrDefault("")
                runOnUiThread { showConsole("爬取（${text.length} 字）：\n${text.take(8000)}") }
            }
        }

        // 运行 JS
        btnCJs?.setOnClickListener {
            val code = input()
            if (code.isEmpty()) { showStatus("⚠ 控制台：请输入 JS 代码"); return@setOnClickListener }
            thread(name = "c-js") {
                val r = runCatching { BrowserCore.evalScript(code) }.getOrDefault("")
                runOnUiThread { showConsole("JS 结果：\n${r.take(8000)}") }
            }
        }

        // 页内查找
        btnCFind?.setOnClickListener {
            val t = input()
            if (t.isEmpty()) { showStatus("⚠ 控制台：请输入关键词"); return@setOnClickListener }
            thread(name = "c-find") {
                val n = runCatching { BrowserCore.findInPage(t) }.getOrDefault(0)
                runOnUiThread {
                    showStatus("🔍 命中 $n 处")
                    showConsole("查找「$t」命中 $n 处")
                }
            }
        }
        btnCFindNext?.setOnClickListener { BrowserCore.findNext(true); showStatus("🔍 下一个匹配") }
        btnCFindClear?.setOnClickListener { BrowserCore.clearFind(); showStatus("🔍 清除查找"); showConsole("已清除查找高亮") }

        // 导航
        btnCBack?.setOnClickListener { BrowserCore.navBack(); showStatus("◀ 后退") }
        btnCFwd?.setOnClickListener { BrowserCore.navForward(); showStatus("▶ 前进") }
        btnCReload?.setOnClickListener { BrowserCore.navReload(); showStatus("⟳ 刷新") }

        // 截图（存外部存储 Pictures 子目录，返回路径）
        btnCShot?.setOnClickListener {
            thread(name = "c-shot") {
                val dir = getExternalFilesDir(null)
                val path = java.io.File(dir, "quro_shots/shot_${System.currentTimeMillis()}.png").absolutePath
                val res = runCatching { BrowserCore.screenshot(path) }.getOrDefault("")
                runOnUiThread {
                    if (res.isNotEmpty()) {
                        showStatus("📸 截图: $res")
                        showConsole("截图已保存：\n$res")
                    } else {
                        showStatus("⚠ 截图失败")
                        showConsole("截图失败（WebView 尺寸可能为 0，或页面尚未渲染）")
                    }
                }
            }
        }

        // 抓包开关（请求经 WebViewClient.shouldInterceptRequest 写入 CaptureBuffer）
        btnCCapture?.setOnClickListener {
            captureOn = !captureOn
            BrowserCore.setCaptureEnabled(captureOn)
            btnCCapture.text = if (captureOn) "抓包:开" else "抓包:关"
            showStatus(if (captureOn) "🐟 抓包已开启" else "🐟 抓包已关闭")
            if (captureOn) showConsole("抓包已开启：浏览网页后，记录经 ACI console_ui 暴露；点「清抓包」可重置。")
        }
        btnCCapClear?.setOnClickListener {
            BrowserCore.clearCapture()
            showStatus("🐟 抓包记录已清空")
            showConsole("抓包记录已清空")
        }
    }

    /** 把控制台结果追加到 console_output 文本框（UI 线程安全）。 */
    private fun showConsole(msg: String) {
        runOnUiThread { consoleOutput?.append("\n• $msg") }
    }

    /** AI「眼睛」指示灯与控制文本。 */
    private fun updateEye(active: Boolean, message: String) {
        runOnUiThread {
            if (active) {
                eyeIndicator?.setTextColor(0xFF22D3EE.toInt())
                aiStatus?.text = "👁 AI 控制中：${if (message.isEmpty()) "正在操作浏览器" else message}"
            } else {
                eyeIndicator?.setTextColor(0xFF556070.toInt())
                aiStatus?.text = "AI 状态：待命（可被 ZorvAI 经 ACI 远程控制）"
            }
        }
    }

    /** 把当前页内容打包发给 ZorvAI 主应用（text/plain，优先路由到 com.ai.assistance.quro）。 */
    private fun shareToAi() {
        val url = webView?.url ?: ""
        val title = webView?.title ?: ""
        showStatus("📤 正在读取页面内容并发给 AI...")
        // 直接在 UI 线程读 DOM（evaluateJavascript 异步回调，不会死锁）
        webView?.evaluateJavascript("document.documentElement.outerHTML") { html ->
            val snippet = (html ?: "").let { if (it.length > 2000) it.substring(0, 2000) else it }
            val text = buildString {
                append("【ZorvAI 浏览器分享】\n")
                append("网址: $url\n")
                if (title.isNotEmpty()) append("标题: $title\n")
                if (snippet.isNotEmpty()) append("\n页面摘要(html前2000字):\n$snippet")
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                intent.setPackage("com.ai.assistance.quro")
                startActivity(intent)
                showStatus("✅ 已发送给 ZorvAI")
            } catch (e: ActivityNotFoundException) {
                intent.setPackage(null)
                startActivity(Intent.createChooser(intent, "发送给 AI / 应用"))
                showStatus("✅ 已通过选择器发送（未直接命中 ZorvAI）")
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("url")?.let { url ->
            showStatus("📡 onNewIntent: $url")
            webView?.loadUrl(url)
            addressBar?.setText(url)
        }
    }

    override fun onResume() {
        super.onResume()
        // 【v1.0.12 防御】每次回到前台都确保 displayWv 指向当前 WebView，
        // 防止 Activity 被系统重建后 BrowserCore 仍持有已销毁的旧实例 → 读取 500。
        webView?.let { BrowserCore.registerDisplayWebView(it) }
    }

    override fun onDestroy() {
        BrowserCore.aiEyeListener = null
        BrowserCore.unregisterDisplayWebView()
        webView = null
        super.onDestroy()
    }

    // ══════════════════════════════════
    //  内部方法
    // ══════════════════════════════════

    /**
     * 地址栏智能导航（v1.0.11 修复）：
     * - 已带 http(s):// 协议 → 原样打开；
     * - 含点且无空格（看起来像域名/IP，如 example.com / 192.168.1.1）→ 补 https:// 打开；
     * - 其它（含空格的短语 / 无点的关键词，如「百度」）→ 走搜索引擎（默认 bing）。
     */
    private fun smartNavigate(input: String): String {
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(" ") -> searchUrl(input)
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> searchUrl(input)
        }
    }

    private fun searchUrl(q: String): String {
        val enc = URLEncoder.encode(q, "UTF-8")
        return "https://www.bing.com/search?q=$enc"
    }

    private fun loadLandingPage() {
        webView?.loadUrl("about:blank")
        webView?.postDelayed({
            val jsHtml = """
                document.open();
                document.write('<html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">'+
                '<style>body{font-family:sans-serif;margin:0;padding:24px;background:#fff;color:#222}</style></head>'+
                '<body>'+
                '<h2>\uD83C\uDF10 ZorvAI 浏览器</h2>'+
                '<p>\u672C\u6D4F\u89C8\u5668\u7531 <b>ZorvAI</b> \u7ECF ACI \u534F\u8BAE\u8FDC\u7A0B\u63A7\u5236\uFF0C\u4E5F\u53EF\u81EA\u7528\u3002</p>'+
                '<p>\u5728 AI \u5BF9\u8BDD\u91CC\u8BF4<b>\u201C\u6253\u5F00\u67D0\u7F51\u7AD9\u201D</b>\u5373\u53EF\u5728\u6B64\u5448\u73B0\u9875\u9762\u3002</p>'+
                '<hr/><p style=color:#888;font-size:12px>v8 \u00B7 \u6298\u53E0\u5DE5\u5177\u680F + AI\u773C\u775B | '+
                'com.ai.assistance.quro.browser</p>'+
                '</body></html>');
                document.close();
            """.trimIndent()
            webView?.evaluateJavascript(jsHtml, null)
            showStatus("✅ 落地页已加载")
        }, 300)
    }

    private fun verifyRendering() {
        try {
            webView?.evaluateJavascript(
                "(function(){try{var b=document.body;return 'readyState='+document.readyState+',bodyChilds='+(b?b.childNodes.length:-1)+',bodyHTMLLen='+(b?b.innerHTML.length:-1);}catch(e){return 'ERR:'+e;}})()"
            ) { r -> showStatus("🔍 DOM: $r") }
        } catch (_: Throwable) {}
    }

    private fun refreshDiagDisplay() {
        val diag = DiagBuffer.getAll()
        val header = "=== ZorvAI 浏览器 v8 ==="
        statusView?.text = if (diag.isEmpty()) "$header\n(\u6682\u65E0 Service \u65E5\u5FD7)" else "$header\n$diag"
    }

    private fun showStatus(msg: String) {
        DiagBuffer.append("UI", msg)
        runOnUiThread { statusView?.append("\n• $msg") }
    }

    private fun fallbackError(msg: String) {
        setContentView(TextView(this).apply {
            text = "ERROR:\n$msg\n\n--- DiagBuffer ---\n${DiagBuffer.getAll()}"
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
            textSize = 14f
            setTextColor(0xFFCC0000.toInt())
        })
    }

    companion object {
        const val LANDING_URL = "about:blank"
    }
}
