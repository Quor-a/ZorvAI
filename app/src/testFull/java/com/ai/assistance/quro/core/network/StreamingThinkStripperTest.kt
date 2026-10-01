package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死 [StreamingThinkStripper] 的三个视图语义（rawText / visible / thinking）。
 *
 * 为什么值得单独一个测试文件：
 * 它是"思考段剥离"的**唯一**实现，而 native 侧 L4 分流器已把思考段拆成
 * **独立通道**（onThinking）上行 —— 于是"思考段还会不会进剥离器"变成一个
 * 很容易被改坏、却**不会报错**的点。改坏后的表现是：
 *   ① 终态「思考段内 <tool_call> 恢复」永久失效 → 工具再也不会被调用；
 *   ② 模型只吐思考不吐正文时被误判成"无输出" → 白白走降级重试。
 * 两者都是静默失败：不崩、无异常、日志正常。只能靠这里守住。
 */
class StreamingThinkStripperTest {

    // ─────────────────────────── 基本三视图语义 ───────────────────────────

    /** raw 保留全文，visible 剔除思考段，thinking 只留段内内容。 */
    @Test
    fun `splits tagged thinking from visible`() {
        val s = StreamingThinkStripper()

        val visible = s.accept("<think>我要查天气</think>好的，稍等。")

        assertEquals("好的，稍等。", visible)
        assertEquals("我要查天气", s.thinkingText())
        assertEquals("<think>我要查天气</think>好的，稍等。", s.rawText())
    }

    /** 没有思考段时三个视图应完全一致（普通模型不能被误伤）。 */
    @Test
    fun `plain text passes through untouched`() {
        val s = StreamingThinkStripper()

        val visible = s.accept("直接就是正文。")

        assertEquals("直接就是正文。", visible)
        assertEquals("", s.thinkingText())
        assertEquals("直接就是正文。", s.rawText())
    }

    // ───────────────────────── 🔴 N3 回归防线 ─────────────────────────

    /**
     * native 分流后的思考段是**不带标签**的独立通道；驱动层把它贴回
     * `<think>…</think>` 再喂进来，rawText() 必须恢复成完整原文。
     *
     * 这条断言直接对应驱动层终态的
     * `QuroLocalToolsCodec.parseDetailed(stripper.rawText())` ——
     * 思考段内的工具调用就是靠它才能被找回来的。
     */
    @Test
    fun `re-tagged native thinking restores full raw text`() {
        val s = StreamingThinkStripper()

        // ① native onThinking 上行的思考段（驱动层贴回标签后喂入）
        s.accept("<think>" + "用户要查天气，我得调 get_weather" + "</think>")
        // ② native onToken 上行的正文
        val visible = s.accept("好的，我来查。")

        assertEquals("好的，我来查。", visible)
        // 关键：完整原文里思考段**还在**，且顺序与模型真实输出一致
        assertEquals(
            "<think>用户要查天气，我得调 get_weather</think>好的，我来查。",
            s.rawText()
        )
        // 工具调用就藏在思考段里 —— 下游解析器必须看得见
        assertTrue(s.rawText().contains("get_weather"))
        // 同时思考通道也拿到了内容（流式 UI 用）
        assertEquals("用户要查天气，我得调 get_weather", s.thinkingText())
    }

    /**
     * 思考段与正文**交替**出现时（模型边想边说），rawText() 仍是完整原文。
     * 这是最容易出错的一种：不能因为"已经出过正文"就把后续思考段丢掉。
     */
    @Test
    fun `interleaved thinking and visible keep full raw text`() {
        val s = StreamingThinkStripper()

        s.accept("<think>" + "先查天气" + "</think>")
        s.accept("好的。")
        s.accept("<think>" + "再查时间" + "</think>")
        s.accept("马上就好。")

        assertEquals("好的。马上就好。", s.accept(""))
        assertEquals("先查天气再查时间", s.thinkingText())
        assertEquals(
            "<think>先查天气</think>好的。<think>再查时间</think>马上就好。",
            s.rawText()
        )
    }

    /** 只有思考、没有正文时，rawText() **不能为空** —— 上层据此判断"确实产出过内容"。 */
    @Test
    fun `thinking-only output keeps raw text non-empty`() {
        val s = StreamingThinkStripper()

        s.accept("<think>" + "我在推理，但还没想好怎么说。" + "</think>")

        assertTrue("只有思考段的输出不能被当成空输出", s.rawText().isNotEmpty())
        assertEquals("我在推理，但还没想好怎么说。", s.thinkingText())
    }

    // ─────────────────────────── 边界与健壮性 ───────────────────────────

    /**
     * 标签被 chunk 边界劈开时不得泄漏。
     *
     * token 边界不可控（`<thi` + `nk>world` 是常态），这是唯一能证明
     * 增量扫描状态机真的健壮、而不是"测试串刚好没被切开"的测法。
     */
    @Test
    fun `does not leak tags split across chunks`() {
        val s = StreamingThinkStripper()

        var visible = ""
        for (chunk in listOf("<thi", "nk>思考", "内容</thi", "nk>正文")) {
            visible = s.accept(chunk)
        }

        assertEquals("正文", visible)
        assertEquals("思考内容", s.thinkingText())
        assertEquals("<think>思考内容</think>正文", s.rawText())
    }

    /** 逐字符喂入（最坏的切分）也必须与整串喂入结果完全一致。 */
    @Test
    fun `char by char feeding matches whole string feeding`() {
        val text = "<think>推理</think>答案"

        val whole = StreamingThinkStripper().apply { accept(text) }
        val split = StreamingThinkStripper()
        for (ch in text) split.accept(ch.toString())

        assertEquals(whole.rawText(), split.rawText())
        assertEquals(whole.thinkingText(), split.thinkingText())
        assertEquals(whole.accept(""), split.accept(""))
    }

    /** 全角标签也要认（模型偶发吐 ＜think＞／＜／think＞，不认就会实时泄漏思考原文）。 */
    @Test
    fun `normalizes fullwidth tags`() {
        val s = StreamingThinkStripper()

        val visible = s.accept("＜think＞思考＜／think＞正文")

        assertEquals("正文", visible)
        assertEquals("思考", s.thinkingText())
    }

    /** 未闭合的思考段：内容归 thinking，且不得把标签残片漏到 visible。 */
    @Test
    fun `unclosed thinking does not leak into visible`() {
        val s = StreamingThinkStripper()

        val visible = s.accept("<think>还没想完")

        assertEquals("", visible)
        assertEquals("还没想完", s.thinkingText())
    }

    /** reset 后三个视图全部清空（降级重试时必须，否则旧缓冲会污染新路径）。 */
    @Test
    fun `reset clears every view`() {
        val s = StreamingThinkStripper()
        s.accept("<think>a</think>b")

        s.reset()

        assertEquals("", s.rawText())
        assertEquals("", s.thinkingText())
        assertEquals("", s.accept(""))
    }

    /** 空 chunk 是合法输入（原生可能吐空串），不得改变任何状态。 */
    @Test
    fun `empty chunk is a no-op`() {
        val s = StreamingThinkStripper()
        s.accept("<think>x</think>y")

        val rawBefore = s.rawText()
        val visible = s.accept("")

        assertEquals(rawBefore, s.rawText())
        assertEquals("y", visible)
    }
}
