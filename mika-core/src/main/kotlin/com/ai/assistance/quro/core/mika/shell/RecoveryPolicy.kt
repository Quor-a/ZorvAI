package com.ai.assistance.quro.core.mika.shell

import kotlinx.coroutines.delay

/** 判断要不要重起助手时看的几样东西。 */
data class RecoveryInputs(
    /** 本机存着配对钥匙。 */
    val paired: Boolean,
    /** 系统的无线调试开关;有的手机读不到,为 null。 */
    val wirelessDebugging: Boolean?,
    val status: BuiltinShellStatus,
)

enum class StopReason {
    NotPaired,
    /** 无线调试关着:等它打开时再来(由监听开关的一方重新触发)。 */
    WirelessDebuggingOff,
    /** 失败的类别要用户出手(重新配对、连 Wi-Fi),重试没用。 */
    NeedsUser,
    OutOfAttempts,
}

sealed interface RecoveryDecision {
    data object Healthy : RecoveryDecision
    /** 等 [delayMs] 后用已配对的钥匙重连一次。 */
    data class Try(val delayMs: Long) : RecoveryDecision
    /** 别人正在连:等 [delayMs] 再看,不叠一次重连。 */
    data class Wait(val delayMs: Long) : RecoveryDecision
    data class Stop(val reason: StopReason) : RecoveryDecision
}

data class RecoveryOutcome(val recovered: Boolean, val attempts: Int, val stoppedBecause: StopReason?)

/**
 * 助手不在时什么时候重起、隔多久、试几次。拔 USB、adbd 重启会带走所有 shell 身份的进程,
 * 所以不区分「怎么没的」:只要配对还在、无线调试没被确认关掉、失败不需要用户出手,就按退避重连。
 * 信任网络与否读不到,一律先试,失败由连接结果说话。
 */
object RecoveryPolicy {
    const val MAX_ATTEMPTS = 5
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    /** 第一次马上试,之后 2、4、8、16 秒……封顶 30 秒。 */
    fun backoffMs(attempt: Int): Long =
        if (attempt <= 0) 0L else (BASE_DELAY_MS shl (attempt - 1).coerceAtMost(20)).coerceAtMost(MAX_DELAY_MS)

    fun decide(inputs: RecoveryInputs, attempts: Int): RecoveryDecision {
        val s = inputs.status
        return when {
            s == BuiltinShellStatus.Running -> RecoveryDecision.Healthy
            !inputs.paired || s == BuiltinShellStatus.NotPaired -> RecoveryDecision.Stop(StopReason.NotPaired)
            inputs.wirelessDebugging == false -> RecoveryDecision.Stop(StopReason.WirelessDebuggingOff)
            s is BuiltinShellStatus.Failed && needsUser(s.reason) -> RecoveryDecision.Stop(StopReason.NeedsUser)
            attempts >= MAX_ATTEMPTS -> RecoveryDecision.Stop(StopReason.OutOfAttempts)
            s == BuiltinShellStatus.Connecting -> RecoveryDecision.Wait(backoffMs(attempts).coerceAtLeast(BASE_DELAY_MS))
            else -> RecoveryDecision.Try(backoffMs(attempts))
        }
    }

    private fun needsUser(f: BuiltinShellFailure) = f.defaultFix != BuiltinShellFix.Retry

    /**
     * 按 [decide] 一直试到恢复或该停。[attempt] 是一次重连(真实现里是 `BuiltinShellControl.retry()`,
     * 它不抛异常,结果写进状态);[inputs] 每轮重新读。
     */
    suspend fun recover(
        inputs: suspend () -> RecoveryInputs,
        attempt: suspend () -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): RecoveryOutcome {
        var attempts = 0
        while (true) {
            when (val d = decide(inputs(), attempts)) {
                RecoveryDecision.Healthy -> return RecoveryOutcome(recovered = true, attempts = attempts, stoppedBecause = null)
                is RecoveryDecision.Stop -> return RecoveryOutcome(recovered = false, attempts = attempts, stoppedBecause = d.reason)
                is RecoveryDecision.Wait -> { sleep(d.delayMs); attempts++ }
                is RecoveryDecision.Try -> { sleep(d.delayMs); attempt(); attempts++ }
            }
        }
    }
}

/** 开机时「重启后自动恢复」要不要动手:看它是否已经生效(默认打开、补上过的也算),不看用户有没有拨过开关。 */
object BootRestore {
    const val MIN_SDK = 31

    enum class Gate { Act, Skip, PermissionMissing }

    fun gate(sdkInt: Int, consent: RecoveryConsent, holdsPermission: Boolean): Gate = when {
        sdkInt < MIN_SDK || !consent.isApplied(RecoveryCapability.RestoreAfterReboot) -> Gate.Skip
        !holdsPermission -> Gate.PermissionMissing
        else -> Gate.Act
    }
}

/** 上一次开机自动恢复的结果,给设置页如实显示。 */
enum class RestoreOutcome {
    Restored,
    /** 这一项打开过,但修改系统设置的授权已经不在。 */
    PermissionMissing,
    /** 写无线调试开关被系统拒绝或写了不生效:这台手机不支持开机自动打开。 */
    SettingUnavailable,
    /** 开关打开了,但没能连上(比如不在信任的 Wi-Fi 上)。 */
    NotRecovered,
}
