package com.ai.assistance.quro.aciapp

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.os.Build
import android.util.Log

/**
 * ACI 受控端 Service —— Application Module 的规范参考实现。
 *
 * 继承 [BaseAidlAciService]，在 [onCreateCapabilities] 声明能力、
 * [onCheckPermission] 做调用方白名单裁决、[onCall] 按 capability 派发。
 * 这是 ACI 架构「Application Module（应用模块）」层的承载：一个真实可安装的
 * `com.android.application` 模块，消费 [ai.aidl.aci.core] SDK 把自己暴露成可被 AI 调用的受控端。
 */
class AciAppService : BaseAidlAciService() {

    companion object {
        private const val TAG = "AciAppService"
        private const val ZORV_PKG = "com.ai.assistance.quro"
    }

    override fun onCreate() {
        try {
            super.onCreate()
            Log.i(TAG, "AciAppService created")
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate failed: ${e.message}", e)
        }
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        // 1. 连通性自测
        caps.add(
            Capability.create("echo", "回显传入文本，用于 ACI 连通性自测")
                .addParam("text", "string", true, "待回显文本")
                .addResult("text", "string", "回显结果")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 2. 设备信息
        caps.add(
            Capability.create("device_info", "返回本机设备信息（厂商/型号/系统版本/SDK）")
                .addResult("manufacturer", "string", "厂商")
                .addResult("model", "string", "型号")
                .addResult("android_version", "string", "Android 版本")
                .addResult("sdk_int", "string", "SDK 级别")
                .addResult("package", "string", "本应用包名")
                .addFlag(Capability.FLAG_NO_UI)
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 3. 健康状态
        caps.add(
            Capability.create("health", "返回受控端健康状态（存活/运行时长/能力数）")
                .addResult("status", "string", "ok")
                .addResult("uptime_ms", "string", "进程运行时长(ms)")
                .addResult("package", "string", "本应用包名")
                .addResult("capabilities", "string", "已注册能力数量")
                .addFlag(Capability.FLAG_NO_UI)
                .addFlag(Capability.FLAG_BACKGROUND)
        )
    }

    override fun onCheckPermission(request: AidlAciRequest, callerPkg: String): Boolean {
        // 仅放行 ZorvAI 主程序（控制端）与自身
        return callerPkg == ZORV_PKG || callerPkg == packageName
    }

    override fun onCall(request: AidlAciRequest): AidlAciResponse {
        return when (request.capability) {
            "echo" -> handleEcho(request)
            "device_info" -> handleDeviceInfo(request)
            "health" -> handleHealth(request)
            else -> AidlAciResponse.error(
                AidlAciError.CAPABILITY_NOT_FOUND,
                "unknown: ${request.capability}"
            )
        }
    }

    private fun handleEcho(req: AidlAciRequest): AidlAciResponse {
        val text = req.params?.getString("text") ?: ""
        return AidlAciResponse.success()
            .putResult("text", text)
    }

    private fun handleDeviceInfo(req: AidlAciRequest): AidlAciResponse {
        return AidlAciResponse.success()
            .putResult("manufacturer", safe { Build.MANUFACTURER } ?: "unknown")
            .putResult("model", safe { Build.MODEL } ?: "unknown")
            .putResult("android_version", safe { Build.VERSION.RELEASE } ?: "unknown")
            .putResult("sdk_int", Build.VERSION.SDK_INT.toString())
            .putResult("package", packageName)
    }

    private fun handleHealth(req: AidlAciRequest): AidlAciResponse {
        val uptime = android.os.SystemClock.elapsedRealtime()
        return AidlAciResponse.success()
            .putResult("status", "ok")
            .putResult("uptime_ms", uptime.toString())
            .putResult("package", packageName)
            .putResult("capabilities", (getCapabilitiesList()?.size ?: 0).toString())
    }

    private inline fun safe(block: () -> String): String? = runCatching(block).getOrNull()
}
