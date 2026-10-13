package com.ai.assistance.quro.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.R
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.util.qstr

/**
 * Web UI 对话框 —— ZorvAI 对话框的 Web 复制品。
 *
 * 定位（用户 2026-10-13 拍板）：ZorvAI 对话框有的功能这里全有——消息流、Markdown、
 * 流式输出、思考过程、工具调用、附件、输入框、停止/新对话——只是整套界面用网页实现。
 * 参照物：DeepSeek Harness 的 Web 对话框（`dsh web`）、operit 的外部链接对话框。
 *
 * 形态：宿主内起一个**只绑 127.0.0.1** 的本地服务（[QuroWebChatServer]），
 * WebView 指向它，前端从 `assets/web-chat/` 按普通静态资源加载。
 *
 * 上一版为什么推倒重来：把 195 KB 组件契约内联进单个 HTML、`loadDataWithBaseURL` 硬灌、
 * 宿主拼 JS 字符串做增量投影。三个后果——页面因为一个转义字符就整体白掉；体积翻倍；
 * 而且它只能是「一块塞进 WebView 的 HTML」，成不了**链接**，也就谈不上「外部链接对话框」。
 *
 * zxwidget 在这里的角色：**动态交互界面**，只是被本对话框所支持的一个能力
 * （另一个支持它的是 ZorvAI 原生对话框）。它住在 `assets/zx/zxwidget.js`，
 * 不属于 Web UI，也不属于 ZorvAI。
 *
 * @param vm        宿主聊天 ViewModel——与 ZorvAI 主对话框**同一个会话**，
 *                  不是另起炉灶：消息、工具、历史实时同步（前端轮询同一份快照）。
 * @param dark      深色主题。
 * @param onDismiss 关闭。
 */
@Composable
fun ZxWebChatDialog(
    vm: QuroChatViewModel,
    dark: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val server = remember(context) { QuroWebChatServer(context, vm) }

    /** 0 = 启动中；>0 = 就绪；<0 = 启动失败 */
    var serverPort by remember { mutableStateOf(0) }
    var startErr by remember { mutableStateOf<String?>(null) }

    // 服务随对话框生灭：关掉即停，不留常驻监听端口
    DisposableEffect(server) {
        serverPort = runCatching { server.start() }.getOrElse {
            startErr = it.message ?: it.javaClass.simpleName
            -1
        }
        onDispose { server.stop() }
    }

    LaunchedEffect(dark) { server.dark = dark }
    LaunchedEffect(Unit) { server.title = qstr(R.string.qk_04074) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxSize(0.97f),
        ) {
            Box(Modifier.fillMaxSize()) {
                val err = startErr
                when {
                    err != null -> Text(
                        qstr(R.string.qk_04075, err),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                    )

                    serverPort <= 0 -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    else -> WebPane(
                        url = server.url,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (dark) Color(0xFF0D0F13) else Color(0xFFF6F7F9)),
                    )
                }

                // 关闭按钮浮在右上角；网页顶栏已预留 52px 右侧内边距，不会压住内容
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        qstr(R.string.qk_00065),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 只是个 WebView 外壳：设置、加载一次、销毁。
 *
 * 刻意**不做**任何 `evaluateJavascript` 下发——页面自己轮询 `api/snapshot`，
 * 宿主不再拼 JS 字符串，也就不再有「转义漏一个字符 → 整页白」这一整类故障。
 * 同理不再需要 `onPageFinished` 门控：没有东西需要在页面就绪后灌进去，
 * 主题与标题都走快照字段。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPane(
    url: String,
    modifier: Modifier = Modifier,
) {
    var loaded by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.defaultTextEncodingName = "UTF-8"
                // 页面在 http://127.0.0.1 上；附件是 file:// 的，放行同源读取
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.loadsImagesAutomatically = true
                // 页面自带 viewport meta，这里必须 useWideViewPort=true 才会按
                // width=device-width 排版；同时关掉 overview 缩放，免得被整体缩小
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.textZoom = 100
                setBackgroundColor(0x00000000)
            }
        },
        // 端口是 DisposableEffect 里异步拿到的，factory 期间可能还是 0，
        // 所以真正的加载放在 update 里、且只做一次
        update = { wv ->
            if (!loaded && url.isNotBlank()) {
                loaded = true
                wv.loadUrl(url)
            }
        },
        onRelease = { wv -> wv.destroy() },
    )
}
