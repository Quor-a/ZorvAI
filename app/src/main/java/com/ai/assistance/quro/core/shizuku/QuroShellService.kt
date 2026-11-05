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

        // ── 「接管阶梯」新增结果码（2026-11-02）──────────────────────────────
        // 用户原话：「AI 根本不会换起打开老是说在主屏」「直接接管执行任务，不是提示」。
        // 旧实现一见主屏有该包的任务就返回 1（IN_USE_ON_MAIN），上层只能放弃并解释，
        // 于是任何「用户开过这个 App」的场景都变成打不开。现在改成逐级接手，
        // 用这些码把「怎么接手的」如实告诉上层，而不是让上层只会说「打不开」。
        //
        // 🔴 10~14 是**新增**码：mika-phone 的 ShellCodes 不认识它们会走 else 分支，
        //    因此这里绝不能复用 0~8 的既有语义（那是跨模块契约，逐值对应）。
        private const val START_OK_RESUMED = 10   // 本屏已有，已带到前台（状态全保留）
        private const val START_OK_MOVED = 11     // 从别的屏/主屏挪过来了（任务没被杀）
        private const val START_OK_SECOND = 12    // 在后台屏另起了独立实例，用户的没动
        private const val START_OK_TAKEOVER = 13  // 主屏那份被接管挪到后台屏

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

        /** PowerManager.USER_ACTIVITY_EVENT_TOUCH：替屏报活动时用「触摸」这一种。 */
        private const val USER_ACTIVITY_TOUCH = 2
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
            // 🔴 2026-11-02 补：悬浮窗上的导航键（用户反馈「只能点击滑动，返回/手机键都没有」）。
            // 这三个只注入到本服务自建的虚拟屏（injectKey 已用 ownDisplay 限定 displayId），
            // 不会误触用户主屏，因此放开是安全的。
            KeyEvent.KEYCODE_HOME,        // Home：回到虚拟屏的桌面
            KeyEvent.KEYCODE_APP_SWITCH,  // 最近任务：虚拟屏上的多任务
            KeyEvent.KEYCODE_MENU,        // 菜单键（部分老 App 还需要）
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

            // ═══════════════════════════════════════════════════════════════
            // 接管阶梯（2026-11-02 重写，替代原来的「见到主屏就放弃」）
            //
            // 旧实现：只要该包在主屏「有任何任务」就 return START_IN_USE_ON_MAIN。
            // 两个致命问题：
            //   ① 判定过宽 —— 用户切走过微信，它的任务仍留在任务栈里，
            //      于是这个包**永远**被判「用户正在用」，再也打不开；
            //   ② 需求反了 —— 用户要的是「直接接管」，不是被反问一句「你在主屏用着」。
            //
            // 现在按代价从低到高逐级尝试，取第一个成功的手段：
            //   ① 本屏已有 → 带到前台（最省，状态全保留）
            //   ② 自己另一块后台屏有 → 挪过来
            //   ③ 主屏有、但用户没在看（不可见 / 不在焦点）→ 挪过来（无可感打扰）
            //   ④ 主屏有、且用户正看着 → 先在后台屏「另起独立实例」，不碰他正在看的那份；
            //      起不来才真的接管（挪走），并如实告诉上层「已接管」
            // 只有「挪不动 + 起不来」才返回 START_IN_USE_ON_MAIN。
            // ═══════════════════════════════════════════════════════════════

            // ① 本屏已有该 app：带到前台，不重启
            taskIdOn(displayId, packageName)?.let { tid ->
                if (bringToFront(tid)) return START_OK_RESUMED
            }

            // ② 自己的另一块后台屏上有：挪过来
            otherOwnTask(packageName, displayId)?.let { pair ->
                if (moveTask(pair.first, displayId)) {
                    taskIdOn(displayId, packageName)?.let { bringToFront(it) }
                    return START_OK_MOVED
                }
            }

            // ③ / ④ 主屏状态
            val main = mainScreenTask(packageName)
            if (main != null) {
                if (!main.visible) {
                    // 用户没在看（任务只是留在栈里）：直接挪过来，他感觉不到
                    if (moveTask(main.taskId, displayId)) {
                        taskIdOn(displayId, packageName)?.let { bringToFront(it) }
                        return START_OK_MOVED
                    }
                }
                // 用户可能正看着，或挪不动：先另起独立实例，绝不先动他那一份
                if (startFresh(displayId, packageName, multiple = true)) return START_OK_SECOND
                // 起不来才接管
                if (moveTask(main.taskId, displayId)) {
                    taskIdOn(displayId, packageName)?.let { bringToFront(it) }
                    return START_OK_TAKEOVER
                }
                Log.w(TAG, "startApp($packageName): 主屏在用且无法接管（挪不动也起不来）")
                return START_IN_USE_ON_MAIN
            }

            // ⑤ 全新启动
            // 🔴 launchIntent 为空要单独返回 START_NOT_FOUND(2)：上层靠这个码去查分身/工作资料。
            //    如果一律返回 START_ERROR(3)，「只装在分身里」的应用就永远查不到也打不开。
            if (launchIntent(packageName) == null) return START_NOT_FOUND
            return if (startFresh(displayId, packageName, multiple = false)) START_OK else START_ERROR
        }
    }

    /** 某屏上某包的任务号；getTasks 按最近使用排序，取第一个即最新的那个。 */
    private fun taskIdOn(displayId: Int, packageName: String): Int? = try {
        val idField = TASK_INFO.getField("taskId")
        tasksOn(displayId).firstOrNull { packagesOf(it).contains(packageName) }
            ?.let { idField.getInt(it) }
    } catch (t: Throwable) {
        null
    }

    /** 自己的其它后台屏上该包的任务：(taskId, displayId)。 */
    private fun otherOwnTask(packageName: String, displayId: Int): Pair<Int, Int>? = try {
        val others = synchronized(displays) { displays.keys.toSet() } - displayId
        if (others.isEmpty()) {
            null
        } else {
            val idField = TASK_INFO.getField("taskId")
            allTasks().firstOrNull {
                displayOfTask0(it) in others && packagesOf(it).contains(packageName)
            }?.let { idField.getInt(it) to displayOfTask0(it) }
        }
    } catch (t: Throwable) {
        null
    }

    /** 把某个 task 带到它所在屏的最前面。 */
    private fun bringToFront(taskId: Int): Boolean {
        if (taskId < 0) return false
        return try {
            val m = taskManager.javaClass.methods.firstOrNull {
                it.name == "moveTaskToFront" && it.parameterTypes.size == 3
            }
            if (m != null) {
                m.invoke(taskManager, taskId, 0, null)
                true
            } else {
                // 没有反射入口就如实返回 false，让阶梯继续往下走 —— 不要假装成功
                // （否则上层以为已经切到前台，实际界面还停在上一个 App）
                Log.w(TAG, "moveTaskToFront 反射不可用，跳过「带到前台」这一级")
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bringToFront($taskId) failed", t)
            false
        }
    }

    /** 主屏上该包的任务，以及它是否**真的**对用户可见（= 他此刻在看）。 */
    private class MainTask(val taskId: Int, val visible: Boolean)

    private fun mainScreenTask(packageName: String): MainTask? = try {
        val idField = TASK_INFO.getField("taskId")
        val hit = allTasks().firstOrNull {
            displayOfTask0(it) == MAIN_DISPLAY && packagesOf(it).contains(packageName)
        } ?: return null
        MainTask(idField.getInt(hit), isTaskVisible(hit))
    } catch (t: Throwable) {
        Log.w(TAG, "mainScreenTask failed", t)
        null
    }

    /**
     * 该任务是否正在对用户可见（= 他此刻真的在看）。
     *
     * 判据按可用性从强到弱：
     *  ① `TaskInfo.isVisible`（API 29+，最准）
     *  ② 是否等于当前焦点 root task（getFocusedRootTaskId）
     *  ③ 兜底：是否等于主屏最近使用的第一个任务
     *
     * 三者都拿不到时**保守判为不可见**：宁可接管（任务状态保留、可挪回），
     * 也不要因为「读不到字段」就一律放弃 —— 那正是「老是说在主屏」的来源。
     */
    private fun isTaskVisible(task: Any): Boolean {
        val id = TASK_INFO.getField("taskId").getInt(task)
        // ① isVisible 方法 / 字段
        runCatching {
            val m = task.javaClass.methods.firstOrNull { it.name == "isVisible" && it.parameterTypes.isEmpty() }
            if (m != null) return (m.invoke(task) as? Boolean) ?: false
        }
        runCatching {
            val f = TASK_INFO.getDeclaredField("isVisible").apply { isAccessible = true }
            return f.getBoolean(task)
        }
        // ② 焦点 task
        val focused = focusedTaskId()
        if (focused > 0) return focused == id
        // ③ 主屏最近使用的第一个
        return runCatching {
            tasksOn(MAIN_DISPLAY).firstOrNull()
                ?.let { TASK_INFO.getField("taskId").getInt(it) } == id
        }.getOrDefault(false)
    }

    private fun focusedTaskId(): Int = runCatching {
        val m = taskManager.javaClass.methods.firstOrNull {
            it.name == "getFocusedRootTaskId" && it.parameterTypes.isEmpty()
        } ?: return -1
        (m.invoke(taskManager) as? Int) ?: -1
    }.getOrDefault(-1)

    /**
     * 在指定屏上新起一个实例。
     *
     * [multiple] = true 时加 FLAG_ACTIVITY_MULTIPLE_TASK：即使主屏已有同包任务，
     * 也在后台屏另起一个独立实例 —— 这是「不打扰用户正在用的那一份」的关键。
     * 不少 App（微信、QQ、浏览器）都支持，支持不了会返回失败，由调用方决定是否接管。
     */
    private fun startFresh(displayId: Int, packageName: String, multiple: Boolean): Boolean {
        val intent = launchIntent(packageName) ?: run {
            Log.w(TAG, "no launch intent for $packageName")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (multiple) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        return startIntentOnDisplay(intent, displayId, USER_CURRENT)
    }

    private fun launchIntent(packageName: String): Intent? = try {
        privilegedContext.packageManager.getLaunchIntentForPackage(packageName)
    } catch (t: Throwable) {
        Log.w(TAG, "getLaunchIntentForPackage failed: $packageName", t)
        null
    }

    /**
     * 按 userId 解析 launch intent。
     *
     * 应用分身 / 工作资料里的包在**主用户的 PackageManager 里可能查不到**，
     * 必须按目标用户解析，否则多开应用永远返回「没装」。
     * 依次尝试：
     *  ① `PackageManager.getLaunchIntentForPackageAsUser(pkg, flags)`（@SystemApi，shell 可反射）
     *  ② `cmd package resolve-activity --user N` 解析 LAUNCHER 组件，再自建 intent
     *  ③ 退化为普通 [launchIntent]（同用户 0 的情况仍能用）
     */
    private fun launchIntentAsUser(packageName: String, userId: Int): Intent? {
        if (userId == USER_CURRENT || userId < 0) return launchIntent(packageName)

        // ① SystemApi 反射
        runCatching {
            val m = android.content.pm.PackageManager::class.java.methods.firstOrNull {
                it.name == "getLaunchIntentForPackageAsUser" && it.parameterTypes.size == 2
            }
            val it0 = m?.invoke(privilegedContext.packageManager, packageName, 0) as? Intent
            if (it0 != null) return it0
        }.onFailure { Log.d(TAG, "getLaunchIntentForPackageAsUser 不可用", it) }

        // ② 命令行解析该用户下的 LAUNCHER 组件
        runCatching {
            val out = exec(
                "cmd package resolve-activity --user $userId" +
                    " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName"
            )
            // 输出里有 `name=com.tencent.mm/.ui.LauncherUI` 或 `com.tencent.mm/com.tencent.mm.ui.LauncherUI`
            val raw = Regex("""name=([A-Za-z][\w.]*)/([\w.$]+)""").find(out)?.let {
                it.groupValues[1] to it.groupValues[2]
            } ?: Regex("""([A-Za-z][\w.]*)/(\.?[\w.$]+)""").find(out)?.let {
                it.groupValues[1] to it.groupValues[2]
            }
            if (raw != null) {
                val pkg = raw.first
                val cls = if (raw.second.startsWith(".")) pkg + raw.second else raw.second
                return Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                    component = ComponentName(pkg, cls)
                }
            }
        }.onFailure { Log.d(TAG, "resolve-activity --user $userId 失败", it) }

        // ③ 退化
        return launchIntent(packageName)
    }

    /**
     * 该包是否**真的正被用户看着**（主屏可见 / 在焦点上）。
     *
     * 🔴 2026-11-02 语义修正：原来只要「主屏任务栈里出现过这个包」就返回 true，
     * 用户切走过的 App 任务还留在栈里，于是永远被判「在用」——这是
     * 「AI 老是说在主屏、多开应用打不开」的直接原因。现在必须真的可见才算。
     */
    private fun inUseOnMainScreen(packageName: String): Boolean =
        mainScreenTask(packageName)?.visible == true

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
            // 🔴 2026-11-02 修 fallback：原来只拼 -a/-d/-p，对 launch intent（MAIN/LAUNCHER）
            // 完全不够用 —— am 会当成 VIEW 去解析，常常直接 Error；现在优先用 -n 组件，
            // 拿不到组件才退化为 MAIN + LAUNCHER + 包名。
            val flags = buildString {
                append("--display ").append(displayId)
                if (userId != USER_CURRENT && userId >= 0) append(" --user ").append(userId)
                val comp = intent.component
                if (comp != null) {
                    append(" -n ").append(comp.flattenToShortString())
                } else {
                    append(" -a ").append(intent.action ?: Intent.ACTION_MAIN)
                    append(" -c ").append(intent.categories?.firstOrNull() ?: Intent.CATEGORY_LAUNCHER)
                    intent.`package`?.let { append(" -p ").append(it) }
                    intent.dataString?.let { append(" -d ").append(it) }
                }
                if (intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0) append(" -f 0x08000000")
            }
            val out = runCatching { exec("am start $flags") }.getOrDefault("")
            !out.contains("Error") && !out.contains("Exception")
        }
    }

    override fun restartApp(displayId: Int, packageName: String): Int {
        if (packageName.isBlank()) return START_ERROR
        if (!ownDisplay(displayId)) return START_ERROR
        // 在别的自己的屏上还有任务：结束进程会连带杀掉那份，于是降级为普通启动
        val elsewhere = onOtherOwnDisplay(packageName, displayId)
        // 🔴 2026-11-02：不再「用户在主屏就放弃」。而且用户正看着这份时**不做 force-stop** ——
        // 那会把他眼前的 App 直接干掉。交给 startApp 的接管阶梯去另起独立实例或挪过来。
        val r = if (inUseOnMainScreen(packageName)) {
            startApp(displayId, packageName)
        } else {
            runCatching { exec("am force-stop $packageName") }
            startApp(displayId, packageName)
        }
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
        // 🔴 2026-11-02：原来这里 `if (packageName != null && inUseOnMainScreen(...)) return
        // START_IN_USE_ON_MAIN`，等于用户主屏开着浏览器就永远打不开链接。
        // 现在不拒绝，改为 MULTIPLE_TASK 在后台屏另起一个实例，用户那份不动。
        val intent = try {
            Intent(action?.takeIf { it.isNotBlank() } ?: Intent.ACTION_VIEW, android.net.Uri.parse(uri))
        } catch (t: Throwable) {
            return START_INVALID_LINK
        }
        packageName?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        if (intent.resolveActivity(privilegedContext.packageManager) == null) return START_UNRESOLVED
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
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
        // 同上：不再因为主屏有该包就拒绝，改为 MULTIPLE_TASK 另起实例
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(packageName)
        }
        if (intent.resolveActivity(privilegedContext.packageManager) == null) return START_UNRESOLVED
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
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

    /**
     * 本机的「用户空间」清单（应用分身 / 手机分身 / 工作资料），**排除当前用户自己**。
     *
     * 🔴 2026-11-02 修：原来只用 `UserManager.userProfiles`。它只返回与当前用户**有关联**
     * 的 profile，国产 ROM 的应用分身（OPPO 常见 user 999、小米 999/10 等）往往不在里面，
     * 于是「多开的应用」在 AI 眼里就是「没装」，永远打不开。
     * 现在先 `pm list users` 拿全量（shell 身份看得到所有用户），再用 userProfiles 补齐。
     */
    override fun appCopyUsers(): Bundle? = try {
        val ids = sortedSetOf<Int>()
        // ① 全量用户：pm list users 输出形如 `UserInfo{999:分身:13} running`
        runCatching {
            val out = exec("pm list users")
            Regex("""UserInfo\{(\d+):""").findAll(out).forEach { ids += it.groupValues[1].toInt() }
        }.onFailure { Log.w(TAG, "pm list users failed", it) }
        // ② userProfiles 补齐（命令行偶有缺项：工作资料 / 克隆 profile）
        runCatching {
            val um = privilegedContext.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            um.userProfiles.forEach { uh ->
                runCatching { uh.javaClass.getMethod("getIdentifier").invoke(uh) as Int }
                    .getOrNull()?.let { ids += it }
            }
        }.onFailure { Log.w(TAG, "userProfiles failed", it) }

        val self = android.os.Process.myUid() / 100000
        val users = ids.filter { it != self }.sorted()
        Bundle().apply {
            putIntArray(KEY_COPY_USERS, users.toIntArray())
            putString("summary", "用户空间：" + users.joinToString(","))
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
        // 🔴 必须按目标用户取 launch intent：分身里装的包在主用户的 PackageManager 里
        // 可能根本查不到（那种「只在 user 999 装着」的情况），用 user 0 的
        // getLaunchIntentForPackage 会返回 null → 上层判定「没装」→ 多开应用永远打不开。
        val intent = launchIntentAsUser(packageName, userId) ?: return START_NOT_FOUND
        val ok = startIntentOnDisplay(intent, displayId, userId)
        Log.d(TAG, "startAppInUser($packageName, user=$userId) on $displayId -> $ok")
        return if (ok) START_OK else START_ERROR
    }

    // ── 设备准备 ──

    override fun wakeDevice(): Boolean {
        val out = runCatching { exec("input keyevent KEYCODE_WAKEUP") }.getOrDefault("")
        return !out.contains("Error")
    }

    /**
     * 替后台屏报一次用户活动（见 AIDL 注释）。
     *
     * 三道闸，任何一道不过都返回 false，让 App 侧停掉轮询而不是反复无效调用：
     *  1. 只认本服务自建的屏（绝不替用户主屏报）；
     *  2. 只有 Android 13+ 建屏时才带 OWN_DISPLAY_GROUP，才自成一组、才有独立息屏计时；
     *     低于 13 报活动会报到默认组，等于让用户的主屏不灭 —— 不能做；
     *  3. 系统必须真有 `userActivity(int displayId, long, int, int)` 这个按屏重载（安卓 12 起）。
     *
     * shell 身份对 power 服务通常有权限；没有时（部分 ROM 收紧）会抛 SecurityException，
     * 这里吞掉并返回 false，上层据此放弃保活，屏照常用，只是可能自己睡。
     */
    @SuppressLint("PrivateApi")
    override fun pokeUserActivity(displayId: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val m = userActivityMethod ?: return false
            m.invoke(powerManager, displayId, SystemClock.uptimeMillis(), USER_ACTIVITY_TOUCH, 0)
            true
        } catch (t: Throwable) {
            if (!pokeWarned) {
                pokeWarned = true
                Log.w(TAG, "pokeUserActivity 不可用（屏可能自己息屏）：${t.message}")
            }
            false
        }
    }

    /** 只警告一次，4 秒一条日志会把 logcat 刷爆。 */
    private var pokeWarned = false

    /** IPowerManager：shell 身份直接问 ServiceManager 要，不能走 App 的 getSystemService。 */
    @Suppress("PrivateApi")
    private val powerManager: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "power") as? android.os.IBinder
            ?: throw IllegalStateException("power service not found")
        Class.forName("android.os.IPowerManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)!!
    }

    @Suppress("PrivateApi")
    private val userActivityMethod: java.lang.reflect.Method? by lazy {
        val perDisplay = arrayOf<Class<*>>(
            Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        )
        runCatching { powerManager.javaClass.methods.firstOrNull { it.name == "userActivity" && it.parameterTypes.contentEquals(perDisplay) } }.getOrNull()
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
