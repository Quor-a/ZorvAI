package com.ai.assistance.quro.kaleidobox.core.engine

import android.content.Context
import java.io.File

/**
 * 一个 KaleidoBox 插件 = 一个独立 App。
 *
 * 这是把"插件当 App 看"的 SDK 基石：每个插件拥有自己的**私有文件目录、私有缓存目录、生命周期**，
 * 就像 Android 里一个真实 App 有 [android.content.Context.getFilesDir] / [android.content.Context.getCacheDir]
 * 与 onCreate/onResume/onPause/onDestroy 一样。
 *
 * 设计参考（本次升级调研）：
 *  - AOSP SystemUI Plugin Framework：插件自带独立 PluginContext（Resources + ClassLoader）、
 *    生命周期 onPluginAttached/Loaded/Unloaded，签名校验 + 崩溃熔断；
 *  - Android SDK Runtime：SDK 作为独立进程、拥有自有存储、走 IPC 暴露能力；
 *  - Android 应用沙箱：每 App 独立 UID + 私有数据目录。
 * 我们对照补齐了"App 级"三件套——**私有存储、生命周期、应用上下文**，让插件表现得像一个真 App。
 */
data class KaleidoAppContext(
    val pkgId: String,
    /** 本包私有文件根：<宿主>/files/kaleidobox/data/<pkgId>。可持续落盘。 */
    val filesDir: File,
    /** 本包私有缓存：<宿主>/cache/kaleidobox/data/<pkgId>。可被系统回收。 */
    val cacheDir: File,
    /** 宿主 Context，仅用于获取系统服务（Clipboard/Notification/...），不应长期持有跨包引用。 */
    val hostContext: Context,
) {
    fun file(name: String) = File(filesDir, name)
    fun cacheFile(name: String) = File(cacheDir, name)
    fun mkdirs() { filesDir.mkdirs(); cacheDir.mkdirs() }
}

/** 插件生命周期事件，由 [com.ai.assistance.quro.kaleidobox.KaleidoActivity] 在对应时机转发。 */
enum class AppLifecycle { CREATE, RESUME, PAUSE, DESTROY }

/**
 * 插件可选继承的 App 基类，拿到应用级上下文与生命周期。
 *
 * 现有仅实现 [KaleidoToolkit] 的包不受影响——生命周期通过 [KaleidoToolkit.onAppLifecycle] 默认空实现注入，
 * 不强制实现本类。若插件想用"App 范式"，让它直接 `class MyApp : KaleidoApp()` 并在 onCreate 里初始化、
 * onDestroy 里释放，宿主在加载/销毁时转发这些事件。
 */
abstract class KaleidoApp {
    lateinit var ctx: KaleidoAppContext
        internal set

    open fun onCreate() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroy() {}
}
