package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.util.Log

/**
 * Apache MINA SSHD 在 Android 上的**兼容兜底**（SSH 闪退的真正根因）。
 *
 * ## 根因
 * Android 的 `System.getProperty("user.home")` 返回 **null**（没有家目录概念）。
 * 而 SSHD 的 `org.apache.sshd.common.util.io.PathUtils` 在**类静态初始化**阶段就读它：
 *
 * ```
 * PathUtils.<clinit> -> getUserHomeFolder() -> Paths.get(null) -> NullPointerException
 * ```
 *
 * 静态初始化抛异常 → JVM 包成 **`ExceptionInInitializerError`**，而它是 `Error`
 * 而不是 `Exception`。后果有两层：
 * 1. 连 `SshServer.setUpDefaultServer()` 都进不去（`ServerBuilder.<clinit>` 就在这条链上）；
 * 2. 调用方的 `catch (e: Exception)` **抓不住 Error**，于是直接把 App 打崩
 *    （用户看到的正是 `uncaught:Thread[main] java.lang.ExceptionInInitializerError`）。
 *
 * ## 解法
 * 在**任何 MINA 类被加载之前**把 `user.home` / `user.name` / `user.dir` 补上，
 * 指向 App 私有目录（唯一可写且稳定的位置）。
 *
 * 注意调用时机：本对象自身不引用任何 MINA 类型，所以「先调 [ensure]，再碰 SSHD」即可保证
 * 顺序；一旦某个 JVM 已经初始化 `PathUtils` 失败，同类加载会变成 `NoClassDefFoundError`，
 * 所以必须抢在第一次使用之前。
 */
internal object SshAndroidCompat {

    private const val TAG = "SshAndroidCompat"

    @Volatile
    private var ready = false

    fun ensure(context: Context) {
        if (ready) return
        val home = context.applicationContext.filesDir.absolutePath
        runCatching {
            if (System.getProperty("user.home").isNullOrBlank()) {
                System.setProperty("user.home", home)
            }
            if (System.getProperty("user.name").isNullOrBlank()) {
                System.setProperty("user.name", "root")
            }
            if (System.getProperty("user.dir").isNullOrBlank()) {
                System.setProperty("user.dir", home)
            }
        }.onFailure { Log.w(TAG, "写入 SSH 兼容系统属性失败（可能被安全策略拦截）", it) }
        ready = true
        Log.i(TAG, "SSH 兼容系统属性就绪：user.home=$home")
    }
}
