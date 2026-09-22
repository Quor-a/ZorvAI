package com.ai.assistance.quro.genui.sdk.components

import com.ai.assistance.quro.genui.sdk.dsl.UIAnimation
import com.ai.assistance.quro.genui.sdk.dsl.UIStyle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * GenUI 变体裂变矩阵
 *
 * 每个组件类型可通过 palette × shape × animation × density × mood
 * 组合出 14,400 种视觉变体；360+ 组件类型整体裂变 500 万+ 组合。
 *
 * AI 在任意组件的 properties 中传入：
 *   {"variant": "neon", "shape": "pill", "animate": "bounce", "density": "compact", "mood": "cheerful"}
 * 即可应用整套变体，无需手写具体样式。
 */
object ComponentVariants {

    // ==================== 配色板（24：12 风格 + 12 语义） ====================
    // 全部按 ZorvAI 暖色体系重调：原为 Tailwind 默认色（#3B82F6 蓝 / #22D3EE 青 / #A3E635 黄绿 …），
    // 是典型的「AI 生成美学」，与 App 本体的陶土/纸/墨割裂。现统一为低饱和暖色和声。
    // 语义板（success/warning/info/danger/rise/fall/brand）让 AI 不必手写十六进制也能表达语义。
    val palettes: Map<String, List<String>> = mapOf(
        // ---- 风格板（12）----
        "default" to listOf("#C25A38", "#A8482B"),   // 陶土（品牌主色）
        "ocean" to listOf("#3E6B85", "#2A4C61"),     // 暖靛蓝（低饱和，非 AI 蓝）
        "sunset" to listOf("#D9814B", "#B8902F"),    // 陶土 → 金
        "neon" to listOf("#C2452F", "#E0A33C"),      // 暖高能（保留冲击力，去荧光）
        "pastel" to listOf("#EFD8CB", "#E6D9C4"),    // 纸粉
        "forest" to listOf("#6E7C62", "#4E5A45"),    // 鼠尾草
        "candy" to listOf("#C97B6E", "#D9A08F"),     // 暖玫瑰
        "mono" to listOf("#544D44", "#211E1A"),      // 墨阶
        "cyberpunk" to listOf("#7A4E86", "#B8902F"), // 暖紫 + 金
        "sakura" to listOf("#D98C86", "#EBC9C0"),    // 淡樱
        "gold" to listOf("#B8902F", "#8A6A1F"),      // 点缀金
        "aurora" to listOf("#5E8E86", "#7C9A6B"),    // 暖青 → 苔绿

        // ---- 语义板（12）----
        "primary" to listOf("#C25A38", "#A8482B"),
        "brand" to listOf("#C25A38", "#B8902F"),     // 品牌渐变对
        "success" to listOf("#4E7A46", "#3A5C34"),
        "warning" to listOf("#B08320", "#8A6516"),
        "info" to listOf("#3F6E8C", "#2F5468"),
        "danger" to listOf("#B23A2E", "#8E2C22"),
        "error" to listOf("#B23A2E", "#8E2C22"),
        "rise" to listOf("#C0392B", "#A02B20"),      // 涨（中国习惯：红）
        "fall" to listOf("#2E7D5B", "#22624A"),      // 跌（中国习惯：绿）
        "sage" to listOf("#6E7C62", "#4E5A45"),
        "paper" to listOf("#F4F1EA", "#ECE7DC"),
        "ink" to listOf("#211E1A", "#544D44")
    )

    // ==================== 形状（8） ====================
    val shapes: Map<String, Float> = mapOf(
        "square" to 0f,
        "sharp" to 4f,
        "rounded" to 12f,
        "card" to 16f,
        "rounded_xl" to 24f,
        "soft" to 28f,
        "pill" to 999f,
        "blob" to 36f
    )

    // ==================== 动效预设（10） ====================
    val animations: List<String> = listOf(
        "none", "fade", "slide", "scale", "expand",
        "bounce", "zoom", "flip", "pop", "stagger"
    )

    // ==================== 密度（3） ====================
    val densities: List<String> = listOf("compact", "regular", "roomy")

    // ==================== 情绪（5） ====================
    val moods: List<String> = listOf("calm", "energetic", "mysterious", "cheerful", "serious")

    /** 单组件变体总数 = 12 × 8 × 10 × 3 × 5 = 14,400 */
    val variantsPerType: Int
        get() = palettes.size * shapes.size * animations.size * densities.size * moods.size

    /**
     * 从组件 properties 读取变体声明并应用到样式
     *
     * 支持 key：variant / palette（可互换）、shape、animate / animation、density、mood
     * mood 会微调圆角与透明度，density 调整内边距
     */
    fun applyToStyle(style: UIStyle, props: JsonObject): UIStyle {
        if (props.isEmpty()) return style
        var s = style

        // 配色板：palette 或 variant 命中 → 设置背景色
        val paletteKey = props.strOf("palette") ?: props.strOf("variant")
        if (paletteKey != null) {
            palettes[paletteKey.lowercase()]?.let { colors ->
                if (s.backgroundColor == null) s = s.copy(backgroundColor = colors[0])
                if (s.borderColor == null && s.borderWidth > 0f) s = s.copy(borderColor = colors[1])
            }
        }

        // 形状
        props.strOf("shape")?.lowercase()?.let { key ->
            shapes[key]?.let { r -> s = s.copy(cornerRadius = r) }
        }

        // 动效：animate / animation → 转 UIAnimation（在组件层面处理，这里记录到 style.textStyle 不合适，返回原样）
        // 动效由 applyAnimation() 在 GenUIRenderer 中处理

        // 密度：调整内边距
        when (props.strOf("density")?.lowercase()) {
            "compact" -> s = s.copy(padding = s.padding.copy(
                top = s.padding.top.coerceAtMost(8f),
                bottom = s.padding.bottom.coerceAtMost(8f),
                start = s.padding.start.coerceAtMost(10f),
                end = s.padding.end.coerceAtMost(10f)
            ))
            "roomy" -> s = s.copy(padding = s.padding.copy(
                top = (s.padding.top + 8f).coerceAtMost(32f),
                bottom = (s.padding.bottom + 8f).coerceAtMost(32f),
                start = (s.padding.start + 6f).coerceAtMost(28f),
                end = (s.padding.end + 6f).coerceAtMost(28f)
            ))
        }

        // 情绪：mysterious → 深色低透明度；cheerful → 高圆角
        when (props.strOf("mood")?.lowercase()) {
            "mysterious" -> s = s.copy(opacity = s.opacity.coerceAtMost(0.85f), cornerRadius = s.cornerRadius.coerceAtLeast(20f))
            "cheerful" -> s = s.copy(cornerRadius = s.cornerRadius.coerceAtLeast(18f))
            "serious" -> s = s.copy(cornerRadius = s.cornerRadius.coerceAtMost(8f))
        }

        return s
    }

    /**
     * 从 properties 读取动效声明，返回 UIAnimation（无声明返回 null）
     */
    fun resolveAnimation(props: JsonObject, existing: UIAnimation?): UIAnimation? {
        if (existing != null) return existing
        val animKey = props.strOf("animate") ?: props.strOf("animation") ?: return null
        val lower = animKey.lowercase()
        if (lower == "none") return null
        val type = when (lower) {
            "fade" -> "fade"
            "slide" -> "slide"
            "scale" -> "scale"
            "expand" -> "expand"
            "bounce", "pop" -> "scale"
            "zoom" -> "scale"
            "flip", "stagger" -> "slide"
            "blur" -> "fade"
            else -> "fade"
        }
        val duration = when (lower) {
            "bounce", "pop" -> 450
            "stagger" -> 600
            else -> 300
        }
        return UIAnimation(type = type, durationMs = duration, easing = "fast_out")
    }

    private fun JsonObject.strOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}
