package com.ai.assistance.quro.core.mika.knowledge

/** 解析 APP.md 的头部:`key: value`、`key: [a, b]`、带引号字符串。值为 String 或 List<String>。 */
object FrontMatter {
    fun parse(md: String): Pair<Map<String, Any>, String> {
        val text = md.removePrefix("﻿").replace("\r\n", "\n")
        if (!text.startsWith("---\n")) return emptyMap<String, Any>() to text
        val lines = text.split("\n")
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" }
            ?: return emptyMap<String, Any>() to text
        val map = LinkedHashMap<String, Any>()
        for (line in lines.subList(1, end)) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).trim()
            val raw = line.substring(i + 1).trim()
            map[key] = if (raw.startsWith("[") && raw.endsWith("]")) {
                splitList(raw.substring(1, raw.length - 1)).map(::unquote).filter { it.isNotEmpty() }
            } else unquote(raw)
        }
        return map to lines.drop(end + 1).joinToString("\n").trim()
    }

    // 引号内的逗号不分隔
    private fun splitList(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (c in s) {
            when {
                quote != null -> { cur.append(c); if (c == quote) quote = null }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                c == ',' -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0]) t.substring(1, t.length - 1) else t
    }
}
