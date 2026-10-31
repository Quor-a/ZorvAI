package com.ai.assistance.quro.core.shizuku

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.ai.assistance.quro.IQuroShellService
import com.ai.assistance.quro.core.display.MainScreenLog
import com.ai.assistance.quro.core.display.MainScreenWatch
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService 实现（AIDL Stub）。
 *
 * 此类实例运行在 **Shizuku 特权进程**中（UID 0=root 或 2000=shell），
 * 因此 [exec] 中的 Runtime.exec() 以特权身份执行，等效于 ADB shell。
 *
 * 生命周期：
 *   - 由 [QuroShizuku] 通过 Shizuku.bindUserService() 绑定
 *   - Shizuku 调用 destroy() 时进程退出
 *   - Shizuku 服务断开时自动解绑
 *
 * ShellService 架构：通过 AIDL UserService 在 Shizuku 特权进程内执行命令。
 */
class QuroShellService : IQuroShellService.Stub() {

    companion object {
        private const val TAG = "QuroShellSvc"

        /** Binder 单次事务有 ~1MB 上限，留足头部余量。 */
        private const val MAX_CHUNK = 512 * 1024

        // ─────────── 后台虚拟屏常量 ───────────
        private const val MAIN_DISPLAY = 0
        private const val MAX_TASKS = 200
        private const val TAP_MS = 60L
        private const val STROKE_MAX_POINTS = 400
        private const val STROKE_MAX_MS = 10_000L

        // 🔴 这些数字必须与 mika-phone 的 ShellCodes **逐值一致**（跨模块契约）。
        // 上游：4=冷启动被降级、7=另一块屏在用；早期这里把 4 当成 IN_USE_BY_OTHER，已纠正。
        private const val START_OK = 0
        private const val START_IN_USE_ON_MAIN = 1
        private const val START_NOT_FOUND = 2
        private const val START_ERROR = 3
        private const val START_OK_COLD_IGNORED = 4
        private const val START_UNRESOLVED = 5
        private const val START_INVALID_LINK = 6
        private const val START_IN_USE_BY_OTHER = 7
        private const val START_USER_NOT_ALLOWED = 8

        private const val CLIP_SET = 0
        private const val CLIP_UNREADABLE = 1
        private const val CLIP_FAILED = 2

        /** appCopyUsers() 返回里的键 */
        private const val KEY_COPY_USERS = "users"

        /** Ctrl 组合键白名单：只放行全选与粘贴，别的组合一律不发。 */
        private val ALLOWED_CTRL_KEYS: Set<Int> = setOf(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_V,
        )

        private const val USER_CURRENT = -2
        private const val INJECT_MODE_ASYNC = 0
        private const val VIRTUAL_KEYBOARD = -1

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*（部分为隐藏常量）
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /** WindowManager.DISPLAY_IME_POLICY_LOCAL = 0：输入法出现在虚拟屏上，主屏焦点不变。 */
        private const val IME_POLICY = 0

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

        /** 按键白名单：绝不从这里发出电源/音量/Home/最近任务这类整机按键。 */
        private val ALLOWED_KEYS: Set<Int> = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }

    override fun destroy() {
        Log.d(TAG, "destroy() called, exiting process")
        kotlin.system.exitProcess(0)
    }

    /**
     * 在 Shizuku 特权进程中执行 shell 命令。
     *
     * @param command 要执行的命令（如 "id"、"pm list packages"、"dumpsys activity top"）
     * @return stdout 内容（非空时）；否则 stderr 内容；均空则 "(无输出)"
     */
    override fun exec(command: String): String {
        return try {
            Log.d(TAG, "exec: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            val body = (out + err).trim()
            Log.d(TAG, "exec result: exit=$exitCode body=${body.take(120)}")
            if (body.isBlank()) "(无输出)" else body
        } catch (e: Exception) {
            Log.e(TAG, "exec failed", e)
            "Error: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 墨语言原生通道：文件落盘 / 查询 / 带超时执行 / 删除
    // ══════════════════════════════════════════════════════════════

    /**
     * 以特权身份把字节写进 path。这是 App 沙箱 → /data/local/tmp 的唯一通道：
     * App 写不进 /data/local/tmp，shell 也读不了 App 私有目录，所以只能由
     * 特权进程代写，调用方分块追加。
     */
    override fun writeChunk(path: String, data: ByteArray?, append: Boolean): Int {
        if (path.isBlank()) return -1
        if (data == null || data.isEmpty()) {
            // 空块：仅在建目录语义下有意义，直接返回当前大小
            return File(path).length().toInt()
        }
        if (data.size > MAX_CHUNK) {
            Log.w(TAG, "writeChunk 块过大 (${data.size})，建议 <= $MAX_CHUNK，仍尝试写入")
        }
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            FileOutputStream(f, append).use { it.write(data) }
            val n = f.length()
            n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "writeChunk 失败: $path", e)
            -1
        }
    }

    override fun fileSize(path: String): Long {
        if (path.isBlank()) return -1L
        return try {
            val f = File(path)
            if (f.isFile) f.length() else -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 带超时执行。原生通道要跑编译和基准测试，超时必须能收回进程，
     * 否则 [exec] 的 waitFor() 会把调用线程永久挂住。
     *
     * 做法：起一个看门狗线程，超时就 destroyForcibly()。注意**不能**只
     * destroyForcibly 而不管孙子进程 —— sh -c 里的 qemu/zig 是子进程，
     * 所以先试 `pkill -f <pattern>` 收尾巴，再强杀。
     */
    override fun execTimed(command: String, timeoutMs: Long): String {
        val budget = if (timeoutMs <= 0) 120_000L else timeoutMs
        return try {
            Log.d(TAG, "execTimed(${budget}ms): $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val finished = process.waitFor(budget, TimeUnit.MILLISECONDS)
            if (!finished) {
                // 收孙子进程：sh -c 起的 qemu / zig 不会随 sh 一起死
                runCatching {
                    Runtime.getRuntime().exec(
                        arrayOf("sh", "-c", "pkill -f 'quro-mo' 2>/dev/null; true")
                    ).waitFor(3, TimeUnit.SECONDS)
                }
                process.destroyForcibly()
                val out = runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                }.getOrDefault("")
                val err = runCatching {
                    BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                }.getOrDefault("")
                val body = (out + err).trim()
                Log.w(TAG, "execTimed 超时 ${budget}ms: $command")
                (if (body.isBlank()) "" else body + "\n") + "exit=timeout(${budget}ms)"
            } else {
                val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val err = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                val body = (out + err).trim()
                Log.d(TAG, "execTimed result: exit=${process.exitValue()} body=${body.take(120)}")
                if (body.isBlank()) "(无输出)" else body
            }
        } catch (e: Exception) {
            Log.e(TAG, "execTimed failed", e)
            "Error: ${e.message}"
        }
    }

    override fun remove(path: String): Boolean {
        if (path.isBlank()) return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "rm -rf '$path'"))
            p.waitFor(30, TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "remove 失败: $path", e)
            false
        }
    }

    // ═══════════════════ 后台虚拟屏（移植自 Mika，去品牌化；只作用于本服务自建的屏）═══════════════════

    /** 伪装成 com.android.shell 的系统 Context：DisplayManager / InputManager 都要求包名与 uid 对得上。 */
    private val privilegedContext: Context by lazy { QuroShellContext.create() }

    /**
     * 不能用 getSystemService：那样拿到的 DisplayManager 绑的是宿主 App 的上下文，
     * 包名与 shell uid 对不上，createVirtualDisplay 会被拒。
     */
    @get:SuppressLint("PrivateApi")
    private val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(privilegedContext) as DisplayManager
    }

    @get:SuppressLint("PrivateApi")
    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    /** displayId → VirtualDisplay。注入与启动都只认这里的 key（白名单），绝不碰主屏。 */
    private val displays = HashMap<Int, VirtualDisplay>()

    /** 查占用和启动之间不能插进另一个启动，否则两个任务同时开同一个 App 只有一个能成。 */
    private val launchLock = Any()

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        val vd = displayManager.createVirtualDisplay(
            "quro-agent", width, height, dpi, surface, displayFlags(),
        )
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setImePolicy(id)
        Log.d(TAG, "createDisplay -> id=$id ${width}x$height @${dpi}dpi")
        id
    } catch (t: Throwable) {
        Log.e(TAG, "createDisplay failed", t)
        -1
    }

    /**
     * 把虚拟屏显示面切到外部 Surface（零拷贝直出）+ 顺手改分辨率。
     *
     * Extendroid 的做法（`VirtualDisplay.surface = surface` + `resize`）：
     * 显示面跟窗口走，窗口多大画面多大；AI 截图把面切回 ImageReader 即可。
     * displayId 必须是本服务自建的屏（ownDisplay 校验），不接外部屏。
     */
    override fun displaySetSurface(displayId: Int, surface: Surface?, width: Int, height: Int, dpi: Int): Boolean {
        if (surface == null || !surface.isValid) return false
        val vd = synchronized(displays) { displays[displayId] } ?: return false
        return try {
            vd.surface = surface
            if (width > 0 && height > 0) {
                runCatching { vd.resize(width, height, if (dpi > 0) dpi else 160) }
            }
            Log.d(TAG, "displaySetSurface id=$displayId -> ${width}x${height}@$dpi")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "displaySetSurface failed id=$displayId", t)
            false
        }
    }

    override fun releaseDisplay(displayId: Int) {
        val vd = synchronized(displays) { displays.remove(displayId) }
        runCatching { vd?.release() }
        Log.d(TAG, "releaseDisplay id=$displayId released=${vd != null}")
    }

    /** 只认本服务自建的屏；调用方传主屏号也不会误伤用户。 */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun startApp(displayId: Int, packageName: String): Int {
        if (packageName.isBlank()) return START_ERROR
        synchronized(launchLock) {
            if (!ownDisplay(displayId)) {
                Log.w(TAG, "startApp refused: display $displayId is not ours")
                return START_ERROR
            }
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(packageName, displayId)) return START_IN_USE_BY_OTHER

            val intent = launchIntent(packageName) ?: return START_NOT_FOUND
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()

            val ok = try {
                val start = taskManager.javaClass.methods.first {
                    it.name == "startActivityAsUser" && it.parameterTypes.size == 12
                }
                // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo,
                //  resultWho, requestCode, flags, profilerInfo, options, userId)
                val result = start.invoke(
                    taskManager, null, QuroShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                    0, 0, null, options, USER_CURRENT,
                ) as Int
                if (result < 0) Log.w(TAG, "startActivityAsUser($packageName) -> $result")
                result >= 0
            } catch (t: Throwable) {
                Log.w(TAG, "startActivityAsUser unavailable, fallback to am start", t)
                val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
                val out = exec("am start --display $displayId -n $component")
                !out.contains("Error") && !out.contains("Exception")
            }
            Log.d(TAG, "startApp($packageName) on $displayId -> ${if (ok) "OK" else "ERROR"}")
            return if (ok) START_OK else START_ERROR
        }
    }

    private fun launchIntent(packageName: String): Intent? = try {
        privilegedContext.packageManager.getLaunchIntentForPackage(packageName)
    } catch (t: Throwable) {
        Log.w(TAG, "getLaunchIntentForPackage failed: $packageName", t)
        null
    }

    /** 该包在主屏（display 0）有任务 → 用户正在用，不要动它。 */
    private fun inUseOnMainScreen(packageName: String): Boolean = try {
        allTasks().any { t -> displayOfTask0(t) == MAIN_DISPLAY && packagesOf(t).contains(packageName) }
    } catch (t: Throwable) {
        Log.w(TAG, "inspect main-screen tasks failed", t)
        false
    }

    /** 该包在本服务另一块屏上有任务 → 别的任务正在用，不抢。 */
    private fun onOtherOwnDisplay(packageName: String, displayId: Int): Boolean = try {
        val others = synchronized(displays) { displays.keys.toSet() } - displayId
        if (others.isEmpty()) false
        else allTasks().any { t -> displayOfTask0(t) in others && packagesOf(t).contains(packageName) }
    } catch (t: Throwable) {
        false
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) {
                !exec("input -d $displayId tap $x $y").contains("Exception")
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        if (!keyAllowed(keyCode)) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) {
            !exec("input -d $displayId keyevent $keyCode").contains("Exception")
        }
    }

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n) return false
        if (times[0] != 0L || times.last() > STROKE_MAX_MS) return false
        if ((1 until n).any { times[it] < times[it - 1] }) return false

        val base = SystemClock.uptimeMillis()
        fun eventAt(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply {
                x = xs[i].toFloat()
                y = ys[i].toFloat()
                pressure = 1f
                size = 1f
            }
            return MotionEvent.obtain(
                base, base + times[i], action, 1,
                arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = base + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = eventAt(i, action)
            return try {
                inject(e, displayId)
            } finally {
                e.recycle()
            }
        }

        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        // 最后一定补发抬起，绝不让"手指"一直按着
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null
        else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className
        }
    } catch (t: Throwable) {
        Log.e(TAG, "topActivity failed", t)
        null
    }

    override fun displayOfTask(taskId: Int): Int = try {
        val idField = TASK_INFO.getField("taskId")
        allTasks().firstOrNull { idField.getInt(it) == taskId }
            ?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        -1
    }

    // ─────────────── 主屏监视（移植自 Mika：App 跑到用户主屏时认出来并挪回）───────────────

    /**
     * 主屏任务的记录。第一次用到才建（会起一个 HandlerThread 并登记任务栈监听）。
     *
     * 为什么要它：AI 的动作不一定把 App 开进后台屏 —— 启动某个 App 时对方若从通知、
     * 服务、系统选择器里二次 launch，系统会按默认放到**主屏**，后台屏上看不出任何变化，
     * 模型于是以为没点到、一遍遍重试。盯住主屏最上面换的是谁，就能识别并 `moveTask` 挪回来。
     */
    private val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

    override fun watchMainScreen(windowMs: Int): Int = try {
        mainWatch.watch(windowMs)
    } catch (t: Throwable) {
        Log.e(TAG, "watchMainScreen failed", t)
        -1
    }

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = try {
        mainWatch.arrivals(sinceUptimeMs)
    } catch (t: Throwable) {
        Log.e(TAG, "mainArrivals failed", t)
        null
    }

    override fun moveTask(taskId: Int, displayId: Int): Boolean {
        // 安全边界：只允许挪到/挪回自己建的屏或主屏，不接受任意屏号
        if (displayId != MAIN_DISPLAY && !ownDisplay(displayId)) return false
        return try {
            taskManager.javaClass
                .getMethod(
                    "moveRootTaskToDisplay",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(taskManager, taskId, displayId)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "moveRootTaskToDisplay 不可用，退回 am display move-stack", t)
            // 兜底：命令行同样走 IActivityTaskManager，只是多一次进程开销
            runCatching { exec("am display move-stack $taskId $displayId") }
            runCatching {
                Thread.sleep(300)
                displayOfTask(taskId) == displayId
            }.getOrDefault(false)
        }
    }

    /** 把系统 TaskInfo 列表压成纯逻辑层要的形状（字段名是本机反射出来的，各版本可能有缺）。 */
    private fun taskRows(): List<MainScreenLog.Task> {
        val id = TASK_INFO.getField("taskId")
        val display = TASK_INFO.getField("displayId")
        val user = runCatching { TASK_INFO.getField("userId") }.getOrNull()
        val type = runCatching { TASK_INFO.getField("topActivityType") }.getOrNull()
        return allTasks().map { t ->
            val top = runCatching { TASK_INFO.getField("topActivity").get(t) as? ComponentName }.getOrNull()
            val base = runCatching { TASK_INFO.getField("baseActivity").get(t) as? ComponentName }.getOrNull()
            MainScreenLog.Task(
                taskId = id.getInt(t),
                displayId = display.getInt(t),
                packageName = top?.packageName ?: base?.packageName,
                userId = user?.getInt(t) ?: -1,
                topActivity = top?.className,
                // TaskInfo.TOP_ACTIVITY_TYPE_*：0=UNDEFINED 1=STANDARD，其余（HOME/RECENTS/ASSISTANT）不算普通 App
                standard = type?.getInt(t)?.let { it == 0 || it == 1 } ?: true,
            )
        }
    }

    // ─────────────── 内部辅助 ───────────────

    private fun displayOfTask0(task: Any): Int = TASK_INFO.getField("displayId").getInt(task)

    private fun packagesOf(task: Any): List<String> = listOf("topActivity", "baseActivity")
        .mapNotNull {
            runCatching { TASK_INFO.getField(it).get(task) as? ComponentName }.getOrNull()?.packageName
        }

    /**
     * IActivityTaskManager.getTasks：按最近使用排序，shell 持有 REAL_GET_TASKS 能看到所有 App 的任务。
     * API 33+ 有带 displayId 的重载，优先用它；更早版本取全部再按 TaskInfo.displayId 过滤。
     */
    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun displayFlags(): Int {
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
                FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        // 只有 OWN_FOCUS 时虚拟屏会抢「最上层焦点」，主屏焦点窗口随之清空；
        // STEAL_TOP_FOCUS_DISABLED（需同时有 OWN_FOCUS、TRUSTED）让它不抢用户主屏的焦点。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }

    /** IWindowManager.setDisplayImePolicy：输入法不弹到用户主屏上。 */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
                .invoke(wm, displayId, IME_POLICY)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_ASYNC) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    private inline fun injectPair(
        down: InputEvent,
        up: InputEvent,
        displayId: Int,
        fallback: () -> Boolean,
    ): Boolean {
        if (!inject(down, displayId)) return fallback()
        return inject(up, displayId) || inject(up, displayId)
    }

    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull() ?: Class.forName("android.hardware.input.InputManager")
        .getMethod("getInstance").invoke(null)!!

    /** 按键白名单：绝不从这里发出电源/音量/Home/最近任务这类整机按键。 */
    private fun keyAllowed(keyCode: Int): Boolean = keyCode in ALLOWED_KEYS
    // ═══════════════════════════════════════════════════════════════════════════
    // 以下为「后台虚拟屏」完整移植所需的宿主能力（对齐上游 43 方法契约）。
    // 由 mika-phone 的 AndroidScreen（动作编排 1810 行）直接调用，
    // 因此这里的语义必须与上游一致 —— 特别是返回码（见 START_* 常量）。
    // ═══════════════════════════════════════════════════════════════════════════

    /** 统一的活动启动通道：反射 startActivityAsUser（带 displayId + userId），失败回退 am。 */
    private fun startIntentOnDisplay(intent: Intent, displayId: Int, userId: Int): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        return try {
            val start = taskManager.javaClass.methods.first {
                it.name == "startActivityAsUser" && it.parameterTypes.size == 12
            }
            val result = start.invoke(
                taskManager, null, QuroShellContext.SHELL_PACKAGE, null, intent, null,
                null, null, 0, 0, null, options, userId,
            ) as Int
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser 不可用，回退 am start", t)
            val flags = buildString {
                append("--display ").append(displayId)
                if (userId != USER_CURRENT && userId >= 0) append(" --user ").append(userId)
                append(" -a ").append(intent.action ?: Intent.ACTION_VIEW)
                intent.dataString?.let { append(" -d ").append(it) }
                intent.`package`?.let { append(" -p ").append(it) }
            }
            val out = runCatching { exec("am start $flags") }.getOrDefault("")
            !out.contains("Error") && !out.contains("Exception")
        }
    }

    override fun restartApp(displayId: Int, packageName: String): Int {
        if (packageName.isBlank()) return START_ERROR
        if (!ownDisplay(displayId)) return START_ERROR
        if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
        // 在别的自己的屏上还有任务：结束进程会连带杀掉那份，于是降级为普通启动
        val elsewhere = onOtherOwnDisplay(packageName, displayId)
        runCatching { exec("am force-stop $packageName") }
        val r = startApp(displayId, packageName)
        return if (elsewhere && r == START_OK) START_OK_COLD_IGNORED else r
    }

    override fun packageOfTask(taskId: Int): String? = try {
        val idField = TASK_INFO.getField("taskId")
        allTasks().firstOrNull { idField.getInt(it) == taskId }
            ?.let { packagesOf(it).firstOrNull() }
    } catch (t: Throwable) {
        Log.w(TAG, "packageOfTask failed", t)
        null
    }

    override fun userOfTask(taskId: Int): Int = try {
        val idField = TASK_INFO.getField("taskId")
        val userField = TASK_INFO.getField("userId")
        allTasks().firstOrNull { idField.getInt(it) == taskId }
            ?.let { userField.getInt(it) } ?: -1
    } catch (t: Throwable) {
        -1
    }

    override fun displayOfPackage(packageName: String): Int = try {
        val own = synchronized(displays) { displays.keys.toSet() }
        val idField = TASK_INFO.getField("taskId")
        allTasks().firstOrNull {
            displayOfTask0(it) in own && packagesOf(it).contains(packageName)
        }?.let { displayOfTask0(it) } ?: -1
    } catch (t: Throwable) {
        -1
    }

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int = try {
        val own = synchronized(displays) { displays.keys.toSet() }
        val userField = TASK_INFO.getField("userId")
        allTasks().firstOrNull {
            displayOfTask0(it) in own &&
                packagesOf(it).contains(packageName) &&
                userField.getInt(it) == userId
        }?.let { displayOfTask0(it) } ?: -1
    } catch (t: Throwable) {
        -1
    }

    override fun injectCtrlKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        if (keyCode !in ALLOWED_CTRL_KEYS) {
            Log.w(TAG, "refused ctrl+key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        return try {
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, VIRTUAL_KEYBOARD, 0)
            val up = KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0, meta, VIRTUAL_KEYBOARD, 0)
            // setDisplayId 是隐藏 API（公开 SDK 里没有）：与上游一样走反射
            runCatching {
                val m = KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                m.invoke(down, displayId)
                m.invoke(up, displayId)
            }
            val inject = inputManager()
            inject?.let { m ->
                m.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    .invoke(m, down, INJECT_MODE_ASYNC)
                m.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    .invoke(m, up, INJECT_MODE_ASYNC)
            } != null
        } catch (t: Throwable) {
            Log.w(TAG, "injectCtrlKey failed", t)
            false
        }
    }

    // ── PLAY_AUDIO 静音（AppOps） ──

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean =
        setAudioMode(packageName, if (muted) "ignore" else "allow")

    override fun getAudioMode(packageName: String): String? {
        if (packageName.isBlank()) return null
        val out = runCatching { exec("appops get $packageName PLAY_AUDIO") }.getOrDefault("")
        return when {
            out.contains("ignore") -> "ignore"
            out.contains("allow") -> "allow"
            out.contains("deny") -> "deny"
            out.contains("default") -> "default"
            else -> null
        }
    }

    override fun setAudioMode(packageName: String, mode: String): Boolean {
        if (packageName.isBlank()) return false
        if (mode !in setOf("allow", "ignore", "deny", "default")) return false
        val out = runCatching { exec("appops set $packageName PLAY_AUDIO $mode") }.getOrDefault("")
        return !out.contains("Error") && !out.contains("Exception")
    }

    // ── 剪贴板 ──

    /** setClipText 之前用户剪贴板里的文字；null = 没有待还原的。 */
    private var savedClip: String? = null

    /** 我们放进去的那段文字，用于判断剪贴板是否被用户换掉。 */
    private var ourClip: String? = null

    private fun clipboard(): android.content.ClipboardManager? = try {
        privilegedContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    } catch (t: Throwable) {
        Log.w(TAG, "取剪贴板失败", t)
        null
    }

    override fun setClipText(text: String): Int {
        val cm = clipboard() ?: return CLIP_FAILED
        return try {
            val current = runCatching {
                cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(privilegedContext)?.toString()
            }.getOrNull()
            if (savedClip == null) {
                // 读不到原剪贴板时不动它：宁可放弃这次粘贴，也不让用户丢掉复制的内容
                if (current == null && cm.hasPrimaryClip()) return CLIP_UNREADABLE
                savedClip = current ?: ""
            }
            val clip = android.content.ClipData.newPlainText("ZorvAI", text)
            runCatching {
                // API 33+ 标为敏感：不进剪贴板历史，也不给输入法联想用
                if (Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = android.os.PersistableBundle().apply {
                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
            }
            cm.setPrimaryClip(clip)
            ourClip = text
            CLIP_SET
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed", t)
            CLIP_FAILED
        }
    }

    override fun restoreClip() {
        val original = savedClip ?: return
        restoreClipText(original, ourClip ?: "")
    }

    override fun savedClipText(): String? = savedClip

    override fun restoreClipText(original: String, ours: String) {
        val cm = clipboard() ?: return
        runCatching {
            val current = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(privilegedContext)?.toString()
            // 用户已经换成别的内容了：不要覆盖他的新内容
            if (ours.isNotEmpty() && current != null && current != ours) {
                Log.i(TAG, "剪贴板已被用户换掉，不还原")
                return
            }
            if (original.isEmpty()) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ZorvAI", original))
            }
            savedClip = null
            ourClip = null
        }.onFailure { Log.w(TAG, "restoreClipText failed", it) }
    }

    // ── 打开链接 / 系统动作 / 分享 ──

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int {
        if (!ownDisplay(displayId)) return START_ERROR
        if (uri.isNullOrBlank()) return START_INVALID_LINK
        if (packageName != null && inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
        val intent = try {
            Intent(action?.takeIf { it.isNotBlank() } ?: Intent.ACTION_VIEW, android.net.Uri.parse(uri))
        } catch (t: Throwable) {
            return START_INVALID_LINK
        }
        packageName?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        if (intent.resolveActivity(privilegedContext.packageManager) == null) return START_UNRESOLVED
        return if (startIntentOnDisplay(intent, displayId, USER_CURRENT)) START_OK else START_ERROR
    }

    override fun startSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int {
        if (!ownDisplay(displayId)) return START_ERROR
        // asMika（以 App 身份启动）只能在 App 进程里发；这里以 shell 身份先试，
        // 页面要求 App 权限时会失败并如实返回，由调用方决定是否退回 App 侧。
        val ok = startIntentOnDisplay(intent, displayId, USER_CURRENT)
        if (!ok && asMika != null) {
            Log.i(TAG, "shell 启动系统动作失败，交由 App 侧以自身身份重试")
        }
        return if (ok) START_OK else START_ERROR
    }

    override fun startShare(displayId: Int, packageName: String, text: String): Int {
        if (!ownDisplay(displayId)) return START_ERROR
        if (packageName.isBlank()) return START_NOT_FOUND
        if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(packageName)
        }
        if (intent.resolveActivity(privilegedContext.packageManager) == null) return START_UNRESOLVED
        return if (startIntentOnDisplay(intent, displayId, USER_CURRENT)) START_OK else START_ERROR
    }

    // ── 命令 ──

    override fun runSystemCommand(displayId: Int, command: String): Bundle = Bundle().apply {
        // 带屏号的命令：am/pm 支持 --display，其余命令在这里只影响需要屏上下文的那部分
        val out = runCatching { exec(command) }.getOrElse { "exec failed: ${it.message}" }
        putInt("exitCode", if (out.startsWith("exec failed")) -1 else 0)
        putString("output", out)
    }

    override fun runCommand(command: String): Bundle = Bundle().apply {
        val out = runCatching { exec(command) }.getOrElse { "exec failed: ${it.message}" }
        putInt("exitCode", if (out.startsWith("exec failed")) -1 else 0)
        putString("output", out)
    }

    // ── 用户 / 包 ──

    override fun listUsers(): String? =
        runCatching { exec("pm list users") }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun listPackages(userId: Int): String? = runCatching {
        exec(if (userId >= 0) "pm list packages --user $userId" else "pm list packages")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun isSystemPackage(packageName: String): Boolean = try {
        val ai = privilegedContext.packageManager.getApplicationInfo(packageName, 0)
        (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (t: Throwable) {
        false
    }

    override fun appCopyUsers(): Bundle? = try {
        val um = privilegedContext.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        val self = android.os.Process.myUid() / 100000
        // userProfiles 的元素类型在不同 SDK 上被擦成 Any?，用反射取编号最稳
        val users = um.userProfiles.mapNotNull { uh ->
            runCatching { uh.javaClass.getMethod("getIdentifier").invoke(uh) as Int }.getOrNull()
        }.filter { it != self }
        Bundle().apply {
            putIntArray(KEY_COPY_USERS, users.toIntArray())
            putString("summary", users.joinToString(","))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "appCopyUsers failed", t)
        null
    }

    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int {
        if (!ownDisplay(displayId)) return START_ERROR
        val allowed = appCopyUsers()?.getIntArray(KEY_COPY_USERS)?.toSet() ?: emptySet()
        if (userId !in allowed) return START_USER_NOT_ALLOWED
        if (cold) runCatching { exec("am force-stop --user $userId $packageName") }
        val intent = launchIntent(packageName) ?: return START_NOT_FOUND
        return if (startIntentOnDisplay(intent, displayId, userId)) START_OK else START_ERROR
    }

    // ── 设备准备 ──

    override fun wakeDevice(): Boolean {
        val out = runCatching { exec("input keyevent KEYCODE_WAKEUP") }.getOrDefault("")
        return !out.contains("Error")
    }

    override fun dismissDeviceKeyguard(): Boolean {
        val out = runCatching { exec("wm dismiss-keyguard") }.getOrDefault("")
        return !out.contains("Error") && !out.contains("Exception")
    }

    override fun enterDeviceCredential(kind: Int, credential: ByteArray): Boolean {
        // 输入锁屏凭据需要系统解锁会话（IWindowManager.dismissKeyguard + 凭据校验），
        // shell 身份拿不到这条通道。这里如实返回 false，由上层提示用户手动解锁。
        Log.i(TAG, "enterDeviceCredential 不支持（shell 无法代表用户提交凭据）kind=$kind")
        return false
    }

    // ── 生命周期 ──

    override fun identity(): IntArray =
        intArrayOf(android.os.Process.myUid(), android.os.Process.myPid())

    override fun releaseAll() {
        runCatching { restoreClip() }
        val ids = synchronized(displays) { displays.keys.toList() }
        ids.forEach { runCatching { releaseDisplay(it) } }
        lastClipNote = null
        Log.i(TAG, "releaseAll: 还原剪贴板、释放 ${ids.size} 块屏")
    }

    private var lastClipNote: String? = null

    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? = try {
        // 名字必须是本应用进程的 WebView 调试套接字，防止连到别人的
        if (!name.startsWith("webview_devtools_remote_")) return null
        val socket = android.net.LocalSocket()
        socket.connect(android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT))
        // LocalSocket 不是 java.net.Socket，fromSocket 不接受；直接复制底层 fd
        ParcelFileDescriptor.dup(socket.fileDescriptor)
    } catch (t: Throwable) {
        Log.w(TAG, "openDevtoolsSocket failed: $name", t)
        null
    }

}
