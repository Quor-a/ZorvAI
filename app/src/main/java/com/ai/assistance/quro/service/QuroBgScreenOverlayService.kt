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
        private const val DEFAULT_W_RATIO = 0.26f
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
        // 🔴 开启悬浮窗服务 = 开启后台虚拟屏 → 自动建屏 + 显示宠物球
        // 宠物球只随虚拟屏生命周期出现（服务启动就建屏）
        Thread {
            com.ai.assistance.quro.core.display.QuroVirtualScreen.ensureDisplay(this)
            Log.i(TAG, "悬浮窗启动时自动建屏: running=" + running)
        }.start()
        // 收成「桌面宠物」形态（用户要求：点宠物才展开后台虚拟屏，
        // 而不是一开启就展开大窗口）。宠物可拖动，点它展开。
        setCollapsed(true)
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

        // 触摸（照 Extendroid 分工）：
        //  · 展开态 —— 画面触摸**全部直通虚拟屏**（轻点=点一下，滑动=滑一下），
        //    拖动窗口走顶部新增的拖动条，两者不打架；
        //  · 宠物态 —— 整个触摸 = 拖动，轻点 = 展开窗口。
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
                    }
                    // 展开态 MOVE 不拖窗：留给 UP 的 swipe 直控判定
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 🔴 兜底：UP 里任何一步（含注入）抛异常都不能拖垮服务，
                    // 否则「点击直接被关闭」。异常只记日志。
                    runCatching {
                        val moved = hypot(ev.rawX - downRawX, ev.rawY - downRawY)
                        if (collapsed) {
                            // 宠物：没怎么动 → 点击展开
                            if (moved <= dp(6)) setCollapsed(false)
                            return@setOnTouchListener true
                        }
                        val sysW = QuroVirtualScreen.frameWidth()
                        val sysH = QuroVirtualScreen.frameHeight()
                        if (sysW > 0 && sysH > 0 && winW > 0 && winH > 0) {
                            fun toSys(px: Float, py: Float) = Pair(
                                (px / winW * sysW).roundToInt().coerceIn(0, sysW - 1),
                                (py / winH * sysH).roundToInt().coerceIn(0, sysH - 1),
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

        // 右上角按钮：收起 / 关闭。
        // 🔴 tag="chip"：宠物态必须隐藏 —— 宠物只有 46dp，两个 chip 叠在它右上角，
        // 点宠物上半部就等于点到「×」直接把悬浮窗关了（用户实测「点击直接被关闭」）。
        val bar = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4); marginEnd = dp(4)
        }
        container.addView(makeChip("—") { setCollapsed(true) }.apply { tag = "chip" }, bar)
        val closeLp = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4); marginEnd = dp(28)
        }
        // 🔴 「×」= 释放虚拟屏 + 停止悬浮窗服务（宠物球也消失）：
        // 这是"关闭整个后台虚拟屏功能"的唯一入口（不走通知）。
        // 「—」只是收起成球，不关功能。
        container.addView(makeChip("✕") {
            Thread {
                QuroVirtualScreen.release()
                stop(this@QuroBgScreenOverlayService)
            }.start()
        }.apply { tag = "close-chip" }, closeLp)

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
        // 🔴 不再在 showWindow 里建屏——onCreate 已经建了（宠物球只随虚拟屏生命周期出现/消失）
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

    /** 收起 / 展开：收起时缩成桌面宠物（身体+眼睛），点宠物展开虚拟屏窗口。 */
    private fun setCollapsed(to: Boolean) {
        // 🔴 视图结构变更统一 post 到下一帧：触摸回调里同步 addView/removeView
        // 会触发 view 树不一致类崩溃（「点击直接被关闭」的另一半来源）。
        android.os.Handler(android.os.Looper.getMainLooper()).post { applyCollapsed(to) }
    }

    private fun applyCollapsed(to: Boolean) {
        val c = root ?: return
        val p = params ?: return
        collapsed = to
        // 🔴 chips（—/×）只在展开态显示：宠物只有 46dp，chips 叠在它右上角，
        // 点宠物上半部等于点到「×」直接 stopSelf —— 用户实测「点击直接被关闭」。
        val chips = (0 until c.childCount).map { c.getChildAt(it) }.filter { it.tag == "chip" }
        if (to) {
            p.width = dp(BALL_DP)
            p.height = dp(BALL_DP)
            c.background = null // 宠物自己带身体背景，容器不再叠圆角矩形
            image?.visibility = View.GONE
            chips.forEach { it.visibility = View.GONE }
            // 🔴 桌面宠物形态：身体 + 两只眼睛，点它展开后台虚拟屏
            if (c.findViewWithTag<View>("pet") == null) {
                c.addView(makePetBody())
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
            // 移除宠物，chips 回来
            c.findViewWithTag<View>("pet")?.let { c.removeView(it) }
            chips.forEach { it.visibility = View.VISIBLE }
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

    /**
     * 桌面宠物（收起态外观）：渐变圆身体 + 两只眼白 + 黑瞳。
     *
     * 用户原话：「点击开按钮做成一个小小的桌面宠物立起来，点的时候展开后台虚拟屏」。
     * 纯 View 叠加实现，不引任何资源；tag="pet" 供展开时移除。
     */
    private fun makePetBody(): FrameLayout {
        val ctx = this
        return FrameLayout(ctx).apply {
            tag = "pet"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // 身体：竖向渐变圆
            addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(
                        Color.parseColor("#5B8CFF"),
                        Color.parseColor("#3B5FD9"),
                    )
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            })
            // 两只眼睛（白底黑瞳），位置略偏上中
            val eyeDp = dp(7)
            val pupilDp = dp(3)
            fun eye(leftMarginDp: Int): FrameLayout =
                FrameLayout(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                    }
                    layoutParams = FrameLayout.LayoutParams(eyeDp, eyeDp, Gravity.CENTER_HORIZONTAL).apply {
                        topMargin = dp(16)
                        marginStart = dp(leftMarginDp)
                        gravity = Gravity.TOP
                    }
                    addView(View(ctx).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#101828"))
                        }
                        layoutParams = FrameLayout.LayoutParams(pupilDp, pupilDp, Gravity.CENTER)
                    })
                }
            addView(eye(-10))
            addView(eye(3))
        }
    }

    private fun clampToScreen(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val halfW = p.width / 2
        val halfH = p.height / 2
        // 🔴 宠物球/窗口必须完全在屏幕范围内：x 下限从 -halfW（一半露出左边缘）到
        // screenWidth - halfW（一半露出右边缘）；y 同理，从 0 到 screenHeight - height（不允许下半部露出）
        p.x = p.x.coerceIn(dm.widthPixels - p.width, 0)
        p.y = p.y.coerceIn(0, dm.heightPixels - p.height)
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
