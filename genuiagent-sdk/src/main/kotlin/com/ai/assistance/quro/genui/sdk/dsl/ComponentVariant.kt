package com.ai.assistance.quro.genui.sdk.dsl

/**
 * 组件变体维度系统
 *
 * 每个组件通过5个维度组合生成变体：
 * - 尺寸 Size: XS, S, M, L, XL (5种)
 * - 风格 Style: Elevated, Filled, Tonal, Outlined, Text (5种)
 * - 形状 Shape: Round, Square, Pill, Rounded (4种)
 * - 状态 State: Default, Pressed, Focused, Disabled, Loading (5种)
 * - 颜色 Color: Primary, Secondary, Tertiary, Error, Surface (5种)
 *
 * 组合数: 5 × 5 × 4 × 5 × 5 = 2500 变体/类型
 * 30 类型 × 2500 = 75,000 变体/领域 (远超 5000)
 */
object ComponentVariant {

    // ==================== 尺寸 ====================
    enum class Size(val value: String, val height: Float, val textSize: Float, val iconSize: Float, val padding: Float) {
        XS("xs", 24f, 11f, 14f, 4f),
        S("s", 32f, 12f, 16f, 6f),
        M("m", 40f, 14f, 20f, 8f),
        L("l", 48f, 16f, 24f, 12f),
        XL("xl", 56f, 18f, 28f, 16f);

        companion object {
            fun from(value: String?): Size = entries.find { it.value == value } ?: M
        }
    }

    // ==================== 风格 ====================
    enum class Style(val value: String) {
        ELEVATED("elevated"),
        FILLED("filled"),
        TONAL("tonal"),
        OUTLINED("outlined"),
        TEXT("text");

        companion object {
            fun from(value: String?): Style = entries.find { it.value == value } ?: FILLED
        }
    }

    // ==================== 形状 ====================
    enum class Shape(val value: String, val cornerRadius: Float) {
        ROUND("round", 100f),
        SQUARE("square", 0f),
        PILL("pill", 50f),
        ROUNDED("rounded", 12f);

        companion object {
            fun from(value: String?): Shape = entries.find { it.value == value } ?: ROUNDED
        }
    }

    // ==================== 状态 ====================
    enum class State(val value: String) {
        DEFAULT("default"),
        PRESSED("pressed"),
        FOCUSED("focused"),
        DISABLED("disabled"),
        LOADING("loading");

        companion object {
            fun from(value: String?): State = entries.find { it.value == value } ?: DEFAULT
        }
    }

    // ==================== 颜色角色 ====================
    enum class ColorRole(val value: String) {
        PRIMARY("primary"),
        SECONDARY("secondary"),
        TERTIARY("tertiary"),
        ERROR("error"),
        SURFACE("surface");

        companion object {
            fun from(value: String?): ColorRole = entries.find { it.value == value } ?: PRIMARY
        }
    }

    // ==================== 内容变体 ====================
    enum class ContentVariant(val value: String) {
        TEXT_ONLY("text_only"),
        ICON_ONLY("icon_only"),
        TEXT_WITH_ICON("text_with_icon"),
        ICON_WITH_TEXT("icon_with_text");

        companion object {
            fun from(value: String?): ContentVariant = entries.find { it.value == value } ?: TEXT_ONLY
        }
    }

    // ==================== 方向 ====================
    enum class Orientation(val value: String) {
        HORIZONTAL("horizontal"),
        VERTICAL("vertical");

        companion object {
            fun from(value: String?): Orientation = entries.find { it.value == value } ?: VERTICAL
        }
    }

    // ==================== 对齐方式 ====================
    enum class Alignment(val value: String) {
        START("start"),
        CENTER("center"),
        END("end"),
        SPACE_BETWEEN("space_between"),
        SPACE_EVENLY("space_evenly"),
        SPACE_AROUND("space_around");

        companion object {
            fun from(value: String?): Alignment = entries.find { it.value == value } ?: START
        }
    }

    // ==================== 交叉对齐 ====================
    enum class CrossAxisAlignment(val value: String) {
        START("start"),
        CENTER("center"),
        END("end"),
        STRETCH("stretch");

        companion object {
            fun from(value: String?): CrossAxisAlignment = entries.find { it.value == value } ?: CENTER
        }
    }

    /**
     * 计算变体组合总数
     */
    val TOTAL_VARIANTS_PER_TYPE = Size.entries.size *
            Style.entries.size *
            Shape.entries.size *
            State.entries.size *
            ColorRole.entries.size // 5×5×4×5×5 = 2500

    /**
     * 每领域变体总数（30 类型 × 2500 = 75,000）
     */
    val VARIANTS_PER_DOMAIN = 30 * TOTAL_VARIANTS_PER_TYPE

    /**
     * 总变体数（10 领域 × 75,000 = 750,000）
     */
    val TOTAL_VARIANTS = 10 * VARIANTS_PER_DOMAIN
}
