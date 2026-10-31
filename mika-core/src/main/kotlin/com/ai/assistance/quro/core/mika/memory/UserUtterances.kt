package com.ai.assistance.quro.core.mika.memory

import java.text.Normalizer

/**
 * 用户本人在这次任务里说的话:目标原文、追问的回答、接管交回时的备注。
 * 记忆的 `quote` 必须是其中某句的子串(全半角统一、去首尾空白后比较)——屏幕、网页、工具结果里的字不算。
 */
class UserUtterances {
    private val texts = mutableListOf<String>()

    @Synchronized fun add(text: String) {
        if (text.isNotBlank()) texts += text
    }

    val all: List<String> @Synchronized get() = texts.toList()

    /** 每次任务开始清空:上一个任务里说的话不算这次的。 */
    @Synchronized fun clear() = texts.clear()

    /**
     * [content] 里的具体值在 [quote] 和用户本次说的话里都找不到的那些:模型转述时从屏幕上抄来的值。
     * 具体值 = 引号内的内容、数字(中文数字、分隔开的数字先统一成一串阿拉伯数字)、3 个字母以上的英文/编号、
     * 地址片段(地名标志字连同前两个字,如「张江路」「一品苑」;只取两个字,模型多补一个区名不至于误拒)。
     * 比较前两边都去掉「用户(的)」「我(的)」,免得第三人称转述本身造成误拒。
     *
     * 局限:没加引号的中文人名、无标志字的地名认不出来(「发给王小明」);这类靠提示词和结果卡片上用户过目。
     */
    @Synchronized fun unsupportedSpecifics(content: String, quote: String): List<String> {
        val c = comparable(content)
        val said = (texts + quote).map(::comparable)
        val specifics = QUOTED.findAll(c).map { it.groupValues[1] } + DIGITS.findAll(c).map { it.value } +
            LATIN.findAll(c).map { it.value } + PLACE.findAll(c).map { it.value }
        return specifics.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filter { v -> said.none { it.contains(v, ignoreCase = true) } }.toList()
    }

    private fun comparable(s: String) = PERSON.replace(NumberText.collapse(s), "")

    /** 不足 2 个字的引文不算数:一两个字在哪句话里都找得到。 */
    @Synchronized fun contains(quote: String): Boolean {
        val q = norm(quote)
        if (q.length < MIN_QUOTE) return false
        return texts.any { norm(it).contains(q) }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).trim()

    private companion object {
        const val MIN_QUOTE = 2
        val QUOTED = Regex("[「『“\"]([^「」『』“”\"\n]+)[」』”\"]")
        val DIGITS = Regex("\\d+")
        val LATIN = Regex("[A-Za-z][A-Za-z0-9._-]{2,}")
        val PLACE = Regex("\\p{IsHan}{2}(?:小区|大厦|广场|公寓|花园|单元|路|街|巷|弄|镇|村|号|楼|栋|室|区|县|市|省|园|苑|城|道|里|湾)")
        val PERSON = Regex("用户的|用户|我的|我")
    }
}
