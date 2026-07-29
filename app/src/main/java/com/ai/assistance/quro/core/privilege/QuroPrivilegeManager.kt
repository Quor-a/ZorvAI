package com.ai.assistance.quro.core.privilege

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.receiver.QuroDeviceAdminReceiver
import com.ai.assistance.quro.service.QuroAccessibilityService
import kotlinx.coroutines.delay

/**
 * 权限子系统 - 仲裁大脑。
 *
 * 设计目标：分层、受控、可审计。不仅"拥有"权限，更要"管理"权限。
 * 核心理念：权限提升必须经过 Intent -> Policy Check -> User Confirmation -> Audit Log 四阶段。
 *
 * 职责：
 *  1) [probe] 探测 L1-L4 各级当前可用状态；
 *  2) [requestElevation] 四阶段仲裁（confirm 回调由 UI 层弹窗实现）；
 *  3) 审计日志统一写入 [QuroPrivilegeAudit]。
 * 不持有权限本身，只负责「探测 + 仲裁 + 审计」。
 */
enum class PrivilegeLevel { L1, L2, L3, L4 }

data class PrivilegeState(
    val level: PrivilegeLevel,
    val available: Boolean,
    val details: String = "",
)

class QuroPrivilegeManager(private val context: Context) {

    /** 获取当前系统可用的所有权限状态（L1-L4）。 */
    fun probe(): Map<PrivilegeLevel, PrivilegeState> = mapOf(
        PrivilegeLevel.L1 to checkAccessibility(),
        PrivilegeLevel.L2 to QuroShizukuBridge.state(context),
        PrivilegeLevel.L3 to checkDeviceAdmin(),
        PrivilegeLevel.L4 to checkRoot(),
    )

    // L1: 无障碍服务（基础自动化：UI 交互 / 屏幕内容读取）
    private fun checkAccessibility(): PrivilegeState {
        val enabled = isAccessibilityEnabled()
        return PrivilegeState(
            PrivilegeLevel.L1,
            enabled,
            if (enabled) "已连接" else "无障碍服务未开启",
        )
    }

    // L3: 设备管理员（锁屏 / 清除数据 / 禁用摄像头）
    private fun checkDeviceAdmin(): PrivilegeState {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, QuroDeviceAdminReceiver::class.java)
        val active = dpm.isAdminActive(admin)
        return PrivilegeState(
            PrivilegeLevel.L3,
            active,
            if (active) "已激活" else "未激活设备管理员",
        )
    }

    // L4: ROOT（内核级操作 / 系统文件修改）
    private fun checkRoot(): PrivilegeState = try {
        val p = Runtime.getRuntime().exec("su")
        val os = java.io.DataOutputStream(p.outputStream)
        os.writeBytes("echo 'root_check'\n")
        os.writeBytes("exit\n")
        os.flush()
        val ok = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        PrivilegeState(PrivilegeLevel.L4, ok, if (ok) "Root 访问可用" else "未获取 Root")
    } catch (e: Exception) {
        PrivilegeState(PrivilegeLevel.L4, false, "未获取 Root")
    }

    private fun isAccessibilityEnabled(): Boolean {
        // 主信号：服务实例已连接（系统已绑定 AccessibilityService）。这是最可靠的实时信号，
        // 只要用户在系统设置里开启本服务，onServiceConnected 即把 instance 置为非空。
        if (QuroAccessibilityService.instance != null) return true
        val cn = ComponentName(context, QuroAccessibilityService::class.java).flattenToString()
        // 权威系统设置：enabled_accessibility_services 是冒号分隔的 flatten 组件名列表，
        // 这是「服务是否真的被系统启用」的真相来源。
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
        if (enabled.split(':').any { it == cn || it.endsWith(".QuroAccessibilityService") }) return true
        // 兜底：管理器列表。部分机型 AccessibilityServiceInfo.id 返回的是短格式
        // （flattenToShortString，形如 pkg/.Svc），与 flattenToString 长格式不相等，故此处做结尾匹配兼容。
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val id = info.id
                id == cn || id.endsWith(".QuroAccessibilityService") ||
                    info.resolveInfo?.serviceInfo?.let {
                        ComponentName(it.packageName, it.name).flattenToString() == cn
                    } ?: false
            }
    }

    /**
     * 请求权限提升（核心仲裁逻辑），四阶段：
     *  阶段1 Intent（capsule 声明需要某等级）-> 阶段2 Policy Check（probe 是否已有）->
     *  阶段3 User Confirmation（[confirm] 回调，由 UI 层弹窗，挂起直至用户选择）-> 阶段4 Audit Log。
     *
     * @param confirm 返回用户是否确认授权。UI 层应在此弹出说明对话框并等待用户点击。
     * @return 提升后该等级是否可用。
     */
    suspend fun requestElevation(
        capsuleId: String,
        level: PrivilegeLevel,
        rationale: String,
        confirm: suspend () -> Boolean,
    ): Boolean {
        val current = probe()[level] ?: return false
        // 阶段2：已拥有则直接通过并记录
        if (current.available) {
            QuroPrivilegeAudit.log(context, capsuleId, level, "auto-grant (already available)", true)
            return true
        }
        // 策略关卡（在用户确认之前）：ALLOW 跳过询问直接放行；DENY 直接拒绝。
        when (QuroPolicyStore.getPriv(context)) {
            QuroPolicy.ALLOW -> {
                QuroPrivilegeAudit.log(context, capsuleId, level, "policy=ALLOW auto-grant", true)
                launchIntentFor(level)?.let { context.startActivity(it) }
                delay(1000)
                val after = probe()[level]?.available ?: false
                QuroPrivilegeAudit.log(context, capsuleId, level, "post-elevation probe (ALLOW)", after)
                return after
            }
            QuroPolicy.DENY -> {
                QuroPrivilegeAudit.log(context, capsuleId, level, "policy=DENY blocked", false)
                return false
            }
            QuroPolicy.ASK -> { /* 走下方原有四阶段确认 */ }
        }
        // 阶段3：用户确认
        val confirmed = confirm()
        if (!confirmed) {
            QuroPrivilegeAudit.log(context, capsuleId, level, "user denied elevation", false)
            return false
        }
        // 阶段4：记录授权动作（实际开启由用户在系统界面完成）
        QuroPrivilegeAudit.log(context, capsuleId, level, "user confirmed elevation", true)
        // 重新探测（用户已在系统界面开启）
        delay(800)
        val after = probe()[level]?.available ?: false
        QuroPrivilegeAudit.log(context, capsuleId, level, "post-elevation probe", after)
        return after
    }

    /** 返回引导用户开启对应等级权限的 Intent（L4 Root 无法引导，返回 null）。 */
    fun launchIntentFor(level: PrivilegeLevel): Intent? = when (level) {
        PrivilegeLevel.L1 -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            val cn = ComponentName(context, QuroAccessibilityService::class.java).flattenToString()
            putExtra(":settings:fragment_args_key", cn)
            putExtra("EXTRA_FRAGMENT_ARG_KEY", cn)
        }
        PrivilegeLevel.L2 -> {
            val shizukuPkg = "moe.shizuku.privileged.api"
            context.packageManager.getLaunchIntentForPackage(shizukuPkg)
                ?: Intent(Intent.ACTION_VIEW).apply { data = android.net.Uri.parse("market://details?id=$shizukuPkg") }
        }
        PrivilegeLevel.L3 -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, QuroDeviceAdminReceiver::class.java))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "授予 CapOS 设备管理员以启用高级系统管理能力")
        }
        PrivilegeLevel.L4 -> null
    }

    companion object {
        /** 各等级通道与说明，供 UI 展示。 */
        fun channelOf(level: PrivilegeLevel): String = when (level) {
            PrivilegeLevel.L1 -> "AccessibilityService"
            PrivilegeLevel.L2 -> "Shizuku / ADB Bridge"
            PrivilegeLevel.L3 -> "DevicePolicyManager"
            PrivilegeLevel.L4 -> "su / Magisk"
        }
    }
}
