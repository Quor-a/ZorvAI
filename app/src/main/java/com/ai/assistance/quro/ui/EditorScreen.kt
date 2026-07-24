package com.ai.assistance.quro.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.core.tools.RunCodeTool
import org.json.JSONObject

/**
 * 内置 CodeMirror 代码编辑器（WebView 承载，离线资源在 assets/www/）。
 *
 * - 语法高亮：JavaScript / Python / HTML / JSON / CSS / XML / C·C++·Java
 * - 「运行」直接调 RunCodeTool：JS 走 App 内置 QuickJS 原生沙箱离线执行，Python 走 Termux/系统 python3
 * - 「完成」把编辑器内容回传 ChatScreen 输入框，供用户审阅后发送
 */
@Composable
fun EditorScreen(
    initialCode: String = "",
    initialLang: String = "javascript",
    onClose: (String) -> Unit
) {
    val ctx = LocalContext.current
    val bridge = remember {
        object {
            @JavascriptInterface
            fun onDone(content: String) {
                // @JavascriptInterface 在 WebView 后台线程回调，切回主线程更新 Compose 状态
                Handler(Looper.getMainLooper()).post { onClose(content) }
            }

            @JavascriptInterface
            fun onRun(code: String, lang: String): String = runCatching {
                RunCodeTool().run(
                    ctx,
                    JSONObject().apply { put("code", code); put("lang", lang) }.toString()
                )
            }.getOrElse { e -> "⚠️ 运行异常：${e.message}" }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                // 关键：允许 WebView 获取焦点和触摸模式焦点，否则 CodeMirror 无法接收键盘输入/粘贴
                isFocusable = true
                isFocusableInTouchMode = true

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val js = "window.loadCode(${JSONObject.quote(initialCode)}, ${JSONObject.quote(initialLang)});"
                        view?.evaluateJavascript(js, null)
                    }
                }
                addJavascriptInterface(bridge, "AndroidBridge")
                loadUrl("file:///android_asset/www/editor.html")
            }
        },
        update = { webView ->
            // 每次 recompose 时确保 WebView 可聚焦（Compose AndroidView 可能重置焦点）
            if (!webView.isFocused) {
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
            }
        }
    )
}
