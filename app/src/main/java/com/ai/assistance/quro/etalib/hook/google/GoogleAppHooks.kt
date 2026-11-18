package com.ai.assistance.quro.etalib.hook.google

import com.ai.assistance.quro.etalib.core.HookSupport
import com.ai.assistance.quro.etalib.core.HookInstallation
import com.ai.assistance.quro.etalib.core.HookRegistrar
import com.ai.assistance.quro.etalib.core.ModuleConfig
import com.ai.assistance.quro.etalib.core.ModuleLogger
import com.ai.assistance.quro.etalib.core.safeLogType

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ai.assistance.quro.etalib.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.WeakHashMap

internal object GoogleAppHooks {
    private const val FLOATY_ACTIVITY_CLASS =
        "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"
    private const val VOICE_COMMAND_DELAY_MS = 350L
    private const val VOICE_COMMAND_ACTIVITY_DEDUP_MS = 6_000L

    private val voiceCommandAttemptLock = Any()
    private val voiceCommandAttempts = WeakHashMap<Activity, Long>()

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "GoogleApp")
        val logger = hooks.logger
        return hooks.install {
            // 机型伪装：在 Google 进程内伪装为 Samsung S24 Ultra，以放开一圈即搜能力。
            // Build 字段是启动时一次性写入的副作用，作为一圈即搜的底层依赖始终执行。
            setBuildField(logger, Build::class.java, "MANUFACTURER", ModuleConfig.SPOOF_MANUFACTURER)
            setBuildField(logger, Build::class.java, "BRAND", ModuleConfig.SPOOF_BRAND)
            setBuildField(logger, Build::class.java, "MODEL", ModuleConfig.SPOOF_MODEL)
            setBuildField(logger, Build::class.java, "PRODUCT", ModuleConfig.SPOOF_PRODUCT)
            setBuildField(logger, Build::class.java, "DEVICE", ModuleConfig.SPOOF_DEVICE)

            // 锁屏/亮屏补语音输入：开关在拦截回调里即时判断。
            hookFloatyVoiceCommand(hooks, classLoader)
        }
    }

    private fun hookFloatyVoiceCommand(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val floatyOnResumeMethod = HookSupport.findClassOrNull(classLoader, FLOATY_ACTIVITY_CLASS)
            ?.let { clazz ->
                runCatching {
                    clazz.getDeclaredMethod("onResume").apply { isAccessible = true }
                }.getOrNull()
            }
        if (floatyOnResumeMethod != null) {
            hooks.intercept(
                id = "google.floaty-on-resume",
                executable = floatyOnResumeMethod,
                description = "FloatyActivity.onResume(Google voice command)"
            ) { chain ->
                val result = chain.proceed()
                val activity = chain.getThisObject() as? Activity
                if (activity != null) {
                    scheduleVoiceCommand(activity, logger, fromKeyguard = activity.isKeyguardLocked())
                }
                result
            }
            return
        }

        val onResumeMethod = HookSupport.findMethod(Activity::class.java, "onResume")
        if (onResumeMethod == null) {
            hooks.missing(
                id = "google.floaty-on-resume",
                description = "FloatyActivity.onResume(Google voice command)",
                detail = "未找到 FloatyActivity/Activity.onResume()，跳过 Gemini 语音补偿"
            )
            return
        }

        hooks.intercept(
            id = "google.floaty-on-resume",
            executable = onResumeMethod,
            description = "Activity.onResume(Google Floaty voice command)"
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity
            if (activity?.javaClass?.name == FLOATY_ACTIVITY_CLASS) {
                scheduleVoiceCommand(activity, logger, fromKeyguard = activity.isKeyguardLocked())
            }
            result
        }
    }

    private fun scheduleVoiceCommand(activity: Activity, logger: ModuleLogger, fromKeyguard: Boolean) {
        // 锁屏走 LOCKSCREEN_VOICE_COMMAND，亮屏走 SCREEN_ON_VOICE_COMMAND；开关关闭则不补发。
        val prefKey = if (fromKeyguard) {
            Prefs.Keys.LOCKSCREEN_VOICE_COMMAND
        } else {
            Prefs.Keys.SCREEN_ON_VOICE_COMMAND
        }
        if (!Prefs.isEnabled(prefKey)) return
        // 只去重同一个 FloatyActivity 实例的重复 onResume；关闭后立刻新开浮窗不受影响。
        if (!markVoiceCommandAttempt(activity)) {
            return
        }

        val scenario = if (fromKeyguard) "锁屏" else "亮屏"
        Handler(Looper.getMainLooper()).postDelayed({
            // 即时关闭：开关在延迟任务排队期间可能已被用户关闭。
            if (!Prefs.isEnabled(prefKey)) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            if (activity.isFinishing || activity.isDestroyed) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            // 延迟期间锁屏状态发生变化则放弃：锁屏分支复查应仍锁屏，亮屏分支复查应仍解锁。
            if (activity.isKeyguardLocked() != fromKeyguard) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        setPackage(ModuleConfig.GOOGLE_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                logger.debug { "GSA: 已为${scenario} Gemini 浮窗补发 ACTION_VOICE_COMMAND" }
            }.onFailure { throwable ->
                clearVoiceCommandAttempt(activity)
                logger.warnThrottled("gsa_floaty_voice_command_failed") {
                    "GSA: ${scenario} Gemini 浮窗补发 ACTION_VOICE_COMMAND 失败，" +
                        "type=${throwable.safeLogType()}"
                }
            }
        }, VOICE_COMMAND_DELAY_MS)
    }

    private fun markVoiceCommandAttempt(activity: Activity): Boolean =
        synchronized(voiceCommandAttemptLock) {
            val now = SystemClock.uptimeMillis()
            val previous = voiceCommandAttempts[activity]
            if (previous != null && now - previous < VOICE_COMMAND_ACTIVITY_DEDUP_MS) {
                false
            } else {
                voiceCommandAttempts[activity] = now
                true
            }
        }

    private fun clearVoiceCommandAttempt(activity: Activity) {
        synchronized(voiceCommandAttemptLock) {
            voiceCommandAttempts.remove(activity)
        }
    }

    private fun Activity.isKeyguardLocked(): Boolean =
        runCatching {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)

    private fun setBuildField(
        logger: ModuleLogger,
        clazz: Class<*>,
        fieldName: String,
        value: String
    ) {
        val field = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }.getOrElse { throwable ->
            logger.warn("GSA: 找不到 Build.$fieldName，type=${throwable.safeLogType()}")
            return
        }

        try {
            try {
                field.set(null, value)
            } catch (_: IllegalAccessException) {
                // Android 17 禁止反射写入这些静态 final 字段；静态字段 API 位于内部 Unsafe。
                val unsafeClass = Class.forName("jdk.internal.misc.Unsafe")
                val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                    isAccessible = true
                }.get(null)
                val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                    .invoke(theUnsafe, field)
                val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                    .invoke(theUnsafe, field) as Long
                unsafeClass.getDeclaredMethod(
                    "putReferenceVolatile",
                    Any::class.java,
                    Long::class.javaPrimitiveType!!,
                    Any::class.java
                ).invoke(theUnsafe, base, offset, value)
            }
            check(field.get(null) == value) { "build_field_write_not_applied" }
        } catch (exception: Exception) {
            logger.warn("GSA: 修改 Build.$fieldName 失败，type=${exception.safeLogType()}")
        }
    }
}
