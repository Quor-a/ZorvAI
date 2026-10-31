package com.ai.assistance.quro.core.mika.uistate

import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * 网页鼠标([com.ai.assistance.quro.core.mika.cap.web.cdp.InputPacing])和手机手势([com.ai.assistance.quro.core.mika.cap.phone.GesturePacing])
 * 共用的节奏原件:区间里有界地随机取值、略带弧度的二次贝塞尔、两头慢中间快的缓动。
 */
object Pace {
    /** [r] 里随机取一个(含两端);区间只有一个值时就是它。 */
    fun pick(random: Random, r: LongRange): Long = if (r.first >= r.last) r.first else random.nextLong(r.first, r.last + 1)

    /** [r] 里随机取一个整数(含两端)。 */
    fun pick(random: Random, r: IntRange): Int = if (r.first >= r.last) r.first else random.nextInt(r.first, r.last + 1)

    /** 弧线偏离直线的幅度:不超过距离的 [fraction],也不超过 [maxPx];向哪一侧随机(带符号)。 */
    fun bow(random: Random, dist: Double, fraction: Double, maxPx: Double): Double =
        min(dist * fraction, maxPx) * random.nextDouble(-1.0, 1.0)

    /** 二次贝塞尔的控制点:[from]、[to] 连线的中点沿法线偏 [bow]。两点重合时就是中点。 */
    fun control(from: Pair<Double, Double>, to: Pair<Double, Double>, bow: Double): Pair<Double, Double> {
        val (x0, y0) = from
        val (x1, y1) = to
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist == 0.0) return (x0 + x1) / 2 to (y0 + y1) / 2
        return (x0 + x1) / 2 - (y1 - y0) / dist * bow to (y0 + y1) / 2 + (x1 - x0) / dist * bow
    }

    /** 二次贝塞尔在 [t](0..1)处的点。 */
    fun bezier(from: Pair<Double, Double>, control: Pair<Double, Double>, to: Pair<Double, Double>, t: Double): Pair<Double, Double> {
        val u = 1 - t
        return (u * u * from.first + 2 * u * t * control.first + t * t * to.first) to
            (u * u * from.second + 2 * u * t * control.second + t * t * to.second)
    }

    /** 两头慢中间快(smoothstep):0→0,1→1,两端速度为 0。 */
    fun ease(t: Double) = t * t * (3 - 2 * t)
}
