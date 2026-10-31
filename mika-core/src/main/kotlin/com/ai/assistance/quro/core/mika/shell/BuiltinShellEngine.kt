package com.ai.assistance.quro.core.mika.shell

import com.ai.assistance.quro.core.mika.adb.AdbConnectionException
import com.ai.assistance.quro.core.mika.adb.AdbPairingException
import com.ai.assistance.quro.core.mika.adb.AdbServiceTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 引擎要的平台能力。阻塞调用都在 IO 调度器上执行。 */
interface BuiltinShellPlatform {
    fun paired(): Boolean
    fun setPaired(paired: Boolean)
    /** 无线调试开关;读不到为 null(不拦着往下走)。 */
    fun wirelessDebugging(): Boolean?
    fun wifiConnected(): Boolean
    suspend fun findPairingPort(timeoutMs: Long): Int?
    /** 用配对码配对;失败抛 [AdbPairingException]。 */
    fun pair(port: Int, code: String)
    /** 连本机 adbd 并起助手(不等交接);失败抛 [AdbConnectionException]。 */
    suspend fun launchHelper()
    /** 让正在跑的助手撤掉改动并退出;没有就什么都不做。 */
    fun stopHelper()
    /** 删掉配对钥匙和交接口令。 */
    fun forget()
    /** 没配对时收下过助手(是电脑经 USB 起的):它停了要告诉用户怎么再起,而不是当作从没设置过。持久化。 */
    fun usbStarted(): Boolean
    fun setUsbStarted(on: Boolean)
}

/** 看门狗重起助手的间隔:第 n 次失败后等 base·2^(n-1),封顶 [maxMs];连续失败 [maxAttempts] 次后停下等人或等开关变化。 */
data class Backoff(val baseMs: Long = 2_000, val maxMs: Long = 60_000, val maxAttempts: Int = 5) {
    fun delayAfter(failures: Int): Long? {
        if (failures >= maxAttempts) return null
        return (baseMs shl (failures - 1).coerceIn(0, 20)).coerceAtMost(maxMs)
    }
}

object BuiltinShellFailures {
    fun ofPairing(reason: AdbPairingException.Reason) = when (reason) {
        AdbPairingException.Reason.WRONG_CODE -> BuiltinShellFailure.WrongCode
        // 配对端口连不上:配对窗口已经关了(端口随窗口一起消失)
        AdbPairingException.Reason.CONNECT_FAILED -> BuiltinShellFailure.PairingWindowClosed
        AdbPairingException.Reason.TLS_FAILED, AdbPairingException.Reason.PROTOCOL -> BuiltinShellFailure.Unknown
    }

    fun ofConnection(reason: AdbConnectionException.Reason) = when (reason) {
        AdbConnectionException.Reason.UNAUTHORIZED -> BuiltinShellFailure.AuthorizationRevoked
        AdbConnectionException.Reason.CONNECT_FAILED,
        AdbConnectionException.Reason.TLS_FAILED,
        AdbConnectionException.Reason.PROTOCOL -> BuiltinShellFailure.ConnectFailed
    }

    /** 看门狗自己再试有没有用:钥匙被撤销、没 Wi-Fi 要等用户或开关变化。 */
    fun retryable(f: BuiltinShellFailure) = f == BuiltinShellFailure.ConnectFailed ||
        f == BuiltinShellFailure.HelperStartFailed || f == BuiltinShellFailure.Unknown
}

/**
 * 内置助手的状态与看门狗。[helper] 有值就是助手的 Binder 在手(Running);
 * 没有时:配过对就先等 [graceMs](还活着的助手会在 ZorvAI 进程起来后自己交接),再经 adbd 起一个,失败按 [backoff] 重试。
 * 要起助手时无线调试关着,有 [autoOn] 就先替用户打开(条件和停下的规矩见 [WirelessDebuggingAutoOn])。
 */
class BuiltinShellEngine(
    private val platform: BuiltinShellPlatform,
    private val helper: StateFlow<Any?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val backoff: Backoff = Backoff(),
    private val graceMs: Long = 3_000,
    private val handoffTimeoutMs: Long = 15_000,
    private val discoveryTimeoutMs: Long = 10 * 60_000L,
    private val pairingPortTimeoutMs: Long = 5_000,
    private val autoOn: WirelessDebuggingAutoOn? = null,
) : BuiltinShellControl {
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<BuiltinShellStatus> = _status.asStateFlow()
    private val _discovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    override val pairingDiscovery: StateFlow<PairingDiscovery> = _discovery.asStateFlow()

    /** 最近一次配对或连接失败的异常类别和说明(诊断信息用,原文未脱敏);连上后清空。 */
    @Volatile var lastError: String? = null
        private set

    private val launchLock = Mutex()
    private var failures = 0
    private var retryJob: Job? = null
    private var discoveryJob: Job? = null
    private var started = false

    override val paired: Boolean get() = platform.paired()

    private fun initialStatus(): BuiltinShellStatus = when {
        helper.value != null -> BuiltinShellStatus.Running
        // 电脑起过的助手可能还活着,等它来交接
        !platform.paired() -> if (platform.usbStarted()) BuiltinShellStatus.Connecting else BuiltinShellStatus.NotPaired
        else -> BuiltinShellStatus.Connecting
    }

    /** 没配对、手里也没有助手时的状态:电脑起过的助手停了就照实说,否则就是还没设置。 */
    private fun unpairedStatus(): BuiltinShellStatus =
        if (platform.usbStarted()) BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) else BuiltinShellStatus.NotPaired

    /** 开始看着助手:掉线就重起。只在功能打开时调。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        var first = true
        scope.launch {
            helper.collect { h ->
                if (h != null) {
                    synchronized(this@BuiltinShellEngine) { failures = 0; retryJob?.cancel() }
                    autoOn?.reset()
                    // 没配对就来了:是电脑经 USB 起的。记下来,它停了才知道该怎么提示
                    if (!platform.paired()) platform.setUsbStarted(true)
                    _status.value = BuiltinShellStatus.Running
                } else if (!platform.paired()) {
                    // App 刚起来时还活着的助手会自己来交接:先等一会儿,再说它停了
                    if (first && platform.usbStarted()) {
                        _status.value = BuiltinShellStatus.Connecting
                        schedule(graceMs)
                    } else {
                        _status.value = unpairedStatus()
                    }
                } else {
                    if (_status.value == BuiltinShellStatus.Running) _status.value = BuiltinShellStatus.Connecting
                    schedule(if (first) graceMs else backoff.baseMs)
                }
                first = false
            }
        }
    }

    /** 有人要用助手却没有:没在起、也没排着重试就马上起一次。 */
    fun need() {
        if (helper.value != null || !platform.paired()) return
        synchronized(this) { if (retryJob?.isActive == true) return }
        if (launchLock.isLocked) return
        schedule(0)
    }

    /**
     * 无线调试开关变了:刚打开就从头再试。关掉时只改状态,不马上替用户打开(用户可能是亲手关的),等要用助手时再说。
     * ZorvAI 自己正在写这个开关时不跟着动:系统可能马上拨回去,由写的那一边判断。
     */
    fun wirelessDebuggingChanged() {
        if (helper.value != null || !platform.paired()) return
        if (autoOn?.writing == true) return
        if (platform.wirelessDebugging() == false) {
            _status.value = offStatus()
            return
        }
        autoOn?.reset()
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /**
     * 连上了 Wi-Fi:之前卡在「没有 Wi-Fi」就马上再试(不用能上网);能替用户打开无线调试时,
     * 卡在无线调试关着(包括上一个网络不允许)也马上再试。换了网络,上一个网络上的拒绝不再算数。
     */
    fun wifiAvailable() {
        autoOn?.reset()
        val s = _status.value
        val off = s == BuiltinShellStatus.WirelessDebuggingOff || s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        if (s != BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi) && !(autoOn != null && off)) return
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /** 无线调试关着时显示的状态:这个 Wi-Fi 上刚被系统拨回过就照实说,否则就是关着。 */
    private fun offStatus(): BuiltinShellStatus =
        if (autoOn?.blocked?.value == AutoOnBlock.NotAllowedHere) BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        else BuiltinShellStatus.WirelessDebuggingOff

    /** 无线调试关着:能替用户打开就打开,开着没被拨回返回 true 接着连;否则写好状态返回 false。 */
    private suspend fun turnOnWirelessDebugging(): Boolean {
        val auto = autoOn ?: return false.also { _status.value = offStatus() }
        when (val g = auto.gate(platform.wifiConnected())) {
            AutoOnGate.Act -> {
                _status.value = BuiltinShellStatus.Connecting
                if (withContext(io) { auto.turnOn() }) return true
            }
            // 离上一次写太近:到时候再来一次
            is AutoOnGate.Wait -> synchronized(this) {
                retryJob = scope.launch {
                    delay(g.ms)
                    launchOnce()
                }
            }
            is AutoOnGate.Skip -> Unit
        }
        _status.value = offStatus()
        return false
    }

    @Synchronized
    private fun schedule(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            launchOnce()
        }
    }

    private suspend fun launchOnce(): Unit = launchLock.withLock {
        if (helper.value != null) return@withLock
        if (!platform.paired()) {
            _status.value = unpairedStatus()
            return@withLock
        }
        if (platform.wirelessDebugging() == false && !turnOnWirelessDebugging()) return@withLock
        _status.value = BuiltinShellStatus.Connecting
        val failure = attempt()
        if (failure == null) {
            lastError = null
            _status.value = BuiltinShellStatus.Running
            return@withLock
        }
        _status.value = BuiltinShellStatus.Failed(failure)
        val next = synchronized(this) { ++failures }
        if (BuiltinShellFailures.retryable(failure)) backoff.delayAfter(next)?.let { d ->
            synchronized(this) {
                retryJob = scope.launch {
                    delay(d)
                    launchOnce()
                }
            }
        }
    }

    private suspend fun attempt(): BuiltinShellFailure? = try {
        withContext(io) { platform.launchHelper() }
        if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) null
        else BuiltinShellFailure.HelperStartFailed.also { lastError = "helper handoff timed out" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbConnectionException) {
        lastError = describe(e)
        val f = BuiltinShellFailures.ofConnection(e.reason)
        if (f == BuiltinShellFailure.ConnectFailed && !platform.wifiConnected()) BuiltinShellFailure.NoWifi else f
    } catch (e: Exception) {
        lastError = describe(e)
        BuiltinShellFailure.Unknown
    }

    private fun describe(e: Exception): String =
        e.javaClass.simpleName + ((e as? AdbConnectionException)?.reason?.let { "($it)" } ?: (e as? AdbPairingException)?.reason?.let { "($it)" } ?: "") +
            (e.message?.let { ": " + it.take(200) } ?: "")

    override fun startDiscovery() {
        synchronized(this) {
            if (discoveryJob?.isActive == true) return
            _discovery.value = PairingDiscovery.Searching
            discoveryJob = scope.launch {
                val port = withContext(io) { platform.findPairingPort(discoveryTimeoutMs) }
                _discovery.value = port?.let { PairingDiscovery.Found(it) } ?: PairingDiscovery.Idle
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(this) {
            discoveryJob?.cancel()
            discoveryJob = null
        }
        _discovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        _status.value = BuiltinShellStatus.Connecting
        val target = port ?: (_discovery.value as? PairingDiscovery.Found)?.port
            ?: withContext(io) { platform.findPairingPort(pairingPortTimeoutMs) }
        if (target == null) {
            lastError = "pairing port not found"
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
            return
        }
        val failure = try {
            withContext(io) { platform.pair(target, code) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbPairingException) {
            lastError = describe(e)
            BuiltinShellFailures.ofPairing(e.reason)
        } catch (e: Exception) {
            lastError = describe(e)
            BuiltinShellFailure.Unknown
        }
        if (failure != null) {
            _status.value = BuiltinShellStatus.Failed(failure)
            return
        }
        platform.setPaired(true)
        stopDiscovery()
        retry()
    }

    override suspend fun retry() {
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
        }
        // 用户点了重试:之前替用户打开无线调试时的拒绝不再算数(不允许的网络上系统会再问一次)
        autoOn?.reset()
        if (helper.value != null) {
            _status.value = BuiltinShellStatus.Running
            return
        }
        launchOnce()
    }

    override suspend fun unpair() {
        synchronized(this) { retryJob?.cancel() }
        launchLock.withLock {
            withContext(io) {
                // 先忘掉「电脑起过」:助手停下时状态就是没设置,不提示再跑命令
                platform.setUsbStarted(false)
                runCatching { platform.stopHelper() }
                platform.forget()
            }
            platform.setPaired(false)
            _status.value = BuiltinShellStatus.NotPaired
        }
    }
}
