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

    // ==================== 配色板（12） ====================
    val palettes: Map<String, List<String>> = mapOf(
        "default" to listOf("#3B82F6", "#1D4ED8"),
        "ocean" to listOf("#0EA5E9", "#0369A1"),
        "sunset" to listOf("#FB923C", "#E11D48"),
        "neon" to listOf("#22D3EE", "#A3E635"),
        "pastel" to listOf("#FBCFE8", "#BFDBFE"),
        "forest" to listOf("#22C55E", "#15803D"),
        "candy" to listOf("#F472B6", "#FB7185"),
        "mono" to listOf("#525252", "#171717"),
        "cyberpunk" to listOf("#F0ABFC", "#4C1D95"),
        "sakura" to listOf("#FDA4AF", "#FECDD3"),
        "gold" to listOf("#FBBF24", "#B45309"),
        "aurora" to listOf("#34D399", "#818CF8")
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
