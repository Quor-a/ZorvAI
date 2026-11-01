import io

# 把 96d0deda2 的单行卡片容错重新加回当前（db69c47be 版）ChatScreen：
# 微信文章确认了 quro-card 围栏协议，模型单行乱写必须有救；回滚时这块被连带退掉。
p = "app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt"
s = io.open(p, encoding="utf-8").read()

old = """        for (m in RE_FENCE.findAll(text)) {
            val lang = m.groupValues[1].trim().lowercase()
            if (!isSelfCardLang(lang)) continue
            val code = m.groupValues[2].removeSuffix("\n")
            val spec = parseCardSpec(code) ?: continue
            if (!seen.add(spec.id)) continue
            out.add(spec)
        }
        return out
    }"""
new = """        for (m in RE_FENCE.findAll(text)) {
            val lang = m.groupValues[1].trim().lowercase()
            if (!isSelfCardLang(lang)) continue
            val code = m.groupValues[2].removeSuffix("\n")
            val spec = parseCardSpec(code) ?: continue
            if (!seen.add(spec.id)) continue
            out.add(spec)
        }
        if (out.isNotEmpty()) return out

        // ── 单行容错（2026-11-01 真机截图实证）：模型高频写「quro-card {json}」——
        // 围栏标记 ``` 丢失、语言标签和 JSON 挤在同一行。这不是围栏，RE_FENCE 抓不到。
        // 按出现位置逐个做**括号配对**提取（非贪婪正则会把嵌套 data 截断），解析失败的跳过。
        var idx = text.indexOf("quro-card")
        while (idx >= 0) {
            val brace = text.indexOf('{', idx)
            if (brace < 0) break
            val json = extractBalancedJson(text, brace)
            if (json != null) {
                val spec = runCatching { parseCardSpec(json) }.getOrNull()
                if (spec != null && seen.add(spec.id)) out.add(spec)
                idx = brace + json.length.coerceAtLeast(1)
            } else {
                idx = brace + 1
            }
            idx = text.indexOf("quro-card", idx)
        }
        return out
    }

    /** 从 [start]（必须指向 '{'）做括号配对提取一段完整 JSON（跳过字符串字面量内的括号）。 */
    private fun extractBalancedJson(text: String, start: Int): String? {
        if (start >= text.length || text[start] != '{') return null
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\\\' && inStr -> escape = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null // 未闭合（流式中）——交给围栏路径或下一条消息
    }"""
assert s.count(old) == 1, "anchor"
s = s.replace(old, new, 1)
# 转义还原：python 字符串里 \\\\ -> 实际应为 Kotlin 的 \\（两字符）；\n -> \n
s = s.replace("c == '\\\\' && inStr", "c == '" + chr(92) * 2 + "' && inStr")

io.open(p, "w", encoding="utf-8", newline="").write(s)
print("单行容错已恢复")
