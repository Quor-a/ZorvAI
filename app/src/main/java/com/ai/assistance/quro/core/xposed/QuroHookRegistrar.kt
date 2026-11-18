package com.ai.assistance.quro.core.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

internal enum class QuroHookStatus { INSTALLED, MISSING, FAILED, SKIPPED }

internal data class QuroHookEntry(
    val group: String,
    val id: String,
    val description: String,
    val status: QuroHookStatus,
    val detail: String? = null,
)

/**
 * 单个功能域的 Hook 安装账本。
 *
 * 与「一把梭 try/catch」的区别：每个目标单独记录 INSTALLED / MISSING / FAILED / SKIPPED，
 * 汇总写进 Xposed 日志。ROM 升级后哪个签名漂了，看日志第一行就知道，不用真机复现。
 * 普通目标缺失按功能域降级开放；框架级 Error 不在这里吞掉，交回框架处理。
 */
internal class QuroHookRegistrar(
    private val module: XposedModule,
    private val group: String,
) {
    private val entries = mutableListOf<QuroHookEntry>()
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val registered = mutableSetOf<String>()

    fun install(block: QuroHookRegistrar.() -> Unit): List<QuroHookEntry> {
        runCatching { block() }.onFailure { e ->
            // 只兜 Exception；HookFailedError / XposedFrameworkError 是 Error，必须继续抛给框架。
            if (e is Exception) {
                record("install.failed", "$group Hook 组安装", QuroHookStatus.FAILED, e.javaClass.simpleName)
                log(Log.ERROR, "$group Hook 组安装失败", e)
            } else {
                throw e
            }
        }
        val summary = entries.groupingBy { it.status }.eachCount()
        log(
            Log.INFO,
            "[$group] Hook 安装完成: installed=${summary[QuroHookStatus.INSTALLED] ?: 0}, " +
                "missing=${summary[QuroHookStatus.MISSING] ?: 0}, " +
                "failed=${summary[QuroHookStatus.FAILED] ?: 0}, " +
                "skipped=${summary[QuroHookStatus.SKIPPED] ?: 0}",
        )
        return entries.toList()
    }

    /**
     * 注册一个方法拦截。hooker 内部必须自己调用 chain.proceed()（除非明确要替换返回值）。
     */
    fun intercept(
        id: String,
        executable: Executable,
        description: String,
        hooker: (XposedInterface.Chain) -> Any?,
    ): XposedInterface.HookHandle? {
        val fullId = "quro.$id"
        if (!registered.add(fullId)) {
            record(id, description, QuroHookStatus.FAILED, "重复 Hook 注册：$fullId")
            return null
        }
        return try {
            val handle = module.hook(executable)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(fullId)
                .intercept { chain -> hooker(chain) }
            handles += handle
            record(id, description, QuroHookStatus.INSTALLED)
            handle
        } catch (e: Exception) {
            registered.remove(fullId)
            record(id, description, QuroHookStatus.FAILED, e.javaClass.simpleName)
            log(Log.ERROR, "安装 Hook 失败：$description", e)
            null
        }
    }

    fun missing(id: String, description: String, detail: String) {
        record(id, description, QuroHookStatus.MISSING, detail)
        log(Log.WARN, "[$group] 目标缺失：$description — $detail")
    }

    fun skipped(id: String, description: String, detail: String) {
        record(id, description, QuroHookStatus.SKIPPED, detail)
    }

    fun handles(): List<XposedInterface.HookHandle> = handles.toList()

    fun switchEnabled(key: String): Boolean = QuroModulePrefs.isEnabled(key)

    private fun record(
        id: String,
        description: String,
        status: QuroHookStatus,
        detail: String? = null,
    ) {
        entries += QuroHookEntry(group, id, description, status, detail)
    }

    private fun log(priority: Int, msg: String, tr: Throwable? = null) {
        runCatching {
            if (tr == null) module.log(priority, QuroModuleConfig.LOG_TAG, msg)
            else module.log(priority, QuroModuleConfig.LOG_TAG, msg, tr)
        }
    }
}
