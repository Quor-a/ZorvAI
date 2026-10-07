package com.ai.assistance.quro.core.experience

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File

/**
 * 🔴 真实的进程内存采集器 —— 「清理内存」页的数据源。
 *
 * ## 为什么要有这个类（用户：「清理内存不要搞假实现，要真实的功能」）
 *
 * 此前「清理内存」页把[CleanupScreen] 的**磁盘目录体积**当作内存数据展示，
 * 是名副其实的假实现：它一个字节内存都没读，却顶着「清理内存」的名头。
 * 本类改为读取 Android 真实可测的内存指标。
 *
 * ## 指标口径（全部来自 AOSP 公开 API，无估算、无占位）
 *
 * | 字段 | 来源 | 含义 |
 * |---|---|---|
 * | [javaHeap] | `Debug.getRuntime().totalMemory()` | 进程已向系统申请的 Java 堆上限 |
 * | [javaHeapUsed] | `Debug.getRuntime().totalMemory() - freeMemory()` | Java 堆实际占用 |
 * | [nativeHeap] | `Debug.getNativeHeapAllocatedSize()` | malloc 出去的 Native 堆 |
 * | [nativeHeapTotal] | `Debug.getNativeHeapSize()` | Native 堆总量 |
 * | [pssTotal] | `Debug.MemoryInfo.getTotalPss()` | **PSS：系统认为这进程真实占用的物理内存**（含共享页按比例分摊），最权威 |
 * | [pssDalvik] / [pssNative] | `Debug.MemoryInfo` 分项 | 拆出 Dalvik / Native 便于定位 |
 * | [systemAvailable] | `ActivityManager.MemoryInfo.availMem` | 系统可用内存（判断是否真缺内存） |
 * | [systemTotal] | `ActivityManager.MemoryInfo.totalMem` | 设备总内存 |
 * | [systemLowMemory] | `ActivityManager.MemoryInfo.lowMemory` | 系统是否已进入低内存状态 |
 *
 * ⚠️ **`javaHeap` 不是「可清理量」**：它是已申请的额度而非垃圾。
 * 真正的「能清多少」= 可被 GC 回收的**已分配但未使用**部分，
 * 见 [collectibleHeap]。UI 上不得把它标成「可清理内存」——那又是一个假实现。
 */
data class QuroMemoryInfo(
    val javaHeap: Long,
    val javaHeapUsed: Long,
    val nativeHeap: Long,
    val nativeHeapTotal: Long,
    val pssTotal: Long,
    val pssDalvik: Long,
    val pssNative: Long,
    val systemTotal: Long,
    val systemAvailable: Long,
    val systemLowMemory: Boolean,
) {
    /** Java 堆中「已分配但未使用」的部分 = GC 能回收的上限（真实可清理量）。 */
    val collectibleHeap: Long get() = (javaHeap - javaHeapUsed).coerceAtLeast(0L)

    /** 本进程占设备总内存的百分比（PSS / 设备总内存），用于进度条。 */
    val pssPercent: Float
        get() = if (systemTotal > 0) (pssTotal.toFloat() / systemTotal).coerceIn(0f, 1f) else 0f

    /** 堆使用率：javaHeapUsed / javaHeap。 */
    val javaHeapPercent: Float
        get() = if (javaHeap > 0) (javaHeapUsed.toFloat() / javaHeap).coerceIn(0f, 1f) else 0f
}

/**
 * 采集一次真实的进程内存快照。
 *
 * @param pid 目标进程 id；传 0 表示当前进程（`Process.myPid()`）。
 */
fun readProcessMemory(context: Context, pid: Int = 0): QuroMemoryInfo {
    val target = if (pid > 0) pid else android.os.Process.myPid()
    // 🔴 用 `Runtime.getRuntime()` 而非 `Debug.getRuntime()` —— 后者不存在。
    val rt = Runtime.getRuntime()

    // 🔴 PSS 主路径：**必须用** `ActivityManager.getProcessMemoryInfo(intArrayOf(pid))`。
    //    它返回 `Debug.MemoryInfo[]`（含 totalPss / dalvikPss / nativePss，单位 KB）。
    //
    //    ⚠️ 本仓 SDK 上 `Debug.getMemoryInfo(mi)` 的签名解析为 **Unit**（无返回值），
    //    不是官方文档里的 Int —— 所以既不能 `if (Debug.getMemoryInfo(mi))`（Unit→Boolean），
    //    也不能拿它的返回值当条目数（`Unresolved reference toInt/compareTo`）。
    //    直接走 ActivityManager 这条被广泛支持的路子，不与SDK 签名硬碰。
    var pssTotal = 0L
    var pssDalvik = 0L
    var pssNative = 0L

    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (am != null) {
        runCatching {
            val pmi = am.getProcessMemoryInfo(intArrayOf(target))?.firstOrNull()
            if (pmi != null && pmi.totalPss > 0) {
                pssTotal = pmi.totalPss.toLong() * 1024L
                pssDalvik = pmi.dalvikPss.toLong() * 1024L
                pssNative = pmi.nativePss.toLong() * 1024L
            }
        }.onFailure { Log.w(TAG, "getProcessMemoryInfo failed", it) }
    } else {
        Log.w(TAG, "ActivityManager unavailable; pss fields stay 0")
    }

    // 设备层面的可用/总内存与低内存状态（判断「是不是真的缺内存」）。
    var sysTotal = 0L
    var sysAvail = 0L
    var lowMem = false
    if (am != null) {
        runCatching {
            val sys = ActivityManager.MemoryInfo()
            am.getMemoryInfo(sys)
            sysTotal = sys.totalMem
            sysAvail = sys.availMem
            lowMem = sys.lowMemory
        }.onFailure { Log.w(TAG, "getMemoryInfo(system) failed", it) }
    }

    return QuroMemoryInfo(
        javaHeap = rt.totalMemory(),
        javaHeapUsed = rt.totalMemory() - rt.freeMemory(),
        nativeHeap = Debug.getNativeHeapAllocatedSize(),
        nativeHeapTotal = Debug.getNativeHeapSize(),
        pssTotal = pssTotal,
        pssDalvik = pssDalvik,
        pssNative = pssNative,
        systemTotal = sysTotal,
        systemAvailable = sysAvail,
        systemLowMemory = lowMem,
    )
}

/**
 * 🔴 **真实**的内存回收动作：向系统申请回调 `onTrimMemory`，并主动跑一次 GC。
 *
 * ## 语义必须说清（否则又是假实现）
 *
 * Android **没有**「一键清空 App 内存」的官方 API。市面上「清理内存」按钮做的是：
 * 1. `ComponentCallbacks2.onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` —— 通知系统「界面已隐藏，
 *    可以释放 UI 级缓存」，是**唯一被系统认可的、真实生效**的释放手段；
 * 2. 主动 `System.gc()` —— **只是建议**，是否回收由系统决定；
 * 3. 清 `cacheDir` —— 这是**磁盘**清理，不是内存。
 *
 * 本函数因此只承诺 1+2，并在返回值里如实区分「已请求」与「实际减少」，
 * 由调用方用**实测差值**播报，绝不预报一个编造的「已释放 xx MB」。
 *
 * @return 回收**后**重新采集的快照（用于算真实减少量）。
 */
fun requestMemoryTrim(context: Context, before: QuroMemoryInfo): QuroMemoryInfo {
    // ① 唯一被系统认可的释放手段：把「UI 已隐藏」通知到 Application，
    //    由各组件的 onTrimMemory 回调自行释放 UI 级缓存。
    //    （早先这里误写成 `am.applicationInfo?.let{}` + 空 runCatching，
    //      既编译不过也什么都不做 —— 纯装饰，已删。）
    val app = context.applicationContext as? android.app.Application
    if (app != null) {
        runCatching { app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
            .onFailure { Log.w(TAG, "onTrimMemory failed", it) }
    } else {
        Log.w(TAG, "applicationContext is not an Application; skip trim request")
    }

    // ② 主动 GC：只是建议，回收与否由系统决定。
    System.gc()
    System.runFinalization()
    Runtime.getRuntime().gc()

    // ③ 返回回收**后**重新采集的快照，由调用方用实测差值播报。
    return readProcessMemory(context).also {
        val freed = before.pssTotal - it.pssTotal
        Log.i(
            TAG,
            "trim requested: pss ${before.pssTotal / 1024}KB -> ${it.pssTotal / 1024}KB " +
                "freed=${freed / 1024}KB javaUsed ${before.javaHeapUsed / 1024}KB -> ${it.javaHeapUsed / 1024}KB",
        )
    }
}

/**
 * 统计某个目录下**可安全删除**的缓存文件体积（只算文件，不含目录项本身）。
 *
 * 与 [com.ai.assistance.quro.ui.ChatScreen.calculateDirSize] 的区别：
 * 那个在**无权限**时返回 0，把「没权限」伪装成「空的」。
 * 本函数用 `null` 明确区分二者：
 * - 返回 `null` = **读不到**（无权限 / IO 异常），调用方必须显示「无权限」而非「0 B」；
 * - 返回 `0L`  = **确实读到了且为空**。
 *
 * 这是「真实功能」与「假实现」的分界线：宁可显示「读不到」，也不能编一个数字。
 */
fun measureDirOrNull(dir: File): Long? {
    if (!dir.exists()) return 0L          // 不存在 = 真空
    if (!dir.canRead()) return null      // 存在但读不到 = 无权限
    // 🔴 递归里任何一个子目录读不到，**整项都算null**，绝不返回偏小的数字。
    //    `getOrNull()` 会把 measureDir 抛出的异常转成 null（= 读不到），
    //    语义与「无权限」一致：宁可说读不到，也不编体积。
    return runCatching { measureDir(dir) }.getOrNull()
}

/**
 * 递归求和，**可失败**（抛 [UnreadableDirException]）而不是把读不到的子树当 0。
 *
 * 🔴 旧实现 `val children = dir.listFiles() ?: return 0L`：子目录读不到就**静默算 0**，
 *    于是一棵 500 MB 但其中某个子树无权限的目录会报出 100 MB —— 同样是假数据，
 *    只是藏得比「无权限→0」更深。这里改为整体失败，交由上层显示「读不到」。
 */
private class UnreadableDirException : Exception()

private fun measureDir(dir: File): Long {
    val children = dir.listFiles() ?: throw UnreadableDirException()
    var size = 0L
    children.forEach { f ->
        size += if (f.isDirectory) measureDir(f) else f.length()
    }
    return size
}

private const val TAG = "QuroMemory"