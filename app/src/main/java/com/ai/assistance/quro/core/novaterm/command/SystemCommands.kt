package com.ai.assistance.quro.core.novaterm.command

import android.os.Build
import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.core.PermissionController
import com.ai.assistance.quro.core.novaterm.core.SessionManager

/**
 * 系统信息命令集
 *
 * 终端呈现的是 Linux/proot 环境，其 /proc 由 proot 绑定宿主 /proc，
 * 故直接解析 /proc 才能得到「环境内」真实、准确的进程/内存/CPU 数据，
 * 而非用 Runtime.exec 调宿主 toybox ps/top（输出格式与语义都不对）。
 */
object PsCommand : BuiltinCommand {
    override val name = "ps"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return try {
            val procs = listProcesses()
            val header = String.format("%-6s %-6s %-5s %s", "PID", "PPID", "STAT", "COMMAND")
            val body = procs.map { (pid, ppid, stat, cmdline) ->
                String.format("%-6s %-6s %-5s %s", pid, ppid, stat, cmdline)
            }
            CommandResult.ok((listOf(header) + body).joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("ps: ${e.message}")
        }
    }
    override fun help() = "ps  - 显示运行中的进程（读取 /proc）"
}

object TopCommand : BuiltinCommand {
    override val name = "top"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return try {
            val count = cmd.getOption("-n", "10").toIntOrNull() ?: 10
            val mem = readMemInfo()
            val procs = listProcesses().take(count)
            val lines = mutableListOf<String>()
            lines.add("Mem: total=${formatKb(mem.total)} available=${formatKb(mem.available)} free=${formatKb(mem.free)}")
            lines.add(String.format("%-6s %-6s %-5s %s", "PID", "PPID", "STAT", "COMMAND"))
            procs.forEach { (pid, ppid, stat, cmdline) ->
                lines.add(String.format("%-6s %-6s %-5s %s", pid, ppid, stat, cmdline))
            }
            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("top: ${e.message}")
        }
    }
    override fun help() = "top [-n <count>]  - 显示系统资源使用（读取 /proc）"
}

object MemCommand : BuiltinCommand {
    override val name = "mem"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return try {
            val m = readMemInfo()
            val used = (m.total - m.available).coerceAtLeast(0)
            val lines = listOf(
                "┌────────────┬────────────┬────────────┬────────────┐",
                "│  Total    │ Available │ Used      │ Free      │",
                "├────────────┼────────────┼────────────┼────────────┤",
                "│ ${formatKb(m.total)}  │ ${formatKb(m.available)}  │ ${formatKb(used)}  │ ${formatKb(m.free)}  │",
                "└────────────┴────────────┴────────────┴────────────┘",
                "",
                "Usage: ${String.format("%.1f", if (m.total > 0) used * 100.0 / m.total else 0.0)}%"
            )
            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("mem: ${e.message}")
        }
    }
    override fun help() = "mem  - 显示内存信息（读取 /proc/meminfo）"
}

object CpuInfoCommand : BuiltinCommand {
    override val name = "cpuinfo"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return try {
            val f = java.io.File("/proc/cpuinfo")
            if (!f.exists()) return CommandResult.err("cpuinfo: /proc/cpuinfo not found")
            val lines = f.readLines()
                .filter { it.contains("Processor") || it.contains("model name") || it.contains("Hardware") || it.contains("BogoMIPS") || it.contains("processor") }
                .take(24)
            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("cpuinfo: ${e.message}")
        }
    }
    override fun help() = "cpuinfo  - 显示 CPU 信息（读取 /proc/cpuinfo）"
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

private data class ProcInfo(
    val pid: String,
    val ppid: String,
    val stat: String,
    val cmdline: String,
)

/** 读取 /proc 下所有数字目录，解析 stat/cmdline 得到进程列表（环境真实数据）。 */
private fun listProcesses(): List<ProcInfo> {
    val procDir = java.io.File("/proc")
    val dirs = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return emptyList()
    return dirs.mapNotNull { dir ->
        runCatching {
            val pid = dir.name
            val stat = java.io.File(dir, "stat").readText()
            val open = stat.indexOf('(')
            val close = stat.indexOf(')')
            val comm = if (open >= 0 && close > open) stat.substring(open + 1, close) else pid
            val after = if (close > 0) stat.substring(close + 1).trim().split("\\s+".toRegex()) else emptyList()
            val state = after.getOrNull(0) ?: "?"
            val ppid = after.getOrNull(1) ?: "?"
            val cmdlineRaw = runCatching { java.io.File(dir, "cmdline").readText() }.getOrDefault("")
                .replace('\u0000', ' ').trim()
            val cmdline = cmdlineRaw.ifEmpty { comm }
            ProcInfo(pid, ppid, state, cmdline.ifEmpty { comm })
        }.getOrNull()
    }
}

private data class MemInfo(val total: Long, val free: Long, val available: Long)

/** 解析 /proc/meminfo（单位 kB → 字节），Linux 环境真实内存。 */
private fun readMemInfo(): MemInfo {
    val f = java.io.File("/proc/meminfo")
    var total = 0L; var free = 0L; var available = 0L
    if (f.exists()) {
        f.readLines().forEach { line ->
            val v = line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
            when {
                line.startsWith("MemTotal:") -> total = v * 1024
                line.startsWith("MemFree:") -> free = v * 1024
                line.startsWith("MemAvailable:") -> available = v * 1024
            }
        }
    }
    if (available == 0L) available = free
    return MemInfo(total, free, available)
}

/**
 * 读取 Android 系统属性（QuroTerm 沙盒此前无此命令 —— 故 `getprop` 直接 command not found）。
 * 数据源优先级：/system/build.prop（若可读）→ Build.* 补全常用 ro.* → SystemProperties 反射兜底任意 key。
 * 支持 `getprop`（列出全部）与 `getprop <key> [default]`。
 */
object GetpropCommand : BuiltinCommand {
    override val name = "getprop"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val key = cmd.getArg(0)
        return try {
            val props = loadProps()
            if (key.isEmpty()) {
                val text = props.entries.sortedBy { it.key }
                    .joinToString("\n") { "${it.key} = ${it.value}" }
                CommandResult.ok(text.ifBlank { "(no properties)" })
            } else {
                val v = props[key] ?: sysProp(key)
                if (!v.isNullOrBlank()) {
                    CommandResult.ok(v)
                } else {
                    val def = cmd.getArg(1)
                    if (def.isNotEmpty()) CommandResult.ok(def)
                    else CommandResult.err("getprop: property [$key] not found")
                }
            }
        } catch (e: Exception) {
            CommandResult.err("getprop: ${e.message}")
        }
    }
    override fun help() = "getprop [key] [default]  - 读取 Android 系统属性（ro.build.* 等）"

    private fun loadProps(): MutableMap<String, String> {
        val map = linkedMapOf<String, String>()
        // 1) 解析 /system/build.prop（普通应用可读，mode 0644；不可读则跳过）
        try {
            val f = java.io.File("/system/build.prop")
            if (f.canRead()) {
                f.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) return@forEach
                    val idx = t.indexOf('=')
                    if (idx > 0) map[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
                }
            }
        } catch (_: Exception) { }
        // 2) Build.* 补全常用 ro.*（稳定、不依赖文件可读性）
        map.putIfAbsent("ro.build.version.sdk", Build.VERSION.SDK_INT.toString())
        map.putIfAbsent("ro.build.version.release", Build.VERSION.RELEASE)
        map.putIfAbsent("ro.build.version.incremental", Build.VERSION.INCREMENTAL)
        map.putIfAbsent("ro.build.version.codename", Build.VERSION.CODENAME)
        map.putIfAbsent("ro.build.version.security_patch", Build.VERSION.SECURITY_PATCH)
        map.putIfAbsent("ro.build.id", Build.ID)
        map.putIfAbsent("ro.build.display.id", Build.DISPLAY)
        map.putIfAbsent("ro.build.fingerprint", Build.FINGERPRINT)
        map.putIfAbsent("ro.build.type", Build.TYPE)
        map.putIfAbsent("ro.build.tags", Build.TAGS)
        map.putIfAbsent("ro.product.brand", Build.BRAND)
        map.putIfAbsent("ro.product.model", Build.MODEL)
        map.putIfAbsent("ro.product.device", Build.DEVICE)
        map.putIfAbsent("ro.product.manufacturer", Build.MANUFACTURER)
        map.putIfAbsent("ro.product.board", Build.BOARD)
        map.putIfAbsent("ro.product.hardware", Build.HARDWARE)
        map.putIfAbsent("ro.hardware", Build.HARDWARE)
        Build.SUPPORTED_ABIS.firstOrNull()?.let { map.putIfAbsent("ro.product.cpu.abi", it) }
        map.putIfAbsent("ro.kernel.version", kernelVersion())
        return map
    }

    private fun sysProp(key: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java, String::class.java)
        (m.invoke(null, key, "") as? String)?.ifBlank { null }
    } catch (_: Exception) { null }

    private fun kernelVersion(): String = try {
        System.getProperty("os.version") ?: "unknown"
    } catch (_: Exception) { "unknown" }
}
