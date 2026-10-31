package com.ai.assistance.quro.core.mika.uistate

/**
 * 动作之后界面上新出现的「到上限了」的说法:今天的次数用完了、操作太频繁、明天再来一类。
 * 只认动作之后才出现的短文字(提示条、弹窗、页面弹框的话):动作前就在屏上的(说明文字、帮助页)不算,
 * 长段落不算。不按网站、App 认,只按说法认。
 */
object LimitNotice {
    /** 比对时去掉空白、不分大小写。 */
    val WORDS = listOf(
        "次数已用完", "次数用完", "机会已用完", "额度已用完", "已达上限", "已达到上限", "达到上限", "超过上限", "超出上限",
        "明天再来", "明日再来", "明天再试", "明日再试", "操作过于频繁", "操作太频繁", "操作频繁", "请求过于频繁", "访问过于频繁",
        "limitreached", "reachedthelimit", "reachedyourlimit", "reachedthedailylimit", "reachedyourdailylimit", "dailylimit",
        "tryagaintomorrow", "comebacktomorrow", "toomanyrequests", "doingthattoooften",
    )

    /** 「已达到沟通上限」「超过每日投递上限」这样中间夹着几个字的说法。 */
    private val REACHED = Regex("(已达|达到|超过|超出)[^，,。.!！?？]{0,6}上限")

    /** 算「短文字」的上限(去掉空白后的字数):提示条、弹窗里的一两句话。 */
    private const val MAX_CHARS = 120

    /** [before] 是动作前的一屏(读不到为 null,这时屏上的都算新出现的);返回新出现的那句话,没有为 null。 */
    fun find(before: ScreenSnapshot?, after: ScreenSnapshot?): String? {
        after ?: return null
        val earlier = before?.let(::texts).orEmpty().map(::compact).toSet()
        return texts(after).firstOrNull { t ->
            val c = compact(t)
            c.length <= MAX_CHARS && c !in earlier && (WORDS.any { it in c } || REACHED.containsMatchIn(c))
        }?.trim()
    }

    private fun texts(s: ScreenSnapshot): List<String> =
        s.elements.filter { !it.editable && !it.hint && it.name.isNotBlank() }.map { it.name } + listOfNotNull(s.web?.dialog?.message)

    private fun compact(t: String) = t.filterNot(Char::isWhitespace).lowercase()
}
