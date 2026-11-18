package com.ai.assistance.quro.core.xposed

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 批次 2：无障碍保护（system_server，净室实现）。
 *
 * 行为：当系统把 Zorv AI 的无障碍服务从 `enabled_accessibility_services` 移除、
 * 或把总开关 `accessibility_enabled` 关掉时，把它写回去。
 * 覆盖「开机 / 解锁 / 系统设置变更 / 包更新后系统重算无障碍列表」这几类触发时机：
 *  · 系统配置变更 → [ContentObserver] 观察两个 Secure 键；
 *  · 开机、解锁、包更新后 ROM 重写列表 → 周期健康检查兜底。
 *
 * 不依赖 Root：本代码跑在 system_server（UID 1000）内，写 Secure Settings 是系统自身权限。
 * 不注册隐藏 API 广播：只用公开的 registerContentObserver / Handler.postDelayed，
 * 避免 Android 13+ registerReceiver 的 exported flag 差异把 system_server 搞崩。
 *
 * 安全约束与韧性（对齐 Eta AccessibilityServiceEnforcer）：
 *  · 开关关闭时一次都不写系统设置；
 *  · 只在「自己确实缺失」时写，写回内容保留用户其它无障碍服务；
 *  · 组件无效（未 enabled / 未 exported / 缺 BIND_ACCESSIBILITY_SERVICE）时不写；
 *  · owner 用户未解锁（直接启动态）时不写，避免在错误时机写入被系统再次清除；
 *  · 自己写回会再次触发观察者，用 enforcing 标志 + 时间窗去重，绝不形成写循环；
 *  · 连续写回失败进入修复限流（3 轮 + 60s 冷却），避免服务持续崩溃时无限拉起；
 *  · OEM 持续反删设置时逐步退避到 60s，稳定一分钟后恢复快速响应；
 *  · 全部 I/O 走系统共享后台线程，异常一律吞掉并记录，绝不抛到系统主线程。
 */
internal object QuroAccessibilityProtectionHooks {

    private const val HEALTH_INTERVAL_MS = 30_000L
    private const val DEDUP_WINDOW_MS = 1_000L
    private const val SERVICE_REBIND_GRACE_MS = 4_000L
    private const val LOG_INTERVAL_MS = 10_000L

    private const val REPAIR_MAX_ATTEMPTS = 3
    private const val REPAIR_COOLDOWN_MS = 60_000L

    private const val SECURE_ENABLED_SERVICES = "enabled_accessibility_services"
    private const val SECURE_MASTER = "accessibility_enabled"

    /**
     * 被保护的组件。Settings 里同时存在 flattenToString（全名）与
     * flattenToShortString（`.` 缩写）两种写法，校验时两种都认。
     */
    private const val SERVICE_FLAT = "com.ai.assistance.quro/com.ai.assistance.quro.service.QuroAccessibilityService"
    private const val SERVICE_SHORT = "com.ai.assistance.quro/.service.QuroAccessibilityService"
    private const val SERVICE_PACKAGE = "com.ai.assistance.quro"
    private const val SERVICE_CLASS = "com.ai.assistance.quro.service.QuroAccessibilityService"

    private const val DISABLED = 0
    private const val ENABLED = 1

    @Volatile
    private var enforcer: Enforcer? = null

    /**
     * 由 [QuroSystemServerHooks] 的统一生命周期锚点调用，避免重复 Hook 同一个方法。
     * 观察者与定时器只装一次；开关在每次 enforce 时即时读取，切换后无需重启。
     */
    @Synchronized
    fun onSystemReady(module: XposedModule, owner: Any?, classLoader: ClassLoader, hooks: QuroHookRegistrar) {
        if (enforcer != null) return
        val ctx = QuroHookEnvironment.systemContext(owner)
        if (ctx == null) {
            hooks.missing(
                id = "system.accessibility-protection",
                description = "system context（无障碍保护）",
                detail = "无法从 SystemServer 实例取得 Context",
            )
            return
        }
        val handler = QuroHookEnvironment.anyHandler(classLoader)
        if (handler == null) {
            hooks.missing(
                id = "system.accessibility-protection",
                description = "后台 Handler（无障碍保护）",
                detail = "BackgroundThread.getHandler 与主线程 Looper 均不可用",
            )
            return
        }
        val instance = Enforcer(ctx, handler, module)
        if (!instance.attach()) {
            hooks.missing(
                id = "system.accessibility-protection",
                description = "Settings ContentObserver（无障碍保护）",
                detail = "registerContentObserver 失败",
            )
            return
        }
        enforcer = instance
        hooks.skipped(
            id = "system.accessibility-protection",
            description = "无障碍保护观察者与周期检查",
            detail = "已装载；是否写回由 hook_accessibility_protection 开关即时决定",
        )
        module.log(
            Log.INFO,
            QuroModuleConfig.LOG_TAG,
            "无障碍保护已装载：观察 $SECURE_ENABLED_SERVICES / $SECURE_MASTER，健康检查每 ${HEALTH_INTERVAL_MS}ms",
        )
    }

    private fun containsOurs(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return raw.split(':').any { part ->
            val v = part.trim()
            v.equals(SERVICE_FLAT, ignoreCase = true) || v.equals(SERVICE_SHORT, ignoreCase = true)
        }
    }

    private class Enforcer(
        private val ctx: Context,
        private val handler: Handler,
        private val module: XposedModule,
    ) {
        @Volatile private var enforcing = false
        @Volatile private var lastApplyMs = 0L
        @Volatile private var attached = false

        // 修复限流：连续写回失败达到上限后进入冷却，避免服务持续崩溃时无限拉起。
        private var repairAttempts = 0
        private var lastRepairAt = 0L
        // 反删退避：OEM 持续反删时逐步拉长健康检查间隔，稳定一分钟后恢复快速响应。
        private var backoffLevel = 0
        private var lastRestoreAt = 0L

        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = schedule("settings_changed")
            override fun onChange(selfChange: Boolean, uri: Uri?) = schedule("settings_changed:$uri")
        }

        private val healthCheck = object : Runnable {
            override fun run() {
                runCatching { enforce("health_check") }
                if (attached) handler.postDelayed(this, healthIntervalMs())
            }
        }

        fun attach(): Boolean = runCatching {
            val resolver = ctx.contentResolver
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(SECURE_ENABLED_SERVICES), false, observer,
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(SECURE_MASTER), false, observer,
            )
            attached = true
            handler.post { runCatching { enforce("bootstrap") } }
            handler.postDelayed(healthCheck, healthIntervalMs())
            true
        }.getOrElse {
            attached = false
            false
        }

        private fun schedule(reason: String) {
            handler.post { runCatching { enforce(reason) } }
        }

        /**
         * 健康检查间隔：OEM 反删退避必须**单调拉长**，稳定 60s 后回落到基础间隔。
         * 对齐 Eta AccessibilityRestoreBackoff（300/1000/5000/30000 单调递增）；
         * 不能出现「反删一次后检查反而更频繁」的反向退避。
         */
        private fun healthIntervalMs(): Long {
            resetBackoffIfStable()
            return when (backoffLevel) {
                0 -> HEALTH_INTERVAL_MS
                1 -> 60_000L
                2 -> 120_000L
                else -> 300_000L
            }
        }

        /** 幂等：组件有效 + owner 已解锁 + 自己确实缺失 / 总开关关闭时才写，写回保留其它服务。 */
        private fun enforce(reason: String) {
            if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.ACCESSIBILITY_PROTECTION)) return
            // 组件无效或 owner 未解锁时不写：此时写入会被系统再次清除，等于白撞。
            if (!isExpectedServiceValid() || !isOwnerUnlocked()) return
            // 修复限流：连续失败冷却期内暂停写回，避免崩溃循环。
            if (inRepairCooldown()) return
            if (enforcing) return
            val now = System.currentTimeMillis()
            if (now - lastApplyMs < DEDUP_WINDOW_MS) return
            enforcing = true
            try {
                val resolver = ctx.contentResolver
                val current = runCatching {
                    Settings.Secure.getString(resolver, SECURE_ENABLED_SERVICES)
                }.getOrNull()
                if (!containsOurs(current)) {
                    val restored = if (current.isNullOrBlank()) SERVICE_FLAT else "$current:$SERVICE_FLAT"
                    val ok = runCatching {
                        Settings.Secure.putString(resolver, SECURE_ENABLED_SERVICES, restored)
                    }.getOrDefault(false)
                    lastApplyMs = System.currentTimeMillis()
                    if (ok) recordRestore() else recordRepairFailure()
                    log(if (ok) Log.INFO else Log.WARN, "无障碍服务写回 ${if (ok) "成功" else "失败"} reason=$reason")
                }
                val master = runCatching {
                    Settings.Secure.getInt(resolver, SECURE_MASTER, 0)
                }.getOrDefault(0)
                if (master != 1) {
                    val ok = runCatching {
                        Settings.Secure.putInt(resolver, SECURE_MASTER, 1)
                    }.getOrDefault(false)
                    lastApplyMs = System.currentTimeMillis()
                    if (ok) recordRestore() else recordRepairFailure()
                    log(if (ok) Log.INFO else Log.WARN, "无障碍总开关写回 ${if (ok) "成功" else "失败"} reason=$reason")
                }
            } finally {
                enforcing = false
            }
        }

        /** 组件有效性校验：服务必须 enabled、exported、持有 BIND_ACCESSIBILITY_SERVICE 权限。 */
        private fun isExpectedServiceValid(): Boolean = runCatching {
            val flags = PackageManager.ComponentInfoFlags.of(
                (PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong()
            )
            val info = ctx.packageManager.getServiceInfo(
                ComponentName(SERVICE_PACKAGE, SERVICE_CLASS), flags,
            )
            val app = info.applicationInfo
            info.enabled && app.enabled && info.exported &&
                info.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
        }.getOrDefault(false)

        private fun isOwnerUnlocked(): Boolean = runCatching {
            ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        }.getOrDefault(false)

        private fun recordRestore() {
            resetBackoffIfStable()
            backoffLevel = (backoffLevel + 1).coerceAtMost(3)
            lastRestoreAt = SystemClock.elapsedRealtime()
            repairAttempts = 0
        }

        private fun recordRepairFailure() {
            repairAttempts++
            lastRepairAt = SystemClock.elapsedRealtime()
        }

        private fun inRepairCooldown(): Boolean {
            val now = SystemClock.elapsedRealtime()
            return repairAttempts >= REPAIR_MAX_ATTEMPTS &&
                lastRepairAt != 0L && now - lastRepairAt < REPAIR_COOLDOWN_MS
        }

        private fun resetBackoffIfStable() {
            val now = SystemClock.elapsedRealtime()
            if (lastRestoreAt != 0L && now - lastRestoreAt >= REPAIR_COOLDOWN_MS) {
                backoffLevel = 0
                lastRestoreAt = 0L
            }
        }

        private fun log(priority: Int, msg: String) {
            runCatching { module.log(priority, QuroModuleConfig.LOG_TAG, "[无障碍保护] $msg") }
        }
    }
}
