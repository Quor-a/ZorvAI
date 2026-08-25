package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.os.Environment
import com.ai.assistance.quro.util.QuroDiag
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 终端会话日志导出（E-10）。
 *
 * 导出到**公共** `Documents/QuroDocs/`，不是应用私有目录：
 * Android 11+ 分区存储下私有目录（`Android/data/...`）在手机文件管理器里
 * 打不开，用户拿不到文件，导出等于没做。Documents / Downloads 是 Google
 * 保留的 File API 兼容例外，文件管理器可直接看到。
 *
 * 若 Documents 不可写（部分定制 ROM / 未授予存储权限），自动回退到
 * Downloads/QuroDocs，再不行才报错——导出是辅助功能，不该抛异常打断终端。
 */
object QuroTerminalExport {

    /** 导出目录名。 */
    const val DIR_NAME: String = "QuroDocs"

    private val tsFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val headerFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 生成导出文件名：`terminal_<yyyyMMdd_HHmmss>.log`。
     *
     * 单独抽出来是为了可测（[java.util.Date] 可注入）。
     */
    fun fileName(at: Date = Date()): String = "terminal_${tsFmt.format(at)}.log"

    /**
     * 拼装导出内容：一段元信息头 + 全部滚动缓冲区行。
     *
     * 元信息头能让用户/开发者在事后知道这份日志是哪种 shell、什么时候、多少行，
     * 否则一堆没有上下文的输出很难复盘。
     */
    fun buildContent(
        lines: List<String>,
        mode: ShellMode,
        cwd: String,
        at: Date = Date(),
    ): String = buildString {
        appendLine("# Zorv AI 终端会话日志")
        appendLine("# 导出时间: ${headerFmt.format(at)}")
        appendLine("# Shell 模式: ${if (mode == ShellMode.LINUX) "proot/Linux (Ubuntu 24.04 ARM64)" else "设备 sh (Toybox)"}")
        appendLine("# 工作目录: $cwd")
        appendLine("# 行数: ${lines.size}")
        appendLine("# " + "-".repeat(56))
        for (l in lines) appendLine(l)
    }

    /**
     * 导出到磁盘。
     *
     * @return 成功时为写入文件的绝对路径，失败时为 `null`
     */
    fun export(
        context: Context,
        lines: List<String>,
        mode: ShellMode,
        cwd: String,
    ): String? {
        val content = buildContent(lines, mode, cwd)
        val name = fileName()

        for (base in candidateDirs()) {
            val path = runCatching {
                if (!base.exists() && !base.mkdirs()) return@runCatching null
                val f = File(base, name)
                f.writeText(content)
                f.absolutePath
            }.getOrNull()
            if (path != null) {
                QuroDiag.log(TAG, "终端日志已导出：$path（${lines.size} 行）")
                return path
            }
        }

        QuroDiag.log(TAG, "终端日志导出失败：Documents / Downloads 均不可写")
        return null
    }

    /** 候选导出目录，按优先级排列。 */
    private fun candidateDirs(): List<File> = listOfNotNull(
        runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        }.getOrNull(),
        runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME)
        }.getOrNull(),
    )

    private const val TAG = "QuroTerminalExport"
}
