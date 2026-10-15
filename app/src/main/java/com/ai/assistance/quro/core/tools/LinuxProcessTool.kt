package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import org.json.JSONObject

/**
 * 查看 / 终止应用内 Linux 环境里残留的后台进程。
 *
 * issue #11 问题 9 的「入口」侧补全：命令里 `nohup / setsid … &` 起的进程会脱离
 * shell 继续跑，工具返回时已被 [QuroToolProcesses.reapLeakedSince] 尽力强杀；
 * 但 `setsid` 派生的进程可能逃逸自动回收，本工具提供用户/AI 可主动「查看 + 终止」
 * 的入口（在 Linux 环境内执行 `ps` / `kill`，只作用于 guest 进程，安全）。
 *
 * 与 [LinuxRunTool] 同属「可选高级入口」，需用户在设置开启「完整工具集」后解锁。
 */
class LinuxProcessTool : QuroTool {
    override val name: String = "linux_process"
    override val description: String =
        "查看或终止应用内 Linux 环境中残留的后台进程（含 nohup/setsid 起的后台任务、" +
            "跑飞的服务进程）。action=list 列出当前进程（pid/父pid/运行时间/CPU/命令）；" +
            "action=kill 按 guest 内 pid 发送 SIGKILL 终止。只作用于 Linux 环境内进程。"
    override val parametersJson: String = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：list=列出后台进程；kill=终止指定 pid","enum":["list","kill"]},
            "pid":{"type":"integer","description":"action=kill 时的目标进程 pid（guest 内的 pid，从 list 结果获取）"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = args.optString("action", "list").lowercase()
        if (!QuroLinuxEnv.probeLenient(context).available) {
            QuroLinuxEnv.setup(context)
            return "⏳ Linux 环境未安装，已自动在后台开始安装，请稍候重试 linux_process 操作。"
        }
        return when (action) {
            "list" -> {
                val (code, out) = QuroLinuxEnv.run(
                    context,
                    "ps -eo pid,ppid,etime,pcpu,comm,args 2>/dev/null || ps -A 2>/dev/null || ps",
                    timeoutMs = 15_000L
                )
                if (code != 0 && out.isBlank()) {
                    "⚠️ 无法列出进程（exit=$code）${out.takeLast(300)}"
                } else {
                    "📋 Linux 环境进程（etime=已运行时间, pcpu=CPU 占用）：\n$out"
                }
            }
            "kill" -> {
                val pid = args.optInt("pid", -1)
                if (pid <= 0) return "❌ kill 需要有效的正整数 pid（从 linux_process 的 list 结果获取）"
                val (code, out) = QuroLinuxEnv.run(
                    context, "kill -9 $pid 2>&1; echo exit=\$?", timeoutMs = 15_000L
                )
                "🛑 已向 Linux 环境内 pid=$pid 发送 SIGKILL（exit=$code）\n${out.takeLast(300)}"
            }
            else -> "未知 action：$action（可用 list / kill）"
        }
    }
}
