package com.ai.assistance.quro.plugin.contract

import com.ai.assistance.quro.plugin.extension.PluginExtension
import java.io.File

/**
 * 插件运行上下文。宿主为每个已加载插件创建唯一实例。
 * 插件通过它注册扩展、访问宿主能力、读写自己的私有存储。
 */
interface PluginContext {

    val pluginId: String
    val pluginVersion: String

    /** 注册一个扩展点实现 —— 这是插件给 ZorvAI「加功能」的唯一入口 */
    fun register(extension: PluginExtension)

    fun unregisterAll()

    // ---------- 宿主能力（可选依赖：能用但不必需） ----------
    /** 宿主能力查询：isAvailable("llm") / "tts" / "memory" / "aci" 等 */
    fun hasHostCapability(name: String): Boolean

    fun log(tag: String, message: String)

    // ---------- 存储 ----------
    fun getString(key: String, default: String = ""): String
    fun putString(key: String, value: String)
    fun getBool(key: String, default: Boolean = false): Boolean
    fun putBool(key: String, value: Boolean)

    fun getFilesDir(): File

    /**
     * 宿主 Application Context。
     *
     * 给需要访问 Android 系统服务的插件用（电池 / 存储 / 网络 / 传感器 / 剪贴板…）。
     * ⚠ 插件与宿主同进程、同权限，拿到的是宿主 Application Context ——
     * 这也是「插件必须与宿主同签名」这条安全边界存在的原因。
     */
    val appContext: android.content.Context

    // ---------- 扩展点互调（插件之间） ----------
    /** 调用其他插件注册的 ACI 能力 */
    suspend fun callCapability(capabilityId: String, args: Map<String, Any?>): ToolResult
}
