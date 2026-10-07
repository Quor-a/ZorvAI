package com.codecanvas.core.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import com.codecanvas.core.render.RenderOutput
import java.io.File
import java.io.OutputStream

enum class ExportFormat(val mime: String, val ext: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
    SVG("image/svg+xml", "svg"),
    ;

    val lossy: Boolean get() = this == JPEG || this == WEBP
}

/**
 * 统一导出层。Android 10+ 走 MediaStore（无需存储权限），
 * 10 以下退回到公共 Pictures 目录。
 */
interface Exporter {
    suspend fun export(
        output: RenderOutput,
        format: ExportFormat,
        displayName: String = "codecanvas_${System.currentTimeMillis()}",
        quality: Int = 95,
    ): Uri
}

class MediaStoreExporter(private val context: Context) : Exporter {

    override suspend fun export(
        output: RenderOutput,
        format: ExportFormat,
        displayName: String,
        quality: Int,
    ): Uri = when {
        output is RenderOutput.Vector || format == ExportFormat.SVG -> {
            val svg = when (output) {
                is RenderOutput.Vector -> output.svg
                else -> error("位图输出无法导出为 SVG，请先使用 SVG 渲染后端")
            }
            writeBytes("$displayName.svg", ExportFormat.SVG.mime) { it.write(svg.toByteArray()) }
        }
        else -> {
            val bmp = bitmapOf(output)
            writeBytes("$displayName.${format.ext}", format.mime) { os ->
                val ok = bmp.compress(format.toCompressFormat(), quality, os)
                if (!ok) error("压缩失败: $format")
            }
        }
    }

    /**
     * 取位图。
     *
     * 两处早期隐患：
     * 1. Vector + 位图格式：此前直接 error，宿主不知道该怎么办。
     *    现在给出可操作的提示（换 SVG 后端出位图，或先光栅化）。
     * 2. ComposeBitmap：core 对 Compose 是 compileOnly 依赖，
     *    未引入 renderer-compose 时会抛 NoClassDefFoundError。
     *    这里包一层，转成可读的提示。
     */
    private fun bitmapOf(output: RenderOutput): Bitmap = when (output) {
        is RenderOutput.Bitmap -> output.bitmap
        is RenderOutput.ComposeBitmap -> runCatching { output.imageBitmap.asAndroidBitmap() }
            .getOrElse {
                error("读取 Compose 位图失败：请确认已引入 renderer-compose 模块（${it.message}）")
            }
        is RenderOutput.Vector -> error(
            "矢量输出无法直接存为位图。三种解法：\n" +
                "1) format 传 ExportFormat.SVG 存成矢量文件；\n" +
                "2) 改用 CANVAS / WEBVIEW 后端渲染出位图；\n" +
                "3) 宿主自行接入 androidsvg 把 svg 光栅化后再导出。"
        )
    }

    private fun writeBytes(fileName: String, mime: String, block: (OutputStream) -> Unit): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CodeCanvas")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore 插入失败")
            resolver.openOutputStream(uri)?.use(block) ?: error("打开输出流失败")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES), "CodeCanvas")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.outputStream().use(block)
            Uri.fromFile(file)
        }
    }
}

private fun ExportFormat.toCompressFormat(): Bitmap.CompressFormat = when (this) {
    ExportFormat.PNG -> Bitmap.CompressFormat.PNG
    ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
    ExportFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30)
        Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    ExportFormat.SVG -> error("SVG 不是位图格式")
}
