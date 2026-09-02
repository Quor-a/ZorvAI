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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import com.ai.assistance.quro.core.tools.QuroBrowserViewHost
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
    // 视图是否已挂载到 WindowManager：show/hide 只 add/remove，避免每次重建与重复 addView 异常。
    private var chatAdded = false
    private var browserAdded = false
    /** 浏览器小窗当前已加载的目标网址：AI 再次打开不同链接时用于判断是否需要原地导航（不重建视图）。 */
    private var browserShownUrl: String? = null

    /** 浮窗窗口的 LayoutParams：拖拽/缩放时更新 x/y 并 updateViewLayout（窗口仅包围面板，非满屏）。 */
    private var chatParams: WindowManager.LayoutParams? = null
    private var browserParams: WindowManager.LayoutParams? = null

    private val chatLinesState = mutableStateOf<List<MiniChatLine>>(emptyList())

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
        val wm = windowManager ?: return
        // 复用持久 ComposeView（不每次重建）：仅首次构建，之后 show/hide 只 add/remove 视图，
        // 彻底消除「化小窗卡顿」——对标可视化弹窗：浮窗视图常驻、最小化只是移除视图，不重建不重排、不重启服务。
        if (chatView == null) {
            val lifecycle = QuroServiceLifecycleOwner().apply { create(); resume() }
            chatLifecycle = lifecycle
            val composeCtx = ContextThemeWrapper(appCtx, getThemeRes())
            val view = ComposeView(composeCtx).apply {
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    MaterialTheme(colorScheme = colorScheme) {
                        // 窗口本身已是「仅包围面板」尺寸（WRAP_CONTENT，见 miniWindowParams），
                        // 面板填满窗口；初始屏幕位置由 chatParams.x/y 决定，拖拽由 onDrag 移动整窗，
                        // 缩放由 onResize 触发重新测量。改版后浮窗不再满屏拦截，面板外区域点击穿透到下层 App。
                        FloatingMiniWindow(
                            title = "对话小窗",
                            initialX = 0.dp, initialY = 0.dp,
                            initialWidth = 300.dp, initialHeight = 420.dp,
                            onRestore = { onExpandChat?.invoke() },
                            onClose = { onCloseChat?.invoke() },
                            onDrag = { dx, dy -> moveMiniWindow(chatParams, chatView, dx, dy) },
                            onResize = { updateMiniLayout(chatParams, chatView) },
                        ) {
                            ChatMiniContent()
                        }
                    }
                }
            }
            chatView = view
            chatAdded = false
            // 独立协程作用域收集消息流：App 退后台时 ChatScreen 的 LaunchedEffect 会暂停，
            // 这里用托管作用域保证化小窗列表持续刷新（系统级浮窗应浮于其他 App 之上仍可用）。
            // 视图常驻期间协程持续运行（最小化↔还原不取消），浮窗列表始终实时。
            chatScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            messageFlow?.let { f ->
                chatScope?.launch {
                    f.collect { msgs -> setChatLines(mapLines(msgs)) }
                }
            }
        }
        // 已挂载则跳过（避免重复 addView 抛异常）；首次或已移除则重新挂回 WindowManager。
        if (!chatAdded) {
            // 窗口仅包围面板（WRAP_CONTENT，非满屏），面板外点击穿透到下层 App（抖音/快手等照常可操作）。
            chatParams = miniWindowParams(dpToPx(24f), dpToPx(120f))
            wm.addView(chatView, chatParams)
            chatAdded = true
        }
    }

    fun hideChat() {
        // 仅移除浮窗视图（保留 ComposeView/生命周期/协程），最小化↔还原瞬时切换不重建、不重启服务。
        if (chatAdded) {
            try {
                chatView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            }
            chatAdded = false
        }
    }

    // ───────────────────────── 浏览器小窗 ─────────────────────────

    fun showBrowser(ctx: Context, url: String, scheme: androidx.compose.material3.ColorScheme) {
        ensureCtx(ctx)
        setColorScheme(scheme)
        if (!hasOverlayPermission(ctx)) return
        ensureService(ctx)
        val wm = windowManager ?: return
        // 复用持久 ComposeView（不每次重建）：仅首次构建，之后 show/hide 只 add/remove 视图。
        // WebView 由 QuroBrowserViewHost 共享单例承载，化小窗只把它从全屏容器重挂到浮窗容器
        // （不新建、不整页重载），浮窗视图常驻使还原↔化小窗零重建、零卡顿（对标可视化弹窗）。
        if (browserView == null) {
            val lifecycle = QuroServiceLifecycleOwner().apply { create(); resume() }
            browserLifecycle = lifecycle
            val composeCtx = ContextThemeWrapper(appCtx, getThemeRes())
            val view = ComposeView(composeCtx).apply {
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    MaterialTheme(colorScheme = colorScheme) {
                        // 窗口仅包围面板（WRAP_CONTENT），面板外点击穿透到下层 App；拖拽移动整窗、缩放重测量。
                        FloatingMiniWindow(
                            title = "浏览器小窗",
                            initialX = 0.dp, initialY = 0.dp,
                            initialWidth = 320.dp, initialHeight = 400.dp,
                            onRestore = {
                                val u = QuroBrowserViewHost.uiState.value.url
                                if (u.isNotEmpty()) onRestoreBrowser?.invoke(u)
                            },
                            onClose = { onCloseBrowser?.invoke() },
                            onDrag = { dx, dy -> moveMiniWindow(browserParams, browserView, dx, dy) },
                            onResize = { updateMiniLayout(browserParams, browserView) },
                        ) {
                            BrowserMiniContent(url)
                        }
                    }
                }
            }
            browserView = view
            browserAdded = false
            browserShownUrl = url
        }
        if (!browserAdded) {
            // 窗口仅包围面板（WRAP_CONTENT）：NOT_FOCUSABLE 不抢下层 App 焦点，NOT_TOUCH_MODAL
            // 让面板外的整片区域点击穿透到抖音/快手等下层 App，可边看浮窗边操作其它应用。
            browserParams = miniWindowParams(dpToPx(40f), dpToPx(150f))
            wm.addView(browserView, browserParams)
            browserAdded = true
        } else if (browserShownUrl != url) {
            // 已浮出且换了网址（AI 再次打开其它链接 / 多次打开浏览器）：
            // 共享 WebView 原地导航，不重建视图、不整页重载，地址栏由 uiState 同步。避免「全屏+小窗并存」。
            QuroBrowserViewHost.get()?.loadUrl(url)
            browserShownUrl = url
        }
    }

    fun hideBrowser() {
        // 仅移除浮窗视图（保留 ComposeView/生命周期），最小化↔还原瞬时切换不重建、不重启服务。
        if (browserAdded) {
            try {
                browserView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            }
            browserAdded = false
        }
        browserShownUrl = null
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
        chatAdded = false
        browserAdded = false
        chatLifecycle?.destroy()
        browserLifecycle?.destroy()
        chatLifecycle = null
        browserLifecycle = null
        chatScope?.cancel()
        chatScope = null
    }

    /** 彻底释放：移除浮窗视图、销毁 ComposeView 与生命周期、停止保活服务。
     *  仅在对话界面销毁（App 退出/进程将死）时由 ChatScreen 调用，避免离开界面后残留前台通知。
     *  日常「化小窗↔还原」走 show/hide（视图常驻、服务保活），不会触发本方法。 */
    fun release() {
        hideChat()
        hideBrowser()
        // 先释放重量级对象（ComposeView/生命周期/协程/消息引用），让 GC 在关停服务前有机会回收，
        // 避免紧随其后的 startService 在进程已濒临堆上限时 OOM 崩进程。
        chatView = null
        browserView = null
        chatLifecycle?.destroy()
        browserLifecycle?.destroy()
        chatLifecycle = null
        browserLifecycle = null
        chatScope?.cancel()
        chatScope = null
        chatLinesState.value = emptyList()
        messageFlow = null
        // 关停保活服务：若进程已濒临 OOM，startService 可能抛 OutOfMemoryError；
        // 服务会随进程被系统回收，吞掉异常避免主线程崩溃。
        try {
            appCtx?.let { QuroMiniWindowService.stop(it) }
        } catch (_: Throwable) {
            // 忽略：内存耗尽，系统会回收服务与前台通知
        }
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
        val bs = QuroBrowserViewHost.collectUiState().value
        var currentUrl by remember { mutableStateOf(TextFieldValue(url)) }
        // 地址栏同步共享状态（导航导致 uiState.url 变化时回写；打字时 uiState.url 不变，不覆盖输入）
        LaunchedEffect(bs.url) {
            if (bs.url.isNotEmpty()) currentUrl = TextFieldValue(bs.url)
        }
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
                        // 【修复】关键词/非 URL 文本 → 走搜索引擎（对齐 QuroBrowserScreen 的 resolveBrowserInput），
                        // 不再被当域名补 https:// 前缀导致 ERR_NAME_NOT_RESOLVED / 被当成网页。
                        val target = QuroBrowserController.resolveBrowserInput(raw)
                        // 共享 WebView：化小窗始终复用同一个，前往即导航（不重建/不重载）
                        QuroBrowserViewHost.get()?.loadUrl(target)
                        // uiState.url 由通用 client 的 onPageStarted 自动更新；currentUrl 仅作即时回显。
                        currentUrl = TextFieldValue(target)
                    }
                }) {
                    Text("前往")
                }
            }
            // 加载错误提示（点击重试）：让「部分网页打不开」有可见反馈，而非静默失败
            bs.loadError?.let { err ->
                Text(
                    "加载失败：$err（点此重试）",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { QuroBrowserViewHost.get()?.reload() }
                        .padding(6.dp),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { c ->
                    // 复用全局唯一浏览器 WebView：化小窗只是把它从全屏容器重挂到浮窗容器，
                    // 不新建 WebView、不整页 loadUrl 重载 —— 彻底消除「化小窗卡顿」（对标 operit）。
                    val container = FrameLayout(c)
                    QuroBrowserViewHost.getOrCreate(c)
                    // 首次打开才加载初始 url；已存在（从全屏重挂）则零重载。
                    QuroBrowserViewHost.loadIfNeeded(url)
                    QuroBrowserViewHost.bindFloat(container)
                    container
                },
                onRelease = {
                    // 仅解绑浮窗容器；若主浏览器仍在则移回全屏，真正关闭才销毁。
                    QuroBrowserViewHost.unbindFloat(it as ViewGroup)
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

    /** 系统级浮窗还原/关闭时把 App 带回前台（moveTaskToFront），使「返回全屏」平滑、浮层即时移除。
     *  仅当 App 确实不在前台时才 startActivity：系统级浮窗本就浮在宿主 Activity 之上、App 一直前台，
     *  此时强制 startActivity 会触发 Activity 重排/重启，导致「返回全屏」卡顿，故直接跳过。 */
    fun bringAppToForeground() {
        val c = appCtx ?: return
        if (isAppForeground()) return
        kotlin.runCatching {
            val intent = Intent(c, QuroMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            c.startActivity(intent)
        }
    }

    /** App 是否处于前台（进程 importance = FOREGROUND）。系统级浮窗显示时宿主 Activity 仍前台，故视为前台。 */
    private fun isAppForeground(): Boolean {
        val c = appCtx ?: return false
        return kotlin.runCatching {
            @Suppress("DEPRECATION")
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val pkg = c.packageName
            for (proc in am.runningAppProcesses ?: emptyList()) {
                if (proc.processName == pkg &&
                    proc.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                ) {
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    /** AI 操控浏览器时把浏览器展示给用户：默认化小窗（符合移动端——默认小窗、手动全屏）。
     *  经 QuroBrowserBridge → ChatScreen 设 browserFloatUrl → 系统级浮窗 showBrowser（NOT_FOCUSABLE，
     *  不抢对话框焦点）。复用同一共享 WebView，绝不跨窗口重建，化小窗/还原均零卡顿。 */
    fun showBrowserFromAi(context: Context, url: String) {
        // 统一经 QuroBrowserBridge 把网址交给 ChatScreen：ChatScreen 默认设 browserFloatUrl（小窗），
        // WebView 始终仅一个实例，化小窗/还原/多次打开均复用，零重建零整页重载。
        com.ai.assistance.quro.core.QuroBrowserBridge.open(url)
    }

    /**
     * 浮窗窗口参数（对话/浏览器共用）：窗口仅包围小窗面板本身（WRAP_CONTENT，按面板尺寸自动测量），
     * 而非满屏。配合 NOT_FOCUSABLE + NOT_TOUCH_MODAL，行为与「语音球」一致——浮于其它 App 之上却不拦截：
     * - NOT_FOCUSABLE：浮窗不抢下层 App（抖音/快手）焦点，其输入框/键盘照常工作；
     * - NOT_TOUCH_MODAL：面板「边界之外」的整片区域点击穿透到下层 App，可边看浮窗边操作其它应用；
     * - 面板内的按钮/滚动/拖拽/缩放仍正常响应（拖拽由 moveMiniWindow 移动整窗，缩放由 updateMiniLayout 重测）。
     * 初始屏幕坐标由 x/y 决定；拖拽时 moveMiniWindow 更新 x/y 并 updateViewLayout。
     */
    private fun miniWindowParams(xPx: Int, yPx: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xPx
            y = yPx
        }
    }

    /** 拖拽时移动整个浮窗窗口（而非面板内部 offset），并夹紧在屏幕内避免丢失。 */
    private fun moveMiniWindow(
        params: WindowManager.LayoutParams?,
        view: View?,
        dxPx: Float,
        dyPx: Float,
    ) {
        val p = params ?: return
        val wm = windowManager ?: return
        val dm = appCtx?.resources?.displayMetrics
        val screenW = dm?.widthPixels ?: 1080
        val screenH = dm?.heightPixels ?: 1920
        p.x = (p.x + dxPx.toInt()).coerceIn(0, (screenW - 40).coerceAtLeast(0))
        p.y = (p.y + dyPx.toInt()).coerceIn(0, (screenH - 40).coerceAtLeast(0))
        try {
            if (view != null) wm.updateViewLayout(view, p)
        } catch (_: Throwable) {
        }
    }

    /** 缩放后重新测量窗口（WRAP_CONTENT 窗口需主动 updateViewLayout 才按新面板尺寸刷新）。 */
    private fun updateMiniLayout(params: WindowManager.LayoutParams?, view: View?) {
        val wm = windowManager ?: return
        try {
            if (params != null && view != null) wm.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun dpToPx(dp: Float): Int {
        val density = appCtx?.resources?.displayMetrics?.density ?: 1f
        return (dp * density).toInt()
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
            // 进程已濒临 OOM 时 startService 可能抛 OutOfMemoryError；服务会随进程被系统回收，
            // 吞掉异常避免主线程崩溃（release 路径已先行释放重量级对象以争取回收空间）。
            try {
                val intent = Intent(context, QuroMiniWindowService::class.java).apply { action = ACTION_STOP }
                context.startService(intent)
            } catch (_: Throwable) {
                // 忽略：内存耗尽，系统会回收服务与前台通知
            }
        }
    }
}
