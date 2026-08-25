package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
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
        if (!QuroLinuxEnv.probe(context).available) {
            QuroLinuxEnv.setup(context)
            return "⏳ Linux 环境未安装，已自动在后台开始安装（下载 Ubuntu rootfs 并初始化），请稍候在终端查看进度后重试。"
        }
        QuroAgentTrace.action("linux", "执行命令", cmd)
        val (code, out) = QuroLinuxEnv.run(context, cmd)
        QuroAgentTrace.result("linux", "输出", out.take(800))
        return if (code == 0) out else "exit=$code\n$out"
    }
}

class LinuxInstallTool : QuroTool {
    override val name: String = "linux_install"
    override val description: String =
        "在应用内 Linux 环境用 apt 安装一个 Ubuntu 软件包（如 python3 / nodejs / git）。"
    override val parametersJson: String =
        """{"type":"object","properties":{"package":{"type":"string","description":"要安装的包名"}},"required":["package"]}"""

    override fun run(context: Context, arguments: String): String {
        val pkg = JSONObject(arguments).optString("package", "")
        if (pkg.isBlank()) return "missing package"
        if (!QuroLinuxEnv.probe(context).available) {
            QuroLinuxEnv.setup(context)
            return "⏳ Linux 环境未安装，已自动在后台开始安装，请稍候重试 $pkg 的安装。"
        }
        val (code, out) = QuroLinuxEnv.run(context, "apt-get install -y --no-install-recommends $pkg")
        return if (code == 0) "✅ 已安装 $pkg" else "❌ 安装失败(exit=$code): $out"
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
