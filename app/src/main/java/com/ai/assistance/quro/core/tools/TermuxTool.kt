package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import org.json.JSONObject

/**
 * Termux 工具：把应用内完整移植的 Termux（官方源码宿主）注册成 AI 可调用的工具。
 *
 * 为什么单独成工具而不是并进 `terminal`：
 *  - `terminal` 是**自研 PTY 会话**（terminal-core），管的是「会话/写入/中断」这类交互式操作；
 *  - 本工具面向 **Termux 的发行版环境**：在 Termux 打开的那套 Ubuntu（proot）里执行命令、
 *    装包、查环境，语义是「用 Termux 干活」，与用户在 Termux 界面里手敲等价。
 *
 * 执行后端与 Termux 会话完全一致：`ProotLaunchProviderImpl` 用的
 * [com.ai.assistance.quro.terminal.runtime.TerminalEnvironment.buildProotLaunch] 与
 * [QuroLinuxEnv] 指向同一份 Ubuntu rootfs，因此在工具里跑的命令与用户在 Termux 里
 * 敲的命令处于同一环境（同样的 /root、同样的已装包、同样的文件）。
 */
class TermuxTool : QuroTool {

    override val name: String = "termux"

    override val description: String = """
Termux 环境工具：在本机 Termux（完整移植的 Termux 官方宿主）所使用的 Ubuntu 发行版里执行命令。
适用于：跑 shell 命令、查系统/软件版本、写脚本、操作 Termux 里的文件、用 apt 装包。
与 terminal 工具的区别：terminal 管交互式会话；termux 直接在这套发行版里执行并拿回完整输出（stdout/stderr + 退出码），
更适合「装个包再跑一下」这类一步到位的工作。

action：
- run：执行一条 shell 命令（默认）
- pkg：安装软件包（自动选 apt/apk/dnf/pacman）
- status：查看 Termux 环境是否就绪（proot 二进制 + rootfs）
- open：把 Termux 界面拉到前台，让用户看到终端

注意：
- 命令在 Termux 的 Ubuntu 里执行，不是 Android 的 /system/bin/sh；要执行 Android 命令请用 terminal 或 shizuku_exec。
- 首次使用若环境未解压，会自动触发后台安装，此时会返回「正在安装」提示，稍后重试。
""".trimIndent()

    override val parametersJson: String = """
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "description": "run（默认）/ pkg / status / open"},
    "command": {"type": "string", "description": "run 用的 shell 命令（在 Termux 的 Ubuntu 环境内执行）"},
    "package": {"type": "string", "description": "pkg 用的包名，多个用空格分隔"},
    "timeout_ms": {"type": "integer", "description": "run 的超时毫秒数，默认 30000，装包等长任务可调大"}
  },
  "required": ["action"]
}
""".trimIndent()

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = obj.optString("action", "run").ifBlank { "run" }

        return when (action) {
            "open" -> openTermux(context)
            "status" -> status(context)
            "pkg" -> installPackage(context, obj.optString("package", ""))
            "run" -> runCommand(
                context,
                obj.optString("command", ""),
                obj.optLong("timeout_ms", DEFAULT_TIMEOUT_MS),
            )
            else -> "未知 action：$action（可用：run / pkg / status / open）"
        }
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
