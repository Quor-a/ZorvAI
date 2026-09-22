package com.ai.assistance.quro.genui.aiapp.data

import android.content.ContentValues
import android.content.Context
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
 * GenUI 界面诊断落盘 —— 把「模型实际产出的画布原文」写到手机公共 Download。
 *
 * 为什么需要它：用户报「组件不好看」时，光看截图只能猜。同一张截图里可能是
 * `padding` 写太大、可能是 `height: "match"` 撑满、也可能是容器类型选错
 * （box 是叠加、column 才纵向排），肉眼分不出来，而这些都是模型**每一轮自由发挥**的。
 * 把原文落盘 → 一次复现就能精确定位，省掉「让用户来回贴 JSON」的往返。
 *
 * 落点与 LLM 诊断保持一致：`Download/QuroAI_logs/`
 * （Android 11+ 走 MediaStore，文件管理器可见；10- 直接写公共目录；
 * 再兜底 app 私有外部目录，adb 可取）。
 */
object GenUiDiag {

    private const val TAG = "GenUiDiag"
    private const val DIR_NAME = "QuroAI_logs"
    private const val LAST_NAME = "genui_last_spec.json"

    /** 最多留几份带时间戳的历史，防止无限堆积 */
    private const val KEEP = 8

    /**
     * 落一份画布原文。
     *
     * @param payload 画布真正吃到的东西：GenUI 轮是 spec JSON，通道轮是原始围栏文本
     * @param request 本轮用户原话（判断"AI 到底在回应什么"）
     * @param channel 渲染通道 key（genui/a2ui/markdown/html）
     * @param debugInfo 提取链路的判定过程（哪个策略命中、失败原因）
     */
    fun dump(
        ctx: Context,
        payload: String,
        request: String,
        channel: String,
        debugInfo: String? = null
    ) {
        if (payload.isBlank()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val body = buildString {
            append("{\n")
            append("  \"time\": ").append(System.currentTimeMillis()).append(",\n")
            append("  \"renderChannel\": ").append(JSONObject.quote(channel)).append(",\n")
            append("  \"request\": ").append(JSONObject.quote(request)).append(",\n")
            append("  \"debugInfo\": ").append(JSONObject.quote(debugInfo.orEmpty())).append(",\n")
            append("  \"payloadLength\": ").append(payload.length).append(",\n")
            // payload 可能是 JSON 也可能是 ``` 围栏文本，原样嵌成字符串更稳（不破坏外层结构）
            append("  \"payload\": ").append(JSONObject.quote(payload)).append("\n")
            append("}")
        }
        val name = "genui_spec_$stamp.json"
        // 最新一份固定名，方便"只发一个文件"；
        // 带时间戳的那份留着对比"哪一轮开始变丑"。
        runCatching { write(ctx, LAST_NAME, body) }
        runCatching { write(ctx, name, body) }
        prune(ctx)
    }

    /** 把 body 写到 Download/QuroAI_logs/<name>（MediaStore → 公共目录 → 私有目录 三级兜底） */
    private fun write(ctx: Context, name: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = ctx.contentResolver
                val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // MediaStore 的 insert 不覆盖同名 → 先删旧的
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
                Log.i(TAG, "画布诊断已写入 Download/$DIR_NAME/$name")
            }.onFailure { Log.e(TAG, "MediaStore 写画布诊断失败: ${it.message}") }
        } else {
            runCatching {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DIR_NAME
                )
                dir.mkdirs()
                File(dir, name).writeText(body)
            }.onFailure { Log.e(TAG, "旧版写画布诊断失败: ${it.message}") }
        }
        // 兜底：app 私有外部存储（/sdcard/Android/data/<pkg>/files/QuroAI_logs）
        runCatching {
            val fb = ctx.getExternalFilesDir(DIR_NAME)?.apply { mkdirs() }
            fb?.let { File(it, name).writeText(body) }
        }.onFailure { Log.e(TAG, "兜底写画布诊断失败: ${it.message}") }
    }

    /** 只保留最近 [KEEP] 份带时间戳的 dump，避免长期使用堆成垃圾 */
    private fun prune(ctx: Context) {
        runCatching {
            val dir = ctx.getExternalFilesDir(DIR_NAME) ?: return@runCatching
            dir.listFiles { f ->
                f.isFile && f.name.startsWith("genui_spec_") && f.name != LAST_NAME
            }?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP)
                ?.forEach { it.delete() }
        }
    }
}
