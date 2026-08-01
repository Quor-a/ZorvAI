package com.ai.assistance.quro.core.aci

import org.json.JSONObject

/**
 * ACI 2.0 标准化错误模型（P0 治理项，依托 aci-core 框架集成）。
 *
 * 本对象是主应用 ACI 层（控制端 QuroAciManager + 受控端 QuroMainAciService）
 * 的一部分，直接消费 aci-core 的公开 API：用 ACIResponse.error(code, json)
 * 把结构化错误塞进 errorMessage，用 getErrorCode()/getErrorMessage() 解析。
 * 不改动 aci-core 内核（其源码不在本仓，仅以 AAR 形式依赖），而是在其上做
 * 语义化错误层 —— 与 aci-core 的 ACIError 常量共存，错误码落在独立的
 * aci-protocol 命名空间（15xx/24xx/25xx），不与 1xxx/2xxx 的 aci-core 原生码冲突。
 *
 * 任何失败都归一为 {code, message, suggestion, layer}：
 * - code：aci-protocol 命名空间下的语义化错误码（独立于 App 版本）
 * - message：人类可读信息
 * - suggestion：人类可读 + LLM 可解析的修复建议，让 Agent 能自助纠错而非盲目重试
 * - layer：出错分层（binder / http / protocol）
 */
object QuroAciErrors {
    // 分层
    const val LAYER_BINDER = "binder"
    const val LAYER_HTTP = "http"
    const val LAYER_PROTOCOL = "protocol"

    // 语义化错误码
    const val E_SERVICE_UNBOUND = 1503
    const val E_TIMEOUT = 1504
    const val E_BAD_REQUEST = 2400
    const val E_HTTP_CLIENT = 2500
    const val E_HTTP_TLS = 2520
    const val E_INTERNAL = 2599

    data class Structured(val code: Int, val message: String, val suggestion: String, val layer: String) {
        fun toJson(): String = JSONObject().apply {
            put("aci_error", true)
            put("code", code)
            put("message", message)
            put("suggestion", suggestion)
            put("layer", layer)
        }.toString()
    }

    fun of(code: Int, message: String, suggestion: String, layer: String): Structured =
        Structured(code, message, suggestion, layer)

    /** Binder 层错误建议（控制端调用受控端失败时）。 */
    fun fromBinderResponse(errorCode: Int, errorMessage: String?): Structured {
        val suggestion = when (errorCode) {
            E_SERVICE_UNBOUND -> "目标 App 未绑定/未安装。请确认其已安装并声明 ACI Service，或重试 aci_list 触发重新发现与唤醒。"
            E_TIMEOUT -> "调用超时（>15s）。服务端可能卡死或进程被杀；可稍后重试，或改用 aci_call_async 异步调用。"
            else -> "Binder 调用返回错误码 $errorCode：${errorMessage ?: ""}。检查参数拼写与被调端日志。"
        }
        return Structured(errorCode, errorMessage ?: "binder error", suggestion, LAYER_BINDER)
    }

    /** HTTP 层失败建议：根据异常特征区分 TLS / 网络。 */
    fun httpSuggestion(cause: String?): String {
        val c = cause ?: ""
        return when {
            c.contains("trust", true) || c.contains("cert", true) || c.contains("SSL", true) ->
                "TLS 证书校验失败。自签/内网 HTTPS 请传 tls_verify:\"false\"，或传 tls_ca_pem 固定自签 CA 后再试。"
            c.contains("timeout", true) || c.contains("timed out", true) ->
                "连接/读取超时。检查目标可达性与网络；必要时调大超时或改异步。"
            else -> "HTTP 请求失败：$c。检查 URL、方法、请求体与网络连通性。"
        }
    }

    fun isTlsError(cause: String?): Boolean {
        val c = cause ?: ""
        return c.contains("trust", true) || c.contains("cert", true) || c.contains("SSL", true)
    }

    /**
     * 从 aci-core 的 errorMessage 中解析结构化错误（受控端经 ACIResponse.error(code, toJson()) 写入）。
     * 非结构化文本返回 null，便于控制端在未知错误时安全降级为原样展示。
     */
    fun parse(json: String?): Structured? {
        if (json.isNullOrEmpty()) return null
        return try {
            val o = JSONObject(json)
            if (!o.optBoolean("aci_error", false)) return null
            Structured(
                o.optInt("code", 0),
                o.optString("message", ""),
                o.optString("suggestion", ""),
                o.optString("layer", "")
            )
        } catch (e: Throwable) { null }
    }
}
