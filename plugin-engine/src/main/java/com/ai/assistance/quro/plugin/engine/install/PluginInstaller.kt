package com.ai.assistance.quro.plugin.engine.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ai.assistance.quro.plugin.engine.core.QuroPluginEngine
import com.ai.assistance.quro.plugin.engine.util.Digest
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class PluginRecord(
    val pluginId: String,
    val name: String,
    val versionCode: Int,
    val versionName: String,
    val entryClass: String,
    val apkPath: String,
    val nativeLibDir: String,
    val installedAt: Long,
    val apkMd5: String
)

data class InstallResult(
    val success: Boolean, val pluginId: String, val message: String
)

/**
 * 插件安装器。
 *
 * 安全基线（插件拥有宿主同等权限，必须严格）：
 *  1. APK 必须声明 quro.plugin.entry
 *  2. 必须与宿主同签名（requireSameSignature，生产环境必须开启）
 *  3. 只存放在 /data/data/<host>/files，绝不放外置存储（可被其他 App 篡改）
 *  4. 原子替换，失败自动回滚
 */
internal class PluginInstaller(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rootDir() = File(context.filesDir, "plugins").apply { mkdirs() }

    fun install(apk: File, requireSameSignature: Boolean): InstallResult {
        var tmp: File? = null
        return try {
            if (!apk.exists() || apk.length() == 0L)
                return fail("", "文件不存在或为空")

            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES
            ) ?: return fail("", "不是合法 APK 文件")

            val entry = info.applicationInfo?.metaData
                ?.getString(QuroPluginEngine.META_ENTRY)
                ?: return fail(info.packageName, "未声明 meta-data: ${QuroPluginEngine.META_ENTRY}")

            if (requireSameSignature) {
                val v = sameSignature(apk)
                if (!v.ok) return fail(
                    info.packageName,
                    "签名与宿主不一致，拒绝安装\n· 原因：${v.detail}"
                )
            }

            val pluginId = info.packageName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
            val versionName = info.versionName ?: "1.0.0"
            val name = info.applicationInfo?.loadLabel(pm)?.toString() ?: pluginId
            val md5 = Digest.md5(apk)

            // 原子替换：先写 tmp，成功后 rename 到 active
            val active = File(rootDir(), "$pluginId/active")
            val backup = File(rootDir(), "$pluginId/.bak")
            tmp = File(rootDir(), "$pluginId/.tmp").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val targetApk = File(tmp, "base.apk")
            apk.copyTo(targetApk, overwrite = true)
            targetApk.setReadOnly()

            val libDir = File(tmp, "lib").apply { mkdirs() }
            extractNativeLibs(targetApk, libDir)

            if (backup.exists()) backup.deleteRecursively()
            if (active.exists()) active.renameTo(backup)
            if (!tmp.renameTo(active)) {
                if (backup.exists()) backup.renameTo(active)
                return fail(pluginId, "写入安装目录失败")
            }
            backup.deleteRecursively()
            tmp = null

            val record = PluginRecord(
                pluginId = pluginId, name = name,
                versionCode = versionCode, versionName = versionName,
                entryClass = entry,
                apkPath = File(active, "base.apk").absolutePath,
                nativeLibDir = File(active, "lib").absolutePath,
                installedAt = System.currentTimeMillis(),
                apkMd5 = md5
            )
            save(record)
            android.util.Log.i(TAG, "插件安装成功：$pluginId v$versionName entry=$entry")
            InstallResult(true, pluginId, "安装成功")
        } catch (t: Throwable) {
            tmp?.deleteRecursively()
            android.util.Log.e(TAG, "安装失败", t)
            InstallResult(false, "", t.message ?: "未知错误")
        }
    }

    fun uninstall(pluginId: String): Boolean {
        File(rootDir(), pluginId).deleteRecursively()
        prefs.edit().remove(KEY_PREFIX + pluginId).apply()
        return true
    }

    fun get(pluginId: String): PluginRecord? =
        prefs.getString(KEY_PREFIX + pluginId, null)?.let { fromJson(it) }

    fun list(): List<PluginRecord> = prefs.all.keys
        .filter { it.startsWith(KEY_PREFIX) }
        .mapNotNull { prefs.getString(it, null) }
        .mapNotNull { runCatching { fromJson(it) }.getOrNull() }

    private fun save(r: PluginRecord) {
        prefs.edit().putString(KEY_PREFIX + r.pluginId, toJson(r).toString()).apply()
    }

    /** 签名比对结论；[detail] 用于把失败原因带回给用户，别再让用户盲猜 */
    internal data class SigVerdict(val ok: Boolean, val detail: String)

    /**
     * 校验 APK 签名与宿主一致。
     *
     * ★ 设计要点一：宿主与插件必须用**同一把尺子**量。
     *   两侧都从**各自的 APK 文件**出发（宿主用 `applicationInfo.sourceDir`），走同一套读取逻辑。
     *
     * ★ 设计要点二：**不能只信 PackageManager**。
     *   实测有 ROM（Android 16）对「只带 v2/v3、不带 V1」的 APK，
     *   `getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)` 压根不填 `signingInfo`，
     *   于是签名完全正常的插件也会被判「读不出证书」→ 拒绝安装。
     *   所以这里改成三级来源、逐级兜底，任一级拿到证书即可：
     *     ① 框架：`apkContentsSigners` + `signingCertificateHistory` + `signatures`
     *     ② 自己解 APK Signing Block（v2 / v3 / v3.1）—— 不依赖框架，权威兜底
     *     ③ 自己读 V1(JAR)：APK 内 META-INF 目录下 .RSA / .DSA / .EC
     *
     * ★ 判定：插件有**任意一张**证书出现在宿主证书集合里即视为同源
     *   （AOSP `SigningInfo` 文档给的判据："check ... is one of the entries"），
     *   这样钥匙轮换过的宿主也能正确放行。
     */
    private fun sameSignature(apk: File): SigVerdict {
        val hostCerts = hostSigCerts()
        val apkCerts = sigCerts(apk)
        return when {
            hostCerts.isEmpty() -> SigVerdict(
                false, "读不出宿主证书（改动前请确认宿主包完好）"
            )
            apkCerts.isEmpty() -> SigVerdict(
                false,
                "文件「${apk.name}」里读不到任何签名证书\n" +
                    "· 框架未返回 signingInfo，APK 内也没有 v2/v3 签名块、没有 V1 证书\n" +
                    "→ 这个 APK 本身就是未签名的，或签名块已损坏"
            )
            apkCerts.any { it in hostCerts } -> SigVerdict(true, apkCerts.first())
            else -> SigVerdict(
                false,
                "插件证书 ${apkCerts.joinToString()}\n宿主证书 ${hostCerts.joinToString()}"
            )
        }
    }

    /** 宿主自身 APK：直接拿 sourceDir，走与插件完全相同的读取路径 */
    private fun hostSigCerts(): Set<String> = try {
        val dir = context.packageManager
            .getApplicationInfo(context.packageName, 0).sourceDir
        sigCerts(File(dir))
    } catch (e: Exception) {
        android.util.Log.w(TAG, "读取宿主证书失败", e)
        emptySet()
    }

    private val sigEntryRe = Regex("META-INF/.*\\.(RSA|DSA|EC)", RegexOption.IGNORE_CASE)

    /** 收集一个 APK 上所有可见的签名证书 SHA-256（大写冒号分隔）。三级来源依次兜底 */
    private fun sigCerts(apkFile: File): Set<String> {
        val out = certsFromFramework(apkFile).toMutableSet()
        if (out.isEmpty()) out += certsFromSigningBlock(apkFile)
        if (out.isEmpty()) out += certsFromJarV1(apkFile)
        return out
    }

    /** 来源①：PackageManager 解析。同时请求两个 flag，让能填的都填上 */
    private fun certsFromFramework(apkFile: File): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            if (info != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val si = info.signingInfo           // 先落局部变量再判空，否则无法 smart cast
                    si?.apkContentsSigners?.forEach { out += sha256(it.toByteArray()) }
                    si?.signingCertificateHistory?.forEach { out += sha256(it.toByteArray()) }
                }
                @Suppress("DEPRECATION")
                info.signatures?.forEach { out += sha256(it.toByteArray()) }
            }
        }.onFailure { android.util.Log.w(TAG, "框架读取签名失败：${apkFile.name}", it) }
        return out
    }

    /**
     * 来源②：绕开 PackageManager，直接解 APK Signing Block（v2 / v3 / v3.1）里的签名证书。
     *
     * 结构（Signing Block 紧贴在 ZIP 中央目录之前）：
     *   [uint64 块长度] [pair…] [uint64 块长度] [16 字节魔数 "APK Sig Block 42"]
     *   pair = [uint64 长度][uint32 id][value]
     * value(v2/v3) 里的 signers → signed data 开头依次是 digests 与 certificates，
     * 两者都是「先 uint32 长度、再若干 length-prefixed 项」，这一层 v2 与 v3 完全一致，
     * 所以只解到证书为止即可，不需要区分后续的 minSdk / signatures / publicKey 布局。
     */
    private fun certsFromSigningBlock(apkFile: File): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            java.io.RandomAccessFile(apkFile, "r").use { raf ->
                val len = raf.length()
                if (len < 64) return@use

                // 1) 尾部回扫 EOCD（签名 0x06054b50），取最靠后的那个
                val tailLen = minOf(len, 66L * 1024L).toInt()
                val tail = ByteArray(tailLen)
                raf.seek(len - tailLen)
                raf.readFully(tail)
                var eocd = -1
                var i = tail.size - 22
                while (i >= 0) {
                    if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                        tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                    ) { eocd = i; break }
                    i--
                }
                if (eocd < 0) return@use

                // EOCD: 签名(4) 盘号(2) 目录盘号(2) 本盘条目(2) 总条目(2) 目录大小(4) 目录偏移(4) 注释长(2)
                val cdOffset = u32(tail, eocd + 16).toLong() and 0xFFFFFFFFL
                if (cdOffset < 32 || cdOffset > len) return@use

                // 2) 签名块结尾 16 字节必须是魔数
                val magic = ByteArray(16)
                raf.seek(cdOffset - 16)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "APK Sig Block 42") return@use

                val sizeBuf = ByteArray(8)
                raf.seek(cdOffset - 24)
                raf.readFully(sizeBuf)
                // size 字段的值 = 块总长 - 8（不含开头那个 uint64）。
                // 所以 pairs 从 cdOffset - size 开始，到尾部 size 字段（cdOffset - 24）为止。
                val blockSize = u64(sizeBuf, 0)
                val pairsStart = cdOffset - blockSize
                val pairsEnd = cdOffset - 24
                if (pairsStart < 0 || pairsStart >= pairsEnd || pairsEnd - pairsStart > 64L * 1024L * 1024L) {
                    return@use
                }

                // 3) 遍历 pair，命中 v2 / v3 / v3.1 就取证书
                val buf = ByteArray((pairsEnd - pairsStart).toInt())
                raf.seek(pairsStart)
                raf.readFully(buf)
                var p = 0
                while (p + 12 <= buf.size) {
                    val pairLen = u64(buf, p)
                    if (pairLen < 4 || p + 8 + pairLen > buf.size) break
                    val id = u32(buf, p + 8).toLong() and 0xFFFFFFFFL
                    if (id == ID_V2 || id == ID_V3 || id == ID_V3_1) {
                        out += certsInSignerBlock(buf, p + 12, (p + 8 + pairLen).toInt())
                    }
                    p += 8 + pairLen.toInt()
                }
            }
        }.onFailure { android.util.Log.w(TAG, "解析 Signing Block 失败：${apkFile.name}", it) }
        return out
    }

    /** 从 v2/v3 的 signers 数据里提取证书（只走 signed data 开头的 digests + certificates） */
    private fun certsInSignerBlock(buf: ByteArray, start: Int, end: Int): Set<String> {
        val out = LinkedHashSet<String>()
        if (start + 4 > end) return out
        var p = start
        val signersLen = u32(buf, p); p += 4
        val signersEnd = minOf(p + signersLen, end)
        while (p + 4 <= signersEnd) {
            val signerLen = u32(buf, p); p += 4
            val signerEnd = minOf(p + signerLen, signersEnd)
            if (p + 4 <= signerEnd) {
                val sdLen = u32(buf, p); p += 4
                val sdEnd = minOf(p + sdLen, signerEnd)
                if (p + 4 <= sdEnd) {
                    val digestsLen = u32(buf, p); p += 4 + digestsLen
                    if (p + 4 <= sdEnd) {
                        val certsLen = u32(buf, p); p += 4
                        val certsEnd = minOf(p + certsLen, sdEnd)
                        while (p + 4 <= certsEnd) {
                            val cl = u32(buf, p); p += 4
                            if (cl <= 0 || p + cl > certsEnd) break
                            out += sha256(buf.copyOfRange(p, p + cl))
                            p += cl
                        }
                    }
                }
            }
            p = signerEnd
        }
        return out
    }

    /** 来源③：自己读 V1(JAR) 证书，APK 内 META-INF 目录下 .RSA / .DSA / .EC */
    private fun certsFromJarV1(apkFile: File): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            java.util.jar.JarFile(apkFile).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.startsWith("META-INF/") && sigEntryRe.matches(it.name) }
                    .forEach { e ->
                        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
                        val certs = jar.getInputStream(e).use { factory.generateCertificates(it) }
                        certs.forEach { out += sha256(it.encoded) }
                    }
            }
        }.onFailure { android.util.Log.w(TAG, "V1 兜底读签名失败：${apkFile.name}", it) }
        return out
    }

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString(":") { b -> "%02X".format(b) }

    private fun extractNativeLibs(apk: File, outDir: File) {
        val abis = Build.SUPPORTED_ABIS.toList()
        ZipFile(apk).use { zip ->
            val entries = zip.entries().toList().filter {
                !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so")
            }
            if (entries.isEmpty()) return
            val abi = abis.firstOrNull { a -> entries.any { it.name.startsWith("lib/$a/") } }
                ?: entries.first().name.split("/")[1]
            entries.filter { it.name.startsWith("lib/$abi/") }.forEach { e ->
                File(outDir, e.name.removePrefix("lib/$abi/")).apply {
                    parentFile?.mkdirs()
                    zip.getInputStream(e).use { i -> FileOutputStream(this).use { o -> i.copyTo(o) } }
                }
            }
        }
    }

    // ---------- 序列化 ----------
    private fun toJson(r: PluginRecord) = JSONObject().apply {
        put("pluginId", r.pluginId); put("name", r.name)
        put("versionCode", r.versionCode); put("versionName", r.versionName)
        put("entryClass", r.entryClass); put("apkPath", r.apkPath)
        put("nativeLibDir", r.nativeLibDir)
        put("installedAt", r.installedAt); put("apkMd5", r.apkMd5)
    }

    private fun fromJson(s: String) = JSONObject(s).let { o ->
        PluginRecord(
            o.getString("pluginId"), o.getString("name"),
            o.getInt("versionCode"), o.optString("versionName", "1.0"),
            o.getString("entryClass"), o.getString("apkPath"),
            o.optString("nativeLibDir", ""), o.optLong("installedAt", 0),
            o.optString("apkMd5", "")
        )
    }

    private fun fail(id: String, msg: String) = InstallResult(false, id, msg).also {
        android.util.Log.e(TAG, "安装失败[$id]: $msg")
    }

    companion object {
        private const val PREFS = "quro_plugins"
        private const val KEY_PREFIX = "record_"
        private const val TAG = "PluginInstaller"

        /** APK Signing Block 里各签名方案对应的 pair id */
        private const val ID_V2 = 0x7109871aL
        private const val ID_V3 = 0xf05368c0L
        private const val ID_V3_1 = 0x1b93ad61L
    }
}
