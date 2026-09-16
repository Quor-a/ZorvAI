package com.ai.assistance.quro.kaleidobox.android

import android.content.Context
import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime
import com.ai.assistance.quro.kaleidobox.core.registry.InstallSource
import com.ai.assistance.quro.kaleidobox.core.registry.PackageRecord
import com.ai.assistance.quro.kaleidobox.core.registry.PackageSource
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 插件包导入器 —— 把"磁盘 zip / 网络链接 / App 内置资产"形式的 Kaleido 包装入运行时。
 *
 * 之前 KaleidoBox 只有 `install(目录)` 与内存示例包，没有 zip/url 导入，
 * 用户拿到的第三方包（.zip，含 kaleido.json + classes.dex）无处可装。这里补齐：
 *  - [ZipPackageSource]：直接以 zip 文件为 [PackageSource]（无需解压到临时目录）；
 *  - [installZipPersisted]：解压到 packagesDir/<id>，用 [DirectoryPackageSource] 安装（重启仍在）；
 *  - [installUrlPersisted]：下载 zip 后再走 [installZipPersisted]；
 *  - [installAssetZip]：从 App 内置资产装载打包好的演示包。
 */
object PackageImporter {

    /** 以 zip 文件为包源（只读，缓存已读条目）。 */
    class ZipPackageSource(private val zip: File) : PackageSource {
        private val cache = mutableMapOf<String, ByteArray>()

        init {
            require(zip.isFile) { "包文件不存在: ${zip.absolutePath}" }
        }

        override fun read(path: String): ByteArray? {
            cache[path]?.let { return it }
            ZipFile(zip).use { zf ->
                zf.getEntry(path)?.let { e ->
                    val b = zf.getInputStream(e).readBytes()
                    cache[path] = b
                    return b
                }
            }
            return null
        }

        override fun exists(path: String): Boolean {
            if (cache.containsKey(path)) return true
            ZipFile(zip).use { zf -> return zf.getEntry(path) != null }
        }

        override fun list(): List<String> = ZipFile(zip).use { zf ->
            zf.entries().toList().filter { !it.isDirectory }.map { it.name }
        }

        override fun digest(path: String): String {
            val b = read(path) ?: return ""
            val md = java.security.MessageDigest.getInstance("SHA-256")
            return md.digest(b).joinToString("") { "%02x".format(it) }
        }
    }

    /** 解压 zip 到目标目录（覆盖写入）。 */
    private fun unzip(zip: File, dir: File) {
        dir.mkdirs()
        ZipInputStream(zip.inputStream()).use { zis ->
            var ze = zis.nextEntry
            while (ze != null) {
                val name = ze.name.replace('\\', '/')
                if (name.endsWith("/") || name.contains("..")) { ze = zis.nextEntry; continue }
                val out = File(dir, name)
                out.parentFile?.mkdirs()
                out.outputStream().use { os -> zis.copyTo(os) }
                ze = zis.nextEntry
            }
        }
    }

    /** 从本地 zip 文件装入并持久化（解压到 packagesDir/<id>）。 */
    fun installZipPersisted(
        ctx: Context,
        zip: File,
        options: KaleidoRuntime.InstallOptions = KaleidoRuntime.InstallOptions(source = InstallSource.SIDELOAD),
    ): PackageRecord {
        val meta = readPackageId(zip)
            ?: throw KaleidoException.Install(zip.name, "缺少 kaleido.json，不是合法 Kaleido 包")
        val dir = File(KaleidoBoxHost.get().packagesDir, meta).apply { mkdirs() }
        unzip(zip, dir)
        val src = com.ai.assistance.quro.kaleidobox.core.registry.DirectoryPackageSource(dir)
        if (src.read("kaleido.json") == null) {
            dir.deleteRecursively()
            throw KaleidoException.Install(meta, "解压后缺少 kaleido.json")
        }
        return KaleidoBoxHost.get().runtime.install(src, options)
    }

    /** 从网络链接下载 zip 并装入（持久化）。 */
    fun installUrlPersisted(
        ctx: Context,
        url: String,
        options: KaleidoRuntime.InstallOptions = KaleidoRuntime.InstallOptions(source = InstallSource.SIDELOAD),
    ): PackageRecord {
        val cache = File(ctx.cacheDir, "kaleidobox/imports").apply { mkdirs() }
        val name = url.substringAfterLast('/').substringBefore('?')
            .ifBlank { "plugin_${System.currentTimeMillis()}.zip" }
            .let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val target = File(cache, name)
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.inputStream.use { ins -> target.outputStream().use { os -> ins.copyTo(os) } }
        if (target.length() == 0L) throw KaleidoException.Install(url, "下载失败（空文件）")
        return installZipPersisted(ctx, target, options)
    }

    /** 从 App 内置资产装载打包好的演示包（持久化）。 */
    fun installAssetZip(
        ctx: Context,
        assetRelPath: String,
        options: KaleidoRuntime.InstallOptions = KaleidoRuntime.InstallOptions(source = InstallSource.BUILTIN),
    ): PackageRecord {
        val cache = File(ctx.cacheDir, "kaleidobox/assetpkgs").apply { mkdirs() }
        val name = assetRelPath.substringAfterLast('/')
        val target = File(cache, name)
        ctx.assets.open(assetRelPath).use { ins -> target.outputStream().use { os -> ins.copyTo(os) } }
        return installZipPersisted(ctx, target, options)
    }

    /** 仅读出包 id（不安装），用于决定持久化目录。 */
    private fun readPackageId(zip: File): String? {
        val bytes = ZipPackageSource(zip).read("kaleido.json") ?: return null
        val text = bytes.toString(Charsets.UTF_8)
        val m = com.ai.assistance.quro.kaleidobox.core.util.Json.parse(text) as? Map<*, *>
        return (m?.get("id") as? String)?.takeIf { it.isNotBlank() }
    }
}
