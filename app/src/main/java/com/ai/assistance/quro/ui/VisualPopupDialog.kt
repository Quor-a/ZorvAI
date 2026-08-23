package com.ai.assistance.quro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ai.assistance.quro.core.tools.PopupButton
import com.ai.assistance.quro.core.tools.PopupInput
import com.ai.assistance.quro.core.tools.PopupResult
import com.ai.assistance.quro.core.tools.VisualPopupQueue
import com.ai.assistance.quro.core.tools.VisualPopupData

/**
 * 自由可视化弹窗 - AI可以创建任意内容的弹窗，没有格式限制
 */
@Composable
fun VisualPopupDialog() {
    val cs = MaterialTheme.colorScheme
    var currentPopup by remember { mutableStateOf<Pair<Int, VisualPopupData>?>(null) }
    var inputValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 定时检查是否有待处理的弹窗
    LaunchedEffect(Unit) {
        while (true) {
            val popup = VisualPopupQueue.getCurrentPopup()
            if (popup != null && currentPopup == null) {
                currentPopup = popup
                // 初始化输入框默认值
                val defaults = popup.second.inputs.associate { it.id to it.defaultValue }
                inputValues = defaults
            }
            kotlinx.coroutines.delay(500)
        }
    }

    currentPopup?.let { (index, popup) ->
        Dialog(
            onDismissRequest = {
                if (popup.cancelable) {
                    VisualPopupQueue.submitResult(index, PopupResult(null, inputValues, cancelled = true))
                    currentPopup = null
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = popup.cancelable,
                dismissOnClickOutside = popup.cancelable
            )
        ) {
            Card(
                modifier = Modifier
                    .then(
                        if (popup.width != null) Modifier.width(popup.width.dp)
                        else Modifier.fillMaxWidth(0.9f)
                    )
                    .then(
                        if (popup.height != null) Modifier.heightIn(max = popup.height.dp)
                        else Modifier.heightIn(max = 500.dp)
                    )
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    // 标题栏
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.Web,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = popup.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (popup.cancelable) {
                            IconButton(
                                onClick = {
                                    VisualPopupQueue.submitResult(index, PopupResult(null, inputValues, cancelled = true))
                                    currentPopup = null
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // 图片（如果有）
                    popup.imageUrl?.let { url ->
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    settings.javaScriptEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    webViewClient = WebViewClient()
                                    loadUrl(url)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .padding(bottom = 12.dp)
                        )
                    }

                    // 内容（Markdown/HTML/纯文本）
                    Text(
                        text = popup.content,
                        fontSize = 15.sp,
                        color = cs.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 输入框（如果有）
                    if (popup.inputs.isNotEmpty()) {
                        popup.inputs.forEach { input ->
                            OutlinedTextField(
                                value = inputValues[input.id] ?: input.defaultValue,
                                onValueChange = { value ->
                                    inputValues = inputValues + (input.id to value)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                label = { Text(input.label) },
                                placeholder = { Text(input.placeholder) },
                                singleLine = input.type != "text",
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = cs.primary,
                                    unfocusedBorderColor = cs.outline
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 按钮区域
                    if (popup.buttons.isNotEmpty()) {
                        // 如果有多个按钮，使用Column布局；单个按钮使用Row
                        if (popup.buttons.size <= 2) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                popup.buttons.forEach { button ->
                                    val buttonColor = when (button.style) {
                                        "danger" -> cs.error
                                        "secondary" -> cs.secondary
                                        "success" -> Color(0xFF4CAF50)
                                        else -> cs.primary
                                    }
                                    val textColor = when (button.style) {
                                        "danger" -> cs.onError
                                        "secondary" -> cs.onSecondary
                                        "success" -> Color.White
                                        else -> cs.onPrimary
                                    }

                                    Button(
                                        onClick = {
                                            VisualPopupQueue.submitResult(
                                                index,
                                                PopupResult(button.value, inputValues, cancelled = false)
                                            )
                                            currentPopup = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = button.text,
                                            fontSize = 14.sp,
                                            color = textColor
                                        )
                                    }
                                }
                            }
                        } else {
                            // 多个按钮使用Column布局
                            popup.buttons.forEach { button ->
                                val buttonColor = when (button.style) {
                                    "danger" -> cs.error
                                    "secondary" -> cs.secondary
                                    "success" -> Color(0xFF4CAF50)
                                    else -> cs.primary
                                }
                                val textColor = when (button.style) {
                                    "danger" -> cs.onError
                                    "secondary" -> cs.onSecondary
                                    "success" -> Color.White
                                    else -> cs.onPrimary
                                }

                                Button(
                                    onClick = {
                                        VisualPopupQueue.submitResult(
                                            index,
                                            PopupResult(button.value, inputValues, cancelled = false)
                                        )
                                        currentPopup = null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = button.text,
                                        fontSize = 14.sp,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
