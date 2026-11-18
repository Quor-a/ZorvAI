package com.ai.assistance.quro.core.xposed

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * 系统侧「默认数字助理」配置与唤起（system_server，净室实现）。
 *
 * 三件事，全部是 Android 公开/hidden 框架 API，不依赖任何 ROM 私有类：
 *  1. 写 `Settings.Secure.assistant` 与 `voice_interaction_service`（按用户写）；
 *  2. 用 `RoleManager` 维护 `ROLE_ASSISTANT` 持有者（重启/切用户后系统会以角色为准）；
 *  3. 唤起：`IVoiceInteractionManagerService.showSessionForActiveService(...)`，
 *     锁屏场景优先 `launchVoiceAssistFromKeyguard()`。
 *
 * 关键修正（之前版本的致命缺陷）：`voice_interaction_service` 只接受真正的
 * VoiceInteractionService 组件。旧实现把「没有该组件」的应用写进去，等于让电源键彻底失效；
 * 现在本应用已带 `QuroVoiceInteractionService`，两个键才都可以写同一个组件。
 *
 * 目标为「保留厂商助手」时**只回滚自己写过的值**，绝不把用户原有的值抹成空串。
 */
internal object QuroAssistantRole {

    private const val BOOT_COMPLETED_PHASE = 1_000
    private const val SHOW_SOURCE_PUSH_TO_TALK = 1 shl 5
    private const val ROLE_WATCHDOG_MS = 17_000L
    private const val VERIFY_COOLDOWN_MS = 15_000L
    private const val REFRESH_COOLDOWN_MS = 5_000L

    private const val SECURE_ASSISTANT = "assistant"
    private const val SECURE_VOICE_INTERACTION = "voice_interaction_service"
    private const val ASSISTANT_ROLE = "android.app.role.ASSISTANT"

    private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    private const val GOOGLE_COMPONENT =
        "$GOOGLE_PACKAGE/com.google.android.voiceinteraction.GsaVoiceInteractionService"
    private const val SELF_PACKAGE = "com.ai.assistance.quro"
    private const val SELF_COMPONENT =
        "$SELF_PACKAGE/com.ai.assistance.quro.service.QuroVoiceInteractionService"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var systemContext: Context? = null
    @Volatile private var voiceInteractionStub: Any? = null
    @Volatile private var lastVerifiedAt = 0L
    @Volatile private var lastVerifiedTarget: String? = null
    @Volatile private var lastForcedAt = 0L
    @Volatile private var lastForcedTarget: String? = null
    private val inFlight = AtomicBoolean(false)

    /** 本模块会写入的两个组件的包名；回滚时只处理这些。 */
    private val managedComponents = setOf(GOOGLE_COMPONENT, SELF_COMPONENT)
    private val managedPackages = setOf(GOOGLE_PACKAGE, SELF_PACKAGE)

    // ---- Hook 安装 ----

    fun install(
        module: io.github.libxposed.api.XposedModule,
        classLoader: ClassLoader,
        hooks: QuroHookRegistrar,
    ) {
        val serviceClass = QuroHookSupport.findClassOrNull(
            classLoader,
            "com.android.server.voiceinteraction.VoiceInteractionManagerService",
        )
        if (serviceClass == null) {
            hooks.missing(
                id = "system.assistant-service",
                description = "VoiceInteractionManagerService",
                detail = "未找到语音交互服务，默认数字助理校正不可用",
            )
            return
        }

        val intType = Int::class.javaPrimitiveType!!
        val bootPhase = QuroHookSupport.findMethod(serviceClass, "onBootPhase", intType)
        if (bootPhase == null) {
            hooks.missing(
                id = "system.assistant-boot",
                description = "VoiceInteractionManagerService.onBootPhase",
                detail = "未找到 onBootPhase(int)",
            )
        } else {
            hooks.intercept(
                id = "system.assistant-boot",
                executable = bootPhase,
                description = "VoiceInteractionManagerService.onBootPhase（开机校正助理）",
            ) { chain ->
                val phase = chain.getArg(0) as? Int ?: -1
                val result = chain.proceed()
                val owner = chain.getThisObject()
                capture(owner)
                if (phase == BOOT_COMPLETED_PHASE) {
                    systemContext?.let { schedule(it, force = false) }
                }
                result
            }
        }

        // 切用户 / 解锁：系统可能已按角色重建实现，这里重新核验一次。
        listOf("onUserUnlocking" to 1, "onUserSwitching" to 2).forEach { (name, params) ->
            val method = QuroHookSupport.findDeclaredMethods(serviceClass) { m ->
                m.name == name && m.parameterTypes.size == params
            }.firstOrNull()
            if (method == null) {
                hooks.missing(
                    id = "system.assistant-$name",
                    description = "VoiceInteractionManagerService.$name",
                    detail = "未找到 $name/$params",
                )
                return@forEach
            }
            hooks.intercept(
                id = "system.assistant-$name",
                executable = method,
                description = "VoiceInteractionManagerService.$name（切用户后校正）",
            ) { chain ->
                val result = chain.proceed()
                capture(chain.getThisObject())
                systemContext?.let { schedule(it, force = false) }
                result
            }
        }
    }

    /** 从服务实例抓 mContext / mServiceStub，供后续唤起与重建使用。 */
    private fun capture(owner: Any?) {
        (QuroHookSupport.getFieldValue(owner, "mContext") as? Context)?.let { systemContext = it }
        QuroHookSupport.getFieldValue(owner, "mServiceStub")?.let { voiceInteractionStub = it }
    }

    // ---- 配置 ----

    /**
     * 按当前目标校正默认助理。由开机锚点、切用户与电源键兜底共同调用。
     * 同一时刻只允许一个校正任务在飞，避免 RoleManager 请求互相覆盖。
     */
    fun schedule(context: Context, force: Boolean) {
        // 对齐 Eta AssistantBinding.assistantSelectionAction 的三态决策：
        //  - target == OEM(VENDOR) → RESTORE_OEM，与 autoConfig 无关；
        //  - autoConfig 关闭且目标非 OEM → NONE，**保持现状不动作**；
        //  - 其余 → CONFIGURE_MANAGED。
        // 之前只按 autoConfig 判定就回滚，会在「目标=GEMINI/QURO 但关掉自动校正」时
        // 把托管绑定清成厂商原生（开机 / 每次电源键唤起失败后都会触发），与目标相反。
        val target = QuroModulePrefs.powerTarget()
        if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.AUTO_DEFAULT_ASSISTANT) &&
            target != QuroModuleConfig.PowerTarget.VENDOR
        ) return

        if (target == QuroModuleConfig.PowerTarget.VENDOR) {
            if (!inFlight.compareAndSet(false, true)) return
            val posted = mainHandler.post {
                try {
                    val userId = currentUserId()
                    if (hasManagedBinding(context, userId)) restoreOem(context, userId)
                } catch (t: Exception) {
                    log("回滚厂商助理异常：${t.javaClass.simpleName}")
                } finally {
                    inFlight.set(false)
                }
            }
            if (!posted) inFlight.set(false)
            return
        }
        if (!inFlight.compareAndSet(false, true)) return
        val posted = mainHandler.post {
            try {
                apply(context, force)
            } catch (t: Exception) {
                log("默认助理校正异常：${t.javaClass.simpleName}")
            } finally {
                inFlight.set(false)
            }
        }
        if (!posted) inFlight.set(false)
    }

    /** 当前是否存在本模块写入的托管绑定（Secure 值或角色持有者），用于 target=VENDOR 时判断是否回滚。 */
    private fun hasManagedBinding(context: Context, userId: Int): Boolean {
        val assistant = readSecure(context.contentResolver, SECURE_ASSISTANT, userId)
        val voice = readSecure(context.contentResolver, SECURE_VOICE_INTERACTION, userId)
        return (assistant != null && assistant in managedComponents) ||
            (voice != null && voice in managedComponents) ||
            managedPackages.any { hasRoleHolder(context, it, userId) }
    }

    fun apply(context: Context, force: Boolean) {
        val target = QuroModulePrefs.powerTarget()
        val userId = currentUserId()
        val now = SystemClock.uptimeMillis()
        if (!force && target == lastVerifiedTarget && now - lastVerifiedAt < VERIFY_COOLDOWN_MS) return
        if (force && target == lastForcedTarget && now - lastForcedAt < REFRESH_COOLDOWN_MS) return

        val expected = when (target) {
            QuroModuleConfig.PowerTarget.QURO -> SELF_COMPONENT
            QuroModuleConfig.PowerTarget.GEMINI -> GOOGLE_COMPONENT
            else -> null
        }

        if (expected == null) {
            restoreOem(context, userId)
            invalidate()
            return
        }

        // 目标应用没装就不要写，否则系统会指向一个不存在的组件。
        if (!QuroHookSupport.isPackageInstalled(context, packageOf(expected))) {
            log("目标组件未安装，跳过写入：$expected")
            return
        }

        writeSecure(context.contentResolver, SECURE_ASSISTANT, expected, userId)
        writeSecure(context.contentResolver, SECURE_VOICE_INTERACTION, expected, userId)
        addRoleHolder(context, packageOf(expected), userId)

        lastVerifiedAt = now
        lastVerifiedTarget = target
        if (force) {
            lastForcedAt = now
            lastForcedTarget = target
        }
        rebuildImplementation(userId, force = true)
        log("默认数字助理已设为 $expected（user=$userId）")
    }

    /** 回滚：只清理本模块写过的值，别人设置的值一律不动。 */
    private fun restoreOem(context: Context, userId: Int) {
        val resolver = context.contentResolver
        val assistant = readSecure(resolver, SECURE_ASSISTANT, userId)
        val voice = readSecure(resolver, SECURE_VOICE_INTERACTION, userId)
        if (assistant != null && assistant in managedComponents) {
            writeSecure(resolver, SECURE_ASSISTANT, null, userId)
        }
        if (voice != null && voice in managedComponents) {
            writeSecure(resolver, SECURE_VOICE_INTERACTION, null, userId)
        }
        managedPackages.forEach { pkg ->
            if (hasRoleHolder(context, pkg, userId)) clearRoleHolders(context, userId)
        }
        rebuildImplementation(userId, force = false)
        log("已回滚为厂商原生助理（user=$userId）")
    }

    // ---- 唤起 ----

    /**
     * 长按电源键 / 语音键的目标分发。
     *
     * 返回 true 表示本次已经处理，调用方应吞掉原消息。
     */
    fun showSession(context: Context?, target: String): Boolean {
        if (context == null) return false
        if (target == QuroModuleConfig.PowerTarget.VENDOR) return false
        val component = when (target) {
            QuroModuleConfig.PowerTarget.QURO -> SELF_COMPONENT
            QuroModuleConfig.PowerTarget.GEMINI -> GOOGLE_COMPONENT
            else -> return false
        }
        val pkg = packageOf(component)
        if (!QuroHookSupport.isPackageInstalled(context, pkg)) {
            log("目标未安装，保留原生行为：$pkg")
            return false
        }
        // 当前默认助理不是目标时，voiceinteraction 路径一定失败，直接交给 Activity 兜底。
        val current = readSecure(context.contentResolver, SECURE_VOICE_INTERACTION, currentUserId())
        if (current != component) {
            log("voice_interaction_service=$current，改用 Activity 兜底")
            return startActivity(context, pkg)
        }

        val service = resolveVoiceInteractionService()
        if (service != null) {
            if (isKeyguardLocked(context) && launchFromKeyguard(service)) return true
            if (showSessionForActive(service)) return true
        }
        return startActivity(context, pkg)
    }

    private fun resolveVoiceInteractionService(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, "voiceinteraction") as? IBinder ?: return@runCatching null
        val stub = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
        stub.getDeclaredMethod("asInterface", IBinder::class.java)
            .apply { isAccessible = true }
            .invoke(null, binder)
    }.getOrNull()

    private fun launchFromKeyguard(service: Any): Boolean = runCatching {
        val supports = service.javaClass.methods.firstOrNull {
            it.name == "activeServiceSupportsLaunchFromKeyguard" && it.parameterTypes.isEmpty()
        }?.invoke(service) as? Boolean ?: false
        if (!supports) return@runCatching false
        val launch = service.javaClass.methods.firstOrNull {
            it.name == "launchVoiceAssistFromKeyguard" && it.parameterTypes.isEmpty()
        } ?: return@runCatching false
        launch.invoke(service)
        log("已从锁屏唤起默认助理")
        true
    }.getOrDefault(false)

    private fun showSessionForActive(service: Any): Boolean = runCatching {
        val method = service.javaClass.methods.firstOrNull {
            it.name == "showSessionForActiveService" && it.parameterTypes.size == 5
        } ?: return@runCatching false
        val flags = SHOW_SOURCE_PUSH_TO_TALK
        method.invoke(service, android.os.Bundle(), flags, null, null, null) as? Boolean ?: false
    }.getOrDefault(false)

    /**
     * Activity 兜底：三条路依次尝试，绝不因为「只有 Service 注册了 ASSIST」就判定失败。
     *
     * 本应用的 `AssistantAssistService` 是 **Service** 形态的助手入口（与小布一致），
     * `resolveActivity` 对它永远返回 null；因此必须再查 `queryIntentServices` 并用
     * startService 唤起，否则「目标已配置但电源键没反应」会再次出现。
     */
    private fun startActivity(context: Context, pkg: String): Boolean {
        val actions = listOf(
            android.content.Intent.ACTION_ASSIST,
            android.content.Intent.ACTION_VOICE_COMMAND,
        )
        actions.forEach { action ->
            val intent = android.content.Intent(action).apply {
                setPackage(pkg)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (QuroHookSupport.resolvesActivity(context, intent)) {
                if (runCatching { context.startActivity(intent) }.isSuccess) {
                    log("已通过 $action（Activity）拉起 $pkg")
                    return true
                }
            }
            if (resolvesService(context, intent)) {
                if (runCatching { context.startService(intent) }.isSuccess) {
                    log("已通过 $action（Service）拉起 $pkg")
                    return true
                }
            }
        }
        return runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            log("已通过启动页拉起 $pkg")
            true
        }.getOrDefault(false)
    }

    private fun resolvesService(context: Context, intent: android.content.Intent): Boolean = runCatching {
        context.packageManager.queryIntentServices(intent, 0).orEmpty().isNotEmpty()
    }.getOrDefault(false)

    private fun isKeyguardLocked(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    /** 让系统重建 voice interaction 实现，使新写入的组件立刻生效（不必重启）。 */
    private fun rebuildImplementation(userId: Int, force: Boolean) {
        val stub = voiceInteractionStub ?: return
        runCatching {
            val init = stub.javaClass.methods.firstOrNull {
                it.name == "initForUser" && it.parameterTypes.size == 1
            }
            val switch = stub.javaClass.methods.firstOrNull {
                it.name == "switchImplementationIfNeeded" && it.parameterTypes.size == 1
            }
            if (init == null || switch == null) return@runCatching
            init.invoke(stub, userId)
            switch.invoke(stub, force)
        }
    }

    // ---- 角色 ----

    private fun addRoleHolder(context: Context, pkg: String, userId: Int) {
        mutateRole(
            context = context,
            method = "addRoleHolderAsUser",
            baseArgs = arrayOf(ASSISTANT_ROLE, pkg, 0, userHandle(userId)),
            label = "addRoleHolderAsUser",
        )
    }

    private fun clearRoleHolders(context: Context, userId: Int) {
        mutateRole(
            context = context,
            method = "clearRoleHoldersAsUser",
            baseArgs = arrayOf(ASSISTANT_ROLE, 0, userHandle(userId)),
            label = "clearRoleHoldersAsUser",
        )
    }

    private fun mutateRole(
        context: Context,
        method: String,
        baseArgs: Array<Any?>,
        label: String,
    ) {
        val roleManager = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull() ?: return
        val target = roleManager.javaClass.methods.firstOrNull {
            it.name == method && it.parameterTypes.size == baseArgs.size + 2
        } ?: run {
            log("RoleManager 缺少 $method")
            return
        }
        val completed = AtomicBoolean(false)
        val executor = Executor { runnable -> if (!mainHandler.post(runnable)) runnable.run() }
        val callback = Consumer<Boolean> { result -> completed.compareAndSet(false, result == true) }
        val args = arrayOfNulls<Any>(baseArgs.size + 2)
        baseArgs.copyInto(args, endIndex = baseArgs.size)
        args[baseArgs.size] = executor
        args[baseArgs.size + 1] = callback
        runCatching { target.invoke(roleManager, *args) }.onFailure {
            log("$label 调用失败：${it.javaClass.simpleName}")
            return
        }
        // 角色写入是异步的；回调超时后核验**真实 RoleManager 角色状态**。
        // 不能读 voice_interaction_service：那是 apply() 在发起角色请求前自己写好的，
        // 恒等于期望值，根本无法反映角色是否落地。
        mainHandler.postDelayed({
            if (completed.get()) return@postDelayed
            val userId = currentUserId()
            val target = QuroModulePrefs.powerTarget()
            val expected = when (target) {
                QuroModuleConfig.PowerTarget.QURO -> SELF_COMPONENT
                QuroModuleConfig.PowerTarget.GEMINI -> GOOGLE_COMPONENT
                else -> null
            }
            val reached = if (expected != null) {
                hasRoleHolder(context, packageOf(expected), userId)
            } else {
                managedPackages.none { hasRoleHolder(context, it, userId) }
            }
            if (reached) {
                rebuildImplementation(userId, force = false)
                log("$label 回调超时，已核验真实角色生效并重建实现")
            } else {
                log("$label 回调超时，真实角色未落地，等待下次校正")
            }
        }, ROLE_WATCHDOG_MS)
    }

    private fun hasRoleHolder(context: Context, pkg: String, userId: Int): Boolean = runCatching {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return@runCatching false
        val method = roleManager.javaClass.methods.firstOrNull {
            it.name == "getRoleHoldersAsUser" && it.parameterTypes.size == 2
        } ?: return@runCatching false
        @Suppress("UNCHECKED_CAST")
        val holders = method.invoke(roleManager, ASSISTANT_ROLE, userHandle(userId)) as? List<String>
        holders.orEmpty().contains(pkg)
    }.getOrDefault(false)

    // ---- 系统设置读写 ----

    private fun readSecure(resolver: ContentResolver, key: String, userId: Int): String? = runCatching {
        Settings.Secure::class.java.getDeclaredMethod(
            "getStringForUser",
            ContentResolver::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
            .invoke(null, resolver, key, userId) as? String
    }.getOrNull()

    private fun writeSecure(resolver: ContentResolver, key: String, value: String?, userId: Int) {
        runCatching {
            Settings.Secure::class.java.getDeclaredMethod(
                "putStringForUser",
                ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
                .invoke(null, resolver, key, value, userId)
        }.onFailure { log("写入 $key 失败：${it.javaClass.simpleName}") }
    }

    private fun userHandle(userId: Int): Any = runCatching {
        val clazz = Class.forName("android.os.UserHandle")
        clazz.methods.firstOrNull {
            it.name == "of" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.invoke(null, userId)
            ?: clazz.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(userId)
    }.getOrElse { error("无法构造 UserHandle($userId)") }

    private fun currentUserId(): Int = runCatching {
        Class.forName("android.app.ActivityManager")
            .getDeclaredMethod("getCurrentUser").invoke(null) as Int
    }.getOrDefault(0)

    private fun packageOf(component: String): String = component.substringBefore('/')

    private fun invalidate() {
        lastVerifiedAt = 0L
        lastVerifiedTarget = null
    }

    private fun log(message: String) {
        runCatching { Log.i(QuroModuleConfig.LOG_TAG, "[助理] $message") }
    }

    /** 供电源键侧复用：当前目标对应的组件是否已就绪。 */
    fun isConfigured(context: Context?, target: String): Boolean {
        if (context == null) return false
        val component = when (target) {
            QuroModuleConfig.PowerTarget.QURO -> SELF_COMPONENT
            QuroModuleConfig.PowerTarget.GEMINI -> GOOGLE_COMPONENT
            else -> return false
        }
        return readSecure(context.contentResolver, SECURE_VOICE_INTERACTION, currentUserId()) == component
    }

    /**
     * 恢复 Google 的软件热词检测。
     *
     * 息屏后系统会关掉软件热词会话（省电），部分 ROM 不再自动恢复，导致「Hey Google」失灵。
     * 走的是系统内部结构：`mServiceStub.mImpl.mHotwordDetectionConnection.mDetectorSessions`
     * → 找到 `SoftwareTrustedHotwordDetectorSession` → 若未在监听则用它保存的回调重新
     * `startListeningFromMicLocked(null, callback)`。
     *
     * 全程反射、全程可失败；只要有一环对不上就返回 false，由调用方决定是否重试。
     */
    fun resumeSoftwareHotword(): Boolean {
        val stub = voiceInteractionStub ?: return false
        return runCatching {
            synchronized(stub) {
                val impl = QuroHookSupport.getFieldValue(stub, "mImpl") ?: return@synchronized false
                val component = QuroHookSupport.getFieldValue(impl, "mComponent") as? android.content.ComponentName
                if (component?.packageName != GOOGLE_PACKAGE) return@synchronized false

                val connection = QuroHookSupport.getFieldValue(impl, "mHotwordDetectionConnection")
                    ?: return@synchronized false
                val sessions = QuroHookSupport.getFieldValue(connection, "mDetectorSessions")
                    ?: return@synchronized false
                val session = firstSoftwareHotwordSession(sessions) ?: return@synchronized false

                val running = QuroHookSupport.getFieldValue(session, "mPerformingSoftwareHotwordDetection")
                    as? Boolean ?: false
                if (running) return@synchronized false

                val callback = QuroHookSupport.getFieldValue(session, "mSoftwareCallback")
                    ?: return@synchronized false
                val start = QuroHookSupport.findDeclaredMethods(impl.javaClass) { m ->
                    m.name == "startListeningFromMicLocked" && m.parameterTypes.size == 2
                }.firstOrNull() ?: return@synchronized false

                start.invoke(impl, null, callback)
                log("已恢复 Google 软件热词检测")
                true
            }
        }.getOrDefault(false)
    }

    private fun firstSoftwareHotwordSession(holder: Any): Any? {
        val target = "com.android.server.voiceinteraction.SoftwareTrustedHotwordDetectorSession"
        // ArraySet / ArrayList 都实现 Iterable 的不多，两种访问方式都试。
        (holder as? Iterable<*>)?.forEach { item ->
            if (item != null && item.javaClass.name == target) return item
        }
        val size = QuroHookSupport.invokeNoArgs(holder, "size") as? Int
        if (size != null) {
            for (index in 0 until size) {
                val item = QuroHookSupport.invokeByName(holder, "valueAt", index)
                    ?: QuroHookSupport.invokeByName(holder, "get", index)
                if (item != null && item.javaClass.name == target) return item
            }
        }
        return null
    }
}
