package com.ai.assistance.quro.genui.sdk.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * GenUI 状态存储，基于 StateFlow 实现响应式状态管理
 * 支持路径解析、数据绑定表达式解析等
 */
open class GenUIStateStore(
    initial: Map<String, JsonElement> = emptyMap()
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Map<String, JsonElement>> = _state.asStateFlow()

    fun snapshot(): Map<String, JsonElement> = _state.value

    operator fun get(key: String): JsonElement? = _state.value[key]

    fun getString(key: String): String? {
        val value = _state.value[key]
        return value?.jsonPrimitive?.contentOrNull ?: value?.toString()
    }

    fun getBoolean(key: String): Boolean? {
        val primitive = _state.value[key] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: (primitive.contentOrNull == "true")
    }

    operator fun set(key: String, value: JsonElement) {
        _state.value = _state.value + (key to value)
    }

    operator fun set(key: String, value: String) {
        set(key, JsonPrimitive(value))
    }

    operator fun set(key: String, value: Boolean) {
        set(key, JsonPrimitive(value))
    }

    operator fun set(key: String, value: Number) {
        set(key, JsonPrimitive(value))
    }

    fun toggle(key: String) {
        val current = getBoolean(key) ?: false
        set(key, !current)
    }

    /**
     * 根据点分路径解析嵌套的 JsonElement
     */
    fun resolvePath(path: String): JsonElement? {
        val parts = path.split(".")
        var current: JsonElement? = _state.value[parts.first()]
        for (i in 1 until parts.size) {
            val obj = current as? JsonObject ?: return null
            current = obj[parts[i]] ?: return null
        }
        return current
    }

    /**
     * 根据点分路径解析字符串值
     */
    fun resolvePathString(path: String): String? {
        val element = resolvePath(path) ?: return null
        return (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
    }

    /**
     * 解析数据绑定表达式，支持 ${state.xxx} 格式
     */
    fun resolveBinding(expression: String): String? {
        if (expression.isBlank()) return null

        val trimmed = expression.trim()

        // 单个绑定表达式
        if (trimmed.startsWith("\${") && trimmed.endsWith("}")) {
            val path = trimmed.removePrefix("\${").removeSuffix("}").removePrefix("state.")
            return resolvePathString(path) ?: expression
        }

        // 字符串模板中的多个绑定
        val result = StringBuilder()
        var i = 0
        while (i < trimmed.length) {
            val next = i + 1
            if (next < trimmed.length && trimmed[i] == '$' && trimmed[next] == '{') {
                val start = i + 2
                val endIndex = trimmed.indexOf('}', start)
                if (endIndex > next) {
                    val path = trimmed.substring(start, endIndex).trim().removePrefix("state.")
                    val resolved = resolvePathString(path) ?: trimmed.substring(i, endIndex + 1)
                    result.append(resolved)
                    i = endIndex + 1
                    continue
                }
            }
            result.append(trimmed[i])
            i = next
        }
        return result.toString()
    }
}
