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

    /** 强制执行锁屏（需已激活设备管理员）。 */
    fun lockScreen(context: Context): String = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, QuroDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return "设备管理员未激活，无法锁屏"
        dpm.lockNow()
        "已发送锁屏指令"
    }.getOrElse { "锁屏失败：${it.message}" }
}
