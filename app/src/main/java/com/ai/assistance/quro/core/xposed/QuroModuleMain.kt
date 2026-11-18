package com.ai.assistance.quro.core.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed 现代模块入口（API 102）。由 META-INF/xposed/java_init.list 声明，每个目标进程各实例化一次。
 *
 * 只在声明的作用域进程里保留生命周期回调，其余进程立即 detach：
 * 本模块不钩普通第三方 App，也不钩自己的主进程，避免无谓注入面。
 *
 * 配置通道：onModuleLoaded 拿框架托管的**只读** RemotePreferences；UI 进程另一侧写入同组，
 * 所有 Hook 在拦截入口即时读当前值（不做缓存快照），所以开关切换后下一次触发即生效。
 */
class QuroModuleMain : XposedModule() {

    private var processName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        if (!shouldKeepLifecycleCallbacks(param)) {
            detach()
            return
        }
        val remote = runCatching { getRemotePreferences(QuroModuleConfig.PREFS_GROUP) }.getOrNull()
        if (remote == null) {
            // 不能伪装成配置同步正常：缺配置时所有开关按缺省值（关闭）走，且留下可诊断日志。
            log(Log.WARN, "RemotePreferences 不可用，开关全部按缺省值处理")
        }
        QuroModulePrefs.attach(remote, canWrite = false)
        log(
            Log.INFO,
            "模块已加载 process=${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion",
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        QuroSystemServerHooks.install(this, param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val current = processName ?: return
        val pkg = param.packageName
        // onPackageReady 会对进程内加载的每个包回调一次；只处理与当前进程对应的那个包。
        if (!QuroHookSupport.isPackageProcess(current, pkg)) return
        QuroPackageHooks.install(this, param, current)
    }

    private fun shouldKeepLifecycleCallbacks(param: ModuleLoadedParam): Boolean {
        if (param.isSystemServer) return true
        val name = param.processName
        return QuroModuleConfig.DECLARED_SCOPE.any { pkg ->
            pkg != QuroModuleConfig.SYSTEM && QuroHookSupport.isPackageProcess(name, pkg)
        }
    }

    private fun log(priority: Int, msg: String) {
        runCatching { log(priority, QuroModuleConfig.LOG_TAG, msg) }
    }
}
