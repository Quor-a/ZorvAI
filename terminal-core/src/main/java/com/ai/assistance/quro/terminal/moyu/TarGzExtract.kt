package com.ai.assistance.quro.terminal.moyu

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 从 **gzip 压缩的 tar 流**里按白名单选择性解出文件（墨语言通道的 pack 读取器）。
 *
 * ## 为什么单独成类
 *
 * 这段代码埋过一个「静默全废」的 bug：跳过条目时只跳了 512 对齐的补齐字节、
 * **没跳数据本体**，于是流从第二个条目起就整体错位，白名单一个都匹配不上，
 * 上层报「资产里没解出任何目标文件（白名单写错了？）」。实测 addon 包真实 194 条，
 * 错位版能"走出" 346 条且命中 0 —— 墨语言核心层因此**从来没有部署成功过**。
 * 抽成公共类是为了让它能被 JVM 单测直接喂合成 tar 钉住（见 TarGzExtractTest）。
 *
 * ## 白名单约定
 *
 * - `wanted` 左侧 = 包内成员名，右侧 = 目标相对路径；
 * - 左侧以 `/` 结尾 = **前缀匹配**（整个目录），否则精确匹配；
 * - 只解普通文件（typeflag '0' / '\0'），目录与链接只跳过。
 *
 * @return 已解出的 (本地文件, 目标相对路径) 列表；一个都没解出时抛异常（不静默）。
 */
object TarGzExtract {

    /**
     * @param gzStream 已打开的资产流（内部会套 [GZIPInputStream]，调用方负责关闭）
     * @param assetLabel 资产名，只用于错误文案
     * @param wanted 白名单（见类注释）
     * @param destRoot 解出文件的落地根目录
     */
    fun extract(
        gzStream: InputStream,
        assetLabel: String,
        wanted: List<Pair<String, String>>,
        destRoot: File,
    ): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        val prefixes = wanted.filter { it.first.endsWith("/") }
            .map { it.first.removeSuffix("/") to it.second.removeSuffix("/") }
        val exact = wanted.filterNot { it.first.endsWith("/") }.toMap()

        gzStream.use { raw ->
            val gz = GZIPInputStream(raw, 256 * 1024)
            val header = ByteArray(512)
            var pendingLongName: String? = null
            while (readFully(gz, header)) {
                if (header.all { it.toInt() == 0 }) break
                val typeFlag = header[156].toInt().toChar()
                var name = tarString(header, 0, 100)
                val prefix = tarString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                name = name.trim()

                // GNU 长名约定：typeflag 'L' 的块内容是下一个条目的完整路径
                if (typeFlag == 'L') {
                    val size = tarOctal(header, 124, 12).toInt()
                    val buf = ByteArray(size)
                    if (!readFully(gz, buf)) error("资产 $assetLabel 损坏：长名块不完整")
                    pendingLongName = String(buf, Charsets.US_ASCII).trimEnd('\u0000')
                    skipPadding(gz, size)
                    continue
                }
                if (typeFlag == 'x' || typeFlag == 'g') {
                    // PAX 扩展头：数据体里是 key=value 记录，必须整条跳过（只跳补齐会错位）
                    skipEntry(gz, tarOctal(header, 124, 12))
                    continue
                }
                val full = pendingLongName ?: name
                pendingLongName = null
                if (typeFlag == '5') {                     // 目录：size 通常为 0，仍按通用规则跳过更稳
                    skipEntry(gz, tarOctal(header, 124, 12))
                    continue
                }
                if (typeFlag != '0' && typeFlag != '\u0000') {
                    // 符号链接 / 硬链接 / 设备节点等：size 是链接目标长度，同样有数据体
                    skipEntry(gz, tarOctal(header, 124, 12))
                    continue
                }

                val size = tarOctal(header, 124, 12)
                var rel: String? = exact[full]
                if (rel == null) {
                    for ((p, target) in prefixes) {
                        if (full.startsWith("$p/")) {
                            rel = "$target/${full.removePrefix("$p/")}"
                            break
                        }
                    }
                }
                if (rel == null) {
                    // 🔴 不在这条白名单里的成员：连数据体一起跳过，否则流错位（见 skipEntry 注释）
                    skipEntry(gz, size)
                    continue
                }
                val dest = File(destRoot, rel.replace('/', File.separatorChar))
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { fos ->
                    var left = size
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) {
                        val want = if (left < buf.size) left.toInt() else buf.size
                        val got = gz.read(buf, 0, want)
                        if (got <= 0) error("资产 $assetLabel 损坏：$full 数据不足")
                        fos.write(buf, 0, got)
                        left -= got
                    }
                }
                skipPadding(gz, size.toInt())
                out += dest to rel
            }
        }
        if (out.isEmpty()) error("资产 $assetLabel 里没解出任何目标文件（白名单写错了？）")
        return out
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                error("流意外结束：还差 ${buf.size - off} 字节")
            }
            off += n
        }
        return true
    }

    private fun skipPadding(input: InputStream, size: Int) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0 && !readFully(input, ByteArray(pad))) {
            error("流意外结束：跳过补齐字节失败")
        }
    }

    /**
     * 跳过**整条** tar 条目（数据本体 + 512 对齐补齐）。
     *
     * 🔴 曾经这里只调 [skipPadding]（只跳补齐字节），于是「不要的条目」数据体留在流里 →
     * 下一条 header 从数据中间读起 → 全盘错位 → 白名单一个都匹配不上，报
     * 「资产里没解出任何目标文件（白名单写错了？）」。实测 addon 包真实 194 条，
     * 错位版能"走出" 346 条且命中 0 —— 这就是墨语言核心层永远部署不上的根因。
     */
    private fun skipEntry(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val want = if (left < buf.size) left.toInt() else buf.size
            val got = input.read(buf, 0, want)
            if (got <= 0) error("流意外结束：跳过条目数据失败（还差 $left 字节）")
            left -= got
        }
        skipPadding(input, size.toInt())
    }

    private fun tarString(header: ByteArray, off: Int, len: Int): String {
        val end = minOf(off + len, header.size)
        var e = off
        while (e < end && header[e].toInt() != 0) e++
        return String(header, off, e - off, Charsets.US_ASCII).trim()
    }

    private fun tarOctal(header: ByteArray, off: Int, len: Int): Long {
        val s = tarString(header, off, len)
        if (s.isEmpty()) return 0
        return runCatching { s.toLong(8) }.getOrDefault(0L)
    }
}
