package com.ai.assistance.quro.etalib

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import com.ai.assistance.quro.etalib.config.Prefs
import com.ai.assistance.quro.etalib.core.HookInstallation
import com.ai.assistance.quro.etalib.core.ModuleConfig
import com.ai.assistance.quro.etalib.core.ModuleLogger
import com.ai.assistance.quro.etalib.core.safeLogType
import com.ai.assistance.quro.etalib.hook.aimemory.ColorOsMemoryHooks
import com.ai.assistance.quro.etalib.hook.breeno.BreenoHooks
import com.ai.assistance.quro.etalib.hook.colordirect.ColorDirectHooks
import com.ai.assistance.quro.etalib.hook.google.GoogleAppHooks
import com.ai.assistance.quro.etalib.hook.google.GoogleEligibilityHooks
import com.ai.assistance.quro.etalib.hook.hyperos.HyperOsLauncherHooks
import com.ai.assistance.quro.etalib.hook.hyperos.HyperOsScreenSearchHooks
import com.ai.assistance.quro.etalib.hook.system.SystemServerHooks
import com.ai.assistance.quro.etalib.hook.system.SystemUiHooks
import com.ai.assistance.quro.etalib.hook.xiaoai.XiaoAiHooks

class ModuleMain : XposedModule() {

    private val logger = ModuleLogger(this)
    private var currentProcessName: String? = null
    // 当前未启用热重载；保留句柄用于未来显式 unhook/replace，而不是维持 Hook 生效。
    private val hookHandles = mutableListOf<HookHandle>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        if (!shouldKeepLifecycleCallbacks(param)) {
            detach()
            return
        }
        // 缓存框架提供的只读 remote preferences，供所有 hook 拦截回调即时读取。
        // getRemotePreferences 是 XposedInterface 的方法，XposedModule 继承自其 Wrapper 可直接调用。
        // 调用失败时保留历史默认行为，但必须留下可诊断日志，不能伪装成配置同步正常。
        val remotePreferences = try {
            getRemotePreferences(Prefs.GROUP)
        } catch (exception: Exception) {
            logger.warn("RemotePreferences 不可用，将使用兼容默认值: ${exception.safeLogType()}")
            null
        }
        Prefs.attachRemote(remotePreferences)
        logger.debug {
            "模块已加载 process=${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion"
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        recordInstallation(SystemServerHooks.install(this, logger, param.classLoader))
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES -> {
                if (param.isFirstPackage && currentProcessName == param.packageName) {
                    recordInstallation(HyperOsLauncherHooks.install(this, logger, param.classLoader))
                }
            }

            ModuleConfig.SYSTEM_UI_PACKAGE -> {
                if (currentProcessName == ModuleConfig.SYSTEM_UI_PACKAGE) {
                    recordInstallation(SystemUiHooks.install(this, logger, param.classLoader))
                }
            }

            ModuleConfig.GOOGLE_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.GOOGLE_PACKAGE)) {
                    recordInstallation(
                        HookInstallation.combine(
                            group = "Google",
                            installations = listOf(
                                GoogleEligibilityHooks.install(this, logger, param.classLoader),
                                GoogleAppHooks.install(this, logger, param.classLoader)
                            )
                        )
                    )
                }
            }

            ModuleConfig.COLOR_DIRECT_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.COLOR_DIRECT_PACKAGE)) {
                    recordInstallation(
                        ColorDirectHooks.install(this, logger, param.classLoader, param.applicationInfo)
                    )
                }
            }

            ModuleConfig.BREENO_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.BREENO_PACKAGE)) {
                    recordInstallation(
                        BreenoHooks.install(this, logger, param.classLoader, param.applicationInfo)
                    )
                }
            }

            ModuleConfig.COLOROS_MEMORY_PACKAGE -> {
                if (currentProcessName == ModuleConfig.COLOROS_MEMORY_PACKAGE) {
                    recordInstallation(
                        ColorOsMemoryHooks.install(this, logger, param.classLoader, param.applicationInfo)
                    )
                }
            }

            ModuleConfig.XIAOAI_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.XIAOAI_PACKAGE)) {
                    recordInstallation(HyperOsScreenSearchHooks.install(this, logger, param.classLoader))
                }
                if (isCurrentXiaoAiProcess()) {
                    recordInstallation(
                        XiaoAiHooks.install(
                            module = this,
                            rootLogger = logger,
                            classLoader = param.classLoader,
                        )
                    )
                }
            }
        }
    }

    private fun recordInstallation(installation: HookInstallation) {
        hookHandles += installation.handles
        logger.scoped(installation.report.group).info(installation.report.summary())
    }

    private fun isCurrentPackageProcess(packageName: String): Boolean {
        val processName = currentProcessName ?: return false
        return isPackageProcess(processName, packageName)
    }

    private fun shouldKeepLifecycleCallbacks(param: ModuleLoadedParam): Boolean {
        if (param.isSystemServer) return true
        val processName = param.processName
        return processName in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES ||
            processName == ModuleConfig.SYSTEM_UI_PACKAGE ||
            isPackageProcess(processName, ModuleConfig.GOOGLE_PACKAGE) ||
            isPackageProcess(processName, ModuleConfig.COLOR_DIRECT_PACKAGE) ||
            isPackageProcess(processName, ModuleConfig.BREENO_PACKAGE) ||
            processName == ModuleConfig.COLOROS_MEMORY_PACKAGE ||
            isPackageProcess(processName, ModuleConfig.XIAOAI_PACKAGE)
    }

    private fun isCurrentXiaoAiProcess(): Boolean {
        val processName = currentProcessName ?: return false
        return processName == ModuleConfig.XIAOAI_PACKAGE ||
            processName == ModuleConfig.XIAOAI_CORE_PROCESS
    }

    private fun isPackageProcess(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")
}
