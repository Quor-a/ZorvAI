package com.ai.assistance.quro.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID

/**
 * 附件模型与摄取工具（原创）：聊天支持图片 / 视频 / 文件附件。
 * 选取的附件会被复制到应用私有目录（quro_uploads），路径随会话 JSON 持久化，
 * 退出重开仍可读取；发送给视觉模型时再压缩为 base64 data URI。
 */
data class QuroAttachment(
    val id: String = UUID.randomUUID().toString(),
    val type: String,        // "image" | "video" | "file"
    val uri: String,         // 应用私有目录下的绝对文件路径
    val name: String,
    val mime: String,
    val size: Long = 0,
)

object QuroAttachmentKit {
    fun uploadsDir(context: Context): File =
        File(context.filesDir, "quro_uploads").also { if (!it.exists()) it.mkdirs() }

    fun typeOf(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        else -> "file"
    }

    /** 从系统返回的 content Uri 复制内容到应用私有目录，返回可持久化的附件。 */
    fun fromUri(context: Context, uri: Uri, mimeHint: String): QuroAttachment? {
        return runCatching {
            val cr = context.contentResolver
            val mime = mimeHint.takeIf { it.isNotBlank() }
                ?: (cr.getType(uri) ?: "application/octet-stream")
            val type = typeOf(mime)
            val ext = when (type) {
                "image" -> "jpg"
                "video" -> "mp4"
                else -> "bin"
            }
            val name = "att_${System.nanoTime()}_${UUID.randomUUID().toString().take(8)}.$ext"
            val out = File(uploadsDir(context), name)
            cr.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            QuroAttachment(
                type = type,
                uri = out.absolutePath,
                name = name,
                mime = mime,
                size = out.length(),
            )
        }.getOrNull()
    }

    /** 由已位于 uploads 目录的临时文件（如系统相机输出）直接构造附件。 */
    fun fromFile(context: Context, file: File, mime: String): QuroAttachment {
        val type = typeOf(mime)
        return QuroAttachment(
            type = type,
            uri = file.absolutePath,
            name = file.name,
            mime = mime,
            size = file.length(),
        )
    }

    /** 生成发送给视觉模型的 image_url（base64 data URI，最长边缩到 maxEdge 以控体积）。 */
    fun toVisionDataUri(path: String, maxEdge: Int = 1024): String? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val scale = (bounds.outWidth.coerceAtLeast(bounds.outHeight) / maxEdge).coerceAtLeast(1)
            val real = BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = BitmapFactory.decodeFile(path, real) ?: return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        }.getOrNull()
    }
}
