package com.ai.assistance.quro.ui

import kotlin.math.roundToInt
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.core.tools.VisualCustomPopupData
import com.ai.assistance.quro.core.tools.generateCustomPopupHtml

/**
 * 可视化弹窗悬浮窗UI组件
 * 
 * 用于在系统级悬浮窗中显示AI创建的可视化弹窗
 */
@Composable
fun VisualCustomPopupOverlay(
    popupData: VisualCustomPopupData,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
    onDrag: (Int, Int) -> Unit = { _, _ -> },
    onMinimize: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    var webView by remember { mutableStateOf<WebView?>(null) }
    
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部标题栏（可拖动区域）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.primaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            onDrag(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 拖动图标
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "拖动",
                    tint = cs.onPrimaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Icon(
                    Icons.Filled.Web,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(20.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = popupData.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                
                // 关闭按钮
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = cs.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            // WebView内容
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        // 修复：默认 WebViewClient 不拦截 shouldOverrideUrlLoading，
                        // AI 自写 HTML 内的链接会跳出悬浮窗/跳走。
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): Boolean {
                                request?.url?.let { view?.loadUrl(it.toString()) }
                                return true
                            }
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.defaultTextEncodingName = "UTF-8"
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.allowFileAccessFromFileURLs = true

                        // 添加JavaScript接口，让HTML可以调用
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun postMessage(json: String) {
                                try {
                                    val msg = org.json.JSONObject(json)
                                    val action = msg.optString("action", "")
                                    when (action) {
                                        "submit" -> {
                                            val data = msg.opt("data")
                                            val result = org.json.JSONObject().apply {
                                                put("cancelled", false)
                                                put("data", data ?: org.json.JSONObject())
                                            }.toString()
                                            onSubmit(result)
                                        }
                                        "close" -> {
                                            onClose()
                                        }
                                    }
                                } catch (e: Exception) {
                                    // 忽略解析错误
                                }
                            }
                        }, "Android")

                        // 加载AI自写的HTML
                        val html = generateCustomPopupHtml(popupData)
                        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)

                        webView = this
                    }
                },
                // 修复：原代码无 onRelease，悬浮窗关闭后 WebView 未 destroy 造成泄漏。
                update = { /* no-op */ },
                onRelease = { wv ->
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                    webView = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
            
            // 底部操作栏：收起 + 确认
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onMinimize) {
                    Icon(Icons.Filled.Minimize, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("收起")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    webView?.evaluateJavascript(
                        "(function(){ try { if (window.submitResult) { window.submitResult({}); return; } if (window.Android && window.Android.postMessage) { window.Android.postMessage(JSON.stringify({action:'submit', data:{}})); } } catch(e){} })();",
                        null
                    )
                }) {
                    Text("确认")
                }
            }
        }
    }
}