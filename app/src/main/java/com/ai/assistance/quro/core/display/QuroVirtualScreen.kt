package com.ai.assistance.quro.core.display

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import android.os.Bundle
import android.os.SystemClock
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
            // 🔴 动作前开始盯主屏：不少 App 被 setLaunchDisplayId 拉起后，自己又会从
            // 通知/服务/系统选择器里二次 launch，系统按默认把它放到**用户主屏**。
            // 后台屏上看不出任何变化，模型会以为没点到而反复重试 —— 盯住并挪回。
            val since = SystemClock.uptimeMillis()
            val topBefore = runCatching { shell.watchMainScreen(MAIN_WATCH_MS) }.getOrDefault(-1)

            val r = shell.startApp(d.displayId, packageName)
            val base = when (r) {
                0 -> {
                    refreshTopActivity()
                    "已把 $packageName 启动到后台屏（displayId=${d.displayId}）"
                }
                1 -> "用户正在主屏使用 $packageName，为不打扰已放弃启动"
                2 -> "$packageName 没有可启动的入口（未安装？）"
                4 -> "$packageName 正在另一块后台屏上使用中"
                else -> "启动 $packageName 失败（code=$r）"
            }
            if (r != 0) return base

            // 动作后核对：这几秒内主屏冒到最上面的是不是它
            val rescued = rescueIfOnMainScreen(shell, d.displayId, since, topBefore, packageName, context)
            if (rescued != null) "$base\n$rescued" else base
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

    /** 动作后盯主屏的窗口：3 秒。太短会漏掉慢启动的 App，太长会把用户自己的操作算进来。 */
    private const val MAIN_WATCH_MS = 3000

    /** 动作后等多久再核对（给二次 launch 一点时间）。 */
    private const val RESCUE_WAIT_MS = 900L

    /**
     * 核对「刚才那个 App 是不是跑到用户主屏上了」，是就把它挪回后台屏。
     *
     * 判定用移植来的 [MainScreenSpill]（纯逻辑）：排除本应用自己、桌面/最近任务类页面、
     * 从别的屏挪过来的任务、以及用户正在主屏上用着的那个 App（那属于用户意图，不能抢）。
     *
     * @return 给 AI 看的一句话；没有需要处理的情况返回 null
     */
    private fun rescueIfOnMainScreen(
        shell: com.ai.assistance.quro.IQuroShellService,
        displayId: Int,
        since: Long,
        topBefore: Int,
        packageName: String,
        context: Context,
    ): String? {
        return runCatching {
            Thread.sleep(RESCUE_WAIT_MS)
            val b = shell.mainArrivals(since) ?: return null
            val arrivals = parseArrivals(b)
            if (arrivals.isEmpty()) return null
            val verdicts = MainScreenSpill.decide(
                arrivals = arrivals,
                since = since,
                windowMs = MAIN_WATCH_MS.toLong(),
                topBefore = topBefore,
                excluded = setOf(context.packageName),
                userTaps = emptyList(),
            )
            if (verdicts.isEmpty()) return null

            val moved = mutableListOf<String>()
            val inUse = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (v in verdicts) {
                when (v) {
                    is MainScreenSpill.PullBack -> {
                        val pkg = v.arrival.packageName ?: continue
                        if (shell.moveTask(v.arrival.taskId, displayId)) {
                            moved += pkg
                        } else {
                            failed += pkg
                        }
                    }
                    is MainScreenSpill.InUse -> {
                        inUse += v.arrival.packageName ?: packageName
                    }
                }
            }
            when {
                moved.isNotEmpty() ->
                    "⚠️ ${moved.joinToString()} 被系统放到了你的主屏上，已自动挪回后台屏"
                inUse.isNotEmpty() ->
                    "${inUse.joinToString()} 开在你正在用的界面里，没有动它（要后台操作请先切走）"
                failed.isNotEmpty() ->
                    "${failed.joinToString()} 跑到了主屏且挪回失败（可能被系统锁定了任务）"
                else -> null
            }
        }.getOrNull()
    }

    /** 把 AIDL 回来的平行数组还原成 [MainScreenSpill.Arrival] 列表。 */
    private fun parseArrivals(b: Bundle): List<MainScreenSpill.Arrival> {
        val tasks = b.getIntArray(MainScreenWatch.KEY_TASK) ?: return emptyList()
        val pkgs = b.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val users = b.getIntArray(MainScreenWatch.KEY_USER)
        val ats = b.getLongArray(MainScreenWatch.KEY_AT)
        val froms = b.getIntArray(MainScreenWatch.KEY_FROM)
        val same = b.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)
        val standard = b.getBooleanArray(MainScreenWatch.KEY_STANDARD)
        val acts = b.getStringArray(MainScreenWatch.KEY_ACTIVITY)
        val nows = b.getIntArray(MainScreenWatch.KEY_NOW)
        return tasks.indices.map { i ->
            MainScreenSpill.Arrival(
                taskId = tasks[i],
                packageName = pkgs?.getOrNull(i),
                userId = users?.getOrNull(i) ?: -1,
                at = ats?.getOrNull(i) ?: 0L,
                from = froms?.getOrNull(i) ?: -1,
                sameTask = same?.getOrNull(i) ?: false,
                standard = standard?.getOrNull(i) ?: true,
                now = nows?.getOrNull(i) ?: -1,
                topActivity = acts?.getOrNull(i),
            )
        }
    }

    /**
     * 在后台屏的输入框里写入文字（**不经过系统输入法**）。
     *
     * 为什么要单独一条：走 IME 会在用户主屏弹出键盘（输入法窗口跟焦点走），
     * 是后台操作里最容易被察觉的打扰。走无障碍 ACTION_SET_TEXT 等价粘贴，零打扰。
     *
     * @return 实际写入的字符数；null = 这块屏上没有可用输入框（调用方应提示先点一下输入框）
     */
    fun inputText(context: Context, text: String, hint: String? = null, append: Boolean = false): Int? {
        val d = display ?: return null
        val svc = com.ai.assistance.quro.service.QuroAccessibilityService.instance ?: return null
        val ok = runCatching {
            BgScreenReader.inputText(svc, d.displayId, text, hint, append)
        }.getOrDefault(false)
        if (ok) return text.length
        // 没有可编辑控件，或该控件不认 ACTION_SET_TEXT
        return null
    }

    // ─────────── 界面结构读取（移植 Mika 的读树能力）───────────

    /** 最近一次读到的界面快照；`tapElement` 靠它把 ref 换成坐标。 */
    @Volatile
    private var lastSnapshot: BgSnapshot? = null

    /**
     * 读后台屏的**控件树**（不是截图）。
     *
     * 需要无障碍服务在运行；服务没开时返回 null（此时只能退回 screenshot）。
     * 这一步是「精确点击」的前提：拿到 ref + 真实坐标，就不必靠看图猜位置。
     */
    fun readScreen(): BgSnapshot? {
        val d = display ?: return null
        val svc = com.ai.assistance.quro.service.QuroAccessibilityService.instance ?: return null
        val snap = BgScreenReader.read(svc, d.displayId, d.width, d.height) ?: return null
        lastSnapshot = snap
        return snap
    }

    /** 最近一次快照（供 UI 显示，不重新读屏）。 */
    fun lastSnapshot(): BgSnapshot? = lastSnapshot

    /**
     * 按 ref（如 `@e3`）点击某个元素。
     *
     * ref 来自上一次 [readScreen]；界面已经变了会点错，所以点之前应由调用方重新 read。
     */
    fun tapElement(context: Context, ref: String): Boolean {
        val e = lastSnapshot?.elements?.firstOrNull { it.ref == ref } ?: return false
        return tapAt(context, e.centerX, e.centerY)
    }

    /**
     * 点一下：**优先用无障碍手势**（不需要任何特权），失败再退回 Shizuku 注入。
     *
     * 为什么这个顺序：无障碍通道在多数机型上更稳（不受 shell 输入注入限制），
     * 而 Shizuku 注入需要服务在线；两条都留，任一条可用即可工作。
     */
    fun tapAt(context: Context, x: Int, y: Int): Boolean {
        val d = display ?: return false
        val svc = com.ai.assistance.quro.service.QuroAccessibilityService.instance
        if (svc != null && svc.tapOn(d.displayId, x.toFloat(), y.toFloat())) return true
        return tap(context, x, y)
    }

    /** 滑动：同样优先无障碍手势，失败退回 Shizuku 注入。 */
    fun swipeAt(context: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val d = display ?: return false
        val svc = com.ai.assistance.quro.service.QuroAccessibilityService.instance
        if (svc != null && svc.swipeOn(d.displayId, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)) {
            return true
        }
        return swipe(context, x1, y1, x2, y2, durationMs)
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
            lastSnapshot = null
            _state.value = UiState()
            Log.i(TAG, "后台虚拟屏已释放 id=${d.displayId}")
        }
    }
}

