package com.ai.assistance.quro.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.core.tools.VisualCustomPopupData
import com.ai.assistance.quro.core.tools.VisualCustomPopupQueue
import com.ai.assistance.quro.core.tools.generateCustomPopupHtml

/**
 * AI自写UI可视化弹窗组件
 *
 * 包含两部分：
 * 1. 对话框小卡片：显示在消息列表中，点击打开完整弹窗
 * 2. 完整弹窗：WebView渲染AI自写的HTML内容
 */
@Composable
fun VisualCustomPopupCard(
    popupData: VisualCustomPopupData,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Icon(
                Icons.Filled.Web,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 标题和描述
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = popupData.cardTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (popupData.cardDescription.isNotBlank()) {
                    Text(
                        text = popupData.cardDescription,
                        fontSize = 12.sp,
                        color = cs.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            
            // 打开按钮
            Icon(
                Icons.Filled.OpenInNew,
                contentDescription = "打开",
                tint = cs.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * AI自写UI可视化弹窗 - 完整的WebView弹窗
 */
@Composable
fun VisualCustomPopupDialog() {
    val cs = MaterialTheme.colorScheme
    var currentPopup by remember { mutableStateOf<Pair<String, VisualCustomPopupData>?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 事件驱动：监听弹窗队列变化
    LaunchedEffect(Unit) {
        VisualCustomPopupQueue.eventFlow.collect { event ->
            when (event) {
                is VisualCustomPopupQueue.PopupEvent.PopupAdded -> {
                    // 有新弹窗加入，如果当前没有显示弹窗则显示
                    if (currentPopup == null) {
                        val popup = VisualCustomPopupQueue.getCurrentPopup()
                        if (popup != null) {
                            currentPopup = popup
                        }
                    }
                }
                is VisualCustomPopupQueue.PopupEvent.PopupRemoved -> {
                    // 弹窗被移除
                    if (currentPopup?.first == event.id) {
                        currentPopup = null
                    }
                }
            }
        }
    }

    currentPopup?.let { (id, popup) ->
        Dialog(
            onDismissRequest = {
                if (popup.cancelable) {
                    VisualCustomPopupQueue.submitResult(id, """{"cancelled":true}""")
                    currentPopup = null
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = popup.cancelable,
                dismissOnClickOutside = false  // WebView内点击不应关闭
            )
        ) {
            Card(
                modifier = Modifier
                    .then(
                        if (popup.width != null) Modifier.width(popup.width.dp)
                        else Modifier.fillMaxWidth(0.95f)
                    )
                    .then(
                        if (popup.height != null) Modifier.heightIn(max = popup.height.dp)
                        else Modifier.heightIn(max = 600.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 顶部标题栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(cs.primaryContainer)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Web,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = popup.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        if (popup.cancelable) {
                            IconButton(
                                onClick = {
                                    VisualCustomPopupQueue.submitResult(id, """{"cancelled":true}""")
                                    currentPopup = null
                                },
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
                    }
                    
                    // WebView内容
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                
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
                                                    VisualCustomPopupQueue.submitResult(id, result)
                                                    // 在主线程关闭弹窗
                                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        currentPopup = null
                                                    }
                                                }
                                                "close" -> {
                                                    VisualCustomPopupQueue.submitResult(id, """{"cancelled":true}""")
                                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        currentPopup = null
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // 忽略解析错误
                                        }
                                    }
                                }, "Android")
                                
                                // 加载AI自写的HTML
                                val html = generateCustomPopupHtml(popup)
                                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                
                                webView = this
                            }
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
                        TextButton(
                            onClick = {
                                // 收起：暂存弹窗（保留在队列，对话内卡片可重新打开），不提交结果
                                currentPopup = null
                            }
                        ) {
                            Icon(Icons.Filled.Minimize, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("收起")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                // 确认：触发 HTML 内 submitResult（已桥接 Android），无则回传空结果
                                webView?.evaluateJavascript(
                                    "(function(){ try { if (window.submitResult) { window.submitResult({}); return; } if (window.Android && window.Android.postMessage) { window.Android.postMessage(JSON.stringify({action:'submit', data:{}})); } } catch(e){} })();",
                                    null
                                )
                            }
                        ) {
                            Text("确认")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 自定义弹窗消息卡片 - 在对话列表中显示
 */
@Composable
fun CustomPopupMessageCard(
    popupId: String,
    cardTitle: String,
    cardDescription: String,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = cs.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 标题和描述
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = cardTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (cardDescription.isNotBlank()) {
                    Text(
                        text = cardDescription,
                        fontSize = 12.sp,
                        color = cs.onSecondaryContainer.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            
            // 箭头
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = cs.onSecondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}