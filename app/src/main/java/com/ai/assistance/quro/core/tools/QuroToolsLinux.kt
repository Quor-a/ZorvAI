package com.ai.assistance.quro.core.tools
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.linux.DETECT_DISTRO_CMD
import com.ai.assistance.quro.core.linux.PkgAction
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.linux.QypkgPackageManager
import com.ai.assistance.quro.terminal.TerminalManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 应用内 Linux 环境工具集（proot + Ubuntu 24.04 ARM64）。
 *
 * v108 删除了 QuroToolsLinux.kt，此文件为 v116 恢复。后端 [QuroLinuxEnv] 在
 * proot 二进制与 Ubuntu rootfs 资产齐备时真实执行命令；资产缺失时优雅降级并报明确原因。
 *
 * 这些工具属「可选高级入口」（类比 L2–L4），默认不进入 AI 核心动作空间，
 * 需用户在设置开启「完整工具集」后解锁——避免默认暴露高风险系统级执行能力。
 */
class LinuxRunTool : QuroTool {
    override val name: String = "linux_run"
    override val description: String =
        "在应用内 proot + Ubuntu Linux 环境中执行一条 shell 命令并返回输出（如 uname -a / apt-get --version / python3 -c）。需 Linux 环境资产就绪。"
    override val parametersJson: String =
        """{"type":"object","properties":{"command":{"type":"string","description":"在 Linux 环境内执行的命令"}},"required":["command"]}"""

    override fun run(context: Context, arguments: String): String {
        val cmd = JSONObject(arguments).optString("command", "")
        if (cmd.isBlank()) return "missing command"
        // 环境未就绪则自动触发后台安装，避免「未知工具/环境不可用」死路。
        // 使用宽松探测：严格 probe 会因 canExecute()/符号链接解析误判。
        if (!QuroLinuxEnv.probeLenient(context).available) {
            QuroLinuxEnv.setup(context)
            return "⏳ Linux 环境未安装，已自动在后台开始安装（下载 Ubuntu rootfs 并初始化），请稍候在终端查看进度后重试。"
        }
        QuroAgentTrace.action("linux", "执行命令", cmd)
        val (code, out) = QuroLinuxEnv.run(context, cmd)
        QuroAgentTrace.result("linux", qstr(R.string.qk_02865), out.take(800))
        return if (code == 0) out else "exit=$code\n$out"
    }
}

class LinuxInstallTool : QuroTool {
    override val name: String = "linux_install"
    override val description: String =
        "在应用内 Linux 环境安装一个软件包（如 python3 / nodejs / git）。" +
            "会自动探测发行版并选用对应包管理器（Ubuntu/Debian→apt、Alpine→apk、Fedora→dnf、Arch→pacman），" +
            "无需你指定用哪个命令。"
    override val parametersJson: String =
        """{"type":"object","properties":{"package":{"type":"string","description":"要安装的包名，可空格分隔多个"}},"required":["package"]}"""

    override fun run(context: Context, arguments: String): String {
        val pkgLine = JSONObject(arguments).optString("package", "")
        val pkgs = pkgLine.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (pkgs.isEmpty()) return "missing package"
        if (!QuroLinuxEnv.probeLenient(context).available) {
            QuroLinuxEnv.setup(context)
            return "⏳ Linux 环境未安装，已自动在后台开始安装，请稍候重试 ${pkgs.joinToString(" ")} 的安装。"
        }
        // 不再写死 apt-get：先探测发行版，再交由对应包管理器生成命令。
        // 否则换到 Alpine/Fedora 环境时「装软件」会直接失效且报错对用户无指导性。
        val pm = QuroLinuxEnv.detectPackageManager(context)
        val (code, out) = QuroLinuxEnv.run(
            context, pm.install(pkgs), timeoutMs = INSTALL_TIMEOUT_MS
        )
        val tail = out.takeLast(1500)
        return if (code == 0) {
            "✅ 已安装 ${pkgs.joinToString(" ")}（${pm.displayName}）\n$tail"
        } else {
            // 命令失败时把所用发行版与命令一并回传，便于排查「包不存在/源不可达/权限不足」
            "❌ 安装失败(exit=$code, ${pm.displayName})\n命令：${pm.install(pkgs)}\n$tail"
        }
    }

    private companion object {
        /** 装包可能触发下载与解压，默认 30s 远远不够，给到 5 分钟。 */
        const val INSTALL_TIMEOUT_MS = 300_000L
    }
}

/**
 * 统一的 Linux 包管理工具。
 *
 * 相比 [LinuxInstallTool]（只做安装），本工具覆盖完整的包管理生命周期
 * （安装/卸载/更新源/升级/搜索/列表/详情/清理/探测发行版），
 * 并同样基于 [QuroLinuxDistroDetector] 自动适配包管理器。
 */
class LinuxPackageTool : QuroTool {
    override val name: String = "linux_pkg"
    override val description: String =
        "在应用内 Linux 环境管理软件包：安装/卸载/更新软件源/升级/搜索/列出已装/查看详情/清理缓存/探测发行版。" +
            "自动识别发行版并选用正确的包管理器（apt / apk / dnf / pacman）；" +
            "当前选中澜岫 LANXIU 时自动改用其自带的 qypkg（支持 list 列出已装、remove 移除、install 安装设备上的 .qyp 包；" +
            "qypkg 没有远程软件源，update/upgrade/search 会返回说明而非报错）。"
    override val parametersJson: String = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：install/remove/update/upgrade/search/list/info/clean/detect","enum":["install","remove","update","upgrade","search","list","info","clean","detect"]},
            "packages":{"type":"string","description":"包名，多个用空格分隔（install/remove/info 需要）"},
            "query":{"type":"string","description":"搜索关键词或列表过滤词（search/list 需要）"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = PkgAction.from(args.optString("action", ""))
            ?: return "未知操作：${args.optString("action", "")}。可用：install/remove/update/upgrade/search/list/info/clean/detect"

        val pkgs = args.optString("packages", "")
            .split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        val query = args.optString("query", "").trim()

        // 🌿 澜岫 LANXIU 分流（2026-10-21 真机根因）：本工具原本一律走 [QuroLinuxEnv]（Ubuntu 通道），
        // 在 LANXIU 里它读到的是 Ubuntu 的 os-release(24.04) → 判成 apt，而 LANXIU 只有自家
        // qypkg-inst（路径 /usr/bin/qypkg-inst），于是每个操作都报 apt-get: not found / 不识别发行版。
        // 这里按「当前选中的发行版」路由：LANXIU → TerminalManager.runInGuest + QypkgPackageManager。
        val termMgr = runCatching { TerminalManager.getInstance(context) }.getOrNull()
        if (termMgr != null && termMgr.getDistro().id == "lanxiu") {
            return runQypkgInLanxiu(termMgr, action, pkgs, query)
        }

        if (!QuroLinuxEnv.probeLenient(context).available) {
            QuroLinuxEnv.setup(context)
            return "⏳ Linux 环境未安装，已自动在后台开始安装，请稍候重试「${action.summary}」。"
        }

        val pm = QuroLinuxEnv.detectPackageManager(context)

        // detect 只探测环境，不需要真的动包管理器
        val command = when (action) {
            PkgAction.INSTALL -> {
                if (pkgs.isEmpty()) return "install 需要 packages 参数"
                pm.install(pkgs)
            }
            PkgAction.REMOVE -> {
                if (pkgs.isEmpty()) return "remove 需要 packages 参数"
                pm.remove(pkgs)
            }
            PkgAction.UPDATE -> pm.update()
            PkgAction.UPGRADE -> pm.upgrade()
            PkgAction.SEARCH -> {
                if (query.isBlank()) return "search 需要 query 参数"
                pm.search(query)
            }
            PkgAction.LIST -> pm.listInstalled(query.ifBlank { null })
            PkgAction.INFO -> {
                if (pkgs.isEmpty()) return "info 需要 packages 参数"
                pm.info(pkgs.first())
            }
            PkgAction.CLEAN -> pm.clean()
            PkgAction.DETECT -> DETECT_DISTRO_CMD
        }

        val timeout = if (action == PkgAction.INSTALL || action == PkgAction.UPGRADE) {
            300_000L   // 下载+解压，给足 5 分钟
        } else {
            60_000L
        }

        val (code, out) = QuroLinuxEnv.run(context, command, timeoutMs = timeout)
        val tail = out.takeLast(2000)

        return buildString {
            append("${action.summary}｜发行版包管理器：${pm.displayName}")
            appendLine()
            append("命令：$command")
            appendLine()
            if (code == 0) append("✅ 成功") else append("⚠️ 退出码 $code")
            if (tail.isNotBlank()) {
                appendLine()
                append(tail)
            }
        }.trim()
    }

    /**
     * LANXIU 专用：在澜岫 guest 内用 qypkg-inst 执行包操作。
     *
     * 与 Ubuntu 通道的差异（均为 2026-10-21 真机核对）：
     *  - 执行通道不同：LANXIU 走 [TerminalManager.runInGuest]（它按 selectedDistro 进 LANXIU rootfs），
     *    而非 QuroLinuxEnv（只会进 Ubuntu rootfs）。
     *  - 能力不同：qypkg 是本地包安装器，没有远程软件源，只有 -l（列表）/ -r（移除）/ <pkg.qyp>（安装）。
     */
    private fun runQypkgInLanxiu(
        tm: TerminalManager,
        action: PkgAction,
        pkgs: List<String>,
        query: String,
    ): String {
        val status = runCatching { tm.guestEnvStatus() }.getOrNull()
        if (status == null || status.name != "READY") {
            return "⏳ LANXIU 环境尚未就绪（当前状态：${status?.name ?: "未知"}）。" +
                "请先在「终端 → LANXIU」完成首次解压安装（约数分钟），就绪后再用「${action.summary}」。"
        }

        val pm = QypkgPackageManager
        val command = when (action) {
            PkgAction.INSTALL -> {
                if (pkgs.isEmpty()) return "install 需要 packages 参数（.qyp 包在设备上的路径，如 /sdcard/Download/foo.qyp）"
                pm.install(pkgs)
            }
            PkgAction.REMOVE -> {
                if (pkgs.isEmpty()) return "remove 需要 packages 参数（已安装的包名）"
                pm.remove(pkgs)
            }
            PkgAction.UPDATE -> pm.update()
            PkgAction.UPGRADE -> pm.upgrade()
            PkgAction.SEARCH -> {
                if (query.isBlank()) return "search 需要 query 参数"
                pm.search(query)
            }
            PkgAction.LIST -> pm.listInstalled(query.ifBlank { null })
            PkgAction.INFO -> {
                if (pkgs.isEmpty()) return "info 需要 packages 参数"
                pm.info(pkgs.first())
            }
            PkgAction.CLEAN -> pm.clean()
            // detect 顺便把 qypkg 自检带上，一眼能看出它能不能跑
            PkgAction.DETECT -> "cat /etc/os-release 2>/dev/null; echo '--- qypkg-inst -l ---'; qypkg-inst -l 2>&1 | head -n 20"
        }

        val timeout = if (action == PkgAction.INSTALL) 300_000L else 60_000L
        val r = runCatching { runBlocking { tm.runInGuest(command, timeout) } }
            .getOrElse { return "⚠️ 在 LANXIU 内执行失败：${it.message ?: it.javaClass.simpleName}" }

        val tail = r.output.takeLast(2000)
        return buildString {
            append("${action.summary}｜包管理器：${pm.displayName}")
            appendLine()
            append("命令：$command")
            appendLine()
            when {
                r.timedOut -> append("⚠️ 执行超时（${timeout / 1000}s）")
                r.code == 0 -> append("✅ 成功")
                else -> append("⚠️ 退出码 ${r.code}")
            }
            if (tail.isNotBlank()) {
                appendLine()
                append(tail)
            } else if (action == PkgAction.LIST) {
                appendLine()
                append("（列表为空：尚未安装任何 .qyp 包）")
            }
        }.trim()
    }
}

class LinuxStartTool : QuroTool {
    override val name: String = "linux_start"
    override val description: String =
        "检查并初始化应用内 Linux 环境，返回就绪状态（proot 二进制与 Ubuntu rootfs 是否就位）。"
    override val parametersJson: String = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val st = QuroLinuxEnv.probe(context)
        return JSONObject().apply {
            put("available", st.available)
            put("proot", st.prootPath ?: JSONObject.NULL)
            put("rootfs", st.rootfsPath ?: JSONObject.NULL)
            put("reason", st.reason)
        }.toString()
    }
}

class LinuxStopTool : QuroTool {
    override val name: String = "linux_stop"
    override val description: String =
        "终止应用内 Linux 环境中仍在运行的进程（发送 SIGTERM 给 proot 会话）。"
    override val parametersJson: String = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val (code, out) = QuroLinuxEnv.run(context, "pkill -TERM proot 2>/dev/null; echo stopped")
        return if (code == 0) "✅ 已请求停止 Linux 环境进程" else "⚠️ $out"
    }
}

class LinuxStatusTool : QuroTool {
    override val name: String = "linux_status"
    override val description: String =
        "返回应用内 Linux 环境的探测状态：proot 二进制路径、Ubuntu rootfs 路径、是否可用。"
    override val parametersJson: String = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val st = QuroLinuxEnv.probe(context)
        return JSONObject().apply {
            put("available", st.available)
            put("proot", st.prootPath ?: JSONObject.NULL)
            put("rootfs", st.rootfsPath ?: JSONObject.NULL)
            put("reason", st.reason)
        }.toString()
    }
}