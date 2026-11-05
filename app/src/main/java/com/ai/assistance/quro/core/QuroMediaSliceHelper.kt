package com.ai.assistance.quro.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 媒体切片辅助（视频抽帧 / PDF 选页），供对话框附件「切片/选页」使用。
 * 纯 IO 操作，统一走 Dispatchers.IO。
 */
object QuroMediaSliceHelper {

    /** 读取视频时长（毫秒）。失败返回 0。 */
    fun videoDurationMs(path: String): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
        } finally {
            runCatching { r.release() }
        }
    }.getOrDefault(0L)

    /**
     * 把视频抽成 [frameCount] 帧，保存为 JPEG 文件到 quro_uploads 目录。
     * 返回保存的图片文件路径列表（空表示失败）。
     */
    suspend fun extractVideoFrames(ctx: Context, path: String, frameCount: Int): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val r = MediaMetadataRetriever()
            val out = mutableListOf<String>()
            try {
                r.setDataSource(path)
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (duration <= 0) return@runCatching emptyList<String>()
                val interval = duration / (frameCount + 1)
                val dir = QuroAttachmentKit.uploadsDir(ctx)
                for (i in 1..frameCount) {
                    val timeUs = interval * i
                    val bmp = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val f = File(dir, "frame_${System.nanoTime()}_$i.jpg")
                    f.outputStream().use { os ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, os)
                    }
                    bmp.recycle()
                    out.add(f.absolutePath)
                }
            } finally {
                runCatching { r.release() }
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 读取 PDF 总页数。失败返回 0。 */
    fun pdfPageCount(ctx: Context, path: String): Int = runCatching {
        val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(fd)
            try {
                renderer.pageCount
            } finally {
                renderer.close()
            }
        } finally {
            fd.close()
        }
    }.getOrDefault(0)
}
