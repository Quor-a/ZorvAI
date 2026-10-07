package com.codecanvas.core.model

/**
 * 画布规格。所有渲染后端共用同一份描述，保证「同一段脚本 → 任意架构 → 同样的图」。
 *
 * 坐标系统一采用 **逻辑像素(dp 语义)**，由各 Renderer 自行乘以 density/scale 落到物理像素，
 * 因此同一 spec 在 Canvas / WebView / Compose / SVG 上输出尺寸一致。
 */
data class CanvasSpec(
    val width: Float = 1080f,
    val height: Float = 1440f,
    /** 输出缩放倍率：1 = 逻辑尺寸，2 = 2x 高清图 */
    val scale: Float = 2f,
    val background: Background = Background.Solid(0xFFFFFFFF.toInt()),
    /** 内边距，脚本画的 (0,0) 会偏移到这里 */
    val padding: Padding = Padding.all(48f),
    /** 抗锯齿 / 文本子像素 */
    val antialias: Boolean = true,
    /** 最长边硬上限，防止长图 OOM；超出时 Renderer 会自动降采样 */
    val maxEdgePx: Int = 4096,
) {
    val pixelWidth: Int get() = (width * scale).toInt()
    val pixelHeight: Int get() = (height * scale).toInt()

    /** 内边距。传一个值是四周相同，传两个是 (水平, 垂直) */
    data class Padding(val horizontal: Float = 0f, val vertical: Float = 0f) {
        companion object {
            fun all(v: Float) = Padding(v, v)
        }
    }

    sealed interface Background {
        data class Solid(val color: Int) : Background
        data class LinearGradient(val colors: IntArray, val angleDeg: Float = 135f) : Background
        object Transparent : Background
    }
}

/** 颜色 + 描边 + 阴影的统一样式描述，脚本与渲染器之间传输用 */
data class Style(
    val fill: Int? = null,
    val stroke: Int? = null,
    val strokeWidth: Float = 1f,
    val alpha: Float = 1f,
    val cornerRadius: Float = 0f,
    val shadow: Shadow? = null,
) {
    data class Shadow(val color: Int, val blur: Float, val dx: Float, val dy: Float)
}

/** 字体描述。中文字体建议 App 自带等宽字体，否则不同机型行宽不一致 */
data class FontSpec(
    val family: String = "monospace",
    val size: Float = 28f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val letterSpacing: Float = 0f,
)
