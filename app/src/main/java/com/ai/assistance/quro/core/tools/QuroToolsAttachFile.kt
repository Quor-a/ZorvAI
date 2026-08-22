package com.ai.assistance.quro.core.tools

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.ai.assistance.quro.core.QuroAttachmentKit
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * AI 发文件：把设备上的文件（图片 / 视频 / 文档等）作为消息附件发到对话框，
 * 用户可直接在气泡里预览。AI 调用后，[com.ai.assistance.quro.core.QuroAssistant]
 * 会把它渲染成一条带附件的可见 AI 气泡。
 *
 * 入参：{"path":"文件绝对路径或 content:// URI","caption":"可选说明"}
 * 返回：{"ok":true,"name":..,"type":"image|video|file","path":..,"size":..,"caption":..}
 *      失败：{"ok":false,"error":"..."}
 */
class AttachFileTool : QuroTool {
    override val name = "attach_file"
    override val description =
        "把设备上的文件（图片/视频/文档/压缩包等）作为消息附件发到对话框，用户可直接预览。例如用户让你“把这张图发出来/把刚才生成的文件发给我”时调用。" +
        "参数 {\"path\":\"文件绝对路径（如 /sdcard/Pictures/x.jpg）或 content:// URI\",\"caption\":\"可选，随附件显示的一句说明\"}。"

    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"要发送的文件绝对路径或 content:// URI"},"caption":{"type":"string","description":"可选，随附件显示的一句说明文字"}}}"""

    override fun run(context: Context, arguments: String): String {
        return runCatching {
            val o = JSONObject(arguments)
            val rawPath = o.optString("path", "").trim()
            val caption = o.optString("caption", "").trim()
            if (rawPath.isBlank()) return@runCatching err("path 为空")

            val (srcFile, mime) = if (rawPath.startsWith("content://")) {
                val uri = Uri.parse(rawPath)
                val m = context.contentResolver.getType(uri) ?: guessMime(rawPath)
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m) ?: "bin"
                val dest = File(QuroAttachmentKit.uploadsDir(context), "att_${System.nanoTime()}_${UUID.randomUUID().toString().take(8)}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching err("无法读取 content:// 源")
                dest to m
            } else {
                val f = File(rawPath)
                if (!f.exists() || !f.isFile) return@runCatching err("文件不存在：$rawPath")
                val m = guessMime(f.name)
                // 已在 uploads 目录则直接复用，否则复制进来（保证 FileProvider 可共享预览）
                if (f.parentFile?.canonicalPath == QuroAttachmentKit.uploadsDir(context).canonicalPath) {
                    f to m
                } else {
                    val ext = f.extension.ifBlank { "bin" }
                    val dest = File(QuroAttachmentKit.uploadsDir(context), "att_${System.nanoTime()}_${UUID.randomUUID().toString().take(8)}.$ext")
                    f.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                    dest to m
                }
            }

            val type = when {
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                else -> "file"
            }
            JSONObject().apply {
                put("ok", true)
                put("name", srcFile.name)
                put("type", type)
                put("path", srcFile.absolutePath)
                put("size", srcFile.length())
                put("caption", caption)
            }.toString()
        }.getOrElse { err(it.message ?: "未知错误") }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image/$ext"
            "mp4", "mkv", "webm", "avi", "mov" -> "video/$ext"
            "pdf" -> "application/pdf"
            "txt", "log", "md", "json", "csv", "xml", "html", "htm" -> "text/plain"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun err(msg: String) = JSONObject().apply { put("ok", false); put("error", msg) }.toString()
}
