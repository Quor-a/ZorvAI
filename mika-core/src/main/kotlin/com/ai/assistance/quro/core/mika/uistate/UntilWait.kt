package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.tool.Until
import com.ai.assistance.quro.core.mika.tool.UntilOutcome
import com.ai.assistance.quro.core.mika.run.waitOrSteer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 两次读屏至少隔这么久:界面事件一阵一阵来,不必每条都读一遍。 */
private const val MIN_GAP_MS = 200L
/** 端口不报界面事件时,隔这么久重读一次;也是两次看用户有没有插话的最长间隔。 */
private const val POLL_MS = 500L
/** 等「界面稳定」时每次交给端口等的上限(端口按自己的安静时长判)。 */
private const val STABLE_SLICE_MS = 1_500L
/** 意外要连着读到几次才算数(页面换页时新旧窗口会短暂同时在)。 */
private const val CONFIRM_READS = 2

/**
 * 一批动作里一步做完后的等待:按 [Until] 逐次读屏(不登记编号)做字面比较,手机、网页同一套,只经 [ScreenPort] 与读屏结果。
 * 端口有界面事件时提前醒来([ScreenPort.awaitChange]),否则隔一会儿重读。看出意外(弹框、别的包盖上来、换了 App)、
 * 用户插话、到了时限就不再等,交回去由模型判断。基准是开始等时的那一屏:这一步本身引起的切换由执行入口另外核对。
 */
internal class UntilWait(
    private val screen: ScreenPort,
    private val read: suspend () -> ScreenSnapshot?,
    private val clock: () -> Long,
    private val interrupted: () -> Boolean,
) {
    suspend fun run(until: Until): UntilOutcome {
        val start = clock()
        val timeout = until.timeoutMs.coerceIn(0, Until.MAX_MS)
        // 时钟不走(测试、时钟出错)时也一定结束:每轮至少花 MIN_GAP_MS
        val maxRounds = timeout / MIN_GAP_MS + 2
        var base: ScreenSnapshot? = null
        var stableNow = false
        var suspect: String? = null
        var seen = 0
        fun done(met: Boolean, stopped: String?) = UntilOutcome(met, stopped, (clock() - start).coerceAtLeast(0))
        repeat(maxRounds.toInt()) {
            val snap = read()
            if (snap != null) {
                val first = base ?: snap.also { base = it }
                // 有意外时这一轮不算成立:弹框后面露出的字不代表能接着做
                val odd = unexpected(first, snap, until)
                if (odd == null && (!until.stable || stableNow) && holds(until, snap)) return done(true, null)
                if (odd != null && odd == suspect) seen++ else { suspect = odd; seen = if (odd == null) 0 else 1 }
                // 页面弹框挂着时页面停着,不必再读一次确认
                if (suspect != null && (seen >= CONFIRM_READS || snap.web?.dialog != null)) return done(false, suspect)
            }
            if (interrupted()) return done(false, UntilOutcome.NEW_MESSAGE)
            val left = timeout - (clock() - start)
            if (left <= 0) return done(false, UntilOutcome.TIMEOUT)
            // 每段等待都在用户插话时立刻结束(见 waitOrSteer),不等到下一轮才看
            if (until.stable) {
                stableNow = waitOrSteer { waitStable(minOf(left, STABLE_SLICE_MS)) } ?: return done(false, UntilOutcome.NEW_MESSAGE)
            } else if (waitOrSteer { awaitChange(minOf(left, POLL_MS)) } ?: return done(false, UntilOutcome.NEW_MESSAGE)) {
                delay(MIN_GAP_MS)
            }
        }
        return done(false, UntilOutcome.TIMEOUT)
    }

    private suspend fun waitStable(ms: Long): Boolean =
        try { screen.waitStable(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(ms); false }

    private suspend fun awaitChange(ms: Long): Boolean =
        try { screen.awaitChange(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(ms); false }

    companion object {
        /** 条件此刻是否成立(只看给了的几项);和动作结果里 expect 的核对一样按元素名字比,不分大小写。 */
        fun holds(until: Until, s: ScreenSnapshot): Boolean {
            val names = s.elements.map { it.name }
            until.appears?.trim()?.let { want -> if (names.none { it.contains(want, ignoreCase = true) }) return false }
            // 什么都没读到(受保护、读不到)不算「已经没了」
            until.gone?.trim()?.let { gone -> if (names.isEmpty() || names.any { it.contains(gone, ignoreCase = true) }) return false }
            until.place?.trim()?.let { p -> if (!inPlace(s, p)) return false }
            return true
        }

        private fun inPlace(s: ScreenSnapshot, p: String) =
            s.packageName?.contains(p, ignoreCase = true) == true || s.web?.url?.contains(p, ignoreCase = true) == true

        /**
         * 和开始等时那一屏 [base] 比,此刻 [now] 有没有明显的意外;没有为 null。
         * 页面弹框挂着、同一 App 多出一个应用窗口为 [UntilOutcome.POPUP];多出别的包的应用或系统窗口为 [UntilOutcome.SYSTEM_DIALOG];
         * 前台换成别的 App(且不是 [until] 要等的那个)为 [UntilOutcome.APP_SWITCHED]。网页换站点是正常的跳转,不算换了 App。
         */
        fun unexpected(base: ScreenSnapshot, now: ScreenSnapshot, until: Until): String? {
            if (now.web?.dialog != null && base.web?.dialog == null) return UntilOutcome.POPUP
            val top = now.packageName
            val foreign = { s: ScreenSnapshot ->
                s.windows.filter { it.type in OVERLAY_TYPES && it.packageName != null && it.packageName != s.packageName }.map { it.id }.toSet()
            }
            if ((foreign(now) - foreign(base)).isNotEmpty()) return UntilOutcome.SYSTEM_DIALOG
            if (now.web == null && top != null && top != base.packageName && until.place?.let { inPlace(now, it.trim()) } != true) return UntilOutcome.APP_SWITCHED
            val own = { s: ScreenSnapshot -> s.windows.count { it.type == APPLICATION && it.packageName == top } }
            if (top == base.packageName && own(now) > own(base)) return UntilOutcome.POPUP
            return null
        }

        private const val APPLICATION = "application"
        private val OVERLAY_TYPES = setOf(APPLICATION, "system")
    }
}
