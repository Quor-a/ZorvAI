package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.novaterm.command.CommandResult
import com.ai.assistance.quro.core.novaterm.core.SessionManager
import com.ai.assistance.quro.core.novaterm.executor.SandboxExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * QuroTerm 自研沙盒终端能力（集成自 NovaTerm，已去品牌化命名为 QuroTerm）。
 *
 * 在应用私有沙盒（/data/local/tmp/quroterm/root）内执行命令，
 * 不依赖系统 shell / root / Termux，与既有 proot 终端（QuroTerminalScreen）形成
 * 「轻量自研 + 重终端」互补：轻量场景用 QuroTerm，重 Linux 环境用 proot。
 */
class QuroTermTool : QuroTool {
    override val name = "quroterm_exec"
    override val description =
        "在 QuroTerm 自研沙盒终端执行一条命令（ls/cd/cat/echo/grep/ps/top/netstat/ping/curl/wget/dns/" +
            "pkg/run/alias/su/sandbox/encrypt/compress/base64 等），不依赖系统 shell / root / Termux。" +
            "参数：{\"command\":\"要执行的命令\"}。返回命令输出文本。适合在受控沙盒里跑工具链/脚本，避免触碰真实系统。" +
            "注意：沙盒为虚拟文件系统，路径以 ~ 为根（/data/local/tmp/quroterm/root）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"command":{"type":"string","description":"要执行的命令，如 \"ls -la\" / \"echo hello\" / \"run python3 -c 'print(1)'\""}},
        "required":["command"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val cmd = runCatching { JSONObject(arguments) }
            .getOrElse { return "参数不是合法 JSON：$arguments" }
            .optString("command", "").trim()
        if (cmd.isEmpty()) return "缺少 command 参数。"

        val sid = "tool_${System.nanoTime()}"
        SessionManager.createSession(sid)
        val exec = SandboxExecutor(sid)
        return try {
            val result = runBlocking { exec.executeBlocking(cmd) }
            when (result) {
                is CommandResult.Text -> result.output.ifBlank { "(无输出)" }
                is CommandResult.RichText -> result.lines.joinToString("\n") { it.text }.ifBlank { "(无输出)" }
                is CommandResult.Structured -> result.data.joinToString("\n") { m ->
                    m.entries.joinToString(" | ") { (k, v) -> "$k=$v" }
                }.ifBlank { "(无输出)" }
                is CommandResult.Interactive -> "交互提示：${result.prompt}"
                is CommandResult.Binary -> "二进制输出（${result.bytes.size} 字节，类型 ${result.mimeType}）"
            }
        } catch (e: Exception) {
            "QuroTerm 执行异常：${e.message}"
        } finally {
            exec.shutdown()
            SessionManager.destroySession(sid)
        }
    }
}
