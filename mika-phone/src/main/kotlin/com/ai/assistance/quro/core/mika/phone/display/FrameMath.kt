package com.ai.assistance.quro.core.mika.phone.display

import kotlin.math.abs

/**
 * 画面粗判,只用像素读取函数,不依赖安卓类(可在 JVM 上测)。
 * [pixel] 返回 (x, y) 处的 ARGB 颜色值。
 */
object FrameMath {
    const val GRID_W = 12
    const val GRID_H = 24
    private const val CELL_SAMPLES = 4
    private const val CELL_DELTA = 16
    private const val MIN_CELLS = 3
    private const val PAGE_SHARE = 0.25
    private const val BLACK_SAMPLES = 24
    private const val BLACK_LEVEL = 8

    /** 把画面切成 [GRID_W]×[GRID_H] 格,每格取 4×4 个点的平均亮度。 */
    fun lumaGrid(width: Int, height: Int, pixel: (Int, Int) -> Int): IntArray {
        val out = IntArray(GRID_W * GRID_H)
        val cw = width / GRID_W
        val ch = height / GRID_H
        if (cw == 0 || ch == 0) return out
        for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
            var sum = 0L
            for (sy in 0 until CELL_SAMPLES) for (sx in 0 until CELL_SAMPLES) {
                sum += luma(pixel(gx * cw + sx * cw / CELL_SAMPLES, gy * ch + sy * ch / CELL_SAMPLES))
            }
            out[gy * GRID_W + gx] = (sum / (CELL_SAMPLES * CELL_SAMPLES)).toInt()
        }
        return out
    }

    /** 有足够多的格子明显变亮或变暗才算画面变了,光标闪烁这类小变化不算。 */
    fun differs(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        return a.indices.count { abs(a[it] - b[it]) > CELL_DELTA } >= MIN_CELLS
    }

    /** 至少 [PAGE_SHARE] 的格子变了:换了一整页(或整屏在动),不是头像动图、光标、进度圈这类局部变化。 */
    fun pageChanged(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        return a.indices.count { abs(a[it] - b[it]) > CELL_DELTA } >= a.size * PAGE_SHARE
    }

    /** 均匀取样的点里至少 99.5% 每个通道都 ≤ [BLACK_LEVEL] 才算全黑。 */
    fun isBlack(width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
        if (width <= 1 || height <= 1) return false
        var dark = 0
        val total = BLACK_SAMPLES * BLACK_SAMPLES
        for (gy in 0 until BLACK_SAMPLES) for (gx in 0 until BLACK_SAMPLES) {
            val p = pixel(gx * (width - 1) / (BLACK_SAMPLES - 1), gy * (height - 1) / (BLACK_SAMPLES - 1))
            if ((p shr 16 and 0xff) <= BLACK_LEVEL && (p shr 8 and 0xff) <= BLACK_LEVEL && (p and 0xff) <= BLACK_LEVEL) dark++
        }
        return dark * 1000 >= total * 995
    }

    private fun luma(p: Int) = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
}
