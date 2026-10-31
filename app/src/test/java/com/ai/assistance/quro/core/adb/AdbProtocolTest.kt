package com.ai.assistance.quro.core.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * ADB 协议层单测。
 *
 * 目的：报文格式、Android 公钥布局、认证签名三处最容易「看着对但实际错」，
 * 一旦错了真机表现只是「连不上」，极难排查。这里用可断言的形状把它钉住。
 *
 * 依据：AOSP protocol.txt（24B 头 / magic = cmd ^ 0xFFFFFFFF）、
 *      adb_auth_host.cpp（PKCS#1 v1.5 填充 + RSA/ECB/NoPadding）。
 */
class AdbProtocolTest {

    // ───────────── 报文编解码 ─────────────

    @Test
    fun `报文往返编解码保持一致`() {
        val out = ByteArrayOutputStream()
        val writer = AdbMessageWriter(out)
        val payload = "shell:echo hi".toByteArray()

        writer.write(AdbProtocol.CMD_OPEN, 7, 0, payload)

        val bytes = out.toByteArray()
        assertEquals("报文总长 = 24 字节头 + payload", 24 + payload.size, bytes.size)

        val header = ByteBuffer.wrap(bytes, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("command", AdbProtocol.CMD_OPEN, header.int)
        assertEquals("arg0", 7, header.int)
        assertEquals("arg1", 0, header.int)
        assertEquals("data_length", payload.size, header.int)
        assertEquals("data_check 填 0（adbd 对 0 不校验）", 0, header.int)
        assertEquals("magic 必须是 command 取反", AdbProtocol.magicOf(AdbProtocol.CMD_OPEN), header.int)

        val reader = AdbMessageReader(ByteArrayInputStream(bytes))
        val msg = reader.read()
        assertEquals(AdbProtocol.CMD_OPEN, msg.command)
        assertEquals(7, msg.arg0)
        assertArrayEquals(payload, msg.payload)
    }

    @Test
    fun `magic 不匹配必须抛协议异常`() {
        val out = ByteArrayOutputStream()
        AdbMessageWriter(out).connect()
        val bytes = out.toByteArray()
        // 破坏 header 里的 magic（偏移 20~23）；注意报文尾部是 payload，不是 magic
        bytes[20] = (bytes[20] + 1).toByte()

        val reader = AdbMessageReader(ByteArrayInputStream(bytes))
        val failed = runCatching { reader.read() }.exceptionOrNull()
        assertNotNull("magic 校验必须失败", failed)
        assertTrue("必须是 AdbProtocolException，实际 ${failed?.javaClass}", failed is AdbProtocolException)
    }

    @Test
    fun `magicOf 是 command 的按位取反`() {
        assertEquals(AdbProtocol.CMD_CNXN xor -1, AdbProtocol.magicOf(AdbProtocol.CMD_CNXN))
        assertEquals(AdbProtocol.CMD_AUTH xor -1, AdbProtocol.magicOf(AdbProtocol.CMD_AUTH))
        assertEquals(AdbProtocol.CMD_WRTE xor -1, AdbProtocol.magicOf(AdbProtocol.CMD_WRTE))
    }

    @Test
    fun `CNXN 报文携带 version 与 maxPayload`() {
        val out = ByteArrayOutputStream()
        AdbMessageWriter(out).connect(maxPayload = 1024 * 1024)
        val bytes = out.toByteArray()
        val header = ByteBuffer.wrap(bytes, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.CMD_CNXN, header.int)
        assertEquals("version", AdbProtocol.VERSION, header.int)
        assertEquals("maxPayload", 1024 * 1024, header.int)
        // payload 以 NUL 结尾
        assertEquals("payload 末字节必须是 NUL", 0, bytes.last().toInt())
    }

    // ───────────── 身份与签名 ─────────────

    @Test
    fun `Android 公钥必须是 524 字节且字段合法`() {
        val dir = Files.createTempDirectory("adbkey-test").toFile()
        try {
            val keyPair = AdbKeyPair.loadOrCreate(dir)

            val payload = keyPair.publicKeyPayload
            // payload = Base64 文本 + 结尾 NUL
            assertEquals("公钥 payload 必须以 NUL 结尾", 0, payload.last().toInt())
            val text = String(payload, 0, payload.size - 1, Charsets.UTF_8)
            val raw = java.util.Base64.getDecoder().decode(text)

            assertEquals("C 结构体 RSAPublicKey 必须正好 524 字节", 524, raw.size)

            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("len 字段 = modulus 的 32 位字数", 64, buf.int)
            buf.int // n0inv
            repeat(64) { buf.int } // n
            repeat(64) { buf.int } // rr
            assertEquals("exponent 必须是 65537", 65537, buf.int)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `签名长度必须等于密钥字节数且填充符合 PKCS1`() {
        val dir = Files.createTempDirectory("adbkey-sign").toFile()
        try {
            val keyPair = AdbKeyPair.loadOrCreate(dir)
            // 设备下发的 token 是 20 字节（SHA-1 摘要长度）
            val token = ByteArray(20) { (it + 1).toByte() }
            val signature = keyPair.signToken(token)

            assertEquals("RSA 2048 的签名必须是 256 字节", 256, signature.size)

            // 用对应公钥做裸 RSA「解密」，应还原出 00 01 FF..FF 00 <DigestInfo> <token>
            val pub = java.util.Base64.getDecoder()
                .decode(String(keyPair.publicKeyPayload, 0, keyPair.publicKeyPayload.size - 1, Charsets.UTF_8))
            val buf = ByteBuffer.wrap(pub).order(ByteOrder.LITTLE_ENDIAN)
            buf.int
            buf.int
            val nWords = IntArray(64) { buf.int }
            buf.int
            buf.int
            val n = BigIntegerFromWords(nWords)
            val e = java.math.BigInteger.valueOf(65537)

            val decrypted = java.math.BigInteger(1, signature).modPow(e, n).toByteArray()
            // BigInteger 会去掉符号前导 0，也可能多带 1 字节符号位 —— 统一补齐/截断到 256
            val block = ByteArray(256)
            if (decrypted.size >= 256) {
                System.arraycopy(decrypted, decrypted.size - 256, block, 0, 256)
            } else {
                System.arraycopy(decrypted, 0, block, 256 - decrypted.size, decrypted.size)
            }

            assertEquals("块首字节", 0x00, block[0].toInt())
            assertEquals("块第二字节", 0x01, block[1].toInt())
            val digestInfoStart = 256 - 20 - 15
            assertEquals("DigestInfo 起始（SHA-1）", 0x30, block[digestInfoStart].toInt())
            assertEquals("DigestInfo 次字节", 0x21, block[digestInfoStart + 1].toInt())
            assertArrayEquals("尾 20 字节必须原样是 token", token, block.copyOfRange(236, 256))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `密钥持久化后可复现同一公钥`() {
        val dir = Files.createTempDirectory("adbkey-persist").toFile()
        try {
            val first = AdbKeyPair.loadOrCreate(dir)
            val second = AdbKeyPair.loadOrCreate(dir)
            assertArrayEquals(
                "同一目录二次加载必须得到同一把钥匙（否则设备会反复要求授权）",
                first.publicKeyPayload,
                second.publicKeyPayload,
            )
            assertTrue(File(dir, "adbkey").isFile)
            assertTrue(File(dir, "adbkey.pub").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun BigIntegerFromWords(words: IntArray): java.math.BigInteger {
        var acc = java.math.BigInteger.ZERO
        val r32 = java.math.BigInteger.ZERO.setBit(32)
        for (i in words.indices.reversed()) {
            acc = acc.multiply(r32).add(java.math.BigInteger.valueOf(words[i].toLong() and 0xFFFFFFFFL))
        }
        return acc
    }
}
