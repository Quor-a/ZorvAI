package com.ai.assistance.quro.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import kotlin.math.abs

/**
 * 后台虚拟屏的**系统级悬浮窗** —— 照 Extendroid 的 `PopupWindow` 重写。
 *
 * ## 与上一版的本质区别
 *
 * 上一版走「ImageReader → Bitmap → Compose」转发，帧率低、大窗口卡；
 * 这一版照 Extendroid 的核心机制改为 **SurfaceView 直出**：
 * 特权端 `VirtualDisplay.surface = holder.surface`（零拷贝，GPU 合成），
 * 窗口多大画面多大，全屏也不掉帧。
 *
 * ## 让用户操控打开的软件（不只是 AI）
 *
 * SurfaceView 的完整触摸事件换算成屏内坐标后交给虚拟屏：按下/移动/抬起合成
 * 手势注入（[QuroVirtualScreen.tapAt] / [swipeAt]）—— 用户在浮窗里就是在
 * 直接操作那个 App，和 AI 同一条通道。
 *
 * ## 窗口结构（对齐上游 layout_app_window）
 *
 * ```
 * [控制条: ⠿拖动 | 球 | 全屏 | 返回 | ✕]
 * [SurfaceView 画面（触摸直通虚拟屏）]
 * [缩放行: ◧拉窄 | 提示 | 拉宽◨]
 * ```
 * 收球后只剩一颗「AI」圆球（可拖动），点球恢复窗口。
 */
class QuroBgScreenOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var ball: TextView? = null
    private var surfaceView: SurfaceView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 是否已把虚拟屏显示面接到本窗口的 SurfaceView。 */
    private var attached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFY_ID, buildNotification())
        showWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        detachSurface()
        root?.let { runCatching { wm.removeView(it) } }
        ball?.let { runCatching { wm.removeView(it) } }
        root = null
        ball = null
        running = false
        super.onDestroy()
    }

    // ────────────────────────── 窗口搭建 ──────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showWindow() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        // 默认窗口：主屏宽 72%，高按虚拟屏比例（Extendroid optimiseSizes 同思路）
        val dw = QuroVirtualScreen.frameWidth().takeIf { it > 0 } ?: screenW
        val dh = QuroVirtualScreen.frameHeight().takeIf { it > 0 } ?: screenH
        val w = (screenW * 0.72f).toInt()
        val h = ((w.toLong() * dh / dw).toInt()).coerceAtMost((screenH * 0.72f).toInt())

        val lp = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - w) / 2
            y = (screenH - h) / 5
        }
        params = lp

        val container = FrameLayout(this)
        container.addView(buildPanel())
        root = container
        wm.addView(container, lp)
        running = true
    }

    /** 构建主窗口：控制条 + SurfaceView + 缩放行。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel(): LinearLayout {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(238, 14, 17, 25))
        }

        // ── 控制条 ──
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(255, 28, 33, 46))
            setPadding(12, 6, 12, 6)
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(ctx).apply {
            text = "⠿ 后台屏"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(8, 8, 20, 8)
            // 拖动整窗（Extendroid dragHandle 同款）
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = params?.x ?: 0; dragStartY = params?.y ?: 0
                        touchDownX = e.rawX; touchDownY = e.rawY; false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.x = dragStartX + (e.rawX - touchDownX).toInt()
                        params?.y = dragStartY + (e.rawY - touchDownY).toInt()
                        root?.let { r -> runCatching { wm.updateViewLayout(r, params) } }
                        true
                    }
                    else -> false
                }
            }
        }
        bar.addView(handle)
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        fun barBtn(label: String, onClick: () -> Unit) = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            minWidth = 0
            setPadding(16, 4, 16, 4)
            setOnClickListener { onClick() }
        }
        bar.addView(barBtn("球") { collapseToBall() })
        bar.addView(barBtn("全屏") { openFullscreen() })
        bar.addView(barBtn("返回") { Thread { QuroVirtualScreen.back(ctx) }.start() })
        bar.addView(barBtn("✕") { stopSelf() })
        col.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── SurfaceView（零拷贝直出 + 触摸直通）──
        val sv = SurfaceView(ctx)
        surfaceView = sv
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                attachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                attachSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = detachSurface()
        })
        // 触摸直通：按下/移动/抬起合成手势注入虚拟屏
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        sv.setOnTouchListener { _, e ->
            val dwS = QuroVirtualScreen.frameWidth().toFloat()
            val dhS = QuroVirtualScreen.frameHeight().toFloat()
            val vw = sv.width.toFloat()
            val vh = sv.height.toFloat()
            if (dwS <= 0 || dhS <= 0 || vw <= 0 || vh <= 0) return@setOnTouchListener true
            fun toScreen(ex: Float, ey: Float) = Pair(
                (ex / vw * dwS).toInt().coerceIn(0, dwS.toInt() - 1),
                (ey / vh * dhS).toInt().coerceIn(0, dhS.toInt() - 1),
            )
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = e.x; touchDownY = e.y
                    strokeX.clear(); strokeY.clear()
                    strokeX.add(toScreen(e.x, e.y).first); strokeY.add(toScreen(e.x, e.y).second)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = toScreen(e.x, e.y)
                    strokeX.add(p.first); strokeY.add(p.second)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.x - touchDownX) > slop || abs(e.y - touchDownY) > slop
                    val xs = strokeX.toList(); val ys = strokeY.toList()
                    val dur = (e.eventTime - e.downTime).coerceAtLeast(80L)
                    val up = toScreen(e.x, e.y)
                    Thread {
                        if (moved && xs.size > 2) {
                            QuroVirtualScreen.swipeAt(ctx, xs.first(), ys.first(), xs.last(), ys.last(), dur)
                        } else {
                            QuroVirtualScreen.tapAt(ctx, up.first, up.second)
                        }
                    }.start()
                    true
                }
                else -> true
            }
        }
        col.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 缩放行（Extendroid 左右 resizer：拖两侧改窗宽，高按屏比例跟随）──
        val resize = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(255, 28, 33, 46))
            gravity = Gravity.CENTER_VERTICAL
        }
        fun resizer(label: String, sign: Int): TextView = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(26, 12, 26, 12)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        resizeStartX = e.rawX; resizeStartW = params?.width ?: 0; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dwR = QuroVirtualScreen.frameWidth().coerceAtLeast(1)
                        val dhR = QuroVirtualScreen.frameHeight().coerceAtLeast(1)
                        val nw = (resizeStartW + sign * (e.rawX - resizeStartX).toInt())
                            .coerceIn(minW, resources.displayMetrics.widthPixels - 40)
                        params?.width = nw
                        params?.height = (nw.toLong() * dhR / dwR).toInt().coerceAtLeast(200)
                        root?.let { r -> runCatching { wm.updateViewLayout(r, params) } }
                        true
                    }
                    else -> true
                }
            }
        }
        resize.addView(resizer("◧", -1))
        resize.addView(TextView(ctx).apply {
            text = "拖两侧调大小 · 拖顶栏移动 · 点画面直接操作"
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 11f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        resize.addView(resizer("◨", 1))
        col.addView(resize, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return col
    }

    // ────────────────────────── 显示面 / 手势 ──────────────────────────

    private fun attachSurface(surface: Surface) {
        val id = QuroVirtualScreen.displayId
        if (id < 0) return
        val dm = resources.displayMetrics
        val w = surfaceView?.width?.coerceAtLeast(160) ?: 480
        val h = surfaceView?.height?.coerceAtLeast(160) ?: 800
        val ok = QuroVirtualScreen.attachSurface(id, surface, w, h, dm.densityDpi)
        attached = ok
        QuroVirtualScreen.setUiSurfaceAttached(ok)
        Log.d(TAG, "overlay attach ok=$ok ${w}x$h")
    }

    private fun detachSurface() {
        if (!attached) return
        attached = false
        QuroVirtualScreen.setUiSurfaceAttached(false)
        Thread { QuroVirtualScreen.detachSurfaceToReader() }.start()
    }

    // ────────────────────────── 收球 / 全屏 ──────────────────────────

    /** 收成一颗「AI」球（Extendroid hideBtn 同款）：移除主窗，显示小球，点球恢复。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun collapseToBall() {
        detachSurface()
        root?.let { runCatching { wm.removeView(it) } }
        root = null

        val size = (resources.displayMetrics.density * 56).toInt()
        val ballLp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 24
            y = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        }

        val b = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.argb(235, 72, 110, 240))
            }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = ballLp.x; dragStartY = ballLp.y
                        touchDownX = e.rawX; touchDownY = e.rawY; false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        ballLp.x = dragStartX + (e.rawX - touchDownX).toInt()
                        ballLp.y = dragStartY + (e.rawY - touchDownY).toInt()
                        ball?.let { runCatching { wm.updateViewLayout(it, ballLp) } }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // 位移小于阈值 → 点球展开窗口
                        if (abs(e.rawX - touchDownX) < 12 && abs(e.rawY - touchDownY) < 12) {
                            expandFromBall(ballLp.x, ballLp.y)
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        ball = b
        wm.addView(b, ballLp)
    }

    /** 从球恢复窗口（球消失，窗口出现在球的位置）。 */
    private fun expandFromBall(x: Int, y: Int) {
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        showWindow()
        params?.let {
            it.x = x.coerceAtLeast(0)
            it.y = y.coerceAtLeast(0)
        }
        root?.let { r -> runCatching { wm.updateViewLayout(r, params) } }
    }

    /** 全屏：交给桌面 Activity（沉浸式全屏直出，同一块虚拟屏）。 */
    private fun openFullscreen() {
        runCatching {
            startActivity(
                Intent(this, Class.forName("com.ai.assistance.quro.activity.QuroDesktopActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "打开桌面失败", it) }
    }

    // ────────────────────────── 杂项 ──────────────────────────

    private fun buildNotification(): Notification {
        val ch = "quro_bg_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "后台屏悬浮窗", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, ch)
            .setContentTitle("后台虚拟屏悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var resizeStartX = 0f
    private var resizeStartW = 0
    private val strokeX = mutableListOf<Int>()
    private val strokeY = mutableListOf<Int>()
    private val minW get() = (resources.displayMetrics.density * 180).toInt()

    companion object {
        private const val TAG = "QuroBgOverlay"
        private const val NOTIFY_ID = 1042

        @Volatile
        var running: Boolean = false
            private set

        fun isOverlayAllowed(ctx: Context): Boolean =
            android.provider.Settings.canDrawOverlays(ctx)

        fun requestOverlayPermission(ctx: Context) {
            runCatching {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        fun start(ctx: Context) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, QuroBgScreenOverlayService::class.java))
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, QuroBgScreenOverlayService::class.java)) }
            running = false
        }
    }
}
