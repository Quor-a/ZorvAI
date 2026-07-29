package com.ai.assistance.quro.util

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 诊断日志落盘工具：把关键运行日志（飞书 WS 帧、ANR 主线程栈等）写入
// 手机公共 Download 目录下的 QuroAI_logs 子目录，而非应用私有目录
// （Android/data/...，手机文件管理器打不开、需 adb 或 run-as 才能取）。
//
// 用公共 Download 目录的原因：Android 11+ 分区存储下，Downloads 目录仍允许
// 通过 File API 直接读写（Google 保留的兼容性例外），手机文件管理器可见，
// 用户无需 adb 或 logcat 即可取到日志。
//
// 所有写入均 runCatching 包裹，绝不影响主流程。
object QuroDiag {
    private val dir: File?
        get() = runCatching {
            val d = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "QuroAI_logs"
            )
            if (!d.exists()) d.mkdirs()
            d
        }.getOrNull()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // 追加一行到按天滚动的汇总日志 quro_diag_<date>.log（带时间戳）。
    fun log(tag: String, line: String) {
        runCatching {
            val d = dir ?: return@runCatching
            val f = File(d, "quro_diag_${dateFmt.format(Date())}.log")
            val ts = timeFmt.format(Date())
            f.appendText("[$ts][$tag] $line\n")
        }
    }

    // 写一个独立文件（用于大块内容，如 ANR 主线程栈、原始帧 hex）。返回文件路径或 null。
    fun writeFile(name: String, content: String): String? = runCatching {
        val d = dir ?: return@runCatching null
        val f = File(d, name)
        f.writeText(content)
        f.absolutePath
    }.getOrNull()
}
