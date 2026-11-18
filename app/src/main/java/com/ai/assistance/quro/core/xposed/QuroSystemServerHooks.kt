package com.ai.assistance.quro.core.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * system_server 侧的统一安装锚点（批次 2/3 汇总）。
 *
 * 只挂一次 `SystemServer.startOtherServices(TimingsTraceAndSlog)`：它在系统服务注册完成后
 * 回调，此时 Accessibility / ContextualSearch / VoiceInteraction / Role 等 ManagerService
 * 都已就绪，是无障碍保护、一圈即搜底座、电源键分发、默认数字助理的共同挂载位置。
 *
 * 各功能域独立注册自己的方法 Hook，并在统一锚点里做一次「系统就绪」初始化。
 * 目标类或签名找不到时只记 MISSING，绝不阻断系统启动。
 */
internal object QuroSystemServerHooks {

    private const val SYSTEM_SERVER_CLASS = "com.android.server.SystemServer"
    private const val TIMINGS_CLASS = "com.android.server.utils.TimingsTraceAndSlog"
    private const val START_OTHER_SERVICES = "startOtherServices"

    /** 安装结果；等服务真正起来后再写入跨进程诊断，此时才拿得到 system context。 */
    @Volatile
    private var installedEntries: List<QuroHookEntry> = emptyList()

    fun install(module: XposedModule, classLoader: ClassLoader): List<QuroHookEntry> {
        val hooks = QuroHookRegistrar(module, "SystemServer")
        val entries = hooks.install {
            // 各功能域独立注册自己的方法 Hook（找不到目标各自记 MISSING）。
            QuroContextualSearchHooks.install(module, classLoader, this)
            QuroPowerAssistantHooks.install(module, classLoader, this)
            QuroAssistantRole.install(module, classLoader, this)

            val timings = QuroHookSupport.findClassOrNull(classLoader, TIMINGS_CLASS)
            val systemServer = QuroHookSupport.findClassOrNull(classLoader, SYSTEM_SERVER_CLASS)
            val method = if (timings != null && systemServer != null) {
                QuroHookSupport.findMethod(systemServer, START_OTHER_SERVICES, timings)
            } else null
            if (method == null) {
                missing(
                    id = "system.lifecycle",
                    description = "SystemServer.startOtherServices",
                    detail = "未找到 SystemServer.startOtherServices(TimingsTraceAndSlog)",
                )
                return@install
            }
            // 开机热路径可能已被 AOT 编译；不 deopt 会出现「Hook 成功但回调不触发」。
            QuroHookSupport.deoptimize(module, method)

            val registrar = this
            intercept(
                id = "system.lifecycle",
                executable = method,
                description = "SystemServer.startOtherServices（统一就绪锚点）",
            ) { chain ->
                val result = chain.proceed()
                // 原方法完成后再初始化，避免任何异常影响系统启动。
                val owner = chain.getThisObject()
                val systemContext = QuroHookEnvironment.systemContext(owner)
                runCatching {
                    QuroAccessibilityProtectionHooks.onSystemReady(module, owner, classLoader, registrar)
                }
                runCatching { QuroContextualSearchHooks.onSystemReady(module, classLoader, owner) }
                runCatching {
                    systemContext?.let {
                        QuroAssistantRole.schedule(it, force = true)
                    }
                }
                runCatching { reportConfiguration(module, owner) }
                // 把安装账本写到应用能读到的地方：以后「哪一环没装上」不再需要 logcat。
                runCatching { QuroHookReport.publish(systemContext, "SystemServer", installedEntries) }
                runCatching {
                    systemContext?.let {
                        QuroHookReport.recordEvent(it, "system", "startOtherServices 锚点已触发")
                    }
                }
                result
            }
        }
        installedEntries = entries
        return entries
    }

    private fun reportConfiguration(module: XposedModule, systemServer: Any?) {
        val enabled = QuroModuleConfig.MAIN_SWITCHES.joinToString { def ->
            "${def.key}=${QuroModulePrefs.isEnabled(def.key)}"
        }
        val message = "system_server 已就绪 (${systemServer?.javaClass?.name ?: "unknown"})；" +
            "power=${QuroModulePrefs.powerTarget()}；$enabled"
        module.log(Log.INFO, QuroModuleConfig.LOG_TAG, message)
    }
}
