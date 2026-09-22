package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.text.dropLast
import kotlin.text.endsWith
import kotlin.text.toFloatOrNull

object DimensionSerializer : KSerializer<Dimension> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Dimension") {
            // 声明为对象结构，支持字符串（"10dp"、"match"、"50%"）和对象格式
            // 使用 CLASS kind 可以正确处理对象输入，避免 "Expected beginning of the string, but got {" 错误
            element("type", PrimitiveSerialDescriptor("type", PrimitiveKind.STRING), isOptional = true)
            element("value", PrimitiveSerialDescriptor("value", PrimitiveKind.FLOAT), isOptional = true)
            element("dp", PrimitiveSerialDescriptor("dp", PrimitiveKind.FLOAT), isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: Dimension) {
        // 序列化时仍然输出字符串形式，保持向后兼容
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Dimension {
        val jsonDecoder = decoder as? JsonDecoder ?: return Dimension.Wrap
        val element = jsonDecoder.decodeJsonElement()

        if (element is JsonPrimitive) {
            // 字符串/数字形式（最常见）
            val content = element.content
            content.toFloatOrNull()?.let {
                return Dimension.Fixed(it)
            }
            if (content == "match" || content == "fill") {
                return Dimension.Match
            }
            if (content == "wrap") {
                return Dimension.Wrap
            }
            if (content.endsWith("%")) {
                val value = content.dropLast(1).toFloatOrNull() ?: 0f
                return Dimension.Weight(value / 100f)
            }
            if (content.endsWith("dp", ignoreCase = true)) {
                val value = content.dropLast(2).toFloatOrNull() ?: 0f
                return Dimension.Fixed(value)
            }
            return content.toFloatOrNull()?.let { Dimension.Fixed(it) } ?: Dimension.Wrap
        }

        if (element is JsonObject) {
            // 对象格式：支持 AI 输出对象形式的 Dimension
            val obj = element

            // 简写形式：{"dp": 10}
            obj["dp"]?.floatOrNull()?.let {
                return Dimension.Fixed(it)
            }

            // 标准形式：{"type": "fixed", "value": 10}
            val type = obj["type"]?.contentOrNull()?.lowercase()
            val value = obj["value"]?.floatOrNull() ?: 0f

            return when (type) {
                "fixed" -> Dimension.Fixed(value)
                "match", "fill" -> Dimension.Match
                "wrap" -> Dimension.Wrap
                "weight", "percent", "fraction" -> {
                    val fraction = if (value > 1f) value / 100f else value
                    Dimension.Weight(fraction)
                }
                else -> Dimension.Wrap
            }
        }

        return Dimension.Wrap
    }

    private fun kotlinx.serialization.json.JsonElement.floatOrNull(): Float? {
        return (this as? JsonPrimitive)?.content?.toFloatOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement.contentOrNull(): String? {
        return (this as? JsonPrimitive)?.content
    }
}
