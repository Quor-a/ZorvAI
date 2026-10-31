package com.ai.assistance.quro.core.mika.appvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.ai.assistance.quro.core.mika.vision.ImageOps
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** 用系统 Bitmap 实现的图像处理;改过的图按 PNG 输出(不丢细节),只有 [toJpeg] 出 JPEG。 */
object BitmapImageOps : ImageOps {
    /** 亮度低于它的像素算黑。 */
    private const val BLACK_LUMA = 16
    private const val BLACK_SHARE = 0.98
    /** 判全黑时最多抽这么多个点,整屏逐点看太慢。 */
    private const val SAMPLE_SIDE = 64

    private fun decode(img: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(img, 0, img.size) ?: throw IllegalArgumentException("not a decodable image")

    private inline fun <T> use(img: ByteArray, block: (Bitmap) -> T): T {
        val bmp = decode(img)
        try { return block(bmp) } finally { bmp.recycle() }
    }

    private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

    /** 编码后回收;结果可能就是原图(尺寸没变时系统直接返回原图),重复回收无害。 */
    private fun Bitmap.png(): ByteArray = try { encode(Bitmap.CompressFormat.PNG, 100) } finally { recycle() }

    private fun toPx(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt().coerceIn(0, size - 1)

    private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    override fun size(img: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(img, 0, img.size, o)
        require(o.outWidth > 0 && o.outHeight > 0) { "not a decodable image" }
        return o.outWidth to o.outHeight
    }

    /** 每块取真平均:一步缩到 8×8 只是抽点,同一画面两次结果可能不同。逐行读,不一次拿整屏像素。 */
    override fun aHash(img: ByteArray): Long = use(img) { bmp ->
        val w = bmp.width; val h = bmp.height
        val sum = LongArray(64); val count = IntArray(64)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val by = y * 8 / h * 8
            for (x in 0 until w) {
                val i = by + x * 8 / w
                sum[i] += luma(row[x]); count[i]++
            }
        }
        val gray = DoubleArray(64) { if (count[it] == 0) 0.0 else sum[it].toDouble() / count[it] }
        val mean = gray.sum() / 64.0
        gray.foldIndexed(0L) { i, acc, g -> if (g >= mean) acc or (1L shl i) else acc }
    }

    override fun toJpeg(img: ByteArray, maxSide: Int, quality: Int): ByteArray = use(img) { bmp ->
        val f = maxSide.toDouble() / max(bmp.width, bmp.height)
        val out = if (f < 1.0) {
            Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * f).roundToInt()), max(1, (bmp.height * f).roundToInt()), true)
        } else bmp
        try { out.encode(Bitmap.CompressFormat.JPEG, quality) } finally { if (out !== bmp) out.recycle() }
    }

    override fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int, strokePx: Int): ByteArray = use(img) { bmp ->
        require(box.size == 4) { "box must be [l, t, r, b]" }
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val half = strokePx / 2f
        // 描边以线为中心;右、下边的像素格是 [r, r+1),所以线要落在 r+1 上,四条边才一样宽且都在框内
        val rect = RectF(
            toPx(box[0], out.width) - expandPx + half, toPx(box[1], out.height) - expandPx + half,
            toPx(box[2], out.width) + 1 + expandPx - half, toPx(box[3], out.height) + 1 + expandPx - half,
        )
        val paint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = strokePx.toFloat() }
        Canvas(out).drawRect(rect, paint)
        out.png()
    }

    override fun crop(img: ByteArray, box: IntArray): ByteArray = use(img) { bmp ->
        require(box.size == 4) { "box must be [l, t, r, b]" }
        val l = toPx(box[0], bmp.width); val t = toPx(box[1], bmp.height)
        val r = toPx(box[2], bmp.width); val b = toPx(box[3], bmp.height)
        require(r >= l && b >= t) { "empty box" }
        Bitmap.createBitmap(bmp, l, t, r - l + 1, b - t + 1).png()
    }

    override fun scale(img: ByteArray, factor: Double): ByteArray = use(img) { bmp ->
        require(factor > 0) { "factor must be positive" }
        Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * factor).roundToInt()), max(1, (bmp.height * factor).roundToInt()), true)
            .png()
    }

    override fun pixels(img: ByteArray): IntArray = use(img) { bmp ->
        IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    override fun isMostlyBlack(img: ByteArray): Boolean = use(img) { bmp ->
        val sx = max(1, bmp.width / SAMPLE_SIDE); val sy = max(1, bmp.height / SAMPLE_SIDE)
        var total = 0; var black = 0
        for (y in 0 until bmp.height step sy) for (x in 0 until bmp.width step sx) {
            total++
            if (luma(bmp.getPixel(x, y)) < BLACK_LUMA) black++
        }
        black >= total * BLACK_SHARE
    }
}
