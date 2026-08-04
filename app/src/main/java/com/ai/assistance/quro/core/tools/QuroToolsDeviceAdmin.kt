package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject

/**
 * L3 设备管理员工具集（CapOS 通道）。
 *
 * 通过已激活的 DevicePolicyManager 实现：
 *   - lock_screen：强制锁屏
 *   - device_admin_status：查询设备管理员激活状态
 *   - set_camera_disabled：禁用/启用摄像头
 *
 * 所有操作都需要 L3 设备管理员已在 CapOS 权限子系统中激活。
 */

/** 强制锁屏。 */
class LockScreenTool : QuroTool {
    override val name = "lock_screen"
    override val description = "强制锁定手机屏幕（需 L3 设备管理员已激活）。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(context, com.ai.assistance.quro.receiver.QuroDeviceAdminReceiver::class.java)
        return if (!dpm.isAdminActive(admin)) "❌ 设备管理员未激活，请到 CapOS 权限子系统 → L3 → 请求授权"
        else try { dpm.lockNow(); "✅ 已发送锁屏指令" } catch (e: Exception) { "❌ 锁屏失败: ${e.message}" }
    }
}

/** 查询设备管理员状态。 */
class DeviceAdminStatusTool : QuroTool {
    override val name = "device_admin_status"
    override val description = "查询设备管理员（L3）当前激活状态和可用能力，无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(context, "com.ai.assistance.quro.receiver.QuroDeviceAdminReceiver")
        val active = dpm.isAdminActive(admin)
        return if (active) {
            """{"active":true,"capabilities":["lock_now","set_camera_disabled"]}"""
        } else {
            """{"active":false,"message":"L3 设备管理员未激活，请到 CapOS 权限子系统 → L3 设备管理员 → 请求授权"}"""
        }
    }
}

/** 禁用或启用摄像头。 */
class SetCameraDisabledTool : QuroTool {
    override val name = "set_camera_disabled"
    override val description = "禁用或启用设备的摄像头（需 L3 设备管理员已激活）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "disabled":{"type":"boolean","description":"true=禁用摄像头 / false=恢复摄像头（默认 true）"}
        }
    }"""
    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val disabled = args.optBoolean("disabled", true)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(context, "com.ai.assistance.quro.receiver.QuroDeviceAdminReceiver")
        return try {
            dpm.setCameraDisabled(admin, disabled)
            "✅ 摄像头已${if (disabled) "禁用" else "恢复"}"
        } catch (e: Exception) {
            "❌ 操作失败: ${e.message}"
        }
    }
}
