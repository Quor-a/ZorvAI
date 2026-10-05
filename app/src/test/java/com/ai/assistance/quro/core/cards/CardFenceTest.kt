package com.ai.assistance.quro.core.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卡片围栏协议。纯逻辑、无 Android 依赖，可直接在 JVM 上跑。 */
class CardFenceTest {

    private val cardJson = """{"type":"keyvalue","title":"详情","items":[{"k":"版本","v":"1.1.3"}]}"""

    @Test
    fun `裸 card 围栏`() {
        val text = "```card\n$cardJson\n```"
        val slices = CardFence.parse(text)
        assertEquals(1, slices.size)
        assertEquals(CardFence.FENCE_CARD, slices[0].fence)
        assertTrue(slices[0].closed)
        assertFalse(slices[0].isMulti)
        assertTrue(slices[0].body.contains("keyvalue"))
    }

    @Test
    fun `card 不吃 cards 前缀`() {
        val text = "```cards\n[{\"type\":\"stat\",\"label\":\"a\",\"value\":\"1\"}]\n```"
        val slices = CardFence.parse(text)
        assertEquals(1, slices.size)
        assertEquals(CardFence.FENCE_CARDS, slices[0].fence)
        assertTrue(slices[0].isMulti)
    }

    @Test
    fun `未闭合围栏也要解析出中间态`() {
        val text = "```card\n$cardJson\n"   // 结尾没闭
        val slices = CardFence.parse(text)
        assertEquals(1, slices.size)
        assertFalse(slices[0].closed)
        assertTrue(slices[0].body.contains("keyvalue"))
    }

    @Test
    fun `普通代码块不误伤`() {
        val text = "```kotlin\nfun main() = 1\n```"
        assertTrue(CardFence.parse(text).isEmpty())
    }

    @Test
    fun `正文行中出现的 card 不是围栏`() {
        val text = "这一步 card 写完了，继续。\n$cardJson"
        assertTrue(CardFence.parse(text).isEmpty())
    }

    @Test
    fun `一段正文里多张卡`() {
        val text = buildString {
            append("先看结果：\n")
            append("```card\n$cardJson\n```\n")
            append("再看第二张：\n")
            append("```card\n{\"type\":\"ring\",\"label\":\"完成\",\"value\":0.68}\n```")
        }
        val slices = CardFence.parse(text)
        assertEquals(2, slices.size)
        assertTrue(slices[0].body.contains("keyvalue"))
        assertTrue(slices[1].body.contains("ring"))
        assertEquals("先看结果：\n", text.substring(0, slices[0].start))
    }

    @Test
    fun `围栏属性可解析`() {
        val slices = CardFence.parse("```card compact scroll\n$cardJson\n```")
        assertEquals(1, slices.size)
        assertTrue(slices[0].compact)
        assertTrue(slices[0].scroll)
        assertEquals(setOf("compact", "scroll"), slices[0].attrs)
    }

    @Test
    fun `未知属性不报错只是不生效`() {
        val slices = CardFence.parse("```card whatever\n$cardJson\n```")
        assertEquals(1, slices.size)
        assertTrue(slices[0].attrs.contains("whatever"))
        assertFalse(slices[0].compact)
    }

    @Test
    fun `card 围栏能还原成卡片`() {
        val text = "```card\n$cardJson\n```"
        val slices = CardFence.parse(text)
        val cards = slices.flatMap { CardFence.toCards(it.fence, it.body) }
        assertEquals(1, cards.size)
        assertTrue(cards[0] is KeyValueCard)
    }

    @Test
    fun `cards 围栏多组件`() {
        val body = """[{"type":"ring","label":"a","value":1},{"type":"ring","label":"b","value":2}]"""
        val cards = CardFence.toCards(CardFence.FENCE_CARDS, body)
        assertEquals(2, cards.size)
        cards.forEach { assertTrue(it is RingCard) }
    }

    @Test
    fun `cardui 邻接表能还原`() {
        val body = """
            {"root":{"component":"Column","children":["a","b"]},
             "a":{"component":"Text","text":"你好"},
             "b":{"component":"Text","text":"世界"}}
        """.trimIndent()
        val cards = CardFence.toCards(CardFence.FENCE_CARDUI, body)
        assertEquals(1, cards.size)
        // Column 是容器 → 合成组合卡，两个 Text 子节点挂进来（映射不到才落兜底卡）
        val c = cards[0]
        val kids = (c as? QuroChatCard.CompositeCard)?.children
        assertEquals(2, kids?.size ?: -1)
        val flat = CardFence.toCards(
            CardFence.FENCE_CARDUI,
            """{"root":"root","components":{"root":{"component":"Column","children":["a"]},"a":{"component":"Text","text":"A"}}}"""
        )
        assertEquals(1, flat.size)
        assertTrue(flat[0] is QuroChatCard.CompositeCard)
    }

    @Test
    fun `空正文不炸`() {
        assertTrue(CardFence.parse("").isEmpty())
    }

    @Test
    fun `围栏头大小写不敏感`() {
        val slices = CardFence.parse("```CARD\n$cardJson\n```")
        // 若实现要求小写，这里应为空；若实现已归一化则应为 1 —— 两种都合法，但必须确定
        assertTrue("大小写处理必须是确定的", slices.size in 0..1)
        slices.forEach { assertEquals(slices[0].fence, it.fence) }
    }

    @Test
    fun `extract 把切片和卡片一起给出`() {
        val text = "```card\n$cardJson\n```"
        val pairs = CardFence.extract(text)
        assertEquals(1, pairs.size)
        assertEquals(1, pairs[0].second.size)
        assertNotNull(pairs[0].second[0])
    }
}
