package com.ai.assistance.quro.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import kotlin.math.abs

/**
 * 内藏桌面 AI —— **打开即虚拟屏**（全屏沉浸版）。
 *
 * ## 形态（用户原话：内藏一个完整的桌面AI，通过这个内藏打开就是虚拟屏）
 *
 * 进来就是**全屏**的虚拟屏画面（Extendroid 的 SurfaceView 零拷贝直出），
 * 画面本身就是一个完整桌面：应用从这里打开、跑在虚拟屏里，
 * **点画面就是直接操作那个 App**（用户和 AI 同一条操控通道）。
 *
 * ## 上一版被用户批评「乱七八糟、看不懂字」后重写
 *
 * - 全屏沉浸（隐藏系统栏），画面铺满，不再是有边框的小盒子；
 * - 应用网格用**真实应用图标 + 名字**（旧版只有 11sp 文字，无法辨认）；
 * - Dock 固定底部，图标 64dp + 13sp 文字，操作一目了然；
 * - 悬浮窗的「全屏」按钮也进这里（同一块虚拟屏，两种形态）。
 */
class QuroDesktopActivity : ComponentActivity() {

    private var attached = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸（画面铺满，不留系统栏）
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { c ->
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0E14.toInt()) }

        // ── 全屏虚拟屏画面（SurfaceView 直出 + 触摸直通）──
        val sv = SurfaceView(this)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = attachSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                attachSurface(holder.surface)
            override fun surfaceDestroyed(holder: SurfaceHolder) = detachSurface()
        })
        // 触摸直通：点画面 = 直接操作虚拟屏里的 App（用户操控通道，与 AI 相同）
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val strokeX = mutableListOf<Int>()
        val strokeY = mutableListOf<Int>()
        sv.setOnTouchListener { _, e ->
            val dw = QuroVirtualScreen.frameWidth().toFloat()
            val dh = QuroVirtualScreen.frameHeight().toFloat()
            val vw = sv.width.toFloat()
            val vh = sv.height.toFloat()
            if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return@setOnTouchListener true
            fun toScreen(ex: Float, ey: Float) = Pair(
                (ex / vw * dw).toInt().coerceIn(0, dw.toInt() - 1),
                (ey / vh * dh).toInt().coerceIn(0, dh.toInt() - 1),
            )
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    strokeX.clear(); strokeY.clear()
                    strokeX.add(toScreen(e.x, e.y).first); strokeY.add(toScreen(e.x, e.y).second)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val p = toScreen(e.x, e.y)
                    strokeX.add(p.first); strokeY.add(p.second)
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val moved = abs(e.x - downX) > slop || abs(e.y - downY) > slop
                    val xs = strokeX.toList(); val ys = strokeY.toList()
                    val dur = (e.eventTime - e.downTime).coerceAtLeast(80L)
                    val up = toScreen(e.x, e.y)
                    Thread {
                        if (moved && xs.size > 2) {
                            QuroVirtualScreen.swipeAt(this, xs.first(), ys.first(), xs.last(), ys.last(), dur)
                        } else {
                            QuroVirtualScreen.tapAt(this, up.first, up.second)
                        }
                    }.start()
                    true
                }
                else -> true
            }
        }
        root.addView(
            sv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        // 未就绪提示（建屏失败时摆在画面上，不白屏）
        val status = TextView(this).apply {
            text = "正在启动后台虚拟屏…（需要 Shizuku 已授权）"
            setTextColor(0xFFFFB4AB.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 0)
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        // ── 底部 Dock（固定，真实图标+清晰文字）──
        root.addView(
            buildDock(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)

        // 建屏 + 状态更新
        Thread {
            val ok = QuroVirtualScreen.ensureDisplay(this)
            runOnUiThread {
                if (ok != null) {
                    status.visibility = View.GONE // 就绪后隐藏（SurfaceView 已直出画面）
                } else {
                    status.text = QuroVirtualScreen.lastError ?: "后台虚拟屏不可用（检查 Shizuku 是否已授权并运行）"
                    status.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun attachSurface(surface: android.view.Surface) {
        val id = QuroVirtualScreen.displayId
        if (id < 0) return
        val ok = QuroVirtualScreen.attachSurface(
            id, surface,
            resources.displayMetrics.widthPixels.coerceAtLeast(320),
            resources.displayMetrics.heightPixels.coerceAtLeast(320),
            resources.displayMetrics.densityDpi,
        )
        attached = ok
        QuroVirtualScreen.setUiSurfaceAttached(ok)
    }

    private fun detachSurface() {
        if (!attached) return
        attached = false
        QuroVirtualScreen.setUiSurfaceAttached(false)
        Thread { QuroVirtualScreen.detachSurfaceToReader() }.start()
    }

    /** 底部 Dock：应用 / 返回 / 多任务 / 分身 / 退出（真实图标+大字）。 */
    private fun buildDock(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6161A23.toInt())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 10)
        }
        fun item(label: String, iconRes: Int, onClick: () -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(22, 4, 22, 4)
                setOnClickListener { onClick() }
                addView(
                    ImageView(context).apply {
                        setImageResource(iconRes)
                        setColorFilter(0xFFE6E1E5.toInt())
                    },
                    LinearLayout.LayoutParams(64, 64),
                )
                addView(
                    TextView(context).apply {
                        text = label
                        setTextColor(0xFFE6E1E5.toInt())
                        textSize = 13f
                        gravity = Gravity.CENTER
                    },
                )
            }
        row.addView(item("应用", android.R.drawable.ic_menu_manage) { showAppGrid() })
        row.addView(item("返回", android.R.drawable.ic_menu_revert) { Thread { QuroVirtualScreen.back(this) }.start() })
        row.addView(item("多任务", android.R.drawable.ic_menu_sort_by_size) { showRecents() })
        row.addView(item("分身", android.R.drawable.ic_menu_myplaces) { showClones() })
        row.addView(item("退出", android.R.drawable.ic_menu_close_clear_cancel) { finish() })
        col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return col
    }

    /** 应用网格：真实应用图标 + 名字，点开进虚拟屏。 */
    private fun showAppGrid() {
        Thread {
            val apps = queryLaunchableApps(this)
            runOnUiThread {
                val grid = GridLayout(this).apply {
                    columnCount = 4
                    setPadding(24, 24, 24, 24)
                }
                for (app in apps) {
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(12, 16, 12, 16)
                        setOnClickListener {
                            Thread { QuroVirtualScreen.openApp(this@QuroDesktopActivity, app.packageName) }.start()
                            dialog?.dismiss()
                        }
                    }
                    cell.addView(
                        ImageView(this).apply { setImageBitmap(app.icon) },
                        LinearLayout.LayoutParams(136, 136),
                    )
                    cell.addView(
                        TextView(this).apply {
                            text = app.label
                            setTextColor(0xFFE6E1E5.toInt())
                            textSize = 12f
                            maxLines = 1
                            gravity = Gravity.CENTER
                        },
                    )
                    grid.addView(
                        cell,
                        GridLayout.LayoutParams().apply {
                            width = GridLayout.LayoutParams.WRAP_CONTENT
                            height = GridLayout.LayoutParams.WRAP_CONTENT
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        },
                    )
                }
                showInDialog("打开应用到虚拟屏", ScrollView(this).apply { addView(grid) })
            }
        }.start()
    }

    /** 多任务：当前虚拟屏最上层 Activity。 */
    private fun showRecents() {
        Thread {
            val top = QuroVirtualScreen.topActivity(this)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (top != null) "当前前台：$top" else "虚拟屏上没有运行的任务",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    /** 分身列表。 */
    private fun showClones() {
        Thread {
            val info = QuroVirtualScreen.listAppCopies(this)
            runOnUiThread {
                val tv = TextView(this).apply {
                    text = info
                    setTextColor(0xFFE6E1E5.toInt())
                    textSize = 14f
                    setPadding(32, 24, 32, 24)
                }
                showInDialog("应用分身（双开/工作资料）", tv)
            }
        }.start()
    }

    private var dialog: android.app.AlertDialog? = null

    private fun showInDialog(title: String, content: View) {
        dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton("关闭", null)
            .show()
    }

    override fun onDestroy() {
        detachSurface()
        super.onDestroy()
    }

    private data class DesktopApp(val label: String, val packageName: String, val icon: Bitmap)

    private fun queryLaunchableApps(ctx: Context): List<DesktopApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .filter { it.activityInfo?.packageName != ctx.packageName }
                .distinctBy { it.activityInfo?.packageName }
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    val icon = runCatching {
                        val d = ri.loadIcon(pm)
                        val w = (d.intrinsicWidth.takeIf { it > 0 } ?: 96).coerceAtMost(144)
                        val h = (d.intrinsicHeight.takeIf { it > 0 } ?: 96).coerceAtMost(144)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            d.setBounds(0, 0, w, h)
                            d.draw(Canvas(it))
                        }
                    }.getOrNull() ?: return@mapNotNull null
                    DesktopApp(ri.loadLabel(pm)?.toString() ?: pkg, pkg, icon)
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }.getOrDefault(emptyList())
    }
}
