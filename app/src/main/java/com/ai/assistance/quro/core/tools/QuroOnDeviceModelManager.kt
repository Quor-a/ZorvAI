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
 *    校验流式 transducer 三件套（encoder/decoder/joiner 的 .ncnn.param + .ncnn.bin）
 *    与 tokens.txt 齐全后，写入部署状态，识别引擎直接可用。
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
            // 错误页下限校验：仅拒绝明显坏文件（链接失效 / 返回几 KB 错误页）。
            // 注意：这里用「下载压缩包体积」下限（默认 1MB，见 AsrModelSpec.minSizeBytes），
            // 不再用解压后的模型体积去卡压缩包；模型是否真的可用以解压后的 NCNN 布局校验为准。
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
            if (layout != AsrModelLayout.TRANSDUCER) {
                destDir.deleteRecursively()
                fail(
                    appCtx, deployKey, onState,
                    when (layout) {
                        AsrModelLayout.ONNX_LEGACY ->
                            "压缩包内是 ONNX 模型，与本机 NCNN 引擎不兼容，请改用 Sherpa-NCNN 流式模型"
                        AsrModelLayout.SENSE_VOICE_LEGACY ->
                            "压缩包内是旧版 SenseVoice 布局，缺少 encoder/decoder/joiner 三件套，引擎无法加载"
                        else ->
                            "压缩包内未找到流式模型文件（需 encoder/decoder/joiner 的 .ncnn.param + .ncnn.bin 与 tokens.txt）"
                    },
                )
                return@withContext false
            }
            // 三件套齐全但成对关系可能残缺（例如 .param 有而 .bin 缺），这里再做一次严格定位
            if (findAsrFiles(destDir, AsrModelType.STREAMING_TRANSDUCER) == null) {
                destDir.deleteRecursively()
                fail(appCtx, deployKey, onState, "模型文件不完整：encoder / decoder / joiner 的 .param 与 .bin 必须成对存在，且需要 tokens.txt")
                return@withContext false
            }

            QuroOnDeviceModelPrefs.putDeployedEntry(
                appCtx,
                deployKey,
                QuroOnDeviceModelPrefs.DeployedEntry(
                    destDir.absolutePath,
                    modelName,
                    AsrModelType.STREAMING_TRANSDUCER.name,
                    QuroOnDeviceModelPrefs.STATUS_DEPLOYED,
                ),
            )
            onState("部署完成：$modelName（占用 ${formatBytes(deployedDirTotalBytes(destDir.absolutePath))}）")
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
        // 类型记录只作参考：旧安装可能写着 SENSE_VOICE 之类的历史值，一律以磁盘实际布局为准
        return when (detectAsrLayout(File(dir))) {
            AsrModelLayout.TRANSDUCER -> findAsrFiles(File(dir), AsrModelType.STREAMING_TRANSDUCER)
            // 旧 ONNX / 旧 SenseVoice 部署与当前引擎不兼容，需重新下载流式模型
            AsrModelLayout.ONNX_LEGACY -> null
            AsrModelLayout.SENSE_VOICE_LEGACY -> null
            AsrModelLayout.NONE -> null
        }
    }

    /**
     * 已部署目录的历史遗留判定：目录里是引擎跑不动的旧模型（旧 SenseVoice / ONNX）。
     * 设置页据此提示用户「一键换成推荐模型」，而不是让他对着「识别没反应」干瞪眼。
     */
    fun isLegacyIncompatible(ctx: Context): Boolean {
        val dir = QuroOnDeviceModelPrefs.getDeployedDir(ctx.applicationContext) ?: return false
        return when (detectAsrLayout(File(dir))) {
            AsrModelLayout.SENSE_VOICE_LEGACY, AsrModelLayout.ONNX_LEGACY -> true
            else -> false
        }
    }

    /** 已部署模型占用的磁盘空间（字节）；未部署返回 0。 */
    fun deployedSizeBytes(ctx: Context): Long =
        deployedDirTotalBytes(QuroOnDeviceModelPrefs.getDeployedDir(ctx.applicationContext))

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

    /**
     * 校验已部署目录的磁盘完整性（二次进入设置页时调用，解决「记录写着已部署、但文件被删/损坏」
     * 导致 UI 误判可用或不断重复下载的问题）。
     *
     * 判定可用：目录存在 + 目录内最大文件 ≥ [MIN_VALID_MODEL_BYTES]（排除错误页/空目录）
     * + 布局为 [AsrModelLayout.TRANSDUCER] 且三件套成对齐全。全部满足才视为「已可用、无需重下」。
     */
    fun verifyDeployedDir(dir: String?): Boolean {
        val d = dir?.let { File(it) } ?: return false
        if (!d.isDirectory) return false
        if (deployedDirMaxFileBytes(dir) < MIN_VALID_MODEL_BYTES) return false
        if (detectAsrLayout(d) != AsrModelLayout.TRANSDUCER) return false
        return findAsrFiles(d, AsrModelType.STREAMING_TRANSDUCER) != null
    }
}
