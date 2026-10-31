package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import org.json.JSONObject

/**
 * Termux 工具：应用内完整移植的 Termux（官方源码宿主）对 AI 的统一入口。
 *
 * ## 为什么是「一个工具、两类 action」
 *
 * Termux 本身有**多个终端会话**（TermuxService 里一堆 TerminalSession，用户可以在侧栏切换）。
 * 如果只做一个「执行命令」的工具，AI 就无法表达「在这个会话里继续敲」「切到另一个会话」这类语义，
 * 用户说的「Termux 里开两个窗口」也就做不了。所以本工具分两类 action：
 *
 *  - **会话类**（session_new / sessions / session_switch / session_kill / write / interrupt / kill）
 *    → 转发给 [QuroTerminalTool] 的会话驱动，管理多个常驻终端会话；
 *  - **环境类**（run / pkg / status / open）
 *    → 走 [QuroLinuxEnv]，在 Termux 打开的那套 Ubuntu 里一次性执行并拿完整输出。
 *
 * 执行后端与 Termux 界面完全一致：[com.ai.assistance.quro.termux.app.ProotLaunchProviderImpl]
 * 用的 `TerminalEnvironment.buildProotLaunch` 与 [QuroLinuxEnv] 指向**同一份 Ubuntu rootfs**，
 * 所以在工具里跑的命令与用户在 Termux 里手敲处于同一环境（同样的 /root、同样的已装包、同样的文件）。
 */
class TermuxTool : QuroTool {

    override val name: String = "termux"

    override val description: String = """
Termux 工具：应用内完整移植的 **Termux**（不是外部 App，是内置的 Termux 官方宿主）。
**用户只要提到「终端 / Termux / 命令行 / 跑个命令 / 装个包 / bash / shell」，优先用本工具。**

Termux 支持**多个终端会话**，本工具同时管会话与环境：

【会话类】（多终端；常驻、可切换、可写输入）
- sessions：列出所有终端会话（含 id / 名称 / 当前是否活跃）
- session_new：新建一个终端会话（可选 name），并切为当前
- session_switch：切换当前会话（id）
- session_kill：销毁指定会话（id）
- write：往当前会话写一行输入（不是一次性执行，是"敲进去"，用于交互式程序）
- interrupt：中断当前会话正在跑的命令（等价 Ctrl-C）
- kill：结束当前会话

【环境类】（一次性执行，拿完整 stdout/stderr + 退出码）
- run：在 Termux 的 Ubuntu 环境里执行一条 shell 命令（默认）
- pkg：安装软件包（自动适配 apt/apk/dnf/pacman）
- status：查看 Termux 环境是否就绪（proot + rootfs）与会话概况
- open：把 Termux 界面拉到前台，让用户看到终端

选择建议：
- 「装个东西然后跑一下」→ run / pkg（一次拿到完整输出，最省事）
- 「开个终端给我 / 在终端里连着敲几条」→ session_new 再 write
- 「看看有哪些终端 / 切到另一个」→ sessions / session_switch

注意：
- 命令跑在 Termux 的 Ubuntu 里，**不是** Android 的 /system/bin/sh；要执行 Android 命令请用 shizuku_exec 或 terminal(action="run")。
- 首次使用若环境未解压，会自动后台安装并返回提示，稍后重试即可，**不要反复触发**。
""".trimIndent()

    override val parametersJson: String = """
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "description": "run/session_new/sessions/session_switch/session_kill/write/interrupt/kill/pkg/status/open"},
    "command": {"type": "string", "description": "run 用的 shell 命令（在 Termux 的 Ubuntu 内执行）"},
    "text": {"type": "string", "description": "write 写进当前会话的一行输入"},
    "id": {"type": "string", "description": "session_switch / session_kill 的目标会话 id"},
    "name": {"type": "string", "description": "session_new 的会话名（可选）"},
    "package": {"type": "string", "description": "pkg 用的包名，多个空格分隔"},
    "timeout_ms": {"type": "integer", "description": "run 超时毫秒，默认 30000"}
  },
  "required": ["action"]
}
""".trimIndent()

    /** 会话驱动：复用 terminal 那一套（多终端会话就是它在管）。 */
    private val sessions = QuroTerminalTool()

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = obj.optString("action", "run").ifBlank { "run" }

        return when (action) {
            // ── 会话类：转发给会话驱动 ──
            "sessions", "session_new", "session_switch", "session_kill",
            "write", "interrupt", "kill",
            -> forwardToSessions(context, action, obj)

            // ── 环境类 ──
            "open" -> openTermux(context)
            "status" -> status(context)
            "pkg" -> installPackage(context, obj.optString("package", ""))
            "run" -> runCommand(
                context,
                obj.optString("command", ""),
                obj.optLong("timeout_ms", DEFAULT_TIMEOUT_MS),
            )

            else -> "未知 action：$action\n" +
                "可用：run / session_new / sessions / session_switch / session_kill / write / interrupt / kill / pkg / status / open"
        }
    }

    private fun forwardToSessions(context: Context, action: String, obj: JSONObject): String {
        val inner = JSONObject().put("action", action)
        when (action) {
            "session_new" -> inner.put("name", obj.optString("name", ""))
            "session_switch", "session_kill" -> inner.put("id", obj.optString("id", "default"))
            "write" -> inner.put("text", obj.optString("text", ""))
            else -> Unit
        }
        QuroAgentTrace.action("termux", "会话:$action", inner.toString())
        return sessions.run(context, inner.toString())
    }

    private fun runCommand(context: Context, command: String, timeoutMs: Long): String {
        if (command.isBlank()) return "缺少 command"

        if (!QuroLinuxEnv.probeLenient(context).available) {
            // 与 linux_run 一致：环境没解压就自动触发安装，不留死路。
            QuroLinuxEnv.setup(context)
            return "⏳ Termux 环境尚未就绪，已在后台开始安装（解压 Ubuntu rootfs），稍后重试该命令。"
        }

        QuroAgentTrace.action("termux", "执行命令", command)
        val safeTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val (code, out) = QuroLinuxEnv.run(context, command, timeoutMs = safeTimeout)
        QuroAgentTrace.result("termux", "exit=$code", out.take(800))

        return if (code == 0) {
            if (out.isBlank()) "（命令执行成功，无输出）" else out
        } else {
            "exit=$code\n$out"
        }
    }

    private fun installPackage(context: Context, packageLine: String): String {
        val pkgs = packageLine.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (pkgs.isEmpty()) return "缺少 package"

        if (!QuroLinuxEnv.probeLenient(context).available) {
            QuroLinuxEnv.setup(context)
            return "⏳ Termux 环境尚未就绪，已在后台开始安装，稍后重试安装 ${pkgs.joinToString(" ")}。"
        }

        val pm = QuroLinuxEnv.detectPackageManager(context)
        val cmd = pm.install(pkgs)
        QuroAgentTrace.action("termux", "安装软件包", pkgs.joinToString(" "))
        val (code, out) = QuroLinuxEnv.run(context, cmd, timeoutMs = INSTALL_TIMEOUT_MS)
        val tail = out.takeLast(1500)
        return if (code == 0) {
            "✅ 已安装 ${pkgs.joinToString(" ")}（${pm.displayName}）\n$tail"
        } else {
            "❌ 安装失败(exit=$code, ${pm.displayName})\n命令：$cmd\n$tail"
        }
    }

    private fun status(context: Context): String {
        val st = QuroLinuxEnv.probeLenient(context)
        val sb = StringBuilder()
        sb.appendLine("Termux 环境：${if (st.available) "✅ 就绪" else "❌ 未就绪"}")
        st.prootPath?.let { sb.appendLine("proot：$it") }
        st.rootfsPath?.let { sb.appendLine("rootfs：$it") }
        if (!st.available) {
            if (st.reason.isNotBlank()) sb.appendLine("原因：${st.reason}")
            sb.appendLine("未就绪时调用 run 会自动触发后台安装。")
        }
        // 顺带把会话概况带出来，AI 一次调用就能知道"有几个终端"
        val sessionInfo = runCatching { sessions.run(context, """{"action":"sessions"}""") }.getOrNull()
        if (!sessionInfo.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("— 终端会话 —")
            sb.appendLine(sessionInfo.take(1200))
        }
        return sb.toString().trim()
    }

    private fun openTermux(context: Context): String {
        return runCatching {
            // 反射拉起，避免 tools 层对 termux-host 形成编译期硬依赖
            val cls = Class.forName("com.ai.assistance.quro.termux.app.TermuxActivity")
            val intent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "已打开 Termux 界面"
        }.getOrElse { "打开 Termux 界面失败：${it.message}" }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 600_000L
        const val INSTALL_TIMEOUT_MS = 300_000L
    }
}
