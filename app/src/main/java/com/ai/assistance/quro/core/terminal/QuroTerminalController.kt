package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.os.Environment
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一次非交互式命令执行的结构化结果（E-8）。
 *
 * 旧的 [runCommand] 只返回一个 `String`，把
 * 「命令失败」「命令超时」「命令成功但没输出」全部糊成人类可读文本，
 * 调用方（AI 工具层）**没有任何办法**判断命令到底成没成功，
 * 只能靠正则去猜 `"⏱ 命令超时"` 这种提示语——极其脆弱。
 *
 * 现在退出码、超时标志、原始输出分开返回，工具层可以如实上报 `exit_code`。
 *
 * @param output stdout + stderr 合并并 trim 后的原始输出（不含任何提示语装饰）
 * @param exitCode 进程退出码；超时或启动失败时为 -1
 * @param timedOut 是否因超时被强杀
 * @param error 启动失败等异常说明；正常执行时为空串
 */
data class ShellResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
    val error: String = "",
) {
    /** 命令是否成功（正常结束且退出码为 0）。 */
    val success: Boolean get() = error.isEmpty() && !timedOut && exitCode == 0

    /** 人类可读渲染（保持与旧版 `runCommand` 相同的表现，供 UI / 旧调用方使用）。 */
    fun render(): String = when {
        error.isNotEmpty() -> "fail: $error"
        timedOut -> "⏱ 命令超时已终止" + if (output.isNotBlank()) "，已捕获输出：\n$output" else ""
        output.isBlank() -> "(no output, exit $exitCode)"
        else -> output
    }
}

/**
 * 终端会话的全局控制器（v127 重写，后端换成自包含 [QuroShellSession]）。
 *
 * **终端架构统一（本次重构）**：本控制器不再自行持有会话，而是把
 * 「默认共享会话」委托给 [QuroTerminalSessionManager] 协调——AI 工具层、终端界面、CMS 开发环境
 * 现在共用同一个默认 [QuroShellSession]，并由管理器提供 list / create / switch / destroy 等管理能力。
 *
 * - [session]：默认共享会话（getter 委托管理器）。
 * - [createSession]：确保默认会话存在（缺失后端则跟随安装），等价于「打开终端」。
 * - [ensureSession]：懒确保默认会话（不触发下载，缺失后端时回退设备 sh）。
 * - [sendToShell]：给用户输入框调用，等价于在提示符后敲回车（带回显 + 哨兵完成检测）。
 * - [runCommand]：非交互式一次性执行（AI terminal_exec 设备回退用），与活动会话解耦，不回显。
 * - [interrupt]：中断当前运行中的命令（E-9），软中断失败则重建会话并回到原目录。
 */
object QuroTerminalController {

    /** 默认共享会话（AI 工具 / CMS / 使用者共用），由 [QuroTerminalSessionManager] 持有。 */
    val session: QuroShellSession?
        get() = QuroTerminalSessionManager.defaultSession

    /** 确保默认会话存在（跟随创建，缺失后端则安装）。等价于「打开终端」。返回默认会话。 */
    fun createSession(context: Context): QuroShellSession =
        runBlocking(Dispatchers.IO) {
            QuroTerminalSessionManager.ensureDefault(context, installIfMissing = true)
                ?: error("无法创建终端会话")
        }

    /** 懒确保：若没有默认会话则创建一个（不触发下载，缺失后端时回退设备 shell）。 */
    fun ensureSession(context: Context): QuroShellSession? =
        if (session != null) session else runBlocking(Dispatchers.IO) {
            QuroTerminalSessionManager.ensureDefault(context, installIfMissing = false)
        }

    /** 用户提交一条命令（带回显 + 完成哨兵）。无活动会话则忽略。 */
    fun sendToShell(command: String) {
        session?.sendCommand(command)
    }

    /** 向已运行的交互式程序写入原始输入（如 python REPL）。无活动会话则忽略。 */
    fun sendRaw(text: String) {
        session?.sendRaw(text)
    }

    /** 发送原样按键序列（不补换行），供终端界面的特殊按键行使用（E-10）。 */
    fun sendKey(seq: String) {
        session?.sendKey(seq)
    }

    /** 销毁默认会话（terminal_kill 工具 / 离开界面时用）。 */
    fun destroySession() {
        runBlocking(Dispatchers.IO) { QuroTerminalSessionManager.killDefault() }
    }

    /**
     * 中断当前运行中的命令（E-9）。
     *
     * 两阶段：先让会话尝试软中断（写 ETX）；软中断失败则**强杀 shell 进程并重建默认会话**，
     * 并把工作目录恢复到中断前的位置，让用户可以无缝继续操作。
     *
     * 之所以要走到「重建」这一步：本会话的 stdin 是管道不是 PTY，
     * 内核不会把 `^C` 转成 SIGINT 发给前台进程组，`ping`（无 -c）、
     * `cat`（无参）这类命令**只能**靠杀进程停下来。
     *
     * @return 展示给用户的结果说明
     */
    suspend fun interrupt(context: Context): String {
        val s = session ?: return "当前没有活动终端会话"
        if (s.exited) return "shell 已退出，无需中断"
        if (!s.busy) return "当前没有运行中的命令"

        if (s.interrupt()) return "已中断当前命令"

        // 硬中断：记住 cwd → 杀进程 → 重建默认会话 → cd 回去
        val cwd = s.cwdState
        val history = s.lines.toList()
        s.forceStop()
        s.destroy()

        val ns = runBlocking(Dispatchers.IO) {
            QuroTerminalSessionManager.ensureDefault(context, installIfMissing = false)
        } ?: return "命令未响应软中断，且无法重建 shell"
        // 把中断前的滚动内容接回新会话，否则用户屏幕会突然被清空、以为崩了
        ns.prependHistory(history)
        ns.restoreCwd(cwd)
        return "命令未响应软中断，已重启 shell 并回到 $cwd"
    }

    /**
     * 非交互式一次性执行（AI terminal_exec 在 Linux 环境不可用时的设备回退）。
     * 与活动会话完全解耦：它自己起一个短命 sh 进程，不回显、不碰滚动缓冲区。
     *
     * **阻塞**最长 timeoutMs，必须在 IO 线程调用。
     */
    fun runCommand(command: String, timeoutMs: Long = 30_000L): ShellResult {
        if (command.isBlank()) return ShellResult("", -1, error = "命令为空")

        var proc: Process? = null
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", command),
                arrayOf("PATH=/system/bin:/system/xbin:/sbin", "LANG=en_US.UTF-8"),
                File(Environment.getExternalStorageDirectory().absolutePath)
            )
            proc = p
            // ★ ANR/挂死修复：原 p.waitFor() 无超时；若命令不退出（交互式、无计数的 ping、python REPL 等），
            // 会永久阻塞 IO 线程 → 整条 ask() 循环卡死 → 用户看到「Zorv AI 没有响应」。
            // 改为：后台读流（避免 stdout 不关闭导致 readText 永久阻塞）+ 带超时 waitFor，超时强行销毁。
            val outB = StringBuilder()
            val errB = StringBuilder()
            val tOut = Thread {
                runCatching { p.inputStream.bufferedReader().use { outB.append(it.readText()) } }
            }.apply { isDaemon = true; start() }
            val tErr = Thread {
                runCatching { p.errorStream.bufferedReader().use { errB.append(it.readText()) } }
            }.apply { isDaemon = true; start() }

            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                tOut.join(500)
                tErr.join(500)
                ShellResult(
                    output = (outB.toString() + errB.toString()).trim(),
                    exitCode = -1,
                    timedOut = true,
                )
            } else {
                tOut.join(1000)
                tErr.join(1000)
                ShellResult(
                    output = (outB.toString() + errB.toString()).trim(),
                    exitCode = p.exitValue(),
                )
            }
        } catch (e: Exception) {
            ShellResult("", -1, error = e.message ?: e.toString())
        } finally {
            runCatching { proc?.destroy() }
        }
    }

    /** 兼容旧调用方：只要人类可读文本。 */
    fun runCommandText(command: String, timeoutMs: Long = 30_000L): String =
        runCommand(command, timeoutMs).render()
}
