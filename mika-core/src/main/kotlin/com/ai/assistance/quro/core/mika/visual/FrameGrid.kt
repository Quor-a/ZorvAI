package com.ai.assistance.quro.core.mika.visual

import kotlin.math.abs

/**
 * 一帧画面缩成的亮度格子([cols]×[rows],行优先):动作前后各取一份,作判效果的旁证(主要看界面状态)。
 * 只用像素读取函数取样,不依赖安卓类(可在 JVM 上测);端口拿手里现成的位图取样,不编码图片。
 */
class FrameGrid(val cols: Int, val rows: Int, val luma: IntArray) {
    init { require(luma.size == cols * rows) }

    companion object {
        private const val SAMPLES = 2

        /** 把 [width]×[height] 的画面切成格子,每格取 2×2 个点的平均亮度;[pixel] 返回 (x, y) 处的 ARGB。太小的画面为 null。 */
        fun sample(width: Int, height: Int, pixel: (Int, Int) -> Int): FrameGrid? {
            val cols = FrameTuning.GRID_COLS; val rows = FrameTuning.GRID_ROWS
            val cw = width / cols
            val ch = height / rows
            if (cw == 0 || ch == 0) return null
            val out = IntArray(cols * rows)
            for (gy in 0 until rows) for (gx in 0 until cols) {
                var sum = 0
                for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                    sum += luma(pixel(gx * cw + (2 * sx + 1) * cw / (2 * SAMPLES), gy * ch + (2 * sy + 1) * ch / (2 * SAMPLES)))
                }
                out[gy * cols + gx] = sum / (SAMPLES * SAMPLES)
            }
            return FrameGrid(cols, rows, out)
        }

        private fun luma(p: Int) = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
    }
}

/** 两帧之间:整屏变了的格子占比 [whole],目标区域里的占比 [region](没给区域为 null)。 */
data class FrameChange(val whole: Double, val region: Double?) {
    /** 有几格以上明显变了(光标闪烁、一个点的涟漪不算)。 */
    val visible: Boolean get() = whole >= FrameTuning.FRAME_VISIBLE || (region ?: 0.0) >= FrameTuning.REGION_VISIBLE
    /** 整屏大面积变了。 */
    val large: Boolean get() = whole >= FrameTuning.FRAME_LARGE
}

/** 动作前后两帧的粗比较:每格亮度差超过 [FrameTuning.CELL_DELTA] 算这格变了。 */
object FrameDiff {
    /** [roi] 是目标区域(0–999 归一化的 [l,t,r,b]);两帧尺寸不一致、缺一帧为 null。 */
    fun compare(before: FrameGrid?, after: FrameGrid?, roi: List<Int>? = null): FrameChange? {
        if (before == null || after == null || before.cols != after.cols || before.rows != after.rows) return null
        val cols = before.cols; val rows = before.rows
        var changed = 0
        var inRegion = 0; var regionChanged = 0
        val box = roi?.takeIf { it.size == 4 }?.let { r ->
            // 向外扩半格,免得小控件落在格子缝里
            val l = (r[0] * cols / 1000.0 - 0.5).toInt().coerceIn(0, cols - 1)
            val t = (r[1] * rows / 1000.0 - 0.5).toInt().coerceIn(0, rows - 1)
            val rr = (r[2] * cols / 1000.0 + 0.5).toInt().coerceIn(l, cols - 1)
            val b = (r[3] * rows / 1000.0 + 0.5).toInt().coerceIn(t, rows - 1)
            intArrayOf(l, t, rr, b)
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = y * cols + x
            val d = abs(before.luma[i] - after.luma[i]) > FrameTuning.CELL_DELTA
            if (d) changed++
            if (box != null && x in box[0]..box[2] && y in box[1]..box[3]) {
                inRegion++
                if (d) regionChanged++
            }
        }
        return FrameChange(changed.toDouble() / (cols * rows), box?.let { regionChanged.toDouble() / inRegion.coerceAtLeast(1) })
    }
}
