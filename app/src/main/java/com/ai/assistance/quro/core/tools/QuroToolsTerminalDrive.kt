package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import org.json.JSONObject

class TerminalDriveTool : QuroTool {
    override val name: String = "terminal_run"
    override val description: String =
        "Run a shell command inside the in-app terminal and return its output (e.g. ls / pwd / cat file / getprop). App-sandbox only, no root."
    override val parametersJson: String = "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"shell command to run\"}},\"required\":[\"command\"]}"

    override fun run(context: Context, arguments: String): String {
        val cmd = JSONObject(arguments).optString("command", "")
        if (cmd.isBlank()) return "missing command"
        QuroAgentTrace.action("terminal", "执行命令", cmd)
        val out = QuroTerminalController.runCommand(cmd)
        QuroAgentTrace.result("terminal", "命令输出", out.take(500))
        return out
    }
}
