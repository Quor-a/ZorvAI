package com.zorv.plugin.signcheck

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 一个 APK 的签名快照。
 *
 * [fingerprints] 为空表示「未签名 / 读不出签名」，而不是空字符串——
 * 未签名与签了别的钥匙，在宿主看来都会以「签名与宿主不一致」被拒绝，
 * 但对排查来说必须能区分，所以这里显式保留空集合。
 *
 * 之所以是**集合**：AOSP `SigningInfo` 的签名历史在钥匙轮换后会包含多张证书，
 * 宿主侧判定的是「插件有任意一张证书出现在宿主证书集合里」。
 */
data class ApkSig(
    val title: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val fingerprints: List<String>,
    val note: String? = null,
) {
    val signed: Boolean get() = fingerprints.isNotEmpty()

    /** 列表展示用：取第一张 */
    val fingerprint: String? get() = fingerprints.firstOrNull()

    /** 指纹前 6 组十六进制，列表里够辨识又不刷屏 */
    val short: String
        get() = fingerprint?.split(":")?.take(6)?.joinToString(":") ?: "（未签名）"

    /** 与宿主完全一致的判定：任意一张证书相同即同源 */
    fun isSameAs(other: ApkSig): Boolean =
        signed && other.signed && fingerprints.any { it in other.fingerprints }
}

/**
 * 签名核验逻辑。
 *
 * ★ 指纹口径与宿主 `PluginInstaller.sameSignature()` **完全一致**：
 *   两侧都从**各自的 APK 文件**出发，按同一套来源收证书集合——
 *     ① signingInfo.apkContentsSigners（当前签名者，v2/v3）
 *     ② signingInfo.signingCertificateHistory（完整历史，含轮换前的旧证书）
 *     ③ info.signatures（旧 API 的 V1 口径）
 *     ④ 兜底：直接读 APK 内 META-INF 目录下 .RSA / .DSA / .EC 里的 V1 证书
 *   只要插件有任意一张证书出现在宿主证书集合里，即视为同源。
 *
 *   口径一旦与宿主不同，插件报「一致」而宿主拒绝安装，用户就没法用它排查问题了。
 */
object SignCheck {

    // ---------------- 底层：证书指纹 ----------------

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

    private val sigEntryRe = Regex("META-INF/.*\\.(RSA|DSA|EC)", RegexOption.IGNORE_CASE)

    private const val ID_V2 = 0x7109871aL
    private const val ID_V3 = 0xf05368c0L
    private const val ID_V3_1 = 0x1b93ad61L

    /**
     * 收集一个 APK 上所有可见的签名证书 SHA-256（大写冒号分隔），口径与宿主完全一致。
     *
     * 三级来源逐级兜底——**不能只信 PackageManager**：实测有 ROM（Android 16）对
     * 「只带 v2/v3、不带 V1」的 APK 压根不填 `signingInfo`，只靠框架会把签名正常的
     * APK 也判成「读不出证书」。宿主侧已是同一套逻辑，两边结果才能对得上。
     */
    private fun certsOf(ctx: Context, apkFile: File): List<String> {
        val out = certsFromFramework(ctx, apkFile).toMutableSet()
        if (out.isEmpty()) out += certsFromSigningBlock(apkFile)
        if (out.isEmpty()) out += certsFromJarV1(apkFile)
        return out.toList()
    }

    /** 来源①：框架 */
    private fun certsFromFramework(ctx: Context, apkFile: File): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            if (info != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val si = info.signingInfo            // 先落局部变量再判空
                    si?.apkContentsSigners?.forEach { out += sha256(it.toByteArray()) }
                    si?.signingCertificateHistory?.forEach { out += sha256(it.toByteArray()) }
                }
                @Suppress("DEPRECATION")
                info.signatures?.forEach { out += sha256(it.toByteArray()) }
            }
        }
        return out
    }

    /**
     * 来源②：自己解 APK Signing Block（v2 / v3 / v3.1）。
     *
     * 布局：[uint64 size][pair…][uint64 size][16 字节 "APK Sig Block 42"]，紧贴中央目录之前。
     * size 的值 = 块总长 - 8，故 pairs 区间为 [cdOffset - size, cdOffset - 24)。
     */
    private fun certsFromSigningBlock(apkFile: File): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            java.io.RandomAccessFile(apkFile, "r").use { raf ->
                val len = raf.length()
                if (len < 64) return@use
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
                val cdOffset = u32(tail, eocd + 16).toLong() and 0xFFFFFFFFL
                if (cdOffset < 32 || cdOffset > len) return@use
                val magic = ByteArray(16)
                raf.seek(cdOffset - 16)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "APK Sig Block 42") return@use
                val sizeBuf = ByteArray(8)
                raf.seek(cdOffset - 24)
                raf.readFully(sizeBuf)
                val blockSize = u64(sizeBuf, 0)
                val pairsStart = cdOffset - blockSize
                val pairsEnd = cdOffset - 24
                if (pairsStart < 0 || pairsStart >= pairsEnd ||
                    pairsEnd - pairsStart > 64L * 1024L * 1024L
                ) return@use
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
        }
        return out
    }

    /** v2/v3 的 signers → signed data 开头的 digests + certificates（这一层 v2/v3 布局相同） */
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

    /** 来源③：V1(JAR) 证书 */
    private fun certsFromJarV1(apkFile: File): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            java.util.jar.JarFile(apkFile).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.startsWith("META-INF/") && sigEntryRe.matches(it.name) }
                    .forEach { e ->
                        val f = java.security.cert.CertificateFactory.getInstance("X.509")
                        jar.getInputStream(e).use { f.generateCertificates(it) }
                            .forEach { out += sha256(it.encoded) }
                    }
            }
        }
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

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    // ---------------- 查询 ----------------

    /** 宿主自身：走 sourceDir，与插件完全同一条读取路径 */
    fun hostSig(ctx: Context): ApkSig = try {
        val dir = ctx.packageManager.getApplicationInfo(ctx.packageName, 0).sourceDir
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        ApkSig(
            title = "宿主",
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = versionCodeOf(info),
            fingerprints = certsOf(ctx, File(dir)),
        )
    } catch (t: Throwable) {
        ApkSig("宿主", ctx.packageName, null, null, emptyList(), "读取宿主签名失败：${t.message}")
    }

    /** 设备上任意路径的 APK */
    fun apkAt(ctx: Context, apk: File, title: String = apk.name): ApkSig {
        if (!apk.isFile) {
            return ApkSig(title, null, null, null, emptyList(), "文件不存在：${apk.absolutePath}")
        }
        return try {
            val fps = certsOf(ctx, apk)
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ApkSig(
                title = title,
                packageName = info?.packageName,
                versionName = info?.versionName,
                versionCode = info?.let { versionCodeOf(it) },
                fingerprints = fps,
                note = when {
                    fps.isEmpty() -> "该 APK 未签名 / 读不出证书（宿主会拒绝安装）"
                    else -> null
                },
            )
        } catch (t: Throwable) {
            ApkSig(title, null, null, null, emptyList(), "读取失败：${t.message}")
        }
    }

    /** 本插件自身的 APK 路径：<宿主 files>/plugins/<pluginId>/active/base.apk */
    fun selfApk(ctx: Context, pluginId: String): File =
        File(File(ctx.filesDir, "plugins"), "$pluginId/active/base.apk")

    /** 已装插件清单：pluginId → base.apk */
    fun installedPluginApks(ctx: Context): List<Pair<String, File>> {
        val root = File(ctx.filesDir, "plugins")
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { d ->
                File(d, "active/base.apk").takeIf { it.isFile }?.let { d.name to it }
            }
            .sortedBy { it.first }
    }

    // ---------------- 文案 ----------------

    /** 单条详情 */
    fun detail(sig: ApkSig): String = buildString {
        append("名称：").append(sig.title).append('\n')
        append("包名：").append(sig.packageName ?: "—").append('\n')
        append("版本：").append(sig.versionName ?: "—")
        append(" (").append(sig.versionCode?.toString() ?: "—").append(")\n")
        if (sig.fingerprints.isEmpty()) {
            append("签名：未签名 / 无法解析")
        } else {
            sig.fingerprints.forEachIndexed { i, fp ->
                append("签名").append(if (sig.fingerprints.size > 1) "[${i + 1}]" else "")
                    .append("：").append(fp).append('\n')
            }
            if (sig.fingerprints.size > 1) {
                append("（多张 = 钥匙轮换过的签名历史；宿主判定为「任意一张相同即同源」）")
            }
        }
        sig.note?.let { append('\n').append("备注：").append(it) }
    }

    /** 一致性结论（口径与宿主一致：证书集合有交集即同签名） */
    fun verdict(a: ApkSig, b: ApkSig): String = when {
        !a.signed && !b.signed -> "两侧都读不出签名（可能都未签名）"
        !a.signed -> "${a.title} 未签名 → 不一致"
        !b.signed -> "${b.title} 未签名 → 不一致"
        a.isSameAs(b) -> "一致 → 宿主会放行安装"
        else -> "不一致 → 宿主会以「签名与宿主不一致，拒绝安装」拒绝"
    }

    /** 完整核验报告（AI 工具与 /sigcheck 共用） */
    fun fullReport(ctx: Context, pluginId: String): String = buildString {
        val host = hostSig(ctx)
        val self = apkAt(ctx, selfApk(ctx, pluginId), "本插件")

        append("═══ 宿主 ═══\n")
        append(detail(host)).append("\n\n")

        append("═══ 本插件 ═══\n")
        append(detail(self)).append('\n')
        append("结论：").append(verdict(host, self)).append("\n\n")

        val others = installedPluginApks(ctx).filter { it.first != pluginId }
        append("═══ 其他已装插件（").append(others.size).append(" 个）═══\n")
        if (others.isEmpty()) {
            append("（无）")
        } else {
            others.forEach { (id, f) ->
                val s = apkAt(ctx, f, id)
                append(if (s.isSameAs(host)) "  一致  " else "  不一致  ")
                append(id)
                append("  v").append(s.versionName ?: "?")
                append("  ").append(s.short)
                append('\n')
            }
        }
    }
}
