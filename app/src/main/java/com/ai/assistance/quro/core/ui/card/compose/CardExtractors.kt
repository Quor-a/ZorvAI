package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.LayoutNode
import org.json.JSONObject

/**
 * 纯 JVM 内容提取器：渲染器与单测共用的「解析层」逻辑。
 *
 * 旧版把文字/渐变/几何/按钮命中全部埋在 Canvas 绘制指令里，单测只能靠录制
 * 假 backend 回放像素来断言，且永远修不对（文字宽度估错、内容消失）。
 * 这里把**与排版无关的内容提取**抽成纯函数：
 *  - 渐变字符串容错解析（List 与逗号/空格串都认）；
 *  - 自定义布局树的全量文字收集（未知节点也兜底收集，杜绝「内容消失」）；
 *  - metric 卡的展示值（含 rawJson 兜底，杜绝「灰盒 --」）；
 *  - 按钮组动作解析、布局树粗略高度估计（回归「不会塌成 0 高」）。
 * 这些函数不依赖 Compose 运行时，纯 JVM 即可断言。
 */
object CardExtractors {

    /** 读取节点 props 的数值（缺省走默认）。 */
    fun num(props: Map<String, Any?>, key: String, def: Float): Float =
        (props[key] as? Number)?.toFloat() ?: def

    /**
     * 把「百分比类」数值归一成 0..1。
     *
     * 🔴 模型写进度/环形值时两种写法混着来：`{"value":86}`（0~100）与 `{"value":0.86}`（0~1）。
     * 旧实现一律 `/100`，于是 `0.86` 变成 0.0086 —— 进度条几乎看不见、环形几乎不画，
     * 用户看到的就是「卡片是空的」。这里按值域自动判定：
     *  - 给了 `max` → 按 max 归一；
     *  - 值 > 1 → 视为 0~100；
     *  - 否则视为已经是 0~1（`1` 即 100%）。
     */
    fun percent(props: Map<String, Any?>, key: String = "value"): Float {
        val v = (props[key] as? Number)?.toFloat() ?: 0f
        val max = (props["max"] as? Number)?.toFloat()
        return when {
            max != null && max > 0f -> (v / max).coerceIn(0f, 1f)
            v > 1.0001f -> (v / 100f).coerceIn(0f, 1f)
            v < 0f -> 0f
            else -> v
        }
    }

    /** 把任意值解析成 Compose Color：#rrggbb / #aarrggbb / #rgb / 主题 token 名。token 用传入的 resolve 解析。 */
    fun parseColor(any: Any?, resolve: (ColorToken) -> Color): Color? {
        val s = any?.toString() ?: return null
        return try {
            if (s.startsWith("#")) parseHexColor(s)
            else {
                val norm = s.replace("_", "")
                val tok = ColorToken.values().firstOrNull { it.name.equals(norm, ignoreCase = true) } ?: return null
                resolve(tok)
            }
        } catch (_: Exception) { null }
    }

    /** 手写 hex 解析，避免依赖 Android 运行时（android.graphics.Color），保证纯 JVM 可测。 */
    private fun parseHexColor(s: String): Color {
        val h = s.removePrefix("#").trim()
        return when (h.length) {
            6 -> Color(
                h.substring(0, 2).toInt(16),
                h.substring(2, 4).toInt(16),
                h.substring(4, 6).toInt(16),
            )
            8 -> Color(
                h.substring(0, 2).toInt(16),
                h.substring(2, 4).toInt(16),
                h.substring(4, 6).toInt(16),
                h.substring(6, 8).toInt(16),
            )
            3 -> Color(
                h.substring(0, 1).repeat(2).toInt(16),
                h.substring(1, 2).repeat(2).toInt(16),
                h.substring(2, 3).repeat(2).toInt(16),
            )
            else -> Color.Black
        }
    }

    /**
     * 渐变色字符串列表提取：兼容标准 List 与逗号/空格/换行分隔字符串。
     * 返回 >=1 个合法色串（解析成 Color 交给渲染器，>=2 才视为有效渐变）。
     */
    fun extractGradientStrs(any: Any?): List<String>? {
        val parts: List<String> = when (any) {
            is List<*> -> any.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
            is String -> any.split(",", " ", "\n").map { it.trim() }.filter { it.isNotBlank() }
            else -> return null
        }
        if (parts.isEmpty()) return null
        return parts
    }

    /** 渐变解析成 Color 列表（>=2 才视为有效渐变）。 */
    fun extractGradientColors(any: Any?, resolve: (ColorToken) -> Color): List<Color>? {
        val strs = extractGradientStrs(any) ?: return null
        val colors = strs.mapNotNull { parseColor(it, resolve) }
        return colors.takeIf { it.size >= 2 }
    }

    /** 递归收集 custom 布局树中的所有可见文字（text/badge/button/icon.emoji/list items/未知节点 text）。 */
    fun collectTexts(node: LayoutNode?): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: LayoutNode) {
            val p = n.props
            val t = p["text"]?.toString() ?: p["label"]?.toString() ?: p["title"]?.toString() ?: ""
            when (n.type) {
                "text", "badge", "chip", "tag", "pill", "button", "icon", "image", "avatar" -> {
                    if (t.isNotEmpty()) out.add(t)
                }
                "list", "items", "group", "grid" -> {
                    if (n.children.isEmpty()) {
                        val items = p["items"]
                        if (items is List<*>) {
                            items.forEach {
                                val s = when (it) {
                                    is Map<*, *> -> (it["text"] ?: it["label"] ?: it["title"] ?: "").toString()
                                    else -> it?.toString() ?: ""
                                }
                                if (s.isNotEmpty()) out.add(s)
                            }
                        }
                    }
                }
                else -> {
                    if (t.isNotEmpty()) out.add(t) // 未知节点也兜底收集文字
                }
            }
            n.children.forEach { walk(it) }
        }
        node?.let { walk(it) }
        return out
    }

    /** metric 卡展示值（含 rawJson 兜底）。纯 JVM，便于单测断言「残缺卡也出原文、不画 --」。 */
    data class MetricDisplay(val name: String, val valueText: String?, val extra: Int)

    fun metricValues(spec: CardSpec): MetricDisplay {
        val chart = spec.data as? CardData.Chart
        val s = chart?.series?.firstOrNull()
        val raw = runCatching { JSONObject(spec.rawJson) }.getOrNull()
        val rawLabel = raw?.optString("label", "")?.ifBlank { raw.optString("title", "") } ?: ""
        val rawUnit = raw?.optString("unit", "") ?: ""
        val rawValue: Any? = raw?.opt("value")
        val name = (s?.name ?: "").ifBlank { rawLabel }
        val value = s?.points?.firstOrNull()
        val unit = (s?.labels?.firstOrNull() ?: "").ifBlank { rawUnit }
        val valueText: String? = when {
            value != null -> {
                val v = if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
                if (unit.isEmpty()) v else "$v $unit"
            }
            rawValue is Number -> rawValue.toString()
            rawValue is String && rawValue.isNotBlank() -> rawValue
            else -> null
        }
        val extra = (chart?.series?.size ?: 1) - 1
        return MetricDisplay(name, valueText, extra)
    }

    /** button_group：按 index 取动作；优先 Form.buttons[i].action，否则 spec.actions[i]。 */
    fun resolveButtonAction(spec: CardSpec, index: Int): Action? {
        val form = spec.data as? CardData.Form
        form?.buttons?.getOrNull(index)?.action?.let { return it }
        return spec.actions.getOrNull(index)
    }

    /**
     * custom 布局树粗略高度估算（dp），用于回归断言「不会塌成 0 高 / 内容不丢」。
     * 非精确排版（Compose 实际由布局引擎自动计算），仅保证一个合理的正高度。
     */
    fun estimateHeightDp(node: LayoutNode?, fallback: Float = 60f): Float {
        fun walk(n: LayoutNode): Float {
            val p = n.props
            val pad = num(p, "padding", n.style.paddingDp)
            val gap = num(p, "gap", 8f)
            val inner: Float = when (n.type) {
                "card", "box", "column", "list", "items", "group", "grid" -> {
                    if (n.children.isEmpty()) {
                        num(p, "h", 40f)
                    } else {
                        val subs = n.children.sumOf { walk(it).toDouble() }.toFloat()
                        subs + gap * (n.children.size - 1).coerceAtLeast(0)
                    }
                }
                "row" -> if (n.children.isEmpty()) 20f else n.children.maxOf { walk(it) }
                "text" -> num(p, "fontSize", n.style.fontSizeSp)
                "spacer" -> num(p, "h", 8f)
                "divider" -> 1f
                "ring" -> num(p, "size", 90f)
                "bar" -> num(p, "h", 10f)
                "badge", "chip", "tag", "pill" -> num(p, "fontSize", 11f) + 8f
                "button" -> num(p, "fontSize", 14f) + 20f
                "icon", "image", "avatar" -> num(p, "size", 24f)
                else -> {
                    val t = p["text"]?.toString() ?: p["label"]?.toString() ?: ""
                    if (t.isNotEmpty()) num(p, "fontSize", n.style.fontSizeSp) else num(p, "h", 1f)
                }
            }
            return inner + pad * 2
        }
        return node?.let { walk(it) } ?: fallback
    }
}
