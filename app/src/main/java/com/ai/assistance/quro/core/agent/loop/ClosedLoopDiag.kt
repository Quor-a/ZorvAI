package com.ai.assistance.quro.core.agent.loop

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 闭环执行诊断落盘 —— 把"某工具为何没跑通/没符合预期"的闭环轨迹写到手机公共 Download。
 *
 * 复用 GenUI [com.ai.assistance.quro.genui.aiapp.data.GenUiDiag] 的
 * MediaStore→公共目录→私有目录三级兜底，落点统一在 `Download/QuroAI_logs/`，
 * 与 GenUI 诊断同目录，方便一次 adb 拉全。
 */
object ClosedLoopDiag {
    private const val TAG = "ClosedLoopDiag"
    private const val DIR_NAME = "QuroAI_logs"
    private const val LAST_NAME = "closed_loop_last.json"
    private const val KEEP = 8

    /**
     * 落一份闭环诊断。
     * @param history 逐轮的"失败类型→决策"记录，便于复盘本次为何走到终态。
     */
    fun dump(context: Context?, name: String, arguments: String, history: List<String>, scenario: String) {
        if (context == null) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val body = buildString {
            append("{\n")
            append("  \"time\": ").append(System.currentTimeMillis()).append(",\n")
            append("  \"scenario\": ").append(JSONObject.quote(scenario)).append(",\n")
            append("  \"tool\": ").append(JSONObject.quote(name)).append(",\n")
            append("  \"arguments\": ").append(JSONObject.quote(arguments.take(4000))).append(",\n")
            append("  \"history\": [\n")
            append(history.joinToString(",\n") { "    " + JSONObject.quote(it) })
            append("\n  ]\n")
            append("}")
        }
        val fileName = "closed_loop_$stamp.json"
        runCatching { write(context, LAST_NAME, body) }
        runCatching { write(context, fileName, body) }
        prune(context)
    }

    private fun write(ctx: Context, name: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = ctx.contentResolver
                val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                runCatching {
                    resolver.delete(coll, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name))
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME")
                }
                val uri = resolver.insert(coll, values) ?: return@runCatching
                resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                Log.i(TAG, "闭环诊断已写入 Download/$DIR_NAME/$name")
            }.onFailure { Log.e(TAG, "MediaStore 写闭环诊断失败: ${it.message}") }
        } else {
            runCatching {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DIR_NAME,
                )
                dir.mkdirs()
                File(dir, name).writeText(body)
            }.onFailure { Log.e(TAG, "旧版写闭环诊断失败: ${it.message}") }
        }
        runCatching {
            val fb = ctx.getExternalFilesDir(DIR_NAME)?.apply { mkdirs() }
            fb?.let { File(it, name).writeText(body) }
        }.onFailure { Log.e(TAG, "兜底写闭环诊断失败: ${it.message}") }
    }

    private fun prune(ctx: Context) {
        runCatching {
            val dir = ctx.getExternalFilesDir(DIR_NAME) ?: return@runCatching
            dir.listFiles { f ->
                f.isFile && f.name.startsWith("closed_loop_") && f.name != LAST_NAME
            }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        }
    }
}
