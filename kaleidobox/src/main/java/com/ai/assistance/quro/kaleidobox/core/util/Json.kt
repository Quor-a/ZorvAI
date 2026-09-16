package com.ai.assistance.quro.kaleidobox.core.util

import com.ai.assistance.quro.kaleidobox.core.model.*

/**
 * 极简 JSON 读写。核心模块刻意不引第三方依赖（Gson/Moshi），
 * 让 kaleido-core 可以直接在 JVM / Android / 单测里跑，也避免与宿主已有的 JSON 库冲突。
 */
object Json {

    fun parse(text: String): Any? = Parser(text).parseValue()

    fun write(v: Any?): String = buildString { writeValue(v, this) }

    // ---------------- write ----------------
    private fun writeValue(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is KValue -> writeValue(fromK(v), sb)
            is Boolean -> sb.append(v)
            is Number -> sb.append(v)
            is String -> writeString(v, sb)
            is ByteArray -> writeString(v.toString(Charsets.ISO_8859_1), sb)
            is Map<*, *> -> {
                sb.append('{')
                v.entries.withIndex().forEach { (i, e) ->
                    if (i > 0) sb.append(',')
                    writeString(e.key.toString(), sb)
                    sb.append(':')
                    writeValue(e.value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                v.withIndex().forEach { (i, e) ->
                    if (i > 0) sb.append(',')
                    writeValue(e, sb)
                }
                sb.append(']')
            }
            is Array<*> -> writeValue(v.toList(), sb)
            is Enum<*> -> writeString(v.name, sb)
            else -> writeString(v.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        s.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** KValue → 原生 JSON 结构（用于输出给脚本引擎 / 网络）。 */
    fun fromK(v: KValue): Any? = when (v) {
        is KValue.Null -> null
        is KValue.Bool -> v.value
        is KValue.I64 -> v.value
        is KValue.F64 -> v.value
        is KValue.Str -> v.value
        is KValue.Bytes -> v.value.toString(Charsets.ISO_8859_1)
        is KValue.Arr -> v.value.map { fromK(it) }
        is KValue.Obj -> v.value.mapValues { fromK(it.value) }
        is KValue.Handle -> mapOf("__kHandle" to v.id, "__kKind" to v.kind.name, "__kLabel" to v.label)
        is KValue.Err -> mapOf("__kErr" to v.code, "message" to v.message, "detail" to (v.detail?.let { fromK(it) }))
    }

    /** 原生 JSON 结构 → KValue（脚本引擎回传时用）。 */
    fun toK(v: Any?): KValue = when (v) {
        null -> KValue.Null
        is KValue -> v
        is Boolean -> KValue.Bool(v)
        is Int -> KValue.I64(v.toLong())
        is Long -> KValue.I64(v)
        is Double -> KValue.F64(v)
        is Float -> KValue.F64(v.toDouble())
        is String -> KValue.Str(v)
        is ByteArray -> KValue.Bytes(v)
        is List<*> -> KValue.Arr(v.map { toK(it) })
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val m = v as Map<String, Any?>
            when {
                m.containsKey("__kHandle") -> KValue.Handle(
                    (m["__kHandle"] as Number).toLong(),
                    HandleKind.valueOf(m["__kKind"] as String),
                    m["__kLabel"] as? String ?: ""
                )
                m.containsKey("__kErr") -> KValue.Err(
                    m["__kErr"] as String,
                    m["message"] as? String ?: "",
                    (m["detail"] as? Map<*, *>)?.let { toK(it) as? KValue.Obj }
                )
                else -> KValue.Obj(m.mapValues { toK(it.value) })
            }
        }
        else -> KValue.Str(v.toString())
    }

    // ---------------- read ----------------
    private class Parser(private val src: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            return when (peek()) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNum()
            }.also { skipWs() }
        }

        private fun parseObj(): Map<String, Any?> {
            expect("{"); val out = linkedMapOf<String, Any?>(); skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs(); val k = parseStr(); skipWs(); expect(":"); out[k] = parseValue(); skipWs()
                if (peek() == ',') i++ else { expect("}"); break }
            }
            return out
        }

        private fun parseArr(): List<Any?> {
            expect("["); val out = mutableListOf<Any?>(); skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(parseValue()); skipWs()
                if (peek() == ',') i++ else { expect("]"); break }
            }
            return out
        }

        private fun parseStr(): String {
            expect("\""); val sb = StringBuilder()
            while (true) {
                val c = src[i++]
                if (c == '"') break
                if (c != '\\') { sb.append(c); continue }
                when (val e = src[i++]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> {
                        sb.append(src.substring(i, i + 4).toInt(16).toChar()); i += 4
                    }
                    else -> sb.append(e)
                }
            }
            return sb.toString()
        }

        private fun parseNum(): Any {
            val start = i
            if (peek() == '-' || peek() == '+') i++
            while (i < src.length && (src[i].isDigit() || src[i] in "eE+-.")) i++
            val s = src.substring(start, i)
            return if (s.contains('.') || s.contains('e') || s.contains('E')) s.toDouble() else s.toLong()
        }

        private fun skipWs() { while (i < src.length && src[i].isWhitespace()) i++ }
        private fun peek() = if (i < src.length) src[i] else '\u0000'
        private fun expect(t: String) {
            require(src.startsWith(t, i)) { "JSON 解析失败 @$i: 期望 '$t'" }
            i += t.length
        }
    }
}
