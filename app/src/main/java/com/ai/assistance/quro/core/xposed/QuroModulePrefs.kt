package com.ai.assistance.quro.core.xposed

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * 跨进程配置读写：UI 进程拿可写的 RemotePreferences，Hook 进程拿同一组的只读副本。
 *
 * 为什么不用 SharedPreferences 文件 / 广播 / ContentProvider：
 * 被 Hook 的是 system_server、SystemUI、Google App、小布等**别的 UID 的进程**，
 * 它们读不到本应用私有目录；RemotePreferences 由 LSPosed 框架托管，是唯一同时可达的通道。
 *
 * 开关一律在 Hook 拦截入口读取当前值，所以配置切换后下一次触发即生效。
 */
object QuroModulePrefs {

    @Volatile
    private var prefs: SharedPreferences? = null

    /** UI 侧拿到的实例可写；Hook 侧只读。 */
    @Volatile
    private var writable: Boolean = false

    fun attach(instance: SharedPreferences?, canWrite: Boolean) {
        prefs = instance
        writable = instance != null && canWrite
        if (writable) ensureDefaults()
    }

    fun attachFromService(service: XposedService?) {
        val instance = service?.let {
            runCatching { it.getRemotePreferences(QuroModuleConfig.PREFS_GROUP) }.getOrNull()
        }
        attach(instance, canWrite = true)
    }

    fun detach() {
        prefs = null
        writable = false
    }

    fun hasConnection(): Boolean = prefs != null

    fun canWrite(): Boolean = writable

    fun isEnabled(key: String): Boolean =
        runCatching {
            prefs?.getBoolean(key, QuroModuleConfig.defaultOf(key))
                ?: QuroModuleConfig.defaultOf(key)
        }.getOrDefault(QuroModuleConfig.defaultOf(key))

    fun powerTarget(): String {
        val raw = runCatching {
            prefs?.getString(QuroModuleConfig.Keys.POWER_ASSISTANT_TARGET, null)
        }.getOrNull()
        return if (raw in QuroModuleConfig.PowerTarget.ALL) {
            raw ?: QuroModuleConfig.PowerTarget.VENDOR
        } else {
            QuroModuleConfig.PowerTarget.VENDOR
        }
    }

    /** 首次连接时把全部键写成显式缺省值，避免 Hook 端与 UI 端对“未设置”的理解漂移。 */
    fun ensureDefaults(): Boolean {
        val p = prefs ?: return false
        if (!writable) return false
        return runCatching {
            val edit = p.edit()
            var changed = false
            QuroModuleConfig.ALL_SWITCHES.forEach { def ->
                if (!p.contains(def.key)) {
                    edit.putBoolean(def.key, def.defaultEnabled)
                    changed = true
                }
            }
            if (!p.contains(QuroModuleConfig.Keys.POWER_ASSISTANT_TARGET)) {
                edit.putString(
                    QuroModuleConfig.Keys.POWER_ASSISTANT_TARGET,
                    QuroModuleConfig.PowerTarget.VENDOR,
                )
                changed = true
            }
            if (changed) edit.commit() else true
        }.getOrDefault(false)
    }

    /** @return 是否成功提交到框架（未连接 / 只读 / binder 失败均返回 false）。 */
    fun setEnabled(key: String, value: Boolean): Boolean {
        val p = prefs ?: return false
        if (!writable) return false
        if (QuroModuleConfig.switchOf(key) == null) return false
        return runCatching { p.edit().putBoolean(key, value).commit() }.getOrDefault(false)
    }

    fun setPowerTarget(value: String): Boolean {
        val p = prefs ?: return false
        if (!writable || value !in QuroModuleConfig.PowerTarget.ALL) return false
        return runCatching {
            p.edit().putString(QuroModuleConfig.Keys.POWER_ASSISTANT_TARGET, value).commit()
        }.getOrDefault(false)
    }

    /** 诊断用快照，只含本组键，不落盘。 */
    fun snapshot(): Map<String, Any?> = runCatching {
        buildMap {
            QuroModuleConfig.ALL_SWITCHES.forEach { put(it.key, isEnabled(it.key)) }
            put(QuroModuleConfig.Keys.POWER_ASSISTANT_TARGET, powerTarget())
        }
    }.getOrDefault(emptyMap())
}
