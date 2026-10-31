package com.ai.assistance.quro.core.mika.shell

/** 从系统配对窗口上读到的配对码和端口;没读到或有歧义时为 null。 */
data class PairingScreenReading(val code: String?, val port: Int?)

/** 用读到的码去配对;[port] 为空时用找到的配对端口。 */
data class PairingAttempt(val code: String, val port: Int?)

/**
 * 从一个窗口的全部文字里认出配对码和「IP:端口」。只看文字的样子,不靠控件 id、界面语言或某种布局:
 * 配对码是整段文字恰好 6 位数字(中间可以有空格);端口取「IPv4:端口」或「[IPv6]:端口」里冒号后的数字。
 * 同一窗口里出现两个不同的码或端口就当没读到,宁可退回让用户手动填。
 */
object PairingScreen {
    private val CODE = Regex("""\d{6}""")
    private val IPV4_PORT = Regex("""(?<![\w.:])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})(?![\w.:])""")
    private val IPV6_PORT = Regex("""\[[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[\w.]+)?]:(\d{1,5})(?![\w.:])""")

    fun read(texts: List<String>): PairingScreenReading {
        val codes = texts.mapNotNull { t -> t.filterNot { it.isWhitespace() }.takeIf { CODE.matches(it) } }.toSet()
        val ports = texts.flatMap(::ports).toSet()
        return PairingScreenReading(codes.singleOrNull(), ports.singleOrNull())
    }

    private fun ports(text: String): List<Int> {
        val v4 = IPV4_PORT.findAll(text)
            .filter { m -> (1..4).all { m.groupValues[it].toInt() <= 255 } }
            .map { it.groupValues[5] }
        val v6 = IPV6_PORT.findAll(text).map { it.groupValues[1] }
        return (v4 + v6).mapNotNull { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } }.toList()
    }

    /**
     * 该不该拿读到的码去配对:有码、有端口(屏上读到,或已找到配对窗口)、这个码没试过,
     * 而且助手正等着配对(不在配对中、没有已经好了)。码不对时同一个码不会再试,等配对窗口换新码。
     */
    fun attempt(reading: PairingScreenReading, portFound: Boolean, status: BuiltinShellStatus, tried: Set<String>): PairingAttempt? {
        val code = reading.code ?: return null
        if (code in tried) return null
        if (reading.port == null && !portFound) return null
        val waiting = when (status) {
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> true
            is BuiltinShellStatus.Failed -> status.reason.duringPairing || status.fix == BuiltinShellFix.RePair
            BuiltinShellStatus.Connecting, BuiltinShellStatus.Running -> false
        }
        return if (waiting) PairingAttempt(code, reading.port) else null
    }
}
