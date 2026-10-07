# -*- coding: utf-8 -*-
"""给 ChatScreen 的围栏正则加「属性」支持。

改动点：
1. RE_FENCE / RE_FENCE_OPEN 的 lang 组从 [\\w+#-]* 放宽到 [^\\n`]*，
   让 ```card compact title=X 的属性不再被并进围栏体。
2. parseBlocks / parseTail 用 splitFenceHead 切出 (lang, attrs)，
   卡片围栏把 attrs 透传给 MsgBlock.Card。
原子写：.tmp + os.replace
"""
import io
import os

P = "app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt"
s = io.open(P, encoding="utf-8").read()

# ── 1. 放宽两个正则的 lang 组 ──
old_main = 'private val RE_FENCE = Regex("(?m)^```([\\\\w+#-]*)\\\\n?([\\\\s\\\\S]*?)```")'
new_main = (
    'private val RE_FENCE = Regex("(?m)^```([^\\\\n`]*)\\\\n?([\\\\s\\\\S]*?)```")'
)
assert s.count(old_main) == 1, "RE_FENCE 原文未唯一匹配: %d" % s.count(old_main)
s = s.replace(old_main, new_main)

old_open = 'private val RE_FENCE_OPEN = Regex("""(?m)^```([a-zA-Z0-9_+#-]*)[ \\t]*\\n""")'
new_open = 'private val RE_FENCE_OPEN = Regex("""(?m)^```([^\\n`]*?)[ \\t]*\\n""")'
assert s.count(old_open) == 1, "RE_FENCE_OPEN 原文未唯一匹配: %d" % s.count(old_open)
s = s.replace(old_open, new_open)

# ── 2. parseBlocks：切分头/属性 ──
old_a = """        val lang = m.groupValues[1].trim()
        val code = m.groupValues[2].removeSuffix("\\n")
        when {
            // 可视化组件围栏（正文第二通道）：card / cards / cardui → MsgBlock.Card，
            // 内容交给 CardFence 单一解码层，气泡里不再当代码块显示源码。
            isCardFenceLang(lang) -> blocks.add(MsgBlock.Card(lang.lowercase(), code, true))"""
new_a = """        val (langRaw, fenceAttrs) = splitFenceHead(m.groupValues[1])
        val lang = langRaw
        val code = m.groupValues[2].removeSuffix("\\n")
        when {
            // 可视化组件围栏（正文第二通道）：card / cards / cardui / cardjson → MsgBlock.Card，
            // 内容交给 CardFence 单一解码层，气泡里不再当代码块显示源码。
            isCardFenceLang(lang) -> blocks.add(MsgBlock.Card(lang.lowercase(), code, true, fenceAttrs))"""
assert s.count(old_a) == 1, "parseBlocks 卡片分支未唯一匹配: %d" % s.count(old_a)
s = s.replace(old_a, new_a)

# ── 3. parseTail（流式未闭合）：同样切分 ──
old_b = """        val lang = open.groupValues[1].trim()
        // 语言非空，或虽为空但有后续内容 → 视为开围栏（流式未闭合，而非孤立的闭合围栏）"""
new_b = """        val (langRaw, fenceAttrs) = splitFenceHead(open.groupValues[1])
        val lang = langRaw
        // 语言非空，或虽为空但有后续内容 → 视为开围栏（流式未闭合，而非孤立的闭合围栏）"""
assert s.count(old_b) == 1, "parseTail 头解析未唯一匹配: %d" % s.count(old_b)
s = s.replace(old_b, new_b)

old_c = 'isCardFenceLang(lang) -> out.add(MsgBlock.Card(lang.lowercase(), after, false))'
new_c = 'isCardFenceLang(lang) -> out.add(MsgBlock.Card(lang.lowercase(), after, false, fenceAttrs))'
assert s.count(old_c) == 1, "parseTail 卡片分支未唯一匹配: %d" % s.count(old_c)
s = s.replace(old_c, new_c)

# ── 4. MsgBlock.Card 加 attrs 字段 ──
old_d = """     * @param fence 围栏头（card / cards / cardui / cardjson），交给 [CardFence.toCards] 复用同一解码层
     * @param source 围栏内容（未 trim）
     * @param closed 是否已闭合。false = 流式中间态，JSON 合法即自动出现
     */
    data class Card(val fence: String = CardFence.FENCE_CARD, val source: String = "", val closed: Boolean = true) : MsgBlock()"""
new_d = """     * @param fence 围栏头（card / cards / cardui / cardjson），交给 [CardFence.toCards] 复用同一解码层
     * @param source 围栏内容（未 trim）
     * @param closed 是否已闭合。false = 流式中间态，JSON 合法即自动出现
     * @param attrs 围栏属性串（`compact scroll title=季度 theme=accent`），渲染层据此调外观。
     *   存原始串而不是解析好的集合：解析规则只存在于 [CardFence]，这里不重复实现一遍。
     */
    data class Card(
        val fence: String = CardFence.FENCE_CARD,
        val source: String = "",
        val closed: Boolean = true,
        val attrs: String = "",
    ) : MsgBlock()"""
assert s.count(old_d) == 1, "MsgBlock.Card 定义未唯一匹配: %d" % s.count(old_d)
s = s.replace(old_d, new_d)

# 原子写
tmp = P + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(s)
os.replace(tmp, P)
print("OK 已写入", P)