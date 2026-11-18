package com.ai.assistance.quro.etalib.data.model

import kotlinx.serialization.Serializable

/**
 * Provider 或模型级别的自定义 HTTP Header。
 *
 * 注意：host / content-length / connection / transfer-encoding 等危险 header
 * 会在网络层被过滤，防止破坏 HTTP 协议。
 */
@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)
