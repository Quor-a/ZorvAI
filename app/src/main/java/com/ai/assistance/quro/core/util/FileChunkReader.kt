package com.ai.assistance.quro.core.util

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 分页/分块文件读取（issue #11 问题5）。
 *
 * 背景（用户 v1.1.4 实测）：全部会话存于单个 JSON（本机约 13MB），而读取类工具
 * （file_read / read_text_file / workspace_read）均为**整读语义**——
 * `file_read` 甚至对 >512KB 文件直接回「建议分段读取」却**不提供分段能力**，
 * 读取/审计/导出历史对话只能把大内容整体读进上下文，逼近模型上限。
 *
 * 本类提供 offset（字节偏移）+ maxBytes（本段长度）的分段读取，并处理 UTF-8 边界：
 *  · offset 落在多字节字符中间 → 向前跳过孤儿续字节，从下一个字符边界开始；
 *  · 本段末尾字符不完整 → 丢弃不完整尾部（consumed < requested），下次 offset
 *    从「实际消耗」处继续，绝不把字符拦腰截断成乱码。
 *
 * 返回值 [Chunk.text] 为解码文本，[Chunk.consumed] 为**实际消耗的字节数**——
 * 调用方应把「offset + consumed」作为下一页起点回给模型，保证循环不漏不重。
 */
object FileChunkReader {

    /** 单段读取的默认字节数（约 32KB 文本，远小于任何模型上下文预算）。 */
    const val DEFAULT_CHUNK_BYTES: Int = 32 * 1024

    /** 单段读取的硬上限（256KB）：再大就该让模型分多次，而不是一把梭。 */
    const val MAX_CHUNK_BYTES: Int = 256 * 1024

    data class Chunk(
        /** 解码后的文本（UTF-8，已做边界修剪，不会出现替换符乱码）。 */
        val text: String,
        /** 本次实际消耗的字节数（≤ 请求的 maxBytes；修剪不完整尾部时会小于请求值）。 */
        val consumed: Int,
        /** 文件总字节数。 */
        val total: Long,
        /** 本次读取的起始偏移（= 入参 offset 修正到字符边界后的值）。 */
        val start: Long,
    ) {
        /** 是否还有剩余字节未读。 */
        val hasMore: Boolean get() = start + consumed < total
        /** 下一页应传的 offset（漏读/重读都不会发生）。 */
        val nextOffset: Long get() = start + consumed
    }

    class ChunkError(message: String) : IOException(message)

    /**
     * 读取 [file] 的 [offset, offset+maxBytes) 字节段并解码为文本。
     *
     * @param offset    起始字节偏移（<0 按 0；>文件长度抛 [ChunkError]）。
     * @param maxBytes  本段最大字节数（<=0 用 [DEFAULT_CHUNK_BYTES]；>[MAX_CHUNK_BYTES] 截断）。
     * @throws ChunkError 文件不存在 / 是目录 / offset 越界。
     */
    fun read(file: File, offset: Long, maxBytes: Int = DEFAULT_CHUNK_BYTES): Chunk {
        if (!file.exists()) throw ChunkError("文件不存在：${file.absolutePath}")
        if (file.isDirectory) throw ChunkError("是目录不是文件（读取请给文件路径）：${file.absolutePath}")
        val total = file.length()
        if (total <= 0L) return Chunk("", 0, total, 0)
        val start = offset.coerceAtLeast(0L)
        // offset 恰等于文件大小 = 已读到末尾：返回空段（分页循环会精确命中 EOF，不能报错）
        if (start == total) return Chunk("", 0, total, start)
        if (start > total) {
            throw ChunkError("offset=$start 越界（文件共 $total 字节，有效范围 0..$total）")
        }
        val want = if (maxBytes <= 0) DEFAULT_CHUNK_BYTES else maxBytes.coerceAtMost(MAX_CHUNK_BYTES)
        val buf = ByteArray(want.toInt())
        var read = 0
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            while (read < want) {
                val n = raf.read(buf, read, want - read)
                if (n < 0) break
                read += n
            }
        }
        // ① offset 落在字符中间：跳过开头孤儿续字节（0x80-0xBF）
        var from = 0
        while (from < read && (buf[from].toInt() and 0xC0) == 0x80) from++
        // ② 段尾字符不完整：从尾部向前找序列起点，不足则整段丢弃该字符
        var to = read
        var i = to - 1
        var cont = 0
        while (i >= from && (buf[i].toInt() and 0xC0) == 0x80) { cont++; i-- }
        if (i >= from) {
            val lead = buf[i].toInt()
            val need = when {
                lead and 0x80 == 0 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            if (cont + 1 < need) to = i
        }
        val consumed = to - from
        val text = if (consumed <= 0) "" else String(buf, from, consumed, Charsets.UTF_8)
        return Chunk(text, consumed, total, start + from)
    }

    /**
     * 生成给模型看的分页头：告诉它文件多大、本次读了哪段、还剩多少、下一页 offset 是多少。
     * 有了这行，模型可以自行循环分页而不必猜。
     */
    fun pageHeader(file: File, c: Chunk): String = buildString {
        append("📄 分页读取：共 ${c.total} 字节；本次 [${c.start}, ${c.start + c.consumed})")
        if (c.hasMore) {
            append("；剩余 ${c.total - c.nextOffset} 字节")
            append("\n⚠️ 未读完：继续读取请传 offset=${c.nextOffset}（同样的 maxBytes），直到无此提示")
        } else {
            append("；已到文件末尾")
        }
        append("\n--- 内容开始 ---\n")
    }
}
