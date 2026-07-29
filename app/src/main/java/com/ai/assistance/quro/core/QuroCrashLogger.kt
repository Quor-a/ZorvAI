package com.ai.assistance.quro.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃收集器（原创，v73 加入，v74 改进取回方式）。
 *
 * 目的：QuroAI 在真机上出现启动期闪退，但开发机（Windows）无法运行 aarch64 真机，
 * 也难以要求用户使用 adb 抓取日志。此收集器在 Application.attachBaseContext 阶段
 * （早于任何 ContentProvider.onCreate，包括 ShizukuProvider）安装 UncaughtExceptionHandler，
 * 把崩溃栈同时打到 logcat 与文件，便于无 adb 经验也能取回日志定位根因。
 *
 * v74 改进：日志**额外写入「下载」目录**（MediaStore，Android 11+ 文件管理器可见），
 * 因为原路径 /sdcard/Android/data/.../files/ 在 Android 11+ 被文件管理器屏蔽，用户取不到。
 *
 * 注意：本处理器在记录后会**重新抛出**给系统默认处理器，保留原生崩溃行为（进程仍会退出），
 * 仅额外留存一份可读报告。
 */
object QuroCrashLogger {
    private const val TAG = "QURO_CRASH"
    private const val FILE_NAME = "quro_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val report = buildReport(thread, throwable)
            Log.e(TAG, report)
            writeFile(context, report)
            prev?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 主动记录任意运行期异常（非崩溃），便于部署/执行错误可视化取回。
     * 与崩溃报告同样写入「下载」目录（MediaStore，Android 11+ 文件管理器可见）+ app 私有目录。
     */
    fun logError(context: Context, tag: String, t: Throwable) {
        val sw = StringWriter()
        sw.append("QuroAI 运行期错误 [$tag]\n")
        sw.append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        sw.append("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android API ${Build.VERSION.SDK_INT}\n")
        sw.append("===== 异常栈 =====\n")
        t.printStackTrace(PrintWriter(sw))
        val report = sw.toString()
        Log.e(TAG, report)
        writeFile(context, report)
    }

    private const val DIAG_NAME = "quro_diag.txt"

    /**
     * 记录一条运行期诊断事件（非崩溃），用于无 adb 取回真机行为数据。
     * 追加到 app 私有目录并镜像到「下载」目录（MediaStore，Android 11+ 文件管理器可见），
     * 便于排查「HTML 渲染 / 切换会话续跑 / 头像即时」等难以静态定位的问题。
     */
    fun logEvent(context: Context, tag: String, msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts][$tag] $msg\n"
        val f = File(context.filesDir, DIAG_NAME)
        runCatching { f.appendText(line) }
        runCatching { upsertDownload(context, DIAG_NAME, f.readText()) }
    }

    /** 把诊断文件（全量）写入「下载」目录：先删同名再插入，保证 Download 中始终是最新全量。 */
    private fun upsertDownload(context: Context, name: String, content: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, name).writeText(content)
            }
            return
        }
        val resolver = context.contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        runCatching {
            resolver.delete(coll, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name))
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(coll, values) ?: return@runCatching
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        sw.append("QuroAI 崩溃报告\n")
        sw.append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        sw.append("线程: ${thread.name}\n")
        sw.append("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android API ${Build.VERSION.SDK_INT}\n")
        sw.append("===== 异常栈 =====\n")
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun writeFile(context: Context, report: String) {
        val stamp = "\n\n========== ${System.currentTimeMillis()} ==========\n$report"

        // 1) app 私有目录（兜底，adb 可取）
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            f.appendText(stamp)
        }

        // 2) 下载目录（文件管理器可见，Android 11+ 也能取）——用户最易取回的路径
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { u ->
                    resolver.openOutputStream(u)?.use { os -> os.write(stamp.toByteArray()) }
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val f = File(dir, FILE_NAME)
                f.appendText(stamp)
            }
        }
    }
}
