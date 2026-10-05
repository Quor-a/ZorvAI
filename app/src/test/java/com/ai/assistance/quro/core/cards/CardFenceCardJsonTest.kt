package com.ai.assistance.quro.core.cards

import com.ai.assistance.quro.core.cards.CardFence.FENCE_CARDJSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 4 种围栏 `cardjson` 的自检。
 *
 * 动机：card / cards 的围栏体是一整段 JSON，流式时必须等整段收全能解析，
 * 而模型实际经常一行一个卡片地陆续吐，这段时间用户只能看到代码块。
 * 逐行约定让每行独立解析：已完整的行立刻出卡，末尾没收全那行等闭合后补上，
 * 且单行脏数据不连累其余各行。
 */
class CardFenceCardJsonTest {

    @Test
    fun `cardjson 已纳入受支持围栏`() {
        assertTrue("cardjson 应在 ALL_FENCES 里", CardFence.ALL_FENCES.contains(FENCE_CARDJSON))
        assertEquals(4, CardFence.ALL_FENCES.size)
    }

    @Test
    fun `cardjson 不会与 card 互相误配`() {
        // 关键：OPEN_RE 用 \b 消歧，card 不能吃掉 cardjson 的前缀，反之亦然
        val cardJson = CardFence.parse("```cardjson\n{\"type\":\"stat\",\"label\":\"a\",\"value\":\"1\"}\n```")
        assertEquals(1, cardJson.size)
        assertEquals(FENCE_CARDJSON, cardJson[0].fence)

        val plainCard = CardFence.parse("```card\n{\"type\":\"stat\",\"label\":\"a\",\"value\":\"1\"}\n```")
        assertEquals(1, plainCard.size)
        assertEquals(CardFence.FENCE_CARD, plainCard[0].fence)
    }

    @Test
    fun `一行一个卡片解析出多张`() {
        val text = """
            ```cardjson
            {"type":"stat","label":"日活","value":"1.2k"}
            {"type":"stat","label":"留存","value":"63%"}
            {"type":"stat","label":"付费","value":"4.1%"}
            ```
        """.trimIndent()
        val cards = CardFence.toCards(FENCE_CARDJSON, text.substringAfter("cardjson").substringBeforeLast("```"))
        assertEquals(3, cards.size)
        assertTrue(cards.all { it is QuroChatCard.StatCard })
    }

    @Test
    fun `流式中间态已完整的行照样能出卡`() {
        // 未闭合围栏：模拟模型只吐了 2 行半
        val body = "\n{\"type\":\"stat\",\"label\":\"日活\",\"value\":\"1.2k\"}\n{\"type\":\"stat\",\"label\":\"留"
        val cards = CardFence.toCards(FENCE_CARDJSON, body)
        assertEquals("已完整那行应立刻出卡，末尾半行跳过", 1, cards.size)
        assertEquals("日活", (cards[0] as QuroChatCard.StatCard).label)
    }

    @Test
    fun `单行脏数据不连累其余各行`() {
        // 这正是逐行相对整段的核心收益：一行坏 ≠ 整卡全废
        val body = """
            {"type":"stat","label":"好1","value":"1"}
            这行是散文，不是 JSON
            {"type":"stat","label":"好2","value":"2"}
            [1,2,3]
            {"type":"stat","label":"好3","value":"3"}
        """.trimIndent()
        val cards = CardFence.toCards(FENCE_CARDJSON, body)
        assertEquals(3, cards.size)
        assertEquals(listOf("好1", "好2", "好3"), cards.map { (it as QuroChatCard.StatCard).label })
    }

    @Test
    fun `空行与缩进被容忍`() {
        val body = "\n\n   {\"type\":\"stat\",\"label\":\"缩进\",\"value\":\"1\"}   \n\n"
        assertEquals(1, CardFence.toCards(FENCE_CARDJSON, body).size)
    }

    @Test
    fun `未知组件名逐行也不丢`() {
        val body = "{\"type\":\"AwesomeBox\",\"x\":1}"
        val cards = CardFence.toCards(FENCE_CARDJSON, body)
        assertEquals(1, cards.size)
        // 未知组件名不应被丢弃：落 CustomCard 并保留组件名（小写，与其余围栏一致），
        // 便于用户反馈时直接看出 AI 到底输出了什么
        assertEquals("awesomebox", (cards[0] as CustomCard).kind)
        assertTrue("原始 JSON 应保留在 payload 里", (cards[0] as CustomCard).payload.contains("AwesomeBox"))
    }

    @Test
    fun `isStreaming 标记只对 cardjson 为真`() {
        val json = "{\"type\":\"stat\",\"label\":\"a\",\"value\":\"1\"}"
        assertTrue(CardFence.parse("```cardjson\n$json\n```")[0].isStreaming)
        assertFalse(CardFence.parse("```card\n$json\n```")[0].isStreaming)
        assertFalse(CardFence.parse("```cards\n[$json]\n```")[0].isStreaming)
    }

    @Test
    fun `extract 端到端能取出逐行卡片`() {
        val text = "看数据：\n```cardjson\n{\"type\":\"stat\",\"label\":\"a\",\"value\":\"1\"}\n```\n还要别的吗？"
        val out = CardFence.extract(text)
        assertEquals(1, out.size)
        assertEquals(1, out[0].second.size)
    }

    @Test
    fun `逐行围栏不跨行组合卡但单行组合卡可用`() {
        // 单行写完的 composite 仍应生效（只是跨行的写不了）
        val body = """{"type":"composite","layout":"stack","children":[{"type":"stat","label":"a","value":"1"}]}"""
        assertEquals(1, CardFence.toCards(FENCE_CARDJSON, body).size)
    }

    @Test
    fun `空围栏体返回空列表`() {
        assertTrue(CardFence.toCards(FENCE_CARDJSON, "").isEmpty())
        assertTrue(CardFence.toCards(FENCE_CARDJSON, "   \n  ").isEmpty())
    }
}