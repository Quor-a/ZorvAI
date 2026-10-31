package com.ai.assistance.quro.core.display

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 *
 * UI 侧通过 [state] / [frames] 观察它（管理页与画中画小窗都靠这两个流）。
 */
object QuroVirtualScreen {

    private const val TAG = "QuroVirtualScreen"

    /** 管理界面需要的屏状态快照。 */
    data class UiState(
        val ready: Boolean = false,
        val displayId: Int = -1,
        /** 当前最上层 Activity 的完整类名（判断 AI 走到哪一步了）。 */
        val topActivity: String? = null,
        /** 最近一次失败原因（Shizuku 未授权等），非空时界面要显示出来。 */
        val error: String? = null,
        /** 用户是否手动收起过管理窗（会话内有效）。 */
        val dismissed: Boolean = false,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var display: QuroAgentDisplay? = null

    private var frameJob: Job? = null

    private val _frames = MutableStateFlow<Bitmap?>(null)

    /** 后台屏的实时画面（缩略到宽 ≤360，供管理页/画中画显示）。 */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val _state = MutableStateFlow(UiState())

    /** 屏状态（是否就绪、屏号、当前 Activity、错误）。 */
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 最近一次失败原因，供工具返回给 AI 排查（Shizuku 未授权/未运行等）。 */
    val lastError: String? get() = _state.value.error

    val isReady: Boolean get() = display != null

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
                val msg = "Shizuku 特权服务未就绪：请确认 Shizuku 已安装、已授权、服务正在运行" +
                    "（设置 → Shizuku）。绑定已重新发起，稍后重试。"
                Log.w(TAG, msg)
                _state.value = _state.value.copy(ready = false, error = msg)
                return null
            }

            val metrics = context.resources.displayMetrics
            val w = metrics.widthPixels.coerceAtLeast(320)
            val h = metrics.heightPixels.coerceAtLeast(320)
            val dpi = metrics.densityDpi.coerceAtLeast(120)

            return try {
                val d = QuroAgentDisplay(shell, w, h, dpi)
                display = d
                Log.i(TAG, "后台虚拟屏已创建 id=${d.displayId} ${w}x$h @${dpi}dpi")

                // 把屏的帧流转发到自己的 _frames，UI 只依赖本对象即可
                frameJob?.cancel()
                frameJob = scope.launch {
                    d.frames.collect { bmp -> _frames.value = bmp }
                }

                _state.value = UiState(ready = true, displayId = d.displayId)
                refreshTopActivity()
                d
            } catch (t: Throwable) {
                val msg = "创建后台虚拟屏失败：${t.message}"
                Log.e(TAG, "createDisplay failed", t)
                _state.value = _state.value.copy(ready = false, error = msg)
                null
            }
        }
    }

    /** 刷新「当前最上层 Activity」（管理界面用；也用于判断 AI 走到哪一步）。 */
    fun refreshTopActivity() {
        val d = display ?: return
        val shell = QuroShizuku.acquireShellService() ?: return
        val top = runCatching { shell.topActivity(d.displayId) }.getOrNull()
        _state.value = _state.value.copy(topActivity = top)
    }

    /** 管理页关闭时调用：只收起界面，不销毁屏（屏还在给 AI 用）。 */
    fun dismissUi() {
        _state.value = _state.value.copy(dismissed = true)
    }

    /** 用户重新打开管理页。 */
    fun showUi() {
        _state.value = _state.value.copy(dismissed = false)
        refreshTopActivity()
    }

    /** 把 App 启动到后台屏。返回给 AI 看的一句话结果。 */
    fun openApp(context: Context, packageName: String): String {
        if (packageName.isBlank()) return "缺少 packageName"
        val d = ensureDisplay(context) ?: return lastError ?: "后台虚拟屏不可用"
        val shell = QuroShizuku.acquireShellService() ?: return "Shizuku 特权服务未就绪"
        return try {
            when (val r = shell.startApp(d.displayId, packageName)) {
                0 -> {
                    refreshTopActivity()
                    "已把 $packageName 启动到后台屏（displayId=${d.displayId}）"
                }
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
            ?.also { _state.value = _state.value.copy(topActivity = it) }
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
            frameJob?.cancel()
            frameJob = null
            runCatching { d.release() }
            _frames.value = null
            _state.value = UiState()
            Log.i(TAG, "后台虚拟屏已释放 id=${d.displayId}")
        }
    }
}

