package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject

object EdgeInsetsSerializer : KSerializer<EdgeInsets> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("EdgeInsets") {
            // 声明为对象结构，支持 all / horizontal / vertical / start / top / end / bottom 等字段
            // 使用 CLASS kind 可以正确处理对象输入，避免 "Expected beginning of the string, but got {" 错误
            element("all", PrimitiveSerialDescriptor("all", PrimitiveKind.FLOAT), isOptional = true)
            element("horizontal", PrimitiveSerialDescriptor("horizontal", PrimitiveKind.FLOAT), isOptional = true)
            element("vertical", PrimitiveSerialDescriptor("vertical", PrimitiveKind.FLOAT), isOptional = true)
            element("start", PrimitiveSerialDescriptor("start", PrimitiveKind.FLOAT), isOptional = true)
            element("top", PrimitiveSerialDescriptor("top", PrimitiveKind.FLOAT), isOptional = true)
            element("end", PrimitiveSerialDescriptor("end", PrimitiveKind.FLOAT), isOptional = true)
            element("bottom", PrimitiveSerialDescriptor("bottom", PrimitiveKind.FLOAT), isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: EdgeInsets) {
        val jsonEncoder = encoder as? JsonEncoder ?: run {
            // 非 JSON 编码器降级：编码为字符串形式
            encoder.encodeString(value.top.toString())
            return
        }
        val json = buildJsonObject {
            put("start", JsonPrimitive(value.start))
            put("top", JsonPrimitive(value.top))
            put("end", JsonPrimitive(value.end))
            put("bottom", JsonPrimitive(value.bottom))
        }
        jsonEncoder.encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): EdgeInsets {
        val jsonDecoder = decoder as? JsonDecoder ?: return EdgeInsets()
        val element = jsonDecoder.decodeJsonElement()

        if (element is JsonPrimitive) {
            // 支持数字/字符串形式的简写："16" 或 16 → EdgeInsets.all(16)
            val value = element.content.toFloatOrNull() ?: 0f
            return EdgeInsets.all(value)
        }

        if (element is JsonObject) {
            val obj = element.jsonObject
            val all = obj["all"]?.floatOrNull()
            if (all != null) {
                return EdgeInsets.all(all)
            }
            val horizontal = obj["horizontal"]?.floatOrNull() ?: 0f
            val vertical = obj["vertical"]?.floatOrNull() ?: 0f
            val start = obj["start"]?.floatOrNull() ?: horizontal
            val top = obj["top"]?.floatOrNull() ?: vertical
            val end = obj["end"]?.floatOrNull() ?: horizontal
            val bottom = obj["bottom"]?.floatOrNull() ?: vertical
            return EdgeInsets(start, top, end, bottom)
        }

        return EdgeInsets()
    }

    private fun JsonElement.floatOrNull(): Float? {
        return (this as? JsonPrimitive)?.content?.toFloatOrNull()
    }
}
