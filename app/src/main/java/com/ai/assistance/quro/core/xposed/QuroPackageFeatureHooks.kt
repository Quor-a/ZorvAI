package com.ai.assistance.quro.core.xposed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Modifier

/**
 * 各目标包进程的功能域 Hook（净室实现）。
 *
 * 与上一版的根本区别：**只挂在真的会拿到数据的地方**。
 *  · 小布 / 超级小爱 → 各自的协议层（[QuroBreenoHooks] / [QuroXiaoAiHooks]），
 *    不再挂 Activity/Service 入口——那条路拿不到用户文本，也写不回气泡；
 *  · SystemUI → ColorOS 导航条长按（`OplusOcrScreenBusiness.onLongPressed`）+ 原生震感；
 *  · Google App → 浮窗语音补偿 + 机型资格补齐（改 Build 字段，不是只改 SystemProperties）；
 *  · ColorOS 识屏 → `CollectInfoActivity` 双指取词改走一圈即搜；
 *  · 小米桌面 → 手势管理器 / invokeOmni / 导航条事件三选一。
 *
 * 每个功能域独立记账，一个域找不到目标不影响其它域；找不到就记 MISSING，
 * 绝不"假装装上了"。
 */
internal object QuroPackageFeatureHooks {

    private const val OCR_BUSINESS = "com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenBusiness"
    private const val VIBRATION_HELPER = "com.oplus.systemui.navigationbar.gesture.VibrationHelper"
    private const val FLOATY_ACTIVITY =
        "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"
    private const val SYSTEM_PROPERTIES = "android.os.SystemProperties"
    private const val APP_PACKAGE_MANAGER = "android.app.ApplicationPackageManager"
    private const val ELIGIBLE_DEVICE_PROP = "ro.opa.eligible_device"
    private const val COLOR_DIRECT_COLLECT_ACTIVITY = "com.coloros.directui.ui.CollectInfoActivity"
    private const val COLOR_DIRECT_EXTRA_DIRECT_EXT = "directExt"

    private const val OCR_HAPTIC_EFFECT_ID = 1
    private const val VOICE_COMMAND_DELAY_MS = 350L
    private const val VOICE_COMMAND_DEDUP_MS = 6_000L

    /** 浮窗语音补发按 Activity 实例去重，不同实例互不影响（对齐 Eta 的 WeakHashMap 语义）。 */
    private val voiceCommandDedup = java.util.WeakHashMap<Activity, Long>()

    fun install(module: XposedModule, param: PackageReadyParam, processName: String): List<QuroHookEntry> {
        val pkg = param.packageName
        val hooks = QuroHookRegistrar(module, pkg)
        val loader = param.classLoader
        return hooks.install {
            when (pkg) {
                QuroModuleConfig.SYSTEM_UI -> installGestureBarCircleToSearch(this, loader)

                QuroModuleConfig.GOOGLE -> {
                    installGoogleEligibility(this, loader)
                    installFloatyVoiceCommand(this, loader)
                }

                QuroModuleConfig.BREENO -> QuroBreenoHooks.install(module, loader, this)

                QuroModuleConfig.XIAOAI -> QuroXiaoAiHooks.install(module, loader, this)

                QuroModuleConfig.COLOR_DIRECT -> installColorDirectCircleToSearch(this, loader)

                QuroModuleConfig.MIUI_HOME, QuroModuleConfig.MIUI_GLOBAL_HOME ->
                    installLauncherGesture(this, loader)

                QuroModuleConfig.AIMEMORY -> skipped(
                    id = "aimemory.not-wired",
                    description = "小布记忆",
                    detail = "记忆读取需要解密其数据库；本版本不接管，避免读到一半的半成品",
                )

                else -> skipped(
                    id = "package.no-domain",
                    description = "$pkg 未绑定功能域",
                    detail = "process=$processName",
                )
            }
        }
    }

    // ---- SystemUI：导航条长按 → 一圈即搜 ----

    private fun installGestureBarCircleToSearch(hooks: QuroHookRegistrar, loader: ClassLoader) {
        val clazz = QuroHookSupport.findClassOrNull(loader, OCR_BUSINESS)
        val method = clazz?.let { QuroHookSupport.findMethod(it, "onLongPressed") }
        if (method == null) {
            hooks.missing(
                id = "systemui.ocr-long-press",
                description = "OplusOcrScreenBusiness.onLongPressed",
                detail = "当前 ROM 没有 ColorOS 识屏长按分发器（非 ColorOS 属正常）",
            )
            return
        }
        hooks.intercept(
            id = "systemui.ocr-long-press",
            executable = method,
            description = "OplusOcrScreenBusiness.onLongPressed（手势条长按）",
        ) { chain ->
            if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            // 上下文在业务对象自己的字段里，不在 Activity 上；逐个候选取，取不到就放行。
            val context = resolveContext(chain.getThisObject())
            if (context == null) {
                log("SystemUI 未能解析 Context，回退原识屏")
                return@intercept chain.proceed()
            }
            if (QuroContextualSearchHooks.trigger(context)) {
                performOcrHaptic(context, loader)
                return@intercept null
            }
            if (QuroContextualSearchHooks.triggerByIntent(context)) {
                performOcrHaptic(context, loader)
                return@intercept null
            }
            chain.proceed()
        }
    }

    private fun resolveContext(target: Any?): Context? =
        QuroHookSupport.invokeNoArgs(target, "getContext") as? Context
            ?: QuroHookSupport.getFieldValue(target, "mContext") as? Context
            ?: QuroHookSupport.getFieldValue(target, "context") as? Context
            ?: QuroHookSupport.getFieldValue(target, "mOcrContext") as? Context
            ?: QuroHookEnvironment.currentApplication()

    /**
     * 补一次原生导航条长按震动：不改震动资源、不伪造强度，直接调 SystemUI 自己的
     * `VibrationHelper.vibrateCustomized(Context, effectId, ...)`，手感与原生一致。
     */
    private fun performOcrHaptic(context: Context, loader: ClassLoader) {
        runCatching {
            val helperClass = QuroHookSupport.findClassOrNull(loader, VIBRATION_HELPER) ?: return
            val getInstance = QuroHookSupport.findMethod(helperClass, "getInstance", Context::class.java)
                ?: return
            val helper = getInstance.invoke(null, context) ?: return
            val vibrate = QuroHookSupport.findMethod(
                helper.javaClass,
                "vibrateCustomized",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            ) ?: return
            vibrate.invoke(helper, context, OCR_HAPTIC_EFFECT_ID, false)
        }
    }

    // ---- Google App：资格补齐 ----

    /**
     * Gemini / 一圈即搜的机型资格判定会读三处：`Build` 静态字段、
     * `SystemProperties.get/getBoolean`、`PackageManager.hasSystemFeature`。
     * 只改其中一处不够——所以三处都补，且一律读回校验。
     *
     * 注意：`Build.MANUFACTURER` 等是 static final，Android 14+ 已不能靠改
     * `Field.accessFlags` 去 final，必须走 Unsafe；见 [QuroHookSupport.setStaticStringField]。
     */
    private fun installGoogleEligibility(hooks: QuroHookRegistrar, loader: ClassLoader) {
        var spoofed = 0
        mapOf(
            "MANUFACTURER" to "samsung",
            "BRAND" to "samsung",
            "MODEL" to "SM-S928B",
            "PRODUCT" to "e3s",
            "DEVICE" to "e3s",
        ).forEach { (field, value) ->
            if (QuroHookSupport.setStaticStringField(android.os.Build::class.java, field, value)) spoofed++
        }
        if (spoofed == 0) {
            hooks.missing(
                id = "google.build-fields",
                description = "android.os.Build 资格字段",
                detail = "一个字段都没改写成功，Google 仍会判定为非合格设备",
            )
        }

        val sp = QuroHookSupport.findClassOrNull(loader, SYSTEM_PROPERTIES)
        if (sp == null) {
            hooks.skipped("google.system-properties", "SystemProperties", "未找到 SystemProperties")
        } else {
            val boolType = Boolean::class.javaPrimitiveType!!
            mapOf(
                "google.system-properties.get" to
                    QuroHookSupport.findMethod(sp, "get", String::class.java),
                "google.system-properties.get-default" to
                    QuroHookSupport.findMethod(sp, "get", String::class.java, String::class.java),
                "google.system-properties.get-boolean" to
                    QuroHookSupport.findMethod(sp, "getBoolean", String::class.java, boolType),
            ).forEach { (id, method) ->
                if (method == null) {
                    hooks.missing(id, "SystemProperties.${id.substringAfterLast('.')}", "签名不匹配")
                    return@forEach
                }
                hooks.intercept(
                    id = id,
                    executable = method,
                    description = "SystemProperties（合格设备标记）",
                ) { chain ->
                    if (chain.getArg(0) != ELIGIBLE_DEVICE_PROP) return@intercept chain.proceed()
                    if (method.returnType == Boolean::class.javaPrimitiveType) true else "true"
                }
            }
        }

        val apm = QuroHookSupport.findClassOrNull(loader, APP_PACKAGE_MANAGER)
        val features = QuroHookSupport.findDeclaredMethods(apm) { m ->
            m.name == "hasSystemFeature" && m.parameterTypes.isNotEmpty() &&
                m.parameterTypes[0] == String::class.java
        }
        if (features.isEmpty()) {
            hooks.missing(
                id = "google.feature",
                description = "ApplicationPackageManager.hasSystemFeature",
                detail = "未找到 hasSystemFeature(String, ...)",
            )
        }
        features.forEach { m ->
            hooks.intercept(
                id = "google.feature-${m.parameterTypes.size}",
                executable = m,
                description = "ApplicationPackageManager.hasSystemFeature/${m.parameterTypes.size}",
            ) { chain ->
                when (chain.getArg(0)) {
                    "com.google.android.feature.GOOGLE_BUILD",
                    "com.google.android.feature.GOOGLE_EXPERIENCE",
                    -> true
                    else -> chain.proceed()
                }
            }
        }
    }

    // ---- Google App：浮窗语音补偿 ----

    /**
     * 锁屏/亮屏唤起 Gemini 浮窗时，部分 ROM 只显示输入框不自动进入语音。
     * 在浮窗 `onResume` 后补发一次 `ACTION_VOICE_COMMAND`（6 秒去重）。
     *
     * 优先挂具体类 `FloatyActivity.onResume`：挂 `Activity.onResume` 等于把 Google App 里
     * 每一个 Activity 的 onResume 都过一遍，既重又容易误判。
     */
    private fun installFloatyVoiceCommand(hooks: QuroHookRegistrar, loader: ClassLoader) {
        val concrete = QuroHookSupport.findClassOrNull(loader, FLOATY_ACTIVITY)
            ?.let { runCatching { it.getDeclaredMethod("onResume").apply { isAccessible = true } }.getOrNull() }
        if (concrete != null) {
            hooks.intercept(
                id = "google.floaty-on-resume",
                executable = concrete,
                description = "FloatyActivity.onResume（Gemini 浮窗语音补偿）",
            ) { chain ->
                val result = chain.proceed()
                (chain.getThisObject() as? Activity)?.let { maybeVoiceCommand(it) }
                result
            }
            return
        }

        val onResume = QuroHookSupport.findMethod(Activity::class.java, "onResume")
        if (onResume == null) {
            hooks.missing(
                id = "google.floaty-on-resume",
                description = "Activity.onResume",
                detail = "未找到 Activity.onResume",
            )
            return
        }
        hooks.intercept(
            id = "google.floaty-on-resume",
            executable = onResume,
            description = "Activity.onResume（Gemini 浮窗语音补偿）",
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity ?: return@intercept result
            if (!isFloatyActivity(activity.javaClass.name)) return@intercept result
            maybeVoiceCommand(activity)
            result
        }
    }

    /** 开关与去重都在真正补发前重新判断，避免延迟期间被关掉。 */
    private fun maybeVoiceCommand(activity: Activity) {
        if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GEMINI_CIRCLE_TO_SEARCH)) return
        val locked = QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.LOCKSCREEN_VOICE_COMMAND)
        val unlocked = QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.SCREEN_ON_VOICE_COMMAND)
        if (!locked && !unlocked) return
        val now = System.currentTimeMillis()
        val last = voiceCommandDedup[activity] ?: 0L
        if (now - last < VOICE_COMMAND_DEDUP_MS) return
        voiceCommandDedup[activity] = now
        val fromKeyguard = isKeyguardLocked(activity)
        activity.window?.decorView?.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) {
                voiceCommandDedup.remove(activity)
                return@postDelayed
            }
            // 延迟期间锁屏状态可能变化：复查，变化则放弃，避免补发错误分支。
            if (isKeyguardLocked(activity) != fromKeyguard) {
                voiceCommandDedup.remove(activity)
                return@postDelayed
            }
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        setPackage(QuroModuleConfig.GOOGLE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.onFailure { voiceCommandDedup.remove(activity) }
        }, VOICE_COMMAND_DELAY_MS)
    }

    /** 锁屏状态读取，用于浮窗语音补发前/后的一致性复查。 */
    private fun isKeyguardLocked(context: Context): Boolean = runCatching {
        context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    private fun isFloatyActivity(name: String): Boolean =
        name == FLOATY_ACTIVITY || name.endsWith("FloatyActivity")

    // ---- ColorOS 识屏：双指取词 → 一圈即搜 ----

    /**
     * 锚点不是 `onCreate`，而是 Activity 里处理外发 Intent 的 **void(Intent)** 方法。
     *
     * 为什么不能挂 onCreate：双指识屏在 Activity 已存在时会走 `onNewIntent` → 同一个 handleIntent，
     * 挂 onCreate 只在首次创建时命中，第二次开始全部漏掉。
     * 方法名会混淆，所以按「实例方法 + 参数恰好一个 Intent + 返回 void」定位；不唯一时优先取名字含 handle 的。
     */
    private fun installColorDirectCircleToSearch(hooks: QuroHookRegistrar, loader: ClassLoader) {
        val clazz = QuroHookSupport.findClassOrNull(loader, COLOR_DIRECT_COLLECT_ACTIVITY)
        if (clazz == null) {
            hooks.missing(
                id = "colordirect.collect-activity",
                description = "CollectInfoActivity",
                detail = "当前 ROM 未提供识屏取词界面（非 ColorOS 属正常）",
            )
            return
        }
        val candidates = QuroHookSupport.findDeclaredMethods(clazz) { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE &&
                m.parameterTypes.size == 1 && m.parameterTypes[0] == Intent::class.java
        }
        val method = candidates.firstOrNull { it.name.contains("handle", ignoreCase = true) }
            ?: candidates.singleOrNull()
        if (method == null) {
            hooks.missing(
                id = "colordirect.collect-activity",
                description = "CollectInfoActivity.handleIntent",
                detail = "未唯一定位 void(Intent) 处理方法，保留系统双指识屏",
            )
            return
        }
        hooks.intercept(
            id = "colordirect.collect-activity",
            executable = method,
            description = "CollectInfoActivity Intent 处理入口（双指识屏）",
        ) { chain ->
            if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            val activity = chain.getThisObject() as? Activity
            val intent = chain.getArg(0) as? Intent
            if (activity == null || !isDoubleFingerCollect(intent)) return@intercept chain.proceed()
            if (QuroContextualSearchHooks.trigger(activity)) {
                runCatching { finishSilently(activity) }
                log("识屏双指取词已改走一圈即搜")
                null
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * 双指取词判定必须同时满足：`fingerTrigger == true` 且 `touchInfo.fingerCount == 2`。
     *
     * 只判 fingerCount 会把单指长按取词也吃掉——这正是上一版「双指识屏乱跳」的来源。
     */
    private fun isDoubleFingerCollect(intent: Intent?): Boolean {
        if (intent == null) return false
        val directExt = intent.getStringExtra(COLOR_DIRECT_EXTRA_DIRECT_EXT)
            ?: intent.extras?.getString(COLOR_DIRECT_EXTRA_DIRECT_EXT)
            ?: return false
        return runCatching {
            val json = org.json.JSONObject(directExt)
            json.optBoolean("fingerTrigger", false) &&
                json.optJSONObject("touchInfo")?.optInt("fingerCount", 0) == 2
        }.getOrDefault(false)
    }

    private fun finishSilently(activity: Activity) {
        runCatching { activity.finishAndRemoveTask() }
        runCatching {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    // ---- 小米桌面：长按导航条 → 一圈即搜 ----

    private fun installLauncherGesture(hooks: QuroHookRegistrar, loader: ClassLoader) {
        if (installNavStubGesture(hooks, loader)) return
        if (installInvokeOmni(hooks, loader)) return
        if (installNavBarEvent(hooks, loader)) return
        hooks.missing(
            id = "launcher.gesture",
            description = "小米桌面手势入口",
            detail = "三种手势锚点都不存在（桌面版本差异），桌面手势一圈即搜不可用",
        )
    }

    private fun installNavStubGesture(hooks: QuroHookRegistrar, loader: ClassLoader): Boolean {
        val clazz = QuroHookSupport.findClassOrNull(
            loader,
            "com.miui.home.recents.gesture.NavStubGestureEventManager",
        ) ?: return false
        val method = QuroHookSupport.findMethod(clazz, "handleLongPressEvent") ?: return false
        val application = QuroHookSupport.findClassOrNull(loader, "com.miui.home.launcher.Application")
        val getInstance = application?.let { QuroHookSupport.findMethod(it, "getInstance") }
        if (getInstance == null || !Modifier.isStatic(getInstance.modifiers)) return false
        return hooks.intercept(
            id = "launcher.nav-stub",
            executable = method,
            description = "NavStubGestureEventManager.handleLongPressEvent",
        ) { chain ->
            if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            val context = runCatching { getInstance.invoke(null) as? Context }.getOrNull()
            if (QuroContextualSearchHooks.trigger(
                    context,
                    QuroContextualSearchHooks.ENTRY_POINT_GESTURE_BAR,
                )
            ) null else chain.proceed()
        } != null
    }

    private fun installInvokeOmni(hooks: QuroHookRegistrar, loader: ClassLoader): Boolean {
        val clazz = QuroHookSupport.findClassOrNull(
            loader,
            "com.miui.home.recents.cts.CircleToSearchHelper",
        ) ?: return false
        val intType = Int::class.javaPrimitiveType!!
        val method = QuroHookSupport.findMethod(clazz, "invokeOmni", Context::class.java, intType, intType)
            ?: return false
        return hooks.intercept(
            id = "launcher.invoke-omni",
            executable = method,
            description = "CircleToSearchHelper.invokeOmni",
        ) { chain ->
            if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            if (QuroContextualSearchHooks.trigger(
                    chain.getArg(0) as? Context,
                    QuroContextualSearchHooks.ENTRY_POINT_GESTURE_BAR,
                )
            ) {
                if (method.returnType == Void.TYPE) null else true
            } else {
                chain.proceed()
            }
        } != null
    }

    private fun installNavBarEvent(hooks: QuroHookRegistrar, loader: ClassLoader): Boolean {
        val clazz = QuroHookSupport.findClassOrNull(
            loader,
            "com.miui.home.recents.cts.NavBarEventHelper",
        ) ?: return false
        val method = QuroHookSupport.findMethod(clazz, "onLongPress", MotionEvent::class.java) ?: return false
        return hooks.intercept(
            id = "launcher.nav-bar-event",
            executable = method,
            description = "NavBarEventHelper.onLongPress",
        ) { chain ->
            if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            val context = QuroHookSupport.getFieldValue(chain.getThisObject(), "mContext") as? Context
            if (QuroContextualSearchHooks.trigger(
                    context,
                    QuroContextualSearchHooks.ENTRY_POINT_GESTURE_BAR,
                )
            ) null else chain.proceed()
        } != null
    }

    private fun log(message: String) {
        runCatching { Log.i(QuroModuleConfig.LOG_TAG, message) }
    }
}
