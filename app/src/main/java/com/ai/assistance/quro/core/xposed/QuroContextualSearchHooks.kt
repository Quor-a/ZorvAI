package com.ai.assistance.quro.core.xposed

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 批次 3：一圈即搜（Contextual Search）系统侧底座（净室实现）。
 *
 * 在 system_server 里补齐三件事，全部按 Android 公开/隐藏框架签名反射定位：
 *  1. `SystemServer.deviceHasConfigString(Context, int)`：让系统认为本 ROM 配了
 *     `config_defaultContextualSearchPackageName`；
 *  2. `ContextualSearchManagerService.getContextualSearchPackageName()`：返回 Google App；
 *  3. `ContextualSearchManagerService.enforcePermission(String)`：只对**白名单系统进程**
 *     放行 `startContextualSearch`，其它调用方照走原权限检查（不敞开全局权限）。
 *  4. 服务未注册时经 `SystemServiceManager.startService(Class)` 补启动。
 *
 * 触发侧 [trigger] 直接调 `IContextualSearchManager.Stub.asInterface(...)` 的
 * `startContextualSearch(int)` / `startContextualSearch(int, ContextualSearchConfig)`，
 * 兼容两种 AOSP 版本，失败返回 false 让调用方降级到原生链路。
 *
 * 开关：QuroModuleConfig.Keys.GEMINI_CIRCLE_TO_SEARCH 与 GESTURE_BAR_CIRCLE_TO_SEARCH。
 * 关闭时所有 Hook 仍然注册但直接 proceed，不做任何系统行为改写。
 */
internal object QuroContextualSearchHooks {

    private const val SYSTEM_SERVER_CLASS = "com.android.server.SystemServer"
    private const val SERVICE_CLASS = "com.android.server.contextualsearch.ContextualSearchManagerService"
    private const val SERVICE_NAME = "contextual_search"
    private const val R_STRING_CLASS = "com.android.internal.R\$string"
    private const val CONFIG_FIELD = "config_defaultContextualSearchPackageName"
    private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"

    /** SystemUI 手势条 / ColorDirect 双指识屏入口（Eta CIRCLE_TO_SEARCH_ENTRYPOINT）。 */
    const val ENTRY_POINT_LONG_PRESS = 2
    /** HyperOS 桌面三锚点 / 识屏触发入口（Eta HyperOsSearchTrigger 显式传 1）。 */
    const val ENTRY_POINT_GESTURE_BAR = 1
    const val ACTION_LAUNCH = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"

    private val ALLOWED_CALLERS = setOf(
        "com.android.systemui",
        "com.coloros.colordirectservice",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.miui.voiceassist",
    )

    @Volatile private var getServiceMethod: Method? = null
    @Volatile private var asInterfaceMethod: Method? = null
    @Volatile private var startMethod: StartMethod? = null

    private data class StartMethod(val method: Method, val hasConfig: Boolean)

    fun install(module: XposedModule, classLoader: ClassLoader, hooks: QuroHookRegistrar) {
        val systemServer = QuroHookSupport.findClassOrNull(classLoader, SYSTEM_SERVER_CLASS)
        val service = QuroHookSupport.findClassOrNull(classLoader, SERVICE_CLASS)
        val contextClass = Context::class.java
        val intClass = Int::class.javaPrimitiveType!!

        // 1) 让 ROM 承认存在 contextual search 默认包名配置。
        val configResId = configResourceId(classLoader)
        val hasConfigString = if (systemServer != null && configResId != null) {
            QuroHookSupport.findMethod(systemServer, "deviceHasConfigString", contextClass, intClass)
        } else null
        if (hasConfigString != null) {
            hooks.intercept(
                id = "system.contextual-search-config",
                executable = hasConfigString,
                description = "SystemServer.deviceHasConfigString",
            ) { chain ->
                // 资格补齐是一圈即搜的底层依赖，始终生效（Eta 无条件改写，不受开关门控）。
                val resId = chain.getArg(1)
                if (resId == configResId) true else chain.proceed()
            }
        } else {
            hooks.missing(
                id = "system.contextual-search-config",
                description = "SystemServer.deviceHasConfigString",
                detail = "未找到方法或 config_defaultContextualSearchPackageName 资源 ID",
            )
        }

        // 2) 服务返回的默认包名。
        val packageMethod = service?.let {
            QuroHookSupport.findMethod(it, "getContextualSearchPackageName")
        }
        if (packageMethod != null) {
            hooks.intercept(
                id = "system.contextual-search-package",
                executable = packageMethod,
                description = "ContextualSearchManagerService.getContextualSearchPackageName",
            ) { chain ->
                // 资格补齐是一圈即搜的底层依赖，始终生效（Eta 无条件改写，不受开关门控）。
                // 必须强制指向 Google App：ColorOS 上原值可能是厂商识屏包，
                // 「原值非空就用原值」等于一圈即搜仍然交给厂商，等于没改。
                GOOGLE_PACKAGE
            }
        } else {
            hooks.missing(
                id = "system.contextual-search-package",
                description = "ContextualSearchManagerService.getContextualSearchPackageName",
                detail = "服务类或方法不存在（非 Android 15+ / ROM 未实现）",
            )
        }

        // 3) 权限放行：只对白名单系统进程 + startContextualSearch 这个动作放行。
        val enforce = service?.let {
            QuroHookSupport.findMethod(it, "enforcePermission", String::class.java)
        }
        if (enforce != null) {
            hooks.intercept(
                id = "system.contextual-search-permission",
                executable = enforce,
                description = "ContextualSearchManagerService.enforcePermission",
            ) { chain ->
                val function = chain.getArg(0) as? String
                if (!enabled() || function != "startContextualSearch" || !callerAllowed(chain.getThisObject())) {
                    chain.proceed()
                } else {
                    null
                }
            }
        } else {
            hooks.missing(
                id = "system.contextual-search-permission",
                description = "ContextualSearchManagerService.enforcePermission",
                detail = "服务类或方法不存在",
            )
        }
    }

    /** 统一生命周期锚点回调：ROM 未把 contextual_search 纳入开机列表时补启动。 */
    fun onSystemReady(module: XposedModule, classLoader: ClassLoader, owner: Any?) {
        if (!enabled()) return
        runCatching { ensureService(module, classLoader, owner) }
    }

    /**
     * 触发侧细分开关与底座主开关必须等价：Eta 的底座四环无条件生效，
     * 只判 GEMINI_CIRCLE_TO_SEARCH 会让「只开手势条长按」的用户整条链路静默失效。
     */
    private fun enabled(): Boolean =
        QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GEMINI_CIRCLE_TO_SEARCH) ||
            QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)

    private fun configResourceId(classLoader: ClassLoader): Int? = runCatching {
        val clazz = QuroHookSupport.findClassOrNull(classLoader, R_STRING_CLASS) ?: return@runCatching null
        val f = clazz.getDeclaredField(CONFIG_FIELD).apply { isAccessible = true }
        f.getInt(null)
    }.getOrNull()

    /**
     * 白名单按 UID 反查包名。ROM 用 shared UID 时 getPackagesForUid 返回整组，
     * 这与 Android 自身的权限主体一致；不允许普通第三方 App 借此绕过权限。
     */
    private fun callerAllowed(serviceInstance: Any?): Boolean {
        val ctx = serviceInstance?.let { owner ->
            QuroHookEnvironment.systemContext(owner)
                ?: QuroHookSupport.readField(owner, "mContext") as? Context
        } ?: return false
        val uid = Binder.getCallingUid()
        // system_server 自身放行；shell(2000) 不再直通，否则任意 adb shell 可绕过白名单。
        if (uid == QuroAgentBridgeProtocolRefs.SYSTEM_UID) return true
        val pkgs = runCatching { ctx.packageManager.getPackagesForUid(uid) }.getOrNull().orEmpty()
        // Eta ContextualSearchCallerPolicy：HyperOS 桌面 / 小爱调用方受手势开关约束；
        // SystemUI / ColorDirect 无条件放行。
        val hyperOsCallers = setOf(
            "com.miui.home", "com.mi.android.globallauncher", "com.miui.voiceassist",
        )
        if (pkgs.any { it in hyperOsCallers } &&
            !QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)
        ) return false
        return pkgs.any { it in ALLOWED_CALLERS }
    }

    private fun ensureService(module: XposedModule, classLoader: ClassLoader, owner: Any?) {
        if (serviceAlive()) return
        val manager = QuroHookSupport.readField(owner, "mSystemServiceManager") ?: return
        val serviceClass = QuroHookSupport.findClassOrNull(classLoader, SERVICE_CLASS) ?: return
        val start = QuroHookSupport.findMethod(manager.javaClass, "startService", Class::class.java) ?: return
        runCatching { start.invoke(manager, serviceClass) }
        module.log(
            Log.INFO,
            QuroModuleConfig.LOG_TAG,
            "contextual_search 补启动：${if (serviceAlive()) "成功" else "服务仍不可用"}",
        )
    }

    private fun serviceAlive(): Boolean = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        val get = sm.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
        (get.invoke(null, SERVICE_NAME) as? IBinder)?.isBinderAlive == true
    }.getOrDefault(false)

    /** 触发一圈即搜。返回 false 表示系统入口不可用，调用方应降级到厂商原生链路。 */
    fun trigger(ctx: Context?, entryPoint: Int = ENTRY_POINT_LONG_PRESS): Boolean {
        if (ctx == null || !enabled()) return false
        if (!runCatching {
                ctx.packageManager.getApplicationInfo(GOOGLE_PACKAGE, 0)
            }.isSuccess) return false
        val binder = contextualSearchBinder() ?: return false
        return runCatching {
            val asInterface = asInterface() ?: return@runCatching false
            val target = startMethod() ?: return@runCatching false
            val proxy = asInterface.invoke(null, binder) ?: return@runCatching false
            if (target.hasConfig) target.method.invoke(proxy, entryPoint, null)
            else target.method.invoke(proxy, entryPoint)
            true
        }.getOrDefault(false)
    }

    /** 兜底入口：显式广播给 Google App 的 Contextual Search Activity。 */
    fun triggerByIntent(ctx: Context?): Boolean {
        if (ctx == null || !enabled()) return false
        return runCatching {
            val intent = Intent(ACTION_LAUNCH).setPackage(GOOGLE_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolve = ctx.packageManager.resolveActivity(intent, 0)
            if (resolve == null) false
            else {
                ctx.startActivity(intent)
                true
            }
        }.getOrDefault(false)
    }

    private fun contextualSearchBinder(): IBinder? = runCatching {
        val m = getServiceMethod ?: Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
            .also { getServiceMethod = it }
        m.invoke(null, SERVICE_NAME) as? IBinder
    }.getOrNull()

    private fun asInterface(): Method? = asInterfaceMethod ?: runCatching {
        Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
            .getDeclaredMethod("asInterface", IBinder::class.java)
            .apply { isAccessible = true }
    }.getOrNull()?.also { asInterfaceMethod = it }

    private fun startMethod(): StartMethod? {
        startMethod?.let { return it }
        val iface = runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager")
        }.getOrNull() ?: return null
        val withConfig = runCatching {
            val cfg = Class.forName("android.app.contextualsearch.ContextualSearchConfig")
            iface.getDeclaredMethod("startContextualSearch", Int::class.javaPrimitiveType!!, cfg)
                .apply { isAccessible = true }
        }.getOrNull()
        val result = if (withConfig != null) {
            StartMethod(withConfig, true)
        } else {
            runCatching {
                iface.getDeclaredMethod("startContextualSearch", Int::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
            }.getOrNull()?.let { StartMethod(it, false) }
        }
        return result?.also { startMethod = it }
    }
}

/** 避免 Hook 进程为了一个常量去加载 app 侧协议对象里的 UI 依赖。 */
private object QuroAgentBridgeProtocolRefs {
    const val SYSTEM_UID = 1000
}
