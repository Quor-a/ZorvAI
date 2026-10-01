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

    // ─────────────────── N9：默认标记集**认不出**的思考段标签对 ───────────────────

    /**
     * `[THINK]` / `[/THINK]` —— 默认标记集（`<think>` / `<thinking>` / 全角）认不出。
     *
     * 这是 N9 之前的一个真实缺口：`emitsThinkBlock` 因此判为 false，于是思考开关不开、
     * 原生分流器也切不开，整段推理会当正文推上屏。现在两条同时兜住：
     * 能力位为真（开关开），标签注入给原生（切得开）。
     */
    @Test
    fun `bracketed think tags are detected and handed to native splitter`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{{ '[THINK]' }}{{ '[/THINK]' }}")
        )

        assertTrue(caps.emitsThinkBlock)
        assertEquals(listOf("[THINK]"), caps.thinkOpenTags)
        assertEquals(listOf("[/THINK]"), caps.thinkCloseTags)
        assertTrue(caps.note.contains("thinkTags=[THINK]"))
        // 模板里根本没有 <think> 字面，note 就不该声称命中了它 ——
        // 诊断日志的价值就是让人不用猜，不能自相矛盾。
        assertFalse(caps.note.contains("<think>"))
    }

    /** GPT-OSS 系 channel 写法：开标签 28 字节，靠原生侧「定长匹配」才容纳得下。 */
    @Test
    fun `channel style think tags are detected`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{{ '<|channel|>analysis<|message|>' }}{{ '<|end|>' }}")
        )

        assertTrue(caps.emitsThinkBlock)
        assertEquals(listOf("<|channel|>analysis<|message|>"), caps.thinkOpenTags)
        assertEquals(listOf("<|end|>"), caps.thinkCloseTags)
    }

    /** 必须**成对**才算：正文里偶发一个 `[THINK]` 不足以判定模型会发射思考段。 */
    @Test
    fun `unpaired think tag does not claim thinking support`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{{ '[THINK] 只是注释里提到一次' }}")
        )

        assertFalse(caps.emitsThinkBlock)
        assertTrue(caps.thinkOpenTags.isEmpty())
        assertTrue(caps.thinkCloseTags.isEmpty())
    }

    /** 默认形态与额外形态并存时两组都要报（并集，不是替换）。 */
    @Test
    fun `default and extra think tags coexist`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{{ '<think>' }}{{ '[THINK]' }}{{ '[/THINK]' }}")
        )

        assertTrue(caps.emitsThinkBlock)
        assertEquals(listOf("[THINK]"), caps.thinkOpenTags)
        assertTrue(caps.note.contains("<think>"))
        assertTrue(caps.note.contains("thinkTags=[THINK]"))
    }

    // ─────────────────── 工具锚点：条件分支的空白控制符变体 ───────────────────

    /**
     * 只写条件分支、不写循环的模板（`{%- if tools %}`）必须判为支持工具调用。
     *
     * 这是本次修掉的一个**真 bug**：锚点曾是子串 `"{% if tools"`，注释还写着
     * "含 `{%- if tools` 变体"，但那条子串**匹配不到** `{%- if tools %}`
     * （`{%` 之后多了个空白控制符 `-`）。而 Qwen3 / Hermes 系官方模板写的正是
     * `{%- if tools %}`。长期没暴露，是因为那些模板同时含 `for tool in tools`，
     * 被另一条锚点兜住了 —— 一旦模板只写分支不写循环就会误判为"不支持"，工具
     * 定义被降级成 system 文本注入。
     */
    @Test
    fun `conditional only tools template counts as supported`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{%- if tools %}{{ tools | tojson }}{%- endif %}")
        )

        assertTrue(caps.supportsTools)
        assertTrue(caps.note.contains("tools"))
    }

    /** `{%+ if tools %}`（`+` 空白控制符）同样要认，空格数量也不应影响判定。 */
    @Test
    fun `plus whitespace control tools condition counts as supported`() {
        val caps = MnnModelCapabilities.probeFromConfig(
            configWithTemplate("{%+ if   tools %}{{ tools }}{% endif %}")
        )

        assertTrue(caps.supportsTools)
    }
}
