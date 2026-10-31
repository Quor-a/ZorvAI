package com.ai.assistance.quro.core.mika.vision

/**
 * 图像处理接口:输入是编码后的图片字节(截屏 PNG 等),输出同样是编码后的字节。
 * 框坐标 [box] 是 [左, 上, 右, 下],按 0–999 归一化,与读屏元素的 bounds 同一口径。
 */
interface ImageOps {
    fun size(img: ByteArray): Pair<Int, Int>

    /** 8×8 灰度平均哈希;两帧的 [hamming] 距离越小越像。 */
    fun aHash(img: ByteArray): Long

    /** 发给模型前压缩:长边不超过 [maxSide]。 */
    fun toJpeg(img: ByteArray, maxSide: Int = 1280, quality: Int = 80): ByteArray

    /** 在 [box] 外扩 [expandPx] 处画红框。 */
    fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int = 3, strokePx: Int = 4): ByteArray

    fun crop(img: ByteArray, box: IntArray): ByteArray

    fun scale(img: ByteArray, factor: Double): ByteArray

    /** 逐像素 ARGB,行优先。 */
    fun pixels(img: ByteArray): IntArray

    /** 几乎全黑:受保护的页面截出来就是这样。 */
    fun isMostlyBlack(img: ByteArray): Boolean
}

fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
