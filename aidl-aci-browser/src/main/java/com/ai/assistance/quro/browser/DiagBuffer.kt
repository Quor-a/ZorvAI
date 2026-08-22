package com.ai.assistance.quro.browser

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全进程共享诊断缓冲区（v5）。
 *
 * 设计动机：v4 证明 Activity 正常启动但文件日志在 Android 11+ Scoped Storage 下不可靠。
 * 本对象让 Service 和 Activity 共享同一份诊断文本，Activity 启动后直接渲染到屏幕顶部，
 * 用户截图即可反馈 Service 侧的 onCreate / onCreateCapabilities / onCall 每一步成败。
 *
 * 线程安全：CopyOnWriteArrayList + synchronized。
 */
object DiagBuffer {

    private val lines = CopyOnWriteArrayList<String>()

    /** 追加一行诊断（带时间戳）。 */
    fun append(tag: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$ts][$tag] $msg"
        lines.add(line)
        // 同时写 Logcat（不依赖文件）
        android.util.Log.d("ACI-Diag", "[$tag] $msg")
    }

    /** 获取全部诊断文本（供 Activity 渲染到屏幕）。 */
    fun getAll(): String = lines.joinToString("\n")

    /** 清空（可选，一般不清）。 */
    fun clear() { lines.clear() }

    /** 写到文件（辅助方法，仅在 getExternalFilesDir 可用时使用）。 */
    fun persist(ctx: android.content.Context?) {
        try {
            val dir = ctx?.getExternalFilesDir("QuroAI_logs") ?: ctx?.filesDir ?: return
            dir.mkdirs()
            val f = java.io.File(dir, "browser_v5_diag_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.log")
            f.writeText(getAll())
        } catch (_: Throwable) {}
    }
}
