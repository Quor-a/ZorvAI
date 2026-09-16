package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：设备信息（完整 app）。
 *
 * 读取宿主 App 与设备信息（[sys.device.info] / [sys.package.info]），按
 * 设备 / 系统 / 处理器 / 内存存储 / 屏幕 / 电池 / 网络 / 宿主 分组展示，
 * 支持刷新与一键复制全部——是一个完整的"设备信息"应用，而非只有 4 行的玩具。
 */
class DevInfoToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_devinfo" -> KValue.Str("设备信息：读取宿主与设备硬件/系统/资源信息，分组展示，可刷新与一键复制。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi())))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 数据

    private fun dev(): Map<String, KValue> =
        (host?.call("sys.device.info", KValue.obj()) as? KValue.Obj)?.value ?: emptyMap()

    private fun hostInfo(): Map<String, KValue> =
        (host?.call("sys.package.info", KValue.obj()) as? KValue.Obj)?.value ?: emptyMap()

    private fun s(m: Map<String, KValue>, k: String): String = m[k]?.asString() ?: ""
    private fun l(m: Map<String, KValue>, k: String): Long = (m[k] as? KValue.I64)?.value ?: -1L

    /** 按分组构造 (分组名 -> 若干 (标签, 值))。 */
    private fun groups(): List<Pair<String, List<Pair<String, String>>>> {
        val d = dev()
        val h = hostInfo()

        val device = listOf(
            "厂商" to s(d, "manufacturer").ifBlank { "-" },
            "品牌" to s(d, "brand").ifBlank { "-" },
            "型号" to s(d, "model").ifBlank { "-" },
            "产品名" to s(d, "product").ifBlank { "-" },
            "设备代号" to s(d, "device").ifBlank { "-" },
            "硬件" to s(d, "hardware").ifBlank { "-" },
        )
        val system = listOf(
            "Android 版本" to s(d, "androidRelease").ifBlank { "-" },
            "SDK" to (l(d, "sdk").takeIf { it > 0 }?.toString() ?: "-"),
            "安全补丁" to s(d, "securityPatch").ifBlank { "-" },
            "Build ID" to s(d, "buildId").ifBlank { "-" },
            "Bootloader" to s(d, "bootloader").ifBlank { "-" },
            "语言/地区" to s(d, "locale").ifBlank { "-" },
        )
        val cpu = listOf(
            "CPU 核心数" to (l(d, "cpuCores").takeIf { it > 0 }?.toString() ?: "-"),
            "最高主频" to (l(d, "cpuMaxFreqMhz").takeIf { it > 0 }?.let { "$it MHz" } ?: "不可读"),
            "ABI" to s(d, "abis").ifBlank { "-" },
        )
        val memTotal = l(d, "memTotal")
        val memAvail = l(d, "memAvail")
        val mem = listOf(
            "总内存" to fmtBytes(memTotal),
            "可用内存" to fmtBytes(memAvail),
            "内存占用" to pct(memTotal - memAvail, memTotal),
            "低内存标志" to if ((d["lowMemory"] as? KValue.Bool)?.value == true) "是" else "否",
            "存储总量" to fmtBytes(l(d, "storageTotal")),
            "可用存储" to fmtBytes(l(d, "storageFree")),
            "存储占用" to pct(l(d, "storageTotal") - l(d, "storageFree"), l(d, "storageTotal")),
        )
        val screen = listOf(
            "分辨率" to "${l(d, "screenW")} × ${l(d, "screenH")}",
            "DPI" to (l(d, "densityDpi").takeIf { it > 0 }?.toString() ?: "-"),
            "密度档" to s(d, "densityName").ifBlank { "-" },
        )
        val bp = l(d, "batteryPct")
        val bt = (d["batteryTempC"] as? KValue.F64)?.value ?: -1.0
        val battery = listOf(
            "电量" to if (bp >= 0) "$bp%" else "-",
            "状态" to s(d, "batteryStatus").ifBlank { "-" },
            "温度" to if (bt > 0) "%.1f ℃".format(bt) else "-",
        )
        val net = listOf(
            "网络类型" to s(d, "netType").ifBlank { "-" },
            "开机时长" to s(d, "uptime").ifBlank { "-" },
        )
        val hostRows = listOf(
            "宿主版本" to s(h, "hostVersion").ifBlank { "-" },
            "宿主 SDK" to s(h, "sdk").ifBlank { "-" },
            "宿主设备" to s(h, "device").ifBlank { "-" },
            "指纹" to s(d, "buildFingerprint").ifBlank { "-" },
        )
        return listOf(
            "设备" to device,
            "系统" to system,
            "处理器" to cpu,
            "内存与存储" to mem,
            "屏幕" to screen,
            "电池" to battery,
            "网络与运行" to net,
            "宿主 App" to hostRows,
        )
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): UiNode {
        val gs = groups()
        val content = buildList<UiNode> {
            add(SamplesUi.section("sec", "设备信息 · 硬件 / 系统 / 资源"))
            gs.forEach { (name, rows) ->
                add(
                    UiNode.Text("g_$name", Bound.Lit(name), TypeStyle.LABEL,
                        color = SamplesUi.C.primary,
                        modifier = Mod(padding = Edges(0, 12, 0, 6)))
                )
                add(SamplesUi.infoCard("card_$name", rows))
            }
            add(SamplesUi.hint("hint", "数据每次打开/刷新时实时读取；可一键复制全部。"))
        }
        return SamplesUi.scrollPage(
            "root",
            content = content,
            actions = listOf(
                SamplesUi.primaryAction("copy", "复制全部信息"),
                SamplesUi.secondaryAction("refresh", "刷新"),
            ),
        )
    }

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        when (obj["actionId"]?.asString() ?: "") {
            "copy" -> {
                val text = groups().joinToString("\n\n") { (name, rows) ->
                    "【$name】\n" + rows.joinToString("\n") { "  ${it.first}: ${it.second}" }
                }
                host?.call("ui.clipboard", KValue.obj("text" to text))
                host?.call("ui.toast", KValue.obj("text" to "设备信息已复制"))
            }
            "refresh" -> host?.call("ui.toast", KValue.obj("text" to "已刷新"))
        }
        return KValue.obj()
    }

    // ---------------------------------------------------------------- 工具

    private fun fmtBytes(b: Long): String {
        if (b <= 0) return "-"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "%.2f %s".format(v, units[i])
    }

    private fun pct(used: Long, total: Long): String =
        if (total > 0) "%d%%".format((used * 100 / total).coerceIn(0, 100)) else "-"
}
