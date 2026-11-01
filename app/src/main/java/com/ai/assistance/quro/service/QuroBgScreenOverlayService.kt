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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        /** 浮窗默认宽度（占屏宽比例）与缩放上下限。0.26 = 用户要求「展开默认再小点」。 */
        private const val DEFAULT_W_RATIO = 0.30f
        private const val MIN_W_DP = 120
        // 🔴 BALL_DP 已删除（宠物球功能已移除）

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

    /** 收起态：已删除（宠物球功能已移除） */
    // private var collapsed = false

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
        // 🔴 从 Preference 读用户调的大小，默认 0.30
        val prefs = getSharedPreferences("quro_settings", android.content.Context.MODE_PRIVATE)
        val ratio = prefs.getFloat("bg_screen_width_ratio", 0.30f)
        winW = (dm.widthPixels * ratio).roundToInt().coerceAtLeast(dp(MIN_W_DP))
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

        // 触摸（照 Extendroid 分工）：
        //  · 窗口态 —— 拖动走顶部拖动条，画面区轻点=点一下虚拟屏，滑动=滑一下
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
                    // 窗口移动也 clamp：防止拖出屏幕
                    p.x = (downParamX + (ev.rawX - downRawX)).roundToInt()
                    p.y = (downParamY + (ev.rawY - downRawY)).roundToInt()
                    clampToScreen(p)
                    runCatching { wm.updateViewLayout(container, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 🔴 兜底：UP 里任何一步（含注入）抛异常都不能拖垮服务，
                    // 否则「点击直接被关闭」。异常只记日志。
                    runCatching {
                        val moved = hypot(ev.rawX - downRawX, ev.rawY - downRawY)
                        val sysW = QuroVirtualScreen.frameWidth()
                        val sysH = QuroVirtualScreen.frameHeight()
                        // 🔴 严格检查：sysW/sysH 必须 > 1 才能算坐标映射（否则 coerceIn 会崩溃）
                        if (sysW > 1 && sysH > 1 && winW > 0 && winH > 0) {
                            fun safeCoerceIn(value: Int, min: Int, max: Int): Int =
                                if (min <= max) value.coerceIn(min, max) else min
                            fun toSys(px: Float, py: Float) = Pair(
                                safeCoerceIn((px / winW * sysW).roundToInt(), 0, sysW - 1),
                                safeCoerceIn((py / winH * sysH).roundToInt(), 0, sysH - 1),
                            )
                            val (sx, sy) = toSys(downRawX - p.x, downRawY - p.y)
                            val (ex, ey) = toSys(ev.x, ev.y)
                            if (moved > dp(6)) {
                                // 🔴 滑动直控（Extendroid 的触摸直通）：注入带真实时长的滑动
                                QuroVirtualScreen.swipeAt(
                                    this@QuroBgScreenOverlayService,
                                    sx, sy, ex, ey,
                                    (ev.eventTime - ev.downTime).coerceAtLeast(120L),
                                )
                            } else {
                                // 轻点 = 点一下虚拟屏
                                QuroVirtualScreen.tapAt(this@QuroBgScreenOverlayService, sx, sy)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 顶部拖动条（展开态专管移窗，画面区让给直控）
        val dragBar = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#66222B3A"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(16), Gravity.TOP,
            ).apply { topMargin = dp(2); marginStart = dp(40); marginEnd = dp(40) }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX; downRawY = e.rawY
                        downParamX = p.x; downParamY = p.y; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = (downParamX + (e.rawX - downRawX)).roundToInt()
                        p.y = (downParamY + (e.rawY - downRawY)).roundToInt()
                        clampToScreen(p)
                        runCatching { wm.updateViewLayout(container, p) }
                        true
                    }
                    else -> false
                }
            }
        }
        container.addView(dragBar)

        // 右上角关闭按钮：释放虚拟屏 + 停止悬浮窗服务
        val closeLp = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4); marginEnd = dp(4)
        }
        container.addView(makeChip("✕") {
            Thread {
                QuroVirtualScreen.release()
                stop(this@QuroBgScreenOverlayService)
            }.start()
        }, closeLp)

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

        // 画面流：**先建屏，再收帧**。
        // 🔴 这里必须自己 ensureDisplay：本服务是悬浮窗的唯一入口，onCreate 里不再建屏
        // （虚拟屏按需创建，AI 任务的 openApp 也会建）。若这里不建，服务启动后只是
        // 被动等 QuroVirtualScreen.frames —— 没人建屏就永远没有帧，窗口里只剩「后台屏」三个字。
        frameJob = scope.launch {
            hint.text = "正在建屏…"
            hint.visibility = View.VISIBLE
            // ensureDisplay 内部会等 Shizuku AIDL 绑定（最长 5s），必须放 IO
            val d = withContext(Dispatchers.IO) {
                QuroVirtualScreen.ensureDisplay(this@QuroBgScreenOverlayService)
            }
            if (d == null) {
                hint.text = QuroVirtualScreen.lastError ?: "后台屏不可用（检查 Shizuku）"
                hint.visibility = View.VISIBLE
                Log.w(TAG, "悬浮窗启动建屏失败: ${QuroVirtualScreen.lastError}")
                return@launch
            }
            Log.i(TAG, "悬浮窗建屏成功 displayId=${d.displayId}")
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
        // 🔴 防御：窗口比屏幕宽时（极端缩放），直接把 x=0, y=0
        if (p.width > dm.widthPixels || p.height > dm.heightPixels) {
            p.x = 0
            p.y = 0
            return
        }
        // 用安全 coerceIn：范围非法时（min > max）直接返回 min，不再抛异常
        fun safeCoerceIn(value: Int, min: Int, max: Int): Int =
            if (min <= max) value.coerceIn(min, max) else min
        // coerceIn(最小值, 最大值)
        val minX = dm.widthPixels - p.width  // 窗口右下角不超右边缘
        val maxX = 0                          // 窗口左上角不超左边缘
        val minY = 0
        val maxY = dm.heightPixels - p.height
        p.x = safeCoerceIn(p.x, minX, maxX)
        p.y = safeCoerceIn(p.y, minY, maxY)
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
            .setContentText("点画面直接操作虚拟屏")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
