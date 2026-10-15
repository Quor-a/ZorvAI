package com.ai.assistance.quro.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * issue #11 问题5 回归锁：分页/分块读取（offset + maxBytes）。
 *
 * 钉死契约：
 *  1. 小文件整读：consumed == 文件长度、hasMore == false；
 *  2. 大文件分页循环：按 nextOffset 逐段读，拼接结果与原文**逐一字节相等**（不漏不重）；
 *  3. UTF-8 边界：offset 落字符中间 → 跳过孤儿续字节；段尾字符不完整 → 丢弃不完整尾部，
 *     且任何情况下解码文本都不含替换符 U+FFFD（不产生乱码）；
 *  4. 错误自解释：不存在 / 是目录 / offset 越界 → ChunkError 且 message 带关键信息。
 */
class FileChunkReaderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory("quro-chunk-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String): File =
        File(dir, name).apply { writeText(content, Charsets.UTF_8) }

    @Test
    fun smallFile_wholeRead() {
        val f = file("small.txt", "hello world")
        val c = FileChunkReader.read(f, 0)
        assertEquals("hello world", c.text)
        assertEquals(11L, c.consumed.toLong())
        assertEquals(11L, c.total)
        assertEquals(0L, c.start)
        assertFalse(c.hasMore)
        assertEquals(11L, c.nextOffset)
    }

    @Test
    fun emptyFile_zeroChunk() {
        val f = file("empty.txt", "")
        val c = FileChunkReader.read(f, 0)
        assertEquals("", c.text)
        assertEquals(0L, c.consumed.toLong())
        assertFalse(c.hasMore)
    }

    @Test
    fun largeFile_pagedLoop_lossless() {
        // 模拟「13MB 会话数据」：用 200KB 重复文本，按 32KB 一段循环读
        val original = buildString {
            var i = 0
            while (length < 200 * 1024) { append("msg-$i|"); i++ }
        }.toString()
        val f = file("big.json", original)
        assertTrue(f.length() > 150 * 1024)

        val sb = StringBuilder()
        var offset = 0L
        var guard = 0
        while (true) {
            val c = FileChunkReader.read(f, offset, 32 * 1024)
            sb.append(c.text)
            assertFalse("解码文本不得含替换符 U+FFFD", c.text.contains('\uFFFD'))
            if (!c.hasMore) break
            offset = c.nextOffset
            if (++guard > 1000) throw AssertionError("分页循环未收敛（疑似 nextOffset 不推进）")
        }
        assertEquals(original, sb.toString())
        assertEquals(f.length(), sb.toString().toByteArray(Charsets.UTF_8).size.toLong())
    }

    @Test
    fun utf8Boundary_offsetMidCharacter_skipsOrphanContinuation() {
        // 「中文」两字 = 6 字节；offset=2 落在第一个字中间 → 必须跳过孤儿续字节从边界开始
        val f = file("cn.txt", "中文ABC")
        val c = FileChunkReader.read(f, 2)
        assertFalse(c.text.contains('\uFFFD'))
        assertTrue("offset 修正后应从字符边界开始（得到『文ABC』）", c.text.endsWith("ABC"))
        assertTrue(c.text.startsWith("文"))
    }

    @Test
    fun utf8Boundary_tailIncomplete_dropped() {
        // 一段正好截在 3 字节汉字中间：consumed 应小于请求值，且文本无替换符
        val f = file("cn2.txt", "一二三四五六七八九十") // 10 字 = 30 字节
        val c = FileChunkReader.read(f, 0, 16) // 16 = 5 个完整汉字(15B) + 1 字节残尾
        assertEquals(15L, c.consumed.toLong()) // 残尾被丢弃
        assertEquals("一二三四五", c.text)
        assertFalse(c.text.contains('\uFFFD'))
        assertTrue(c.hasMore)
        assertEquals(15L, c.nextOffset)
        // 从 nextOffset 继续读，拼接无损
        val c2 = FileChunkReader.read(f, c.nextOffset, 16)
        assertEquals("六七八九十", c2.text)
        assertFalse(c2.hasMore)
    }

    @Test
    fun ascii_exactBoundary() {
        val f = file("ascii.txt", "0123456789")
        val c = FileChunkReader.read(f, 0, 5)
        assertEquals("01234", c.text)
        assertEquals(5L, c.consumed.toLong())
        assertTrue(c.hasMore)
        val c2 = FileChunkReader.read(f, c.nextOffset, 5)
        assertEquals("56789", c2.text)
        assertFalse(c2.hasMore)
    }

    @Test(expected = FileChunkReader.ChunkError::class)
    fun missingFile_throws() {
        FileChunkReader.read(File(dir, "ghost.txt"), 0)
    }

    @Test(expected = FileChunkReader.ChunkError::class)
    fun directory_throws() {
        FileChunkReader.read(dir, 0)
    }

    @Test
    fun offsetBeyondEnd_throwsWithSizeInfo() {
        val f = file("tiny.txt", "abc")
        try {
            FileChunkReader.read(f, 100)
            throw AssertionError("应当抛出 ChunkError")
        } catch (e: FileChunkReader.ChunkError) {
            assertTrue("错误信息应带文件大小：${e.message}", e.message!!.contains("3"))
        }
    }

    @Test
    fun offsetAtEndBoundary_ok() {
        val f = file("tiny2.txt", "abc")
        val c = FileChunkReader.read(f, 3) // 恰好末尾 → 空段
        assertEquals("", c.text)
        assertEquals(0L, c.consumed.toLong())
        assertFalse(c.hasMore)
    }

    @Test
    fun pageHeader_guidesNextOffset() {
        val f = file("hdr.txt", "x".repeat(100))
        val c = FileChunkReader.read(f, 0, 40)
        val header = FileChunkReader.pageHeader(f, c)
        assertTrue(header.contains("共 100 字节"))
        assertTrue(header.contains("offset=40"))
        assertTrue(header.contains("未读完"))
        val c2 = FileChunkReader.read(f, c.nextOffset, 40)
        val header2 = FileChunkReader.pageHeader(f, c2)
        assertTrue(header2.contains("offset=80"))
        val c3 = FileChunkReader.read(f, c2.nextOffset, 40)
        assertTrue(FileChunkReader.pageHeader(f, c3).contains("已到文件末尾"))
    }

    @Test
    fun maxBytesCappedAtHardLimit() {
        val f = file("cap.txt", "y".repeat(1000))
        // 请求 10MB → 被 MAX_CHUNK_BYTES(256KB) 截断；文件仅 1000B，不受影响但不得抛错
        val c = FileChunkReader.read(f, 0, 10 * 1024 * 1024)
        assertEquals(1000L, c.consumed.toLong())
        assertFalse(c.hasMore)
    }
}
