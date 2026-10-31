package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.uistate.Pace
import com.ai.assistance.quro.core.mika.uistate.TextField
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 往输入框里打字的节奏(纯逻辑,不碰输入框):人打字是一个字一个字出来的,中日韩文字经输入法一个词一个词地上屏,
 * 字与字之间快慢不一,标点、空格之后偶尔停一下。[plan] 把一段字排成一串越来越长的前缀和每一段之前等多久;
 * 最后一段总是全文。
 *
 * - 拉丁字母、数字、标点每个字等 [Limits.latinMs] 里随机取的一段;中日韩文字每个字等 [Limits.cjkMs],
 *   连着的几个([Limits.cjkGroup])一起上屏;
 * - 每一段之后有机会([Limits.pauseChance],标点、空格之后加倍)多停 [Limits.pauseMs];
 * - 字多时几个字并成一段,至多 [Limits.maxSteps] 段;总时长超过 [Limits.maxTotalMs] 时每段按比例缩短,长消息也不会打上几分钟。
 * - 一个字不拆开:代理对、组合附加符号、变体选择符、零宽连接的表情序列都和前面的字算一个。
 *
 * [Instant] 一次写入全文、不等待(「快」、测试、不需要节奏的调用方用它)。
 */
class TypingPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** 时长单位毫秒。 */
    data class Limits(
        val latinMs: LongRange = 60L..170L,
        val cjkMs: LongRange = 160L..380L,
        val cjkGroup: IntRange = 1..3,
        val pauseChance: Double = 0.05,
        val pauseMs: LongRange = 300L..900L,
        val maxSteps: Int = 40,
        val maxTotalMs: Long = 6_000L,
    ) {
        companion object {
            /** 限制浏览快慢的 App(和「像人」时所有 App):打得更慢、停得更多,长消息至多 12 秒。 */
            val CAREFUL = Limits(
                latinMs = 90L..230L, cjkMs = 220L..520L, pauseChance = 0.1, pauseMs = 500L..1_400L, maxSteps = 60, maxTotalMs = 12_000L,
            )
        }
    }

    /** 一段:等 [delayMs] 之后,输入框里是全文的前 [end] 个 UTF-16 单位。 */
    data class Step(val end: Int, val delayMs: Long)

    /** 有节奏(不是 [Instant])。 */
    val paced: Boolean get() = random != null

    /** 把 [text] 排成一段一段;[Instant]、空串时只有一段(全文、不等)。 */
    fun plan(text: String): List<Step> {
        val r = random ?: return listOf(Step(text.length, 0))
        val units = units(text)
        if (units.size <= 1) return listOf(Step(text.length, Pace.pick(r, limits.latinMs)))
        val per = ceil(units.size.toDouble() / limits.maxSteps).toInt().coerceAtLeast(1)
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < units.size) {
            // 中日韩文字按输入法一个词一个词上屏(连着的几个,不跨到别的文字);字多时至少 per 个字一段
            val group = if (isCjk(text, units[i])) {
                val want = Pace.pick(r, limits.cjkGroup)
                var k = 1
                while (k < want && i + k < units.size && isCjk(text, units[i + k])) k++
                k
            } else 1
            val upto = minOf(i + maxOf(group, per), units.size)
            var wait = 0L
            for (j in i until upto) wait += Pace.pick(r, if (isCjk(text, units[j])) limits.cjkMs else limits.latinMs)
            val end = if (upto == units.size) text.length else units[upto]
            val last = text.codePointBefore(end)
            val boundary = Character.isWhitespace(last) || isPunctuation(last)
            if (r.nextDouble() < limits.pauseChance * (if (boundary) 2 else 1)) wait += Pace.pick(r, limits.pauseMs)
            steps += Step(end, wait)
            i = upto
        }
        val total = steps.sumOf { it.delayMs }
        if (total <= limits.maxTotalMs) return steps
        val scale = limits.maxTotalMs.toDouble() / total
        return steps.map { it.copy(delayMs = (it.delayMs * scale).roundToLong()) }
    }

    /**
     * 对这个输入框用的节奏:密码框([secret])不分段(读不回原文,核对不了半截字);要粘贴才收得到输入的网页框([web])
     * 不分段(写进去的字 App 收不到)。这两种照旧一次写入([Instant]),其余照这个节奏。
     */
    fun forField(web: Boolean, secret: Boolean): TypingPacing = if (web || secret) Instant else this

    /**
     * 按 [plan] 一段一段把 [text] 的前缀写进 [field],全文那一段留给调用方(照旧整段写入并读回核对),等完最后一段的停顿再返回。
     * 写第一段后读回核对:框不接受半截字(读回对不上、读不出)或写不进时立刻停下,返回 false,调用方照旧一次写入全文。
     * 写完了所有前缀返回 true;不分段([Instant]、只有一个字)时什么也不写,返回 false。
     */
    suspend fun leadIn(field: TextField, text: String, sleep: suspend (Long) -> Unit = { delay(it) }): Boolean {
        val steps = plan(text)
        if (steps.size < 2) return false
        for ((k, s) in steps.dropLast(1).withIndex()) {
            sleep(s.delayMs)
            val prefix = text.substring(0, s.end)
            if (!field.write(prefix)) return false
            if (k == 0) {
                val back = field.read() ?: return false
                if (back.filterNot { it.isWhitespace() } != prefix.filterNot { it.isWhitespace() }) return false
            }
        }
        sleep(steps.last().delayMs)
        return true
    }

    companion object {
        /** 一次写入全文。 */
        val Instant = TypingPacing(null)

        /** 像人打字的节奏;[random] 可换成固定种子(测试)。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = TypingPacing(random, limits)

        /** [text] 里每个字的起点(UTF-16 下标):代理对、附加符号、变体选择符、零宽连接的序列和前面的字算一个。 */
        internal fun units(text: String): List<Int> {
            val out = mutableListOf<Int>()
            var i = 0
            var joinNext = false
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val attach = out.isNotEmpty() && (joinNext || attaches(cp))
                if (!attach) out += i
                joinNext = cp == ZWJ
                i += Character.charCount(cp)
            }
            return out
        }

        private const val ZWJ = 0x200D

        private fun attaches(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> cp == ZWJ || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF
        }

        private fun isCjk(text: String, at: Int): Boolean = when (Character.UnicodeScript.of(text.codePointAt(at))) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> false
        }

        private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}
