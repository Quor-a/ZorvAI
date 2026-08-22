package com.ai.assistance.quro.core.aidlaci

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ACI 发现/绑定链路诊断日志器。
 *
 * 背景：真机「ACI 中心发现 0 个能力」反复出现，但控制端静态分析看不出运行时到底卡在哪一步。
 * 本类把 discover / bindService / onServiceConnected / asInterface 契约选择 / getCapabilities
 * 的每一步真实结果双写到手机存储，用户无需 adb，直接用文件管理器取日志即可定位。
 *
 * 写入优先级：
 *   1) 公共 Download/QuroAI_logs/aci_diag.log（需 MANAGE_EXTERNAL_STORAGE 且已授权）
 *   2) 回退 App 私有外部目录 …/files/Download/aci_diag.log（无需任何权限，一定可写）
 * 取日志时两个位置都看一下；若公共目录为空，说明没授权「所有文件访问」，取私有目录那份。
 */
object AciDiag {
    @Volatile private var ctx: Context? = null
    @Volatile private var file: File? = null
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        ctx = context.applicationContext
        file = resolveFile()
        val v = runCatching {
            ctx!!.packageManager.getPackageInfo(ctx!!.packageName, 0).versionName
        }.getOrNull() ?: "?"
        log("==== AciDiag init (pkg=${ctx?.packageName}, ver=$v) ====")
        log("logPath=${file?.absolutePath}")
    }

    private fun resolveFile(): File? {
        val c = ctx ?: return null
        // 1) 公共 Download/QuroAI_logs（调试版通常已授权 MANAGE_EXTERNAL_STORAGE）
        val pub = runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val d = File(dir, "QuroAI_logs").also { it.mkdirs() }
            File(d, "aci_diag.log")
        }.getOrNull()
        if (pub != null && canWrite(pub)) return pub
        // 2) 回退 App 私有外部目录（无需权限，必定可写）
        val ext = runCatching { c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        if (ext != null) {
            val f = File(ext, "aci_diag.log")
            if (canWrite(f)) return f
        }
        // 3) 最后兜底：内部 files 目录
        return runCatching { File(c.filesDir, "aci_diag.log") }.getOrNull()
    }

    private fun canWrite(f: File): Boolean = runCatching {
        if (f.exists() && f.length() > 2_000_000L) f.delete() // 超过 2MB 截断重写，避免无限膨胀
        FileWriter(f, true).use { it.append("") }
        true
    }.getOrDefault(false)

    fun log(msg: String) {
        synchronized(lock) {
            val f = file ?: return
            try {
                FileWriter(f, true).use { w ->
                    w.append(sdf.format(Date())).append("  ").append(msg).append("\n")
                }
            } catch (ignored: Exception) {
                // 日志失败绝不抛异常影响主流程
            }
        }
    }

    /** 便捷：带调用点的诊断。 */
    fun log(tag: String, msg: String) = log("[$tag] $msg")
}
