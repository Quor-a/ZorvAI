package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 后台虚拟屏的**系统级悬浮窗**（跨 App 可见）。
 *
 * ## 和 App 内画中画的分工
 *
 * - `BackgroundScreenPip`（Compose，在 App 内）：只能在 ZorvAI 界面里看到；
 * - **本服务**（WindowManager overlay）：切到任何 App 都浮在屏幕上 ——
 *   这就是用户说的「手机层漂浮」，形态对齐 Extendroid 的 `PopupWindow` + `OverlayMenu`。
 *
 * ## 能力（对应 Extendroid 那几样）
 *
 *  1. **直接操控虚拟屏**：点画面 → 按比例换算成虚拟屏像素 → `QuroVirtualScreen.tapAt`
 *     （注入走无障碍手势，不需要 Shizuku 在线）；
 *  2. **自由拉窗口大小**：右下角手柄拖拽改窗口宽高，画面等比跟随；
 *  3. **收起来成一个漂浮球**：右上角「—」→ 缩成小球，点球展开。
 *
 * ## 为什么用前台服务
 *
 * overlay 的 View 依附于本进程；进程被回收，浮窗会一起消失。前台服务把它钉住，
 * 这样用户切到别的 App、甚至锁屏再解锁，浮窗还在。
 *
 * ## 权限
 *
 * 需要 `SYSTEM_ALERT_WINDOW`（manifest 已声明），实际还要用户在系统里授予「显示在其他应用上层」。
 * 没授予时本服务不会静默失败 —— [isOverlayAllowed] 供 UI 先检查并跳转授权页。
 */
class QuroBgScreenOverlayService : Service() {

    companion object {
        private const val TAG = "QuroBgOverlay"
        private const val CHANNEL_ID = "quro_bg_overlay"
        private const val NOTIF_ID = 9531
        private const val ACTION_STOP = "com.ai.assistance.quro.action.BG_OVERLAY_STOP"

        /** 浮窗默认宽度（占屏宽比例）与缩放上下限。 */
        private const val DEFAULT_W_RATIO = 0.34f
        private const val MIN_W_DP = 120
        private const val BALL_DP = 46

        /** 浮窗是否已开（UI 用它显示开关状态）。 */
        @Volatile
        var running: Boolean = false
            private set

        fun isOverlayAllowed(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

        /** 引导用户授予「显示在其他应用上层」。 */
        fun requestOverlayPermission(ctx: Context) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "打不开悬浮窗授权页", it) }
        }

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, QuroBgScreenOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Log.w(TAG, "启动悬浮窗服务失败", it) }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(
                    Intent(ctx, QuroBgScreenOverlayService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var frameJob: Job? = null

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var image: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 浮窗宽高（px），拖动缩放手柄时改这两个值。 */
    private var winW = 0
    private var winH = 0

    /** 收起态：只显示一颗球。 */
    private var collapsed = false

    /** 手指按下时窗口左上角的位置，拖动用。 */
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            Log.w(TAG, "前台通知失败（SPECIAL_USE），降级 DATA_SYNC", t)
            runCatching {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }.onFailure {
                Log.e(TAG, "前台通知彻底失败", it)
                stopSelf()
                return
            }
        }
        showWindow()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        frameJob?.cancel()
        runCatching { root?.let { wm.removeView(it) } }
        root = null
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────── 窗口 ───────────────────────────

    private fun showWindow() {
        val dm = resources.displayMetrics
        winW = (dm.widthPixels * DEFAULT_W_RATIO).roundToInt().coerceAtLeast(dp(MIN_W_DP))
        winH = (winW * dm.heightPixels / dm.widthPixels.toFloat()).roundToInt()

        val p = WindowManager.LayoutParams(
            winW,
            winH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_FOCUSABLE：不抢焦点，用户在原 App 里打字不会被打断
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - winW - dp(12)
            y = dp(120)
        }
        params = p

        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#0B0E14"))
                setStroke(dp(1), Color.parseColor("#2A3140"))
            }
            clipToOutline = true
        }
        root = container

        // 画面
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        image = iv
        container.addView(iv)

        // 空闲时给个提示（画面还没到时）
        val hint = TextView(this).apply {
            text = "后台屏"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        container.addView(hint)

        // 触摸：单指 = 拖动窗口；轻点（没移动）= 直接操控虚拟屏
        container.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downParamX = p.x
                    downParamY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (collapsed) {
                        p.x = (downParamX + (ev.rawX - downRawX)).roundToInt()
                        p.y = (downParamY + (ev.rawY - downRawY)).roundToInt()
                        clampToScreen(p)
                        runCatching { wm.updateViewLayout(container, p) }
                    } else {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        // 位移超过阈值才算拖动，否则留给「点一下直控」
                        if (hypot(dx, dy) > dp(6)) {
                            p.x = (downParamX + dx).roundToInt()
                            p.y = (downParamY + dy).roundToInt()
                            clampToScreen(p)
                            runCatching { wm.updateViewLayout(container, p) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = hypot(ev.rawX - downRawX, ev.rawY - downRawY)
                    if (!collapsed && moved <= dp(6)) {
                        // 轻点 = 直接操控虚拟屏：把窗口内坐标换算成虚拟屏像素
                        val localX = ev.x
                        val localY = ev.y
                        val sysW = QuroVirtualScreen.frameWidth()
                        val sysH = QuroVirtualScreen.frameHeight()
                        if (sysW > 0 && sysH > 0 && winW > 0 && winH > 0) {
                            val vx = (localX / winW * sysW).roundToInt().coerceIn(0, sysW - 1)
                            val vy = (localY / winH * sysH).roundToInt().coerceIn(0, sysH - 1)
                            QuroVirtualScreen.tapAt(this@QuroBgScreenOverlayService, vx, vy)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 右上角按钮：收起 / 关闭
        val bar = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4); marginEnd = dp(4)
        }
        container.addView(makeChip("—") { setCollapsed(true) }, bar)
        val closeLp = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4); marginEnd = dp(28)
        }
        container.addView(makeChip("×") { stopSelf() }, closeLp)

        // 右下角缩放手柄：拖动改窗口大小（画面等比跟随）
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#803B82F6"))
            }
            layoutParams = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(4); marginEnd = dp(4)
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downRawX = e.rawX; downRawY = e.rawY; true }
                    MotionEvent.ACTION_MOVE -> {
                        val nw = (winW + (e.rawX - downRawX)).roundToInt()
                            .coerceIn(dp(MIN_W_DP), resources.displayMetrics.widthPixels - dp(24))
                        downRawX = e.rawX
                        winW = nw
                        winH = (nw * resources.displayMetrics.heightPixels /
                            resources.displayMetrics.widthPixels.toFloat()).roundToInt()
                        p.width = winW
                        p.height = winH
                        runCatching { wm.updateViewLayout(container, p) }
                        v.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        container.addView(handle)

        runCatching { wm.addView(container, p) }
            .onFailure { Log.e(TAG, "加浮窗失败（没授予悬浮窗权限？）", it); stopSelf(); return }

        // 画面流：收到帧就换图
        frameJob = scope.launch {
            QuroVirtualScreen.frames.collectLatest { bmp ->
                applyFrame(bmp, hint)
            }
        }
    }

    private fun applyFrame(bmp: Bitmap?, hint: TextView) {
        val iv = image ?: return
        if (bmp == null) {
            hint.visibility = View.VISIBLE
            iv.setImageDrawable(null)
        } else {
            hint.visibility = View.GONE
            iv.setImageBitmap(bmp)
        }
    }

    /** 收起 / 展开：收起时窗口缩成一颗球，只留一个「AI」字样。 */
    private fun setCollapsed(to: Boolean) {
        val c = root ?: return
        val p = params ?: return
        collapsed = to
        if (to) {
            p.width = dp(BALL_DP)
            p.height = dp(BALL_DP)
            c.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EE3B82F6"))
            }
            image?.visibility = View.GONE
            // 球上显示 AI：用一个居中的文字替代画面
            if (image?.parent === c) {
                val ballText = TextView(this).apply {
                    text = "AI"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    tag = "ball"
                }
                c.addView(ballText)
            }
            c.setOnClickListener { setCollapsed(false) }
        } else {
            val dm = resources.displayMetrics
            winW = (dm.widthPixels * DEFAULT_W_RATIO).roundToInt().coerceAtLeast(dp(MIN_W_DP))
            winH = (winW * dm.heightPixels / dm.widthPixels.toFloat()).roundToInt()
            p.width = winW
            p.height = winH
            c.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#0B0E14"))
                setStroke(dp(1), Color.parseColor("#2A3140"))
            }
            // 移除球上的文字
            (0 until c.childCount).map { c.getChildAt(it) }
                .filter { it.tag == "ball" }
                .forEach { c.removeView(it) }
            image?.visibility = View.VISIBLE
            c.setOnClickListener(null)
        }
        runCatching { wm.updateViewLayout(c, p) }
    }

    private fun makeChip(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1F2430"))
            }
            setOnClickListener { onClick() }
        }

    private fun clampToScreen(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        p.x = p.x.coerceIn(-p.width / 2, dm.widthPixels - p.width / 2)
        p.y = p.y.coerceIn(0, dm.heightPixels - dp(60))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    // ─────────────────────────── 通知 ───────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台屏悬浮窗", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "让后台虚拟屏浮在屏幕上，可随时点它直接操作" },
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, QuroMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("后台屏悬浮窗")
            .setContentText("点画面直接操作虚拟屏 · 右上角「—」可收成小球")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
