package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 端侧 ASR 模型「下载 → 解压 → 自动部署」管理器。
 *
 * 特性：
 *  - 支持多个镜像源 / 自定义链接（URL 由调用方从 QuroOnDeviceModelPrefs 取得）。
 *  - 支持 zip / tar.gz(.tgz) / tar.bz2(.tbz2) / tar 压缩格式（Apache Commons Compress）。
 *  - 下载完成后自动解压到应用私有目录 filesDir/ondevice_model/<模型名>/，
 *    校验 NCNN 的 .ncnn.param/.ncnn.bin + tokens.txt 齐全后，写入部署状态，识别引擎直接可用。
 */
object QuroOnDeviceModelManager {
    private const val TAG = "QuroOnDeviceMgr"

    /**
     * 下载并部署。
     * @param url 模型压缩包地址
     * @param onProgress 已下载字节 / 总字节（总字节 <=0 表示未知）
     * @param onState 状态文案回调（用于 UI 展示）
     * @return 是否部署成功
     */
    suspend fun downloadAndDeploy(
        ctx: Context,
        spec: AsrModelSpec,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        onState: suspend (state: String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val url = spec.downloadUrl
        if (url.isBlank()) { onState("链接为空"); return@withContext false }
        val appCtx = ctx.applicationContext
        val deployKey = QuroOnDeviceModelPrefs.deployedKeyFor(spec.id, url)
        QuroOnDeviceModelPrefs.setActiveKey(appCtx, deployKey)
        QuroOnDeviceModelPrefs.setEntryStatus(appCtx, deployKey, QuroOnDeviceModelPrefs.STATUS_DOWNLOADING)

        val cacheDir = File(appCtx.cacheDir, "ondevice_dl")
        cacheDir.mkdirs()
        val fileName = url.substringAfterLast('/').substringBefore('?')
            .ifBlank { "model.tar.bz2" }
        val tmpFile = File(cacheDir, fileName)

        try {
            onState("正在下载…")
            val total = downloadFile(url, tmpFile, onProgress, onState)
            if (total < 0) {
                fail(appCtx, deployKey, onState, "下载失败（HTTP 非 2xx 或网络错误）")
                return@withContext false
            }
            Log.i(TAG, "下载完成: ${tmpFile.length()} bytes -> $fileName")
            // 关键校验：拒绝 3452 字节之类的假模型（链接失效 / 返回错误页）
            if (spec.minSizeBytes > 0 && tmpFile.length() < spec.minSizeBytes) {
                tmpFile.delete()
                fail(appCtx, deployKey, onState, "下载文件仅 ${tmpFile.length()} 字节，疑似链接失效或返回错误页（需 ≥ ${spec.minSizeBytes} 字节）")
                return@withContext false
            }

            onState("正在解压…")
            val modelRoot = File(appCtx.filesDir, "ondevice_model")
            modelRoot.mkdirs()
            val rawName = fileName.substringBeforeLast('.').substringBeforeLast('.')
                .ifBlank { "model" }
            val modelName = rawName.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val destDir = File(modelRoot, modelName)
            destDir.deleteRecursively()
            destDir.mkdirs()
            extract(tmpFile, destDir)

            onState("正在校验模型文件…")
            val layout = detectAsrLayout(destDir)
            if (layout != AsrModelLayout.NCNN) {
                destDir.deleteRecursively()
                fail(
                    appCtx, deployKey, onState,
                    if (layout == AsrModelLayout.ONNX_LEGACY)
                        "压缩包内是旧 ONNX 模型（model.onnx），与 NCNN 引擎不兼容，请改用 Sherpa-NCNN 模型"
                    else
                        "压缩包内未找到 NCNN 模型文件（需 .ncnn.param / .ncnn.bin + tokens.txt）",
                )
                return@withContext false
            }

            QuroOnDeviceModelPrefs.putDeployedEntry(
                appCtx,
                deployKey,
                QuroOnDeviceModelPrefs.DeployedEntry(
                    destDir.absolutePath,
                    modelName,
                    spec.type.name,
                    QuroOnDeviceModelPrefs.STATUS_DEPLOYED,
                ),
            )
            onState("部署完成：$modelName（类型：${spec.type.label}）")
            Log.i(TAG, "模型部署成功 -> ${destDir.absolutePath} 类型=${spec.type}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "部署失败: ${e.message}", e)
            fail(appCtx, deployKey, onState, "部署失败：${e.message}")
            false
        } finally {
            try { tmpFile.delete() } catch (_: Throwable) {}
        }
    }

    private suspend fun fail(ctx: Context, key: String, onState: suspend (String) -> Unit, msg: String) {
        QuroOnDeviceModelPrefs.setEntryStatus(ctx, key, QuroOnDeviceModelPrefs.STATUS_ERROR)
        onState(msg)
    }

    /** 返回下载总字节数（<0 表示失败）。 */
    private suspend fun downloadFile(
        url: String,
        out: File,
        onProgress: suspend (Long, Long) -> Unit,
        onState: suspend (String) -> Unit,
    ): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 0 // 大文件不限读超时
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) return -1
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        fos.write(buf, 0, n)
                        downloaded += n
                        if (downloaded and 0x3FFFFL == 0L) { // 约每 256KB 回调一次
                            onProgress(downloaded, total)
                            onState("正在下载… ${(downloaded * 100 / (total.coerceAtLeast(1)))}%")
                        }
                    }
                }
            }
            onProgress(downloaded, total)
            return downloaded
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun extract(archive: File, dest: File) {
        val name = archive.name.lowercase()
        val bis = BufferedInputStream(FileInputStream(archive))
        when {
            name.endsWith(".zip") ->
                ZipArchiveInputStream(bis).use { extractZip(it, dest) }
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                TarArchiveInputStream(GzipCompressorInputStream(bis)).use { extractTar(it, dest) }
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") ->
                TarArchiveInputStream(BZip2CompressorInputStream(bis)).use { extractTar(it, dest) }
            name.endsWith(".tar") ->
                TarArchiveInputStream(bis).use { extractTar(it, dest) }
            else -> throw IllegalArgumentException("不支持的压缩格式：$name（仅支持 zip / tar.gz / tar.bz2 / tar）")
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, dest: File) {
        var entry = tar.nextEntry
        while (entry != null) {
            val outFile = safeFile(dest, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (tar.read(buf).also { n = it } > 0) fos.write(buf, 0, n)
                }
            }
            entry = tar.nextEntry
        }
    }

    private fun extractZip(zip: ZipArchiveInputStream, dest: File) {
        var entry = zip.nextEntry
        while (entry != null) {
            val outFile = safeFile(dest, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (zip.read(buf).also { n = it } > 0) fos.write(buf, 0, n)
                }
            }
            entry = zip.nextEntry
        }
    }

    /** 防路径穿越：确保解压目标始终在 dest 之内。 */
    private fun safeFile(dest: File, entryName: String): File {
        val cleaned = entryName.replace('\\', '/').trimStart('/')
        val candidate = File(dest, cleaned).canonicalFile
        val root = dest.canonicalFile
        if (candidate != root && !candidate.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException("非法的压缩条目（路径穿越）：$entryName")
        }
        return candidate
    }

    /**
     * 取当前已部署模型的实际文件。优先按已存类型定位；旧版无类型记录时按布局兜底。
     * @return AsrModelFiles 或 null（未部署 / 文件缺失 / 通用布局无法推断类型）
     */
    fun getDeployedModelFiles(ctx: Context): AsrModelFiles? {
        val dir = QuroOnDeviceModelPrefs.getDeployedDir(ctx.applicationContext) ?: return null
        // 大小兜底：最大文件 < 1MB 直接视为坏目录，返回 null（避免 UI 误判可用而进 ensureLoaded 卡 60s）
        if (deployedDirMaxFileBytes(dir) < MIN_VALID_MODEL_BYTES) return null
        val typeName = QuroOnDeviceModelPrefs.getDeployedType(ctx.applicationContext)
        val type = typeName?.let { runCatching { AsrModelType.valueOf(it) }.getOrNull() }
        return if (type != null) {
            findAsrFiles(File(dir), type)
        } else {
            when (detectAsrLayout(File(dir))) {
                AsrModelLayout.NCNN -> findAsrFiles(File(dir), AsrModelType.SENSE_VOICE)
                AsrModelLayout.ONNX_LEGACY -> null // 旧 ONNX 部署，与 NCNN 引擎不兼容，需重新添加并下载 NCNN 模型
                AsrModelLayout.NONE -> null
            }
        }
    }

    /** 删除已部署模型，释放空间。 */
    fun deleteDeployed(ctx: Context): Boolean {
        val dir = QuroOnDeviceModelPrefs.getDeployedDir(ctx.applicationContext) ?: run {
            QuroOnDeviceModelPrefs.clearDeploy(ctx)
            return true
        }
        return try {
            File(dir).deleteRecursively()
            QuroOnDeviceModelPrefs.clearDeploy(ctx)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
