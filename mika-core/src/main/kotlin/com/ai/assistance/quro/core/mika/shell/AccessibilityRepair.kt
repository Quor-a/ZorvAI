package com.ai.assistance.quro.core.mika.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** App 看到的 ZorvAI 无障碍服务:系统列表里有没有它([enabled]),服务实例是否连着([connected])。 */
data class A11yObservation(val enabled: Boolean, val connected: Boolean) {
    /**
     * 服务没在运行,就要修:不管是系统里开着却没绑定(进程被杀后系统当它崩溃了),
     * 还是被从系统列表里拿掉了(强行停止时系统会这样做)。用户要关掉就关「替我打开无障碍」。
     */
    val stuck: Boolean get() = !connected
}

/** 自动重启无障碍服务进行到哪一步:要用服务的一方据此决定多等一会儿还是马上放弃。 */
enum class RepairPhase {
    /** 没有要修的(服务连着、用户在 ZorvAI 里关掉了替我打开、助手不在、试过两次放弃了)。 */
    Idle,
    /** 卡住了,先等一会儿看系统自己会不会连上。 */
    Pending,
    /** 正在去掉再加回、等服务连上。 */
    Repairing,
}

/** 上一次自动重启无障碍服务的结果(只记在内存里,给设置页说一句)。 */
data class RepairOutcome(val restarted: Boolean, val atMillis: Long, val attempts: Int)

/**
 * ZorvAI 的无障碍服务没在运行时自动打开:内置助手在运行时,用它把 ZorvAI 的服务写回系统列表
 * ([RecoveryPermissions.restartAccessibility]:列表里有就去掉再加回让系统重新绑定,没有就直接加上)。
 * App 自己有修改系统设置的授权时([direct])直接写,不用等助手(无线调试关着也能修)。
 * 只看两路状态:服务([A11yObservation])和内置助手;任一变化都重新判断,不轮询。
 *
 * - 只要没在运行就修;只有用户在 ZorvAI 里关掉了「替我打开无障碍」才不管。
 * - 刚卡住时先等 [GRACE_MS]:进程刚起来时系统通常马上会连上,不必动手。
 * - 每次卡住最多试 [MAX_ATTEMPTS] 次,间隔逐次拉长;还是没连上就停下,留给界面上手动处理的提示。
 *   服务连上了才算这一次过去,之后再断开重新计数。
 */
class AccessibilityRepair(
    private val restart: suspend (gapMs: Long) -> PermissionResult,
    private val consent: () -> RecoveryConsent,
    /** App 自己就能写系统设置(拿到了修改系统设置的授权)时为 true:这时不用等内置助手。 */
    private val direct: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _last = MutableStateFlow<RepairOutcome?>(null)
    val last: StateFlow<RepairOutcome?> = _last.asStateFlow()

    private val _phase = MutableStateFlow(RepairPhase.Idle)
    val phase: StateFlow<RepairPhase> = _phase.asStateFlow()

    private enum class Result { Restored, GaveUp, Interrupted }

    /** 一直看着,直到被取消。修的过程中状态的变化(包括自己去掉又加回)按最新的一份再判断一次。 */
    suspend fun watch(service: StateFlow<A11yObservation>, helper: StateFlow<BuiltinShellStatus>) {
        var exhausted = false
        combine(service, helper, ::Pair).conflate().collect { (s, h) ->
            if (s.connected) {
                exhausted = false
                return@collect
            }
            if (exhausted || !eligible(s, h)) return@collect
            try {
                _phase.value = RepairPhase.Pending
                delay(GRACE_MS)
                if (!eligible(service.value, helper.value)) return@collect
                _phase.value = RepairPhase.Repairing
                exhausted = repair(service, helper) == Result.GaveUp
            } finally {
                _phase.value = RepairPhase.Idle
            }
        }
    }

    private fun eligible(s: A11yObservation, h: BuiltinShellStatus) =
        s.stuck && (direct() || h == BuiltinShellStatus.Running) && consent().wants(RecoveryCapability.Accessibility)

    private suspend fun repair(service: StateFlow<A11yObservation>, helper: StateFlow<BuiltinShellStatus>): Result {
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                delay(BACKOFF_MS * (attempt - 1))
                val s = service.value
                if (s.connected) return Result.Restored.also { record(true, attempt - 1) }
                if (!eligible(s, helper.value)) return Result.Interrupted
            }
            if (restart(GAP_MS * attempt) == PermissionResult.Done &&
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { service.first { it.connected } } != null
            ) {
                return Result.Restored.also { record(true, attempt) }
            }
        }
        record(false, MAX_ATTEMPTS)
        return Result.GaveUp
    }

    private fun record(restarted: Boolean, attempts: Int) {
        _last.value = RepairOutcome(restarted, now(), attempts)
    }

    companion object {
        const val GRACE_MS = 3_000L
        const val MAX_ATTEMPTS = 2
        /** 去掉和加回之间的停顿,逐次拉长(1 秒、2 秒):两次写入挨得太近,系统可能只看到最后的列表。 */
        const val GAP_MS = 1_000L
        const val BACKOFF_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        /** 从卡住到放弃最长要多久:先等一会儿,两次去掉再加回、各等连上,中间退避一次,再留些跑命令的余量。 */
        const val WORST_CASE_MS = GRACE_MS + MAX_ATTEMPTS * CONNECT_TIMEOUT_MS + GAP_MS * 3 + BACKOFF_MS + 2_000L

        /**
         * 要用无障碍服务时等它:先照常等 [baseMs];还没连上、但正要修或正在修([phase] 不是 Idle),
         * 就接着等到连上或修的那一边停下(修好了服务已经连着,放弃了返回 null),总共不超过 [capMs]。
         * 没有要修的就和以前一样,[baseMs] 到了返回 null。
         */
        suspend fun <T : Any> awaitService(
            service: StateFlow<T?>,
            phase: StateFlow<RepairPhase>,
            baseMs: Long,
            capMs: Long = WORST_CASE_MS,
        ): T? {
            withTimeoutOrNull(baseMs) { service.filterNotNull().first() }?.let { return it }
            if (phase.value == RepairPhase.Idle) return service.value
            return withTimeoutOrNull((capMs - baseMs).coerceAtLeast(0)) {
                combine(service, phase, ::Pair).first { (s, p) -> s != null || p == RepairPhase.Idle }.first
            }
        }
    }
}
