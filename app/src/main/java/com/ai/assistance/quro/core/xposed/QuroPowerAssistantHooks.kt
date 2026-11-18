package com.ai.assistance.quro.core.xposed

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 长按电源键 / 语音键的目标分发（system_server，净室实现）。
 *
 * 两条链路：
 *  · **ColorOS**：`PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage(Message)`
 *    的 `what = 0x3F3` 就是「长按电源键唤起语音助手」。
 *  · **HyperOS**：`ShortCutActionsUtils.triggerFunction("launch_voice_assistant",
 *    "long_press_power_key", ...)`。
 *
 * 关键修正：OplusSpeechHandler 自己是 Handler，**没有 mContext**。
 * 真正的上下文在 `this$0`（PhoneWindowManagerExtImpl）的 `mPhoneWindowManager` 里。
 * 旧实现直接读 handler 的 mContext 必然拿到 null，于是每次都默默放行——
 * 这就是「设置了目标却毫无反应」的直接原因。
 *
 * 目标分发：
 *  · vendor → 完全放行原链路（连震动都不拦）；
 *  · quro / gemini → 优先走 voiceinteraction 会话（系统原生路径），
 *    失败退 Activity，两者都失败才放行原逻辑，绝不制造「按了没反应」。
 */
internal object QuroPowerAssistantHooks {

    private const val OPLUS_SPEECH_HANDLER =
        "com.android.server.policy.PhoneWindowManagerExtImpl\$OplusSpeechHandler"
    private const val SHORTCUT_UTILS = "com.miui.server.input.util.ShortCutActionsUtils"
    private const val PHONE_WINDOW_MANAGER = "com.android.server.policy.PhoneWindowManager"
    private const val OPLUS_ASSIST_WHAT = 0x3F3

    private const val HAPTIC_EFFECT_ID = 0
    private const val HAPTIC_REASON = "Speech - Long Press"
    private const val DEDUP_WINDOW_MS = 1_000L

    private const val HOTWORD_INITIAL_DELAY_MS = 1_200L
    private const val HOTWORD_RETRY_MS = 1_400L
    private const val HOTWORD_MAX_ATTEMPTS = 3
    private const val SCREEN_OFF_COOLDOWN_MS = 8_000L

    @Volatile private var lastInterceptAt = 0L
    @Volatile private var hotwordGeneration = 0
    @Volatile private var lastScreenOffAt = 0L

    private val pendingLock = Any()
    private var pendingHandler: Handler? = null
    private var pendingTask: Runnable? = null

    fun install(module: XposedModule, classLoader: ClassLoader, hooks: QuroHookRegistrar) {
        installColorOsPower(hooks, classLoader)
        installHyperOsPower(hooks, classLoader)
        installHotwordSelfHeal(hooks, classLoader)
    }

    // ---- ColorOS ----

    private fun installColorOsPower(hooks: QuroHookRegistrar, classLoader: ClassLoader) {
        val clazz = QuroHookSupport.findClassOrNull(classLoader, OPLUS_SPEECH_HANDLER)
        val method = clazz?.let { QuroHookSupport.findMethod(it, "handleMessage", Message::class.java) }
        if (method == null) {
            hooks.missing(
                id = "power.coloros",
                description = "OplusSpeechHandler.handleMessage",
                detail = "当前 ROM 没有 ColorOS 电源键语音分发器（非 ColorOS 属正常）",
            )
            return
        }
        hooks.intercept(
            id = "power.coloros",
            executable = method,
            description = "OplusSpeechHandler.handleMessage（长按电源键）",
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != OPLUS_ASSIST_WHAT) return@intercept chain.proceed()
            val target = QuroModulePrefs.powerTarget()
            if (target == QuroModuleConfig.PowerTarget.VENDOR) return@intercept chain.proceed()

            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            val context = pwm?.let { QuroHookSupport.getFieldValue(it, "mContext") as? Context }
                ?: QuroHookEnvironment.systemContext(chain.getThisObject())
            if (context == null) {
                log("OplusSpeechHandler 未能解析 Context，回退原逻辑")
                return@intercept chain.proceed()
            }
            // 记一条「钩子真的被调用了」——这条比任何日志都关键：
            // 自检里如果连这条都没有，说明系统根本没走到这个入口，问题不在我们的决策逻辑。
            QuroHookReport.recordEvent(context, "power.coloros", "what=0x3F3 target=$target")
            if (!allowLaunchNow()) return@intercept null

            if (QuroAssistantRole.showSession(context, target)) {
                performHaptic(pwm)
                QuroHookReport.recordEvent(context, "power.coloros", "已唤起 $target")
                log("长按电源键已切到目标 $target")
                return@intercept null
            }
            // 会话与 Activity 都失败 → 后台修复默认助理配置，本次放行原生链路。
            QuroAssistantRole.schedule(context, force = true)
            QuroHookReport.recordEvent(context, "power.coloros", "唤起 $target 失败，已放行原生")
            log("目标 $target 唤起失败，已排后台修复并放行原生")
            chain.proceed()
        }
    }

    // ---- HyperOS ----

    private fun installHyperOsPower(hooks: QuroHookRegistrar, classLoader: ClassLoader) {
        val clazz = QuroHookSupport.findClassOrNull(classLoader, SHORTCUT_UTILS)
        if (clazz == null) {
            hooks.missing(
                id = "power.hyperos",
                description = "ShortCutActionsUtils.triggerFunction",
                detail = "当前 ROM 没有 HyperOS 快捷动作分发器（非 HyperOS 属正常）",
            )
            return
        }
        val boolType = Boolean::class.javaPrimitiveType!!
        val candidates = QuroHookSupport.findDeclaredMethods(clazz) { m ->
            m.name == "triggerFunction" &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterTypes.size >= 4 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == String::class.java &&
                m.parameterTypes[2] == Bundle::class.java
        }
        if (candidates.isEmpty()) {
            hooks.missing(
                id = "power.hyperos",
                description = "ShortCutActionsUtils.triggerFunction",
                detail = "类存在但没有 triggerFunction(String,String,Bundle,boolean) 重载",
            )
            return
        }
        candidates.forEachIndexed { index, m ->
            hooks.intercept(
                id = "power.hyperos.$index",
                executable = m,
                description = "ShortCutActionsUtils.triggerFunction/${m.parameterTypes.size}",
            ) { chain ->
                val function = chain.getArg(0) as? String
                val source = chain.getArg(1) as? String
                val isAssistant = function == "launch_voice_assistant" &&
                    (source == "long_press_power_key" || source == "imperceptible_press_power_key")
                if (!isAssistant) return@intercept chain.proceed()
                val target = QuroModulePrefs.powerTarget()
                if (target == QuroModuleConfig.PowerTarget.VENDOR) return@intercept chain.proceed()

                val context = QuroHookSupport.getFieldValue(chain.getThisObject(), "mContext") as? Context
                    ?: QuroHookSupport.getFieldValue(chain.getThisObject(), "this$0")
                        ?.let { QuroHookSupport.getFieldValue(it, "mContext") as? Context }
                    ?: QuroHookEnvironment.systemContext(chain.getThisObject())
                if (context == null) return@intercept chain.proceed()
                QuroHookReport.recordEvent(context, "power.hyperos", "$function/$source target=$target")
                if (!allowLaunchNow()) return@intercept true

                if (QuroAssistantRole.showSession(context, target)) {
                    QuroHookReport.recordEvent(context, "power.hyperos", "已唤起 $target")
                    log("HyperOS 长按电源键已切到 $target")
                    return@intercept true
                }
                QuroAssistantRole.schedule(context, force = true)
                QuroHookReport.recordEvent(context, "power.hyperos", "唤起 $target 失败，已放行原生")
                chain.proceed()
            }
        }
    }

    // ---- 热词自愈 ----

    private fun installHotwordSelfHeal(hooks: QuroHookRegistrar, classLoader: ClassLoader) {
        val clazz = QuroHookSupport.findClassOrNull(classLoader, PHONE_WINDOW_MANAGER)
        if (clazz == null) {
            hooks.missing(
                id = "hotword.pwm",
                description = "PhoneWindowManager",
                detail = "未找到 PhoneWindowManager",
            )
            return
        }
        val intType = Int::class.javaPrimitiveType!!
        val off = QuroHookSupport.findMethod(
            clazz, "screenTurnedOff", intType, Boolean::class.javaPrimitiveType!!,
        )
        if (off != null) {
            hooks.intercept(
                id = "hotword.screen-off",
                executable = off,
                description = "PhoneWindowManager.screenTurnedOff",
            ) { chain ->
                val displayId = chain.getArg(0) as? Int ?: -1
                val result = chain.proceed()
                if (displayId == 0 &&
                    QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.HOTWORD_SELF_HEAL)
                ) {
                    runCatching { scheduleHotwordResume(chain.getThisObject()) }
                }
                result
            }
        } else {
            hooks.missing(
                id = "hotword.screen-off",
                description = "PhoneWindowManager.screenTurnedOff",
                detail = "未找到 screenTurnedOff(int, boolean)",
            )
        }
        val on = QuroHookSupport.findMethod(clazz, "screenTurnedOn", intType)
        if (on != null) {
            hooks.intercept(
                id = "hotword.screen-on",
                executable = on,
                description = "PhoneWindowManager.screenTurnedOn",
            ) { chain ->
                val displayId = chain.getArg(0) as? Int ?: -1
                val result = chain.proceed()
                if (displayId == 0) cancelHotwordResume()
                result
            }
        }
    }

    private fun scheduleHotwordResume(owner: Any?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScreenOffAt < SCREEN_OFF_COOLDOWN_MS) return
        val context = QuroHookSupport.getFieldValue(owner, "mContext") as? Context ?: return
        val interactive = runCatching {
            context.getSystemService(PowerManager::class.java)?.isInteractive
        }.getOrNull() ?: return
        if (interactive) return

        lastScreenOffAt = now
        val handler = (QuroHookSupport.getFieldValue(owner, "mHandler") as? Handler)
            ?: Handler(Looper.getMainLooper())
        val generation = ++hotwordGeneration
        var attempt = 1
        lateinit var task: Runnable
        task = Runnable {
            if (generation != hotwordGeneration) return@Runnable
            if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.HOTWORD_SELF_HEAL)) {
                clearPending()
                return@Runnable
            }
            if (QuroAssistantRole.resumeSoftwareHotword()) {
                clearPending()
                return@Runnable
            }
            if (attempt >= HOTWORD_MAX_ATTEMPTS) {
                clearPending()
                return@Runnable
            }
            attempt++
            handler.postDelayed(task, HOTWORD_RETRY_MS)
        }
        synchronized(pendingLock) {
            pendingTask?.let { pendingHandler?.removeCallbacks(it) }
            pendingHandler = handler
            pendingTask = task
        }
        handler.postDelayed(task, HOTWORD_INITIAL_DELAY_MS)
    }

    private fun cancelHotwordResume() {
        hotwordGeneration++
        lastScreenOffAt = 0L
        clearPending()
    }

    private fun clearPending() {
        synchronized(pendingLock) {
            pendingTask?.let { pendingHandler?.removeCallbacks(it) }
            pendingHandler = null
            pendingTask = null
        }
    }

    // ---- 工具 ----

    /** 1 秒去重：长按消息常连发多帧，避免连开多个会话。 */
    private fun allowLaunchNow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptAt <= DEDUP_WINDOW_MS) return false
        lastInterceptAt = now
        return true
    }

    /**
     * `OplusSpeechHandler.this$0` 是 PhoneWindowManagerExtImpl；
     * `mPhoneWindowManager` 才是真正持有 mContext 的对象。逐个候选尝试，不写死层级。
     */
    private fun resolvePhoneWindowManager(handler: Any?): Any? {
        if (handler == null) return null
        val holder = QuroHookSupport.getFieldValue(handler, "this$0") ?: return null
        (QuroHookSupport.getFieldValue(holder, "mPhoneWindowManager"))?.let { return it }
        var current: Class<*>? = holder.javaClass
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.type.name == PHONE_WINDOW_MANAGER }
            if (field != null) {
                field.isAccessible = true
                runCatching { field.get(holder) }.getOrNull()?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun performHaptic(phoneWindowManager: Any?) {
        if (phoneWindowManager == null) return
        runCatching {
            val wrapper = QuroHookSupport.invokeNoArgs(phoneWindowManager, "getWrapper") ?: return
            val method = QuroHookSupport.findMethod(
                wrapper.javaClass,
                "performHapticFeedback",
                Int::class.javaPrimitiveType!!,
                String::class.java,
            ) ?: return
            method.invoke(wrapper, HAPTIC_EFFECT_ID, HAPTIC_REASON)
        }
    }

    private fun log(message: String) {
        runCatching { Log.i(QuroModuleConfig.LOG_TAG, "[电源键] $message") }
    }
}
