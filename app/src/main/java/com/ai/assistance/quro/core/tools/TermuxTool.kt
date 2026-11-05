package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import com.ai.assistance.quro.core.termux.TermuxEnvironment
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
 *    → 走 [TermuxEnvironment]，在 **Termux 官方 bootstrap** 里一次性执行并拿完整输出。
 *
 * 🔴 两套环境不要混：
 *  - 本工具跑的是 **Termux 官方 bootstrap**（`PREFIX=/data/data/com.termux/files/usr`、`pkg`、`TERMUX_VERSION`）；
 *  - **proot Ubuntu 24.04** 是另一条通道，走 `linux_*` 工具（`apt`、`/root`、标准 Ubuntu 布局）。
 *  用户在 Termux 界面里手敲命令，走的也是这两套之一，取决于界面当前进的是哪个环境。
 */
class TermuxTool : QuroTool {

    override val name: String = "termux"

    override val description: String = """
Termux 原生环境：内置的 Termux 官方 bootstrap（PREFIX 目录树，包管理 pkg）。
用户说「Termux / pkg / 装个包 / bash / 命令行 / shell」时优先用它。

🔴 与 `terminal` 是**两套环境，命令不通用**：
  本工具 = Termux 原生（装包 `pkg`）；`terminal` = zorvAI 终端（proot Ubuntu，装包 `apt`）。
  用户报「pkg: command not found」通常是被错发到 zorvAI 终端了。

- run：执行一条命令，返回 stdout/stderr + 退出码
- pkg：装包（如 package="python" 装 python）
- status：就绪状态 / PREFIX / 执行通道（排查用；报「未就绪」时不要反复重试）
- session_new / sessions / session_switch / session_kill：多会话管理
- write：往当前会话敲一行输入（交互式程序用）；interrupt：中断当前命令
- open：把 Termux 界面拉到前台给用户看

首次使用会自动后台解压 bootstrap（约 1~3 分钟），返回提示后稍等再重试。
""".trimIndent()

    override val parametersJson: String = """
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "description": "run/session_new/sessions/session_switch/session_kill/write/interrupt/kill/pkg/status/repair/open"},
    "command": {"type": "string", "description": "run 用的 shell 命令（在 Termux 官方 bootstrap 内执行）"},
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
            "repair" -> repair(context)
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

        // 🔴 走**官方 Termux bootstrap**（有 PREFIX / pkg / TERMUX_VERSION），
        // 不是 proot Ubuntu —— 那是 linux_* 那条通道。
        ensureTermux(context)?.let { return it }

        QuroAgentTrace.action("termux", "执行命令", command)
        val safeTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val (code, out) = TermuxEnvironment.run(context, command, timeoutMs = safeTimeout)
        QuroAgentTrace.result("termux", "exit=$code", out.take(800))

        return if (code == 0) {
            if (out.isBlank()) "（命令执行成功，无输出）" else out
        } else {
            "exit=$code\n$out"
        }
    }

    /**
     * 确保官方 Termux 环境已解压且**真能 exec**；不满足则后台开始安装，并返回提示。
     * 返回 null 表示可以继续执行。
     *
     * 🔴 这里**不再自动 repair**。旧实现在「文件在位但 exec 失败」时无脑触发 repair，而那个失败
     * 的真因是 Android 10+ 的 SELinux 禁止 `untrusted_app` exec `app_data_file`（本应用 targetSdk 34
     * 正落在这个域）——重新解压、补可执行位、改属主**全都改不动策略**，于是 repair 循环到永远修不好。
     * 现在：通道问题交给 [TermuxEnvironment] 自己换通道并如实归因；只有真正的文件问题才需要 repair。
     */
    private fun ensureTermux(context: Context): String? {
        if (TermuxEnvironment.isActuallyReady(context)) return null
        if (TermuxEnvironment.needsInstall(context)) {
            // 全新安装（bash 都还没解压）
            synchronized(Companion) { if (installing) return "⏳ Termux 环境正在安装（上一次还没结束，别反复触发），稍后重跑即可。" else installing = true }
            Thread {
                try {
                    val err = TermuxEnvironment.install(context)
                    if (err != null) Log.e(TAG, "Termux bootstrap 安装失败：$err")
                    else Log.i(TAG, "Termux bootstrap 安装完成")
                } finally {
                    installing = false
                }
            }.start()
            return "⏳ Termux 环境首次安装中（解压 Termux 官方 bootstrap，约 1~3 分钟）。" +
                "装好后重新执行该命令即可 —— 期间不用换别的工具反复重试。"
        }
        // 文件在位、执行通道不通：如实报归因（含 SE 域 / targetSdk / 试过的通道），不再空转 repair
        return "❌ Termux 一次性执行通道不可用（bootstrap 文件已就位，不是可执行位/属主问题）：\n" +
            TermuxEnvironment.diagnose(context) +
            "\n\n已自动尝试：原生 exec ↔ 官方 system_linker_exec(/system/bin/linker64) 双向切换。" +
            "\n若上面写着 CANNOT LINK / 缺库，可显式调 termux(repair) 重解压一次；" +
            "若是其余情况，重装也无效，需要换目标（例如 linux_* 的 proot Ubuntu 通道）。"
    }

    /**
     * 强制修复：先用 Shizuku/root 的 `rm -rf` 清掉 root 属主旧树（App 自己删不动过严目录），
     * 再让 App 进程重新解压（新文件天然属主是 App，权限正常）。
     *
     * 🔴 为什么必须 Shizuku 先清：若旧树某层目录是 `drwx------`（root 属主），App 作为 other
     * 既删不动也 chmod 不了，只能靠特权进程的 `rm -rf`；清完之后 App 重解压就彻底干净。
     * 若 Shizuku 不可用，则退回 App 的 `deleteRecursively`（仅对 App 自己属主的常规情形有效）。
     *
     * 🔴 作用边界：repair 只修「文件层」（半套树、属主、可执行位、缺库）。SELinux 对
     * `app_data_file` 的 exec 禁令属策略层，repair 动了也没用（见 [TermuxEnvironment] 类注释第 3 点）。
     */
    private fun repairNow(context: Context): String? {
        runCatching {
            QuroShizuku.acquireShellService()?.remove(TermuxEnvironment.termuxRoot(context).absolutePath)
        }
        TermuxEnvironment.wipeRoot(context)
        val err = TermuxEnvironment.install(context)
        if (err != null) Log.e(TAG, "Termux 修复安装失败：$err") else Log.i(TAG, "Termux 修复安装完成")
        return err
    }

    /** 工具动作：用户/AI 显式触发一次 Termux 环境重置修复。 */
    private fun repair(context: Context): String {
        val err = repairNow(context)
        if (err != null) {
            return "❌ Termux 修复失败：$err\n若旧树是 root 属主且 Shizuku 未就绪，请先在设置里给 Shizuku 授权，或清除应用数据后重试。"
        }
        // repair 之后必须实测一次通道，不能只说「已重置」就完事（旧版就是这里骗了用户）
        val ok = TermuxEnvironment.probe(context)
        return if (ok) {
            "✅ Termux 环境已重置修复，执行通道实测通过（${TermuxEnvironment.resolveMode(context).id}）。" +
                "现在可以 run / pkg 了。"
        } else {
            "⚠️ 文件已重新解压，但执行通道仍不通（这不是 repair 能修的层级）：\n" +
                TermuxEnvironment.diagnose(context)
        }
    }

    private fun installPackage(context: Context, packageLine: String): String {
        val pkgs = packageLine.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (pkgs.isEmpty()) return "缺少 package"

        ensureTermux(context)?.let { return it }

        // Termux 官方包管理器是 **pkg**（apt 的封装），不是 Ubuntu 的 apt 直调
        val cmd = "pkg install -y ${pkgs.joinToString(" ")}"
        QuroAgentTrace.action("termux", "pkg 安装", pkgs.joinToString(" "))
        val (code, out) = TermuxEnvironment.run(context, cmd, timeoutMs = INSTALL_TIMEOUT_MS)
        val tail = out.takeLast(1500)
        return if (code == 0) {
            "✅ 已通过 pkg 安装 ${pkgs.joinToString(" ")}\n$tail"
        } else {
            "❌ 安装失败(exit=$code)\n命令：$cmd\n$tail"
        }
    }

    private fun status(context: Context): String {
        val sb = StringBuilder()
        // 报真实身份：PREFIX / TERMUX_VERSION 是「这套到底是不是真 Termux」的判据
        sb.appendLine(TermuxEnvironment.describe(context))
        sb.appendLine()
        sb.appendLine("说明：这是 **Termux 官方 bootstrap**（有 PREFIX / pkg / TERMUX_VERSION）。")
        sb.appendLine("proot Ubuntu 24.04 是**另一条通道**，走 `linux_*` 工具，两者不混用。")
        if (TermuxEnvironment.needsInstall(context)) {
            sb.appendLine()
            sb.appendLine("未就绪时调用 run / pkg 会自动触发后台安装。")
        } else if (!TermuxEnvironment.probe(context)) {
            sb.appendLine()
            sb.appendLine("— 执行通道归因（repair 修不了这一层）—")
            sb.appendLine(TermuxEnvironment.diagnose(context))
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
            // 🔴 先确保 bootstrap 前缀就绪（filesDir/termux/usr）：
            //   TermuxActivity 的 setupBootstrapIfNeeded 走「前缀已存在 → 直达会话」，
            //   前缀缺失时它会尝试自带安装器，而宿主的 libtermux-bootstrap.so 并不存在
            //   （只会弹错误框）。App 侧 TermuxEnvironment 是唯一真正装环境的通道，
            //   open 之前装好，界面拉起来即可用 —— 界面与 termux run 从此是同一套文件。
            if (TermuxEnvironment.needsInstall(context)) {
                val err = TermuxEnvironment.install(context)
                if (err != null) {
                    return "已打开 Termux 界面（⚠️ 环境安装失败：$err；界面内会提示重试）"
                }
            }
            // 反射拉起，避免 tools 层对 termux-host 形成编译期硬依赖
            val cls = Class.forName("com.ai.assistance.quro.termux.app.TermuxActivity")
            val intent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "已打开 Termux 界面"
        }.getOrElse { "打开 Termux 界面失败：${it.message}" }
    }

    private companion object {
        const val TAG = "TermuxTool"
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 600_000L
        const val INSTALL_TIMEOUT_MS = 300_000L
        /** 防止重复触发后台安装（安装中仍收到 run 请求时直接返回提示，不叠加线程）。 */
        @Volatile var installing = false
    }
}
