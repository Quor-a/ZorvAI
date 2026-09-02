package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.tools.QuroBrowserController
import com.ai.assistance.quro.core.util.QuroServiceLifecycleOwner
import com.ai.assistance.quro.ui.FloatingMiniWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 对话 / 浏览器「系统级化小窗」管理器（进程级单例）。
 *
 * 与旧版「应用内 Compose 浮层」(FloatingMiniWindow 直接嵌在 ChatScreen 的 Compose 树里，
 * App 退后台即消失) 不同，这里把小窗渲染为真正的系统悬浮窗：
 *   - 通过 WindowManager.addView + LayoutParams(type = TYPE_APPLICATION_OVERLAY) 挂载；
 *   - 挂在应用进程（由 QuroMiniWindowService 前台保活）之上，可浮在桌面 / 其他 App 之上；
 *   - 复用现有 FloatingMiniWindow 容器（拖拽 / 缩放 / 还原 / 关闭）与 ChatScreen 的浏览器 WebView 配置。
 *
 * 权限：依赖 AndroidManifest 已声明的 SYSTEM_ALERT_WINDOW；未授权时上层(ChatScreen)自动降级为应用内浮层。
 */
object QuroMiniWindowManager {

    /** 由 Activity 推送的对话小窗快照（避免跨 Activity 持有 ViewModel）。 */
    data class MiniChatLine(val label: String, val text: String)

    private var appCtx: Context? = null
    private var windowManager: WindowManager? = null

    private var chatLifecycle: QuroServiceLifecycleOwner? = null
    private var browserLifecycle: QuroServiceLifecycleOwner? = null
    private var chatView: ComposeView? = null
    private var browserView: ComposeView? = null

    private val chatLinesState = mutableStateOf<List<MiniChatLine>>(emptyList())
    private val browserUrlState = mutableStateOf<String?>(null)

    /** 对话消息流（Activity 注入）：小窗在 App 退后台时仍实时刷新列表（系统级浮窗核心诉求）。 */
    private var messageFlow: Flow<List<QuroMessage>>? = null
    private var chatScope: CoroutineScope? = null

    /** 当前主题色板（由 Activity 同步，保证悬浮窗与主界面视觉一致）。 */
    private var colorScheme = androidx.compose.material3.darkColorScheme()

    /** Activity 回填回调：悬浮窗内按钮 → 回到主界面状态。 */
    var onExpandChat: (() -> Unit)? = null
    var onCloseChat: (() -> Unit)? = null
    var onNewConversation: (() -> Unit)? = null
    var onRestoreBrowser: ((String) -> Unit)? = null
    var onCloseBrowser: (() -> Unit)? = null
    /** 对话小窗内输入框发送：Activity 回填为 vm.send（完整对话框缩小版，可直接发消息）。 */
    var onSendMessage: ((String) -> Unit)? = null

    // ───────────────────────── 权限 ─────────────────────────

    fun hasOverlayPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun requestOverlayPermission(ctx: Context) {
        if (!hasOverlayPermission(ctx)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.startActivity(intent)
        }
    }

    fun setColorScheme(scheme: androidx.compose.material3.ColorScheme) {
        colorScheme = scheme
    }

    fun setChatLines(lines: List<MiniChatLine>) {
        chatLinesState.value = lines
    }

    /** Activity 注入当前会话消息流（StateFlow），供独立协程作用域在 App 退后台时持续刷新小窗。 */
    fun setMessageSource(flow: Flow<List<QuroMessage>>) {
        messageFlow = flow
    }

    /** 把 QuroMessage 列表映射为小窗快照：跳过隐藏/工具消息，取最近 15 条。 */
    private fun mapLines(msgs: List<QuroMessage>): List<MiniChatLine> {
        return msgs.filter { !it.hidden && it.role != "tool" }
            .takeLast(15)
            .map { m ->
                val label = if (m.role == "user") (m.senderName ?: "我") else "AI"
                MiniChatLine(label, m.content.ifBlank { m.reasoning ?: "" })
            }
    }

    fun isChatShown(): Boolean = chatView != null
    fun isBrowserShown(): Boolean = browserView != null

    // ───────────────────────── 对话小窗 ─────────────────────────

    fun showChat(ctx: Context, scheme: androidx.compose.material3.ColorScheme) {
        ensureCtx(ctx)
        setColorScheme(scheme)
        if (!hasOverlayPermission(ctx)) return
        ensureService(ctx)
        // 幂等：若已有对话浮窗（如再次化小窗），先彻底移除旧视图再重建，避免 early-return 导致"只能化小窗一次"。
        if (chatView != null) hideChat()
        val wm = windowManager ?: return

        val lifecycle = QuroServiceLifecycleOwner().apply { create(); resume() }
        chatLifecycle = lifecycle
        val composeCtx = ContextThemeWrapper(appCtx, getThemeRes())
        val view = ComposeView(composeCtx).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setContent {
                MaterialTheme(colorScheme = colorScheme) {
                    Box(Modifier.fillMaxSize()) {
                        FloatingMiniWindow(
                            title = "对话小窗",
                            initialX = 24.dp, initialY = 120.dp,
                            initialWidth = 300.dp, initialHeight = 420.dp,
                            onRestore = { onExpandChat?.invoke() },
                            onClose = { onCloseChat?.invoke() },
                        ) {
                            ChatMiniContent()
                        }
                    }
                }
            }
        }
        chatView = view
        wm.addView(view, fullScreenParams())
        // 独立协程作用域收集消息流：App 退后台时 ChatScreen 的 LaunchedEffect 会暂停，
        // 这里用托管作用域保证化小窗列表持续刷新（系统级浮窗应浮于其他 App 之上仍可用）。
        chatScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        messageFlow?.let { f ->
            chatScope?.launch {
                f.collect { msgs -> setChatLines(mapLines(msgs)) }
            }
        }
    }

    fun hideChat() {
        try {
            chatView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        chatView = null
        chatLifecycle?.destroy()
        chatLifecycle = null
        chatScope?.cancel()
        chatScope = null
        maybeStopService()
    }

    // ───────────────────────── 浏览器小窗 ─────────────────────────

    fun showBrowser(ctx: Context, url: String, scheme: androidx.compose.material3.ColorScheme) {
        ensureCtx(ctx)
        setColorScheme(scheme)
        if (!hasOverlayPermission(ctx)) return
        ensureService(ctx)
        // 已显示则先移除再重建（保证最新 url 生效）
        if (browserView != null) hideBrowser()
        val wm = windowManager ?: return

        val lifecycle = QuroServiceLifecycleOwner().apply { create(); resume() }
        browserLifecycle = lifecycle
        val composeCtx = ContextThemeWrapper(appCtx, getThemeRes())
        val view = ComposeView(composeCtx).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setContent {
                MaterialTheme(colorScheme = colorScheme) {
                    Box(Modifier.fillMaxSize()) {
                        FloatingMiniWindow(
                            title = "浏览器小窗",
                            initialX = 40.dp, initialY = 150.dp,
                            initialWidth = 320.dp, initialHeight = 400.dp,
                            onRestore = {
                                val u = browserUrlState.value
                                if (u != null) onRestoreBrowser?.invoke(u)
                            },
                            onClose = { onCloseBrowser?.invoke() },
                        ) {
                            BrowserMiniContent(url)
                        }
                    }
                }
            }
        }
        browserView = view
        browserUrlState.value = url
        wm.addView(view, fullScreenParams())
    }

    fun hideBrowser() {
        try {
            browserView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }
        browserView = null
        browserLifecycle?.destroy()
        browserLifecycle = null
        maybeStopService()
    }

    /** 服务进程销毁（进程将死）：兜底移除所有悬浮窗视图。 */
    fun onServiceDestroyed() {
        try {
            chatView?.let { windowManager?.removeView(it) }
            browserView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        chatView = null
        browserView = null
        chatLifecycle?.destroy()
        browserLifecycle?.destroy()
        chatLifecycle = null
        browserLifecycle = null
        chatScope?.cancel()
        chatScope = null
    }

    // ───────────────────────── 内部 Composable 内容 ─────────────────────────

    @Composable
    private fun ChatMiniContent() {
        val lines by chatLinesState
        var inputText by remember { mutableStateOf(TextFieldValue("")) }
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                reverseLayout = true,
            ) {
                items(lines.size) { idx ->
                    val m = lines[idx]
                    Text(
                        "${m.label}：${m.text.take(200)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
            // 输入框：完整对话框缩小版，可直接在此输入并发送消息
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("输入消息…", fontSize = 11.sp) },
                    textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                )
                TextButton(onClick = {
                    val t = inputText.text.trim()
                    if (t.isNotEmpty()) {
                        onSendMessage?.invoke(t)
                        inputText = TextFieldValue("")
                    }
                }) {
                    Text("发送")
                }
            }
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onNewConversation?.invoke() }) {
                    Text("新建对话")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onExpandChat?.invoke() }) {
                    Text("展开对话")
                }
            }
        }
    }

    @Composable
    private fun BrowserMiniContent(url: String) {
        val webSchemes = setOf("http", "https", "file", "about", "data", "javascript")
        var currentUrl by remember { mutableStateOf(TextFieldValue(url)) }
        var wvRef by remember { mutableStateOf<WebView?>(null) }
        Column(Modifier.fillMaxSize()) {
            // 地址栏：完整浏览器缩小版，可直接输入网址导航
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = currentUrl,
                    onValueChange = { currentUrl = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("输入网址…", fontSize = 11.sp) },
                    textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                )
                TextButton(onClick = {
                    val raw = currentUrl.text.trim()
                    if (raw.isNotEmpty()) {
                        val final = if (!raw.contains("://")) "https://$raw" else raw
                        wvRef?.loadUrl(final)
                        browserUrlState.value = final
                        currentUrl = TextFieldValue(final)
                    }
                }) {
                    Text("前往")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { c ->
                    WebView(c).apply {
                        wvRef = this
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadsImagesAutomatically = true
                            // 化小窗需随窗口自由缩放：禁用 wide viewport / overview 缩放，
                            // 让视口宽度 = WebView 实际宽度，页面随窗口尺寸 reflow（而非锁定固定比例）。
                            loadWithOverviewMode = false
                            useWideViewPort = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = true
                            javaScriptCanOpenWindowsAutomatically = true
                            defaultTextEncodingName = "utf-8"
                        }
                        QuroBrowserController.attach(this)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                                QuroBrowserController.markPageStarted(u)
                                if (!u.isNullOrEmpty()) {
                                    browserUrlState.value = u
                                    currentUrl = TextFieldValue(u)
                                }
                            }

                            override fun onPageFinished(view: WebView?, u: String?) {
                                QuroBrowserController.markPageFinished(u)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val reqUri = request?.url ?: return false
                                val scheme = reqUri.scheme?.lowercase() ?: return false
                                if (scheme in webSchemes) return false
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, reqUri)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    c.startActivity(intent)
                                }
                                return true
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView?, u: String?): Boolean {
                                if (u.isNullOrEmpty()) return false
                                val parsed = runCatching { Uri.parse(u) }.getOrNull() ?: return false
                                val scheme = parsed.scheme?.lowercase() ?: return false
                                if (scheme in webSchemes) return false
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, parsed)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    c.startActivity(intent)
                                }
                                return true
                            }
                        }
                        // 化小窗同样需要 WebChromeClient：补齐 onReceivedTitle → markTitle，
                        // 否则化小窗后 status()/snapshot() 的 title 永远为空（同 ChatScreen 修复点）。
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrEmpty()) QuroBrowserController.markTitle(title)
                            }
                        }
                        loadUrl(url)
                    }
                },
                onRelease = {
                    QuroBrowserController.detach(it)
                    it.destroy()
                },
            )
        }
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private fun ensureCtx(ctx: Context) {
        if (appCtx == null) {
            appCtx = ctx.applicationContext
            windowManager = appCtx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
    }

    private fun ensureService(ctx: Context) {
        QuroMiniWindowService.start(ctx.applicationContext)
    }

    private fun maybeStopService() {
        if (chatView == null && browserView == null) {
            appCtx?.let { QuroMiniWindowService.stop(it) }
        }
    }

    /** 系统级浮窗还原/关闭时把 App 带回前台（moveTaskToFront），使「返回全屏」平滑、浮层即时移除。 */
    fun bringAppToForeground() {
        val c = appCtx ?: return
        kotlin.runCatching {
            val intent = Intent(c, QuroMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            c.startActivity(intent)
        }
    }

    private fun fullScreenParams(): WindowManager.LayoutParams {
        val dm = appCtx?.resources?.displayMetrics
        val w = dm?.widthPixels ?: 1080
        val h = dm?.heightPixels ?: 1920
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            w,
            h,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun getThemeRes(): Int {
        val ctx = appCtx ?: return android.R.style.Theme_Material_Light
        return ctx.resources.getIdentifier("Theme.Quro", "style", ctx.packageName).let {
            if (it != 0) it else android.R.style.Theme_Material_Light
        }
    }
}

/**
 * 对话 / 浏览器系统级悬浮窗保活服务。
 *
 * 仅用于「前台保活」：让进程在 App 退到后台 / 切到其他软件时仍存活，
 * 从而 WindowManager 上挂载的 TYPE_APPLICATION_OVERLAY 小窗能持续浮在桌面与其他 App 之上。
 * 悬浮窗视图本身由 QuroMiniWindowManager（进程级单例）持有与增删，本服务不触碰视图。
 *
 * 采用 specialUse 前台服务类型（与终端保活服务一致），并在 Manifest 中声明对应 subtype。
 */
class QuroMiniWindowService : Service() {

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            QuroMiniWindowManager.hideChat()
            QuroMiniWindowManager.hideBrowser()
            stopSelf()
            return START_NOT_STICKY
        }
        // 保活：进程存活期间小窗持续显示
        return START_STICKY
    }

    override fun onDestroy() {
        QuroMiniWindowManager.onServiceDestroyed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(
            CHANNEL_ID,
            "对话/浏览器悬浮小窗",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(chan)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, QuroMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, QuroMiniWindowService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("对话/浏览器悬浮小窗")
            .setContentText("Zorv AI 系统级悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_delete, "关闭全部悬浮窗", stopPi)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 8812
        private const val CHANNEL_ID = "quro_mini_window"
        const val ACTION_STOP = "com.ai.assistance.quro.action.MINI_WINDOW_STOP"

        fun start(context: Context) {
            val intent = Intent(context, QuroMiniWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, QuroMiniWindowService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
