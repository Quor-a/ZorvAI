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
    fun `nodes 反引用格式必须判 A2UI 不能串到 GenUI`() {
        // 用户报的「乱串通道」实锤形状（真机截图2）：提示词教模型的 nodes 数组 +
        // parent 反引用，每个节点都带 properties 键 —— 旧判据见 properties 就认 GenUI，
        // 回放时 A2UI 作品被塞进 GenUI 管线，画布上是一屏源码。
        val nodesPayload = """
            {"nodes":[
              {"id":"root","type":"scroll","parent":null,
               "properties":{"direction":"vertical"},
               "style":{"padding":{"all":16},"backgroundColor":"background"}},
              {"id":"header","type":"header_bar","parent":"root",
               "properties":{"title":"北京"},"style":{"padding":{"all":16}}},
              {"id":"spacer1","type":"spacer","parent":"root","style":{"height":16}},
              {"id":"main_card","type":"card","parent":"root",
               "properties":{"title":"实时天气"},"style":{"padding":{"all":20},"cornerRadius":20,"backgroundColor":"surface"}},
              {"id":"temp","type":"text","parent":"main_card",
               "properties":{"text":"24°C 晴"},"style":{"textSize":28}}
            ]}
        """.trimIndent()
        assertEquals(RenderChannel.A2UI, RenderChannel.infer(nodesPayload))
        // 围栏包着的同形状也一样（a2ui 围栏头优先，双保险）
        assertEquals(RenderChannel.A2UI, RenderChannel.infer("```a2ui\n$nodesPayload\n```"))
        // 没有 nodes 键、只有 parent 反引用的变体也不能误伤
        assertEquals(
            RenderChannel.A2UI,
            RenderChannel.infer("""{"id":"root","type":"scroll","parent":null,"properties":{"direction":"vertical"}}""")
        )
    }

    @Test
    fun `A2UI 本家扁平表 root 是字符串值也不能串到 GenUI`() {
        // {"title":"今日状态","root":"col","components":{"col":{"t":"column",…}}}
        // root 是键但值是节点 id（反引用），与 GenUI 的 "root":{…} 只差一个字符。
        val ownFormat = """
            {"title":"今日状态","root":"col","components":{
              "col":{"t":"column","children":["h","d"],"padding":16},
              "h":{"t":"heading","text":"今日状态"},
              "d":{"t":"card","children":["t1"],"bg":"#FFF6F1EC","radius":16},
              "t1":{"t":"text","text":"生命 72 / 金币 120","size":15}}}
        """.trimIndent()
        assertEquals(RenderChannel.A2UI, RenderChannel.infer(ownFormat))
    }

    @Test
    fun `真 GenUI DSL 仍判 GenUI 不被 A2UI 判据误伤`() {
        // root 对象值（GenUI 主流形状）
        assertEquals(
            RenderChannel.GENUI,
            RenderChannel.infer("""{"root":{"type":"column","properties":{"spacing":8}},"components":[]}""")
        )
        // root 字符串值 + component 键（id 引用形状，cardui/GenUI 都用这个）
        assertEquals(
            RenderChannel.GENUI,
            RenderChannel.infer("""{"root":"root","components":{"root":{"component":"Column","children":["a"]},"a":{"component":"Text","text":"A"}}}""")
        )
        // json 围栏 + properties 特征
        assertEquals(RenderChannel.GENUI, RenderChannel.infer("```json\n{\"root\":{\"type\":\"card\"}}\n```"))
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
