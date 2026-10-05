package com.ai.assistance.quro.core.cards

import com.ai.assistance.quro.core.cards.CardFence.FENCE_CARD
import com.ai.assistance.quro.core.cards.CardFence.FENCE_CARDS
import com.ai.assistance.quro.core.cards.CardFence.FENCE_CARDJSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 围栏属性（`title=` / `theme=` / `compact`）的自检。
 *
 * 这批属性的动因来自两个真实缺陷，都必须有测试守着，否则改回去不会被发现：
 *
 *  1. **带属性围栏整卡消失**：`ChatScreen` 的围栏正则 `^```([\w+#-]*)` 只吃非空白字符，
 *     于是 `card compact title=X` 里 `lang` 只匹配到 `card`，
 *     `" compact title=X\n{...}"` 整段被当成**围栏体** → body 不是合法 JSON → 一张卡都不出。
 *     症状是「卡片凭空消失」，看起来像解析器坏了，实际是属性没人接。
 *  2. **带值属性混进开关集合**：`parseAttrs` 若不剔除 `key=value`，
 *     `title=季度报表` 会变成一个谁也匹配不上的假开关，而真 title 无人读取。
 */
class CardFenceAttrsTest {

    // ── parseValueAttrs：两种写法都吃 ──

    @Test
    fun `等号与冒号两种带值写法都能解析`() {
        assertEquals("季度报表", CardFence.parseValueAttrs("title=季度报表")["title"])
        assertEquals("季度报表", CardFence.parseValueAttrs("title:季度报表")["title"])
    }

    @Test
    fun `值带的包裹引号会被剥掉`() {
        // 模型照 JSON 习惯写 theme="danger" 很常见，不剥引号就匹配不上档位
        assertEquals("danger", CardFence.parseValueAttrs("""theme="danger"""")["theme"])
        assertEquals("danger", CardFence.parseValueAttrs("theme='danger'")["theme"])
    }

    @Test
    fun `未识别的带值属性直接丢弃`() {
        // 宁可主题不生效，也不要让模型写 theme=rainbow 时得到一张不明不白的卡
        assertTrue(CardFence.parseValueAttrs("rainbow=1 color=#fff").isEmpty())
    }

    @Test
    fun `冒号写法不能误吃 https 之类内容`() {
        // 切分点取「首个 = 或 :」，值里再出现分隔符不应二次切分
        assertEquals("a:b", CardFence.parseValueAttrs("title=a:b")["title"])
    }

    // ── parseAttrs：带值属性绝不能混进开关集合 ──

    @Test
    fun `带值属性不进开关集合`() {
        val attrs = CardFence.parseAttrs("compact scroll title=季度 theme=danger")
        assertTrue("compact 应是开关", attrs.contains("compact"))
        assertTrue("scroll 应是开关", attrs.contains("scroll"))
        assertFalse("开关集合里不该出现 title=…", attrs.any { it.startsWith("title") })
        assertFalse("开关集合里不该出现 theme=…", attrs.any { it.startsWith("theme") })
        assertEquals(2, attrs.size)
    }

    @Test
    fun `纯带值属性时开关集合为空`() {
        assertTrue(CardFence.parseAttrs("title=x theme=y").isEmpty())
    }

    // ── Slice：theme 白名单化 ──

    private fun sliceOf(header: String): CardFence.Slice =
        CardFence.parse("```$header\n{\"type\":\"stat\",\"label\":\"用户\",\"value\":\"1.2k\"}\n```").single()

    @Test
    fun `Slice 能取到 title 与 theme`() {
        val s = sliceOf("card title=月度数据 theme=warn compact")
        assertEquals("月度数据", s.title)
        assertEquals("warn", s.theme)
        assertTrue(s.compact)
    }

    @Test
    fun `theme 不在白名单内降级为 accent 且原值仍可查`() {
        // 未知值若原样传下去，渲染层要么找不到色（整张卡无色）要么按未知分支硬套默认，
        // 看起来像生效了其实不是。降级 accent 至少视觉稳定，themeRaw 留着供排查。
        val s = sliceOf("card theme=rainbow")
        assertEquals("accent", s.theme)
        assertEquals("rainbow", s.themeRaw)
    }

    @Test
    fun `没写 theme 时默认为 accent`() {
        assertEquals("accent", sliceOf("card").theme)
        assertEquals("", sliceOf("card").themeRaw)
    }

    @Test
    fun `没写 title 时为空串`() {
        assertEquals("", sliceOf("card compact").title)
    }

    // ── 🔴 回归：带属性围栏必须仍能出卡 ──

    @Test
    fun `带属性的单卡围栏仍出一张卡`() {
        // 这条守的是 ChatScreen 那个正则缺陷的**后果**：
        // 属性一旦被并进 body，body 就不是合法 JSON，parse 直接返回空列表。
        val s = sliceOf("card title=月度数据 theme=accent compact")
        val cards = CardFence.toCards(s.fence, s.body)
        assertEquals("带属性不应让卡片消失", 1, cards.size)
    }

    @Test
    fun `带属性的多卡围栏仍出多张卡`() {
        val s = CardFence.parse(
            """```cards title=对比 theme=danger
            [{"type":"stat","label":"A","value":"1"},{"type":"stat","label":"B","value":"2"}]
            ```"""
        ).single()
        assertEquals(2, CardFence.toCards(s.fence, s.body).size)
        assertEquals("对比", s.title)
        assertEquals("danger", s.theme)
    }

    @Test
    fun `带属性的逐行围栏每行独立出卡`() {
        val s = CardFence.parse(
            """```cardjson title=日志 theme=plain
            {"type":"stat","label":"A","value":"1"}
            {"type":"stat","label":"B","value":"2"}
            ```"""
        ).single()
        assertEquals(2, CardFence.toCards(s.fence, s.body).size)
        assertEquals("plain", s.theme)
    }

    @Test
    fun `四种围栏都吃 title 属性`() {
        // 围栏属性语法对四种围栏必须一致，否则模型换个围栏就静默失效
        val bodies = mapOf(
            FENCE_CARD to """{"type":"stat","label":"用户","value":"1"}""",
            FENCE_CARDS to """[{"type":"stat","label":"用户","value":"1"}]""",
            FENCE_CARDJSON to """{"type":"stat","label":"用户","value":"1"}""",
            "cardui" to """{"root":"a","components":{"a":{"component":"Text"}}}""",
        )
        bodies.forEach { (fence, body) ->
            val s = CardFence.parse("```$fence title=统一标题\n$body\n```").single()
            assertEquals("$fence 应取到 title", "统一标题", s.title)
            assertTrue("$fence 应仍能出卡", CardFence.toCards(s.fence, s.body).isNotEmpty())
        }
    }

    // ── Grouped：组级属性的载体 ──

    @Test
    fun `theme 白名单常量与实现一致`() {
        // 渲染层用 THEME_PRESETS 校验，这里锁住四档不被随手改
        assertEquals(listOf("accent", "warn", "danger", "plain"), CardFence.THEME_PRESETS)
    }
}