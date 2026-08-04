package com.ai.assistance.quro.core.novaterm.executor

import com.ai.assistance.quro.core.novaterm.command.CommandResult
import com.ai.assistance.quro.core.privilege.QuroRootGateway
import java.io.BufferedReader
import java.io.DataOutputStream
import java.util.UUID

/**
 * NovaTerm 的 Root 执行适配层。
 *
 * ## E-7 之后的定位
 *
 * 本类**不再自己实现** quoting / 超时 / 降级 / 审计——那些全部收敛到
 * [QuroRootGateway]。这里只剩两件事：
 *
 *  1. 把 [QuroRootGateway.RootResult] 适配成 NovaTerm 的 [CommandResult]；
 *  2. 维护一个**持久 su shell**（`cd` / 环境变量能跨命令保留），这是网关的
 *     一次性 `su -c` 做不到的，所以必须留在这里。
 *
 * ## 持久 shell 的三个历史 bug（已修）
 *
 *  - 每次调用都 `process.inputStream.bufferedReader()` 新建 reader：
 *    上一次调用残留在旧 reader 缓冲区里的字节会被整段丢掉，输出随机缺失。
 *    → 改为**缓存同一个 reader**。
 *  - 固定哨兵 `NOVA_ROOT_DONE_`：命令自身输出里出现这个词就会提前截断。
 *    → 改为**每次随机**（`NOVA_ROOT_<uuid8>_<exit>`），并顺带带回 exit code。
 *  - `readLine()` 无超时：命令挂起时调用线程永久阻塞。
 *    → 改为**读取截止时间**（deadline）+ `ready()` 轮询。
 */
object RootExecutor {

    /** 持久 shell 单条命令的默认等待上限。 */
    private const val SHELL_TIMEOUT_MS: Long = 15_000L

    /** 等待 shell 输出时的轮询间隔。 */
    private const val POLL_INTERVAL_MS: Long = 20L

    private var suProcess: Process? = null
    private var suOutputStream: DataOutputStream? = null

    /**
     * 持久 shell 的 stdout reader。
     *
     * 必须整个会话共用一个：`BufferedReader` 自带缓冲区，
     * 每次新建都会连同已预读进缓冲区的字节一起丢弃。
     */
    private var suReader: BufferedReader? = null

    @Volatile
    private var isRootAvailable: Boolean? = null

    // ════════════════════════════════════════
    // 可用性
    // ════════════════════════════════════════

    /**
     * 检测 Root 是否可用（结果缓存）。
     *
     * 委托给 [QuroRootGateway.isRootAvailable]：校验 `echo root_ok` 的真实回显，
     * 带 5s 超时与 FD 回收。旧实现 `waitFor()` 无超时，su 卡住时会永久阻塞调用线程；
     * 且「进程退出得快」被当成 root 可用，su 被拒绝时会误报成功。
     *
     * **阻塞**调用，需在 IO 线程执行。
     */
    fun checkRoot(): Boolean {
        isRootAvailable?.let { return it }
        val ok = QuroRootGateway.isRootAvailable()
        isRootAvailable = ok
        return ok
    }

    /** 清除 root 可用性缓存（用户在 Root 管理器里改了授权后可调）。 */
    fun invalidateRootCache() {
        isRootAvailable = null
        QuroRootGateway.invalidateCache()
    }

    // ════════════════════════════════════════
    // 一次性执行（委托网关）
    // ════════════════════════════════════════

    /**
     * 执行一条 root 命令。
     *
     * 走 [QuroRootGateway.exec]，因此自动获得：数组形式 argv（不会被空格拆词）、
     * 强制超时、stdout/stderr 双线程读干净、Shizuku→su 降级、审计落盘。
     *
     * @param command 完整 shell 命令，调用方无需自己加引号
     * @param timeoutMs 超时毫秒
     */
    fun execute(command: String, timeoutMs: Long = QuroRootGateway.DEFAULT_TIMEOUT_MS): CommandResult {
        if (command.isBlank()) return CommandResult.err("Empty command.")

        val res = QuroRootGateway.exec(
            context = null,
            command = command,
            timeoutMs = timeoutMs,
            capsuleId = "novaterm.root",
        )

        return when {
            res.error.isNotBlank() -> CommandResult.err(res.error)
            res.timedOut -> CommandResult.err(
                "命令超时（${timeoutMs}ms）已终止" +
                    if (res.output.isNotBlank()) "\n已捕获输出：\n${res.output}" else ""
            )
            res.success -> CommandResult.ok(res.output)
            else -> CommandResult.Text(
                output = res.output.ifBlank { "Command failed with exit code ${res.exitCode}" },
                exitCode = res.exitCode,
                isError = true,
            )
        }
    }

    // ════════════════════════════════════════
    // 持久 su shell（保留状态）
    // ════════════════════════════════════════

    /**
     * 获取（必要时创建）持久 su shell 的输入流。
     *
     * 创建成功时同时缓存 [suReader]，保证整个会话共用一个带缓冲的 reader。
     */
    private fun getSuShell(): DataOutputStream? {
        val existing = suOutputStream
        if (existing != null && suProcess?.isAlive == true) return existing

        // 进程已死则先彻底回收，避免 FD 泄漏
        if (suProcess != null) closeShell()

        return try {
            val process = Runtime.getRuntime().exec("su")
            suProcess = process
            suOutputStream = DataOutputStream(process.outputStream)
            suReader = process.inputStream.bufferedReader()
            suOutputStream
        } catch (e: Exception) {
            closeShell()
            null
        }
    }

    /**
     * 在持久 su shell 中执行（适合需要保留 `cd` / 环境变量的多条命令）。
     *
     * 用随机哨兵界定命令边界，并从哨兵后缀里取回真实 exit code；
     * 超过 [SHELL_TIMEOUT_MS] 未见哨兵则放弃等待并**关闭 shell**
     * （此时 shell 状态已不可信，继续复用会串台）。
     */
    fun executeInShell(command: String, timeoutMs: Long = SHELL_TIMEOUT_MS): CommandResult {
        if (command.isBlank()) return CommandResult.err("Empty command.")

        val out = getSuShell() ?: return CommandResult.err("Failed to open su shell（su 被拒绝或设备未 Root）")
        val reader = suReader ?: return CommandResult.err("Failed to open su shell reader")

        // 每次随机，避免命令自身输出里恰好含有哨兵导致提前截断
        val sentinel = "NOVA_ROOT_" + UUID.randomUUID().toString().replace("-", "").take(8)

        return try {
            // stderr 也并进 stdout，否则错误信息会留在没人读的 errorStream 里把管道写满
            out.writeBytes("$command 2>&1\n")
            out.writeBytes("echo ${sentinel}_$?\n")
            out.flush()

            val deadline = System.currentTimeMillis() + timeoutMs
            val lines = mutableListOf<String>()
            var exitCode = 0
            var done = false

            while (!done) {
                if (System.currentTimeMillis() > deadline) {
                    closeShell()
                    return CommandResult.err(
                        "持久 shell 命令超时（${timeoutMs}ms），已关闭 shell 以避免状态串台" +
                            if (lines.isNotEmpty()) "\n已捕获输出：\n${lines.joinToString("\n")}" else ""
                    )
                }
                // ready() 为 false 时短暂让出 CPU，避免 readLine() 无限期阻塞
                if (!reader.ready()) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }
                val line = reader.readLine() ?: break
                val idx = line.indexOf(sentinel)
                if (idx >= 0) {
                    // 哨兵可能与前面的输出粘在同一行（命令没有以换行结尾时）
                    if (idx > 0) lines.add(line.substring(0, idx))
                    exitCode = line.substring(idx + sentinel.length)
                        .removePrefix("_")
                        .trim()
                        .toIntOrNull() ?: 0
                    done = true
                } else {
                    lines.add(line)
                }
            }

            val body = lines.joinToString("\n").trim()
            if (exitCode == 0) {
                CommandResult.ok(body)
            } else {
                CommandResult.Text(
                    output = body.ifBlank { "Command failed with exit code $exitCode" },
                    exitCode = exitCode,
                    isError = true,
                )
            }
        } catch (e: Exception) {
            closeShell()
            CommandResult.err("Su shell error: ${e.message}")
        }
    }

    /** 关闭持久 shell 并回收全部 FD。可重复调用。 */
    fun closeShell() {
        runCatching {
            suOutputStream?.writeBytes("exit\n")
            suOutputStream?.flush()
        }
        runCatching { suOutputStream?.close() }
        runCatching { suReader?.close() }
        runCatching { suProcess?.destroy() }
        suOutputStream = null
        suReader = null
        suProcess = null
    }
}
