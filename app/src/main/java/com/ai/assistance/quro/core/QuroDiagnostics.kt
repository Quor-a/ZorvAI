package com.ai.assistance.quro.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 极简崩溃 / 错误上报（原创）：把未捕获异常与对话诊断汇集到内存 StateFlow，
 * 供 QuroApp 顶层弹窗展示；同时落盘 quro_crash.log 以便排查。
 *
 * 背景：主理人无法访问用户设备，此前"设置闪退 / 无法对话"改了三轮仍复现，
 * 卡在"看不到真实报错"。本对象让 App 自己把异常原因显示给用户，
 * 用户截图 / 复制文字发回即可精确定位，不再靠盲猜。
 */
object QuroDiagnostics {
    data class CrashInfo(
        val where: String,
        val message: String,
        val trace: String,
    )

    private val _crash = MutableStateFlow<CrashInfo?>(null)
    val crash: StateFlow<CrashInfo?> = _crash.asStateFlow()

    /** 由 QuroApp 在拿到 Context 时赋值，用于落盘日志。 */
    lateinit var appFilesDir: java.io.File

    fun report(where: String, t: Throwable) {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val trace = sw.toString().lineSequence().take(30).joinToString("\n")
        _crash.value = CrashInfo(where, t.message ?: t.javaClass.simpleName, trace)
        try {
            java.io.File(appFilesDir, "quro_crash.log").appendText("【$where】${t.message}\n$trace\n====\n")
        } catch (_: Exception) {
            // 落盘失败不影响内存上报
        }
    }

    fun clear() {
        _crash.value = null
    }
}
