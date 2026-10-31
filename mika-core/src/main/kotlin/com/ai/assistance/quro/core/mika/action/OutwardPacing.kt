package com.ai.assistance.quro.core.mika.action

import com.ai.assistance.quro.core.mika.net.UrlHost
import kotlin.random.Random

/**
 * 对外动作之间的间隔(纯逻辑,不碰时钟和界面):不可逆判定认出的发送、提交(联系别人、投申请)、删除、下单,
 * 在同一个网站或 App 里连着做时,两次之间隔一段随机的时间,像人一条条处理,不连着秒点。
 * 不管中间有没有换页:换页的停留另由网页的节奏管。手机和网页同一套,因为判定是同一个。
 *
 * - 一个网站(App)里的第一次不等;
 * - 之后每次和上一次至少隔 [Limits.firstGapMs] 里随机取的一段,这个窗口里做得越多,上下限各加一截([Limits.perActionMs]),
 *   都不超过 [Limits.maxGapMs];
 * - 间隔从上一次做完算起,中间读页面、想下一步花掉的时间都算在内,只补不够的部分;
 * - 网页按可注册域名认(www.example.com 和 m.example.com 是同一个),手机按包名;别的网站、App 各算各的。
 *
 * [Off] 从不等待(测试、不需要节奏的调用方用它)。
 */
class OutwardPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** 时长单位毫秒。 */
    data class Limits(
        /** 第二次和第一次之间的间隔范围。 */
        val firstGapMs: LongRange = 8_000L..20_000L,
        /** 之后每多做一次,上下限各加多少(下限、上限)。 */
        val perActionMs: Pair<Long, Long> = 500L to 1_000L,
        /** 间隔的上限。 */
        val maxGapMs: Long = 30_000L,
        /** 算「最近做过几次」的时间窗。 */
        val windowMs: Long = 30 * 60_000L,
    )

    private class Done(val site: String, val atMs: Long, val gapMs: Long)

    private val done = ArrayDeque<Done>()

    /** 有节奏(不是 [Off])。 */
    val paced: Boolean get() = random != null

    /** 在 [site] 上做下一次对外动作之前还要等多久:上一次之后还没隔够时是余下的时间,否则 0。 */
    @Synchronized
    fun waitBefore(site: String, nowMs: Long): Long {
        if (random == null) return 0
        prune(nowMs)
        val last = done.lastOrNull { it.site == site } ?: return 0
        return (last.gapMs - (nowMs - last.atMs)).coerceIn(0, limits.maxGapMs)
    }

    /** 在 [site] 上做了一次对外动作:按这个窗口里做过几次定下离下一次至少要隔多久。 */
    @Synchronized
    fun noteDone(site: String, nowMs: Long) {
        if (random == null) return
        prune(nowMs)
        val count = done.count { it.site == site } + 1
        done.addLast(Done(site, nowMs, gapAfter(count)))
    }

    /** 做过 [n] 次(n ≥ 1)之后到下一次的间隔。 */
    private fun gapAfter(n: Int): Long {
        val r = random ?: return 0
        val extra = (n - 1).toLong()
        val lo = (limits.firstGapMs.first + extra * limits.perActionMs.first).coerceAtMost(limits.maxGapMs)
        val hi = (limits.firstGapMs.last + extra * limits.perActionMs.second).coerceAtMost(limits.maxGapMs)
        return if (lo >= hi) hi else r.nextLong(lo, hi + 1)
    }

    private fun prune(nowMs: Long) {
        while (done.isNotEmpty() && nowMs - done.first().atMs > limits.windowMs) done.removeFirst()
    }

    companion object {
        /** 从不等待。 */
        val Off = OutwardPacing(null)

        /** 像人一条条处理的节奏;[random] 可换成固定种子(测试)。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = OutwardPacing(random, limits)

        /** 一屏属于哪个网站或 App:网页([web] 为 true)按可注册域名,手机按包名。 */
        fun siteOf(packageName: String?, web: Boolean): String? {
            val p = packageName?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            return if (web) UrlHost.registrable(p) ?: p else p
        }
    }
}
