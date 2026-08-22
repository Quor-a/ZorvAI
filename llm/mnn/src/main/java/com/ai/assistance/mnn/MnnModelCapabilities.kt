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
        "{% if tools",        // jinja 条件分支消费 tools（含 {%- if tools 变体）
        "tools is defined",   // jinja defined 检查
        "for tool in tools",  // jinja 遍历 tools
        "tools|length",       // jinja 对 tools 求长度
        "tools | length",     // 同上（带空格）
        "<tool_call",         // 输出侧渲染工具调用标签
        "tool_calls",         // 输出侧 tool_calls 字段（通常位于 {% if message.tool_calls %} 块内）
    )

    /** 模型能力探测结果。 */
    data class Capabilities(
        /** `jinja.chat_template` 是否存在且非空。false 时结构化路径要靠内置 ChatML 兜底。 */
        val hasChatTemplate: Boolean = false,
        /** 模板是否消费 `tools` 变量 —— 即模型是否真的能看见工具定义。 */
        val supportsTools: Boolean = false,
        /** 模板是否支持 `enable_thinking` 开关（Qwen3 等思考模型）。 */
        val supportsThinkingToggle: Boolean = false,
        /** 模板是否会产出 `<think>` 段（无论是否可开关）。 */
        val emitsThinkBlock: Boolean = false,
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
        val supportsTools = TOOLS_ANCHORS.any { anchor -> lower.contains(anchor) }
        val supportsThinkingToggle = lower.contains("enable_thinking")
        val emitsThinkBlock = lower.contains("<think>") || lower.contains("</think>")

        val hits = buildList {
            if (supportsTools) add("tools")
            if (supportsThinkingToggle) add("enable_thinking")
            if (emitsThinkBlock) add("<think>")
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
            note = note,
        )
    }
}
