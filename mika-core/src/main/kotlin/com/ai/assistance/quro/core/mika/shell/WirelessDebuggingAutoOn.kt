package com.ai.assistance.quro.core.mika.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 替用户打开无线调试停下来的原因;停下后要等换了 Wi-Fi、用户亲手打开过、点了重试或助手起来了才再写。 */
enum class AutoOnBlock {
    /** 写了打开,系统马上又拨回关着:这个 Wi-Fi 还没被允许无线调试(系统同时会问用户要不要允许)。 */
    NotAllowedHere,
    /** 系统不让写(写入抛异常或返回失败)。 */
    Refused,
    /** 连着替用户打开了 [WirelessDebuggingAutoOn.MAX_WRITES] 次,助手还是没起来。 */
    GaveUp,
}

/** 此刻要不要替用户打开无线调试。 */
sealed interface AutoOnGate {
    data object Act : AutoOnGate
    /** 离上一次写太近:等 [ms] 再来。 */
    data class Wait(val ms: Long) : AutoOnGate
    data class Skip(val reason: AutoOnSkip) : AutoOnGate
}

enum class AutoOnSkip {
    /** 用户在 ZorvAI 里关掉了「自动打开无线调试」。 */
    Declined,
    /** ZorvAI 没有修改系统设置的授权。 */
    NoPermission,
    /** 没连 Wi-Fi:系统不会在没有 Wi-Fi 时打开无线调试(写了也会被拨回)。 */
    NoWifi,
    /** 已经停下了,见 [AutoOnBlock]。 */
    Blocked,
}

/**
 * 内置助手要启动、无线调试却关着时,替用户把它打开(只开不关)。助手一旦起来就不再需要无线调试,
 * 但重新启动它(重启手机后、ZorvAI 更新后、助手没了)要靠无线调试;而安卓原版在 Wi-Fi 断开时、重启后都会把它关掉。
 *
 * 只在用户没关掉「自动打开无线调试」、ZorvAI 自己拿着修改系统设置的授权、连着 Wi-Fi 时写。
 * 要助手启动时助手当然不在,经已配对连接跑命令又要先有无线调试,所以只有 App 直接写这一条路;做不到就不动,界面照旧给手动打开的办法。
 *
 * 依赖的安卓原版行为(AdbDebuggingManager 对 `adb_wifi_enabled` 的处理):
 * - 设置被写成 1 后,系统检查当前 Wi-Fi:没连 Wi-Fi 就马上写回 0;
 *   这个网络(按 BSSID)不在「始终允许」名单里,也马上写回 0,同时弹框问用户「允许在此网络上使用无线调试吗」(可勾「始终允许」)。
 *   用户点了允许,系统自己再写回 1 并打开;拒绝就一直关着。
 * - 网络在名单里:保持 1,随后起无线调试服务(端口由连接那一步照常去找)。
 * 所以写完后看 [settleMs]:这段时间里读到关着就当这个 Wi-Fi 还没被允许,停下([AutoOnBlock.NotAllowedHere]),
 * 不再反复写(每写一次系统就弹一次框),直到换了 Wi-Fi、用户亲手打开、点了重试或助手起来了([reset])。
 * 有的系统对这一项另有处理(写入被拒、写了不生效),一样停下,不钻各家的实现。
 * 写之间按 [gapAfter] 拉开间隔;连着写了 [MAX_WRITES] 次助手都没起来也停下。
 */
class WirelessDebuggingAutoOn(
    /** 用户要不要这一项(没在 ZorvAI 里关掉「自动打开无线调试」)。 */
    private val wants: () -> Boolean,
    /** ZorvAI 自己拿着修改系统设置的授权。 */
    private val canWrite: () -> Boolean,
    /** 系统的无线调试开关;读不到为 null。 */
    private val read: () -> Boolean?,
    /** 把无线调试开关写成打开;系统不让写时返回 false 或抛异常。 */
    private val write: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val settleMs: Long = SETTLE_MS,
    private val pollMs: Long = POLL_MS,
) {
    private val _blocked = MutableStateFlow<AutoOnBlock?>(null)
    /** 停下来的原因;没停下为 null。给设置页和状态说一句。 */
    val blocked: StateFlow<AutoOnBlock?> = _blocked.asStateFlow()

    /** 正在写、等系统处理:这段时间里开关的变化由这里判断,别人不要跟着动。 */
    @Volatile var writing = false
        private set

    private var writes = 0
    private var lastWriteAt = 0L

    @Synchronized
    fun gate(wifi: Boolean): AutoOnGate {
        if (_blocked.value == null && writes >= MAX_WRITES) _blocked.value = AutoOnBlock.GaveUp
        return decide(wants(), canWrite(), wifi, _blocked.value, writes, now() - lastWriteAt)
    }

    /** 写打开并等系统处理完。开关保持打开返回 true;被拒或被拨回返回 false,并记下原因停下。 */
    suspend fun turnOn(): Boolean {
        synchronized(this) {
            writing = true
            writes++
            lastWriteAt = now()
        }
        try {
            val accepted = runCatching(write).getOrDefault(false)
            if (!accepted || read() == false) return false.also { block(if (accepted) AutoOnBlock.NotAllowedHere else AutoOnBlock.Refused) }
            var waited = 0L
            while (waited < settleMs) {
                delay(pollMs)
                waited += pollMs
                if (read() == false) return false.also { block(AutoOnBlock.NotAllowedHere) }
            }
            return true
        } finally {
            writing = false
        }
    }

    /** 换了 Wi-Fi、用户亲手打开了无线调试或点了重试、助手起来了:从头算。 */
    @Synchronized
    fun reset() {
        writes = 0
        lastWriteAt = 0L
        _blocked.value = null
    }

    private fun block(reason: AutoOnBlock) {
        _blocked.value = reason
    }

    companion object {
        /** 写完后看多久有没有被系统拨回去;原版是收到设置变化后马上处理,几百毫秒内就会拨回。 */
        const val SETTLE_MS = 3_000L
        const val POLL_MS = 250L
        const val MAX_WRITES = 3
        const val BASE_GAP_MS = 10_000L
        const val MAX_GAP_MS = 5 * 60_000L

        /** 写过 [writes] 次之后,下一次至少隔多久:第一次不等,之后 10 秒、20 秒……封顶 5 分钟。 */
        fun gapAfter(writes: Int): Long =
            if (writes <= 0) 0L else (BASE_GAP_MS shl (writes - 1).coerceAtMost(20)).coerceAtMost(MAX_GAP_MS)

        fun decide(wants: Boolean, canWrite: Boolean, wifi: Boolean, blocked: AutoOnBlock?, writes: Int, sinceLastMs: Long): AutoOnGate {
            if (!wants) return AutoOnGate.Skip(AutoOnSkip.Declined)
            if (!canWrite) return AutoOnGate.Skip(AutoOnSkip.NoPermission)
            if (blocked != null || writes >= MAX_WRITES) return AutoOnGate.Skip(AutoOnSkip.Blocked)
            if (!wifi) return AutoOnGate.Skip(AutoOnSkip.NoWifi)
            val gap = gapAfter(writes)
            return if (sinceLastMs < gap) AutoOnGate.Wait(gap - sinceLastMs) else AutoOnGate.Act
        }
    }
}
