package com.ai.assistance.quro.core.mika.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE(RFC 7636)和回调核对用的随机值。每次登录都新生成,不存盘。
 * 校验串用 base64url 写出 32 个随机字节(43 个字符,落在规范要求的 43–128 之间,字符也都在允许的范围里)。
 */
object Pkce {
    private val random = SecureRandom()

    /** 新的校验串(code_verifier)。 */
    fun verifier(bytes: Int = 32): String = randomString(bytes)

    /** S256:校验串的 SHA-256,base64url 不带补位。 */
    fun challenge(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** 回调里原样带回、用来认出是不是这次登录的随机串(state)。 */
    fun state(): String = randomString(24)

    private fun randomString(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
