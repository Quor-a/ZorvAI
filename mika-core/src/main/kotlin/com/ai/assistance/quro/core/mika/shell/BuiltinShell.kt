package com.ai.assistance.quro.core.mika.shell

import kotlinx.coroutines.flow.StateFlow

/**
 * 内置助手(经本机「无线调试」以 shell 身份拉起的特权进程)此刻的状态。
 * 界面、设置页和任务入口都只看它;配对、连接、看门狗的实现各自把结果写成这几种之一。
 */
sealed interface BuiltinShellStatus {
    /** 还没配对过(或解除了配对):要走一遍引导。 */
    data object NotPaired : BuiltinShellStatus

    /** 配对过,但无线调试关着:打开后会自动连上。 */
    data object WirelessDebuggingOff : BuiltinShellStatus

    /** 正在配对、连接或启动助手。 */
    data object Connecting : BuiltinShellStatus

    data object Running : BuiltinShellStatus

    /** 出错了:[reason] 说明是哪一类,[fix] 是一键修复走哪条路。 */
    data class Failed(val reason: BuiltinShellFailure, val fix: BuiltinShellFix = reason.defaultFix) : BuiltinShellStatus
}

/** 失败的类别。文案在界面层;这里只区分「用户该做什么」不同的情况。 */
enum class BuiltinShellFailure(val defaultFix: BuiltinShellFix, val duringPairing: Boolean) {
    /** 配对码不对或已过期。 */
    WrongCode(BuiltinShellFix.RePair, duringPairing = true),
    /** 配对窗口在填码前关掉了(找不到配对端口)。 */
    PairingWindowClosed(BuiltinShellFix.RePair, duringPairing = true),
    /** 没连 Wi-Fi:无线调试打不开或连不上。 */
    NoWifi(BuiltinShellFix.OpenWifi, duringPairing = false),
    /** ZorvAI 替用户打开无线调试,系统马上又关掉了:这个 Wi-Fi 还没被允许无线调试,要用户亲手打开一次并允许。 */
    WifiNotAllowed(BuiltinShellFix.OpenWirelessDebugging, duringPairing = false),
    /** 连不上本机的无线调试。 */
    ConnectFailed(BuiltinShellFix.Retry, duringPairing = false),
    /** 已配对的授权不认了(太久没用被系统撤销,或用户在系统里删了)。 */
    AuthorizationRevoked(BuiltinShellFix.RePair, duringPairing = false),
    /** 连上了,助手却没能启动或交接。 */
    HelperStartFailed(BuiltinShellFix.Retry, duringPairing = false),
    /**
     * 电脑经 USB 起的助手停了(多半是手机重启过),又没配对过无线调试,ZorvAI 自己起不来:
     * 再在电脑上跑一次命令,或去配对无线调试(之后就能自动恢复)。
     */
    UsbHelperStopped(BuiltinShellFix.RePair, duringPairing = false),
    Unknown(BuiltinShellFix.Retry, duringPairing = false),
}

enum class BuiltinShellFix {
    /** 用已配对的钥匙再连一次。 */
    Retry,
    /** 重新走配对那一步。 */
    RePair,
    /** 去系统的 Wi-Fi 设置。 */
    OpenWifi,
    /** 去开发者选项里亲手打开无线调试。 */
    OpenWirelessDebugging,
}

/** 找「使用配对码配对设备」窗口公布的配对端口(本机 mDNS)的结果。 */
sealed interface PairingDiscovery {
    data object Idle : PairingDiscovery
    data object Searching : PairingDiscovery
    data class Found(val port: Int) : PairingDiscovery
}

/**
 * 内置助手对界面的接口。实现负责配对(mDNS 找端口、6 位码)、连接本机 adbd、拉起并看住助手;
 * 所有结果都通过 [status] 反映出来,动作本身不抛异常。
 */
interface BuiltinShellControl {
    val status: StateFlow<BuiltinShellStatus>
    val pairingDiscovery: StateFlow<PairingDiscovery>

    /** 配对过无线调试(ZorvAI 能自己经它起助手、跑命令)。助手也可能是电脑经 USB 起的,所以在运行不等于配对过。 */
    val paired: Boolean get() = status.value != BuiltinShellStatus.NotPaired

    /** 开始找配对端口(用户正在去打开配对窗口)。重复调用无害。 */
    fun startDiscovery()
    fun stopDiscovery()

    /** 用 6 位配对码配对,成功后接着连接并启动助手。[port] 为空时用找到的配对端口。 */
    suspend fun startPairing(code: String, port: Int? = null)

    /** 用已配对的钥匙重新连接并启动助手。 */
    suspend fun retry()

    /** 删除配对钥匙、停掉助手、撤销助手给 ZorvAI 的授权;之后状态是 [BuiltinShellStatus.NotPaired]。 */
    suspend fun unpair()
}

/** 配对码:6 位数字。用户可能带空格、横线或全角数字,一并规范掉;不是 6 位数字就返回 null。 */
object PairingCode {
    const val LENGTH = 6

    /** 输入法可能打出的各种横线:半角、全角、连字号、短横、长横。 */
    private const val DASHES = "-－‐‑–—"

    fun normalize(input: String): String? {
        val digits = input.filterNot { it.isWhitespace() || it in DASHES }
            .map { c -> if (c in '０'..'９') '0' + (c - '０') else c }
            .joinToString("")
        return digits.takeIf { it.length == LENGTH && it.all { c -> c in '0'..'9' } }
    }

    /** 手动输入的端口:1–65535。 */
    fun port(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..65535 }
}
