package com.ai.assistance.quro.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.R
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.util.qstr

/**
 * Web UI 对话框 —— **ZorvAI 对话框的投影**。
 *
 * ── 定位（用户 2026-10-13 两次纠偏后定稿）──────────────────────────────
 * 「web ui 是 zorvAI 的投影」，不是「另外设计 UI 界面」。
 * 所以这里的 WebView 只是一个**投影片**：显示的是 [QuroWebUiServer] 投影出来的
 * 同一个会话、同一套块（见 [WebUiProjection]），不是另一个聊天应用。
 *
 * 它与主对话框**共用同一个 [QuroChatViewModel]**：在这里发的消息，主对话框里看得见；
 * 主对话框里的流式输出，这里也在滚。所谓投影就是这一件事。
 *
 * ── 必须给出对外链接（用户原话：「外部怎么端口 web ui 链接都没有」）────
 * 顶栏常驻一条可复制、可在浏览器打开的链接。它在同一 Wi-Fi 下**能在别的设备上打开**，
 * 这才是「投影」成立的前提——只能在这台手机里看的 WebView 不算投影。
 *
 * ── zxwidget 不在这里 ────────────────────────────────────────────────
 * 互动UI界面是独立模块（`assets/zx/`），宿主是原生对话框。本对话框既不提供它的
 * 运行时、也不发它的资源、也不承担它的交互回传。
 * 它在投影里只表现为消息流中的「一块互动界面（组件名）」，如实呈现、不做编译。
 *
 * @param vm          宿主聊天 ViewModel —— 与主对话框同一个会话。
 * @param dark        深色主题。
 * @param modelName   顶栏模型名（投影顶栏字段，与原生 `ChatTopBar` 同源）。
 * @param personaName 顶栏人格名。
 * @param onDismiss   关闭。
 */
@Composable
fun WebUiDialog(
    vm: QuroChatViewModel,
    dark: Boolean,
    modelName: String = "",
    personaName: String = "",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val server = remember(context) { QuroWebUiServer(context, vm) }

    /** 0 = 启动中；>0 = 就绪；<0 = 启动失败 */
    var serverPort by remember { mutableStateOf(0) }
    var startErr by remember { mutableStateOf<String?>(null) }

    /** 页面是否已加载完成（`onPageFinished` 置位）。区分「还在加载」与「加载完但空白」。 */
    var pageLoaded by remember { mutableStateOf(false) }

    /** 主文档加载失败的原因；null = 没失败。 */
    var pageErr by remember { mutableStateOf<String?>(null) }

    /** 顶栏链接（优先局域网直链）。 */
    var link by remember { mutableStateOf("") }

    var copied by remember { mutableStateOf(false) }

    // 服务随对话框生灭：关掉即停，不留常驻监听端口
    DisposableEffect(server) {
        serverPort = runCatching { server.start() }.getOrElse {
            QuroDiag.log(TAG, "投影服务启动失败：${it.javaClass.simpleName} ${it.message}")
            startErr = it.message ?: it.javaClass.simpleName
            -1
        }
        onDispose { server.stop() }
    }

    // 真机日志里的锚点：搜 "Web UI 投影对话框已打开"
    LaunchedEffect(serverPort) {
        if (serverPort > 0) {
            link = server.accessUrl
            QuroDiag.log(
                TAG,
                "Web UI 投影对话框已打开 回环=${server.loopbackUrl} 局域网=${server.lanUrl ?: "（不可用）"}"
            )
        }
    }

    LaunchedEffect(dark) { server.dark = dark }
    LaunchedEffect(modelName) { server.modelName = modelName }
    LaunchedEffect(personaName) { server.personaName = personaName }
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
            Column(Modifier.fillMaxSize()) {

                // ── 顶栏：这是投影的「对外入口」，不是装饰 ──
                // 没有它，用户拿不到可复制到别的设备打开的地址 —— 那正是
                // 「外部怎么端口 web ui 链接都没有」这条反馈的直接答复。
                LinkBar(
                    link = link,
                    copied = copied,
                    onCopy = {
                        copyToClipboard(context, link)
                        copied = true
                    },
                    onOpen = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { QuroDiag.log(TAG, "外部打开失败：${it.message}") }
                    },
                    onDismiss = onDismiss,
                )

                Box(Modifier.fillMaxSize()) {
                    val err = startErr
                    when {
                        err != null -> Text(
                            qstr(R.string.qk_04075, err),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        )

                        serverPort <= 0 -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                        else -> {
                            // WebView 一律加载**回环**地址：不经网卡最稳，
                            // 局域网直链只用于「复制给别人打开」。
                            WebPane(
                                url = server.loopbackUrl,
                                onLoaded = { pageLoaded = true; pageErr = null },
                                onError = { pageErr = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (dark) Color(0xFF16130F) else Color(0xFFF4F1EA)),
                            )
                            val perr = pageErr
                            when {
                                // 失败：把原因写在界面上。否则这里只是一块空白色块，
                                // 观感与「功能没做」完全一致，无法定位。
                                perr != null -> Overlay {
                                    Text(
                                        qstr(R.string.qk_04077, server.loopbackUrl, perr),
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                    )
                                }

                                !pageLoaded -> Overlay {
                                    CircularProgressIndicator(Modifier.padding(4.dp))
                                    Text(
                                        qstr(R.string.qk_04078, server.loopbackUrl),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
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

/**
 * 顶栏：投影的对外链接 + 复制 / 浏览器打开 / 关闭。
 * 只放「不这么放用户就拿不到」的东西，不加任何自创装饰。
 */
@Composable
private fun LinkBar(
    link: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    qstr(R.string.qk_04079),
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    link.ifBlank { qstr(R.string.qk_04080) },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = cs.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (link.isNotBlank()) {
                IconButton(onClick = onCopy) {
                    Icon(
                        // 🔴 用 Material 图标而不是 LucideIcon(name)：
                        //    本仓 LucideIcon 只认 iconRes() 里登记过的名字，写不存在的名字
                        //    会**静默**落到 else -> ic_x，界面上就是一个大叉（这里踩过）。
                        //    复制 / 新窗口这两个语义在登记表里没有，就别硬套。
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        qstr(if (copied) R.string.qk_04082 else R.string.qk_04081),
                        tint = if (copied) cs.primary else cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpen) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        qstr(R.string.qk_04083),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, qstr(R.string.qk_00065), tint = cs.onSurfaceVariant)
            }
        }
    }
}

/** 加载进度 / 加载失败提示的居中浮层。 */
@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) { content() }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ZorvAI Web UI", text))
    }
}

/**
 * 只是个 WebView 外壳：设置、加载一次、销毁。
 *
 * 刻意**不做**任何 `evaluateJavascript` 下发——页面自己轮询 `api/snapshot`，
 * 宿主不再拼 JS 字符串，也就不再有「转义漏一个字符 → 整页白」这一整类故障。
 *
 * 但 [WebViewClient] 必须装：不装就收不到 `onPageFinished` / `onReceivedError`，
 * 页面失败就等于**静默空白色块**（WebView 背景透明，露出父容器底色）。
 * 这里只把事实转成日志 + 界面状态，不改任何加载行为。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPane(
    url: String,
    onLoaded: () -> Unit,
    onError: (String) -> Unit,
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
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.loadsImagesAutomatically = true
                // 页面自带 viewport meta，这里必须 useWideViewPort=true 才会按
                // width=device-width 排版；同时关掉 overview 缩放，免得被整体缩小
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.textZoom = 100
                setBackgroundColor(0x00000000)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        QuroDiag.log(TAG, "页面加载完成：$u")
                        onLoaded()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // 🔴 只认主文档的错误。子资源（favicon、某张图）失败不该把整页判死，
                        //    否则一次无害的 404 会让界面显示成「加载失败」，比空白更误导。
                        if (request?.isForMainFrame != true) return
                        val d = "errorCode=${error?.errorCode} ${error?.description}"
                        QuroDiag.log(TAG, "页面加载失败：${request.url} → $d")
                        onError(d)
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        response: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame != true) return
                        val d = "HTTP ${response?.statusCode}"
                        QuroDiag.log(TAG, "页面返回错误：${request.url} → $d")
                        onError(d)
                    }
                }
            }
        },
        // 端口是 DisposableEffect 里异步拿到的，factory 期间可能还是 0，
        // 所以真正的加载放在 update 里、且只做一次
        update = { wv ->
            if (!loaded && url.isNotBlank()) {
                loaded = true
                QuroDiag.log(TAG, "开始加载 $url")
                wv.loadUrl(url)
            }
        },
        onRelease = { wv -> wv.destroy() },
    )
}

private const val TAG = "QuroWebUi"
