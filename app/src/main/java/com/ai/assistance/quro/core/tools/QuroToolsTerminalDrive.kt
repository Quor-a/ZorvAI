package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import org.json.JSONObject

/**
 * `terminal_run`：应用沙盒内一次性执行 shell 命令（免权限，无 root）。
 *
 * **E-8**：与 [TerminalExecTool] 一样改为返回**结构化 JSON**。
 * 旧实现直接把 [QuroTerminalController.runCommand] 的字符串结果丢回给模型，
 * 命令失败（exit 1）和命令成功但无输出（exit 0）在文本上完全一样，
 * 模型只能靠猜——这正是「命令明明失败了却继续往下走」的根因。
 */
class TerminalDriveTool : QuroTool {
    override val name: String = "terminal_run"
    override val description: String =
        "在应用沙盒终端执行 shell 命令（如 ls / pwd / cat file / getprop）。纯沙盒执行，无 proot/Linux。" +
            "如需 Python/Linux 能力请用 terminal_exec（自动走 proot）。" +
            "返回 JSON：{exit_code, success, timed_out, output}。**务必检查 exit_code / success**。"
    override val parametersJson: String =
        "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"shell command to run\"}},\"required\":[\"command\"]}"

    override fun run(context: Context, arguments: String): String {
        val cmd = JSONObject(arguments).optString("command", "")
        if (cmd.isBlank()) return "missing command"
        QuroAgentTrace.action("terminal", "执行命令", cmd)

        val r = QuroTerminalController.runCommand(cmd)
        val json = JSONObject().apply {
            put("exit_code", r.exitCode)
            put("success", r.success)
            put("timed_out", r.timedOut)
            put("output", r.output)
            if (r.error.isNotEmpty()) put("error", r.error)
            if (r.timedOut) {
                put("hint", "命令在超时前未结束，已被强制终止；交互式或持续输出的命令请改用 terminal_write / terminal_interrupt")
            }
        }

        val out = json.toString()
        QuroAgentTrace.result("terminal", "命令输出", out.take(500))
        return out
    }
}
