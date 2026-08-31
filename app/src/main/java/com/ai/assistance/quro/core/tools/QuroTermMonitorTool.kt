package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 终端资源与进程监控（proot Linux 沙箱内，读 /proc；无需 cgroups/namespace，在无 root 的 Android 应用里也能跑）。
 *
 * 说明（重要，避免误解）：
 * - Android 应用**没有** CAP_SYS_ADMIN，也通常没有用户命名空间，因此 Linux namespaces / cgroups v2 /
 *   seccomp / Docker / Podman 这类"容器运行时级"隔离在应用进程内**做不到**——那是内核/系统层能力，
 *   不是装个包就能有。本应用用 proot（ptrace 用户态拦截）做文件系统/环境隔离，已经是 Android 上最实用的方案。
 * - 但"可观测性"是能做的：本工具直接读 /proc 暴露 loadavg、内存、CPU 核数、进程清单，
 *   让 AI 和应用能实时掌握终端（及设备）负载，弥补"监控缺失"。
 *
 * 返回 loadavg / meminfo 头几行 / CPU 核数 / Top 进程（pid、comm、cmdline 截断）。
 */
class TermMonitorTool : QuroTool {
    override val name = "term_monitor"
    override val description = "终端资源与进程监控（proot 沙箱内读 /proc，无需 cgroups）。" +
        "返回 loadavg、内存概览、CPU 核数、Top 进程（pid/comm/cmdline）。" +
        "参数 {\"top_n\":15}（默认 15）。无 root 的 Android 应用无法用 namespaces/cgroups 做硬隔离，" +
        "但 /proc 监控可用。若 Linux 环境未初始化会返回提示。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "top_n":{"type":"integer","description":"显示的进程数，默认 15，最大 50"}
        }
    }"""

    override fun run(context: Context, arguments: String): String = runBlocking {
        val topN = runCatching { JSONObject(arguments).optInt("top_n", 15) }.getOrDefault(15).coerceIn(1, 50)
        val script = buildString {
            appendLine("echo '== loadavg =='; cat /proc/loadavg 2>/dev/null || echo n/a")
            appendLine("echo '== mem =='; head -3 /proc/meminfo 2>/dev/null || echo n/a")
            appendLine("echo '== cpu_cores =='; grep -c '^processor' /proc/cpuinfo 2>/dev/null || echo n/a")
            appendLine("echo '== procs =='; n=0; for d in /proc/[0-9]*; do")
            appendLine("  pid=\${d#/proc/}; comm=\$(cat \$d/comm 2>/dev/null); [ -z \"\$comm\" ] && continue;")
            appendLine("  cl=\$(tr '\\0' ' ' < \$d/cmdline 2>/dev/null | cut -c1-120);")
            appendLine("  printf '%s\\t%s\\t%s\\n' \"\$pid\" \"\$comm\" \"\$cl\"; n=\$((n+1)); [ \$n -ge $topN ] && break;")
            appendLine("done")
        }
        val res = runCatching { QuroLinuxEnv.run(context, script, timeoutMs = 15_000L) }.getOrElse { -1 to "执行失败：${it.message}" }
        val (code, out) = res
        if (code != 0 && out.isBlank()) {
            return@runBlocking "⚠️ 监控失败（exit=$code）。请先确保 Linux 环境已初始化（终端页安装 rootfs，或先调 dev_env）。错误：$out"
        }
        buildString {
            appendLine("✅ 终端监控（exit=$code，经 proot 读 /proc）：")
            appendLine()
            appendLine(out.take(4000))
            appendLine()
            appendLine("注：proot 无 PID 命名空间，进程清单为设备级可见进程；loadavg/meminfo 为设备级。硬隔离(namespaces/cgroups)在 Android 应用内不可行。")
        }
    }
}
