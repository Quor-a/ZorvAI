package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.uistate.DwellInterrupted
import com.ai.assistance.quro.core.mika.uistate.IdleScroll
import com.ai.assistance.quro.core.mika.uistate.PageLimitReached
import com.ai.assistance.quro.core.mika.uistate.PlacePace
import com.ai.assistance.quro.core.mika.run.waitOrSteer
import com.ai.assistance.quro.core.mika.uistate.ScreenPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 一个任务里在 App 里按 [AppPacing] 停留:每个改动屏幕的动作做完记下([after]),下一个之前补足没停够的时间([before])。
 * [pace] 按包名给出 App 知识对这个 App 浏览快慢的说法。
 * 给了 [idle]、这次的范围允许、且调用方交了屏幕端口(接下来的动作不按屏上的位置做)时,停留里按它的安排轻轻滚几下([ScreenPort.idleScroll])。
 * 限制浏览快慢的 App 本任务里换屏到了上限,[before] 不再等,抛 [PageLimitReached](和网页同一个结果 page_limit);
 * 停留中用户插了话([waitOrSteer]),抛 [DwellInterrupted]:调用方不做这个动作,没停够的部分下次照样补足。
 */
internal class AppDwell(
    private val pacing: AppPacing,
    private val pace: (String) -> PlacePace = { PlacePace.NORMAL },
    private val idle: IdleScroll? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** 停了多久、停留里轻轻滚了几下。 */
    class Dwelt(val waitMs: Long, val idleScrolls: Int)

    /** 停留里轻轻滚了一下之后调用(模型手里那一屏的位置已不准)。 */
    var onIdleScroll: () -> Unit = {}

    private val paces = HashMap<String, PlacePace>()

    val paced: Boolean get() = pacing.paced

    /** 在 [app] 里做完了一个改动屏幕的动作:[newScreen] 换到了新的一屏,[counts] 时算进屏数(见 [AppPacing.noteAction])。 */
    fun after(app: String?, newScreen: Boolean, counts: Boolean = newScreen) {
        if (!paced) return
        pacing.noteAction(app, clock(), newScreen, app?.let(::paceOf) ?: PlacePace.NORMAL, counts)
    }

    /**
     * 在 [app] 里要做下一个改动屏幕的动作之前:没停够就补足。[textChars] 是此刻这一屏上的字数(新的一屏按它加读的时间)。
     * [gate] 时先看屏数上限,到了抛 [PageLimitReached]。[port] 给了时停留里可以轻轻滚几下。
     */
    suspend fun before(app: String?, textChars: Int, gate: Boolean, port: ScreenPort? = null): Dwelt {
        if (!paced) return Dwelt(0, 0)
        val p = app?.let(::paceOf) ?: PlacePace.NORMAL
        if (gate && pacing.capReached(app, p)) throw PageLimitReached(app!!, pacing.screensIn(app), pacing.screenCap)
        val wait = pacing.waitBefore(clock(), textChars)
        if (wait <= 0) return Dwelt(0, 0)
        val plan = idle?.takeIf { port != null && pacing.idleAllowed() }
        val scrolls = if (plan != null) idleWait(port!!, plan, wait) else 0.also { pause(wait) }
        return Dwelt(wait, scrolls)
    }

    /** 用户答过话:各 App 的屏数重新算。 */
    fun userAnswered() = pacing.userAnswered()

    /** 在 [app] 里的对外动作做完;[done](做成了)时这个 App 不再设屏数上限。 */
    fun outwardEnd(app: String, done: Boolean) {
        if (done) pacing.outwardDone(app)
    }

    /** 停 [ms] 毫秒;用户插了话就不再停,抛 [DwellInterrupted]。 */
    private suspend fun pause(ms: Long) {
        waitOrSteer { sleep(ms) } ?: throw DwellInterrupted()
    }

    /** 停 [wait] 毫秒,其间按 [plan] 轻轻滚几下;端口说这会儿不便滚(弹框、输入法、焦点)就不再滚,照样停够。返回滚了几下。 */
    private suspend fun idleWait(port: ScreenPort, plan: IdleScroll, wait: Long): Int {
        val start = clock()
        var done = 0
        for (step in plan.plan(wait)) {
            val left = step.atMs - (clock() - start)
            if (left > 0) pause(left)
            val ok = try { port.idleScroll(step.fraction) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) break
            done++
            onIdleScroll()
        }
        val rest = wait - (clock() - start)
        if (rest > 0) pause(rest)
        return done
    }

    private fun paceOf(app: String): PlacePace = synchronized(paces) { paces.getOrPut(app) { pace(app) } }
}
