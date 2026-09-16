package com.zorv.plugin.units

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin

/**
 * 示例插件：单位换算。
 *
 * 演示「枚举参数 + 数值参数 + 中文别名」：
 *  LLM 会把 category 填成 8 个枚举值之一，from/to 用人类习惯的单位名（支持中文别名）。
 */
class UnitsEntry : PluginEntry {

    override fun onCreate(ctx: PluginContext) {
        ctx.log("UnitsEntry", "单位换算插件启动 v${ctx.pluginVersion}")

        plugin(ctx) {
            aiTool(
                name = "unit_convert",
                description = "在各种计量单位之间换算（长度/重量/温度/面积/体积/速度/数据存储/时间）。" +
                    "用户问「100 公里等于多少英里」「30 摄氏度是多少华氏度」这类问题时调用。"
            ) {
                param("value", ParamType.NUMBER, "要换算的数值")
                param("from", ParamType.STRING, "源单位，如 km / 公里 / c / 摄氏度 / mb")
                param("to", ParamType.STRING, "目标单位，如 mile / 英里 / f / 华氏度 / gb")
                param(
                    "category", ParamType.STRING, "类别", required = false,
                    enum = listOf("length", "weight", "temperature", "area", "volume", "speed", "data", "time"),
                    default = "length"
                )
                execute { args ->
                    val v = args.number("value", Double.NaN)
                    if (v.isNaN()) return@execute ToolResult.error("value 必须是数字")
                    val from = args.string("from").trim()
                    val to = args.string("to").trim()
                    if (from.isEmpty() || to.isEmpty()) return@execute ToolResult.error("from 与 to 都不能为空")
                    val category = args.string("category").ifBlank { "length" }

                    runCatching { convert(v, from, to, category) }
                        .fold(
                            onSuccess = { ToolResult.text(it) },
                            onFailure = { ToolResult.error(it.message ?: "换算失败") }
                        )
                }
            }
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }

    // ==================== 换算实现 ====================

    /** 类别 → (单位别名 → 相对基准单位的系数) */
    private val tables: Map<String, Map<String, Double>> = mapOf(
        "length" to mapOf(
            "mm" to 0.001, "毫米" to 0.001,
            "cm" to 0.01, "厘米" to 0.01,
            "m" to 1.0, "米" to 1.0,
            "km" to 1000.0, "千米" to 1000.0, "公里" to 1000.0,
            "inch" to 0.0254, "in" to 0.0254, "英寸" to 0.0254,
            "foot" to 0.3048, "ft" to 0.3048, "英尺" to 0.3048,
            "yard" to 0.9144, "码" to 0.9144,
            "mile" to 1609.344, "mi" to 1609.344, "英里" to 1609.344,
            "nmi" to 1852.0, "海里" to 1852.0,
        ),
        "weight" to mapOf(
            "mg" to 0.000001, "毫克" to 0.000001,
            "g" to 0.001, "克" to 0.001,
            "kg" to 1.0, "千克" to 1.0, "公斤" to 1.0,
            "t" to 1000.0, "吨" to 1000.0,
            "lb" to 0.45359237, "磅" to 0.45359237,
            "oz" to 0.028349523125, "盎司" to 0.028349523125,
            "jin" to 0.5, "斤" to 0.5,
            "liang" to 0.05, "两" to 0.05,
        ),
        "area" to mapOf(
            "cm2" to 0.0001, "平方厘米" to 0.0001,
            "m2" to 1.0, "平方米" to 1.0, "平米" to 1.0,
            "km2" to 1_000_000.0, "平方千米" to 1_000_000.0, "平方公里" to 1_000_000.0,
            "mu" to 666.6666666667, "亩" to 666.6666666667,
            "hectare" to 10_000.0, "ha" to 10_000.0, "公顷" to 10_000.0,
            "acre" to 4046.8564224, "英亩" to 4046.8564224,
        ),
        "volume" to mapOf(
            "ml" to 0.001, "毫升" to 0.001,
            "l" to 1.0, "升" to 1.0,
            "m3" to 1000.0, "立方米" to 1000.0,
            "gal" to 3.785411784, "加仑" to 3.785411784,
            "cup" to 0.2365882365, "杯" to 0.2365882365,
        ),
        "speed" to mapOf(
            "mps" to 1.0, "米每秒" to 1.0,
            "kmph" to 0.2777777778, "kph" to 0.2777777778, "kmh" to 0.2777777778, "公里每小时" to 0.2777777778,
            "mph" to 0.44704, "英里每小时" to 0.44704,
            "knot" to 0.5144444444, "节" to 0.5144444444,
        ),
        "data" to mapOf(
            "b" to 1.0, "byte" to 1.0, "字节" to 1.0,
            "kb" to 1024.0, "kb_1000" to 1000.0,
            "mb" to 1048576.0,
            "gb" to 1073741824.0,
            "tb" to 1099511627776.0,
        ),
        "time" to mapOf(
            "ms" to 0.001, "毫秒" to 0.001,
            "s" to 1.0, "sec" to 1.0, "秒" to 1.0,
            "min" to 60.0, "分钟" to 60.0,
            "h" to 3600.0, "hr" to 3600.0, "小时" to 3600.0,
            "d" to 86400.0, "day" to 86400.0, "天" to 86400.0,
        ),
    )

    private fun convert(value: Double, fromRaw: String, toRaw: String, categoryRaw: String): String {
        val category = categoryRaw.lowercase()
        val from = fromRaw.lowercase()
        val to = toRaw.lowercase()

        if (category == "temperature" || from in TEMP_ALIASES || to in TEMP_ALIASES) {
            val fromK = toKelvin(value, from)
            val out = fromKelvin(fromK, to)
            return fmt(value, fromRaw) + " = " + fmt(out, toRaw) +
                "\n(${fmt(fromK, "K")} 开尔文)"
        }

        val table = tables[category]
            ?: throw IllegalArgumentException("未知类别 $categoryRaw，可用：" + tables.keys.joinToString(" / "))
        val f = table[from] ?: throw IllegalArgumentException("类别 $category 下不认识源单位「$fromRaw」")
        val t = table[to] ?: throw IllegalArgumentException("类别 $category 下不认识目标单位「$toRaw」")
        val base = value * f
        val out = base / t
        return fmt(value, fromRaw) + " = " + fmt(out, toRaw)
    }

    private val TEMP_ALIASES = setOf(
        "c", "celsius", "摄氏", "摄氏度", "°c",
        "f", "fahrenheit", "华氏", "华氏度", "°f",
        "k", "kelvin", "开尔文", "开"
    )

    private fun toKelvin(v: Double, unit: String): Double = when (unit) {
        "c", "celsius", "摄氏", "摄氏度", "°c" -> v + 273.15
        "f", "fahrenheit", "华氏", "华氏度", "°f" -> (v - 32) * 5.0 / 9.0 + 273.15
        "k", "kelvin", "开尔文", "开" -> v
        else -> throw IllegalArgumentException("不认识温度单位「$unit」")
    }

    private fun fromKelvin(k: Double, unit: String): Double = when (unit) {
        "c", "celsius", "摄氏", "摄氏度", "°c" -> k - 273.15
        "f", "fahrenheit", "华氏", "华氏度", "°f" -> (k - 273.15) * 9.0 / 5.0 + 32
        "k", "kelvin", "开尔文", "开" -> k
        else -> throw IllegalArgumentException("不认识温度单位「$unit」")
    }

    private fun fmt(v: Double, unit: String): String {
        val s = if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format("%.6f", v).trimEnd('0').trimEnd('.')
        return "$s $unit"
    }
}
