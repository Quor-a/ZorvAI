package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 终端会话的全局控制器（v127 重写，后端换成自包含 [QuroShellSession]，彻底移除 Termux/PTY）。
 *
 * - [createSession]：按需创建（Linux 环境就绪则 proot，否则设备 sh）常驻会话；重复调用会先销毁旧会话。
 * - [sendToShell]：给用户输入框调用，等价于在提示符后敲回车（带回显 + 哨兵完成检测）。
 * - [runCommand]：非交互式一次性执行（AI terminal_exec 设备回退用），与活动会话解耦，不回显。
 */
object QuroTerminalController {
    var session: QuroShellSession? = null
        private set

    fun createSession(context: Context): QuroShellSession {
        session?.destroy()
        val s = QuroShellSession.create(context)
        session = s
        return s
    }

    /** 用户提交一条命令（带回显 + 完成哨兵）。无活动会话则忽略。 */
    fun sendToShell(command: String) {
        session?.sendCommand(command)
    }

    /** 向已运行的交互式程序写入原始输入（如 python REPL）。无活动会话则忽略。 */
    fun sendRaw(text: String) {
        session?.sendRaw(text)
    }

    /** 销毁当前会话（terminal_kill 工具 / 离开界面时用）。 */
    fun destroySession() {
        session?.destroy()
        session = null
    }

    /**
     * 非交互式一次性执行，返回合并后的输出（AI terminal_exec 在 Linux 环境不可用时的设备回退）。
     * 与活动会话完全解耦：它自己起一个短命 sh 进程，不回显、不碰滚动缓冲区。
     */
    fun runCommand(command: String, timeoutMs: Long = 30000): String {
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", command),
                arrayOf("PATH=/system/bin:/system/xbin:/sbin", "LANG=en_US.UTF-8"),
                File(Environment.getExternalStorageDirectory().absolutePath)
            )
            // ★ ANR/挂死修复：原 p.waitFor() 无超时；若命令不退出（交互式、无计数的 ping、python REPL 等），
            // 会永久阻塞 IO 线程 → 整条 ask() 循环卡死 → 用户看到「Zorv AI 没有响应」。
            // 改为：后台读流（避免 stdout 不关闭导致 readText 永久阻塞）+ 带超时 waitFor，超时强行销毁。
            val outB = StringBuilder()
            val errB = StringBuilder()
            val tOut = Thread { runCatching { p.inputStream.bufferedReader().use { outB.append(it.readText()) } } }.also { it.start() }
            val tErr = Thread { runCatching { p.errorStream.bufferedReader().use { errB.append(it.readText()) } } }.also { it.start() }
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                tOut.join(500); tErr.join(500)
                val partial = (outB.toString() + errB.toString()).trim()
                "⏱ 命令超时(${timeoutMs}ms)已终止${if (partial.isNotBlank()) "，已捕获输出：\n$partial" else ""}"
            } else {
                tOut.join(1000); tErr.join(1000)
                val raw = (outB.toString() + errB.toString()).trim()
                if (raw.isBlank()) "(no output, exit ${p.exitValue()})" else raw
            }
        } catch (e: Exception) {
            "fail: ${e.message}"
        }
    }
}
