package com.ai.assistance.mnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死 [MnnThinkContent.split] 的切分口径。
 *
 * 背景：用户反馈「部分离线模型有工具调用和思考但是不能用」。其中「思考」这一半的病灶是
 * Qwen3 / DeepSeek-R1 系模型把推理过程原样吐在正文里，如果不切：
 * 1. 界面上是一大段自言自语；
 * 2. 思考段里**示例性质**的 `<tool_call>` 会被工具解析器当成真实调用（最恶劣的一种）；
 * 3. 思考段计入复读检测，误判退化。
 *
 * 所以这里把每种真实输出形态都钉死，避免后续重构悄悄改变行为。
 */
class MnnThinkContentTest {

    /** 没有 think 标签时必须原样透传，不能顺手 trim 掉正文里的有效空白。 */
    @Test
    fun `plain text is returned untouched`() {
        val raw = "北京今天晴，气温 22℃。"
        val split = MnnThinkContent.split(raw)

        assertEquals(raw, split.answer)
        assertEquals("", split.reasoning)
        assertFalse(split.hasReasoning)
        assertFalse(split.answerFromReasoning)
    }

    /** 空输入不能抛异常（原生返回空串时上层会直接调用）。 */
    @Test
    fun `empty input yields empty split`() {
        val split = MnnThinkContent.split("")

        assertEquals("", split.answer)
        assertEquals("", split.reasoning)
        assertFalse(split.hasReasoning)
    }

    /** 标准成对标签：思考进 reasoning，正文进 answer。 */
    @Test
    fun `paired think tags are separated`() {
        val raw = "<think>用户问天气，需要调用工具。</think>北京今天晴。"
        val split = MnnThinkContent.split(raw)

        assertEquals("北京今天晴。", split.answer)
        assertEquals("用户问天气，需要调用工具。", split.reasoning)
        assertTrue(split.hasReasoning)
        assertFalse(split.answerFromReasoning)
    }

    /**
     * 关键回归：思考段里出现示例性 `<tool_call>` 时，必须留在 reasoning 里，
     * 不能漏进 answer —— 否则 [QuroLocalToolsCodec] 会把"模型的自言自语"当成真实工具调用执行。
     */
    @Test
    fun `example tool call inside think block does not leak into answer`() {
        val raw = """
            <think>我可以这样调用：<tool_call>{"name":"get_time","arguments":{}}</tool_call>，先确认一下。</think>
            现在是下午三点。
        """.trimIndent()
        val split = MnnThinkContent.split(raw)

        assertEquals("现在是下午三点。", split.answer)
        assertFalse("示例 tool_call 泄漏进正文", split.answer.contains("<tool_call>"))
        assertTrue(split.reasoning.contains("<tool_call>"))
    }

    /** 多段思考：按出现顺序拼接，段间空行分隔；正文同样按顺序拼接。 */
    @Test
    fun `multiple think blocks are concatenated`() {
        val raw = "<think>第一步。</think>好的，<think>第二步。</think>完成了。"
        val split = MnnThinkContent.split(raw)

        assertEquals("好的，完成了。", split.answer)
        assertEquals("第一步。\n\n第二步。", split.reasoning)
    }

    /** 生成被 max_tokens 截断导致缺少闭合标签：开标签之后全算思考内容。 */
    @Test
    fun `unclosed think tag treats remainder as reasoning`() {
        val raw = "先说一句。<think>我还在想这个问题，突然被截断"
        val split = MnnThinkContent.split(raw)

        assertEquals("先说一句。", split.answer)
        assertEquals("我还在想这个问题，突然被截断", split.reasoning)
    }

    /**
     * 模型只吐了思考段（max_tokens 太小的典型形态）：
     * 正文回退为思考内容，避免界面出现空气泡，并置 [MnnThinkContent.Split.answerFromReasoning]。
     */
    @Test
    fun `think only output falls back to reasoning as answer`() {
        val raw = "<think>让我仔细想想这道题的解法……</think>"
        val split = MnnThinkContent.split(raw)

        assertEquals("让我仔细想想这道题的解法……", split.answer)
        assertEquals("让我仔细想想这道题的解法……", split.reasoning)
        assertTrue(split.answerFromReasoning)
    }

    /** 空思考段（`<think></think>`，Qwen3 关闭思考时的产物）不应产生空 reasoning 噪声。 */
    @Test
    fun `empty think block is ignored`() {
        val raw = "<think></think>直接回答。"
        val split = MnnThinkContent.split(raw)

        assertEquals("直接回答。", split.answer)
        assertEquals("", split.reasoning)
        assertFalse(split.hasReasoning)
    }

    /** 真实的 `<tool_call>` 在思考段之外时必须完整保留，供下游解析器识别。 */
    @Test
    fun `real tool call after think block is preserved in answer`() {
        val raw = "<think>该查时间了。</think><tool_call>{\"name\":\"get_time\",\"arguments\":{}}</tool_call>"
        val split = MnnThinkContent.split(raw)

        assertEquals("<tool_call>{\"name\":\"get_time\",\"arguments\":{}}</tool_call>", split.answer)
        assertEquals("该查时间了。", split.reasoning)
        assertFalse(split.answerFromReasoning)
    }
}
