package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.core.PermissionController
import com.ai.assistance.quro.core.novaterm.core.SessionManager

/**
 * 系统信息命令集
 */
object PsCommand : BuiltinCommand {
    override val name = "ps"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec("ps")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            CommandResult.ok(output.trim())
        } catch (e: Exception) {
            CommandResult.err("ps: ${e.message}")
        }
    }
    override fun help() = "ps  - 显示运行中的进程"
}

object TopCommand : BuiltinCommand {
    override val name = "top"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return try {
            val count = cmd.getOption("-n", "10").toIntOrNull() ?: 10
            val process = Runtime.getRuntime().exec("top -n $count")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            CommandResult.ok(output.trim())
        } catch (e: Exception) {
            // 解析 /proc 作为备选方案
            val lines = parseProcForTop()
            CommandResult.ok(lines.joinToString("\n"))
        }
    }
    override fun help() = "top [-n <count>]  - 显示系统资源使用"
}

object MemCommand : BuiltinCommand {
    override val name = "mem"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val rt = Runtime.getRuntime()
        val maxMem = rt.maxMemory()
        val totalMem = rt.totalMemory()
        val freeMem = rt.freeMemory()
        val usedMem = totalMem - freeMem

        val lines = listOf(
            "┌────────────┬────────────┬────────────┬────────────┐",
            "│  Max Mem   │ Total Mem  │ Used Mem   │ Free Mem   │",
            "├────────────┼────────────┼────────────┼────────────┤",
            "│ ${formatKb(maxMem)}  │ ${formatKb(totalMem)}  │ ${formatKb(usedMem)}  │ ${formatKb(freeMem)}  │",
            "└────────────┴────────────┴────────────┴────────────┘",
            "",
            "Usage: ${String.format("%.1f", usedMem * 100.0 / totalMem)}%"
        )
        return CommandResult.ok(lines.joinToString("\n"))
    }
    override fun help() = "mem  - 显示内存信息"
}

object CpuInfoCommand : BuiltinCommand {
    override val name = "cpuinfo"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec("cat /proc/cpuinfo")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // 精简输出
            val lines = output.lines()
                .filter { it.contains("Processor") || it.contains("model name") || it.contains("MHz") || it.contains("cores") }
                .take(20)
            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("cpuinfo: ${e.message}")
        }
    }
    override fun help() = "cpuinfo  - 显示 CPU 信息"
}

object BatteryCommand : BuiltinCommand {
    override val name = "battery"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        // 通过读取系统文件获取电池信息
        return try {
            val voltageFile = java.io.File("/sys/class/power_supply/battery/voltage_now")
            val capacityFile = java.io.File("/sys/class/power_supply/battery/capacity")
            val statusFile = java.io.File("/sys/class/power_supply/battery/status")

            val capacity = if (capacityFile.exists()) capacityFile.readText().trim() + "%" else "N/A"
            val status = if (statusFile.exists()) statusFile.readText().trim() else "N/A"
            val voltage = if (voltageFile.exists()) {
                val v = voltageFile.readText().trim().toLongOrNull() ?: 0
                String.format("%.2fV", v / 1000000.0)
            } else "N/A"

            val lines = listOf(
                "🔋 Battery Status",
                "─────────────────",
                "  Capacity:  $capacity",
                "  Status:    $status",
                "  Voltage:   $voltage",
            )
            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("battery: ${e.message}")
        }
    }
    override fun help() = "battery  - 显示电池信息"
}

object NetStatCommand : BuiltinCommand {
    override val name = "netstat"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec("cat /proc/net/tcp")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val lines = output.lines().take(30)
            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("netstat: ${e.message}")
        }
    }
    override fun help() = "netstat  - 显示网络连接"
}

// ========== 辅助函数 ==========
private fun formatKb(bytes: Long): String {
    val kb = bytes / 1024
    return when {
        kb < 1024 -> "${kb}KB"
        kb < 1048576 -> String.format("%.1fMB", kb / 1024.0)
        else -> String.format("%.1fGB", kb / 1048576.0)
    }
}

private fun parseProcForTop(): List<String> {
    val procDir = java.io.File("/proc")
    if (!procDir.exists()) return listOf("top: /proc not accessible")
    val processes = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
        ?.take(15) ?: return emptyList()

    return processes.mapNotNull { dir ->
        val cmdline = java.io.File(dir, "cmdline").readText().replace('\u0000', ' ').trim()
        if (cmdline.isNotEmpty()) {
            val stat = java.io.File(dir, "stat").readText().split(" ")
            val name = stat.getOrElse(1) { "?" }
            val pid = dir.name
            "$pid  $name  $cmdline"
        } else null
    }
}
