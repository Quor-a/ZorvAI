package com.ai.assistance.quro.core.cms

import android.content.Context
import android.provider.Settings
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.privilege.PrivilegeLevel
import com.ai.assistance.quro.core.privilege.QuroPrivilegeManager
import com.ai.assistance.quro.core.privilege.QuroShizukuBridge

/**
 * CMS v2 权限中介（原创，对应 Rust 版 PermissionBroker）：
 * 授权决策链 L4 全局 → L3 永久 → L2 会话 → 否则请求用户确认（UI 回调）。
 * 所有决策都写入审计日志（不可篡改）。
 * 在决策链之前先过全局策略关卡（[QuroPolicyStore]）：ALLOW 自动放行 / DENY 直接拒绝 / ASK 走原有确认。
 */
class QuroCmsBroker(context: Context) {

    private val appContext = context.applicationContext
    private val storage = QuroCmsStorage(context)

    /**
     * 仲裁某项权限。返回 true 表示允许继续执行。
     * @param uiRequest 当需要用户确认时回调，返回用户选择的授权级别；null 视为拒绝。
     */
    suspend fun ensureAuthorized(
        moduleId: String,
        perm: QuroCmsPermission,
        riskLevel: String,
        uiRequest: suspend (QuroCmsPermission) -> AuthorizationLevel?,
    ): Boolean {
        // 0) 所需权限级别当前在系统层面已可用 → 直接自动授权使用，不再询问（AI 自选通道）。
        //    例如 Root 已授予则 Critical 能力直接跑；Shizuku 已连则 Elevated 直接跑；
        //    普通(Normal) 永远可用。这样「AI 用权限时自动查已授权哪些、自己决定用哪个」得以实现。
        if (levelAvailable(perm.level)) {
            storage.setAuth(moduleId, perm.id, AuthorizationLevel.Temporary)
            storage.log(moduleId, perm.id, "authorize", "Temporary", "auto-available",
                "所需${perm.level.name}权限当前已可用，自动授权使用", riskLevel)
            return true
        }
        val existing = storage.getAuth(moduleId, perm.id)
        if (existing != null && existing != AuthorizationLevel.Denied) {
            storage.log(moduleId, perm.id, "authorize", existing.name, "auto",
                "已有${existing.name}授权，自动通过", riskLevel)
            return true
        }
        // 策略关卡：在用户确认之前判定。
        return when (QuroPolicyStore.getCms(appContext)) {
            QuroPolicy.ALLOW -> {
                storage.setAuth(moduleId, perm.id, AuthorizationLevel.Temporary)
                storage.log(moduleId, perm.id, "authorize", "Temporary", "policy-allow",
                    "策略=允许：自动授权(临时令牌)", riskLevel)
                true
            }
            QuroPolicy.DENY -> {
                storage.log(moduleId, perm.id, "authorize", "Denied", "policy-deny",
                    "策略=禁止：直接拒绝", riskLevel)
                false
            }
            QuroPolicy.ASK -> {
                val choice = uiRequest(perm)
                if (choice == null || choice == AuthorizationLevel.Denied) {
                    storage.log(moduleId, perm.id, "authorize", "Denied", "deny", "用户拒绝", riskLevel)
                    false
                } else {
                    storage.setAuth(moduleId, perm.id, choice)
                    storage.log(moduleId, perm.id, "authorize", choice.name, "grant", "用户授予${choice.name}", riskLevel)
                    true
                }
            }
        }
    }

    /**
     * 判断某权限级别当前在系统层面是否已可用（无需再弹授权）：
     * - [PermissionLevel.Normal]  永远可用（应用进程内 shell / Intent）。
     * - [PermissionLevel.Special] 悬浮窗已授权（Settings.canDrawOverlays）。
     * - [PermissionLevel.Elevated] Shizuku 已连接。
     * - [PermissionLevel.Critical] ROOT 已可用。
     * 供 ensureAuthorized 在提示用户之前先自查「通道是否已开」，已开即直接放行。
     */
    private fun levelAvailable(level: PermissionLevel): Boolean = when (level) {
        PermissionLevel.Normal -> true
        PermissionLevel.Special -> Settings.canDrawOverlays(appContext)
        PermissionLevel.Elevated -> QuroShizukuBridge.state(appContext).available
        PermissionLevel.Critical -> QuroPrivilegeManager(appContext).probe()[PrivilegeLevel.L4]?.available ?: false
    }

    /** 高危权限使用后回收临时令牌（最小权限原则）。 */
    fun reclaimTemporary(moduleId: String, permId: String) {
        if (storage.getAuth(moduleId, permId) == AuthorizationLevel.Temporary) {
            storage.revoke(moduleId, permId)
            storage.log(moduleId, permId, "reclaim", "Temporary", "auto", "临时令牌已回收", "high")
        }
    }

    fun revoke(moduleId: String, permId: String) {
        storage.revoke(moduleId, permId)
        storage.log(moduleId, permId, "revoke", "Denied", "user", "用户撤销授权", "n/a")
    }

    fun listAuths(): List<QuroCmsStorage.AuthEntry> = storage.listAuths()

    fun logExecution(moduleId: String, capabilityId: String, level: String, riskLevel: String, result: String) {
        val ok = !result.startsWith("⛔")
        storage.log(moduleId, capabilityId, "execute", level, if (ok) "success" else "blocked",
            result.take(120), riskLevel)
    }
}
