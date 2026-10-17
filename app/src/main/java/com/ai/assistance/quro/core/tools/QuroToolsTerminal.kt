package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager
import com.ai.assistance.quro.core.terminal.ShellMode
import com.ai.assistance.quro.terminal.TerminalManager
import com.ai.assistance.quro.terminal.runtime.Distro
import com.ai.assistance.quro.terminal.runtime.GuestEnvStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用内终端工具集（v108 删除了 QuroToolsTerminal.kt，此文件为 v116 恢复）。
 *
 * 后端复用已有的 [QuroTerminalController]（自包含 [com.ai.assistance.quro.core.terminal.QuroShellSession]，
 * 常驻 shell 进程 + 按行流式读入，无 Termux/PTY 依赖），并在应用内 Linux 环境（proot + Ubuntu 24.04 ARM64）
 * 就绪时优先经 [QuroLinuxEnv] 执行，使 AI 经这些工具驱动的命令也拥有 python3 / nslookup /
 * 任意写等完整 Linux 能力（terminal_exec 与交互式终端界面走同一 proot 路径）。
 * 本文件只补回 v108 被删的交互式终端工具（写输入 / 结束会话 / 查状态 / exec），AI 可经这些工具驱动终端。
 *
 * 这些工具均为沙盒内执行，不触及系统底层，故默认进入核心工具集（与 terminal_run 同级）。
 */
class TerminalExecTool : QuroTool {
    override val name: String = "terminal_exec"
    override val description: String =
        "在应用内终端执行一条 shell 命令（如 ls / pwd / cat file / getprop / ps / python3 / 写文件）。" +
            "应用内 Linux 环境就绪时经 proot+Ubuntu 执行（免 root），否则回退设备 Toybox shell。" +
            "返回 JSON：{source, exit_code, success, timed_out, output}。" +
            "**务必检查 exit_code / success**，不要只看 output 就断定命令成功。"
    override val parametersJson: String =
        """{"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令"}},"required":["command"]}"""

    override fun run(context: Context, arguments: String): String {
        val cmd = JSONObject(arguments).optString("command", "")
        if (cmd.isBlank()) return "missing command"
        QuroAgentTrace.action("terminal", "执行命令", cmd)

        // 双系统：AI 经 TerminalManager 感知并控制 Ubuntu / LANXIU 两套 proot 发行版。
        // LANXIU 只能走 TerminalManager 的 proot rootfs（installed-rootfs/lanxiu），
        // 因为 QuroLinuxEnv 的 linux-sandbox/rootfs 仅含 Ubuntu。
        val json = JSONObject()
        val tm = TerminalManager.getInstance(context)
        val distro = tm.getDistro()
        if (distro == Distro.LANXIU) {
            // LANXIU 必须走 TerminalManager 的 proot rootfs（installed-rootfs/lanxiu），
            // 绝不能回退设备 sh——否则 AI 切到 LANXIU 后 exec 仍跑在设备 sh，等于「切换无效」。
            // runInGuest→execGuest 内部会 initializeEnvironment() 首次自动解压 88MB xz rootfs，
            // 180s 兜底仅覆盖首次解压；解压/启动失败会返回 error，这里如实上报，
            // 而不是静默降级到设备 sh 把失败藏起来。
            val res = runBlocking { tm.runInGuest(cmd, 180_000L) }
            json.put("distro", "lanxiu")
            json.put("source", "proot/LANXIU (澜岫)")
            json.put("exit_code", res.code)
            json.put("success", res.ok)
            json.put("timed_out", res.timedOut)
            json.put("output", res.output)
            if (res.error != null) json.put("error", res.error)
            if (res.timedOut) json.put("hint", "命令在超时前未结束，已被强制终止；若是交互式或持续输出的命令请改用 terminal_write")
        } else {
            // Ubuntu：优先沿用已建立的 QuroLinuxEnv(linux-sandbox/rootfs) 工具链（含 python3 等），
            // 不可用时再回退 TerminalManager 的 installed-rootfs/ubuntu，最后设备 sh。
            val st = QuroLinuxEnv.probeLenient(context)
            if (st.available) {
                val (code, text) = QuroLinuxEnv.run(context, cmd)
                json.put("distro", "ubuntu")
                json.put("source", "proot/Linux (Ubuntu)")
                json.put("exit_code", code)
                json.put("success", code == 0)
                json.put("timed_out", false)
                json.put("output", text)
            } else {
                val r = QuroTerminalController.runCommand(cmd)
                json.put("distro", "ubuntu")
                json.put("source", "device/Toybox")
                json.put("exit_code", r.exitCode)
                json.put("success", r.success)
                json.put("timed_out", r.timedOut)
                json.put("output", r.output)
                if (r.error.isNotEmpty()) json.put("error", r.error)
                if (r.timedOut) json.put("hint", "命令在超时前未结束，已被强制终止；若是交互式或持续输出的命令请改用 terminal_write")
            }
        }

        val out = json.toString()
        QuroAgentTrace.result("terminal", "命令输出", out.take(800))
        return out
    }

    /** 渲染终端输出为可视化 HTML 页面 */
    private fun renderTerminalOutput(cmd: String, exitCode: Int, output: String, source: String): String {
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>")
            append("body{font-family:'Fira Code',monospace;padding:16px;margin:0;background:#1e1e1e;color:#d4d4d4;}")
            append(".header{background:#2d2d2d;padding:12px;border-radius:8px;margin-bottom:16px;}")
            append(".prompt{color:#569cd6;}")
            append(".cmd{color:#ce9178;}")
            append(".output{background:#0d1117;padding:16px;border-radius:8px;white-space:pre-wrap;word-break:break-all;}")
            append(".success{color:#4ec9b0;}")
            append(".error{color:#f44747;}")
            append(".hint{color:#808080;font-size:12px;margin-top:12px;}")
            append("</style></head><body>")
            append("<div class=\"header\">")
            append("<span class=\"prompt\">$</span> <span class=\"cmd\">$cmd</span>")
            append("</div>")
            append("<div class=\"output ${if (exitCode == 0) "success" else "error"}\">")
            append(output.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            append("</div>")
            append("<div class=\"hint\">退出码: $exitCode | 来源: $source</div>")
            append("</body></html>")
        }
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
        // 终端架构统一：优先使用默认共享会话；若无则懒创建一个（不触发下载，缺失后端时回退设备 sh）。
        val session = QuroTerminalController.ensureSession(context)
            ?: return "❌ 无法创建终端会话，请先在对话框工具栏打开「终端」界面或安装 Linux 环境"
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
        // 双系统：当前选中的发行版 + 两套 rootfs 各自就绪度。
        val tm = TerminalManager.getInstance(context)
        val distro = tm.getDistro()
        val ubuntuReady = tm.guestEnvStatus(Distro.UBUNTU) == GuestEnvStatus.READY
        val lanxiuStatus = tm.guestEnvStatus(Distro.LANXIU)
        val lanxiuReady = lanxiuStatus == GuestEnvStatus.READY
        // 交互式终端（terminal_write / 界面终端共用的默认会话）的真实后端：
        // 它只经 QuroLinuxEnv 跑 Ubuntu（或设备 sh / VM），【从不】跑 LANXIU。
        // 之前 shell 字段直接按 selected_distro 标志推断，会谎报成 proot/LANXIU（假 ready 最坑）。
        val interactiveBackend = when (QuroTerminalController.session?.mode) {
            ShellMode.VM -> "proot/VM Linux"
            ShellMode.LINUX -> "proot/Linux (Ubuntu)"
            ShellMode.DEVICE -> "/system/bin/sh (Toybox)"
            null -> "none"
        }
        // 一次性执行（terminal_exec）实际使用的发行版 = selected_distro 标志：
        // LANXIU 走 TerminalManager.execGuest 的独立 rootfs，Ubuntu 走 QuroLinuxEnv。
        val execSource = when {
            distro == Distro.LANXIU -> "proot/LANXIU (澜岫, 独立 rootfs)"
            ubuntuReady -> "proot/Linux (Ubuntu)"
            else -> "/system/bin/sh (Toybox)"
        }
        // 真实探活：rootfs 存在 ≠ exec 可用。2026-10-17 真机曾出现 lanxiu_ready=true 但
        // exec 100% 失败（proot -w /root 回退宿主 cwd -> bash shell-init getcwd 崩，exit 130）。
        // 这里对选中的 LANXIU 跑一次最小 echo 探活，让 status 反映「exec 真能跑」，而不是只按
        // rootfs 是否存在来报。非 LANXIU 不探活（Ubuntu 走 QuroLinuxEnv，自有探针，避免无谓 proot 开销）。
        val execReachable: Boolean? = if (distro == Distro.LANXIU && lanxiuReady) {
            runCatching { runBlocking { tm.runInGuest("echo __LANXIU_PROBE__", 12_000L) } }
                .fold({ r -> r.ok && r.output.contains("__LANXIU_PROBE__") }, { false })
        } else null
        // 终端架构统一：把会话总览一并返回，AI/使用者可据此管理所有会话与后端。
        val sessions = JSONArray()
        QuroTerminalSessionManager.listSessions().forEach { s ->
            sessions.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("kind", s.kind.name)
                put("backend", s.backend.name)
                put("is_default", s.isDefault)
                put("alive", s.alive)
            })
        }
        return JSONObject().apply {
            put("active_session", session != null)
            put("mode", when {
                session == null -> "none"
                session.mode == com.ai.assistance.quro.core.terminal.ShellMode.LINUX -> "linux"
                else -> "device"
            })
            put("busy", session?.busy ?: false)
            put("cwd", session?.cwdState ?: "")
            // E-8：上一条命令的退出码也要能查到，否则 AI 用 terminal_write 驱动交互式
            // 会话时完全不知道命令成没成功。
            put("last_exit", session?.lastExit ?: -1)
            put("last_interrupted", session?.lastInterrupted ?: false)
            put("shell", interactiveBackend)
            // 双系统核心字段
            put("selected_distro", distro.id)
            put("distro_display", distro.displayName)
            put("exec_source", execSource)
            put("ubuntu_ready", ubuntuReady)
            put("lanxiu_ready", lanxiuReady)
            put("lanxiu_status", lanxiuStatus.name.lowercase())
            put("exec_reachable", execReachable)
            put("linux_env", ubuntuReady || lanxiuReady)
            put("default_session_id", QuroTerminalSessionManager.defaultSession?.let { session ->
                // 返回实际的会话 ID，而不是固定字符串 "default"
                QuroTerminalSessionManager.listSessions().find { it.isDefault }?.id ?: "default"
            } ?: "none")
            put("sessions", sessions)
            put("note", "交互式终端(shell=$interactiveBackend) 与 terminal_exec(exec_source=$execSource) 是两条独立通道：交互式终端固定跑 Ubuntu（或设备 sh），切换发行版只影响 terminal_exec 的一次性执行通道，不会让可见终端也变成 LANXIU——LANXIU 仅在 terminal_exec 的独立 rootfs 中可用。set_distro 之前的『假 ready』正是 shell 字段按 selected_distro 标志误报所致，现已改为上报真实交互式后端。")
        }.toString()
    }
}

/**
 * 切换 AI 与交互式终端共用的 Linux 发行版（双系统控制）。
 *
 * 这是让 AI「感知并操控」双系统的关键控制开关：set_distro 后，[TerminalManager] 的
 * 全局 selectedDistro 改变，[terminal_exec] 与可见终端都运行在该发行版上（AI 与用户
 * 共用同一套 rootfs）。Ubuntu=Debian 系(apt)；LANXIU=澜岫(qypkg，非 apt)。
 */
class TerminalSetDistroTool : QuroTool {
    override val name: String = "terminal_set_distro"
    override val description: String =
        "切换 AI 与交互式终端共用的 Linux 发行版（双系统）。参数 distro=ubuntu 或 lanxiu。" +
            "切换后 terminal_exec 与可见终端都运行在该发行版（AI 与用户共用同一套 rootfs）。" +
            "LANXIU=澜岫（用 qypkg 包管理，不是 apt）；Ubuntu=Debian 系（apt/pip/npm）。" +
            "返回切换结果与两套发行版就绪状态（ubuntu_ready / lanxiu_ready / lanxiu_status）。"
    override val parametersJson: String =
        """{"type":"object","properties":{"distro":{"type":"string","description":"目标发行版：ubuntu 或 lanxiu"}},"required":["distro"]}"""

    override fun run(context: Context, arguments: String): String {
        val req = JSONObject(arguments).optString("distro", "").trim().lowercase()
        if (req != "ubuntu" && req != "lanxiu") {
            return JSONObject().put("ok", false)
                .put("error", "distro 必须是 ubuntu 或 lanxiu，收到: '$req'")
                .toString()
        }
        val target = Distro.fromId(req)
        return try {
            val tm = TerminalManager.getInstance(context)
            runBlocking { tm.setDistro(target) }
            val ubuntuReady = tm.guestEnvStatus(Distro.UBUNTU) == GuestEnvStatus.READY
            val lanxiuReady = tm.guestEnvStatus(Distro.LANXIU) == GuestEnvStatus.READY
            JSONObject().apply {
                put("ok", true)
                put("selected_distro", target.id)
                put("distro_display", target.displayName)
                put("ubuntu_ready", ubuntuReady)
                put("lanxiu_ready", lanxiuReady)
                put("lanxiu_status", tm.guestEnvStatus(Distro.LANXIU).name.lowercase())
                put("note", "切换成功；terminal_exec 现在运行在 ${target.displayName}。LANXIU 用 qypkg，Ubuntu 用 apt。")
            }.toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.toString()).toString()
        }
    }
}

/**
 * 中断当前运行中的命令（E-9）。
 *
 * 对应终端界面上的「■ 中断」按钮。因为会话 stdin 是管道不是 PTY，
 * 软中断（写 ETX）对 `ping` / `cat` 这类不读 stdin 的命令无效，
 * 此时 [QuroTerminalController.interrupt] 会强杀 shell 并重建会话 + 恢复 cwd。
 */
class TerminalInterruptTool : QuroTool {
    override val name: String = "terminal_interrupt"
    override val description: String =
        "中断交互式终端里正在运行的命令（等价于按 Ctrl+C / 界面上的「■ 中断」按钮）。" +
            "当 terminal_status 返回 busy=true、命令迟迟不结束（如 ping 无 -c、cat 无参、死循环）时使用。" +
            "软中断无效时会强制重启 shell 并自动回到原工作目录（shell 内的环境变量与后台任务会丢失）。"
    override val parametersJson: String = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val session = QuroTerminalController.session
            ?: return "当前没有活动终端会话，无需中断"
        if (!session.busy) return "当前没有运行中的命令"
        QuroAgentTrace.action("terminal", "中断命令", session.cwdState)
        // QuroTool.run 是同步接口，这里用 runBlocking 桥接挂起的 interrupt()。
        // 最长阻塞 ≈ INTERRUPT_GRACE_MS(1.2s) + 重建会话耗时，不会挂死；
        // 且工具调用本就在 IO 线程，不影响主线程。
        val msg = runBlocking { QuroTerminalController.interrupt(context) }
        QuroAgentTrace.result("terminal", "中断结果", msg)
        return msg
    }
}

/**
 * 列出所有终端会话（终端架构统一 · 新增）。
 *
 * AI 与使用者可据此查看并管理所有会话与后端：默认共享会话（AI/CMS/使用者共用）、
 * 额外会话、终端界面（Termux PTY）、以及跨重启的历史会话。返回每个会话的
 * id / 名称 / 后端 / 是否默认 / 是否存活，供 terminal_session_switch / terminal_session_kill 使用。
 */
class TerminalSessionsTool : QuroTool {
    override val name: String = "terminal_sessions"
    override val description: String =
        "列出所有终端会话：默认共享会话 / 额外会话 / 终端界面 / 历史，含 id、名称、后端(proot/Linux 或设备 sh)、是否默认、是否存活。" +
            "AI 和使用者据此管理所有会话与后端。配合 terminal_session_new/switch/kill 使用。"
    override val parametersJson: String = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val arr = JSONArray()
        QuroTerminalSessionManager.listSessions().forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("kind", s.kind.name)
                put("backend", s.backend.name)
                put("is_default", s.isDefault)
                put("alive", s.alive)
            })
        }
        return JSONObject().put("count", arr.length()).put("sessions", arr).toString()
    }
}

/**
 * 创建新终端会话（终端架构统一 · 新增）。
 *
 * 新会话与默认会话共用同一后端（proot/Ubuntu 或设备 sh），但独立运行、不自动成为默认。
 * 若后端未安装会跟随安装 Linux 环境（与打开终端界面一致）。
 */
class TerminalSessionNewTool : QuroTool {
    override val name: String = "terminal_session_new"
    override val description: String =
        "创建一个新的终端会话（后端与默认会话一致：proot/Ubuntu 或设备 sh）。不会自动成为默认；" +
            "可用 terminal_session_switch 切换默认。若后端未安装会跟随安装 Linux 环境。"
    override val parametersJson: String =
        """{"type":"object","properties":{"name":{"type":"string","description":"可选会话名"}},"required":[]}"""

    override fun run(context: Context, arguments: String): String {
        val name = JSONObject(arguments).optString("name", "")
        return try {
            val info = runBlocking {
                QuroTerminalSessionManager.createSession(context, name.ifBlank { null }, installIfMissing = true)
            }
            JSONObject().apply {
                put("ok", true)
                put("id", info.id)
                put("name", info.name)
                put("backend", info.backend.name)
                put("is_default", info.isDefault)
            }.toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.toString()).toString()
        }
    }
}

/**
 * 切换默认会话（终端架构统一 · 新增）。
 *
 * 把指定 id 的会话提升为默认共享会话——此后 AI 工具（terminal_write/interrupt/status）与 CMS
 * 开发环境都将使用该会话。原默认会话降级为额外会话并保留进程，不会丢失工作。
 */
class TerminalSessionSwitchTool : QuroTool {
    override val name: String = "terminal_session_switch"
    override val description: String =
        "把指定 id 的会话切换为默认共享会话（AI 工具 / CMS 此后将使用该会话）。原默认会话降级为额外会话并保留进程。" +
            "id 来自 terminal_sessions 的返回。"
    override val parametersJson: String =
        """{"type":"object","properties":{"id":{"type":"string","description":"目标会话 id（来自 terminal_sessions）"}},"required":["id"]}"""

    override fun run(context: Context, arguments: String): String {
        val id = JSONObject(arguments).optString("id", "")
        if (id.isBlank()) return "missing id"
        return try {
            val ok = runBlocking { QuroTerminalSessionManager.switchDefault(id) }
            if (ok) "✅ 已切换默认会话为 $id（可用 terminal_sessions 确认）"
            else "❌ 找不到会话 $id（可用 terminal_sessions 查询）"
        } catch (e: Exception) {
            "❌ 切换失败: ${e.message}"
        }
    }
}

/**
 * 销毁指定会话（终端架构统一 · 新增）。
 *
 * id=default（或省略）则销毁默认共享会话；销毁后下次使用会重新创建。
 * 满足「使用者可管理（结束）所有会话」的需求。
 */
class TerminalSessionKillTool : QuroTool {
    override val name: String = "terminal_session_kill"
    override val description: String =
        "销毁指定 id 的会话；id=default 或省略则销毁默认共享会话。销毁后下次使用会重新创建。" +
            "用于使用者结束不再需要的会话。"
    override val parametersJson: String =
        """{"type":"object","properties":{"id":{"type":"string","description":"会话 id，默认 default"}},"required":[]}"""

    override fun run(context: Context, arguments: String): String {
        val id = JSONObject(arguments).optString("id", "default").ifBlank { "default" }
        return try {
            val ok = runBlocking { QuroTerminalSessionManager.destroySession(id) }
            if (ok) "✅ 已销毁会话 $id" else "❌ 找不到会话 $id（可用 terminal_sessions 查询）"
        } catch (e: Exception) {
            "❌ 销毁失败: ${e.message}"
        }
    }
}
