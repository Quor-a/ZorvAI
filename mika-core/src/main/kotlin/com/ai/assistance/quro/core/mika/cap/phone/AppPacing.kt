package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.uistate.PlacePace
import com.ai.assistance.quro.core.mika.uistate.Pace
import kotlin.random.Random
import com.ai.assistance.quro.core.mika.uistate.IdleScroll

/**
 * 在 App 里一步一步操作的节奏(纯逻辑,不碰时钟和界面):人点完一下总要看一眼才点下一下,到了新的一屏还要先看一会儿。
 * 每做完一个改动屏幕的动作([noteAction])按它有没有换到新的一屏定下离下一个动作至少隔多久;下一个动作之前问 [waitBefore]:
 * 还没隔够就补足余下的时间。
 *
 * - 同一屏上接着做(打字、拨开关、只变了一两个元素):停 [Limits.sameScreenMs] 里随机取的一段;
 * - 换到了新的一屏(界面大变、换了 App):停 [Limits.newScreenMs] 里的一段,另按这一屏上的字数加一段读的时间
 *   ([Limits.readMsPerChar],至多 [Limits.maxReadMs]);
 * - 偶尔([Limits.pauseChance])多停一会儿([Limits.pauseMs]);一次停留不超过 [Limits.maxDwellMs];
 * - 停留从上一个动作做完算起,模型想、读屏花掉的时间都算在内,只补不够的部分。
 * - App 知识说这个 App 限制浏览快慢([PlacePace.CAREFUL])时按 [careful] 的范围停(更久),
 *   一个任务里在这个 App 里换到新的一屏的次数有上限([screenCap]),到了之后 [capReached] 为真,调用方不再换屏;
 *   用户答过话([userAnswered])后重新计数;本任务在这个 App 里做成过对外动作([outwardDone],发送、提交一类)之后不再设上限。
 *   和网页一页一页看的节奏([com.ai.assistance.quro.core.mika.cap.web.PagePacing])是同一套说法。
 *
 * [Off] 从不等待,也不设上限(「快」、测试、不需要节奏的调用方用它)。
 */
class AppPacing(
    private val random: Random?,
    val limits: Limits = Limits(),
    val careful: Limits = Limits.CAREFUL,
    /** 限制浏览快慢的 App 一个任务里(用户答话之间)最多换到多少次新的一屏。 */
    val screenCap: Int = CAREFUL_SCREEN_CAP,
) {
    /** 时长单位毫秒。 */
    data class Limits(
        /** 同一屏上接着做时的停留范围。 */
        val sameScreenMs: LongRange = 500L..1_200L,
        /** 换到新的一屏之后的停留范围(不含读的时间)。 */
        val newScreenMs: LongRange = 900L..2_000L,
        /** 新的一屏上每个字加多少读的时间;0 为不加。 */
        val readMsPerChar: Long = 0L,
        /** 读的时间的上限。 */
        val maxReadMs: Long = 0L,
        /** 多停一会儿的机会。 */
        val pauseChance: Double = 0.05,
        /** 多停一会儿时另加的范围。 */
        val pauseMs: LongRange = 600L..1_500L,
        /** 一次停留的上限。 */
        val maxDwellMs: Long = 3_500L,
        /** 新的一屏上停得够久时,停留里可以轻轻滚几下(见 [com.ai.assistance.quro.core.mika.uistate.IdleScroll])。 */
        val idleScroll: Boolean = false,
    ) {
        companion object {
            /** 限制浏览快慢的 App(和「像人」时所有 App):停得更久,按字数读,停留里轻轻滚几下。 */
            val CAREFUL = Limits(
                sameScreenMs = 900L..2_200L, newScreenMs = 2_500L..5_000L, readMsPerChar = 15L, maxReadMs = 8_000L,
                pauseChance = 0.1, pauseMs = 2_000L..5_000L, maxDwellMs = 15_000L, idleScroll = true,
            )
        }
    }

    private class Last(val app: String?, val atMs: Long, val newScreen: Boolean, val baseMs: Long, val limits: Limits)

    private var last: Last? = null

    /** 本任务里(上次用户答话以来)各个限制浏览快慢的 App 换到过几次新的一屏。 */
    private val screens = HashMap<String, Int>()

    /** 本任务里做过对外动作的 App:屏数上限对它们不再算;用户答话也不清。 */
    private val outward = HashSet<String>()

    /** 有节奏(不是 [Off])。 */
    val paced: Boolean get() = random != null

    /**
     * 在 [app](包名;不知道为 null)里做完了一个改动屏幕的动作:[newScreen] 是换到了新的一屏,
     * [counts] 时这一屏算进限制浏览快慢的 App 的屏数(往下滚读同一页余下的内容不算)。[pace] 是 App 知识对这个 App 浏览快慢的说法。
     */
    @Synchronized
    fun noteAction(app: String?, nowMs: Long, newScreen: Boolean, pace: PlacePace = PlacePace.NORMAL, counts: Boolean = newScreen) {
        val r = random ?: return
        val l = limitsFor(pace)
        var base = Pace.pick(r, if (newScreen) l.newScreenMs else l.sameScreenMs)
        if (r.nextDouble() < l.pauseChance) base += Pace.pick(r, l.pauseMs)
        last = Last(app, nowMs, newScreen, base.coerceAtMost(l.maxDwellMs), l)
        if (newScreen && counts && pace == PlacePace.CAREFUL && app != null) screens[app] = (screens[app] ?: 0) + 1
    }

    /**
     * 下一个动作之前还要等多久:上一个动作之后还没停够时是余下的时间,否则 0。
     * [textChars] 是此刻这一屏上的字数:上一个动作换到了新的一屏时按它加读的时间。[Off] 恒为 0。
     */
    @Synchronized
    fun waitBefore(nowMs: Long, textChars: Int = 0): Long {
        val l = last ?: return 0
        val read = if (l.newScreen) (textChars.coerceAtLeast(0) * l.limits.readMsPerChar).coerceAtMost(l.limits.maxReadMs) else 0L
        val dwell = (l.baseMs + read).coerceAtMost(l.limits.maxDwellMs)
        return (dwell - (nowMs - l.atMs)).coerceIn(0, l.limits.maxDwellMs)
    }

    /** 这次停留里可以轻轻滚几下:上一个动作换到了新的一屏,且按的范围允许。 */
    @Synchronized
    fun idleAllowed(): Boolean = last?.let { it.newScreen && it.limits.idleScroll } == true

    /** 本任务里(上次用户答话以来)[app] 换到过几次新的一屏。 */
    @Synchronized
    fun screensIn(app: String): Int = screens[app] ?: 0

    /** [app] 换到新的一屏的次数已到上限:再换屏之前要先停下(用户答应了再看)。普通 App、做过对外动作的 App、[Off] 恒为 false。 */
    @Synchronized
    fun capReached(app: String?, pace: PlacePace): Boolean =
        random != null && app != null && pace == PlacePace.CAREFUL && app !in outward && screensIn(app) >= screenCap

    /** 本任务在 [app] 里做了一次对外动作(发送、提交):这个 App 余下的时间里不再设屏数上限。 */
    @Synchronized
    fun outwardDone(app: String) {
        if (random != null) outward += app
    }

    /** 用户答过话(回答了追问、交回了接管):各 App 的屏数重新算。 */
    @Synchronized
    fun userAnswered() = screens.clear()

    private fun limitsFor(pace: PlacePace) = if (pace == PlacePace.CAREFUL) careful else limits

    companion object {
        /** 限制浏览快慢的 App 一个任务里最多换到多少次新的一屏(App 里一屏比网页的一页碎:点开、返回各算一次)。 */
        const val CAREFUL_SCREEN_CAP = 24

        /** 从不等待。 */
        val Off = AppPacing(null)

        /** 像人在 App 里一步一步操作的节奏;[random] 可换成固定种子(测试)。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            AppPacing(random, limits, careful)
    }
}
