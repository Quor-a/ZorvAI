package com.ai.assistance.quro.core.mika.uistate

import kotlin.math.abs
import kotlin.random.Random

/**
 * 停留时顺手的小动作(纯逻辑,不碰页面):人等着翻下一页时,常会把这一页往下滑一点、偶尔又往回拨一点。
 * 一次停留够长([Limits.minWaitMs])才做;做 1–3 下([Limits.count]),每下滚视口高度的一小截([Limits.stepFraction]),
 * 大多往下,偶尔([Limits.upChance])往回拨一点,但往回拨不超过之前往下滚的;几下加起来不超过 [Limits.maxTotal] 个视口,
 * 净移动不超过 [Limits.maxNet] 个视口。时刻落在停留里,前后各留出一段([Limits.leadMs]、[Limits.tailMs])。
 * 只滚页面,不移动指针、不悬停、不点。
 */
class IdleScroll(private val random: Random, val limits: Limits = Limits()) {
    /** 时长单位毫秒,幅度单位视口高度。 */
    data class Limits(
        val minWaitMs: Long = 3_000L,
        val count: IntRange = 1..3,
        val stepFraction: ClosedFloatingPointRange<Double> = 0.06..0.16,
        val upChance: Double = 0.25,
        val maxTotal: Double = 0.45,
        val maxNet: Double = 0.3,
        val leadMs: Long = 800L,
        val tailMs: Long = 1_200L,
        /** 两下之间至少隔这么久。 */
        val gapMs: Long = 700L,
    )

    /** 一下:停留开始后 [atMs] 时滚 [fraction] 个视口(正为往下)。 */
    data class Step(val atMs: Long, val fraction: Double)

    /** 一次 [waitMs] 的停留里做哪几下;太短为空。 */
    fun plan(waitMs: Long): List<Step> {
        val l = limits
        if (waitMs < l.minWaitMs) return emptyList()
        val room = waitMs - l.leadMs - l.tailMs
        if (room < 0) return emptyList()
        // 停留里放得下几下(两下之间至少隔 gapMs)
        val fits = (room / l.gapMs + 1).toInt()
        val n = random.nextInt(l.count.first, l.count.last + 1).coerceAtMost(fits).coerceAtLeast(1)
        var total = 0.0
        var net = 0.0
        val fractions = mutableListOf<Double>()
        repeat(n) { i ->
            val size = random.nextDouble(l.stepFraction.start, l.stepFraction.endInclusive)
            val up = i > 0 && net > 0 && random.nextDouble() < l.upChance
            val f = if (up) -minOf(size, net) else minOf(size, l.maxNet - net)
            if (abs(f) < MIN_STEP || total + abs(f) > l.maxTotal) return@repeat
            fractions += f
            total += abs(f)
            net += f
        }
        // 时刻:在可用的那段里均匀撒开,再排好序,两下之间不近于 gapMs
        val times = MutableList(fractions.size) { l.leadMs + random.nextLong(0, room + 1) }.sorted().toMutableList()
        for (i in 1 until times.size) times[i] = maxOf(times[i], times[i - 1] + l.gapMs)
        return fractions.indices.mapNotNull { i -> times[i].takeIf { it <= waitMs - l.tailMs }?.let { Step(it, fractions[i]) } }
    }

    private companion object {
        /** 小于这么点的一下不做(滚了也看不出)。 */
        const val MIN_STEP = 0.02
    }
}
