package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import org.json.JSONObject

/**
 * 应用内终端工具集（v108 删除了 QuroToolsTerminal.kt，此文件为 v116 恢复）。
 *
 * 后端复用已有的 [QuroTerminalController]（自包含 [com.ai.assistance.quro.core.terminal.QuroShellSession]，
 * 常驻 shell 进程 + 按行流式读入，无 Termux/PTY 依赖），并在应用内 Linux 环境（proot + Alpine aarch64）
 * 就绪时优先经 [QuroLinuxEnv] 执行，使 AI 经这些工具驱动的命令也拥有 python3 / nslookup /
 * 任意写等完整 Linux 能力（terminal_exec 与交互式终端界面走同一 proot 路径）。
 * 本文件只补回 v108 被删的交互式终端工具（写输入 / 结束会话 / 查状态 / exec），AI 可经这些工具驱动终端。
 *
 * 这些工具均为沙盒内执行，不触及系统底层，故默认进入核心工具集（与 terminal_run 同级）。
 */
class TerminalExecTool : QuroTool {
    override val name: String = "terminal_exec"
    override val description: String =
        "在应用内终端执行一条 shell 命令并返回输出（如 ls / pwd / cat file / getprop / ps / python3 / 写文件）。应用内 Linux 环境就绪时经 proot+Alpine 执行（免 root），否则回退设备 Toybox shell。"
    override val parametersJson: String =
        """{"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令"}},"required":["command"]}"""

    override fun run(context: Context, arguments: String): String {
        val cmd = JSONObject(arguments).optString("command", "")
        if (cmd.isBlank()) return "missing command"
        QuroAgentTrace.action("terminal", "执行命令", cmd)
        // v122：优先走 proot/Linux 环境（python3 / 任意写可用），不可用时回退设备 Toybox shell。
        // 这样 AI 经 terminal_exec 驱动的命令与交互式终端界面拥有完全一致的能力，不再受 untrusted_app 沙盒限制。
        val st = QuroLinuxEnv.probe(context)
        val out = if (st.available) {
            val (code, text) = QuroLinuxEnv.run(context, cmd)
            "[proot/Linux] (exit $code)\n$text"
        } else {
            QuroTerminalController.runCommand(cmd)
        }
        QuroAgentTrace.result("terminal", "命令输出", out.take(800))
        return out
    }
}

class TerminalWriteTool : QuroTool {
    override val name: String = "terminal_write"
    override val description: String =
        "向当前交互式终端会话写入一行输入并回车（等价于在提示符后敲回车）。需终端界面已打开且存在活动会话；常用于让 AI 替用户在终端里执行命令或喂给正在运行的交互式程序（如 python REPL）。"
    override val parametersJson: String =
        """{"type":"object","properties":{"text":{"type":"string","description":"要写入终端的输入内容（一行命令）"}},"required":["text"]}"""

    override fun run(context: Context, arguments: String): String {
        val text = JSONObject(arguments).optString("text", "")
        if (text.isBlank()) return "missing text"
        val session = QuroTerminalController.session
            ?: return "❌ 当前没有活动终端会话，请先在对话框工具栏打开「终端」界面再写入"
        session.sendCommand(text)
        return "✅ 已写入终端: $text"
    }
}

class TerminalKillTool : QuroTool {
    override val name: String = "terminal_kill"
    override val description: String =
        "结束当前交互式终端会话（销毁常驻 shell 进程）。若会话未启动则提示无活动会话。"
    override val parametersJson: String = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        if (QuroTerminalController.session == null) return "当前没有活动终端会话，无需结束"
        QuroTerminalController.destroySession()
        return "✅ 已结束终端会话"
    }
}

class TerminalStatusTool : QuroTool {
    override val name: String = "terminal_status"
    override val description: String =
        "返回终端会话状态：是否存在活动会话、模式（设备 sh / proot-Linux）、是否忙碌、当前工作目录。"
    override val parametersJson: String = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val session = QuroTerminalController.session
        val linux = QuroLinuxEnv.shellLaunch(context) != null
        val shell = if (linux) "proot/Linux (Alpine aarch64)" else "/system/bin/sh (Toybox)"
        return JSONObject().apply {
            put("active_session", session != null)
            put("mode", when {
                session == null -> "none"
                session.mode == com.ai.assistance.quro.core.terminal.ShellMode.LINUX -> "linux"
                else -> "device"
            })
            put("busy", session?.busy ?: false)
            put("cwd", session?.cwdState ?: "")
            put("shell", shell)
            put("linux_env", linux)
            put("note", if (session != null) "交互式会话可用，可用 terminal_write 输入" else "会话未启动，terminal_exec 仍可独立执行命令")
        }.toString()
    }
}
