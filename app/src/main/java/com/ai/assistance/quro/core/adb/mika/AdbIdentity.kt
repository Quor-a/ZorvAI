package com.ai.assistance.quro.core.adb.mika

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mika 自己的 adb 身份:一把 RSA-2048 钥匙 + 自签名证书。配对时把 [adbPublicKey] 交给设备,
 * 之后每次 TLS 连接都出示 [certificate](设备只比对证书里的公钥)。
 */
class AdbIdentity(val keyPair: KeyPair, val name: String) {
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    val certificate: X509Certificate by lazy { SelfSignedCertificate.create(keyPair) }

    /**
     * 设备 keystore 里的一行:base64(524 字节公钥结构) + 空格 + 名字。
     * 名字里不能有空白:系统设置按空白切分这一行,名字带空格时「已配对的设备」里删不掉。
     */
    val adbPublicKey: String by lazy {
        Base64.getEncoder().encodeToString(AdbPublicKey.encode(publicKey)) + " " + name.replace(Regex("\\s"), "_")
    }

    companion object {
        fun generate(name: String): AdbIdentity {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            return AdbIdentity(g.generateKeyPair(), name)
        }

        fun fromPkcs8(pkcs8: ByteArray, name: String): AdbIdentity {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateCrtKey
            val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
            return AdbIdentity(KeyPair(pub, priv), name)
        }

        /** 读已存的钥匙;没有(或读不出)就新建一把并存下。 */
        fun loadOrCreate(store: AdbKeyStore, name: String): AdbIdentity {
            store.read()?.let { bytes -> runCatching { return fromPkcs8(bytes, name) } }
            return generate(name).also { store.write(it.keyPair.private.encoded) }
        }
    }
}

/** 钥匙(PKCS#8)的存取。App 里用 Android Keystore 包一层加密存放。 */
interface AdbKeyStore {
    fun read(): ByteArray?
    fun write(pkcs8: ByteArray)
    fun clear()
}

/**
 * 加密存放在文件里的钥匙:文件内容 = 1 字节 IV 长度 + IV + AES-GCM 密文。
 * IV 由 Cipher 自己生成(Android Keystore 的密钥不许调用方指定 IV)。
 */
class WrappedKeyFile(private val file: File, private val wrappingKey: () -> SecretKey) : AdbKeyStore {
    override fun read(): ByteArray? {
        if (!file.isFile) return null
        return runCatching {
            val b = file.readBytes()
            val ivLen = b[0].toInt() and 0xff
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b.copyOfRange(1, 1 + ivLen)))
            c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen)
        }.getOrNull()
    }

    override fun write(pkcs8: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = c.iv
        val out = byteArrayOf(iv.size.toByte()) + iv + c.doFinal(pkcs8)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(out)
        check(tmp.renameTo(file)) { "cannot write ${file.name}" }
    }

    override fun clear() {
        file.delete()
    }
}

/**
 * adb 的 RSA 公钥二进制格式(全部小端,共 524 字节):
 * 模数字数(64)、n0inv = −1/n mod 2^32、模数 n、rr = (2^2048)^2 mod n、公开指数。
 */
object AdbPublicKey {
    private const val WORDS = 64
    const val SIZE = 4 + 4 + WORDS * 4 * 2 + 4

    fun encode(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() == 2048) { "adb keys are RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32 - n.mod(r32).modInverse(r32)
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.toInt())
        buf.put(EdPoint.toLe(n, 256))
        buf.put(EdPoint.toLe(rr, 256))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }
}

/** 最小的自签名 X.509 v3 证书(无扩展):CN=Mika,1970-01-01 到 2099-12-31,SHA256withRSA。 */
internal object SelfSignedCertificate {
    private val SHA256_WITH_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun create(keyPair: KeyPair): X509Certificate {
        val algId = seq(tlv(0x06, SHA256_WITH_RSA), tlv(0x05, ByteArray(0)))
        val name = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME), tlv(0x0c, "Mika".toByteArray()))))
        val validity = seq(tlv(0x17, "700101000000Z".toByteArray()), tlv(0x18, "20991231235959Z".toByteArray()))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, byteArrayOf(1)),
            algId, name, validity, name,
            keyPair.public.encoded,
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    internal fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
