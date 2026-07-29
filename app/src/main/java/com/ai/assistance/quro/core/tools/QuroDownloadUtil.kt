package com.ai.assistance.quro.core.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.URL

/**
 * 应用内统一文件下载落盘工具（自研，零 apl 自动操控）。
 *
 * 经 HttpURLConnection 拉取并用 MediaStore 写入公共 Download/Quro 目录（Android Q+），
 * 失败时回退到应用私有 Download 目录。供内置浏览器下载监听与 ai_browser download 动作复用。
 *
 * 注意：当前实现将响应整体读入内存后落盘，适合常规文档/图片/压缩包等下载；
 * 超大文件（数百 MB 级）存在内存压力，后续可改为流式落盘。
 */
object QuroDownloadUtil {

    /** 从 URL 或 Content-Disposition 推断文件名；无法推断返回 null。 */
    fun deriveFileName(url: String, contentDisposition: String?): String? {
        if (!contentDisposition.isNullOrBlank()) {
            val m = Regex(
                "filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
                RegexOption.IGNORE_CASE,
            ).find(contentDisposition)?.groupValues?.getOrNull(1)?.trim()
            if (!m.isNullOrBlank()) return m
        }
        val path = url.substringBefore('?').substringAfterLast('/')
        return if (path.isNotBlank() && path.contains('.')) path else null
    }

    /**
     * 下载并落盘。
     * 返回结果：成功以 "OK:<name>" 或 "FALLBACK:<path>" 开头；失败返回纯错误信息。
     */
    fun download(
        ctx: Context,
        dlUrl: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
    ): String {
        val name = deriveFileName(dlUrl, contentDisposition) ?: "quro_${System.currentTimeMillis()}"
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return try {
            val conn = (URL(dlUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                userAgent?.let { setRequestProperty("User-Agent", it) }
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return "下载失败：HTTP $code"
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val saved = saveBytes(ctx, bytes, name, mime)
            if (saved != null) {
                "OK:$name"
            } else {
                val p = fallbackBytes(ctx, bytes, name)
                "FALLBACK:$p"
            }
        } catch (e: Exception) {
            "下载失败：${e.message}"
        }
    }

    private fun saveBytes(ctx: Context, bytes: ByteArray, name: String, mime: String): String? = try {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Quro")
            }
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            Uri.fromFile(File(dir, name))
        }
        if (uri == null) return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        uri.toString()
    } catch (e: Exception) {
        null
    }

    private fun fallbackBytes(ctx: Context, bytes: ByteArray, name: String): String {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        val out = File(dir, name)
        out.writeBytes(bytes)
        return out.absolutePath
    }

    /**
     * 把一段文本写入公共 Download/Quro 目录（Android Q+ 走 MediaStore，低版本回退公共目录）。
     * 供「开发者文档 / 依赖模板 / 说明」等一键保存到本地复用。
     * 返回 "OK:<name>" 表示成功，其余为错误信息。
     */
    fun saveTextToDownloads(ctx: Context, fileName: String, mime: String, text: String): String = try {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Quro")
            }
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            Uri.fromFile(File(dir, fileName))
        }
        if (uri == null) return "保存失败：无法获取写入 URI"
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        "OK:$fileName"
    } catch (e: Exception) {
        "保存失败：${e.message}"
    }
}
