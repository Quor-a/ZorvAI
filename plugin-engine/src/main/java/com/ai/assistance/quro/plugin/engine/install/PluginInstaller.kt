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

            if (requireSameSignature && !sameSignature(apk))
                return fail(info.packageName, "签名与宿主不一致，拒绝安装")

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

    /** 校验 APK 签名与宿主一致 */
    private fun sameSignature(apk: File): Boolean {
        val apkSig = sigOf(apk) ?: return false
        val hostSig = hostSig() ?: return false
        return apkSig == hostSig
    }

    private fun sigOf(apk: File): String? {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return null
        // ★ 必须先取局部变量再判空：signingInfo 是可变属性，直接判空无法 smart cast
        val si = info.signingInfo
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && si != null)
            si.signingCertificateHistory?.firstOrNull()?.toByteArray()
        else @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        return bytes?.let { sha256(it) }
    }

    private fun hostSig(): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, flags)
            val si = info.signingInfo
            val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && si != null)
                si.signingCertificateHistory?.firstOrNull()?.toByteArray()
            else @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
            bytes?.let { sha256(it) }
        } catch (e: Exception) { null }
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
    }
}
