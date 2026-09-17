package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 在 proot 容器内用 tmux 管理终端会话（多窗格/后台任务/键盘发送）。
 * 会话通过固定 socket 持久化于 /root/.quro-tmux.sock（setsid 脱离 proot 进程树，可跨调用 attach）。
 */
class QuroTmuxTool : QuroTool {
    override val name = "tmux"
    override val description = "在 proot 容器内用 tmux 管理终端会话（多窗格/后台任务/键盘发送）。" +
        "参数 {\"action\":\"new_session|list_sessions|send_keys|capture|kill_session\",\"name\":\"会话名\",\"command\":\"新建会话时执行的命令（可选）\",\"keys\":\"要发送的按键串（send_keys 用）,\"timeout_ms\":30000}。" +
        "会话通过固定 socket 持久化于 /root/.quro-tmux.sock。首次自动 apt 安装 tmux。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"new_session | list_sessions | send_keys | capture | kill_session"},
            "name":{"type":"string","description":"会话名（除 list_sessions 外必填）"},
            "command":{"type":"string","description":"新建会话时运行的命令（可选）"},
            "keys":{"type":"string","description":"要发送的按键（send_keys 用，自动追加 Enter）"},
            "timeout_ms":{"type":"integer","description":"超时毫秒，默认 30000"}
        },
        "required":["action"]
    }"""

    private val SOCK = "/root/.quro-tmux.sock"

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = jo.optString("action", "").trim().lowercase()
        val name = jo.optString("name", "").trim()
        val timeout = jo.optInt("timeout_ms", 30000).coerceIn(1000, 120000)
        if (action != "list_sessions" && name.isEmpty()) return "❌ 该操作需要 name"
        return runBlocking {
            val ensure = "command -v tmux >/dev/null 2>&1 || { apt-get update -qq >/dev/null 2>&1 && apt-get install -y tmux >/dev/null 2>&1; }"
            val cmd = when (action) {
                "new_session" -> {
                    val cmdPart = jo.optString("command", "").trim()
                    // setsid 让 tmux 服务脱离 proot 进程树存活，便于后续 attach
                    "$ensure\nsetsid tmux -S $SOCK new-session -d -s \"$name\" ${if (cmdPart.isNotBlank()) "\"$cmdPart\"" else ""}"
                }
                "list_sessions" -> "$ensure\ntmux -S $SOCK list-sessions"
                "send_keys" -> {
                    val keys = jo.optString("keys", "").trim()
                    if (keys.isEmpty()) return@runBlocking "❌ send_keys 需要 keys"
                    "$ensure\ntmux -S $SOCK send-keys -t \"$name\" \"$keys\" Enter"
                }
                "capture" -> "$ensure\ntmux -S $SOCK capture-pane -p -t \"$name\""
                "kill_session" -> "$ensure\ntmux -S $SOCK kill-session -t \"$name\""
                else -> return@runBlocking "❌ 未知 action：$action"
            }
            val (rc, out) = QuroLinuxEnv.run(context, cmd, timeout.toLong())
            when (action) {
                "new_session" -> if (rc == 0) "✅ 会话 '$name' 已创建" else "❌ 创建失败（exit=$rc）：\n${out.take(1500)}"
                "list_sessions" -> if (out.isBlank()) "（无会话）" else "📟 tmux 会话：\n$out"
                "capture" -> if (out.isBlank()) "（会话 '$name' 无输出）" else "📟 [$name] 捕获：\n$out"
                "kill_session" -> "✅ 会话 '$name' 已终止"
                else -> "exit=$rc\n$out"
            }
        }
    }
}
