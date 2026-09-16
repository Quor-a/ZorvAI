package com.ai.assistance.quro.plugin.engine.core

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.engine.bridge.AciBridge
import com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
import com.ai.assistance.quro.plugin.extension.PluginExtension
import java.io.File

internal class PluginContextImpl(
    override val pluginId: String,
    override val pluginVersion: String,
    private val hostContext: Context,
    private val hostCapabilities: Set<String>
) : PluginContext {

    private val prefs: SharedPreferences =
        hostContext.getSharedPreferences("quro_plugin_$pluginId", Context.MODE_PRIVATE)

    override fun register(extension: PluginExtension) {
        ExtensionRegistry.register(pluginId, extension)
        android.util.Log.i("QuroPlugin", "注册扩展 ${extension.type}/${extension.id}")
    }

    override fun unregisterAll() = ExtensionRegistry.unregisterPlugin(pluginId)

    override fun hasHostCapability(name: String): Boolean = name in hostCapabilities

    override fun log(tag: String, message: String) {
        // ★ Log.i 返回 Int，接口要求 Unit；必须用花括号体，不能写成 `= android.util.Log.i(...)`（返回类型不匹配）
        android.util.Log.i("QuroPlugin/[$pluginId]", "[$tag] $message")
    }

    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getBool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }

    override fun getFilesDir(): File =
        File(hostContext.filesDir, "plugins/$pluginId").apply { mkdirs() }

    /** 宿主 Application Context（插件访问系统服务用；插件与宿主同进程同权限） */
    override val appContext: Context get() = hostContext

    override suspend fun callCapability(capabilityId: String, args: Map<String, Any?>): ToolResult {
        // 先找插件能力，再找外部 ACI 能力
        val local = AciBridge.dispatchToPlugin(capabilityId, args)
        if (!local.startsWith("ERROR:")) return ToolResult.text(local)
        return AciBridge.invokeExternal(capabilityId, args)
    }
}
