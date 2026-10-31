package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import kotlin.math.ceil

/**
 * 不调分词器的粗估:非汉字字符 / 4 + 汉字 × 0.75;图片知道尺寸时按 宽×高/[PIXELS_PER_TOKEN],不知道按 1000 计。
 * 只用来判断要不要压缩。
 * 中日韩标点、全角字符、假名、谚文和汉字一样按 0.75 计。
 */
object TokenEstimate {
    private const val IMAGE_TOKENS = 1000
    /** 常见服务商的口径(约 28×28 像素一个 token,各家略有出入)。 */
    const val PIXELS_PER_TOKEN = 750.0

    /** 一张 [width]×[height] 的图大约多少 token;尺寸不知道时按 1000。 */
    fun image(width: Int, height: Int): Int = if (width > 0 && height > 0) ceil(width.toLong() * height / PIXELS_PER_TOKEN).toInt() else IMAGE_TOKENS

    fun of(messages: List<Message>): Int {
        var images = 0
        var text = 0.0
        for (m in messages) for (p in m.parts) when (p) {
            is Part.Image -> images += image(p.width, p.height)
            is Part.Text -> text += chars(p.text)
            is Part.Thinking -> text += chars(p.text)
            is Part.ToolCall -> text += chars(p.name) + chars(p.argsJson)
            is Part.ToolResult -> text += chars(p.content)
        }
        return images + ceil(text).toInt()
    }

    /**
     * 用真实用量校准粗估:返回新的「真实 / 粗估」比值。[previous] 为 null 时直接取这一次的比值,
     * 否则和之前的平滑合并;结果限在 [MIN_SCALE]..[MAX_SCALE],一次异常的用量不会把估算带偏太多。
     */
    fun calibrated(previous: Double?, estimated: Int, actual: Int): Double {
        if (estimated <= 0 || actual <= 0) return previous ?: 1.0
        val ratio = actual.toDouble() / estimated
        val next = if (previous == null) ratio else previous * (1 - SMOOTHING) + ratio * SMOOTHING
        return next.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 3.0
    private const val SMOOTHING = 0.3

    /** 同一口径估算一段文字,用于不在消息里的固定开销(基础提示词、工具定义)。 */
    fun of(text: String): Int = ceil(chars(text)).toInt()

    private val WIDE_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    )
    private val WIDE_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION, Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
    )

    private fun wide(cp: Int) = Character.UnicodeScript.of(cp) in WIDE_SCRIPTS || Character.UnicodeBlock.of(cp) in WIDE_BLOCKS

    private fun chars(s: String): Double {
        var han = 0
        var other = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (wide(cp)) han++ else other++
            i += Character.charCount(cp)
        }
        return han * 0.75 + other / 4.0
    }
}
