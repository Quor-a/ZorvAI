package com.ai.assistance.quro.core.tools

import android.os.Process as AndroidProcess
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Job

/**
 * 工具执行期派生的**进程登记表** —— 让「停止生成」能真正杀到子进程。
 *
 * ## 背景（issue #11 问题 8，严重度：高）
 * 用户点「停止生成」后界面恢复输入态，但对话框仍继续转圈/跑后续轮次。
 * 代码层定位到两条断链：
 *  1. **取消信号未级联到执行层**：`QuroTool.execOnce` 用
 *     `withTimeout(60s) { droidMcp.callTool(...) }` 包住工具执行，但工具内部是
 *     `Process.waitFor()` / proot 子进程 / Shizuku binder 同步调用等**阻塞式**代码 ——
 *     协程取消与超时都只能「等阻塞调用自己返回」，proot 里跑个 apt install 就是一分钟以上，
 *     取消完全失效；
 *  2. **回灌前未检查停止标志**：工具返回后循环无条件把结果写回上下文继续下一轮
 *     （该条在 `QuroAssistant` 侧修，见 reactPass 的「回灌前短路」）。
 *
 * ## 机制
 * - 每次工具 attempt 开始（[beginRun]）建一个 [Run]，挂在当前协程的 [Job] 上；
 * - 工具 spawn 的进程（proot / su / sh -c / python / 抓包…）立即 [register] 进这个 Run；
 * - [Job] 完成时（用户停止 = 父 job 取消；`withTimeout` 超时 = 子 job 取消）经
 *   `invokeOnCompletion` 在**取消方线程**上执行 [Run.killAll]：
 *   destroy → 杀子孙 → destroyForcibly。
 *   🔴 这一步必须在**别的线程**上做：协程自己还阻塞在 `waitFor` 里，
 *      等它自己跑取消逻辑永远等不到。
 * - 进程被杀后 `waitFor` 立刻返回，阻塞的工具调用随之收尾，取消异常正常上抛。
 *
 * ## 线程模型
 * 登记用 [ThreadLocal]：工具进程都在工具调用的协程线程上同步 spawn，
 * 不需要（也难以做到）跨线程传播。读取用后台线程的（如 QuroRootGateway 的 stdout
 * 读取线程）不 spawn 进程，无需登记。
 */
object QuroToolProcesses {

    private const val TAG = "QuroToolProcesses"

    /** 一次工具 attempt 的进程登记句柄。 */
    class Run(val job: Job?) {
        internal val procs = CopyOnWriteArrayList<Process>()

        @Volatile
        var killed: Boolean = false
            private set

        /**
         * 登记一个派生进程。若 Run 已杀（或 job 已取消），来晚的进程当场杀掉的 ——
         * 防止「用户在 spawn 与登记之间点了停止」这种窄窗漏杀。
         */
        fun register(p: Process) {
            if (killed || (job != null && !job.isActive)) {
                killAll()
                runCatching { p.destroy() }
                runCatching { p.destroyForcibly() }
                return
            }
            procs.add(p)
        }

        fun unregister(p: Process) {
            procs.remove(p)
        }

        /**
         * 强杀本 Run 登记过的全部进程。
         * 幂等：重复调用只杀第一批（后续 register 也会被即时杀）。
         */
        fun killAll() {
            if (killed) return
            killed = true
            val snapshot = procs.toList()
            procs.clear()
            snapshot.forEach { p ->
                // 先温柔 destroy（SIGTERM），再强杀子孙，最后 destroyForcibly（SIGKILL）。
                // proot/su 场景下真正干活的是孙进程，只杀壳没有用。
                runCatching { p.destroy() }
                killDescendants(p)
                runCatching { p.destroyForcibly() }
            }
            if (snapshot.isNotEmpty()) {
                Log.w(TAG, "停止/超时：已强杀 ${snapshot.size} 个工具派生进程")
            }
        }

        /**
         * 反射杀进程子孙。
         *
         * 🔴 为什么不直接 `p.descendants()`：本仓编译用的 android.jar stub **不含**
         * `java.lang.ProcessHandle` 一族（API 26+ 才进 stub，同仓
         * `QuroShellSession` 读 pid 也是反射兜底，见其 197 行注释）。
         * 真机 API 26+ 运行期方法存在，反射调用即可；拿不到就只杀直接子进程
         * （与改造前超时路径的杀伤面一致，不退步）。
         */
        private fun killDescendants(p: Process) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val stream = p.javaClass.getMethod("descendants").invoke(p)
                        as? java.util.stream.Stream<*> ?: return@runCatching
                stream.forEach { child ->
                    runCatching {
                        child?.javaClass?.getMethod("destroyForcibly")?.invoke(child)
                    }
                }
            }
        }
    }

    private val activeRuns = CopyOnWriteArrayList<Run>()
    private val current = ThreadLocal<Run?>()

    /**
     * 开始一次工具 attempt 的进程登记。
     *
     * @param job 工具协程的 Job（`withTimeout` 内侧即超时子 job）。
     *   父 job 取消（用户停止 / barge-in 切会话）会传导到它 → 回调杀进程；
     *   `withTimeout` 超时同样取消这个 job → 超时也真取消（此前超时杀不到阻塞进程）。
     */
    fun beginRun(job: Job?): Run {
        val run = Run(job)
        activeRuns.add(run)
        current.set(run)
        if (job != null) {
            job.invokeOnCompletion {
                // 取消（用户停止/切会话/超时）才杀；正常完成不碰进程。
                if (job.isCancelled) run.killAll()
                activeRuns.remove(run)
                if (current.get() === run) current.set(null)
            }
        }
        return run
    }

    /** 结束一次 attempt（正常路径：不杀进程，只摘除登记）。 */
    fun endRun(run: Run) {
        activeRuns.remove(run)
        if (current.get() === run) current.set(null)
    }

    /** 工具 spawn 进程后调用；无活动 Run（后台初始化等）时安全空转。 */
    fun register(p: Process) {
        current.get()?.register(p)
    }

    /** 进程自行结束后调用，及时摘除避免 Run 持死句柄。 */
    fun unregister(p: Process) {
        current.get()?.unregister(p)
    }

    /**
     * 全局清杀（ViewModel onCleared / 删除会话 / 账号切换）。
     * 不等 job 回调，立即杀掉所有在册进程。
     */
    fun killAllActive() {
        activeRuns.toList().forEach { it.killAll() }
    }

    /** 当前在册进程数（诊断/测试用）。 */
    fun activeProcessCount(): Int = activeRuns.sumOf { it.procs.size }

    /** 清空 ThreadLocal（测试用；防用例间串味）。 */
    fun clearThreadLocal() {
        current.set(null)
    }

    /* ── issue #11 问题 9：工具派生的后台进程脱离 ───────────────────────────
     * `linux_run` 里 `nohup / setsid … &` 起的进程会脱离 shell 继续跑，持续占
     * CPU/IO。根因：shell 退出后这些进程被 reparent 到 init，既不在 Run 的
     * 进程表里、也不在 Process.descendants() 树里（已脱离），常规 killAll 抓不到。
     *
     * 安全做法（不碰任何系统/其他 App 进程）：
     *   1. 命令启动前 [snapshotPids] 拍一份全量 pid 快照；
     *   2. 命令返回后 [reapLeakedSince] 再拍一次，diff 出「这段时间内新冒出来的
     *      pid」—— 这些就是被命令拉起、且命令结束仍活着的漏网进程；
     *   3. 只杀其中 **uid == 本 App uid** 的（proot/su/用户 nohup 进程都跑在
     *      本 App uid 下），且排除本 App 主 pid 自身。
     *   这样绝不会误伤其他 UID 的系统守护 / 其他 App（见 killProcess 的 UID 约束）。
     */

    /** 读取 /proc 下所有数值目录，得到当前活着的进程 pid 集合。 */
    fun snapshotPids(): Set<Int> {
        val out = mutableSetOf<Int>()
        runCatching {
            File("/proc").listFiles()?.forEach { f ->
                val n = f.name.toIntOrNull()
                if (n != null) out.add(n)
            }
        }
        return out
    }

    /**
     * 杀掉 [before] 快照之后新出现的、且属于本 App uid 的进程（漏网后台进程）。
     * @return 实际强杀的进程数。
     */
    fun reapLeakedSince(tag: String, before: Set<Int>): Int {
        val myPid = AndroidProcess.myPid()
        val myUid = AndroidProcess.myUid()
        val after = snapshotPids()
        val leaked = after - before
        if (leaked.isEmpty()) return 0
        var killed = 0
        leaked.forEach { pid ->
            if (pid == myPid) return@forEach
            if (!isSameUid(pid, myUid)) return@forEach
            runCatching {
                AndroidProcess.killProcess(pid)
                killed++
            }
        }
        if (killed > 0) {
            Log.w(TAG, "[$tag] 已强杀 $killed 个命令残留后台进程（issue #11 问题 9）")
        }
        return killed
    }

    /** 读 /proc/<pid>/status 的 Real UID，判断是否与给定 uid 相同（失败默认不同，安全保守）。 */
    private fun isSameUid(pid: Int, uid: Int): Boolean {
        return runCatching {
            val text = File("/proc/$pid/status").readText()
            val line = text.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return@runCatching false
            // Uid:  <real>  <effective>  <saved>  <filesystem>
            val real = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: return@runCatching false
            real == uid
        }.getOrDefault(false)
    }
}
