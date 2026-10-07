package com.ai.assistance.quro.core.experience

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.app.usage.StorageStatsManager
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * 🔴 系统记账口径的存储统计 —— 清理页的**权威数据源**。
 *
 * ## 为什么必须引入这个（用户：「功能不可用，假实现，去看看开源的怎么做」）
 *
 * 清理页原先只有一条数据通路：`measureDirOrNull(dir)` —— **递归 `listFiles()` 逐个累加
 * `File.length()`**。这套做法有三个硬伤：
 *
 * 1. **慢到不可用**。`linux-sandbox` 是整个 Ubuntu rootfs（数万文件），
 *    递归统计要几十秒。开源清理类 App（清浊 / SuperCleanMaster / Fulldive Full Cleaner）
 *    早已不用这套 —— 它们读的是**内核维护的记账**，O(1) 返回，不碰文件系统。
 * 2. **拿不到全局真相**。逐目录遍历只能看到「我们自己去 stat 的那些路径」，
 *    看不到 WebView 数据库、CodeCache、no_backup、art/Dex 残留等系统认为属于本 app 的数据。
 * 3. **口径不可信**。`File.length()` 只是文件逻辑长度，不含已分配块对齐与稀疏文件差异，
 *    与系统设置里「应用占用」显示的数字对不上，用户一看就知道不对。
 *
 * ## 三条官方 API（本工程 `minSdk = 26`，全部可用，且**全部零权限**）
 *
 * | API | 含义 | 说明 |
 * |---|---|---|
 * | [StorageStatsManager.queryStatsForPackage] | 本包 data 分区记账 | `dataBytes`（**已含 cache**）/ `cacheBytes` / `externalCacheBytes`，内核维护 |
 * | [StorageManager.getCacheSizeBytes] | 本包在该存储卷上的缓存总量 | 官方口径的「缓存」，对应设置里的「清除缓存」 |
 * | [StorageManager.getAllocatableBytes] | 本包还能申请多少字节 | **包含系统可回收的 clearable cache**，可用于判断「空间是否真的紧张」 |
 *
 * ⚠️ **不要把 [getAllocatableBytes] 当「可清理量」**：它算的是本包可用额度（含系统可回收部分），
 *    不是「能删掉多少字节」。UI 上只用于判断存储压力。
 *
 * ⚠️ [StorageStatsManager] 只覆盖 **data 分区**（`/data/user/0/<pkg>`）。
 *    `getExternalFilesDir()` 在 `/storage/emulated/0/Android/data/<pkg>`，**不计入**，
 *    外部私有目录的体积仍需 [measureDirOrNull] 补齐（见 [collectExternalAppDirs]）。
 *
 * 所有 API 一律`runCatching` 包裹：不同厂商 ROM 对 [getCacheSizeBytes] 的实现差异很大，
 * 拿不到就返回 `null`（= 读不到），**绝不回落到编造的 0**。
 */
data class QuroStorageStats(
    /**
     * 本包 **data 分区总量**（`StorageStats.getDataBytes()`，**已含 cache**）。
     * null = 系统没给（ROM 没实现 / 抛异常）。
     *
     * 🔴 语义坑：它不是「纯数据」。求和时**不能再 + [cacheBytes]**，否则重复计算。
     */
    val dataBytes: Long?,
    /** 本包 data 分区的 cache 字节。 */
    val cacheBytes: Long?,
    /** 本包外部 cache 字节（`getExternalCacheDir`）。 */
    val externalCacheBytes: Long?,
    /** 官方口径的缓存总量（`StorageManager.getCacheSizeBytes`）。 */
    val cacheSizeBytes: Long?,
    /** 本包还能申请的字节数（含系统可回收缓存）。用于判断存储压力，不等于「可清理量」。 */
    val allocatableBytes: Long?,
    /** 该存储卷剩余可用字节（`File.getUsableSpace`）。 */
    val freeBytes: Long?,
    /** 该存储卷总字节。 */
    val totalBytes: Long?,
) {
    /**
     * **官方记账的应用总占用** = data 分区总量 + 外部 cache。任一为 null 则整体为 null。
     *
     * 🔴 [dataBytes] 已含 cache，所以这里**只加 [externalCacheBytes]**；
     *    早期版本把 [cacheBytes] 也加了一遍，等于把缓存算两遍 —— 与系统设置对不上。
     */
    val accountedBytes: Long?
        get() {
            val d = dataBytes ?: return null
            val e = externalCacheBytes ?: return null
            return d + e
        }

    /** 存储占用率 0f..1f；拿不到总字节时返回 null（UI 应显示「读不到」而非 0%）。 */
    val usedPercent: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            val free = freeBytes ?: return null
            return ((total - free).toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * 采集系统记账口径的存储统计。
 *
 * @param volumePath 决定「哪个存储卷」的路径；传 app 私有外部目录（`getExternalFilesDir`）
 *   可让 [StorageManager.getCacheSizeBytes] 报到外部卷上的缓存。传 null 时用 data 分区。
 */
fun readStorageStats(context: Context, volumePath: String? = null): QuroStorageStats {
    val pkg = context.packageName

    var dataBytes: Long? = null
    var cacheBytes: Long? = null
    var externalCacheBytes: Long? = null

    val ssm = runCatching {
        context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
    }.getOrNull()

    val sm = runCatching {
        context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
    }.getOrNull()

    var uuid: UUID = StorageManager.UUID_DEFAULT
    if (sm != null && volumePath != null) {
        runCatching { uuid = sm.getUuidForPath(File(volumePath)) }
            .onFailure { Log.w(TAG, "getUuidForPath failed", it) }
    }

    if (ssm != null) {
        runCatching {
            // 🔴🔴 签名实测（javap android-36/android.jar）：
            //   `queryStatsForPackage(UUID storageUuid, String packageName, UserHandle user)`
            //   —— **UUID 在前**，不是文档里常见的 (pkg, uuid) 顺序。
            //   写反了会直接编译不过（Argument type mismatch），别照抄别处的示例代码。
            //
            //   权限：查**自己的包**不需要任何权限；查别的包才需要 PACKAGE_USAGE_STATS。
            //   这里固定用当前进程 UserHandle，拿不到就当「读不到」。
            val st = ssm.queryStatsForPackage(uuid, pkg, Process.myUserHandle())
            // 🔴🔴 字段名实测（javap android-36/android.jarandroid.app.usage.StorageStats）：
            //   `getDataBytes()` —— **没有** `appDataBytes` 这个属性。
            //   而且 dataBytes 的语义是 **data 分区总量（已含 cache）**，
            //   不是「不含 cache 的纯数据」，所以下面求和时不能再 + cacheBytes。
            dataBytes = st.dataBytes
            cacheBytes = st.cacheBytes
            externalCacheBytes = st.externalCacheBytes
        }.onFailure { Log.w(TAG, "queryStatsForPackage failed", it) }
    } else {
        Log.w(TAG, "StorageStatsManager unavailable (ROM stripped?)")
    }

    // 🔴 getCacheSizeBytes / getAllocatableBytes 都是 API 26+，本工程 minSdk=26，
    //    编译期就保证存在，无需版本分支。部分 ROM 对外置卷会抛 IOException，
    //    统一 runCatching → null（「读不到」），绝不用 0 冒充。
    val cacheSizeBytes: Long? = sm?.let {
        runCatching { it.getCacheSizeBytes(uuid) }
            .onFailure { Log.w(TAG, "getCacheSizeBytes failed", it) }
            .getOrNull()
    }
    val allocatableBytes: Long? = sm?.let {
        runCatching { it.getAllocatableBytes(uuid) }
            .onFailure { Log.w(TAG, "getAllocatableBytes failed", it) }
            .getOrNull()
    }

    // 🔴 剩余/总量优先走 `StorageStatsManager.getFreeBytes/getTotalBytes(uuid)`：
    //    那是**按存储卷**给的口径（跨卷正确）；`File.getUsableSpace()` 只看某个目录所在分区，
    //    在多卷设备上会与应用实际占用的卷不一致 —— 又一处口径不可信。
    val usable: Long? = ssm?.let {
        runCatching { it.getFreeBytes(uuid) }
            .onFailure { Log.w(TAG, "ssm.getFreeBytes failed", it) }
            .getOrNull()
    } ?: volumePath?.let { File(it) }?.let { f ->
        runCatching { f.usableSpace }.getOrNull()
    } ?: runCatching { context.filesDir.usableSpace }.getOrNull()
    val total: Long? = ssm?.let {
        runCatching { it.getTotalBytes(uuid) }
            .onFailure { Log.w(TAG, "ssm.getTotalBytes failed", it) }
            .getOrNull()
    } ?: volumePath?.let { File(it) }?.let { f ->
        runCatching { f.totalSpace }.getOrNull()
    } ?: runCatching { context.filesDir.totalSpace }.getOrNull()

    return QuroStorageStats(
        dataBytes = dataBytes,
        cacheBytes = cacheBytes,
        externalCacheBytes = externalCacheBytes,
        cacheSizeBytes = cacheSizeBytes,
        allocatableBytes = allocatableBytes,
        freeBytes = usable,
        totalBytes = total,
    )
}

/**
 * 统计**外部私有目录**（`getExternalFilesDir`）里我们自己那几类产物的体积。
 *
 * 🔴 为什么要单独补这一段：`StorageStatsManager` **只管 data 分区**，
 *    `quro_exports` / `quro_backups` / `QuroAI_logs` 都在外部卷上，
 *    官方 API 给不出它们的数字，只能老实地递归量一次（这三个目录都很小，秒回）。
 */
fun measureExternalAppDirs(context: Context): Map<String, Long?> {
    val ext = context.getExternalFilesDir(null)
    if (ext == null) {
        Log.w(TAG, "getExternalFilesDir unavailable; external app dirs unreadable")
        return mapOf("logs" to null, "exports" to null, "backups" to null)
    }
    return mapOf(
        "logs" to measureDirOrNull(File(ext, "QuroAI_logs")),
        "exports" to measureDirOrNull(File(ext, "quro_exports")),
        "backups" to measureDirOrNull(File(ext, "quro_backups")),
    )
}

/**
 * data 分区里**除已知目录外**的剩余账 —— 用来发现我们没登记的写入点。
 *
 * `dataBytes` 是内核记账（已含 cache），`paths` 里那几项是我们自己 `stat` 出来的，
 * 两者相减就是「还有多少数据不知道写在哪」。这在开源清理 App 里叫**孤儿数据发现**：
 * 分类没列全 = 分类是假实现。
 *
 * @return 剩余字节；算不出（缺任一项）返回 null。
 */
fun orphanDataBytes(stats: QuroStorageStats, knownDataDirsBytes: Long?): Long? {
    val accounted = stats.accountedBytes ?: return null
    val known = knownDataDirsBytes ?: return null
    return (accounted - known).coerceAtLeast(0L)
}

private const val TAG = "QuroStorage"