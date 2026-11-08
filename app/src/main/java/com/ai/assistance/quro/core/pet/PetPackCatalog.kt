package com.ai.assistance.quro.core.pet

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内宠物包中心。
 *
 * 内置目录项一键安装，或直接在应用内选 zip / 填链接安装；
 * 安装完成即写 pet_pack_dir 并广播刷新，桌宠立即换形象。
 *
 * 链接安装走完整的下载 → 大小与 sha256 校验 → 解压 → 选中流程。
 */
object PetPackCatalog {

    /** 下载包上限，与解压上限保持一致，避免超大压缩包把缓存写爆。 */
    const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * 从系统文件选择器返回的 Uri（content:// / file://）直接安装。
     * 在 IO 线程调用；返回可传给 PetPrefs.packDir 的目录名。
     */
    fun installFromUri(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法打开所选文件")
        return stream.use { PetPack.importZip(context, it, replaceExisting = true).dirName }
    }

    /** 一键安装随 APK 内置的宠物包；force=true 强制重装。 */
    fun installBuiltin(
        context: Context,
        assetDir: String,
        id: String,
        force: Boolean = false,
    ): String = PetPackAssets.ensurePack(context, assetDir, id, force)
        ?: throw IllegalStateException("内置宠物包安装失败")

    /**
     * 从 http/https 链接下载宠物包并安装。
     * expectedSha256 非空时强制校验，避免下载到被篡改的包。
     */
    fun installFromUrl(context: Context, url: String, expectedSha256: String? = null): String {
        val target = runCatching { URL(url.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("链接格式不正确")
        require(target.protocol.equals("https", true) || target.protocol.equals("http", true)) {
            "仅支持 http/https 链接"
        }
        val temp = File(context.cacheDir, "pet-download-${System.currentTimeMillis()}.zip")
        try {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/zip, application/octet-stream")
            }
            try {
                conn.connect()
                val code = conn.responseCode
                require(code in 200..299) { "下载失败（HTTP $code）" }
                val declared = conn.contentLengthLong
                if (declared > 0) {
                    require(declared <= MAX_DOWNLOAD_BYTES) { "下载的宠物包超过 200MB" }
                }
                conn.inputStream.use { input ->
                    temp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_DOWNLOAD_BYTES) { "下载的宠物包超过 200MB" }
                            out.write(buf, 0, n)
                        }
                        require(total > 0) { "下载的宠物包是空的" }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (!expectedSha256.isNullOrBlank()) {
                val actual = sha256(temp)
                require(actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    "下载的宠物包校验失败（sha256 不符）"
                }
            }
            return temp.inputStream().use { PetPack.importZip(context, it, replaceExisting = true).dirName }
        } finally {
            temp.delete()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
