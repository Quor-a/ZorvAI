package com.ai.assistance.quro.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Quro 崩溃/异常上报器（原创）：把「设置闪退 / 无法对话」这类
 * 用户在设备上才能看到的崩溃，捕获并就地展示，避免主理人（无设备）盲猜。
 *
 * - [handler]：作为协程异常处理器，接住 Settings / 对话里逃逸的协程异常
 *   （含 SupervisorJob 原本会冒泡致崩的那类），转成可见的报错而非静默死亡。
 * - [report]：记录最近一次异常到 StateFlow，并在 filesDir 落盘，便于回传定位。
 * - [lastCrash]：由 QuroApp 顶层收集并弹窗展示。
 */
object QuroCrashReporter {
    private val _lastCrash = MutableStateFlow<String?>(null)
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    /** 由 QuroApp 在拿到 Context 时赋值，用于落盘日志。 */
    var crashDir: File? = null

    fun report(t: Throwable, where: String = "") {
        val msg = buildString {
            if (where.isNotBlank()) append("[$where] ")
            append(t.javaClass.name)
            if (t.message != null) append(": ${t.message}")
            append("\n")
            t.stackTrace.take(14).forEach { append("  at $it\n") }
        }
        _lastCrash.value = msg
        try {
            val dir = crashDir ?: return
            File(dir, "quro_crash.log").appendText("${System.currentTimeMillis()}\n$msg\n----\n")
            // 回放屏自身若崩溃（理论上不应发生），不要用自己的崩溃覆盖 pending，
            // 否则会陷入「启动→崩→回放→又崩」死循环，并冲掉真正的原始崩溃。
            val isSelfCrash = t.stackTrace.any { it.className.contains("CrashViewerScreen") }
            if (!isSelfCrash) {
                // 末次（非自身）崩溃单独存一份，供下次启动回放成可复制的弹屏。
                File(dir, "quro_crash_pending.log").writeText("${System.currentTimeMillis()}\n$msg")
            }
        } catch (_: Exception) {
            // 落盘失败不影响内存上报
        }
    }

    fun clear() {
        _lastCrash.value = null
    }

    /**
     * 读取「上一次运行」遗留的未捕获崩溃（由 [report] 写入），供启动期回放屏展示。
     * 仅在文件存在且非空时返回，任何异常都安全降级为 null。
     */
    fun loadPending(): String? {
        return try {
            val dir = crashDir ?: return null
            val f = File(dir, "quro_crash_pending.log")
            if (!f.exists() || f.length() == 0L) null else f.readText()
        } catch (_: Exception) {
            null
        }
    }

    /** 清除待回放的崩溃记录（用户已查看/复制后调用）。 */
    fun clearPending() {
        try {
            crashDir?.let { File(it, "quro_crash_pending.log").delete() }
        } catch (_: Exception) {
            // ignore
        }
    }

    /** 协程异常处理器：逃逸异常不再冒泡致崩，而是进 [report]。 */
    val handler = CoroutineExceptionHandler { _, t -> report(t, "coroutine") }
}
