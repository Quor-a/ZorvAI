package com.ai.assistance.quro.core.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 围栏 → 渲染管线（v1400 · 正文第二通道）的契约测试。
 *
 * 这里盯的是「接线层」容易悄悄坏掉的四件事：
 *  1. 三档围栏（card / cards / cardui）在同一段正文里必须**互不串台**；
 *  2. 流式未闭合（closed=false）必须能出卡，而不是整段退化成代码块；
 *  3. [CardFence.blankBraces] 之后**下标必须保持不变**，否则保护区间会错位，
 *     围栏 JSON 漏出去被内联通道再抽一遍 → 同一张卡渲染两遍；
 *  4. 闭合围栏打错语言、正文里写着 card 字样这类脏输入不能让卡消失或误触发。
 */
class CardFencePipelineTest {

    // ── blankBraces：保护区间 ──────────────────────────────────────────────

    @Test
    fun `blankBraces 抹掉左花括号但长度下标不变`() {
        // 只抹**左**花括号（右花括号原样保留）：外层 findBalancedBrace 找不到起点，
        // 整段围栏 JSON 自然被跳过；同时下标/长度一字节不变，调用方还能用原始下标定位围栏。
        // text = "先看：\n{\"type\":\"button\"}\n结束"
        // 下标：0..2=先看：  3=\n  4={  5..19={"type":"button"}  20=}  21=\n  22..=结束
        val text = "先看：\n" + "{\"type\":\"button\"}" + "\n结束"
        val out = CardFence.blankBraces(text, listOf(4..20))
        assertEquals(text.length, out.length)
        assertEquals(' ', out[4])
        // 右花括号不抹 —— 这是刻意的最小侵入：只动会破坏「找起点」的那个字符
        assertEquals('}', out[20])
        assertEquals(0, out.count { it == '{' })
    }

    @Test
    fun `blankBraces 空区间原样返回`() {
        val text = "{\"type\":\"button\"}"
        assertEquals(text, CardFence.blankBraces(text, emptyList()))
    }

    @Test
    fun `blankBraces 区间越界不越界访问`() {
        val text = "{\"a\":1}"
        // -5..0 被夹成 0..0（正好抹掉唯一的左花括号）；3..99 被夹成 3..6（其中没有左花括号）
        val out = CardFence.blankBraces(text, listOf(-5..0, 3..99))
        assertEquals(text.length, out.length)
        assertEquals(' ', out[0])
        assertEquals(" \"a\":1}", out)
    }

    // ── 三档围栏共存 ───────────────────────────────────────────────────────

    @Test
    fun `同一段正文里 card 与 cards 互不串台`() {
        val text = buildString {
            append("单张：\n```card\n")
            append("{\"type\":\"ring\",\"label\":\"a\",\"value\":0.5}\n```\n")
            append("多张：\n```cards\n[{\"type\":\"ring\",\"label\":\"b\",\"value\":1}]\n```")
        }
        val pairs = CardFence.extract(text)
        assertEquals(2, pairs.size)
        assertEquals(CardFence.FENCE_CARD, pairs[0].first.fence)
        assertEquals(CardFence.FENCE_CARDS, pairs[1].first.fence)
        assertEquals(1, pairs[0].second.size)
        assertEquals(1, pairs[1].second.size)
    }

    @Test
    fun `cardui 与 card 相邻时不会互相吃掉内容`() {
        val text = """
            ```cardui
            [{"id":"root","component":"Column","children":["t"]},
             {"id":"t","component":"Text","text":"你好"}]
            ```
            ```card
            {"type":"ring","label":"b","value":0.25}
            ```
        """.trimIndent()
        val pairs = CardFence.extract(text)
        assertEquals(2, pairs.size)
        assertEquals(1, pairs[0].second.size)
        assertTrue(pairs[0].second[0] is QuroChatCard.CompositeCard)
        assertEquals(CardFence.FENCE_CARD, pairs[1].first.fence)
        assertEquals(1, pairs[1].second.size)
    }

    // ── 流式（未闭合）──────────────────────────────────────────────────────

    @Test
    fun `未闭合围栏内容合法也要给出卡`() {
        val text = "```card\n" + "{\"type\":\"ring\",\"label\":\"a\",\"value\":0.8}"
        val pairs = CardFence.extract(text)
        assertEquals(1, pairs.size)
        assertFalse(pairs[0].first.closed)
        assertEquals(1, pairs[0].second.size)
    }

    @Test
    fun `流式截断的 JSON 不炸也不吐半成品卡`() {
        // 写到一半的 JSON：既不能抛异常，也不能返回一个「看着像但数据是空的」卡
        val text = "```card\n{\"type\":\"stat\",\"label\":\"用户\",\"val"
        assertTrue("截断时宁可不给卡，也不能给坏卡", CardFence.extract(text).isEmpty())
    }

    // ── cards 组合卡 ───────────────────────────────────────────────────────

    @Test
    fun `cards 的 children 形态合成一张组合卡`() {
        val body = "{\"layout\":\"stack\",\"title\":\"面板\",\"children\":[" +
            "{\"type\":\"ring\",\"label\":\"a\",\"value\":1}," +
            "{\"type\":\"ring\",\"label\":\"b\",\"value\":2}]}"
        val cards = CardFence.toCards(CardFence.FENCE_CARDS, body)
        assertEquals(1, cards.size)
        val c = cards[0] as? QuroChatCard.CompositeCard
            ?: throw AssertionError("期望组合卡，实际 ${cards[0].javaClass.simpleName}")
        assertEquals(2, c.children.size)
    }

    @Test
    fun `cards 数组里混入非对象元素不整体失败`() {
        val body = "[{\"type\":\"ring\",\"label\":\"a\",\"value\":1},\"坏元素\",null,42]"
        val cards = CardFence.toCards(CardFence.FENCE_CARDS, body)
        assertEquals(1, cards.size)
    }

    // ── 围栏头边界 ─────────────────────────────────────────────────────────

    @Test
    fun `plain 围栏里写着 card 字样不误判`() {
        val text = "```json\n{\"note\":\"this is card data\"}\n```"
        assertTrue(CardFence.parse(text).isEmpty())
    }

    @Test
    fun `缩进的围栏头也算`() {
        val text = "  ```card\n  {\"type\":\"ring\",\"label\":\"a\",\"value\":1}\n  ```"
        val slices = CardFence.parse(text)
        assertEquals(1, slices.size)
        assertEquals(CardFence.FENCE_CARD, slices[0].fence)
        assertTrue(slices[0].closed)
    }

    @Test
    fun `闭合围栏标错语言不算闭合但卡不能消失`() {
        // 约定：只有「纯 ``` 行」才当闭合标记。模型把闭合写成 ```py 不算闭合，
        // 但**绝不能因此把整张卡丢掉**——未闭合也是合法结果，照样要能把卡解析出来。
        val text = "```card\n{\"type\":\"ring\",\"label\":\"a\",\"value\":1}\n```py"
        val slices = CardFence.parse(text)
        assertEquals(1, slices.size)
        assertFalse(slices[0].closed)
        // 关键：未闭合也照样出卡，界面不会闪一下源码代码块再跳回空白
        assertEquals(1, CardFence.toCards(slices[0].fence, slices[0].body).size)
    }
}
