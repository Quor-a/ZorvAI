package com.ai.assistance.quro.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * L5 · 设备资源画像（Kotlin 侧）。
 *
 * 与原生层 `quro::llm::Resource::profile()`（llm/core/src/resource.cpp）**必须同口径**：
 * 同一台机器上「UI 建议的线程数」和「引擎实际用的线程数」不一致，是最难查的一类怪象
 * （用户看到设置页写 6、日志里跑 4）。所以这里只做一件事：把原生那套判据用 Kotlin
 * 再算一遍，供 UI 展示与 `:llm` 服务自检使用；真正的推理参数仍由原生层决定。
 *
 * 判据（与原生一致）：
 *   - 大核识别：读每个核的 `cpuinfo_max_freq`（回落 `scaling_max_freq`），
 *     把「最高频的 80% 及以上」的核视为同一大核簇。这样 2.8G 与 2.4G 算同簇，
 *     2.8G 与 1.8G 则排除小核 —— 直接按「前 N 个核」猜在大小核交织的 SoC 上必错。
 *   - 推荐线程数 = 大核数 - 1：留一个核给 UI 与系统，否则滑动界面会明显掉帧。
 *   - 内存预算 = MemAvailable 的 55%：留 45% 给 UI、系统服务与 KV cache 的增长。
 *     按「总内存」算会算出一个永远吃不满、却能在低端机上直接触发 LMK 的数字。
 */
object QuroDeviceProfile {

    /** 与 llm/core/src/resource.cpp 的 kBudgetNumerator/kBudgetDenominator 保持一致。 */
    private const val BUDGET_NUMERATOR = 55
    private const val BUDGET_DENOMINATOR = 100
    private const val MIN_BUDGET_BYTES = 256L * 1024 * 1024
    private const val MAX_BUDGET_BYTES = 12L * 1024 * 1024 * 1024

    private const val BIG_CLUSTER_RATIO = 0.8

    data class Profile(
        val totalCores: Int,
        val bigCores: Int,
        val bigCoreIds: List<Int>,
        val maxBigCoreKhz: Long,
        val memTotalBytes: Long,
        val memAvailableBytes: Long,
        val memoryBudgetBytes: Long,
        val recommendedThreads: Int,
    ) {
        fun describe(): String = buildString {
            append("CPU ")
            append(totalCores).append(" 核")
            if (bigCores > 0) {
                append("（大核 ").append(bigCores).append(" 个")
                if (maxBigCoreKhz > 0) append(" @ ").append(maxBigCoreKhz / 1000).append(" MHz")
                append("）")
            }
            append("，内存 ").append(mb(memTotalBytes)).append("MB")
            append("（可用 ").append(mb(memAvailableBytes)).append("MB）")
            append("，预算 ").append(mb(memoryBudgetBytes)).append("MB")
            append("，建议线程 ").append(recommendedThreads)
        }
    }

    /** 采样一次。读 sysfs 有几十次小文件 IO，别在主线程反复调。 */
    fun sample(context: Context? = null): Profile {
        val coreIds = cpuIds()
        val freqs = coreIds.associateWith { maxFreqKhz(it) }
        val topFreq = freqs.values.maxOrNull() ?: 0L
        val threshold = (topFreq * BIG_CLUSTER_RATIO).toLong()
        val bigIds = if (topFreq <= 0L) {
            emptyList()
        } else {
            freqs.filterValues { it >= threshold && it > 0L }.keys.sorted()
        }

        val memTotal = context?.let { systemMemTotal(it) } ?: memTotalFromProc()
        val memAvail = memAvailableFromProc()
        val budget = (memAvail * BUDGET_NUMERATOR / BUDGET_DENOMINATOR)
            .coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)

        val threads = if (bigIds.isNotEmpty()) {
            (bigIds.size - 1).coerceAtLeast(1)
        } else {
            (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        }

        return Profile(
            totalCores = Runtime.getRuntime().availableProcessors(),
            bigCores = bigIds.size,
            bigCoreIds = bigIds,
            maxBigCoreKhz = topFreq,
            memTotalBytes = memTotal,
            memAvailableBytes = memAvail,
            memoryBudgetBytes = budget,
            recommendedThreads = threads,
        )
    }

    fun describe(): String = JSONObject()
        .put("android", Build.VERSION.RELEASE)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("abi", Build.SUPPORTED_ABIS.joinToString(","))
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("profile", toJson(sample()))
        .put("thermal", toJson(thermal()))
        .toString()

    // ─────────────────────────────────────────────────────────────────────
    // L5 · 温控读数
    // ─────────────────────────────────────────────────────────────────────
    // 这是「真正决定流畅度的两个开关」之一的观测入口。
    //
    // 为什么先做「读」而不是直接做「主动降档」：
    //   持续推理 5–10 分钟后 SoC 必然降频。裸跑 30 分钟吞吐掉约 69%；
    //   若每 2 秒读一次余量并**主动**小幅让步，可以保住约 77% 峰值
    //   （被动降频是断崖式的，主动降档是平滑的）。
    //   但主动降档要动原生 decode 循环（llama_set_n_threads / 批大小），
    //   属于运行期行为变更，必须先能看见「现在到底多热、判定成了哪一档」，
    //   否则调参就是在盲猜 —— 用户看到变慢了也无从判断是降档还是别的问题。
    //
    // 判据与 llm/core/src/thermal.cpp 的 ThermalGovernor::evaluate 保持一致，
    // 否则会出现「原生按 HOT 降了线程、诊断页却显示 COOL」这种最难查的错位。

    private const val TEMP_COOL_C = 45.0
    private const val TEMP_HOT_C = 90.0

    /** 平台热状态名（PowerManager.THERMAL_STATUS_*，与原生 thermal.cpp 的档位表同序）。 */
    private val THERMAL_STATUS_NAMES = arrayOf(
        "NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"
    )

    data class Thermal(
        /** PowerManager.getCurrentThermalStatus()；-1 = 该 API 不可用。 */
        val platformStatus: Int,
        /** 0.0–1.0。负值表示两个数据源都拿不到 → 不该做任何干预。 */
        val headroom: Float,
        /** sysfs 读到的最高 SoC 温度（℃）；-1 = 读不到。 */
        val maxTempC: Double,
        /** 数据来源说明，直接进日志/诊断页。 */
        val source: String,
    ) {
        /** 折算成 0–100 的余量百分比；-1 表示不可用。 */
        val headroomPct: Int
            get() = if (headroom < 0f) -1 else (headroom * 100f).coerceIn(0f, 100f).toInt()

        /**
         * 档位。UNKNOWN 表示**不干预** —— 探测不到就明确什么都不做，
         * 而不是瞎猜一个档位去降线程（那会让「读不到温度」变成「无缘无故变慢」）。
         */
        val level: String
            get() {
                val pct = headroomPct
                if (pct < 0) return "UNKNOWN"
                if (platformStatus >= 3) return "CRITICAL"   // SEVERE 及以上不信任余量的乐观值
                return when {
                    pct >= 70 -> "COOL"
                    pct >= 40 -> "WARM"
                    pct >= 15 -> "HOT"
                    else -> "CRITICAL"
                }
            }

        fun describe(): String {
            if (headroomPct < 0) return "温控不可用（无数据源）"
            val sb = StringBuilder("$level · 余量 $headroomPct%")
            if (platformStatus >= 0) {
                sb.append(" · 平台").append(
                    THERMAL_STATUS_NAMES.getOrElse(platformStatus) { "?" }
                )
            }
            if (maxTempC >= 0) sb.append(" · ").append("%.1f".format(maxTempC)).append("℃")
            sb.append("（").append(source).append("）")
            return sb.toString()
        }
    }

    /**
     * 采样一次温控状态。三源降级：
     *   1. PowerManager.getThermalHeadroom（API 30+，官方余量，最准）；
     *   2. PowerManager.getCurrentThermalStatus（API 29+，只有档位没有余量）；
     *   3. sysfs /sys/class/thermal 读温度后折算伪余量（兜底，部分机型无权限）。
     *
     * 与原生唯一的口径差异：原生走 NDK AThermal（dlopen libandroid.so），
     * 这里走框架 API —— 两者底层是同一份数据，不会打架。
     */
    fun thermal(context: Context? = null): Thermal {
        var status = -1
        var headroom = -1f
        var source = ""

        val pm = context?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                status = runCatching { pm.currentThermalStatus }.getOrDefault(-1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // forecastSeconds = 0：要「现在」的余量，不要预测值。
                val h = runCatching { pm.getThermalHeadroom(0) }.getOrDefault(Float.NaN)
                if (!h.isNaN() && h > 0f) {
                    headroom = h
                    source = "PowerManager"
                }
            }
        }

        val tempC = readMaxSocTempC()
        if (headroom < 0f && tempC >= 0.0) {
            headroom = tempToHeadroom(tempC)
            source = "sysfs"
        }
        if (source.isEmpty() && status >= 0) source = "平台档位（无余量）"

        return Thermal(platformStatus = status, headroom = headroom, maxTempC = tempC, source = source)
    }

    private fun toJson(t: Thermal): JSONObject = JSONObject()
        .put("level", t.level)
        .put("headroomPct", t.headroomPct)
        .put("platformStatus", t.platformStatus)
        .put(
            "platformStatusName",
            if (t.platformStatus >= 0) {
                THERMAL_STATUS_NAMES.getOrElse(t.platformStatus) { "UNKNOWN" }
            } else {
                "UNAVAILABLE"
            }
        )
        .put("maxTempC", t.maxTempC)
        .put("source", t.source)
        .put("summary", t.describe())

    /** 温度 → 伪余量。与 thermal.cpp 的 tempToPseudoHeadroom 同阈值。 */
    private fun tempToHeadroom(c: Double): Float = when {
        c < 0.0 -> -1f
        c <= TEMP_COOL_C -> 1f
        c >= TEMP_HOT_C -> 0f
        else -> ((TEMP_HOT_C - c) / (TEMP_HOT_C - TEMP_COOL_C)).toFloat()
    }

    /**
     * 扫描 sysfs 取最热的 SoC 相关温度区。
     *
     * 只统计 cpu/gpu/soc/big/little 等算力相关区 —— 电池与充电口温度不代表
     * 算力余量，混进来会把「边充边用」误判成「SoC 快烧了」而错误降档。
     */
    private fun readMaxSocTempC(): Double {
        val root = File("/sys/class/thermal")
        if (!root.isDirectory) return -1.0
        val zones = root.listFiles() ?: return -1.0

        var maxC = -1.0
        for (zone in zones) {
            if (!zone.name.startsWith("thermal_zone")) continue
            val type = runCatching {
                File(zone, "type").readText().trim().lowercase()
            }.getOrDefault("")
            if (type.isEmpty()) continue
            if (listOf("cpu", "gpu", "soc", "big", "little", "tsens", "apc").none { type.contains(it) }) {
                continue
            }
            val raw = runCatching {
                File(zone, "temp").readText().trim().toLong()
            }.getOrDefault(Long.MIN_VALUE)
            if (raw == Long.MIN_VALUE) continue
            // 单位不统一：多数是 0.001℃，少数直接给 ℃。> 1000 视为毫摄氏度。
            val c = if (raw > 1000L) raw / 1000.0 else raw.toDouble()
            if (c > maxC) maxC = c
        }
        return maxC
    }

    private fun toJson(p: Profile): JSONObject = JSONObject()
        .put("totalCores", p.totalCores)
        .put("bigCores", p.bigCores)
        .put("bigCoreIds", JSONArray(p.bigCoreIds))
        .put("maxBigCoreKhz", p.maxBigCoreKhz)
        .put("memTotalBytes", p.memTotalBytes)
        .put("memAvailableBytes", p.memAvailableBytes)
        .put("memoryBudgetBytes", p.memoryBudgetBytes)
        .put("recommendedThreads", p.recommendedThreads)
        .put("summary", p.describe())

    // ── sysfs / proc 读取 ───────────────────────────────────────────────

    private fun cpuIds(): List<Int> = (0 until 32).filter { id ->
        File("/sys/devices/system/cpu/cpu$id").isDirectory
    }

    private fun maxFreqKhz(cpuId: Int): Long {
        for (name in listOf("cpuinfo_max_freq", "scaling_max_freq")) {
            val f = File("/sys/devices/system/cpu/cpu$cpuId/cpufreq/$name")
            if (f.isFile) {
                val v = runCatching { f.readText().trim().toLong() }.getOrDefault(0L)
                if (v > 0L) return v
            }
        }
        return 0L
    }

    private fun memAvailableFromProc(): Long {
        val f = File("/proc/meminfo")
        if (!f.isFile) return 0L
        return runCatching {
            f.readLines().firstNotNullOfOrNull { line ->
                if (line.startsWith("MemAvailable:")) {
                    line.filter { it.isDigit() }.toLongOrNull()?.times(1024L)
                } else {
                    null
                }
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun memTotalFromProc(): Long {
        val f = File("/proc/meminfo")
        if (!f.isFile) return 0L
        return runCatching {
            f.readLines().firstNotNullOfOrNull { line ->
                if (line.startsWith("MemTotal:")) {
                    line.filter { it.isDigit() }.toLongOrNull()?.times(1024L)
                } else {
                    null
                }
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun systemMemTotal(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        return info.totalMem.takeIf { it > 0L } ?: memTotalFromProc()
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)
}
