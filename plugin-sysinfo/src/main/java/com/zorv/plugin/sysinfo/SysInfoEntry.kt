package com.zorv.plugin.sysinfo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin
import java.util.Locale

/**
 * 示例插件：设备体检。
 *
 * 演示「插件能访问完整的 Android 系统能力」——通过 ctx.appContext 读电池/内存/存储/CPU/屏幕。
 * 这类工具宿主本来没有，装了这个插件 AI 就多出了这些能力，而宿主一行代码都没改。
 *
 * 注册 4 个 AI 工具：sys_report / sys_battery / sys_memory / sys_storage
 * 不申请任何权限（只读系统状态，无需危险权限）。
 */
class SysInfoEntry : PluginEntry {

    override fun onCreate(ctx: PluginContext) {
        ctx.log("SysInfoEntry", "设备体检插件启动 v${ctx.pluginVersion}")

        plugin(ctx) {

            aiTool(
                name = "sys_report",
                description = "给设备做一次体检，汇总输出：机型与系统版本、CPU、内存占用、存储占用、电池电量与温度、屏幕分辨率、开机时长。"
            ) {
                execute {
                    val c = ctx.appContext
                    ToolResult.text(
                        buildString {
                            appendLine("===== 设备体检报告 =====")
                            appendLine("[机型] ${Build.MANUFACTURER} ${Build.MODEL}")
                            appendLine("[系统] Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                            appendLine("[CPU ] ${Runtime.getRuntime().availableProcessors()} 核 · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "-"}")
                            appendLine("[内存] ${memoryLine(c)}")
                            appendLine("[存储] ${storageLine(c)}")
                            appendLine("[电池] ${batteryLine(c)}")
                            appendLine("[屏幕] ${screenLine(c)}")
                            appendLine("[开机] ${uptimeLine()}")
                        }.trim()
                    )
                }
            }

            aiTool(
                name = "sys_battery",
                description = "查询当前电池状态：电量百分比、是否充电、充电方式、温度、电压、健康状态。"
            ) {
                execute { ToolResult.text(batteryLine(ctx.appContext)) }
            }

            aiTool(
                name = "sys_memory",
                description = "查询内存使用情况：总内存、可用内存、已用比例，以及系统是否处于低内存状态。"
            ) {
                execute { ToolResult.text(memoryLine(ctx.appContext)) }
            }

            aiTool(
                name = "sys_storage",
                description = "查询内部存储占用：总容量、可用空间、已用比例。"
            ) {
                param("path", ParamType.STRING, "可选。要查询的目录路径，默认查内部数据分区", required = false)
                execute { args ->
                    val p = args.string("path").ifBlank { Environment.getDataDirectory().absolutePath }
                    ToolResult.text(storageLine(ctx.appContext, p))
                }
            }
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }

    // ==================== 各项读取 ====================

    private fun batteryLine(c: Context): String = runCatching {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching "无法读取电池状态（系统未返回 sticky 广播）"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "交流电"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
            else -> "未接电源"
        }
        val tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
        val voltMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
            BatteryManager.BATTERY_HEALTH_COLD -> "温度过低"
            else -> "未知"
        }
        buildString {
            append("电量 ${if (pct >= 0) "$pct%" else "未知"} · $status · 电源 $plugged")
            if (tempC > 0) append(" · 温度 ${String.format(Locale.US, "%.1f", tempC)}°C")
            if (voltMv > 0) append(" · 电压 ${voltMv}mV")
            append(" · 健康 $health")
        }
    }.getOrElse { "读取电池失败：${it.message}" }

    private fun memoryLine(c: Context): String = runCatching {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val total = info.totalMem / 1024.0 / 1024.0
        val avail = info.availMem / 1024.0 / 1024.0
        val usedPct = if (total > 0) (total - avail) * 100 / total else 0.0
        "总 ${mb(total)} · 可用 ${mb(avail)} · 已用 ${String.format(Locale.US, "%.1f", usedPct)}%" +
            (if (info.lowMemory) " · ⚠ 系统处于低内存状态" else "")
    }.getOrElse { "读取内存失败：${it.message}" }

    private fun storageLine(c: Context, path: String = Environment.getDataDirectory().absolutePath): String =
        runCatching {
            val s = StatFs(path)
            val total = s.blockCountLong * s.blockSizeLong
            val avail = s.availableBlocksLong * s.blockSizeLong
            val usedPct = if (total > 0) (total - avail) * 100.0 / total else 0.0
            "$path\n总 ${gb(total)} · 可用 ${gb(avail)} · 已用 ${String.format(Locale.US, "%.1f", usedPct)}%"
        }.getOrElse { "读取存储失败：${it.message}" }

    private fun screenLine(c: Context): String = runCatching {
        val dm = c.resources.displayMetrics
        val d = c.resources.configuration.densityDpi
        "${dm.widthPixels} × ${dm.heightPixels} px · ${d} dpi · 密度 ${dm.density}"
    }.getOrElse { "读取屏幕失败：${it.message}" }

    private fun uptimeLine(): String {
        val ms = SystemClock.elapsedRealtime()
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        return "已运行 ${h} 小时 ${m} 分钟"
    }

    private fun mb(v: Double) = String.format(Locale.US, "%.0f MB", v)
    private fun gb(v: Long) = String.format(Locale.US, "%.2f GB", v / 1024.0 / 1024.0 / 1024.0)
}
