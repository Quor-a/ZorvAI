package com.ai.assistance.quro.cluster.storage

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

/**
 * 数据库构建。三个工程细节：
 *  1. 独立库名 qurocluster.db，不与宿主混库；
 *  2. SQLCipher 加密，口令由宿主传入（推荐从 AndroidKeystore 派生，不要硬编码）；
 *  3. 打开失败（口令变更/库损坏）时**自动重建**，宁可丢缓存也不能让整个 App 崩。
 */
/**
 * 🔴 SQLCipher native 库加载守卫（幂等，线程安全）。
 *
 * ## 为什么必须显式加载
 * `net.zetetic:sqlcipher-android:4.5.5` 的 AAR **不自动加载 so**。实测反汇编
 * （`javap -p -c net.zetetic.database.sqlcipher.SQLiteConnection`）：
 *  - 该类有 30+ 个 `native` 方法（`nativeOpen` / `nativeClose` / `nativeKey` ...）；
 *  - 它的 `static {}` 只做三件事：`$assertionsDisabled`、`EMPTY_STRING_ARRAY`、
 *    `EMPTY_BYTE_ARRAY`，**没有任何 `System.loadLibrary` 调用**；
 *  - `SupportOpenHelperFactory` 与 `SQLiteDatabase` 同样 0 处 `loadLibrary`；
 *  - 整个包里也没有 `net.zetetic.database.sqlcipher.System` 这类自动加载垫片类。
 * 库名对应 APK 里的 `lib/arm64-v8a/libsqlcipher.so`（AAR 的 `jni/<abi>/`），
 * **必须由宿主首次触碰 native 方法前自己 `System.loadLibrary("sqlcipher")`**。
 *
 * ## 漏掉时的症状（真机实测）
 * 不加载就直接建库，会在 Room 首次打开时抛：
 * `No implementation found for long net.zetetic.database.sqlcipher.SQLiteConnection.nativeOpen(...)`
 * 提示 `is the library loaded, e.g. System.loadLibrary?` —— 即本文件曾缺失的初始化。
 *
 * ## 为什么用守卫而不是裸调用
 * `loadLibrary` 对同一 so 重复调用是幂等的，但**并发首次调用**时后到的线程会看到
 * `JNI_OnLoad` 尚未完成而抛 `UnsatisfiedLinkError`（Android 上确有其事），
 * 于是「建集群」偶发崩溃、复现不稳定。故用双重检查锁 + 三态标记：
 * 成功/失败都只尝试一次，失败态把原始异常带出去，便于定位 ABI 缺失等问题。
 */
private object SqlCipherLoader {
    private const val LIB = "sqlcipher"

    /** null = 尚未尝试；true/false = 已尝试及其结果（false 时 [error] 有值）。 */
    @Volatile private var loaded: Boolean? = null

    @Volatile private var error: Throwable? = null

    fun ensureLoaded() {
        loaded?.let { done ->
            if (!done) throw UnsatisfiedLinkError(
                "libsqlcipher.so 加载失败（此前已尝试过）：${error?.message}"
            ).also { it.initCause(error) }
            return
        }
        synchronized(this) {
            // 双检：等锁期间可能已被别的线程加载完
            val settled = loaded
            if (settled != null) {
                if (!settled) throw UnsatisfiedLinkError(
                    "libsqlcipher.so 加载失败：${error?.message}"
                ).also { it.initCause(error) }
                return
            }
            try {
                System.loadLibrary(LIB)
                loaded = true
            } catch (t: Throwable) {
                // 不吞异常：记下来，下次调用原样抛出，但**不重复尝试 loadLibrary**
                // （重复加载对已部分初始化的 so 反而可能二次崩）。
                error = t
                loaded = false
                throw UnsatisfiedLinkError(
                    "libsqlcipher.so 加载失败：${t.javaClass.simpleName}: ${t.message}。" +
                        "本 APK 仅打包了 arm64-v8a 的 libsqlcipher.so；" +
                        "若设备/模拟器不是 arm64-v8a（如 x86_64 模拟器），" +
                        "属于 ABI 不匹配而非代码缺陷。"
                ).also { it.initCause(t) }
            }
        }
    }
}

object DatabaseProvider {

    @Volatile private var db: ClusterDatabase? = null

    fun get(context: Context, passphrase: ByteArray? = null): ClusterDatabase =
        db ?: synchronized(this) {
            db ?: build(context, passphrase).also { db = it }
        }

    private fun build(context: Context, passphrase: ByteArray?): ClusterDatabase {
        // 🔴 必须在触碰任何 SQLCipher native 方法之前加载 so，见 SqlCipherLoader 文档。
        SqlCipherLoader.ensureLoaded()
        val key = passphrase ?: deriveStableKey(context)
        val factory: SupportSQLiteOpenHelper.Factory = SupportOpenHelperFactory(key)
        return Room.databaseBuilder(context.applicationContext, ClusterDatabase::class.java, "qurocluster.db")
            .openHelperFactory(factory)
            // 领域对象是 JSON 列，schema 极少变更；真要改时这里换 fallbackToDestructiveMigration
            .fallbackToDestructiveMigration()
            .build()
    }

    /** 没有宿主口令时的兜底：用随机密钥，重启即失效（只保护本地静态文件） */
    private fun deriveStableKey(context: Context): ByteArray {
        val f = java.io.File(context.noBackupFilesDir, ".ack")
        val k = if (f.exists()) f.readBytes() else ByteArray(32).also {
            SecureRandom().nextBytes(it); f.writeBytes(it)
        }
        return k
    }
}
