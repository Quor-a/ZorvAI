package com.ai.assistance.quro.core.mika.guard

/**
 * 操作守卫的判定规则。每条规则看的都是同一种事实:做了什么动作、作用在哪个目标(两者合成动作签名 [actionSignature]),
 * 动作的实际效果(effect),以及之后界面到了哪个状态;[OperationGuards] 按这些事实记计数,这里只把计数换成该给的级别。纯函数。
 *
 * 走进死胡同时先提醒(nudge):说清卡在哪,并给三条出路(换一条明显不同的路、问用户、把已有的交出来并说明缺什么)。
 * 每条阶梯的最后一级才叫停,它只是最后的安全网,门槛远在提醒之后;慢慢兜圈子的几条按本次任务的步数上限定(见 [lateStop]):
 *
 * | 信号 | 提醒 | 叫停(安全网) |
 * |---|---|---|
 * | 同一动作 + 同一目标重复(最近 [LOOP_WINDOW] 次里) | 3、6 | 12 |
 * | 同上,诊断类看界面的工具(按工具计) | 6、9 | 15 |
 * | 动作没有效果(effect 为 no_effect,或动过之后界面不变),连续 | 3、6、9 | 12 |
 * | 页面没变时又读了一遍、没读出新内容 | 5、8 | 12 |
 * | 连续失败(第 4 次起附出路) | 2、4 | 8 |
 * | 不可恢复的失败 | 1 | 3 |
 * | 没到过新状态时第 N 次回到同一状态 | 3、6 | 步数上限的 1/4(至少 10) |
 * | 在两三个状态之间兜圈(整圈数) | 2、4 | 步数上限的 1/5(至少 6) |
 * | 连续 N 个动作没到过任何新状态 | 8、16 | 步数上限的 1/2(至少 24) |
 *
 * 不知道步数上限时按 [DEFAULT_BUDGET] 算。
 */
internal object GuardRules {
    /** 计数到了阶梯的哪一级:[threshold] 是这一级的门槛。 */
    data class Rung(val threshold: Int, val severity: Severity)

    /** 重复动作看最近多少次动作。 */
    const val LOOP_WINDOW = 16

    /** 不知道任务的步数上限时按这么多步算。 */
    const val DEFAULT_BUDGET = 60

    /** 重复动作的阶梯:提醒、再提醒、叫停。诊断类看界面本来就常重复,门槛更高。 */
    fun repeatLadder(diagnostic: Boolean): List<Int> = if (diagnostic) listOf(6, 9, 15) else listOf(3, 6, 12)

    /** 动作没有效果的阶梯。 */
    val NO_EFFECT_LADDER = listOf(3, 6, 9, 12)

    /** 页面没变时重读的阶梯。 */
    val REREAD_LADDER = listOf(5, 8, 12)

    /** 连续失败的阶梯;不可恢复的失败另按 [UNRECOVERABLE_LADDER]。 */
    val FAILURE_LADDER = listOf(2, 4, 8)
    val UNRECOVERABLE_LADDER = listOf(1, 3)

    /** 连续失败到这一次起,提醒里附上出路。 */
    const val FAILURE_WAYS_OUT = 4

    /** 兜圈最多看几个状态一圈。 */
    const val MAX_CYCLE = 3

    /** 回到同一状态的阶梯([budget] 是任务的步数上限)。 */
    fun sameStateLadder(budget: Int?) = listOf(3, 6, lateStop(budget, 4, 10))

    /** 兜圈的阶梯(整圈数)。 */
    fun cycleLadder(budget: Int?) = listOf(2, 4, lateStop(budget, 5, 6))

    /** 连续没到过新状态的动作数的阶梯。 */
    fun noNewStateLadder(budget: Int?) = listOf(8, 16, lateStop(budget, 2, 24))

    /** 安全网的门槛:步数上限的 1/[divisor],至少 [floor](要远在提醒之后)。 */
    fun lateStop(budget: Int?, divisor: Int, floor: Int): Int = maxOf(floor, (budget ?: DEFAULT_BUDGET) / divisor)

    /**
     * 计数 [count] 在阶梯 [ladder](升序,最后一级叫停)上到了哪一级。还没到第一级,或这一级([warnedAt] 是已提醒到的门槛)已提醒过为 null;
     * 到最高一级且 [canStop] 时叫停,否则提醒。
     */
    fun climb(count: Int, ladder: List<Int>, warnedAt: Int, canStop: Boolean): Rung? {
        val threshold = ladder.lastOrNull { count >= it } ?: return null
        if (threshold <= warnedAt) return null
        return Rung(threshold, if (threshold == ladder.last() && canStop) Severity.Stop else Severity.Warn)
    }

    /** 末尾在 [period] 个不同状态之间兜圈:[length] 是合这一圈的末尾长度,[rounds] 是走了几整圈。 */
    data class Cycle(val period: Int, val length: Int) {
        val rounds: Int get() = length / period
    }

    /**
     * 先后到过的各状态(相邻的不同)末尾是不是在 2–[MAX_CYCLE] 个状态之间兜圈:A B A B 是 2 个一圈的 2 圈,
     * A B C A B C 是 3 个一圈的 2 圈。没有至少重复一步的为 null;几种圈长都合时取走得最长的。
     */
    fun cycle(trail: List<Long>): Cycle? {
        var best: Cycle? = null
        for (p in 2..MAX_CYCLE) {
            if (trail.size < p + 1) continue
            if (trail.takeLast(p).toSet().size != p) continue
            var len = p
            var i = trail.size - 1
            while (i - p >= 0 && trail[i] == trail[i - p]) { len++; i-- }
            if (len > p && (best == null || len > best.length)) best = Cycle(p, len)
        }
        return best
    }

    /** 连续失败 [consecutive] 次、其中不可恢复 [unrecoverable] 次时该给的级别;不到为 null。 */
    fun failures(consecutive: Int, unrecoverable: Int): Severity? = when {
        consecutive >= FAILURE_LADDER.last() || unrecoverable >= UNRECOVERABLE_LADDER.last() -> Severity.Stop
        consecutive >= FAILURE_LADDER.first() || unrecoverable >= UNRECOVERABLE_LADDER.first() -> Severity.Warn
        else -> null
    }
}
