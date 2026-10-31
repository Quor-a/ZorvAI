package com.ai.assistance.quro.core.mika.selfcheck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 一次自检此刻的样子:[results] 按 [SelfCheck] 的顺序,每项一条。 */
data class SelfCheckState(
    val phase: Phase = Phase.Idle,
    val results: List<CheckResult> = SelfCheck.entries.map { CheckResult(it) },
    val facts: DeviceFacts? = null,
    val elapsedMs: Long = 0,
) {
    enum class Phase { Idle, Running, Done, Cancelled }

    fun of(check: SelfCheck): CheckResult = results[check.ordinal]

    /** 跑完或取消了:可以出报告。 */
    val ended: Boolean get() = phase == Phase.Done || phase == Phase.Cancelled
}

/**
 * 按顺序跑完 [SelfCheck] 的每一项:先要过的没过就跳过并写明缺哪一项;每项有自己的时限,整次还有总时限 [budgetMs],
 * 时间用完了余下的都记为跳过。出错按失败记,不中断后面的项。不论跑完、出错还是被取消,最后都调 [SelfCheckPort.release]。
 * [state] 随时给出进度,界面照着画。一个实例只跑一次。
 */
class SelfCheckRunner(
    private val port: SelfCheckPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = TOTAL_BUDGET_MS,
) {
    private val _state = MutableStateFlow(SelfCheckState())
    val state: StateFlow<SelfCheckState> = _state.asStateFlow()

    suspend fun run(): SelfCheckState {
        val start = now()
        _state.value = SelfCheckState(SelfCheckState.Phase.Running)
        try {
            for (c in SelfCheck.entries) step(c, start)
            finish(SelfCheckState.Phase.Done, start)
        } catch (e: CancellationException) {
            finish(SelfCheckState.Phase.Cancelled, start)
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { port.release() } }
        }
        return _state.value
    }

    private suspend fun step(c: SelfCheck, start: Long) {
        val results = _state.value.results
        c.needs.firstOrNull { !results[it.ordinal].status.passed }?.let { blocker ->
            set(CheckResult(c, CheckStatus.Skipped, "needs ${blocker.key} (${results[blocker.ordinal].status.name.lowercase()})", blockedBy = blocker))
            return
        }
        val left = budgetMs - (now() - start)
        if (left <= 0) {
            set(CheckResult(c, CheckStatus.Skipped, "out of time", Advice.OutOfTime))
            return
        }
        set(CheckResult(c, CheckStatus.Running))
        val t0 = now()
        val limit = minOf(c.timeoutMs, left)
        val outcome = try {
            withTimeoutOrNull(limit) { if (c == SelfCheck.DEVICE) device() else port.check(c) }
                ?: CheckOutcome.fail("timed out after ${seconds(limit)} s", Advice.TimedOut)
        } catch (e: TimeoutCancellationException) {
            // 端口里自己的时限到了(不是这次自检被取消)
            CheckOutcome.fail("timed out inside the check", Advice.TimedOut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckOutcome.fail("error: ${e.javaClass.simpleName}", Advice.Error)
        }
        set(CheckResult(c, outcome.status, outcome.detail, outcome.advice, durationMs = now() - t0))
        _state.value = _state.value.copy(elapsedMs = now() - start)
    }

    private suspend fun device(): CheckOutcome {
        val f = port.facts()
        _state.value = _state.value.copy(facts = f)
        return judgeDevice(f)
    }

    private fun set(r: CheckResult) {
        val s = _state.value
        _state.value = s.copy(results = s.results.map { if (it.check == r.check) r else it })
    }

    /** 结束:还没结论的(取消时正在跑的和后面的)都记为跳过。 */
    private fun finish(phase: SelfCheckState.Phase, start: Long) {
        val s = _state.value
        _state.value = s.copy(
            phase = phase,
            elapsedMs = now() - start,
            results = s.results.map { if (it.status.final) it else CheckResult(it.check, CheckStatus.Skipped, "cancelled", Advice.Cancelled) },
        )
    }

    companion object {
        /** 整次自检的总时限:各项的时限加起来比这长,正常情况下远用不完。 */
        const val TOTAL_BUDGET_MS = 90_000L

        private fun seconds(ms: Long) = (ms + 999) / 1000

        /** 「设备」这一项:安卓 12 以下手机操作用不了;没有无线调试只能用电脑启动,算部分可用。 */
        fun judgeDevice(f: DeviceFacts): CheckOutcome {
            val detail = "Android ${f.androidRelease} (SDK ${f.sdk}), ${f.abis.firstOrNull() ?: "unknown ABI"}" +
                ", wireless debugging ${when (f.wirelessDebugging) { true -> "present"; false -> "missing"; null -> "unknown" }}"
            return when {
                f.sdk < DeviceFacts.MIN_PHONE_SDK -> CheckOutcome.fail("$detail; phone control needs Android 12+", Advice.PhoneTooOld)
                f.wirelessDebugging == false -> CheckOutcome.partial(detail, Advice.NoWirelessDebugging)
                else -> CheckOutcome.pass(detail)
            }
        }
    }
}
