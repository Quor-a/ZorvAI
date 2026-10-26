package com.ai.assistance.quro.terminal.moyu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * 墨语言通道 pack 读取器的回归测试（[TarGzExtract]）。
 *
 * ## 为什么必须有这个测试
 *
 * 这里的 bug 是「静默全废」型的：跳过库里**不要的条目**时只跳了 512 对齐的补齐字节、
 * 没跳数据本体，于是读第二个文件起整条流就错位，白名单一个都匹配不上。
 * 上层只会看到一句「资产里没解出任何目标文件（白名单写错了？）」，
 * 而真实后果是**墨语言核心层从来没有部署成功过**（用户真机连报多次「墨语言没修」）。
 * 实测 addon 包真实 194 条，错位版能"走出" 346 条且命中 0。
 *
 * 所以这里刻意把「不想要的大文件夹在两个想要的文件中间」——
 * 只有正确跳过数据体，第二个想要的文件才可能被解出来。
 */
class TarGzExtractTest {

    /** 造一个 tar 头（512 字节）。typeFlag：'0' 普通文件 / '5' 目录。 */
    private fun header(name: String, size: Int, typeFlag: Char): ByteArray {
        val h = ByteArray(512)
        val n = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(n, 0, h, 0, minOf(n.size, 100))
        // mode / uid / gid
        fun putOctal(value: Int, off: Int, len: Int) {
            val s = value.toString(8).padStart(len - 1, '0') + "\u0000"
            System.arraycopy(s.toByteArray(Charsets.US_ASCII), 0, h, off, len)
        }
        putOctal(0b111_101_101, 100, 8)   // 0755
        putOctal(0, 108, 8)                // uid
        putOctal(0, 116, 8)                // gid
        putOctal(size, 124, 12)            // size
        putOctal(0, 136, 12)               // mtime
        h[156] = typeFlag.code.toByte()
        System.arraycopy("ustar\u000000".toByteArray(Charsets.US_ASCII), 0, h, 257, 8)
        // 校验和：先按空格算，再写入
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0
        for (b in h) sum += (b.toInt() and 0xFF)
        val cs = sum.toString(8).padStart(6, '0') + "\u0000 "
        System.arraycopy(cs.toByteArray(Charsets.US_ASCII), 0, h, 148, 8)
        return h
    }

    /** 追加一个条目（数据 + 512 对齐补齐）。 */
    private fun ByteArrayOutputStream.entry(name: String, content: ByteArray, typeFlag: Char = '0') {
        write(header(name, content.size, typeFlag))
        write(content)
        val pad = (512 - (content.size % 512)) % 512
        if (pad > 0) write(ByteArray(pad))
    }

    /** 打包成 gzip 的 tar 字节流。 */
    private fun tarGz(build: ByteArrayOutputStream.() -> Unit): ByteArray {
        val tar = ByteArrayOutputStream().apply(build)
        tar.write(ByteArray(1024))   // 结束：两个全零块
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    private fun newTmpDir(): File =
        java.nio.file.Files.createTempDirectory("targz-test").toFile().apply { deleteOnExit() }

    @Test
    fun `白名单命中的文件在夹着大文件时仍能全部解出`() {
        // 结构：目录 → 想要的 A → 不想要的大文件（数据体 3000B）→ 想要的 B → 不想要的小文件 → 想要的目录前缀文件
        val bytes = tarGz {
            entry("opt/", ByteArray(0), '5')
            entry("opt/molang/lib/", ByteArray(0), '5')
            entry("opt/molang/VERSION", "1.2.3\n".toByteArray())
            entry("opt/molang/bin/moc", ByteArray(3000) { 'x'.code.toByte() })   // ← 错位触发器
            entry("opt/molang/bin/mo2x", "MO2X".toByteArray())
            entry("opt/skip-me", "no\n".toByteArray())                            // ← 再来一个
            entry("opt/molang/lib/cstd.mo", "lib-cstd".toByteArray())
            entry("opt/molang/system/bin/uspace2", "USPACE".toByteArray())
        }
        val dest = newTmpDir()
        val picked = TarGzExtract.extract(
            java.io.ByteArrayInputStream(bytes),
            "synthetic.pack",
            listOf(
                "opt/molang/VERSION" to "VERSION",
                "opt/molang/bin/mo2x" to "bin/mo2x",
                "opt/molang/lib/" to "lib/",
                "opt/molang/system/bin/uspace2" to "system/bin/uspace2",
            ),
            dest,
        )
        val map = picked.associate { it.second to it.first.readText() }
        assertEquals(setOf("VERSION", "bin/mo2x", "lib/cstd.mo", "system/bin/uspace2"), map.keys)
        assertEquals("1.2.3\n", map["VERSION"])
        assertEquals("MO2X", map["bin/mo2x"])            // 若跳过不跳数据体，这里必为空/错乱
        assertEquals("lib-cstd", map["lib/cstd.mo"])
        assertEquals("USPACE", map["system/bin/uspace2"])
    }

    @Test
    fun `一个都没命中时抛异常而不是静默返回空`() {
        val bytes = tarGz {
            entry("other/file.txt", "hi".toByteArray())
        }
        val dest = newTmpDir()
        val err = runCatching {
            TarGzExtract.extract(
                java.io.ByteArrayInputStream(bytes),
                "synthetic.pack",
                listOf("opt/molang/VERSION" to "VERSION"),
                dest,
            )
        }.exceptionOrNull()
        assertTrue("应当抛出可读异常，实际=$err", err != null)
        assertTrue(
            "异常文案要能指向白名单，实际=${err?.message}",
            err?.message?.contains("没解出任何目标文件") == true,
        )
    }

    @Test
    fun `前缀白名单保留目录结构且不越界写到根目录外`() {
        val bytes = tarGz {
            entry("pack/lib/a.mo", "AAA".toByteArray())
            entry("pack/lib/sub/b.mo", "BBB".toByteArray())
        }
        val dest = newTmpDir()
        val picked = TarGzExtract.extract(
            java.io.ByteArrayInputStream(bytes),
            "synthetic.pack",
            listOf("pack/lib/" to "lib/"),
            dest,
        )
        assertEquals(listOf("lib/a.mo", "lib/sub/b.mo"), picked.map { it.second })
        assertEquals("BBB", File(dest, "lib/sub/b.mo").readText())
    }

    /**
     * 直接打**真资产**：addon 包按 CORE_FILES 白名单必须解出 78 个文件 / 6,397,316 字节。
     *
     * 这是真机实测基线（Python tarfile 与修好后的读取器两边一致），
     * 一旦有人再动跳过逻辑，这里立刻会红。资产不在这台机器的工作目录下时跳过。
     */
    @Test
    fun `真 addon 资产按核心白名单解出 78 个文件`() {
        val asset = listOf(
            File("../terminal-core/src/main/assets/molang-ubuntu-addon.pack"),
            File("terminal-core/src/main/assets/molang-ubuntu-addon.pack"),
        ).firstOrNull { it.isFile }
        if (asset == null) {
            println("跳过：本机找不到 molang-ubuntu-addon.pack")
            return
        }
        val dest = newTmpDir()
        val picked = TarGzExtract.extract(
            asset.inputStream(),
            asset.name,
            MoyuLocal.CORE_FILES,
            dest,
        )
        assertEquals("解出文件数", 78, picked.size)
        assertEquals("解出总字节", 6_397_316L, picked.sumOf { it.first.length() })
        // 四个核心件必须都在，且非空
        val rels = picked.map { it.second }.toSet()
        listOf("qemu-x86_64-static", "bin/mo2x", "system/bin/uspace2", "VERSION").forEach {
            assertTrue("缺核心件 $it（实际=${rels.sorted()}）", it in rels)
        }
    }
}
