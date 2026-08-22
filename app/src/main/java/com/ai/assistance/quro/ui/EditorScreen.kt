package com.ai.assistance.quro.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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

            @JavascriptInterface
            fun onSaveCode(content: String, lang: String) {
                val extension = when (lang.lowercase()) {
                    "javascript", "js" -> ".js"
                    "python", "py" -> ".py"
                    "html", "htm" -> ".html"
                    "json" -> ".json"
                    "css" -> ".css"
                    "xml", "svg" -> ".xml"
                    "java" -> ".java"
                    "kotlin", "kt" -> ".kt"
                    "c", "cpp", "c++" -> ".cpp"
                    "shell", "sh", "bash" -> ".sh"
                    else -> ".txt"
                }
                val fileName = "quro_code_${System.currentTimeMillis()}$extension"
                saveToDownloads(ctx, fileName, content)
            }

            @JavascriptInterface
            fun onSaveResult(result: String) {
                val fileName = "quro_result_${System.currentTimeMillis()}.txt"
                saveToDownloads(ctx, fileName, result)
            }
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

private fun saveToDownloads(context: Context, fileName: String, content: String) {
    Handler(Looper.getMainLooper()).post {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore API
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                    Toast.makeText(context, "✅ 已保存到 Downloads/$fileName", Toast.LENGTH_SHORT).show()
                } ?: Toast.makeText(context, "❌ 保存失败：无法创建文件", Toast.LENGTH_SHORT).show()
            } else {
                // Android 9 及以下使用直接写入
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = java.io.File(downloadsDir, fileName)
                file.writeText(content)
                Toast.makeText(context, "✅ 已保存到 Downloads/$fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "❌ 保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
