package com.ai.assistance.quro.genui.aiapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 渲染类型（[RenderChannel]）回归测试。
 *
 * 为什么单独测这一层：历史记录里的「渲染类型」决定了回放走哪条解析管线。
 * A2UI 的扁平 JSON 和 GenUI 的 DSL 都是 JSON，一旦认错，这条回放就废
 * （拿 GenUI 解析器啃 A2UI 表 → 画布降级成错误卡）。
 * 这里锁死三件事：
 *   ① 询问弹窗的选项文案能被 [RenderChannel.parse] 原样反解回通道（问与答闭环）
 *   ② 用户话术里的点名认得出，且「别用 genui，改用 a2ui」不会反过来
 *   ③ 老数据（没存 renderType）能按 payload 形态推断出来，且显式值永远优先于推断
 */
class RenderChannelTest {

    // ── ① 弹窗选项 ↔ 通道 往返一致 ─────────────────────────────

    @Test
    fun `每条弹窗选项文案都能反解回自己的通道`() {
        RenderChannel.values().forEach { ch ->
            assertEquals(
                "选项「${ch.option}」反解成了别的通道，用户点它就会走错管线",
                ch, RenderChannel.parse(ch.option)
            )
        }
    }

    @Test
    fun `光靠展示名也能反解`() {
        RenderChannel.values().forEach { ch ->
            assertEquals(ch, RenderChannel.parse(ch.label))
        }
    }

    // ── ② 用户话术点名 ────────────────────────────────────────

    @Test
    fun `用户话里点名通道要认得出`() {
        assertEquals(RenderChannel.A2UI, RenderChannel.parse("用 a2ui 写个表格"))
        assertEquals(RenderChannel.MARKDOWN, RenderChannel.parse("用 markdown 写篇攻略"))
        assertEquals(RenderChannel.HTML, RenderChannel.parse("用 html 做个贪吃蛇"))
        assertEquals(RenderChannel.GENUI, RenderChannel.parse("用 GenUI SDK 画个计数器"))
    }

    @Test
    fun `否定 genui 改选 a2ui 时不能认成 genui`() {
        // 「别用 genui，改用 a2ui」里两个词都在，谁先判断谁赢。
        // a2ui 必须先判，否则用户明确要 a2ui 却被锁进 GenUI 通道。
        assertEquals(
            RenderChannel.A2UI,
            RenderChannel.parse("别用 genui，改用 a2ui 写")
        )
    }

    @Test
    fun `认不出的自由文本返回 null 而不是瞎猜`() {
        assertNull(RenderChannel.parse("帮我画个界面"))
        assertNull(RenderChannel.parse(""))
        assertNull(RenderChannel.parse(null))
    }

    // ── ③ 老数据兼容推断 ──────────────────────────────────────

    @Test
    fun `按围栏头推断老数据的渲染类型`() {
        assertEquals(RenderChannel.GENUI, RenderChannel.infer("```genui\n{\"root\":{}}\n```"))
        assertEquals(RenderChannel.A2UI, RenderChannel.infer("```a2ui\n[{\"id\":\"root\"}]\n```"))
        assertEquals(RenderChannel.MARKDOWN, RenderChannel.infer("```markdown\n# 标题\n```"))
        assertEquals(RenderChannel.HTML, RenderChannel.infer("```html\n<html></html>\n```"))
    }

    @Test
    fun `裸 JSON 靠关键键区分 GenUI 与 A2UI`() {
        // GenUI DSL 的特征键是 properties / root；A2UI 扁平邻接表没有
        assertEquals(RenderChannel.GENUI, RenderChannel.infer("{\"root\":{\"type\":\"card\"}}"))
        assertEquals(RenderChannel.A2UI, RenderChannel.infer("{\"id\":\"root\",\"type\":\"column\"}"))
    }

    @Test
    fun `空 payload 兜底到默认通道而不是崩`() {
        assertEquals(RenderChannel.DEFAULT, RenderChannel.infer(""))
        assertEquals(RenderChannel.DEFAULT, RenderChannel.infer(null))
    }

    // ── ④ of WorkItem：显式值永远优先于推断 ───────────────────

    @Test
    fun `WorkItem 的通道优先取显式 renderType`() {
        // 关键回归：内容看着像 markdown（以 # 开头），但落库时明确记了 genui。
        // 若实现写成「先推断再看字段」，这条回放就会走 markdown 管线，界面变文章。
        val w = GenUISessionStore.WorkItem(
            title = "t", request = "r", json = "# 这其实是 GenUI 生成的界面",
            time = 0L, renderType = RenderChannel.GENUI.key
        )
        assertEquals(RenderChannel.GENUI, w.channel)
    }

    @Test
    fun `老数据没 renderType 时按 payload 推断`() {
        val w = GenUISessionStore.WorkItem(
            title = "t", request = "r",
            json = "```a2ui\n{\"id\":\"root\"}\n```",
            time = 0L
        )
        assertEquals("老数据应自动补出 a2ui", RenderChannel.A2UI, w.channel)
    }

    @Test
    fun `四个通道的 key 稳定且唯一`() {
        val keys = RenderChannel.values().map { it.key }
        assertEquals("key 数量应等于通道数", RenderChannel.values().size, keys.toSet().size)
        // key 是落库字段，改了老数据就认不出来 → 这里把现值钉死
        assertEquals(listOf("genui", "a2ui", "markdown", "html"), keys)
    }

    @Test
    fun `每个通道都有展示名和说明，弹窗不会出现空选项`() {
        RenderChannel.values().forEach { ch ->
            assertNotNull(ch.label)
            assert(ch.label.isNotBlank()) { "${ch.key} 缺展示名" }
            assert(ch.desc.isNotBlank()) { "${ch.key} 缺说明" }
            assertNotNull(RenderChannel.fromKey(ch.key))
        }
    }
}
