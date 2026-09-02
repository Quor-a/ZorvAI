package com.ai.assistance.quro.core.miniapp

import android.content.Context
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 加解密模块：md5 / sha1 / sha256 / hmacSha256。
 * 移植自 MiniAppFramework（com.miniapp），去品牌化为 QuroAI 的 MiniAppBridgeModule 协议。
 * 纯 Kotlin 实现，无需任何系统权限。
 */
class CryptoModule(private val context: Context) : MiniAppBridgeModule {
    override val name = "crypto"

    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "md5" -> hash("MD5", params, callback)
            "sha1" -> hash("SHA-1", params, callback)
            "sha256" -> hash("SHA-256", params, callback)
            "hmacSha256" -> hmac(params, callback)
            else -> callback(-1, null, "method not found: $method")
        }
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun hash(algo: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val data = params.optString("data", "")
        runCatching {
            val d = java.security.MessageDigest.getInstance(algo).digest(bytes(data))
            callback(0, hex(d), null)
        }.onFailure { callback(-1, null, it.message) }
    }

    private fun hmac(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val data = params.optString("data", "")
        val key = params.optString("key", "")
        runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(bytes(key), "HmacSHA256"))
            callback(0, hex(mac.doFinal(bytes(data))), null)
        }.onFailure { callback(-1, null, it.message) }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
