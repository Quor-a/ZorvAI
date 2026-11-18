package com.ai.assistance.quro.etalib.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject

/** Eta Runtime 与小布记忆 Hook 之间的有界内部协议。 */
internal object ColorOsMemoryBridgeProtocol {
    const val PACKAGE_NAME = "com.oplus.aimemory"
    const val PROVIDER_CLASS = "com.oplus.aimemory.provider.DataShareProvider"
    const val PROVIDER_URI = "content://com.oplus.aimemory.provider.DataShareProvider"
    const val METHOD = "com.ai.assistance.quro.coloros_memory.query.v1"
    const val RESULT_KEY = "eta_memory_bridge"
    const val DATABASE_NAME = "ai_memory"

    const val OPERATION_SEARCH = "search"
    const val OPERATION_ORDERS = "orders"
    const val OPERATION_PLACES = "places"

    private const val VERSION = 1
    private const val MAX_REQUEST_BYTES = 8 * 1024
    private const val MAX_RESPONSE_BYTES = 320 * 1024

    fun encodeRequest(operation: String, args: JSONObject): String {
        require(operation in OPERATIONS) { "不支持的 ColorOS 记忆查询操作" }
        val raw = JSONObject()
            .put("version", VERSION)
            .put("operation", operation)
            .put("args", JSONObject(args.toString()))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= MAX_REQUEST_BYTES) { "ColorOS 记忆查询参数过大" }
        return encode(raw)
    }

    fun decodeRequest(encoded: String): Request? {
        val raw = decode(encoded, MAX_REQUEST_BYTES) ?: return null
        val json = runCatching { JSONObject(String(raw, StandardCharsets.UTF_8)) }.getOrNull()
            ?: return null
        if (json.optInt("version") != VERSION) return null
        val operation = json.optString("operation")
        if (operation !in OPERATIONS) return null
        val args = json.optJSONObject("args") ?: return null
        return Request(operation, args)
    }

    fun encodeResponse(content: String): String {
        val raw = content.toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= MAX_RESPONSE_BYTES) { "ColorOS 记忆查询结果过大" }
        return "$VERSION:${encode(raw)}"
    }

    fun decodeShellResponse(stdout: String): String? {
        val marker = "$RESULT_KEY="
        val start = stdout.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val valueEnd = stdout.indexOf("}]", valueStart).takeIf { it >= 0 } ?: return null
        val envelope = stdout.substring(valueStart, valueEnd).trim()
        val separator = envelope.indexOf(':')
        if (separator <= 0 || envelope.substring(0, separator).toIntOrNull() != VERSION) return null
        val raw = decode(envelope.substring(separator + 1), MAX_RESPONSE_BYTES) ?: return null
        return String(raw, StandardCharsets.UTF_8)
    }

    fun buildRootCommand(encodedRequest: String): String {
        require(encodedRequest.length <= MAX_REQUEST_BYTES * 2) { "ColorOS 记忆查询参数过大" }
        require(encodedRequest.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "ColorOS 记忆查询参数编码无效"
        }
        return "content call --uri ${shellQuote(PROVIDER_URI)} " +
            "--method ${shellQuote(METHOD)} --arg ${shellQuote(encodedRequest)}"
    }

    data class Request(
        val operation: String,
        val args: JSONObject,
    )

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(encoded: String, maxBytes: Int): ByteArray? {
        if (encoded.isBlank() || encoded.length > ((maxBytes + 2) / 3) * 4) return null
        return runCatching { Base64.getUrlDecoder().decode(encoded) }
            .getOrNull()
            ?.takeIf { it.size <= maxBytes }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val OPERATIONS = setOf(OPERATION_SEARCH, OPERATION_ORDERS, OPERATION_PLACES)
}
