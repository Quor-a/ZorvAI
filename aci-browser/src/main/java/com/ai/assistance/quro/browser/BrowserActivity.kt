package com.ai.assistance.quro.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

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

        if (webView == null) { showStatus("❌ 找不到WebView"); return }

        // ── 2. 配置 WebView ──
        try {
            webView?.webViewClient = WebViewClient()
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
                val finalUrl = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
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

    override fun onDestroy() {
        BrowserCore.aiEyeListener = null
        BrowserCore.unregisterDisplayWebView()
        webView = null
        super.onDestroy()
    }

    // ══════════════════════════════════
    //  内部方法
    // ══════════════════════════════════

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
