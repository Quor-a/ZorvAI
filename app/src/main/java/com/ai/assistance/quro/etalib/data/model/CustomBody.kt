package com.ai.assistance.quro.etalib.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Provider 或模型级别的自定义请求体字段。
 *
 * 网络层会递归合并到最终请求 JSON 中，模型级覆盖 Provider 级。
 */
@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
