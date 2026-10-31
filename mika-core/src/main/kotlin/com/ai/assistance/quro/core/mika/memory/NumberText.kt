package com.ai.assistance.quro.core.mika.memory

import java.text.Normalizer

/**
 * 数字的统一写法,记忆校验与技能脱敏共用:全角转半角,3 个以上连写的中文数字逐字转成阿拉伯数字
 * (十百千去掉:两边用同一转换,比较时一致即可),数字之间的空格、连字符、点、逗号去掉
 * (「138 0013 8000」「1,234」「一三八零零一三八零零零」都变成一串数字)。
 */
internal object NumberText {
    private val CN_DIGIT = mapOf('零' to '0', '〇' to '0', '一' to '1', '二' to '2', '三' to '3', '四' to '4',
        '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9')
    val CN_RUN = Regex("[零〇一二三四五六七八九十百千]{3,}")
    private val SEPARATED = Regex("\\d(?:[\\s\\-.,]*\\d)+")
    private const val MIN_SEPARATED = 3

    fun collapse(s: String): String {
        val t = CN_RUN.replace(Normalizer.normalize(s, Normalizer.Form.NFKC)) { m ->
            m.value.mapNotNull { CN_DIGIT[it] }.joinToString("").ifEmpty { m.value }
        }
        return SEPARATED.replace(t) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length >= MIN_SEPARATED) digits else m.value
        }
    }
}
