package com.ai.assistance.quro.core.mika.shell

/**
 * WebView 调试接口的本地抽象套接字:每个开着调试的进程一个,名为 `webview_devtools_remote_<pid>`。
 * 内置助手代连时只认这个样子的名字,且 pid 必须属于 ZorvAI 的 uid。
 */
object DevtoolsSocket {
    private const val PREFIX = "webview_devtools_remote_"
    private val NAME = Regex("""webview_devtools_remote_([1-9][0-9]{0,9})""")

    fun name(pid: Int): String = "$PREFIX$pid"

    /** 名字里的 pid;不是这个样子(多了前后缀、前导 0、超出范围)返回 null。 */
    fun pidOf(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** `/proc/<pid>/status` 里的真实 uid(Uid 行第一列);没有这一行返回 null。 */
    fun procUid(status: String): Int? =
        status.lineSequence().firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
}
