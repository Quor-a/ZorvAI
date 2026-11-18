package com.ai.assistance.quro.core.xposed

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

/** 框架报告的「确实已加载模块代码」的进程快照。 */
data class QuroRunningTarget(
    val processName: String,
    val state: String,
    val pid: Int,
    val uid: Int,
)

/** 「框架通信」页展示的一帧状态；不可变，便于 Compose 直接持有。 */
data class QuroModuleStatus(
    val connected: Boolean = false,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val frameworkVersionCode: Long = 0L,
    val apiVersion: Int = 0,
    val scope: List<String> = emptyList(),
    val runningTargets: List<QuroRunningTarget> = emptyList(),
) {
    /** 声明了但用户没勾的作用域（不做安装过滤，供工具核对声明面）。 */
    val missingScope: List<String>
        get() = QuroModuleConfig.DECLARED_SCOPE.filter { it !in scope }

    /**
     * 本机真正还缺、且补了有意义的作用域：已安装但没勾。
     *
     * 把「本机没装的小米助手」「没装的 Google App」排除掉——
     * 否则请求会被框架整单拒绝（它不认识未安装的包），表现就是点了补齐毫无反应。
     */
    fun actionableMissingScope(installed: Set<String>): List<String> =
        QuroModuleConfig.DECLARED_SCOPE.filter {
            it !in scope && QuroModuleConfig.isTargetPresent(it, installed)
        }

    /** 某个开关在当前环境（本机已装应用 + 已勾作用域）下还差哪些作用域。 */
    fun unmetScope(def: QuroModuleConfig.SwitchDef, installed: Set<String>): List<String> =
        QuroModuleConfig.unmetScope(def, scope, installed)

    /** 某个作用域目标在本机是否存在（`system` 恒为 true）。 */
    fun isPresent(pkg: String, installed: Set<String>): Boolean =
        QuroModuleConfig.isTargetPresent(pkg, installed)

    /** 某个作用域目标当前是否真的有进程加载了本模块。 */
    fun isScopeActive(pkg: String): Boolean = runningTargets.any { t ->
        t.processName == pkg || t.processName.startsWith("$pkg:") ||
            (pkg == QuroModuleConfig.SYSTEM && t.processName == "system_server")
    }
}

/**
 * UI 进程侧的框架服务连接状态。
 *
 * LSPosed 通过 service AAR 自带的 XposedProvider 把 binder 推给本进程；
 * [XposedServiceHelper] 在 Application.onCreate 注册监听后回放缓存的 binder。
 * 拿到 [XposedService] 才能写 RemotePreferences、读真实作用域、请求补齐作用域。
 *
 * 「服务连接」≠「模块已启用」：关闭模块后连接仍可能保留，实际生效取决于目标进程是否加载 Hook，
 * 因此 UI 同时展示作用域清单，不把连接状态当功能开关。
 */
object QuroModuleServiceState : XposedServiceHelper.OnServiceListener {

    @Volatile
    var service: XposedService? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 只注册一次；ContentProvider 先于 Application.onCreate 收到的 binder 由 Helper 内部缓存回放。 */
    @Volatile
    private var registered = false

    /** 应用 Context：用于 PackageManager 判断哪些作用域目标在本机真实存在。 */
    @Volatile
    private var appContext: android.content.Context? = null

    fun install(app: Application) {
        appContext = app.applicationContext ?: app
        if (registered) return
        registered = true
        runCatching { XposedServiceHelper.registerListener(this) }
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        QuroModulePrefs.attachFromService(service)
        dispatch()
    }

    override fun onServiceDied(service: XposedService) {
        // 多框架场景下死掉的可能是已被替换的旧实例；只清当前持有的那个。
        if (this.service === service) {
            this.service = null
            QuroModulePrefs.detach()
            dispatch()
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun dispatch() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { runCatching { it() } }
        } else {
            mainHandler.post { listeners.forEach { runCatching { it() } } }
        }
    }

    fun isConnected(): Boolean = service != null

    fun status(): QuroModuleStatus {
        val s = service ?: return QuroModuleStatus()
        return runCatching {
            QuroModuleStatus(
                connected = true,
                frameworkName = s.frameworkName,
                frameworkVersion = s.frameworkVersion,
                frameworkVersionCode = s.frameworkVersionCode,
                apiVersion = s.apiVersion,
                scope = runCatching { s.scope }.getOrDefault(emptyList()),
                runningTargets = runCatching {
                    s.runningTargets.map { t ->
                        QuroRunningTarget(
                            processName = t.processName,
                            state = t.state?.name ?: "UNKNOWN",
                            pid = t.pid,
                            uid = t.uid,
                        )
                    }
                }.getOrDefault(emptyList()),
            )
        }.getOrElse { QuroModuleStatus(connected = true) }
    }

    /** 一键补齐缺失作用域；由框架弹授权，结果回调在主线程。 */
    fun requestMissingScope(onResult: (ok: Boolean, message: String) -> Unit) {
        requestMissingScope(installedPackages(), onResult)
    }

    /**
     * 补齐作用域。
     *
     * 关键修正：只提交**本机已安装**的目标。旧实现把 `com.miui.voiceassist` 这类
     * ColorOS 上根本不存在的包一起塞给框架，框架直接整单失败，用户看到的就是「点了没反应」。
     *
     * 另加 1.5s 观察窗：管理器若既不弹确认也不回调（部分 Vector 版本对静态作用域模块
     * 不弹框），如实告诉用户去管理器手动勾，而不是让按钮变成一个死键。
     */
    fun requestMissingScope(
        installed: Set<String>,
        onResult: (ok: Boolean, message: String) -> Unit,
    ) {
        val s = service
        if (s == null) {
            onResult(false, "框架服务未连接")
            return
        }
        val missing = runCatching { status().actionableMissingScope(installed) }
            .getOrDefault(emptyList())
        if (missing.isEmpty()) {
            onResult(true, "本机已无需补齐（未安装的组件不参与判定）")
            return
        }
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val settle = { ok: Boolean, message: String ->
            if (settled.compareAndSet(false, true)) {
                mainHandler.post {
                    onResult(ok, message)
                    if (ok) dispatch()
                }
            }
        }
        val callback = object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                settle(true, "已加入作用域：${approved.joinToString()}")
            }

            override fun onScopeRequestFailed(message: String) {
                settle(false, "框架拒绝：$message；请到管理器手动勾选 ${missing.joinToString()}")
            }
        }
        val ok = runCatching { s.requestScope(missing, callback) }.isSuccess
        if (!ok) {
            settle(false, "当前框架不支持程序化补作用域，请在管理器勾选：${missing.joinToString()}")
            return
        }
        // 管理器没有弹确认、也没有回调：给一句可执行的指引，而不是静默。
        mainHandler.postDelayed(
            {
                settle(false, "请在管理器勾选：${missing.joinToString()}")
            },
            1_500L,
        )
    }

    /** 本机已安装包名；未连接前返回空集，调用方按「全部存在」处理会偏保守，因此优先传 Context 版本。 */
    private fun installedPackages(): Set<String> {
        val ctx = appContext ?: return emptySet()
        return QuroModuleConfig.installedPackages(ctx.packageManager)
    }
}
