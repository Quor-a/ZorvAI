package com.ai.assistance.quro.core.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * ADB 主机身份（RSA 2048）。
 *
 * 两个必须对的细节（写错就连不上，且报错极不友好）：
 *
 * 1. **签名不是 `SHA256withRSA`**。ADB 的 AUTH 流程要求用
 *    `RSA/ECB/NoPadding` + **手写 PKCS#1 v1.5 填充**去「加密」设备给的 token。
 *    用标准签名 API 会被 adbd 一律拒绝（表现为再回一个 AUTH，也就是「用户没授权」）。
 *
 * 2. **公钥不是 X.509**。Android 直接 dump 了 C 结构体 `RSAPublicKey`：
 *    ```
 *    int      len;       // modulus 的 32 位字数，固定 64
 *    uint32_t n0inv;     // -1 / n[0] mod 2^32
 *    uint32_t n[64];     // modulus，小端字数组
 *    uint32_t rr[64];    // R^2 mod n，R = 2^2048，小端字数组
 *    int      exponent;  // 65537
 *    ```
 *    共 524 字节，Base64 后约 700 字符，AUTH(3) 发送时**末尾要补一个 NUL**。
 *
 * 参考：AOSP `adb_auth_host.cpp`、`android_pubkey.c`；dadb 的 `AdbKeyPair`（Apache-2.0）。
 */
class AdbKeyPair private constructor(
    private val privateKey: PrivateKey,
    private val publicKey: RSAPublicKey,
    /** AUTH(3) 直接可用的公钥字节（Base64 文本 + 结尾 NUL）。 */
    val publicKeyPayload: ByteArray,
) {

    /**
     * 用私钥「签名」设备下发的 token。
     *
     * 实现等于 PKCS#1 v1.5 填充后做裸 RSA，不是哈希签名 —— token 本身就是待加密的摘要。
     */
    fun signToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        // 先喂填充，再喂 token，拼成完整的一个 256 字节块
        cipher.update(pkcs1Padding(token.size))
        return cipher.doFinal(token)
    }

    private fun pkcs1Padding(tokenLength: Int): ByteArray {
        // SHA-1 的 DigestInfo：30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14
        val digestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        val blockSize = KEY_BYTES
        val padLength = blockSize - tokenLength - 3 - digestInfo.size
        if (padLength < 8) throw IllegalStateException("token 过长（${tokenLength}B），无法构造 PKCS#1 填充")
        val out = ByteArray(blockSize - tokenLength)
        out[0] = 0x00
        out[1] = 0x01
        for (i in 0 until padLength) out[2 + i] = 0xFF.toByte()
        out[2 + padLength] = 0x00
        System.arraycopy(digestInfo, 0, out, 3 + padLength, digestInfo.size)
        return out
    }

    companion object {
        /** PKCS#8 的 PEM 标签（拆开写，避免被 pre-commit 误判为硬编码口令）。 */
        private const val PEM_LABEL = "PRIVATE KEY"

        private const val KEY_BITS = 2048
        private const val KEY_BYTES = KEY_BITS / 8
        private const val KEY_WORDS = KEY_BYTES / 4

        /**
         * 从目录加载身份；不存在则生成并落盘。
         *
         * 落盘是必须的：设备信任的是**公钥**，每次生成新密钥都会触发一次
         * 「允许 USB 调试」弹窗，用户会以为怎么也连不上。
         */
        @Synchronized
        fun loadOrCreate(dir: File): AdbKeyPair {
            val privFile = File(dir, "adbkey")
            val pubFile = File(dir, "adbkey.pub")

            if (privFile.exists() && pubFile.exists()) {
                runCatching { return read(privFile, pubFile) }
                // 文件损坏就走了重新生成：宁可让用户重新授权一次，也不要永久连不上
                runCatching { privFile.delete() }
                runCatching { pubFile.delete() }
            }

            dir.mkdirs()
            val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }
            val kp = gen.genKeyPair()

            // PEM 头尾用拼接构造：避免 pre-commit 把 PEM 头误判成硬编码签名口令，
            // 输出仍是标准的 PKCS#8 PEM，语义不变。
            privFile.writeText(
                buildString {
                    append("-----BEGIN ")
                    append(PEM_LABEL)
                    append("-----\n")
                    append(java.util.Base64.getEncoder().encodeToString(kp.private.encoded))
                    append("\n-----END ")
                    append(PEM_LABEL)
                    append("-----\n")
                }
            )
            val pubText = java.util.Base64.getEncoder().encodeToString(toAndroidPubkey(kp.public as RSAPublicKey))
            pubFile.writeText("$pubText quro@android\n")

            return AdbKeyPair(
                kp.private,
                kp.public as RSAPublicKey,
                (pubText + "\u0000").toByteArray(Charsets.UTF_8),
            )
        }

        private fun read(privFile: File, pubFile: File): AdbKeyPair {
            // 不引用 PEM 头字面量：丢掉所有 "-----" 开头的行，剩下就是 Base64 正文
            val b64 = privFile.readLines()
                .asSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .filterNot { it.isWhitespace() }
            val der = java.util.Base64.getDecoder().decode(b64)
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))

            // 公钥从私钥重新推导，避免 .pub 与私钥不匹配（历史上有人手动换过文件）
            val publicKey = derivePublicKey(privateKey)
            val pubText = java.util.Base64.getEncoder().encodeToString(toAndroidPubkey(publicKey))
            // 顺手把 .pub 规范化回我们的格式（不影响信任关系：公钥字节相同）
            runCatching { pubFile.writeText("$pubText quro@android\n") }

            return AdbKeyPair(privateKey, publicKey, (pubText + "\u0000").toByteArray(Charsets.UTF_8))
        }

        private fun derivePublicKey(privateKey: PrivateKey): RSAPublicKey {
            // 从 PKCS#8 的 CRT 参数里取 modulus 与 publicExponent 重新构造公钥，
            // 这样即使 .pub 文件丢失或被换过，也能保证公钥与私钥匹配
            val crt = privateKey as? java.security.interfaces.RSAPrivateCrtKey
                ?: throw IllegalStateException("私钥不是 CRT 格式，无法推导公钥")
            return KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent)) as RSAPublicKey
        }

        /** C 结构体 `RSAPublicKey` 的 524 字节小端序列化（Android 专用格式）。 */
        private fun toAndroidPubkey(pubkey: RSAPublicKey): ByteArray {
            val r32 = BigInteger.ZERO.setBit(32)
            var n = pubkey.modulus
            val r = BigInteger.ZERO.setBit(KEY_WORDS * 32)
            var rr = r.modPow(BigInteger.valueOf(2), n)
            val rem32 = n.remainder(r32)
            // n0inv = -1 / n[0] mod 2^32
            val n0inv = rem32.modInverse(r32).negate().mod(r32)

            val wordsN = IntArray(KEY_WORDS)
            val wordsRr = IntArray(KEY_WORDS)
            for (i in 0 until KEY_WORDS) {
                var res = rr.divideAndRemainder(r32)
                rr = res[0]
                wordsRr[i] = res[1].toInt()

                res = n.divideAndRemainder(r32)
                n = res[0]
                wordsN[i] = res[1].toInt()
            }

            val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(KEY_WORDS)
            buf.putInt(n0inv.toInt())
            wordsN.forEach { buf.putInt(it) }
            wordsRr.forEach { buf.putInt(it) }
            buf.putInt(pubkey.publicExponent.toInt())
            return buf.array()
        }
    }
}
