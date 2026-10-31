package com.ai.assistance.quro.core.mika.vision

import com.ai.assistance.quro.core.mika.model.Part
import kotlinx.coroutines.CancellationException
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 发给任何模型的图片(主模型的读屏、截图、回看,看图模型,核查器)都按这一种编码:
 * JPEG,长边不超过 [MAX_SIDE],质量 [QUALITY]。全分辨率 PNG 一张要几千 token、上 MB,而读屏靠的是控件树,
 * 画面只要看得清版面和文字。哪些画面不能发(受保护页面、付款页)由调用方决定,这里只管编码。
 */
object ModelImage {
    const val MAX_SIDE = 1280
    const val QUALITY = 80

    /**
     * 编码成发给模型的一张图。[ops] 为空或解不开时原样发出(按文件头认 mime),尺寸能读出就带上。
     * [screenshotId]:是哪次读屏的画面;不是屏幕画面时为 null。
     */
    fun part(img: ByteArray, ops: ImageOps?, screenshotId: Int? = null): Part.Image {
        val jpeg = ops?.let { o -> attempt { o.toJpeg(img, MAX_SIDE, QUALITY) } }
        val out = jpeg ?: img
        val size = ops?.let { o -> attempt { o.size(out) } } ?: pngSize(out)
        return Part.Image(
            Base64.getEncoder().encodeToString(out), if (jpeg != null) "image/jpeg" else mimeOf(img),
            size?.first ?: 0, size?.second ?: 0, screenshotId,
        )
    }

    /** 按 [MAX_SIDE] 缩放后的尺寸(和 [ImageOps.toJpeg] 同一算法);估算用。 */
    fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val f = MAX_SIDE.toDouble() / max(width, height)
        if (f >= 1.0) return width to height
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }

    private inline fun <T> attempt(block: () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    private fun mimeOf(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "image/gif"
        else -> "image/png"
    }

    /** PNG 的 IHDR 里直接读宽高;不是 PNG 为 null。 */
    private fun pngSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || b[0] != 0x89.toByte() || b[1] != 'P'.code.toByte() || b[2] != 'N'.code.toByte() || b[3] != 'G'.code.toByte()) return null
        fun int(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }
}
