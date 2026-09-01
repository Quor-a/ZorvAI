package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.os.Process
import android.util.Log
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.File

/**
 * 终端「幽灵进程猎手」（phantom killer，路线图 P1）。
 *
 * ## 问题
 * 终端会话 = proot + 常驻 shell。如果只杀“直接进程”而不杀“进程组”：
 *  - 会话被 destroy / 强制停止时，proot 拉起的子进程（sh、用户后台作业、python REPL、嵌套 shell）
 *    不会跟着死，变成残留孤儿；
 *  - App 崩溃 / 被系统杀后重启，上一轮的 proot 被 reparent 到 init(pid 1)，
 *    继续占 CPU/内存，且下次 proot 启动时可能撞上残留的 dpkg 锁 / 端口。
 *
 * ## 本类只做两件事，且一律用**精确身份判定**（proot 二进制路径 + 我们的 rootfs 路径），
 * 绝不误杀其它进程：
 *  1. [killTree]：杀掉某个会话根 PID 的整棵进程树（proot + shell + 所有后代）；
 *  2. [reapOrphans]：App 启动时扫描 /proc，回收上一轮残留、父进程已死的 proot 进程树。
 *
 * 纯 JVM + /proc 读取，无 JNI 依赖；所有文件/信号操作都包了 try/catch，单条失败不影响其余。
 */
object QuroTerminalReaper {

    private const val TAG = "QuroTermReaper"

    private const val SIGKILL = 9

    /** 启动期回收只跑一次的护栏（reapOrphans 本身幂等，但避免在运行中反复扫 /proc）。 */
    @Volatile
    private var startupReaped = false

    /**
     * 杀掉以 [rootPid] 为根的整棵进程树。
     *
     * 策略：先一次性收集 [rootPid] 的全部后代（基于 /proc 的 PPID 关系），再“由深到浅”
     * 逐个 SIGKILL——即使杀父时子进程被 reparent 到 init，完整后代清单已在手，不会漏杀；
     * 最后才杀 rootPid 本身。
     *
     * 安全护栏：rootPid<=1 或 == 自身进程 pid 直接返回，避免自杀或误伤 init。
     *
     * @param rootPid 根进程 PID（proot 或 sh）
     * @param signal 信号，默认 SIGKILL（猎手语义：目标已是应死 / 无响应进程）
     * @return 实际发出信号（不论是否真存在）的进程数
     */
    fun killTree(rootPid: Int, signal: Int = SIGKILL): Int {
        if (rootPid <= 1) return 0
        val self = Process.myPid()
        if (rootPid == self) return 0

        val (descendants, depth) = descendantsWithDepth(rootPid)
        // 由深到浅：先杀后代再杀祖先，rootPid 深度 0 排最后
        val ordered = (descendants + rootPid).sortedByDescending { depth[it] ?: 0 }

        var sent = 0
        for (pid in ordered) {
            if (pid <= 1 || pid == self) continue
            if (sendSignal(pid, signal)) sent++
        }
        if (sent > 0) Log.i(TAG, "killTree: 已向 $sent 个进程发送信号 $signal（根 pid=$rootPid）")
        return sent
    }

    /**
     * App 启动期回收上一轮残留的 proot 孤儿进程。
     *
     * 判定“是我们的 proot 且是孤儿”，三者**全中**才杀：
     *  - cmdline 含 [QuroLinuxEnv.prootPath]（我们的 proot 二进制）；
     *  - cmdline 含 [QuroLinuxEnv.rootfsPath]（指向本 app 内部存储的 Ubuntu rootfs）；
     *  - PPID == 1（被 reparent 到 init）或 PPID 进程已不存在（父 = 上次 App 已死）。
     *
     * 活会话的 proot 父进程是本次 App（PPID 不会是 1），因此不会被误伤；
     * 其它 App 的 proot 因 cmdline 不含我们的 rootfs 也不会被碰。
     *
     * @return 回收的孤儿进程树节点总数（含根）
     */
    fun reapOrphans(context: Context): Int {
        if (startupReaped) return 0
        startupReaped = true

        val prootBin = runCatching { QuroLinuxEnv.prootPath(context) }.getOrNull() ?: return 0
        val rootfs = runCatching { QuroLinuxEnv.rootfsPath(context) }.getOrNull() ?: return 0
        if (prootBin.isEmpty() || rootfs.isEmpty()) return 0

        val alive = listProcPids().toSet()
        val self = Process.myPid()
        var reaped = 0
        for (pid in alive) {
            if (pid <= 1 || pid == self) continue
            val cmd = cmdlineOf(pid)
            if (!cmd.contains(prootBin)) continue
            if (!cmd.contains(rootfs)) continue
            val ppid = ppidOf(pid)
            val orphaned = ppid <= 1 || !alive.contains(ppid)
            if (!orphaned) continue
            Log.w(TAG, "reapOrphans: 发现残留 proot 孤儿 pid=$pid ppid=$ppid，回收其进程树")
            reaped += killTree(pid, SIGKILL) + 1
        }
        if (reaped > 0) Log.i(TAG, "reapOrphans: 启动期共回收 $reaped 个残留 proot 孤儿进程")
        return reaped
    }

    /** 单元测试 / 调试用：重置启动期护栏（正常进程不会调用）。 */
    internal fun resetStartupGuard() {
        startupReaped = false
    }

    // ───────────────────────── /proc 读取 ─────────────────────────

    /** 列出 /proc 下所有数字目录（即进程 pid）。 */
    private fun listProcPids(): List<Int> = runCatching {
        File("/proc").listFiles { _, name -> name.isNotEmpty() && name.all { it.isDigit() } }
            ?.mapNotNull { it.name.toIntOrNull() } ?: emptyList()
    }.getOrDefault(emptyList())

    /** 读 /proc/<pid>/stat 解析 PPID（第 4 个字段，'(' 之后）。失败返回 -1。 */
    private fun ppidOf(pid: Int): Int = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val close = stat.lastIndexOf(')')
        if (close < 0) return@runCatching -1
        val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
        // rest[0]=state, rest[1]=ppid
        rest.getOrNull(1)?.toIntOrNull() ?: -1
    }.getOrDefault(-1)

    /** 读 /proc/<pid>/cmdline（NUL 分隔，替换为空格），失败返回空串。 */
    private fun cmdlineOf(pid: Int): String = runCatching {
        val bytes = File("/proc/$pid/cmdline").readBytes()
        String(bytes, Charsets.UTF_8).replace('\u0000', ' ').trim()
    }.getOrDefault("")

    /**
     * 基于 /proc 的 PPID 关系，收集 [root] 的全部后代，并算好各自深度（到 root 的跳数）。
     * 返回 (后代 pid 列表, pid→深度)。root 自身不在后代列表里、深度记为 0。
     */
    private fun descendantsWithDepth(root: Int): Pair<List<Int>, Map<Int, Int>> {
        val parent = buildParentMap()
        val depth = mutableMapOf<Int, Int>(root to 0)
        val result = mutableListOf<Int>()
        val queue = ArrayDeque<Int>().apply { add(root) }
        val seen = mutableSetOf(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val curDepth = depth[cur] ?: 0
            for ((pid, p) in parent) {
                if (p == cur && !seen.contains(pid)) {
                    seen.add(pid)
                    result.add(pid)
                    depth[pid] = curDepth + 1
                    queue.add(pid)
                }
            }
        }
        return result to depth
    }

    /** 构建 pid→ppid 映射。 */
    private fun buildParentMap(): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        for (pid in listProcPids()) {
            val p = ppidOf(pid)
            if (p >= 0) map[pid] = p
        }
        return map
    }

    /** 向进程发送信号，成功返回 true。 */
    private fun sendSignal(pid: Int, sig: Int): Boolean =
        runCatching { Process.sendSignal(pid, sig) }.isSuccess
}
