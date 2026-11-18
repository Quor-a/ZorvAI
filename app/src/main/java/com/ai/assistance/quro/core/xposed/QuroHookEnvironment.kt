package com.ai.assistance.quro.core.xposed

import android.content.Context
import android.os.Handler
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Hook 进程里取得 Android 框架上下文的最小工具（净室实现，纯反射 + 框架类）。
 *
 * 被 Hook 的是 system_server / SystemUI / 厂商助手等**别人的进程**，没有本应用 Application，
 * 也没有资源 ID；因此这里只依赖 android.* 与 java.lang.reflect，绝不引用 app 侧单例。
 *
 * 一律「找不到返回 null」：ROM 换了字段名属于正常情况，调用方按 MISSING 记账降级。
 */
internal object QuroHookEnvironment {

    private const val BACKGROUND_THREAD = "com.android.internal.os.BackgroundThread"
    private const val ACTIVITY_THREAD = "android.app.ActivityThread"

    /** system_server 的 mSystemContext / mContext 字段，或 getSystemContext()/getContext()。 */
    fun systemContext(owner: Any?): Context? {
        fromOwner(owner)?.let { return it }
        return runCatching {
            val at = Class.forName(ACTIVITY_THREAD)
            val current = at.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }.invoke(null) ?: return@runCatching null
            at.getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }.invoke(current) as? Context
        }.getOrNull()
    }

    /** 普通 App 进程（SystemUI / Google / 小布）取当前 Application 作为 Context。 */
    fun currentApplication(): Context? = runCatching {
        val at = Class.forName(ACTIVITY_THREAD)
        at.getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }.invoke(null) as? Context
    }.getOrNull()

    /**
     * 复用系统共享后台线程，不自建线程：Settings I/O 与观察者回调离开主线程，
     * 又不会因模块常驻线程导致 system_server 泄漏。
     */
    fun backgroundHandler(classLoader: ClassLoader): Handler? = runCatching {
        val clazz = QuroHookSupport.findClassOrNull(classLoader, BACKGROUND_THREAD) ?: return@runCatching null
        val get = QuroHookSupport.findMethod(clazz, "getHandler") ?: return@runCatching null
        get.invoke(null) as? Handler
    }.getOrNull()

    /** 优先用系统后台 Handler；普通 App 进程退回主线程 Looper。 */
    fun anyHandler(classLoader: ClassLoader): Handler? =
        backgroundHandler(classLoader) ?: runCatching { Handler(android.os.Looper.getMainLooper()) }.getOrNull()

    private fun fromOwner(owner: Any?): Context? {
        if (owner == null) return null
        listOf("mSystemContext", "mContext", "mUiContext").forEach { name ->
            field(owner.javaClass, name)?.let { f ->
                runCatching { f.get(owner) as? Context }.getOrNull()?.let { return it }
            }
        }
        listOf("getSystemContext", "getContext", "getUiContext").forEach { name ->
            method(owner.javaClass, name)?.let { m ->
                runCatching { m.invoke(owner) as? Context }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun field(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val f = runCatching { current!!.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (f != null) return f
            current = current.superclass
        }
        return null
    }

    private fun method(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val m = runCatching { current!!.getDeclaredMethod(name).apply { isAccessible = true } }.getOrNull()
            if (m != null) return m
            current = current.superclass
        }
        return null
    }
}
