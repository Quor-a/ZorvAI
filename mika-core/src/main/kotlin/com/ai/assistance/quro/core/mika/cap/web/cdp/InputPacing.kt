package com.ai.assistance.quro.core.mika.cap.web.cdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import com.ai.assistance.quro.core.mika.uistate.Pace
import kotlin.random.Random

/**
 * DevTools 鼠标键盘的节奏:鼠标沿一小段弧线移过去再按、按住一会儿再抬、键与键之间有长短不一的间隔、滚轮一格一格地转。
 * 所有时长与幅度都有上下限([Limits]),一次动作的总时长因此有界。[Instant] 不加任何停顿和中间点
 * (事件序列与不加节奏时一样,测试和不需要节奏的调用方用它)。
 */
class InputPacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * 上下限。时长单位毫秒,距离单位 CSS 像素。
     * [approachPx]:不知道指针在哪时,从目标多远处移过来;[bowFraction] / [maxBowPx]:轨迹偏离直线的最大幅度(相对距离 / 绝对);
     * [maxKeyedChars]:超过这么长的文字整段交给输入框(像粘贴),不再逐键敲。
     */
    data class Limits(
        val moveSteps: IntRange = 4..9,
        val moveStepMs: LongRange = 8L..22L,
        val approachPx: IntRange = 60..180,
        val bowFraction: Double = 0.12,
        val maxBowPx: Double = 40.0,
        val dwellMs: LongRange = 40L..120L,
        val pressMs: LongRange = 50L..120L,
        val keyHoldMs: LongRange = 25L..70L,
        val keyGapMs: LongRange = 35L..120L,
        val modifierGapMs: LongRange = 15L..45L,
        val wheelNotchPx: Double = 53.0,
        val maxNotches: Int = 12,
        val wheelGapMs: LongRange = 25L..70L,
        val maxKeyedChars: Int = 30,
    )

    /** 有节奏(不是 [Instant])。 */
    val paced: Boolean get() = random != null

    fun dwell(): Long = pick(limits.dwellMs)
    fun press(): Long = pick(limits.pressMs)
    fun keyHold(): Long = pick(limits.keyHoldMs)
    fun keyGap(): Long = pick(limits.keyGapMs)
    fun modifierGap(): Long = pick(limits.modifierGapMs)
    fun moveStep(): Long = pick(limits.moveStepMs)
    fun wheelGap(): Long = pick(limits.wheelGapMs)

    private fun pick(r: LongRange): Long = random?.let { Pace.pick(it, r) } ?: 0L

    /** 不知道指针在哪时的起点:目标周围 [Limits.approachPx] 远的某处(坐标不小于 0)。[Instant] 时就是目标本身。 */
    fun origin(x: Double, y: Double): Pair<Double, Double> {
        val r = random ?: return x to y
        val dist = Pace.pick(r, limits.approachPx).toDouble()
        val angle = r.nextDouble(0.0, 2 * PI)
        return (x + dist * cos(angle)).coerceAtLeast(0.0) to (y + dist * sin(angle)).coerceAtLeast(0.0)
    }

    /**
     * 从 [from] 到 [to] 的中间点(不含起点,最后一个正好是终点):二次贝塞尔弧线,两头慢中间快。
     * [Instant] 或距离不到 3 像素时只有终点。
     */
    fun path(from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>> {
        val r = random ?: return listOf(to)
        val (x0, y0) = from
        val (x1, y1) = to
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist < 3.0) return listOf(to)
        val n = Pace.pick(r, limits.moveSteps)
        val c = Pace.control(from, to, Pace.bow(r, dist, limits.bowFraction, limits.maxBowPx))
        return (1..n).map { i -> if (i == n) to else Pace.bezier(from, c, to, Pace.ease(i.toDouble() / n)) }
    }

    /**
     * 把滚动距离 [distance](正为向下)分成一格一格的滚轮增量,合起来正好是 [distance]:每格 [Limits.wheelNotchPx],
     * 最后一格是余下的零头;格数超过 [Limits.maxNotches] 时改为 [Limits.maxNotches] 格均分(每格大一些,总时长因此有界)。
     * [Instant] 时一次滚完。0 为空表。
     */
    fun notches(distance: Double): List<Double> {
        if (distance == 0.0) return emptyList()
        if (random == null) return listOf(distance)
        val sign = if (distance < 0) -1.0 else 1.0
        val total = abs(distance)
        val count = ceil(total / limits.wheelNotchPx - 1e-9).toInt().coerceAtLeast(1)
        if (count > limits.maxNotches) return List(limits.maxNotches) { sign * total / limits.maxNotches }
        return List(count) { i -> sign * if (i < count - 1) limits.wheelNotchPx else total - limits.wheelNotchPx * (count - 1) }
    }

    companion object {
        /** 不加停顿、不加中间点。 */
        val Instant = InputPacing(null)

        /** 像人手的节奏;[random] 可换成固定种子(测试)。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = InputPacing(random, limits)
    }
}
