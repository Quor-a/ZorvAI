# -*- coding: utf-8 -*-
"""Task #125：把 CardFence（```card / ```cards / ```cardui）接进 ChatScreen 正文渲染管线。

纯增量：
  1. MsgBlock 新增 Card 成员；
  2. parseBlocks / parseTail 认 card / cards / cardui 三种围栏头；
  3. isCardFenceLang 单一判定真源；
  4. extractInlineComponents 增加「保护区间」，围栏 JSON 不会被内联通道再抽一遍（防重复渲染）；
  5. 气泡剔除 Card 块、底部全宽内联渲染。
"""
import io
import os
import sys

P = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "ai", "assistance", "quro", "ui", "ChatScreen.kt",
)

with io.open(P, "r", encoding="utf-8", newline="") as f:
    raw = f.read()
crlf = "\r\n" in raw
s = raw.replace("\r\n", "\n")

edits = []


def rep(old, new, tag):
    edits.append((tag, old, new))


# ── 1. import ────────────────────────────────────────────────────────────────
rep(
    "import com.ai.assistance.quro.core.cards.CardActionRouter\n",
    "import com.ai.assistance.quro.core.cards.CardActionRouter\n"
    "import com.ai.assistance.quro.core.cards.CardFence\n",
    "import CardFence",
)

# ── 2. sealed class MsgBlock 新增 Card ───────────────────────────────────────
rep(
    "    data class Aip(val source: String) : MsgBlock()\n"
    "    data class Heading",
    "    data class Aip(val source: String) : MsgBlock()\n"
    "    /**\n"
    "     * 可视化组件围栏（正文第二通道）：```card（单组件）/ ```cards（多组件）/ ```cardui（A2UI 邻接表）。\n"
    "     * 与 DynamicUi / SelfCard 同源机制——从气泡剔除，改在消息底部全宽内联渲染。\n"
    "     * @param fence 围栏头（card / cards / cardui），交给 [CardFence.toCards] 复用同一解码层\n"
    "     * @param source 围栏内容（未 trim）\n"
    "     * @param closed 是否已闭合。false = 流式中间态，JSON 合法即自动出现\n"
    "     */\n"
    "    data class Card(val fence: String = CardFence.FENCE_CARD, val source: String = \"\", val closed: Boolean = true) : MsgBlock()\n"
    "    data class Heading",
    "MsgBlock.Card",
)

# ── 3. parseBlocks：闭合围栏 ─────────────────────────────────────────────────
rep(
    "        when {\n"
    "            // 自研卡片渲染（feat_self_card）：独立围栏 ```quro-card，先于动态 UI 判定，避免被劫持。",
    "        when {\n"
    "            // 可视化组件围栏（正文第二通道）：card / cards / cardui → MsgBlock.Card，\n"
    "            // 内容交给 CardFence 单一解码层，气泡里不再当代码块显示源码。\n"
    "            isCardFenceLang(lang) -> blocks.add(MsgBlock.Card(lang.lowercase(), code, true))\n"
    "            // 自研卡片渲染（feat_self_card）：独立围栏 ```quro-card，先于动态 UI 判定，避免被劫持。",
    "parseBlocks-closed",
)

# ── 4. parseTail：流式未闭合围栏 ─────────────────────────────────────────────
rep(
    "            when {\n"
    "                // 自研卡片渲染（feat_self_card）流式未闭合围栏：同样转 SelfCard 块（边写边出卡片）",
    "            when {\n"
    "                // 可视化组件围栏流式未闭合：闭口前就当卡片解析（边写边出卡，JSON 合法即显示）\n"
    "                isCardFenceLang(lang) -> out.add(MsgBlock.Card(lang.lowercase(), after, false))\n"
    "                // 自研卡片渲染（feat_self_card）流式未闭合围栏：同样转 SelfCard 块（边写边出卡片）",
    "parseTail-open",
)

# ── 5. isCardFenceLang 判定真源 ──────────────────────────────────────────────
rep(
    "private fun isSelfCardLang(lang: String): Boolean {\n"
    "    val l = lang.trim().lowercase()\n"
    "    return l == \"quro-card\" || l == \"quro_card\" || l == \"zorv-card\" || l == \"zorv_card\"\n"
    "}\n",
    "private fun isSelfCardLang(lang: String): Boolean {\n"
    "    val l = lang.trim().lowercase()\n"
    "    return l == \"quro-card\" || l == \"quro_card\" || l == \"zorv-card\" || l == \"zorv_card\"\n"
    "}\n"
    "\n"
    "/**\n"
    " * 判定是否为「可视化组件围栏」（正文第二通道）：card / cards / cardui。\n"
    " * 直接复用 [CardFence.ALL_FENCES] 单一真源：围栏头改名时解析层与渲染层不会各改各的\n"
    " * （历史教训：quro-ui / quro-card 都曾因两边判定不一致，出现「能解析却渲染成代码块」）。\n"
    " */\n"
    "private fun isCardFenceLang(lang: String): Boolean {\n"
    "    val l = lang.trim().lowercase()\n"
    "    return CardFence.ALL_FENCES.any { l == it || l == it.replace('-', '_') }\n"
    "}\n",
    "isCardFenceLang",
)

# ── 6. extractInlineComponents：保护区间 ─────────────────────────────────────
rep(
    "private fun extractInlineComponents(text: String): Pair<String, List<QuroChatCard>> {\n"
    "    if (text.isBlank()) return text to emptyList()\n"
    "    val cards = mutableListOf<QuroChatCard>()\n"
    "    val sb = StringBuilder()\n"
    "    var i = 0\n"
    "    while (i < text.length) {\n"
    "        val brace = text.indexOf('{', i)\n"
    "        if (brace < 0) { sb.append(text.substring(i)); break }\n"
    "        sb.append(text.substring(i, brace))\n"
    "        val end = findBalancedBrace(text, brace)\n"
    "        if (end < 0) { sb.append(text.substring(brace)); break }\n"
    "        val candidate = text.substring(brace, end + 1)\n",
    "private fun extractInlineComponents(text: String, protected: List<IntRange> = emptyList()): Pair<String, List<QuroChatCard>> {\n"
    "    if (text.isBlank()) return text to emptyList()\n"
    "    // 受保护区间（可视化组件围栏 ```card / ```cards / ```cardui）内的花括号就地抹成空格。\n"
    "    // 长度与下标完全不变 —— 下面所有扫描逻辑一行都不用改；效果是围栏里的 JSON 不会被\n"
    "    // 再当成「内联组件」抽走，否则围栏通道与内联通道会把同一张卡渲染两遍。\n"
    "    val scan = if (protected.isEmpty()) text else text.toCharArray().also { ch ->\n"
    "        for (r in protected) {\n"
    "            val from = maxOf(r.first, 0)\n"
    "            val to = minOf(r.last, ch.lastIndex)\n"
    "            for (x in from..to) if (ch[x] == '{') ch[x] = ' '\n"
    "        }\n"
    "    }.concatToString()\n"
    "    val cards = mutableListOf<QuroChatCard>()\n"
    "    val sb = StringBuilder()\n"
    "    var i = 0\n"
    "    while (i < text.length) {\n"
    "        val brace = scan.indexOf('{', i)\n"
    "        if (brace < 0) { sb.append(scan.substring(i)); break }\n"
    "        sb.append(scan.substring(i, brace))\n"
    "        val end = findBalancedBrace(scan, brace)\n"
    "        if (end < 0) { sb.append(scan.substring(brace)); break }\n"
    "        val candidate = scan.substring(brace, end + 1)\n",
    "extract-protect",
)

# ── 7. MessageRow：先算围栏区间，再护住内联扫描 ──────────────────────────────
rep(
    "    val (cleanText, inlineCards) = remember(displayText) { extractInlineComponents(displayText) }\n",
    "    // 可视化组件围栏（正文第二通道）区间：先定位，供下面的内联扫描「护住」围栏 JSON，\n"
    "    // 避免围栏通道与内联通道把同一张卡各渲染一遍。\n"
    "    val cardFenceSpans = remember(displayText) {\n"
    "        runCatching { CardFence.parse(displayText).map { it.start..it.end } }.getOrElse { emptyList<IntRange>() }\n"
    "    }\n"
    "    val (cleanText, inlineCards) = remember(displayText, cardFenceSpans) { extractInlineComponents(displayText, cardFenceSpans) }\n",
    "MessageRow-spans",
)

# ── 8. 气泡剔除 Card 块 ──────────────────────────────────────────────────────
rep(
    "                        val bubbleRenderBlocks = blocks.filter { it !is MsgBlock.DynamicUi && it !is MsgBlock.SelfCard }",
    "                        val bubbleRenderBlocks = blocks.filter { it !is MsgBlock.DynamicUi && it !is MsgBlock.SelfCard && it !is MsgBlock.Card }",
    "bubble-filter",
)

# ── 9. 气泡内空分支 ──────────────────────────────────────────────────────────
rep(
    "                                is MsgBlock.SelfCard -> {}\n",
    "                                is MsgBlock.SelfCard -> {}\n"
    "                                // 可视化组件围栏（card / cards / cardui）已在消息底部全宽内联渲染，\n"
    "                                // 气泡里不重复渲染（280dp 会把它压窄）。\n"
    "                                is MsgBlock.Card -> {}\n",
    "bubble-empty",
)

# ── 10. 底部全宽内联渲染 ─────────────────────────────────────────────────────
rep(
    "        // ── 消息富组件（ui_widget/ui_card 下发的卡片、AI 文本内联组件 JSON）：全宽内联在气泡下方渲染，",
    "        // ── 可视化组件围栏（正文第二通道 ```card / ```cards / ```cardui）：全宽内联渲染。\n"
    "        //    与动态 UI / 自研卡片同源机制：不在 280dp 气泡里渲染，撑满对话框宽度。\n"
    "        //    围栏内容走 CardFence.toCards 单一解码层；流式未闭合、JSON 还没合法时暂不显示，\n"
    "        //    JSON 补全后下一帧重解析自然产出（不闪空卡、不落回源码代码块）。\n"
    "        val fenceCards = remember(blocks) {\n"
    "            blocks.filterIsInstance<MsgBlock.Card>()\n"
    "                .mapNotNull { blk -> runCatching { CardFence.toCards(blk.fence, blk.source) }.getOrNull() }\n"
    "                .flatten()\n"
    "                // 流式防护：数据还没写完（表格/饼图/图表/热力图/雷达空数据）时不显示，\n"
    "                // 下一帧数据到齐重解析就会产出完整卡片，避免闪一下「（无数据）」。\n"
    "                .filter { c -> !cardHasNoData(c) }\n"
    "        }\n"
    "        if (fenceCards.isNotEmpty()) {\n"
    "            Spacer(Modifier.height(8.dp))\n"
    "            Box(Modifier.fillMaxWidth().clipToBounds()) {\n"
    "                FlowRow(\n"
    "                    Modifier.fillMaxWidth(),\n"
    "                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n"
    "                    verticalArrangement = Arrangement.spacedBy(8.dp),\n"
    "                    maxItemsInEachRow = Int.MAX_VALUE,\n"
    "                ) {\n"
    "                    for (c in fenceCards) {\n"
    "                        key(c.id) {\n"
    "                            QuroChatCardView(\n"
    "                                c,\n"
    "                                onCommand,\n"
    "                                modifier = if (isCompactQuroCard(c)) Modifier.wrapContentWidth() else Modifier.fillMaxWidth(),\n"
    "                            )\n"
    "                        }\n"
    "                    }\n"
    "                }\n"
    "            }\n"
    "        }\n"
    "        // ── 消息富组件（ui_widget/ui_card 下发的卡片、AI 文本内联组件 JSON）：全宽内联在气泡下方渲染，",
    "fence-render",
)


# ── 应用 ─────────────────────────────────────────────────────────────────────
def apply(s):
    for tag, old, new in edits:
        n = s.count(old)
        if n != 1:
            print("[FAIL] %s: 锚点命中 %d 次（应为 1）" % (tag, n))
            return None, tag
        s = s.replace(old, new, 1)
        print("[OK]   %s" % tag)
    return s, None


out, bad = apply(s)
if out is None:
    print("\n中止：锚点 %s 不唯一或不存在，未写盘。" % bad)
    sys.exit(1)

if crlf:
    out = out.replace("\n", "\r\n")

# 原子写
tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="") as f:
    f.write(out)
os.replace(tmp, P)
print("\n写盘完成：%s" % P)
