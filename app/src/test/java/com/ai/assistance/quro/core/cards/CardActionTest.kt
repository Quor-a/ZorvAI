package com.ai.assistance.quro.core.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片交互的结构化动作协议。
 *
 * 重点钉住两件事：
 * 1. **向后兼容** —— 历史上宿主只认裸串（`startsWith` 那套），
 *    `parse → toCommand` 必须零漂移，否则加了协议等于改行为；
 * 2. **渐进落地** —— 本地总线没人订阅时动作要能交回宿主，不能吞掉。
 */
class CardActionTest {

    private fun K(raw: String) = CardAction.parse(raw).kind
    private fun D(raw: String) = CardAction.parse(raw)

    // ───────────── 归族 ─────────────

    @Test
    fun `历史裸串全部归到正确的族`() {
        assertEquals(CardAction.Kind.REPLY, K("reply:你好"))
        assertEquals(CardAction.Kind.AI, K("ai:你好"))
        assertEquals(CardAction.Kind.UI, K("ui_open_terminal"))
        assertEquals(CardAction.Kind.UI, K("ui:open_terminal"))
        assertEquals(CardAction.Kind.RUN, K("run:ls -lh"))
        assertEquals(CardAction.Kind.OPEN, K("open:https://example.com"))
        assertEquals(CardAction.Kind.COPY, K("copy:一段文本"))
        assertEquals(CardAction.Kind.SCREEN, K("screen:main"))
        assertEquals(CardAction.Kind.INSTALL, K("linux:install"))
        assertEquals(CardAction.Kind.TOAST, K("toast:保存成功"))
        assertEquals(CardAction.Kind.NAVIGATE, K("navigate:settings"))
    }

    @Test
    fun `send 出来的串归到 emit 族`() {
        assertEquals(CardAction.Kind.EMIT, K("emit:poll:2"))
        // 🔴 event 与 data 必须切开：整个 tail 当 actionId 的话订阅永远命中不到
        assertEquals("poll", D("emit:poll:2").actionId)
        assertEquals("2", D("emit:poll:2").arg("data"))
        assertEquals("poll", D("emit:poll").actionId)
        assertFalse(D("emit:poll").has("data"))
    }

    @Test
    fun `认不出来的串归 UNKNOWN 但不丢原文`() {
        val a = D("__edit_soul_card__")
        assertEquals(CardAction.Kind.UNKNOWN, a.kind)
        assertEquals("__edit_soul_card__", a.actionId)
        assertEquals("__edit_soul_card__", a.toCommand())
    }

    @Test
    fun `空与 null 不炸`() {
        assertEquals(CardAction.Kind.UNKNOWN, K(""))
        assertEquals(CardAction.Kind.UNKNOWN, K("   "))
        assertEquals(CardAction.Kind.UNKNOWN, CardAction.parse(null).kind)
        assertEquals("", CardAction.parse(null).toCommand())
    }

    // ───────────── 往返零漂移（向后兼容的命脉） ─────────────

    @Test
    fun `宿主要认的裸串原样往返`() {
        val history = listOf(
            "reply:看看这份报告",
            "ai:帮我写个脚本",
            "ui_open_terminal",
            "ui_toggle_notify",
            "ui:open_terminal",
            "run:ls -lh",
            "open:https://example.com/a?b=1",
            "copy:一段要复制的文本",
            "screen:main",
            "linux:install",
            "__edit_soul_card__",
        )
        val drifted = history.filter { s ->
            val back = CardAction.parse(s).toCommand()
            back != s
        }
        assertEquals("往返漂移了：${drifted.joinToString()}", emptyList<String>(), drifted)
    }

    @Test
    fun `ui 前缀两种写法归一到同一个 target`() {
        assertEquals(D("ui:open_terminal").arg("target"), D("ui_open_terminal").arg("target"))
        assertEquals("ui_open_terminal", D("ui:open_terminal").arg("target"))
        // 归一后（无原文的规范化形态）必须还能被老宿主的 startsWith("ui_") 吃下。
        // 🔴 这里要用工厂动作来验证：裸串 parse 会保留原文，`ui:open_terminal` 原样返回，
        //    但要落到老 `cmd.startsWith("ui_")` 分支，走的是**归一化后**的那条。
        assertTrue(CardAction.ui("open_terminal").toCommand().startsWith("ui_"))
    }

    @Test
    fun `payload 按族取主参数`() {
        assertEquals("你好", CardAction.reply("你好").payload())
        assertEquals("ui_open_terminal", CardAction.ui("ui_open_terminal").payload())
        assertEquals("ls -lh", CardAction.run("ls -lh").payload())
        assertEquals("https://x", CardAction.open("https://x").payload())
        assertEquals("文本", CardAction.copy("文本").payload())
        assertEquals("", CardAction.install().payload())
    }

    // ───────────── JSON 信封（AI 下发结构化动作） ─────────────

    @Test
    fun `JSON 信封能解析且 args 展开成参数`() {
        val a = CardAction.parse(
            """{"actionId":"ui:open_terminal","kind":"ui","args":{"target":"terminal"}}"""
        )
        assertEquals("ui:open_terminal", a.actionId)
        assertEquals(CardAction.Kind.UI, a.kind)
        // 信封里明写了 args.target，信它；归一只发生在「拿它去跟老宿主对话」那一步
        assertEquals("terminal", a.arg("target"))
    }

    @Test
    fun `信封的参数平铺也能收`() {
        val a = CardAction.parse("""{"actionId":"emit:p1","kind":"emit","option":"2"}""")
        assertEquals(CardAction.Kind.EMIT, a.kind)
        assertEquals("2", a.arg("option"))
    }

    @Test
    fun `Envelope 往返：toCommand 仍能被老宿主吃下`() {
        val raw = """{"actionId":"ui:terminal","kind":"ui"}"""
        val a = CardAction.parse(raw)
        assertTrue(a.toCommand().startsWith("ui_"))
    }

    @Test
    fun `坏 JSON 不炸，落到 UNKNOWN`() {
        val a = CardAction.parse("{oops")
        assertEquals(CardAction.Kind.UNKNOWN, a.kind)
    }

    // ───────────── 本地总线（多卡联动 / 局部更新） ─────────────

    @Test
    fun `emit 命中订阅者时不到宿主`() {
        CardActionBus.clear()
        var hostCalls = 0
        var seen: String? = null
        val off = CardActionBus.subscribe("poll") { a ->
            seen = a.arg("data")
            true
        }
        try {
            var hostCalls = 0
        val hit = CardActionRouter.dispatch(CardAction.parse("emit:poll:2")) { hostCalls++; true }
            assertTrue("订阅者命中时不应再走宿主", hit)
            assertTrue(hostCalls == 0)
            assertEquals("2", seen)
        } finally {
            off()
        }
    }

    @Test
    fun `emit 没人订阅要交回宿主而不是吞掉`() {
        CardActionBus.clear()
        val seen = ArrayList<String>()
        CardActionRouter.dispatch(CardAction.parse("emit:nobody:1")) { a ->
            seen.add("${a.kind}/${a.actionId}")
            true
        }
        assertEquals(listOf("EMIT/nobody"), seen)
    }

    @Test
    fun `非 emit 动作直接给宿主`() {
        CardActionBus.clear()
        val seen = ArrayList<String>()
        val hit = CardActionRouter.dispatch(CardAction.parse("ui_open_terminal")) { a ->
            seen.add(a.toCommand())
            true
        }
        assertTrue(hit)
        assertEquals(listOf("ui_open_terminal"), seen)
    }

    @Test
    fun `宿主不认时退回裸串再兜一次`() {
        CardActionBus.clear()
        val calls = ArrayList<String>()
        CardActionRouter.dispatch(CardAction.parse("__edit_soul_card__")) { a ->
            calls.add(a.toCommand())
            false
        }
        assertEquals(2, calls.size)
        assertEquals("__edit_soul_card__", calls[0])
        assertEquals("__edit_soul_card__", calls[1])
    }

    @Test
    fun `订阅能退订`() {
        CardActionBus.clear()
        var n = 0
        // parse("emit:x") 的 actionId 是事件名 `x`（冒号后面是载荷），订阅要对齐它
        val off = CardActionBus.subscribe("x") { n++; true }
        assertTrue(CardActionBus.has("x"))
        CardActionBus.dispatch(CardAction.parse("emit:x"))
        off()
        assertFalse(CardActionBus.has("x"))
        CardActionBus.dispatch(CardAction.parse("emit:x"))
        assertEquals(1, n)
    }

    @Test
    fun `后注册的订阅者覆盖前者`() {
        CardActionBus.clear()
        var first = 0
        var second = 0
        val off1 = CardActionBus.subscribe("y") { first++; true }
        val off2 = CardActionBus.subscribe("y") { second++; true }
        try {
            CardActionBus.dispatch(CardAction.parse("emit:y"))
            assertEquals(0, first)
            assertEquals(1, second)
        } finally {
            off1()
            off2()
        }
    }

    @Test
    fun `订阅者抛异常不炸派发`() {
        CardActionBus.clear()
        val off = CardActionBus.subscribe("boom") { throw RuntimeException("boom") }
        try {
            assertFalse("订阅者炸了就当作没人接，交回宿主", CardActionBus.dispatch(CardAction.parse("emit:boom")))
        } finally {
            off()
        }
    }

    @Test
    fun `总线清干净避免测试互相污染`() {
        val off = CardActionBus.subscribe("z") { true }
        assertEquals(1, CardActionBus.size())
        off()
        assertEquals(0, CardActionBus.size())
    }

    @Test
    fun `subscribe 返回的是可直接塞 onDispose 的退订函数`() {
        CardActionBus.clear()
        var disposed = false
        val off = CardActionBus.subscribe("d") { true }
        assertEquals(listOf("d"), CardActionBus.ids())
        off()
        disposed = true
        assertTrue(disposed)
        assertFalse(CardActionBus.has("d"))
    }
}
