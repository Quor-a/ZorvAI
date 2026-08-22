package com.ai.assistance.mnn

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 锁死 [MnnModelCapabilities] 的能力探测口径。
 *
 * 背景：用户反馈「**部分**离线模型有工具调用和思考但是不能用」——"部分"二字说明病根是
 * 能力标记与模型实际情况脱节。旧做法按模型名白名单猜能力，用户改个目录名就失灵。
 * 现在改成读 `llm_config.json` 的 `jinja.chat_template` 做特征探测，跟着模型走。
 *
 * 判定口径是**保守**的：宁可报"不支持"（走降级注入），也不误报"支持"（工具定义被模板吃掉，
 * 表现为开了工具调用却毫无反应）。本测试把这个口径钉死。
 */
class MnnModelCapabilitiesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 构造一个带 `jinja.chat_template` 的配置根对象。 */
    private fun configWithTemplate(template: String): JSONObject =
        JSONObject().put("jinja", JSONObject().put("chat_template", template))

    /** Qwen3 风格模板：同时具备 tools 循环、enable_thinking 开关与 `<think>` 段。 */
    private val qwen3Template = """
        {%- if tools %}
            {%- for tool in tools %}{{- tool | tojson }}{%- endfor %}
        {%- endif %}
        {%- if enable_thinking is defined and enable_thinking is false %}
            {{- '<think>\n\n</think>\n\n' }}
        {%- endif %}
    """.trimIndent()

    /** 目录里没有 llm_config.json：全 false，且 note 要说清原因。 */
    @Test
    fun `missing config yields all false with reason`() {
        val dir = tmp.newFolder("model-no-config")

        val caps = MnnModelCapabilities.probe(dir)

        assertFalse(caps.hasChatTemplate)
        assertFalse(caps.supportsTools)
        assertFalse(caps.supportsThinking)
        assertTrue(caps.note.contains("llm_config.json"))
    }

    /** JSON 损坏时不得抛异常，必须降级为全 false 并把原因写进 note。 */
    @Test
    fun `corrupted config is swallowed into note`() {
        val dir = tmp.newFolder("model-bad-json")
        File(dir, "llm_config.json").writeText("{ this is not json")

        val caps = MnnModelCapabilities.probe(dir)

        assertFalse(caps.hasChatTemplate)
        assertTrue(caps.note.contains("解析失败"))
    }

    /** 正常读盘路径：probe(File) 与 probeFromConfig 结论一致。 */
    @Test
    fun `probe from disk matches probe from config`() {
        val dir = tmp.newFolder("model-qwen3")
        val root = configWithTemplate(qwen3Template)
        File(dir, "llm_config.json").writeText(root.toString())

        val fromDisk = MnnModelCapabilities.probe(dir)
        val fromConfig = MnnModelCapabilities.probeFromConfig(root)

        assertEquals(fromConfig, fromDisk)
        assertTrue(fromDisk.hasChatTemplate)
        assertTrue(fromDisk.supportsTools)
        assertTrue(fromDisk.supportsThinkingToggle)
        assertTrue(fromDisk.emitsThinkBlock)
    }

    /** 只有 tools、没有 thinking 特征的模板（Hermes 系）。 */
    @Test
    fun `tools only template reports tools without thinking`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{%- for tool in tools %}{{ tool.function.name }}{%- endfor %}")
        )

        assertTrue(caps.hasChatTemplate)
        assertTrue(caps.supportsTools)
        assertFalse(caps.supportsThinkingToggle)
        assertFalse(caps.emitsThinkBlock)
        assertFalse(caps.supportsThinking)
        assertTrue(caps.note.contains("tools"))
    }

    /** 只发射 `<think>` 段、但没有开关变量的模型（DeepSeek-R1 系）。 */
    @Test
    fun `think block without toggle still counts as thinking`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{{ '<|im_start|>assistant\\n<think>' }}")
        )

        assertFalse(caps.supportsThinkingToggle)
        assertTrue(caps.emitsThinkBlock)
        assertTrue(caps.supportsThinking)
    }

    /** 普通 ChatML 模板：有模板但无 tools / thinking 特征，note 要讲清楚。 */
    @Test
    fun `plain chatml template has template but no capabilities`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{%- for message in messages %}<|im_start|>{{ message.role }}{%- endfor %}")
        )

        assertTrue(caps.hasChatTemplate)
        assertFalse(caps.supportsTools)
        assertFalse(caps.supportsThinking)
        assertTrue(caps.note.contains("未发现"))
    }

    /** 只有老式 `chat_template`（"%s" 占位）时不算 jinja 模板，要提示走内置 ChatML 兜底。 */
    @Test
    fun `legacy chat_template is not treated as jinja template`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            JSONObject().put("chat_template", "<|im_start|>user\n%s<|im_end|>")
        )

        assertFalse(caps.hasChatTemplate)
        assertFalse(caps.supportsTools)
        assertTrue(caps.note.contains("老式"))
        assertTrue(caps.note.contains("ChatML"))
    }

    /** `jinja.chat_template` 存在但是空串——等价于没有模板。 */
    @Test
    fun `blank jinja template is treated as missing`() {
        val caps = MnnModelCapabilities.probeFromConfig(configWithTemplate("   "))

        assertFalse(caps.hasChatTemplate)
        assertTrue(caps.note.contains("ChatML"))
    }

    /** 大小写差异不应影响探测（模板作者写 TOOLS / <THINK> 也要认）。 */
    @Test
    fun `feature detection is case insensitive`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{%- if TOOLS %}{%- endif %}{{ '<THINK>' }}{{ ENABLE_THINKING }}")
        )

        assertTrue(caps.supportsTools)
        assertTrue(caps.supportsThinkingToggle)
        assertTrue(caps.emitsThinkBlock)
    }

    /** summary() 供 QuroDiag 单行打印，必须包含全部四个维度，便于线上排查。 */
    @Test
    fun `summary contains all four dimensions`() {
        val summary = MnnModelCapabilities.probeFromConfig(configWithTemplate(qwen3Template)).summary()

        assertTrue(summary.contains("chat_template="))
        assertTrue(summary.contains("tools="))
        assertTrue(summary.contains("thinkingToggle="))
        assertTrue(summary.contains("thinkBlock="))
    }
}
