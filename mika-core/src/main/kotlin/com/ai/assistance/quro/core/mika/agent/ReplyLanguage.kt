package com.ai.assistance.quro.core.mika.agent

import java.lang.Character.UnicodeScript

/**
 * 最终回答的语言是不是跟着用户:按字母所属的书写系统(Unicode 文字)数,不看模型、不看词表。纯函数。
 *
 * 文字归成语族:汉字和假名一起数,假名够多(至少占这两种的 [KANA_SHARE])算日文,否则算中文;谚文是韩文;
 * 拉丁、西里尔、阿拉伯等其他文字各自一族。数字、标点、空白、markdown 符号都不算字母。
 *
 * 跨文字要公平:中文、日文不用空格分词,一个汉字(假名)大致是一个词,按字数;其余按空格分词的文字按词数
 * (同一文字的字母连在一起算一个词),不按字母数,否则一个英文词要顶好几个汉字。拉丁、西里尔里没有小写字母的词
 * (GB、OS、型号缩写)多是单位和缩写,不算。
 *
 * 回答里不算的部分:各种引号里的(「」『』“”"" 《》)、括号里的(() 和 ()),反引号里的代码,网址,尖括号标签。
 * 这些多是照抄的原文或标注的原文,本来就该保留。另外,一行(或用 |、、 分开的一格)里只有一串同一语族的字、
 * 而且不超过 [NAME_UNITS] 个单位时,当成名字或标签(列表里的 App 名、表格里的一格),也不算:只列外文名字的回答不会被判成换了语言。
 *
 * 判换了语言要同时满足:用户那句至少 [MIN_USER_UNITS] 个单位,回答剩下的正文至少 [MIN_ANSWER_UNITS] 个单位,
 * 回答里最多的语族至少占 [MISMATCH_SHARE],而且不是用户那句里最多的语族。
 */
internal object ReplyLanguage {
    const val MIN_USER_UNITS = 3
    const val MIN_ANSWER_UNITS = 20
    const val MISMATCH_SHARE = 0.6
    const val NAME_UNITS = 6
    const val KANA_SHARE = 0.1

    /** 语族;[display] 是写给模型的说法。 */
    data class Family(val display: String)

    val CHINESE = Family("Chinese")
    val JAPANESE = Family("Japanese")
    val KOREAN = Family("Korean")

    /** 回答 [answer] 的语族和用户那句 [user] 的语族。 */
    data class Mismatch(val answer: Family, val user: Family)

    /** 回答的正文明显不是用户那句的语言时返回两边的语族;否则(或字太少判不了)为 null。 */
    fun mismatch(user: String, answer: String): Mismatch? {
        val u = units(user, skipNames = false)
        val a = units(stripQuoted(answer), skipNames = true)
        val uTotal = u.values.sum()
        val aTotal = a.values.sum()
        if (uTotal < MIN_USER_UNITS || aTotal < MIN_ANSWER_UNITS) return null
        val userFamily = u.maxBy { it.value }.key
        val (answerFamily, count) = a.maxBy { it.value }
        if (count < aTotal * MISMATCH_SHARE || answerFamily == userFamily) return null
        return Mismatch(answerFamily, userFamily)
    }

    /** 文字 [text] 里各语族的单位数(见类说明);[skipNames] 时不算只有一串短字的行或格。 */
    fun units(text: String, skipNames: Boolean): Map<Family, Int> {
        val cells = text.split(CELL).map { cell -> runsOf(cell).filter { it.units > 0 } }
        val counted = (if (skipNames) cells.filterNot { it.size == 1 && it[0].units <= NAME_UNITS } else cells).flatten()
        val han = counted.filter { it.key == HAN }.sumOf { it.units }
        val kana = counted.filter { it.key == KANA }.sumOf { it.units }
        val cjk = if (kana > 0 && kana >= (han + kana) * KANA_SHARE) JAPANESE else CHINESE
        val out = LinkedHashMap<Family, Int>()
        for (r in counted) {
            val family = when (r.key) {
                HAN, KANA -> cjk
                UnicodeScript.HANGUL.name -> KOREAN
                else -> Family(r.key.lowercase().replaceFirstChar(Char::uppercaseChar) + " script")
            }
            out[family] = (out[family] ?: 0) + r.units
        }
        return out
    }

    /** 去掉回答里引号、括号、反引号里的内容和网址、标签(括号可以套着,里外都去掉)。 */
    fun stripQuoted(text: String): String {
        var s = FENCE.replace(text, " ")
        for (r in listOf(INLINE_CODE, URL, TAG, QUOTES)) s = r.replace(s, " ")
        while (true) {
            val next = PARENS.replace(s, " ")
            if (next == s) return s
            s = next
        }
    }

    /** 一串连着的同一文字的字:[key] 是文字(汉字、假名合并前分开记),[units] 是单位数。 */
    private data class Run(val key: String, var units: Int)

    /**
     * 把一格切成串:同一文字的字母之间只隔着空格、连字符、撇号、组合符号(或不属于哪种文字的字母)时算同一串。
     * 汉字、假名每个字一个单位;其他文字每个词一个单位,没有小写字母的拉丁、西里尔词不算。
     */
    private fun runsOf(cell: String): List<Run> {
        val out = mutableListOf<Run>()
        var current: Run? = null
        val word = StringBuilder()
        fun endWord() {
            val r = current
            if (word.isNotEmpty() && r != null && countsAsWord(word)) r.units++
            word.clear()
        }
        var i = 0
        while (i < cell.length) {
            val cp = cell.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) continue
            // 不属于哪种文字的字母(长音符「ー」等)跟着前后的字,自己不算
            if (Character.isLetter(cp) && UnicodeScript.of(cp).let { it == UnicodeScript.COMMON || it == UnicodeScript.INHERITED }) continue
            if (Character.isLetter(cp)) {
                val key = keyOf(cp)
                if (current?.key != key) { endWord(); current = Run(key, 0).also { out += it } }
                if (key == HAN || key == KANA) current!!.units++ else word.appendCodePoint(cp)
            } else if (cp == ' '.code || cp == '-'.code || cp == '\''.code || cp == '’'.code) {
                endWord()
            } else {
                endWord()
                current = null
            }
        }
        endWord()
        return out
    }

    private fun countsAsWord(word: CharSequence): Boolean {
        val script = UnicodeScript.of(Character.codePointAt(word, 0))
        if (script != UnicodeScript.LATIN && script != UnicodeScript.CYRILLIC) return true
        return word.codePoints().anyMatch(Character::isLowerCase)
    }

    private fun keyOf(cp: Int): String = when (val s = UnicodeScript.of(cp)) {
        UnicodeScript.HAN -> HAN
        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> KANA
        else -> s.name
    }

    private const val HAN = "HAN"
    private const val KANA = "KANA"

    /** 行、表格的格、顿号分开的并列项各是一格。 */
    private val CELL = Regex("[\\n|、]")
    private val FENCE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`[^`\\n]*`")
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val TAG = Regex("<[^<>\\n]*>")
    private val QUOTES = Regex("「[^」\\n]*」|『[^』\\n]*』|“[^”\\n]*”|\"[^\"\\n]*\"|《[^》\\n]*》")
    /** 只配最里层的一对括号,反复去掉直到没有;半角、全角括号可以混着配。 */
    private val PARENS = Regex("[(（][^()（）\\n]*[)）]")
}
