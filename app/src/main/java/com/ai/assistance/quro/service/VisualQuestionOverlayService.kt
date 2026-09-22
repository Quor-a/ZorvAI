package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.tools.VisualActionQueue
import com.ai.assistance.quro.core.tools.VisualQuestionQueue
import com.ai.assistance.quro.core.tools.VisualPendingAction
import com.ai.assistance.quro.core.tools.VisualPendingQuestion
import com.ai.assistance.quro.core.util.QuroServiceLifecycleOwner
import com.ai.assistance.quro.ui.VisualActionCard
import com.ai.assistance.quro.ui.VisualQuestionCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 可视化「询问 / 操作」悬浮窗服务。
 *
 * 为什么要这个服务：
 * 询问弹窗原本只是挂在 Activity 的 Compose 树里（`VisualDialogs()` → `Dialog`），
 * 于是它只在**挂载它的那个界面**生效——在 GenUI Agent 里发起的询问会跑到主对话框去弹，
 * 而 GenUI 界面里只剩一段点不了的静态文字。更糟的是 `eventFlow` 用的是 Channel，
 * 多个宿主竞争消费同一条事件，谁先抢到谁显示，另一个永远收不到。
 *
 * 现在改成系统级悬浮窗（TYPE_APPLICATION_OVERLAY）：
 * 无论当前是主对话、GenUI Agent、设置页，还是已经退到别的 App，询问都能漂浮在最上层。
 *
 * 与 Activity 内 Dialog 的分工（避免弹两遍）：
 *  · 本服务运行中 → [isRunning] 为 true，`VisualQuestionDialog` / `VisualActionDialog` 主动让位不显示
 *  · 没有悬浮窗权限 → 本服务起不来，自动退回 Activity 内 Dialog 的老行为
 *
 * 生命周期：按需启动，回答完（或队列空了）自动 stopSelf，不留常驻通知。
 */
class VisualQuestionOverlayService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main + SupervisorJob()) {

    private lateinit var windowManager: WindowManager
    private var currentView: ComposeView? = null
    private var lifecycleOwnerRef: QuroServiceLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isShowing = false

    /** 当前展示的内容类型，用于判断该监听哪个队列的移除事件 */
    private var showingQuestion = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，退回 Activity 内弹窗")
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isRunning = true
        startForeground(NOTIF_ID, buildNotification())

        launch {
            VisualQuestionQueue.eventFlow.collect { event ->
                when (event) {
                    is VisualQuestionQueue.QuestionEvent.QuestionAdded -> {
                        val q = VisualQuestionQueue.getCurrentQuestion()
                        if (q != null) mainHandler.post { showQuestion(q.first, q.second) }
                    }
                    is VisualQuestionQueue.QuestionEvent.QuestionRemoved -> {
                        if (showingQuestion) mainHandler.post { hideOverlay(); finishIfIdle() }
                    }
                }
            }
        }
        launch {
            VisualActionQueue.eventFlow.collect { event ->
                when (event) {
                    is VisualActionQueue.ActionEvent.ActionAdded -> {
                        val a = VisualActionQueue.getCurrentAction()
                        if (a != null) mainHandler.post { showAction(a.first, a.second) }
                    }
                    is VisualActionQueue.ActionEvent.ActionRemoved -> {
                        if (!showingQuestion) mainHandler.post { hideOverlay(); finishIfIdle() }
                    }
                }
            }
        }

        // 兜底：服务启动时问题可能已经在队列里（事件在本服务 collect 之前就发出、或被别的宿主消费掉了）
        mainHandler.post {
            val q = VisualQuestionQueue.getCurrentQuestion()
            if (q != null) showQuestion(q.first, q.second)
            else VisualActionQueue.getCurrentAction()?.let { showAction(it.first, it.second) }
        }

        // 保险：若启动后一直没能显示出内容（事件被别的宿主抢走、或队列已被清空），
        // 超时后自我了断并复位 isRunning，否则 Activity 内 Dialog 会一直让位 → 询问彻底消失。
        mainHandler.postDelayed({
            if (!isShowing) {
                Log.w(TAG, "启动后未显示任何内容，停止服务并退回 Activity 内弹窗")
                isRunning = false
                stopSelf()
            }
        }, IDLE_TIMEOUT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            hideOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI 询问悬浮窗", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, QuroMainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, VisualQuestionOverlayService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 正在询问")
            .setContentText("点此回到应用作答")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPi)
            .build()
    }

    private fun showQuestion(index: Int, pending: VisualPendingQuestion) {
        showingQuestion = true
        setContent {
            VisualQuestionCard(
                index = index,
                pending = pending,
                onAnswered = { mainHandler.post { hideOverlay(); finishIfIdle() } },
            )
        }
    }

    private fun showAction(index: Int, pending: VisualPendingAction) {
        showingQuestion = false
        setContent {
            VisualActionCard(
                index = index,
                pending = pending,
                onDone = { mainHandler.post { hideOverlay(); finishIfIdle() } },
            )
        }
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        if (isShowing) hideOverlay()
        val lifecycleOwner = QuroServiceLifecycleOwner().apply { create(); resume() }
        lifecycleOwnerRef = lifecycleOwner
        val context = androidx.appcompat.view.ContextThemeWrapper(this, themeRes())

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent(content)
        }
        currentView = composeView
        runCatching { windowManager.addView(composeView, params) }
            .onFailure { Log.e(TAG, "添加悬浮窗失败", it); stopSelf(); return }
        isShowing = true
    }

    private fun hideOverlay() {
        try {
            currentView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "移除悬浮窗失败", e)
        }
        currentView = null
        lifecycleOwnerRef?.destroy()
        lifecycleOwnerRef = null
        isShowing = false
    }

    /** 队列已空 → 没事干了，自己停掉，不留通知。 */
    private fun finishIfIdle() {
        val idle = VisualQuestionQueue.getCurrentQuestion() == null && VisualActionQueue.getCurrentAction() == null
        if (idle) stopSelf()
    }

    private fun themeRes(): Int =
        resources.getIdentifier("Theme.Quro", "style", packageName).let {
            if (it != 0) it else android.R.style.Theme_Material_Light
        }

    override fun onDestroy() {
        try {
            hideOverlay()
            isRunning = false
            coroutineContext.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "销毁失败", e)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VisualQuestionOverlay"
        private const val NOTIF_ID = 8805
        private const val CHANNEL_ID = "visual_question_overlay"
        const val ACTION_STOP_SERVICE = "com.ai.assistance.quro.action.STOP_QUESTION_OVERLAY"
        private const val IDLE_TIMEOUT_MS = 15_000L

        /**
         * 悬浮窗服务是否正在运行。
         * `VisualQuestionDialog` / `VisualActionDialog` 据此让位，避免同一个询问弹两遍
         * （eventFlow 是 Channel，两边都 collect 会互相抢事件）。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 有悬浮窗权限时拉起本服务；无权限则什么也不做（自动退回 Activity 内弹窗）。
         *
         * ⚠️ 时序要点：这里**先乐观置 isRunning=true 再启动服务**。
         * 因为 startForegroundService 是异步的，若等服务 onCreate 才置位，
         * 早已挂着的 Activity 内 `VisualDialogs()` 会先一步 collect 到 Channel 事件并弹出 Dialog，
         * 于是同一个询问弹两遍（而且它还会把事件消费掉，服务反而收不到）。
         * 启动失败时兜底复位，退回 Activity 内弹窗，不会把询问卡死。
         */
        fun start(context: Context) {
            if (isRunning) return
            if (!Settings.canDrawOverlays(context)) return
            isRunning = true
            val intent = Intent(context, VisualQuestionOverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                isRunning = false
                Log.w(TAG, "启动悬浮窗服务失败（退回 Activity 内弹窗）", e)
            }
        }
    }
}
