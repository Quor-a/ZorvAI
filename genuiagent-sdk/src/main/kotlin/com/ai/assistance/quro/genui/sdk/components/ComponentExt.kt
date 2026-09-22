package com.ai.assistance.quro.genui.sdk.components

import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/**
 * 从组件属性中获取字符串值
 */
fun UIComponent.propString(key: String): String? {
    val value = properties[key]
    return (value as? JsonPrimitive)?.contentOrNull
}

/**
 * 从组件属性中获取布尔值，支持默认值
 */
fun UIComponent.propBool(key: String, default: Boolean = false): Boolean {
    val value = properties[key]
    val primitive = value as? JsonPrimitive ?: return default
    val boolValue = primitive.booleanOrNull
    if (boolValue != null) {
        return boolValue
    }
    // 回退到字符串比较
    val strValue = primitive.contentOrNull?.lowercase(Locale.ROOT)
    return strValue == "true"
}

/**
 * 从组件属性中获取整数值，支持默认值
 */
fun UIComponent.propInt(key: String, default: Int = 0): Int {
    val value = properties[key]
    val primitive = value as? JsonPrimitive ?: return default
    return primitive.intOrNull ?: default
}

/**
 * 从组件属性中获取浮点数值，支持默认值
 */
fun UIComponent.propFloat(key: String, default: Float = 0f): Float {
    val value = properties[key]
    val primitive = value as? JsonPrimitive ?: return default
    return primitive.floatOrNull ?: default
}

/**
 * 从组件属性中获取字符串列表
 */
fun UIComponent.propStringList(key: String): List<String> {
    val value = properties[key]
    val array = value as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * 从组件属性中获取JsonObject
 */
fun UIComponent.propObject(key: String): JsonObject? {
    val value = properties[key]
    return value as? JsonObject
}

/**
 * 从组件属性中获取字符串值，并解析数据绑定
 */
fun UIComponent.propStringResolved(key: String, ctx: RenderContext): String? {
    val value = propString(key) ?: return null
    val resolved = ctx.state.resolveBinding(value)
    return resolved ?: value
}
