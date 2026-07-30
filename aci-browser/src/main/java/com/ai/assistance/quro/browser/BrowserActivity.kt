package com.ai.assistance.quro.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView

/**
 * 可见浏览器界面（v6 · 自启 Service 版）。
 *
 * v5 诊断结论：
 * - ✅ 白屏已修复（JS 注入落地页 bodyHTMLLen=186）
 * - 🔴 状态条只有 [UI] 没有 [Service] → Service 从未创建 → bindService 失败
 * - ANR 报告显示是 ZorvAI 侧 searchInstalledApps 在主线程卡住（与受控 App 无关）
 *
 * v6 核心改动：
 * 1. BrowserActivity.onCreate 显式 startService() —— 保证 Service 在用户打开 App 时立即运行，
 *    之后 ZorvAI 的 bindService 连接已有实例而非从 stopped-state 创建。
 * 2. startService 结果写入 DiagBuffer 并显示在屏幕上 —— 用户一眼看到 Service 是否启动成功。
 * 3. 落地页保留 v5 的 JS 注入方案（已验证可用）。
 */
class BrowserActivity : Activity() {

    private var webView: WebView? = null
    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── 1. 加载布局 ──
        try { setContentView(R.layout.activity_browser) }
        catch (e: Throwable) { fallbackError("XML布局失败: ${e.message}"); return }

        statusView = findViewById(R.id.status_text)
        webView = findViewById(R.id.webview)
        if (webView == null) { showStatus("❌ 找不到WebView"); return }

        // ── 2. 配置 WebView ──
        try {
            webView?.webViewClient = WebViewClient()
            webView?.settings?.javaScriptEnabled = true
            webView?.settings?.domStorageEnabled = true
            webView?.settings?.loadsImagesAutomatically = true
            webView?.setBackgroundColor(0xFFFFFFFF.toInt())
        } catch (e: Throwable) { showStatus("⚠️ WebView配置: ${e.message}") }

        // ── 3. 注册到 BrowserCore ──
        try {
            BrowserCore.init(applicationContext)
            webView?.let { BrowserCore.registerDisplayWebView(it) }
        } catch (e: Throwable) { showStatus("⚠️ BrowserCore: ${e.message}") }

        // ── 4. 【v6 核心】显式启动 ACI Service ──
        showStatus("🚀 正在启动 ACI Service...")
        try {
            val svcIntent = Intent(this, QuroControlledAciService::class.java)
            val result = startService(svcIntent)
            if (result != null) {
                showStatus("✅ ACI Service 启动成功！ComponentName=${result.shortClassName}")
                // Service 启动后等一下让它完成 onCreate + onCreateCapabilities
                webView?.postDelayed({ refreshDiagDisplay() }, 1000)
            } else {
                showStatus("❌ startService 返回 null（Service 可能未声明或崩溃）")
            }
        } catch (e: SecurityException) {
            showStatus("❌ startService SecurityException: ${e.message}")
        } catch (e: Throwable) {
            showStatus("❌ startService 异常: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── 5. 显示当前诊断 + 加载页面 ──
        refreshDiagDisplay()

        val url = intent?.getStringExtra("url")
        if (!url.isNullOrEmpty()) {
            showStatus("📡 ZorvAI指令: $url")
            webView?.loadUrl(url)
        } else {
            loadLandingPage()
        }

        // ── 6. 延迟验证 ──
        webView?.postDelayed({
            verifyRendering()
            refreshDiagDisplay()
        }, 2500)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("url")?.let { url ->
            showStatus("📡 onNewIntent: $url")
            webView?.loadUrl(url)
        }
    }

    override fun onDestroy() {
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
                '<h2>\uD83C\uDF10 Zorv \u53D7\u63A7\u6D4F\u89C8\u5668</h2>'+
                '<p>\u672C\u6D4F\u89C8\u5668\u7531 <b>ZorvAI</b> \u7ECF ACI \u534F\u8BAE\u8FDC\u7A0B\u63A7\u5236\u3002</p>'+
                '<p>\u5728 AI \u5BF9\u8BDD\u91CC\u8BF4<b>\u201C\u6253\u5F00\u67D0\u7F51\u7AD9\u201D</b>\u5373\u53EF\u5728\u6B64\u5448\u73B0\u9875\u9762\u3002</p>'+
                '<hr/><p style=color:#888;font-size:12px>v6 \u00B7 \u81EA\u542F Service | '+
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
        val header = "=== Zorv \u53D7\u63A7\u6D4F\u89C8\u5668 v6 ==="
        statusView?.text = if (diag.isEmpty()) "$header\n(\u6682\u65E0 Service \u65E5\u5FD7)" else "$header\n$diag"

        (statusView?.parent as? android.widget.FrameLayout)?.let { parent ->
            val idx = parent.indexOfChild(statusView)
            if (idx >= 0 && parent !is ScrollView) {
                val scroll = ScrollView(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                parent.removeView(statusView)
                scroll.addView(statusView, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                parent.addView(scroll, idx)
            }
        }
    }

    private fun showStatus(msg: String) {
        DiagBuffer.append("UI", msg)
        runOnUiThread { statusView?.append("\n• $msg") }
    }

    private fun fallbackError(msg: String) {
        setContentView(TextView(this).apply {
            text = "ERROR:\n$msg\n\n--- DiagBuffer ---\n${DiagBuffer.getAll()}"
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            textSize = 14f
            setTextColor(0xFFCC0000.toInt())
        })
    }

    companion object {
        const val LANDING_URL = "about:blank"
    }
}
