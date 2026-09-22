package com.ai.assistance.quro.genui.aiapp.host

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 内联 HTML / 网页查看器
 *
 * 由 open_html 动作调起：真渲染 AI 传入的 HTML 字符串；
 * 由 open_url 动作（应用内模式）调起：真加载网页 URL。
 */
class HtmlViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val html = intent.getStringExtra("html")
        val url = intent.getStringExtra("url")
        val title = intent.getStringExtra("title") ?: "详情"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // 顶栏：返回 + 标题
        val bar = TextView(this).apply {
            text = "←  $title"
            textSize = 16f
            setPadding(44, 40, 44, 40)
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundColor(Color.rgb(245, 245, 247))
            setOnClickListener { finish() }
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            setBackgroundColor(Color.WHITE)
        }
        if (!html.isNullOrBlank()) {
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        } else if (!url.isNullOrBlank()) {
            webView.loadUrl(url)
        } else {
            finish()
            return
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }
}
