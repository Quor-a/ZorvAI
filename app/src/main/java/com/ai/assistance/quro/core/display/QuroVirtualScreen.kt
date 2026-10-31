package com.ai.assistance.quro.core.display

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.ai.assistance.quro.core.shizuku.QuroShizuku

/**
 * 后台虚拟屏的统一入口（App 侧）。
 *
 * 这块屏对用户**不可见**：AI 在里面启动 App、点按、滑动、读界面，用户照常用自己的手机，
 * 主屏焦点与输入法都不受影响（Android 14+ 用 OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED 实现）。
 *
 * 通道：Shizuku AIDL UserService（shell 身份）→ 特权进程里 `createVirtualDisplay`，
 * 画面经 Surface 回到本进程 ImageReader，因此截图**不依赖无障碍**。
 *
 * 尺寸取主屏分辨率与 dpi，这样 App 在后台屏上的布局与真机一致，坐标可以直接换算。
 */
object QuroVirtualScreen {

    private const val TAG = "QuroVirtualScreen"

    private val lock = Any()

    @Volatile
    private var display: QuroAgentDisplay? = null

    /** 最近一次失败原因，供工具返回给 AI 排查（Shizuku 未授权/未运行等）。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 屏是否已经建好。 */
    val isReady: Boolean get() = display != null

    /** 当前屏号；未建返回 -1。 */
    val displayId: Int get() = display?.displayId ?: -1

    /**
     * 取（必要时创建）后台屏。失败返回 null，原因见 [lastError]。
     *
     * 创建前提：Shizuku 已安装、已授权、服务运行中，且 AIDL UserService 已绑定。
     */
    fun ensureDisplay(context: Context): QuroAgentDisplay? {
        display?.let { return it }
        synchronized(lock) {
            display?.let { return it }

            val shell = QuroShizuku.acquireShellService()
            if (shell == null) {
                // 尝试补绑一次，随后仍拿不到就如实报错（不静默失败，避免 AI 反复空转）
                QuroShizuku.bindAidlService()
                lastError = "Shizuku 特权服务未就绪：请确认 Shizuku 已安装、已授权、服务正在运行" +
                    "（设置 → Shizuku）。绑定已重新发起，稍后重试。"
                Log.w(TAG, lastError!!)
                return null
            }

            val metrics = context.resources.displayMetrics
            val w = metrics.widthPixels.coerceAtLeast(320)
            val h = metrics.heightPixels.coerceAtLeast(320)
            val dpi = metrics.densityDpi.coerceAtLeast(120)

            return try {
                val d = QuroAgentDisplay(shell, w, h, dpi)
                display = d
                lastError = null
                Log.i(TAG, "后台虚拟屏已创建 id=${d.displayId} ${w}x$h @${dpi}dpi")
                d
            } catch (t: Throwable) {
                lastError = "创建后台虚拟屏失败：${t.message}"
                Log.e(TAG, "createDisplay failed", t)
                null
            }
        }
    }

    /** 把 App 启动到后台屏。返回给 AI 看的一句话结果。 */
    fun openApp(context: Context, packageName: String): String {
        if (packageName.isBlank()) return "缺少 packageName"
        val d = ensureDisplay(context) ?: return lastError ?: "后台虚拟屏不可用"
        val shell = QuroShizuku.acquireShellService() ?: return "Shizuku 特权服务未就绪"
        return try {
            when (val r = shell.startApp(d.displayId, packageName)) {
                0 -> "已把 $packageName 启动到后台屏（displayId=${d.displayId}）"
                1 -> "用户正在主屏使用 $packageName，为不打扰已放弃启动"
                2 -> "$packageName 没有可启动的入口（未安装？）"
                4 -> "$packageName 正在另一块后台屏上使用中"
                else -> "启动 $packageName 失败（code=$r）"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startApp failed", t)
            "启动 $packageName 失败：${t.message}"
        }
    }

    /** 后台屏最新画面（PNG）。 */
    fun screenshotPng(context: Context): ByteArray? {
        val d = ensureDisplay(context) ?: return null
        return runCatching { d.latestFramePng() }.getOrNull()
    }

    /** 后台屏当前最上层 Activity 的完整类名；无任务返回 null。 */
    fun topActivity(context: Context): String? {
        val d = ensureDisplay(context) ?: return null
        val shell = QuroShizuku.acquireShellService() ?: return null
        return runCatching { shell.topActivity(d.displayId) }.getOrNull()
    }

    /** 在后台屏上点一下。 */
    fun tap(context: Context, x: Int, y: Int): Boolean {
        val d = ensureDisplay(context) ?: return false
        val shell = QuroShizuku.acquireShellService() ?: return false
        return runCatching { shell.injectTap(d.displayId, x, y) }.getOrDefault(false)
    }

    /** 在后台屏上按一个键（走白名单）。 */
    fun key(context: Context, keyCode: Int): Boolean {
        val d = ensureDisplay(context) ?: return false
        val shell = QuroShizuku.acquireShellService() ?: return false
        return runCatching { shell.injectKey(d.displayId, keyCode) }.getOrDefault(false)
    }

    /** 返回键。 */
    fun back(context: Context): Boolean = key(context, KeyEvent.KEYCODE_BACK)

    /**
     * 一次滑动：从 (x1,y1) 到 (x2,y2)，分 [steps] 段，总时长 [durationMs]。
     * 底层注入真实的时间序列，App 看到的速度就是这里给的速度。
     */
    fun swipe(
        context: Context,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long = 300,
        steps: Int = 12,
    ): Boolean {
        val d = ensureDisplay(context) ?: return false
        val shell = QuroShizuku.acquireShellService() ?: return false
        val n = steps.coerceIn(2, 200)
        val dur = durationMs.coerceIn(50L, 8_000L)
        val xs = IntArray(n) { i -> x1 + (x2 - x1) * i / (n - 1) }
        val ys = IntArray(n) { i -> y1 + (y2 - y1) * i / (n - 1) }
        val times = LongArray(n) { i -> dur * i / (n - 1) }
        return runCatching { shell.injectStroke(d.displayId, xs, ys, times) }.getOrDefault(false)
    }

    /** 释放后台屏（会话结束 / 用户关闭能力时调用）。 */
    fun release() {
        synchronized(lock) {
            val d = display ?: return
            display = null
            runCatching { d.release() }
            Log.i(TAG, "后台虚拟屏已释放 id=${d.displayId}")
        }
    }
}
