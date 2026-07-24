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
    fun runCommand(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", command),
                arrayOf("PATH=/system/bin:/system/xbin:/sbin", "LANG=en_US.UTF-8"),
                File(Environment.getExternalStorageDirectory().absolutePath)
            )
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            val raw = (out + err).trim()
            if (raw.isBlank()) "(no output, exit $code)" else raw
        } catch (e: Exception) {
            "fail: ${e.message}"
        }
    }
}
