package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * exe_editor：Windows PE 可执行文件检视与补丁（对应「其他 AI」文件与编辑维的 ExeEditor 能力）。
 * 纯 Kotlin（RandomAccessFile），无外部依赖。支持：info / sections / read / patch / find。
 * 只读检视安全；patch 为定点字节覆写（危险操作，需用户明确指定 offset 与 hex）。
 */
class QuroExeEditorTool : QuroTool {
    override val name: String = "exe_editor"

    override val description: String =
        "Windows PE 可执行文件检视与定点补丁（DOS+PE 头解析）。" +
            "action 取值：info(头部摘要) / sections(节表) / read(读字节 Hex 转储) / patch(定点覆盖写) / find(搜索 ASCII 串或 hex)。" +
            "path 为文件路径；offset 为 0 基文件偏移（十进制或 0x 十六进制）；length 为读取字节数（read 默认 256）；" +
            "hex 为 patch 的十六进制字节（空格分隔，如 '90 90 EB FE'）；pattern 为 find 的搜索串（ASCII 或空格分隔 hex）；" +
            "max_hits 为 find 最多返回命中数（默认 50）。"

    override val parametersJson: String = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","enum":["info","sections","read","patch","find"],"description":"动作"},
        "path":{"type":"string","description":"PE 文件路径"},
        "offset":{"type":"string","description":"0 基文件偏移（十进制或 0x 开头十六进制）"},
        "length":{"type":"integer","description":"read 动作读取的字节数（默认 256）"},
        "hex":{"type":"string","description":"patch 的十六进制字节（空格分隔，如 '90 90'）"},
        "pattern":{"type":"string","description":"find 的搜索内容（ASCII 串或空格分隔 hex）"},
        "max_hits":{"type":"integer","description":"find 最多命中数（默认 50）"}
      },
      "required":["action","path"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "").lowercase()
            val path = args.optString("path", "")
            if (path.isBlank()) return "参数 path 为空"
            val f = File(path)
            if (!f.isFile || !f.canRead()) return "文件不可读：$path"
            val raf = RandomAccessFile(f, "rw")
            try {
                val len = raf.length()
                if (len < 64) return "文件过小，不是 PE：$path"
                val mz = ByteArray(2)
                raf.seek(0); raf.readFully(mz)
                if (!(mz[0] == 'M'.code.toByte() && mz[1] == 'Z'.code.toByte())) return "不是 MZ 开头，非 DOS/PE 文件"
                raf.seek(0x3CL)
                val eLfanew = readLe32(raf)
                if (eLfanew + 4 > len) return "PE 头偏移越界"
                raf.seek(eLfanew)
                val sig = ByteArray(4); raf.readFully(sig)
                if (!(sig[0] == 'P'.code.toByte() && sig[1] == 'E'.code.toByte() && sig[2] == 0.toByte() && sig[3] == 0.toByte()))
                    return "PE 签名缺失（offset=$eLfanew）"
                val peOff = eLfanew
                raf.seek((peOff + 4).toLong())
                val machine = readLe16(raf)
                val numSections = readLe16(raf)
                readLe32(raf) // timestamp
                readLe32(raf) // ptr to symtab
                readLe32(raf) // num symbols
                val optSize = readLe16(raf)
                val characteristics = readLe16(raf)
                raf.seek((peOff + 24).toLong())
                val magic = readLe16(raf) // 0x10B PE32 / 0x20B PE32+
                raf.seek((peOff + 24 + 16).toLong())
                val entryPoint = readLe32(raf)
                val ibOff = if (magic == 0x20B) 24 else 28
                raf.seek((peOff + 24 + ibOff).toLong())
                val imageBase = if (magic == 0x20B) readLe64(raf) else readLe32(raf)

                when (action) {
                    "info" -> {
                        val o = JSONObject()
                        o.put("size", len)
                        o.put("machine", machineHex(machine))
                        o.put("isPE32plus", magic == 0x20B)
                        o.put("numberOfSections", numSections)
                        o.put("entryPointRVA", "0x${entryPoint.toString(16)}")
                        o.put("imageBase", "0x${imageBase.toString(16)}")
                        o.put("dll", (characteristics and 0x2000) != 0)
                        o.put("characteristics", charFlags(characteristics))
                        o.toString(2)
                    }
                    "sections" -> {
                        val secOff = peOff + 24 + optSize
                        if (secOff + 40L * numSections > len) return "节表越界"
                        val arr = JSONArray()
                        for (i in 0 until numSections) {
                            val base = secOff + 40L * i
                            raf.seek(base)
                            val nameB = ByteArray(8); raf.readFully(nameB)
                            val name = nameB.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
                            raf.seek(base + 8)
                            val vsize = readLe32(raf)
                            val vaddr = readLe32(raf)
                            raf.seek(base + 16)
                            val rsize = readLe32(raf)
                            val raddr = readLe32(raf)
                            val so = JSONObject()
                            so.put("name", name)
                            so.put("virtualAddress", "0x${vaddr.toString(16)}")
                            so.put("virtualSize", vsize)
                            so.put("rawAddress", "0x${raddr.toString(16)}")
                            so.put("rawSize", rsize)
                            arr.put(so)
                        }
                        arr.toString(2)
                    }
                    "read" -> {
                        val off = parseOffset(args.optString("offset", "0"))
                        val n = args.optInt("length", 256)
                        if (off < 0 || off + n > len) return "读取范围越界（offset=$off len=$len）"
                        raf.seek(off); val b = ByteArray(n); raf.readFully(b)
                        hexDump(b, off)
                    }
                    "patch" -> {
                        val off = parseOffset(args.optString("offset", ""))
                        val hexStr = args.optString("hex", "")
                        if (off < 0) return "patch 需要 offset"
                        if (hexStr.isBlank()) return "patch 需要 hex"
                        val bytes = hexToBytes(hexStr)
                        if (off + bytes.size > len) return "patch 越界（offset=$off + ${bytes.size} > len=$len）"
                        raf.seek(off); raf.write(bytes)
                        "已在 offset=0x${off.toString(16)} 写入 ${bytes.size} 字节（hex=$hexStr）：$path"
                    }
                    "find" -> {
                        val pat = args.optString("pattern", "")
                        if (pat.isBlank()) return "find 需要 pattern（ASCII 串或空格分隔 hex）"
                        val needle = try { hexToBytes(pat) } catch (_: Throwable) { pat.toByteArray(Charsets.US_ASCII) }
                        if (needle.isEmpty()) return "pattern 解析为空"
                        val maxHits = args.optInt("max_hits", 50)
                        val hits = mutableListOf<Long>()
                        val buf = ByteArray(1 shl 16)
                        var pos = 0L
                        while (pos + needle.size <= len && hits.size < maxHits) {
                            raf.seek(pos); val r = raf.read(buf)
                            if (r <= 0) break
                            val limit = (r - needle.size + 1).coerceAtLeast(0)
                            for (i in 0 until limit) {
                                var match = true
                                for (j in needle.indices) { if (buf[i + j] != needle[j]) { match = false; break } }
                                if (match) { hits.add(pos + i); if (hits.size >= maxHits) break }
                            }
                            pos += (r - needle.size).coerceAtLeast(1)
                        }
                        if (hits.isEmpty()) "未找到 pattern：$pat"
                        else "命中 ${hits.size} 处（最多 $maxHits）：${hits.joinToString { "0x${it.toString(16)}" }}"
                    }
                    else -> "不支持的 action：$action（支持 info/sections/read/patch/find）"
                }
            } finally { raf.close() }
        } catch (e: Exception) {
            "exe_editor 执行失败：${e.message}"
        }
    }

    private fun readLe16(raf: RandomAccessFile): Int {
        val b = ByteArray(2); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }
    private fun readLe32(raf: RandomAccessFile): Long {
        val b = ByteArray(4); raf.readFully(b)
        var v = 0L
        for (i in 0..3) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
        return v
    }
    private fun readLe64(raf: RandomAccessFile): Long {
        val b = ByteArray(8); raf.readFully(b)
        var v = 0L
        for (i in 0..7) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
        return v
    }
    private fun parseOffset(s: String): Long {
        if (s.isBlank()) return -1
        return if (s.startsWith("0x", true)) s.substring(2).toLong(16) else s.toLong()
    }
    private fun hexToBytes(s: String): ByteArray {
        val parts = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
    }
    private fun machineHex(m: Int): String = when (m) {
        0x14c -> "x86 (0x14c)"
        0x8664 -> "x64 (0x8664)"
        0x1c0 -> "ARM (0x1c0)"
        0xaa64 -> "ARM64 (0xaa64)"
        else -> "0x${m.toString(16)}"
    }
    private fun charFlags(c: Int): JSONArray {
        val map = listOf(
            0x0001 to "RELOCS_STRIPPED", 0x0002 to "EXECUTABLE_IMAGE", 0x0020 to "LARGE_ADDRESS_AWARE",
            0x0100 to "32BIT_MACHINE", 0x1000 to "SYSTEM", 0x2000 to "DLL",
            0x0400 to "REMOVABLE_RUN_FROM_SWAP", 0x0800 to "NET_RUN_FROM_SWAP",
        )
        val arr = JSONArray()
        map.forEach { if (c and it.first != 0) arr.put(it.second) }
        return arr
    }
    private fun hexDump(b: ByteArray, base: Long): String {
        val sb = StringBuilder()
        for (i in b.indices step 16) {
            val addr = (base + i).toString(16).padStart(8, '0')
            val hex = b.drop(i).take(16).joinToString(" ") { "%02x".format(it) }
            val asc = b.drop(i).take(16).map { if (it in 0x20..0x7E) Char(it.toInt()) else '.' }.joinToString("")
            sb.append("$addr  $hex  $asc\n")
        }
        return sb.toString()
    }
}
