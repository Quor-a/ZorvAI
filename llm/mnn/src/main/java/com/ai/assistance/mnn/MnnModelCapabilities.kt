package com.ai.assistance.mnn

import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * 从模型自带的 `llm_config.json` **探测**离线模型的实际能力，替代硬编码模型名白名单。
 *
 * ## 为什么不用白名单
 * 用户反馈的「部分离线模型有工具调用和思考但是不能用」，本质就是能力标记与模型实际情况脱节：
 * 按模型名猜能力，改个文件名就失灵；模型作者换了模板也无从感知。
 * MNN 模型的能力其实**全写在 `llm_config.json` 的 `jinja.chat_template` 里**：
 * - 模板里出现 `tools` 循环 → 模型被训练成能接收工具定义并输出 `<tool_call>`；
 * - 模板里出现 `enable_thinking` / `<think>` → 模型支持思考段开关（Qwen3 系）。
 *
 * 所以这里直接读模板文本做特征探测，跟着模型走，不跟着文件名走。
 *
 * ## 判定口径（保守）
 * 宁可报"不支持"也不误报"支持"：只有模板里出现明确特征串才置 true。
 * 探测失败（文件缺失 / JSON 损坏）时全部返回 false，并把原因放进 [note]，
 * 由上层决定是提示用户还是静默降级。
 */
object MnnModelCapabilities {

    private const val TAG = "MnnModelCapabilities"

    /**
     * 判定“模板真正消费 tools 变量”的结构化锚点（小写比对）。
     * 仅当模板出现下列任一特征串，才认为模型被训练成能接收并产出工具调用；否则保守判不支持，
     * 回落到上层 system 文本注入兜底。这些锚点都是 jinja 语法结构或输出渲染标签，
     * 几乎不可能出现在普通注释里，从而杜绝 `lower.contains("tools")` 的子串误判。
     */
    private val TOOLS_ANCHORS: List<String> = listOf(
        "tools is defined",   // jinja defined 检查
        "for tool in tools",  // jinja 遍历 tools
        "tools|length",       // jinja 对 tools 求长度
        "tools | length",     // 同上（带空格）
        "<tool_call",         // 输出侧渲染工具调用标签
        "tool_calls",         // 输出侧 tool_calls 字段（通常位于 {% if message.tool_calls %} 块内）
    )

    /**
     * 「jinja 条件分支消费 tools」的正则判定。
     *
     * 🔴 这里曾经是一条子串 `"{% if tools"`，注释还写着"含 `{%- if tools` 变体"——
     * 但那条子串**根本匹配不到** `{%- if tools %}`（`{` `%` 之后多了个 `-`）。
     * 而 Qwen3 / Hermes 系的官方模板恰恰写的就是 `{%- if tools %}`。
     * 之所以长期没被发现，是因为这些模板通常**同时**含 `for tool in tools`，
     * 被另一条锚点兜住了；一旦某个模板只写条件分支而不写循环，就会被误判成
     * "不支持工具调用"，工具定义被降级成 system 文本注入 —— 表现为
     * "模型明明支持 tool call，却总是答非所问、不按 schema 出参"。
     *
     * 用正则而非子串：空白控制符（`{%-` / `{%+`）与空格数量都不固定。
     */
    private val TOOLS_CONDITION_RE: Regex = Regex("""\{%[-+]?\s*if\s+[^%{]*\btools\b""")

    /**
     * 「默认标记集**认不出**、必须注入给原生分流器」的思考段标签对（开 → 闭）。
     *
     * 为什么需要这张表：原生共用的 ThinkSplitter 默认只覆盖 `<think>` / `<thinking>` /
     * 全角三种形态（见 quro/think_splitter.h 的 defaultMarkers）。真实模型还会用
     * `[THINK]`、GPT-OSS 系的 `<|channel|>analysis<|message|>`、另一种 channel 写法
     * `<|channel>thought` —— 不注入的话，那些模型的思考段在原生分流器里**根本不被
     * 识别**，整段推理会当正文推上屏。
     *
     * 口径与 llama 侧一致：那边由 llama.cpp 的模板 detector 自动给出
     * （common_chat_params::thinking_start_tag / thinking_end_tags），给出的就是这几种。
     *
     * 表里只列默认集不认的形态 —— 已认的重复注入虽然幂等，但配置越干净越好。
     */
    private val THINK_TAG_PAIRS: List<Pair<String, String>> = listOf(
        "[THINK]" to "[/THINK]",
        "<|channel|>analysis<|message|>" to "<|end|>",
        "<|channel>thought" to "<channel|>",
    )

    /** 模型能力探测结果。 */
    data class Capabilities(
        /** `jinja.chat_template` 是否存在且非空。false 时结构化路径要靠内置 ChatML 兜底。 */
        val hasChatTemplate: Boolean = false,
        /** 模板是否消费 `tools` 变量 —— 即模型是否真的能看见工具定义。 */
        val supportsTools: Boolean = false,
        /** 模板是否支持 `enable_thinking` 开关（Qwen3 等思考模型）。 */
        val supportsThinkingToggle: Boolean = false,
        /**
         * 模板是否会产出思考段（无论是否可开关）。
         *
         * 语义在 N9 之后**更完整**了：除了默认标记集能认的 `<think>`，
         * 现在也涵盖 `[THINK]` / `<|channel|>…` 这些默认集认不出的形态
         * （它们曾让"开关开了、剥离却没生效"变成一桩悬案）。
         */
        val emitsThinkBlock: Boolean = false,
        /**
         * 需要注入给原生分流器的思考段标签（开 / 闭，一一对应）。
         * 空表示模板只用默认形态，或探测不到 —— 两者都不需要注入。
         */
        val thinkOpenTags: List<String> = emptyList(),
        val thinkCloseTags: List<String> = emptyList(),
        /** 探测过程的补充说明（失败原因 / 命中特征），用于写诊断日志。 */
        val note: String = "",
    ) {
        /** 是否存在任一"思考"特征。 */
        val supportsThinking: Boolean get() = supportsThinkingToggle || emitsThinkBlock

        /** 一行式摘要，直接写进 QuroDiag。 */
        fun summary(): String =
            "chat_template=${yn(hasChatTemplate)} tools=${yn(supportsTools)} " +
                "thinkingToggle=${yn(supportsThinkingToggle)} thinkBlock=${yn(emitsThinkBlock)}" +
                if (note.isEmpty()) "" else " | $note"

        private fun yn(value: Boolean): String = if (value) "✓" else "✗"
    }

    /**
     * 探测指定 MNN 模型目录的能力。
     *
     * @param modelDir 含 `llm_config.json` 的模型目录。
     * @return 探测结果；任何异常都被吞掉并反映在 [Capabilities.note] 中，绝不抛出。
     */
    fun probe(modelDir: File): Capabilities {
        val configFile = File(modelDir, "llm_config.json")
        if (!configFile.isFile) {
            return Capabilities(note = "缺少 llm_config.json，无法探测能力")
        }
        val root = runCatching { JSONObject(configFile.readText()) }.getOrElse {
            Log.w(TAG, "Cannot parse llm_config.json: ${it.message}")
            return Capabilities(note = "llm_config.json 解析失败：${it.message}")
        }
        return probeFromConfig(root)
    }

    /**
     * 从已解析的 `llm_config.json` 内容探测能力。抽出来便于单测。
     *
     * @param root `llm_config.json` 根对象。
     */
    fun probeFromConfig(root: JSONObject): Capabilities {
        val jinja = root.optJSONObject("jinja")
        val template = jinja?.optString("chat_template", "").orEmpty()
        if (template.isBlank()) {
            // 退一步看看老式 chat_template（"%s" 占位那种），它不支持 tools/thinking。
            val legacy = root.optString("chat_template", "")
            val note = if (legacy.isBlank()) {
                "llm_config.json 没有 jinja.chat_template，结构化路径将使用内置 ChatML 兜底"
            } else {
                "仅有老式 chat_template（不支持 tools / thinking），结构化路径将使用内置 ChatML 兜底"
            }
            return Capabilities(note = note)
        }

        // 模板文本里的特征串。用小写比对，规避模板作者的大小写差异。
        val lower = template.lowercase()
        // 🔧 2.A（防御纵深）：不能仅凭 "tools" 子串就判定支持工具调用——很多模板只是注释里
        // 提到 "tools"、或把 "tools" 作为示例/变量名出现，并未真正构造可被模型消费的工具上下文。
        // 这里要求模板出现**结构化消费锚点**：jinja 条件分支 / 循环 / defined 检查真正引用了
        // tools 变量，或输出侧会渲染 <tool_call> / tool_calls。只有命中这些锚点才乐观置 true；
        // 否则保守判为不支持，回落到上层 withToolInstruction 的 system 文本注入兜底
        // （该兜底同样能把工具 schema 交付给模型，不会让“开了工具调用却毫无反应”的无声失败再现）。
        val supportsTools = TOOLS_ANCHORS.any { anchor -> lower.contains(anchor) } ||
            TOOLS_CONDITION_RE.containsMatchIn(lower)
        val supportsThinkingToggle = lower.contains("enable_thinking")
        // 默认标记集能认的形态沿**原口径**判定 —— 这一条直接挂钩 v1.0.50
        // 「回复混进 Thinking Process 独白」那次修正的依据，不动它。
        val emitsDefaultThinkBlock = lower.contains("<think>") || lower.contains("</think>")
        // 默认集**认不出**、需要往下注入给原生分流器的那些标签对。
        // 要求开闭**成对**出现，避免把正文里偶发的单个标签当成思考段特征。
        val extraPairs = THINK_TAG_PAIRS.filter { (open, close) ->
            lower.contains(open.lowercase()) && lower.contains(close.lowercase())
        }
        val emitsThinkBlock = emitsDefaultThinkBlock || extraPairs.isNotEmpty()
        val thinkOpenTags = extraPairs.map { it.first }
        val thinkCloseTags = extraPairs.map { it.second }

        val hits = buildList {
            if (supportsTools) add("tools")
            if (supportsThinkingToggle) add("enable_thinking")
            // 用 emitsDefaultThinkBlock 而非 emitsThinkBlock：后者在 N9 之后
            // 也涵盖 [THINK] / <|channel|> 这类**不带 <think> 字面**的模板，
            // 拿它当判定会把"命中 <think>"打进日志，而模板里根本没有这个串 ——
            // 诊断日志的价值就是让人不用猜，不能自相矛盾。
            if (emitsDefaultThinkBlock) add("<think>")
            if (extraPairs.isNotEmpty()) add("thinkTags=" + thinkOpenTags.joinToString(","))
        }
        val note = if (hits.isEmpty()) {
            "jinja.chat_template 存在但未发现 tools / thinking 特征"
        } else {
            "模板命中特征：${hits.joinToString("、")}"
        }

        return Capabilities(
            hasChatTemplate = true,
            supportsTools = supportsTools,
            supportsThinkingToggle = supportsThinkingToggle,
            emitsThinkBlock = emitsThinkBlock,
            thinkOpenTags = thinkOpenTags,
            thinkCloseTags = thinkCloseTags,
            note = note,
        )
    }
}
