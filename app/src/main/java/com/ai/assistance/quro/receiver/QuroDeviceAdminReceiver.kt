package com.ai.assistance.quro.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.core.privilege.PrivilegeLevel
import com.ai.assistance.quro.core.privilege.QuroPrivilegeAudit

/**
 * Quro 设备管理员接收器（CapOS L3 通道）：
 * 激活/停用回调写入审计日志；提供 lockScreen 强制执行锁屏（需已激活）。
 */
class QuroDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        QuroPrivilegeAudit.log(context, "capos.kernel", PrivilegeLevel.L3, "device-admin enabled", true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        QuroPrivilegeAudit.log(context, "capos.kernel", PrivilegeLevel.L3, "device-admin disabled", false)
    }

    /**
     * 用户在系统设置里点「停用设备管理员」时回调（E-6）。
     *
     * 返回的文案会显示在系统的停用确认页上。此前未实现该回调，用户看到的是一个
     * 没有任何影响说明的空白确认框，停用后 lock_screen / set_camera_disabled
     * 会直接失效却不知道为什么。这里明确告知代价，并顺带记一条审计。
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        QuroPrivilegeAudit.log(
            context,
            "capos.kernel",
            PrivilegeLevel.L3,
            "device-admin disable requested",
            false,
        )
        return "停用后将失去以下能力：\n" +
            "• 锁定屏幕（lock_screen）\n" +
            "• 禁用 / 恢复摄像头（set_camera_disabled）\n\n" +
            "AI 助手届时无法再执行这些系统管理操作。你随时可以在「系统权限 → L3 设备管理员」重新激活。"
    }

    /** 强制执行锁屏（需已激活设备管理员）。 */
    fun lockScreen(context: Context): String = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, QuroDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return "设备管理员未激活，无法锁屏"
        dpm.lockNow()
        "已发送锁屏指令"
    }.getOrElse { "锁屏失败：${it.message}" }
}
