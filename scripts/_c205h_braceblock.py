import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterVerdictProtocol.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

old = """        val trimmed = text?.trim()
            ?.removePrefix("```json")?.removePrefix("```")
            ?.removeSuffix("```")?.trim().orEmpty()
        if (trimmed.isEmpty()) return ClusterVerdict.UNPARSABLE
        val o = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: runCatching { JSONObject(loosenJson(trimmed)) }.getOrNull()
            ?: return ClusterVerdict.UNPARSABLE"""

new = """        val trimmed = text?.trim()
            ?.removePrefix("```json")?.removePrefix("```")
            ?.removeSuffix("```")?.trim().orEmpty()
        if (trimmed.isEmpty()) return ClusterVerdict.UNPARSABLE
        // 三级递进：原文 → 剥出花括号块 → 宽松修复后再解析。
        // 🔴 第二级不可省：模型几乎总会在 JSON 外面加话（「这是我的验收结论：…以上。」），
        // 而 org.json 要求整串就是 JSON，前导一句中文就整串失败。
        // 这与引擎内 firstJson 的「按花括号配平截取」是同一步，这里补齐以免两边口径不一。
        val candidates = listOf(trimmed, extractBraceBlock(trimmed))
        var o: JSONObject? = null
        for (c in candidates) {
            if (c.isBlank()) continue
            o = runCatching { JSONObject(c) }.getOrNull()
                ?: runCatching { JSONObject(loosenJson(c)) }.getOrNull()
            if (o != null) break
        }
        if (o == null) return ClusterVerdict.UNPARSABLE"""

assert src.count(old) == 1, 'verdictOf anchor count=%d' % src.count(old)
src = src.replace(old, new, 1)

# 追加 extractBraceBlock
old2 = """    /**
     * 把「只差几层字符」的 JSON 修成合法 JSON。**只做无损的结构性修复**，"""
new2 = """    /**
     * 按花括号配平，从一段可能夹带废话的文本里截出**第一段** JSON 对象。
     *
     * 与引擎内 `firstJson` 的同名步骤同口径（两边不能一个能救一个不能）。
     * 截不到就返回空串，由调用方走下一级。
     */
    private fun extractBraceBlock(raw: String): String {
        val s = raw.indexOf('{')
        if (s < 0) return ""
        var depth = 0
        var inStr = false
        var i = s
        while (i < raw.length) {
            val c = raw[i]
            if (inStr) {
                if (c == '\\\\') { i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(s, i + 1)
                }
            }
            i++
        }
        return ""
    }

    /**
     * 把「只差几层字符」的 JSON 修成合法 JSON。**只做无损的结构性修复**，"""

assert src.count(old2) == 1, 'loosenJson doc anchor count=%d' % src.count(old2)
src = src.replace(old2, new2, 1)

tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  %d -> %d' % (len(orig), len(src)))