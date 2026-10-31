package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.net.UrlHost
import kotlin.random.Random
import com.ai.assistance.quro.core.mika.uistate.PlacePace

/**
 * 网页上一页一页往下看的节奏(纯逻辑,不碰时钟和页面):人打开一页总要看一会儿才翻下一页、点开下一条。
 * 每来一页新内容([noteLoad]:打开了新地址,或往下滚时页面自己又拉来一批内容)按这个网站最近看过几页定下这一页要停留多久;
 * 之后要换页的动作(打开同站网址、点击、后退、往下滚、按键)之前先问 [waitBefore]:这一页还没停够就补足余下的时间。
 *
 * - 一个网站里的第一页不停(刚到这个网站,第一个动作照常);
 * - 从第二页起,停留时长在 [Limits.firstDwellMs] 里随机取,最近 [Limits.windowMs] 里看得越多,上下限各加一截([Limits.perPageMs]),
 *   都不超过 [Limits.maxDwellMs];
 * - 停留从那一页来的时刻算起,模型自己想、读页面花掉的时间都算在内,只补不够的部分;
 * - 网站按可注册域名认(www.example.com 和 m.example.com 是同一个);去另一个网站不等,那边从第一页算起。
 * - 站点知识说这个网站限制浏览快慢([PlacePace.CAREFUL])时,这个网站的页按 [careful] 的范围停留(更久)。
 * - 一个任务里同一个网站来的页数有上限([Limits.pageCap]),到了之后 [capReached] 为真,调用方不再换页;
 *   用户答过话([userAnswered])后重新计数。上限只管浏览:本任务在这个网站上做过对外动作([outwardDone],发送、提交一类)之后,
 *   这个网站在本任务余下的时间里不再设上限(成批联系、投递要能做到网站自己的上限),停留照旧。
 *
 * [Off] 从不等待,也不设页数上限(测试、不需要节奏的调用方用它)。
 */
class PagePacing(private val random: Random?, val limits: Limits = Limits(), val careful: Limits = Limits.CAREFUL) {
    /** 时长单位毫秒。 */
    data class Limits(
        /** 一个网站的第二页的停留范围。 */
        val firstDwellMs: LongRange = 4_000L..7_000L,
        /** 之后每多一页,上下限各加多少(下限、上限)。 */
        val perPageMs: Pair<Long, Long> = 600L to 1_000L,
        /** 停留的上限:一页再多也不超过它。 */
        val maxDwellMs: Long = 12_000L,
        /** 算「最近看过几页」的时间窗。 */
        val windowMs: Long = 10 * 60_000L,
        /** 一个任务里(用户答话之间)同一个网站最多来多少页。 */
        val pageCap: Int = 20,
    ) {
        companion object {
            /** 限制浏览快慢的网站:每页停得更久、页数上限更低。 */
            val CAREFUL = Limits(firstDwellMs = 10_000L..16_000L, perPageMs = 600L to 1_000L, maxDwellMs = 30_000L, pageCap = 12)
        }
    }

    private class Load(val site: String, val atMs: Long, var dwellMs: Long, val limits: Limits)

    private val loads = ArrayDeque<Load>()

    /** 本任务里(上次用户答话以来)各网站来过几页;不随时间窗清掉。 */
    private val pages = HashMap<String, Int>()

    /** 本任务里做过对外动作的网站:页数上限对它们不再算;用户答话也不清。 */
    private val outward = HashSet<String>()

    /** 有节奏(不是 [Off])。 */
    val paced: Boolean get() = random != null

    /**
     * 网站在 [site] 上来了一页新内容(主机名或网址都行);返回这一页定下的停留时长。
     * [pace] 是站点知识对这个网站浏览快慢的说法。
     */
    @Synchronized
    fun noteLoad(site: String, nowMs: Long, pace: PlacePace = PlacePace.NORMAL): Long {
        if (random == null) return 0
        val key = key(site) ?: return 0
        prune(nowMs)
        val l = limitsFor(pace)
        val seen = loads.count { it.site == key }
        val dwell = if (seen == 0) 0 else dwellFor(seen + 1, l)
        loads.addLast(Load(key, nowMs, dwell, l))
        pages[key] = (pages[key] ?: 0) + 1
        return dwell
    }

    /** 本任务里(上次用户答话以来)[site] 上来过几页。 */
    @Synchronized
    fun pagesOn(site: String): Int = key(site)?.let { pages[it] } ?: 0

    /** 按 [pace] 的页数上限。 */
    fun capFor(pace: PlacePace): Int = limitsFor(pace).pageCap

    /** [site] 上来的页已到上限:再换页之前要先停下(用户答应了再看)。做过对外动作的网站([outwardDone])、[Off] 恒为 false。 */
    @Synchronized
    fun capReached(site: String, pace: PlacePace = PlacePace.NORMAL): Boolean =
        random != null && key(site)?.let { it in outward } != true && pagesOn(site) >= capFor(pace)

    /** 本任务在 [site] 上做了一次对外动作(发送、提交):这个网站余下的时间里不再设页数上限。 */
    @Synchronized
    fun outwardDone(site: String) {
        key(site)?.let(outward::add)
    }

    /** 用户答过话(回答了追问、交回了接管):各网站的页数重新算。 */
    @Synchronized
    fun userAnswered() = pages.clear()

    /**
     * 在 [site] 上要做一个可能换页的动作之前还要等多久:最近一页来自这个网站且还没停够时是余下的时间,否则 0。
     * [Off] 恒为 0。
     */
    @Synchronized
    fun waitBefore(site: String?, nowMs: Long): Long {
        if (random == null) return 0
        val key = site?.let(::key) ?: return 0
        val last = loads.lastOrNull() ?: return 0
        if (last.site != key) return 0
        return (last.dwellMs - (nowMs - last.atMs)).coerceIn(0, last.limits.maxDwellMs)
    }

    /** 在 [site] 上已经为最近这一页停够了(调用方等完了):这一页之后的动作不再等。 */
    @Synchronized
    fun served(site: String) {
        val key = key(site) ?: return
        loads.lastOrNull()?.takeIf { it.site == key }?.dwellMs = 0
    }

    private fun limitsFor(pace: PlacePace) = if (pace == PlacePace.CAREFUL) careful else limits

    /** 第 [n] 页(n ≥ 2)按 [l] 的停留时长。 */
    private fun dwellFor(n: Int, l: Limits): Long {
        val r = random ?: return 0
        val extra = (n - 2).toLong()
        val lo = (l.firstDwellMs.first + extra * l.perPageMs.first).coerceAtMost(l.maxDwellMs)
        val hi = (l.firstDwellMs.last + extra * l.perPageMs.second).coerceAtMost(l.maxDwellMs)
        return if (lo >= hi) hi else r.nextLong(lo, hi + 1)
    }

    private fun prune(nowMs: Long) {
        while (loads.isNotEmpty() && nowMs - loads.first().atMs > limits.windowMs) loads.removeFirst()
    }

    private fun key(site: String): String? {
        val host = UrlHost.of(if ("://" in site) site else "https://$site") ?: return null
        return UrlHost.registrable(host) ?: host
    }

    companion object {
        /** 从不等待。 */
        val Off = PagePacing(null)

        /** 像人看网页的节奏;[random] 可换成固定种子(测试)。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            PagePacing(random, limits, careful)
    }
}
