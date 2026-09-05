package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ai.assistance.quro.core.tools.PopupButton
import com.ai.assistance.quro.core.tools.VisualPopupQueue
import com.ai.assistance.quro.core.tools.VisualPopupData
import com.ai.assistance.quro.core.tools.PopupStatus
import com.ai.assistance.quro.core.tools.PopupResult

/**
 * 可视化弹窗小卡片 - 显示在对话框中，点击可重新打开弹窗
 */
/**
 * 可视化弹窗小卡片 - 显示在对话框中，点击可重新打开弹窗。
 * 重写版（更丰富、独立功能）：图标盒(48dp，按 iconName 解析，缺省按状态) + 标题 + 副标题 +
 * 描述 + 标签 chips(最多3) + 可选进度条 + 状态徽标，整卡可点重开弹窗。
 */
@Composable
fun VisualPopupCard(
    popupData: VisualPopupData,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    val containerColor = when (popupData.status) {
        PopupStatus.COMPLETED -> cs.secondaryContainer
        PopupStatus.CANCELLED -> cs.errorContainer
        else -> cs.primaryContainer
    }
    val accentColor = when (popupData.status) {
        PopupStatus.COMPLETED -> cs.secondary
        PopupStatus.CANCELLED -> cs.error
        else -> cs.primary
    }
    val pending = popupData.status == PopupStatus.PENDING || popupData.status == PopupStatus.ACTIVE

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标盒：按 iconName 解析，缺省按状态取
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        resolvePopupIcon(popupData.iconName, popupData.status),
                        contentDescription = null,
                        tint = cs.onPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 标题 + 状态徽标
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = popupData.cardTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    when (popupData.status) {
                        PopupStatus.COMPLETED -> StatusPill("已完成", accentColor, cs.onSecondary)
                        PopupStatus.CANCELLED -> StatusPill("已取消", accentColor, cs.onError)
                        else -> { /* 待处理不显示徽标 */ }
                    }
                }

                // 待处理给箭头提示可点开
                if (pending) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = cs.onPrimaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 副标题
            if (popupData.subtitle.isNotBlank()) {
                Text(
                    text = popupData.subtitle,
                    fontSize = 13.sp,
                    color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // 描述
            if (popupData.cardDescription.isNotBlank()) {
                Text(
                    text = popupData.cardDescription,
                    fontSize = 12.sp,
                    color = cs.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (popupData.subtitle.isNotBlank()) 2.dp else 8.dp)
                )
            }

            // 标签 chips（最多3个）
            if (popupData.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    popupData.tags.take(3).forEach { tag ->
                        Surface(
                            color = accentColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = tag,
                                fontSize = 11.sp,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // 进度条
            popupData.progress?.let { p ->
                LinearProgressIndicator(
                    progress = { p.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = accentColor,
                    trackColor = accentColor.copy(alpha = 0.2f)
                )
            }
        }
    }
}

/** 小卡片状态/标签徽标 */
@Composable
private fun StatusPill(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = fg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 小卡片图标解析：按 iconName 取 Material 图标；为空时按状态取（Web/Check/Close）。
 */
private fun resolvePopupIcon(name: String, status: PopupStatus): ImageVector {
    val n = name.lowercase()
    if (n.isBlank()) return when (status) {
        PopupStatus.COMPLETED -> Icons.Filled.Check
        PopupStatus.CANCELLED -> Icons.Filled.Close
        else -> Icons.Filled.Web
    }
    return when (n) {
        "check","done","complete","ok" -> Icons.Filled.Check
        "close","cancel","error","x" -> Icons.Filled.Close
        "info","information" -> Icons.Filled.Info
        "warning","warn","alert" -> Icons.Filled.Warning
        "star" -> Icons.Filled.Star
        "person","user","account" -> Icons.Filled.Person
        "settings","gear","cog" -> Icons.Filled.Settings
        "favorite","like","heart" -> Icons.Filled.Favorite
        "shopping","cart" -> Icons.Filled.ShoppingCart
        "location","place","map","pin" -> Icons.Filled.LocationOn
        "calendar","date","event" -> Icons.Filled.CalendarToday
        "bolt","flash","speed","fast" -> Icons.Filled.Bolt
        "build","tools","wrench","tool" -> Icons.Filled.Build
        "code","dev","terminal" -> Icons.Filled.Code
        "email","mail" -> Icons.Filled.Email
        "phone","call" -> Icons.Filled.Phone
        "search","find" -> Icons.Filled.Search
        "add","plus","new" -> Icons.Filled.Add
        "rocket","launch" -> Icons.Filled.RocketLaunch
        "lightbulb","idea","tip" -> Icons.Filled.Lightbulb
        "link","url" -> Icons.Filled.Link
        "image","photo","picture" -> Icons.Filled.Image
        "music","audio","sound" -> Icons.Filled.MusicNote
        "video","movie" -> Icons.Filled.VideoLibrary
        "file","doc","document" -> Icons.Filled.Description
        "cloud" -> Icons.Filled.Cloud
        "bell","notification","notify" -> Icons.Filled.Notifications
        "lock","secure","security" -> Icons.Filled.Lock
        "download" -> Icons.Filled.Download
        "upload" -> Icons.Filled.Upload
        "share" -> Icons.Filled.Share
        "edit","pencil","write" -> Icons.Filled.Edit
        "delete","trash","remove" -> Icons.Filled.Delete
        "refresh","sync","reload" -> Icons.Filled.Refresh
        "play" -> Icons.Filled.PlayArrow
        "pause" -> Icons.Filled.Pause
        "home" -> Icons.Filled.Home
        "chat","message","comment" -> Icons.Filled.Chat
        "list","tasks" -> Icons.Filled.List
        "grid","apps" -> Icons.Filled.GridView
        "folder" -> Icons.Filled.Folder
        "tag","label" -> Icons.Filled.Label
        "clock","time" -> Icons.Filled.AccessTime
        "money","currency","coin" -> Icons.Filled.AttachMoney
        "trending","chart","analytics" -> Icons.Filled.TrendingUp
        "wifi" -> Icons.Filled.Wifi
        "battery" -> Icons.Filled.BatteryFull
        else -> Icons.Filled.Web
    }
}

/**
 * 自由可视化弹窗 - AI可以创建任意内容的弹窗，没有格式限制
 */
@Composable
fun VisualPopupDialog() {
    val cs = MaterialTheme.colorScheme
    var currentPopup by remember { mutableStateOf<Pair<String, VisualPopupData>?>(null) }
    var inputValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 事件驱动：监听弹窗队列变化
    LaunchedEffect(Unit) {
        VisualPopupQueue.eventFlow.collect { event ->
            when (event) {
                is VisualPopupQueue.PopupEvent.PopupAdded -> {
                    // 有新弹窗加入，如果当前没有显示弹窗则显示
                    if (currentPopup == null) {
                        val popup = VisualPopupQueue.getCurrentPopup()
                        if (popup != null) {
                            currentPopup = popup
                            // 初始化输入框默认值
                            val defaults = popup.second.inputs.associate { it.id to it.defaultValue }
                            inputValues = defaults
                            // 更新弹窗状态为ACTIVE
                            VisualPopupQueue.updatePopupStatus(popup.first, PopupStatus.ACTIVE)
                        }
                    }
                }
                is VisualPopupQueue.PopupEvent.PopupUpdated -> {
                    // 弹窗状态更新
                    if (currentPopup?.first == event.id) {
                        val popup = VisualPopupQueue.getPopupById(event.id)
                        if (popup != null) {
                            currentPopup = event.id to popup
                        }
                    }
                }
                is VisualPopupQueue.PopupEvent.PopupRemoved -> {
                    // 弹窗被移除
                    if (currentPopup?.first == event.id) {
                        currentPopup = null
                        inputValues = emptyMap()
                    }
                }
            }
        }
    }

    currentPopup?.let { (id, popup) ->
        Dialog(
            onDismissRequest = {
                if (popup.cancelable) {
                    VisualPopupQueue.submitResult(id, PopupResult(null, inputValues, cancelled = true))
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
                        // 修复：close 按钮无条件显示。原逻辑只在 cancelable=true 时显示，
                        // 但 onDismissRequest / dismissOnBackPress 也受 cancelable 控制，
                        // 导致 cancelable=false 时用户完全无法退出。
                        IconButton(
                            onClick = {
                                VisualPopupQueue.submitResult(id, PopupResult(null, inputValues, cancelled = true))
                                currentPopup = null
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(20.dp))
                        }
                    }

                    // 图片（如果有）
                    popup.imageUrl?.let { url ->
                        val safeUrl = remember(url) { url }
                        var imageWebView by remember { mutableStateOf<WebView?>(null) }
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    // 修复：默认 WebViewClient 不拦截 shouldOverrideUrlLoading，
                                    // 用户点图片内的链接会跳出 App。改为永远在 WebView 内加载。
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: android.webkit.WebResourceRequest?
                                        ): Boolean {
                                            // 拦截所有外链跳转，强制在当前 WebView 内加载
                                            request?.url?.let { view?.loadUrl(it.toString()) }
                                            return true
                                        }
                                    }
                                    settings.javaScriptEnabled = false
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    loadUrl(safeUrl)
                                    imageWebView = this
                                }
                            },
                            // 修复：原代码无 DisposableEffect，WebView 仅 detach 不 destroy，
                            // 多次弹图后会泄漏 WebView 内部线程与 Context 引用，最终 OOM。
                            update = { /* no-op */ },
                            onRelease = { wv ->
                                wv.stopLoading()
                                wv.loadUrl("about:blank")
                                wv.destroy()
                                imageWebView = null
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
                                                id,
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
                                            id,
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
