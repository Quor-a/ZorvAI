package com.ai.assistance.quro.core.miniapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * ACI（Android Component Interface）模块：关联启动其他应用 / 组件。
 * 移植自 MiniAppFramework（com.miniapp），去品牌化为 QuroAI 的 MiniAppBridgeModule 协议。
 * 让 AI 生成的小程序可拉起第三方 App / 指定组件（HTML/JS 做不到的真·原生能力）。
 */
class AciModule(private val context: Context) : MiniAppBridgeModule {
    override val name = "aci"

    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "isEnabled" -> callback(0, true, null)
            "setEnabled" -> callback(0, true, null)
            "launchApp" -> launchApp(params, callback)
            "launchComponent" -> launchComponent(params, callback)
            "canLaunch" -> canLaunch(params, callback)
            else -> callback(-1, null, "method not found: $method")
        }
    }

    private fun launchApp(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val pkg = params.optString("packageName", "")
        if (pkg.isEmpty()) { callback(-1, null, "packageName is required"); return }
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                callback(0, true, null)
            } else callback(-1, null, "app not found: $pkg")
        }.onFailure { callback(-1, null, it.message) }
    }

    private fun launchComponent(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val pkg = params.optString("packageName", "")
        val comp = params.optString("componentName", "")
        if (pkg.isEmpty() || comp.isEmpty()) {
            callback(-1, null, "packageName and componentName are required"); return
        }
        runCatching {
            val intent = Intent().setClassName(pkg, comp)
            params.optString("action").takeIf { it.isNotEmpty() }?.let { intent.action = it }
            params.optString("data").takeIf { it.isNotEmpty() }?.let { intent.data = Uri.parse(it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            callback(0, true, null)
        }.onFailure { callback(-1, null, it.message) }
    }

    private fun canLaunch(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val pkg = params.optString("packageName", "")
        if (pkg.isEmpty()) { callback(-1, null, "packageName is required"); return }
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            callback(0, intent != null, null)
        }.onFailure { callback(-1, null, it.message) }
    }
}
