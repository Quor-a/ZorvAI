package com.ai.assistance.quro.terminal.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载 rootfs（首次使用按需下载，如 LANXIU 约 88MB）。
 * 用 HttpURLConnection 带进度回调，复用与 UpdateChecker 相同的原生网络栈（不依赖额外库）。
 */
object RootfsDownloader {
    private const val TAG = "RootfsDownloader"

    /**
     * @param url 下载地址（支持 302 跳转，GitHub Release 资产会跳到 objects.githubusercontent.com）
     * @param dest 目标文件
     * @param onProgress 进度回调（downloaded, total, fraction）；无法取得总长度时 total=-1、fraction=-1f
     * @return 是否成功
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: ((downloaded: Long, total: Long, fraction: Float) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val u = URL(url)
            connection = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ZorvAI-Terminal")
            }
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "下载失败 HTTP $code for $url")
                return@withContext false
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloaded = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        val fraction = if (total > 0) (downloaded.toFloat() / total) else -1f
                        onProgress?.invoke(downloaded, total, fraction)
                    }
                    output.flush()
                }
            }
            Log.d(TAG, "下载完成: ${dest.absolutePath} ($downloaded bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "下载异常: ${e.message}", e)
            runCatching { dest.delete() } // 失败清理半截文件，避免下次误判为已就绪
            false
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
