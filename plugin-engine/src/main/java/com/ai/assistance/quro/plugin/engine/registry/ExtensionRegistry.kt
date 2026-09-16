package com.ai.assistance.quro.plugin.engine.registry

import com.ai.assistance.quro.plugin.extension.ExtensionType
import com.ai.assistance.quro.plugin.extension.PluginExtension

/**
 * 全局扩展点注册表。
 *
 * 宿主为每个 ExtensionType 准备一个「收纳槽」，插件往里放实现。
 * 宿主各功能模块只按类型取用，不认识具体插件 —— 这就是宿主无需改代码即可获得新能力的原因。
 */
object ExtensionRegistry {

    private val slots = java.util.concurrent.ConcurrentHashMap<ExtensionType,
            MutableMap<String, RegisteredExtension>>()

    data class RegisteredExtension(
        val pluginId: String,
        val extension: PluginExtension
    )

    fun register(pluginId: String, ext: PluginExtension) {
        slots.getOrPut(ext.type) { java.util.concurrent.ConcurrentHashMap() }[ext.id] =
            RegisteredExtension(pluginId, ext)
    }

    fun unregisterPlugin(pluginId: String) {
        slots.values.forEach { m ->
            m.entries.removeIf { it.value.pluginId == pluginId }
        }
    }

    fun <T : PluginExtension> list(type: ExtensionType): List<T> {
        @Suppress("UNCHECKED_CAST")
        return slots[type]?.values?.map { it.extension as T } ?: emptyList()
    }

    fun <T : PluginExtension> listByPlugin(pluginId: String, type: ExtensionType): List<T> {
        @Suppress("UNCHECKED_CAST")
        return slots[type]?.values
            ?.filter { it.pluginId == pluginId }
            ?.map { it.extension as T } ?: emptyList()
    }

    fun get(type: ExtensionType, id: String): PluginExtension? = slots[type]?.get(id)?.extension

    fun pluginIdOf(type: ExtensionType, id: String): String? = slots[type]?.get(id)?.pluginId

    fun countOf(pluginId: String): Int =
        slots.values.sumOf { m -> m.count { it.value.pluginId == pluginId } }

    /** 某插件注册的全部扩展（按类型分组，供插件面板展示） */
    fun allOfPlugin(pluginId: String): List<Pair<ExtensionType, PluginExtension>> =
        slots.entries.flatMap { (type, m) ->
            m.values.filter { it.pluginId == pluginId }.map { type to it.extension }
        }
}
