package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.net.UrlHost
import com.ai.assistance.quro.core.mika.run.waitOrSteer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.ai.assistance.quro.core.mika.uistate.DwellInterrupted
import com.ai.assistance.quro.core.mika.uistate.IdleScroll
import com.ai.assistance.quro.core.mika.uistate.PageLimitReached
import com.ai.assistance.quro.core.mika.uistate.PlacePace

/**
 * 一个任务里按 [PagePacing] 停留:看着页面的地址([seen])认出来了新的一页,往下滚时页面自己又拉来一批内容([loadedMore])
 * 也算一页;要换页的动作之前 [before] 补足这一页还没停够的时间,停了多久交给 [onWait](记成这次调用的等待)。
 * [pace] 按主机名给出站点知识对这个网站浏览快慢的说法。
 * 给了 [idle] 且动作不按屏幕坐标做(交了端口)时,停留里按它的安排把页面轻轻滚几下([WebPort.idleScroll]),滚了几下交给 [onIdleScroll]。
 * 同一个网站本任务里来的页到了上限,[before] 不再等,抛 [PageLimitReached]:调用方不做这个动作;
 * 上限只管浏览:正在做的对外动作(发送、提交,[outwardStart] 到 [outwardEnd] 之间)不拦,
 * 本任务在这个网站上做成过对外动作之后不再设上限。
 * 停留中用户插了话([waitOrSteer]),[before] 不再等,抛 [DwellInterrupted]:调用方同样不做这个动作;
 * 这一页没停够的部分不算停过,下次要换页之前照样补足。
 */
internal class PageDwell(
    private val pacing: PagePacing,
    private val pace: (String) -> PlacePace = { PlacePace.NORMAL },
    private val idle: IdleScroll? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** 停下之后调用(参数是停了多少毫秒)。 */
    var onWait: (Long) -> Unit = {}

    /** 停留里轻轻滚了页面之后调用(参数是滚了几下)。 */
    var onIdleScroll: (Int) -> Unit = {}

    /** 停留里轻轻滚过页面、接着要做动作之前调用(重看动作前的样子,免得把滚动算成动作的效果)。 */
    var afterIdle: suspend () -> Unit = {}

    private var lastUrl: String? = null

    private val paces = HashMap<String, PlacePace>()

    val paced: Boolean get() = pacing.paced

    /** 看到了页面此刻的地址(读屏、打开网页的结果):和上次看到的不同(不计 # 后面)就是来了新的一页。 */
    @Synchronized
    fun seen(url: String?) {
        val u = url?.substringBefore('#')?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (u == lastUrl) return
        lastUrl = u
        pacing.noteLoad(u, clock(), paceOf(u))
    }

    /** 地址没变,页面自己又拉来了一批内容(往下滚加载更多)。 */
    @Synchronized
    fun loadedMore() {
        lastUrl?.let { pacing.noteLoad(it, clock(), paceOf(it)) }
    }

    /** 用户答过话:各网站的页数重新算。 */
    fun userAnswered() = pacing.userAnswered()

    /** 正在做的对外动作所在的网站(可注册域名);没有为 null。 */
    @Volatile private var outwardNow: String? = null

    /** 在 [site](网站主机名)上要做一次对外动作:这一次不受页数上限拦。 */
    fun outwardStart(site: String) { outwardNow = siteKey(site) }

    /** 对外动作做完;[done](做成了)时这个网站不再设页数上限。 */
    fun outwardEnd(site: String, done: Boolean) {
        outwardNow = null
        if (done) pacing.outwardDone(site)
    }

    /**
     * 要换页之前:这一页没停够就补足;[target] 是要打开的网址(打开网页时),不给按此刻这一页的网站。返回停了多久。
     * [port] 给了(动作不按屏幕坐标做)时,停留里可以轻轻滚几下页面。[gate] 时先看页数上限,到了抛 [PageLimitReached]。
     */
    suspend fun before(target: String? = null, port: WebPort? = null, gate: Boolean = true): Long {
        val site = target ?: synchronized(this) { lastUrl } ?: return 0
        val p = paceOf(site)
        if (gate && siteKey(site) != outwardNow && pacing.capReached(site, p)) throw PageLimitReached(hostOf(site), pacing.pagesOn(site), pacing.capFor(p))
        val wait = pacing.waitBefore(site, clock())
        if (wait <= 0) return 0
        if (port != null && idle != null) idleWait(port, idle, wait) else pause(wait)
        pacing.served(site)
        onWait(wait)
        return wait
    }

    /** 停 [ms] 毫秒;用户插了话就不再停,抛 [DwellInterrupted]。 */
    private suspend fun pause(ms: Long) {
        waitOrSteer { sleep(ms) } ?: throw DwellInterrupted()
    }

    /**
     * 停 [wait] 毫秒,其间按 [plan] 的安排轻轻滚几下;端口说这会儿不便滚(弹框、输入框拿着焦点等)就不再滚,照样停够。
     * 用户插话截断了停留时,滚过的几下照样报出去、模型手里的那一屏照样标成过时。
     */
    private suspend fun idleWait(port: WebPort, plan: IdleScroll, wait: Long) {
        val start = clock()
        var done = 0
        suspend fun scrolled() { if (done > 0) { onIdleScroll(done); afterIdle() } }
        try {
            for (step in plan.plan(wait)) {
                val left = step.atMs - (clock() - start)
                if (left > 0) pause(left)
                val ok = try { port.idleScroll(step.fraction) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                if (!ok) break
                done++
            }
            val rest = wait - (clock() - start)
            if (rest > 0) pause(rest)
        } catch (e: DwellInterrupted) {
            scrolled()
            throw e
        }
        scrolled()
    }

    private fun paceOf(site: String): PlacePace {
        val host = hostOf(site)
        return synchronized(paces) { paces.getOrPut(host) { pace(host) } }
    }

    private fun siteKey(site: String): String { val h = hostOf(site); return UrlHost.registrable(h) ?: h }

    private fun hostOf(site: String): String = UrlHost.of(if ("://" in site) site else "https://$site") ?: site

    /** 往下滚之前记下页面已收到的响应;滚完用 [afterScroll] 比,有新的成批数据就是加载了更多。 */
    suspend fun scrollMark(port: WebPort): Set<String>? = if (!paced) null else try {
        port.captures(null, null, false).mapTo(HashSet()) { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun afterScroll(port: WebPort, mark: Set<String>?) {
        mark ?: return
        val fresh = try { port.captures(null, null, false) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        if (fresh.any { it.id !in mark && batch(it) }) loadedMore()
    }

    /** 像一批列表数据的响应:成功收完、JSON 或文本、不太小(统计、心跳一类的小响应不算)。 */
    private fun batch(c: CapturedResponse): Boolean {
        if (c.state != CapturedResponse.State.DONE || c.status !in 200..299) return false
        val mime = c.mime?.lowercase().orEmpty()
        if ("json" !in mime && !mime.startsWith("text/")) return false
        return (c.size ?: 0) >= MORE_MIN_BYTES
    }

    private companion object {
        /** 算作「又来了一批内容」的响应至少这么大。 */
        const val MORE_MIN_BYTES = 2_048L
    }
}
