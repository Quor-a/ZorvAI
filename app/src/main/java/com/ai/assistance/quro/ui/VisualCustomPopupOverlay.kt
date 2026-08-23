package com.ai.assistance.quro.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
    onClose: () -> Unit
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
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
            
            // 底部操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 全屏按钮
                IconButton(
                    onClick = {
                        // TODO: 实现全屏功能
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = "全屏",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // 最小化按钮
                IconButton(
                    onClick = {
                        // TODO: 实现最小化功能
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Minimize,
                        contentDescription = "最小化",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}