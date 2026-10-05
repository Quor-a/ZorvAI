package com.ai.assistance.quro.core.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 围栏层第二批：组件名映射、递归组合卡、两阶段建树。
 *
 * 这些行为都属于「错了不报错、只是卡片莫名少一块」，所以必须靠测试钉住。
 */
class CardFenceEx2Test {

    // ───────────── 组件名映射 ─────────────

    @Test
    fun `A2UI 常见组件名能落到名册 type`() {
        val cases = mapOf(
            "Text" to "info", "Button" to "button", "Column" to "composite",
            "GanttChart" to "gantt", "Stopwatch" to "stopwatch", "ColorPalette" to "palette",
            "HabitTrack" to "tracker", "Score" to "scoreboard", "WordCard" to "vocab",
            "Equation" to "formula", "Translation" to "translate", "Invoice" to "invoice",
            "Receipt" to "invoice", "Clock" to "clock", "Swiper" to "carousel",
            "GridView" to "groupedlist", "CandlestickChart" to "candlestick",
        )
        cases.forEach { (name, want) ->
            assertEquals("$name 应映射到 $want", want, CardFence.normalizeType(name))
        }
    }

    @Test
    fun `别名表按小写也能命中`() {
        // 别名表按小写建了索引，所以大小写、以及全小写连写都能命中
        assertEquals(CardFence.normalizeType("Text"), CardFence.normalizeType("TEXT"))
        assertEquals("gantt", CardFence.normalizeType("gantt"))
        // 小写索引建起来后，连 ganttchart 这种全小写连写也认（GanttChart 的键小写化后命中）
        assertEquals("gantt", CardFence.normalizeType("ganttchart"))
    }

    @Test
    fun `映射到名册之外的名字不再伪造`() {
        // 旧实现会把 "AwesomeBox" 蛇形化成 "awesome_box" 交出去，下游查不到又落 CustomCard，
        // 白绕一圈还把「这组件我们没做」藏起来了。现在直接落 custom，原名由 CustomCard 保留。
        assertEquals("custom", CardFence.normalizeType("AwesomeBox"))
        assertFalse(CardFence.isKnownType("AwesomeBox"))
        assertTrue(CardFence.isKnownType("GanttChart"))
    }

    @Test
    fun `pascal 转 snake 的连续大写不吃错`() {
        // 旧实现把替换串写成 "$1_$2"，Kotlin 模板把下划线吞了：QRCode → qrcode。
        // 直接单测这条纯函数，映射层再出问题就能一眼看出是它还是表的问题。
        assertEquals("text_input", CardFence.pascalToSnake("TextInput"))
        assertEquals("qr_code", CardFence.pascalToSnake("QRCode"))
        assertEquals("html_preview", CardFence.pascalToSnake("HTMLPreview"))
        assertEquals("gantt", CardFence.pascalToSnake("Gantt"))
    }

    // ───────────── cards 递归组合 ─────────────

    @Test
    fun `cards 支持嵌套组合卡`() {
        val body = """{"layout":"stack","title":"外层","children":[
            {"layout":"stack","title":"内层","children":[{"type":"stat","label":"a","value":"1"}]},
            {"type":"ring","label":"b","value":2}
        ]}"""
        val cards = CardFence.toCards(CardFence.FENCE_CARDS, body)
        assertEquals(1, cards.size)
        val outer = cards[0] as QuroChatCard.CompositeCard
        assertEquals(2, outer.children.size)

        val inner = outer.children[0] as QuroChatCard.CompositeCard
        assertEquals("内层", inner.title)
        assertEquals(1, inner.children.size)
        assertTrue("最里层应是 stat 卡", inner.children[0] is QuroChatCard.StatCard)
        assertTrue("第二项应是 ring 卡", outer.children[1] is RingCard)
    }

    @Test
    fun `数组套数组也递归`() {
        val body = """[{"children":[{"children":[{"type":"stat","label":"深","value":"1"}]}]}]"""
        val cards = CardFence.toCards(CardFence.FENCE_CARDS, body)
        var depth = 0
        var c: QuroChatCard? = cards.firstOrNull()
        while (c is QuroChatCard.CompositeCard) {
            depth++
            c = c.children.firstOrNull()
        }
        assertTrue("应解出至少两层组合卡，实际 $depth 层", depth >= 2)
        assertTrue(c is QuroChatCard.StatCard)
    }

    @Test
    fun `名册认得的 type 优先于 children 拆分`() {
        // kanban 的 children 是它自己的列语义，不能被当成组合容器拆开
        val body = """{"type":"ring","label":"别拆我","value":1,"children":[{"type":"stat","label":"x","value":"1"}]}"""
        val cards = CardFence.toCards(CardFence.FENCE_CARDS, body)
        assertEquals(1, cards.size)
        assertTrue("ring 卡不该被 children 拆成组合卡", cards[0] is RingCard)
    }

    @Test
    fun `嵌套超过深度上限就收住不报错`() {
        var s = """{"type":"ring","label":"底","value":1}"""
        repeat(12) { s = """{"layout":"stack","children":[$s]}""" }
        val cards = CardFence.toCards(CardFence.FENCE_CARDS, s)
        assertNotNull(cards)
        var depth = 0
        var c: QuroChatCard? = cards.firstOrNull()
        while (c is QuroChatCard.CompositeCard) {
            depth++
            c = c.children.firstOrNull()
        }
        assertTrue("深度应被 MAX_COMPOSITE_DEPTH 兜住，实际 $depth", depth <= CardFence.MAX_COMPOSITE_DEPTH + 1)
    }

    // ───────────── cardui 两阶段建树 ─────────────

    @Test
    fun `共享子节点不会被吞掉`() {
        // a、b 都引用 c：旧实现用单一 visiting 集合，b 那边的 c 会被判成"在递归中"直接丢，
        // 表现是一棵树上凭空少一整根枝。
        val body = """{"root":{"component":"Column","children":["a","b"]},
                       "a":{"component":"Column","children":["c"]},
                       "b":{"component":"Column","children":["c"]},
                       "c":{"component":"Text","text":"共享"}}"""
        val root = CardFence.toCards(CardFence.FENCE_CARDUI, body).firstOrNull() as? QuroChatCard.CompositeCard
        assertNotNull("root 应是组合卡", root)
        val a = root!!.children[0] as QuroChatCard.CompositeCard
        val b = root.children[1] as QuroChatCard.CompositeCard
        assertEquals("a 的子节点丢了", 1, a.children.size)
        assertEquals("b 的子节点被旧逻辑吞了", 1, b.children.size)
        assertTrue(b.children[0] is QuroChatCard.InfoCard)
    }

    @Test
    fun `自引用环不爆栈`() {
        val body = """{"a":{"component":"Column","children":["a","b"]},"b":{"component":"Text","text":"x"}}"""
        val cards = CardFence.toCards(CardFence.FENCE_CARDUI, body)
        assertNotNull("有环也必须给出卡，而不是崩", cards)
    }

    @Test
    fun `映射不到的组件名保留原名进兜底卡`() {
        val body = """{"root":{"component":"AwesomeBox","title":"怪组件"}}"""
        val root = CardFence.toCards(CardFence.FENCE_CARDUI, body).firstOrNull()
        assertTrue("认不出来的组件应落兜底卡", root is CustomCard)
        assertEquals("AwesomeBox", (root as CustomCard).kind)
    }

    @Test
    fun `认不出来的容器仍然当组合卡拆开`() {
        // 名字不认识但带了 children：这些子卡显然还是子卡，组一层组合卡比丢一堆孤儿子节点有用
        val body = """{"root":{"component":"WhateverBox","children":["a"]},"a":{"component":"Text","text":"hi"}}"""
        val root = CardFence.toCards(CardFence.FENCE_CARDUI, body).firstOrNull()
        assertTrue("应合成为组合卡", root is QuroChatCard.CompositeCard)
        assertEquals(1, (root as QuroChatCard.CompositeCard).children.size)
    }

    @Test
    fun `围栏常量与上限是公开契约`() {
        // 4 种围栏：card / cards / cardui / cardjson（逐行流式）
        assertEquals(4, CardFence.ALL_FENCES.size)
        listOf(
            CardFence.FENCE_CARD, CardFence.FENCE_CARDS,
            CardFence.FENCE_CARDUI, CardFence.FENCE_CARDJSON,
        ).forEach { assertTrue("围栏 $it 应在受支持列表里", it in CardFence.ALL_FENCES) }
        assertTrue(CardFence.MAX_COMPOSITE_DEPTH > 0)
        assertTrue(CardFence.MAX_TOTAL_NODES > 0)
    }
}
