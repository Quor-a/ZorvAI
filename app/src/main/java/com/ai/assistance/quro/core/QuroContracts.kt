package com.ai.assistance.quro.core

/**
 * Zorv AI 核心契约。
 * 统一描述对话消息、工具调用、工具规格与 LLM 返回结果。
 */

/** 发送给 LLM 的一条消息。 */
data class QuroChatMessage(
    val role: String,                 // "system" | "user" | "assistant" | "tool"
    val content: String,
    val toolCalls: List<QuroToolCall>? = null, // 仅 assistant 且含工具调用时
    val toolCallId: String? = null,            // 仅 role="tool" 时
    val attachments: List<QuroAttachment>? = null, // 仅 user 且含附件时（图片送视觉模型）
    /** 仅 role="tool" 时携带工具名（function name）。标准 OpenAI 格式里工具名写在前面 assistant
     * 的 tool_calls[].function.name 上、tool 消息可省略；但 Kimi K3 等严格实现要求 tool 消息
     * 自身带 name（或靠顺序对齐），否则 400。这里在 toLlmMessages 收尾按 tool_call_id 反查补全。 */
    val toolName: String? = null,
    /** 推理模型（MiMo / DeepSeek-Reasoner 等）的思考过程。回放带 tool_calls 的 assistant 历史时
     *  必须一并携带 reasoning_content，否则部分严格上游（如 mimo-v2.5 要求 requiresReasoningContentOnAssistantMessages）
     *  会拒收 → 500。此前 toLlmMessages 构造 QuroChatMessage 时漏传此字段，导致思考被丢弃、回放历史缺思考。 */
    val reasoning: String? = null,
)

/** 一次工具调用（LLM 产生；也可由引擎回填 [result] 供 UI 自包含展示）。 */
data class QuroToolCall(
    val id: String = "",
    val name: String,
    val arguments: String,            // 原始 JSON 字符串
    /** 工具执行结果（引擎执行完后回填进 assistant 消息的 toolCalls）。
     *  UI 直接从单条 assistant 消息读出「工具名 + 参数 + 结果」三件套，
     *  不再依赖跨消息 resultMap 匹配 role=tool 结果 → 彻底消除「工具调用展示缺失」。 */
    val result: String? = null,
    /** 工具本次执行耗时（毫秒），由 [QuroAssistant] 在 engine.execute 前后计时回填，UI 展示用。 */
    val durationMs: Long = 0,
)

/** 工具规格（下发给 LLM 的 function 描述）。 */
data class QuroToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,       // JSON Schema 对象字符串
)

/** LLM 返回结果。 */
sealed interface QuroLlmResult {
    data class Text(
        val content: String,
        val reasoning: String? = null,
        /** 停止原因 + token 用量（N3）。上游未回 usage 时各字段为 -1，见 [QuroLlmMeta]。 */
        val meta: QuroLlmMeta = QuroLlmMeta.EMPTY,
    ) : QuroLlmResult
    /** 工具调用结果：[calls] 为本轮模型要求执行的工具列表；[reasoning] 为模型本轮的思考过程
     *  （MiMo 等 reasoning 模型会在 tool_calls 同时返回 reasoning_content，必须保留并在
     *   回传给模型时一并携带，否则模型每轮都在「失忆」状态下做下一步决策，
     *   无法链式编排多步工具调用）。 */
    data class ToolCalls(
        val calls: List<QuroToolCall>,
        val reasoning: String? = null,
        val content: String? = null,
        /** 停止原因 + token 用量（N3）。工具轮被截断时 [QuroLlmMeta.truncated] 为真。 */
        val meta: QuroLlmMeta = QuroLlmMeta.EMPTY,
    ) : QuroLlmResult
    data class Error(val message: String) : QuroLlmResult
}

/**
 * 工具执行结果（引擎内部用）—— **工具与环境层的返回信封**。
 *
 * ## 为什么要有信封
 *
 * 改造前它只有 `(name, result)` 两个字符串字段：成败靠 `name` 塞 `"success"` / `"error"`
 * 两个魔法值，没有 code、没有异常语义、也没有「统一给模型看的返回格式」。
 * 全仓 119 处引用都走 [Companion.Success] / [Companion.Error]，等于这个伪信封是工具层的地基，
 * 而每个工具返回给模型的文本形态都不一样（有的裸 JSON、有的中文报错、有的是 stack trace）。
 *
 * 模型每轮看到的工具返回形状都不同 → 猜字段 → 猜错 → 复读 / 答非所问。
 * 生产级 Agent 里「工具与环境层」恰恰是最容易被忽略、但对正确性影响最大的一层：
 * 返回格式规不规范，直接决定模型能不能正确接着往下编排。
 *
 * ## 兼容性
 *
 * 前两个参数保持 `name` / `result` 的原名与原序，[Companion.Success] / [Companion.Error]
 * 工厂签名不变 —— **119 处调用点零改动**。新增字段一律带默认值。
 */
data class QuroToolResult(
    val name: String,
    val result: String,
    /** 机器可读的归一化错误码，见 [ToolStatus]。成功时为空串。 */
    val code: String = "",
    /** 人类可读的失败说明（已归一，不含原始 stack trace）。 */
    val message: String = "",
) {
    /**
     * 成败 —— **结构判定，不靠字符串嗅探**。
     *
     * 规则刻意放宽成「没有被显式标记成 error 就算成功」：
     * - [Companion.Success] → name="success"；[Companion.Error] / [Companion.Failed] → name="error"；
     * - 少数直接写成 `QuroToolResult(工具名, 正文)` 的调用点（tool_router、子智能体）
     *   把**工具名**塞进了 name —— 这类是「正文」不是「状态」，如果按「name 不是 success 即失败」
     *   来判，一次正常的工具目录查询会被渲染成 `status: error`，等于把所有工具调用都判失败。
     */
    val ok: Boolean get() = !name.equals("error", ignoreCase = true) && code.isBlank()

    /** 归一化状态：优先用显式 code，否则从 message/result 里反推，推不出为 UNKNOWN。 */
    fun status(): ToolStatus = ToolStatus.from(code.ifBlank { message }.ifBlank { result })

    companion object {
        /**
         * 成功结果。`name` 填 "success"，便于调用方按 name 判定成败而不必解析 result 文案。
         *
         * 说明：这里用大写开头的工厂函数（而非 Kotlin 惯例的小写），
         * 是为了让调用处写成语义清晰的 `QuroToolResult.Success("...") / .Error("...")`，
         * 与 sealed 结果类型的写法保持一致，后续若改为 sealed 类也无需改动调用点。
         */
        @Suppress("FunctionName")
        fun Success(result: String): QuroToolResult = QuroToolResult("success", result)

        /** 失败结果。`name` 填 "error"。 */
        @Suppress("FunctionName")
        fun Error(result: String): QuroToolResult = QuroToolResult("error", result)

        /** 带归一化 code 的失败结果（推荐用于有明确错误分类的工具）。 */
        fun Failed(code: String, message: String): QuroToolResult =
            QuroToolResult("error", message, code, message)
    }
}

/**
 * 归一化的工具失败原因。
 *
 * 作用是把各家工具用自己的英文/中文/stack trace 描述的错误，**收敛成模型能稳定识别的有限集合**。
 * 模型看到一个稳定的 code，才知道「该重试还是该换工具还是该换参数」，而不是靠猜。
 */
enum class ToolStatus(val cue: String) {
    SUCCESS("ok"),
    PERMISSION_DENIED("permission"),
    TIMEOUT("timeout"),
    NOT_FOUND("not found"),
    EXIT_NON_ZERO("exit code"),
    INVALID_INPUT("invalid"),
    RESOURCE_EXHAUSTED("out of memory"),
    /** 认不出来的失败：只说明「失败了」，不猜原因。 */
    UNKNOWN("unknown");

    companion object {
        private val MARKERS = listOf(
            PERMISSION_DENIED to listOf("permission", "denied", "forbidden", "拒绝", "权限", "只读"),
            TIMEOUT to listOf("timeout", "timed out", "超时"),
            NOT_FOUND to listOf("not found", "no such file", "找不到", "不存在", "no such"),
            RESOURCE_EXHAUSTED to listOf("out of memory", "memory exhausted", "out-of-memory", "内存不足", "进程被 kill"),
            INVALID_INPUT to listOf("invalid", "parse error", "malformed", "不合法", "缺少", "missing argument"),
            EXIT_NON_ZERO to listOf("exit code", "exit status", "non-zero", "退出码", "非零"),
        )

        /** 从一段自由文本反推状态；推不出返回 [UNKNOWN]（不臆测）。 */
        fun from(text: String): ToolStatus {
            if (text.isBlank()) return UNKNOWN
            val t = text.lowercase()
            for ((status, cues) in MARKERS) {
                for (cue in cues) if (t.contains(cue)) return status
            }
            return UNKNOWN
        }
    }
}

/**
 * 工具结果 → 模型看到的**统一返回文本**（工具与环境层的出口格式化）。
 *
 * 全仓只有这一个出口：`QuroAssistant` 组装 role=tool 消息时统一调它，
 * 于是不论哪个工具、哪种失败，模型拿到的都是同一种形状 ——
 *
 * ```
 * [tool] terminal_exec
 * status: ok
 * result:
 * ...
 * ```
 *
 * 失败时额外给 code 和一句「该怎么办」，而不是把 stack trace 原样糊给模型：
 * ```
 * [tool] terminal_exec
 * status: error
 * code: permission_denied
 * message: Permission denied
 * hint: the call failed - check the input/path/permissions, retry with a smaller scope,
 * or pick a different tool; never invent an answer for what you did not get back.
 * （执行失败：请先确认前置条件与数据，或缩小范围重试，不要臆测结果）
 * ...原始尾部...
 * ```
 */
object QuroToolEnvelope {
    /** 单条返回文本上限：正常工具结果远小于此，只有失控输出（如把整本日志吐回来）才会被标注截断。 */
    const val MAX_TEXT = 12_000

    /** 主入口：把一条 [QuroToolResult] 渲染成模型看到的统一文本。 */
    fun of(toolName: String, r: QuroToolResult): String = r.toModelText(toolName)

    /** 纯字符串入口（工具调用代码手里只有原始字符串时用）。 */
    fun of(toolName: String, ok: Boolean, raw: String): String =
        if (ok) successText(toolName, raw) else errorText(toolName, "", "", raw)
}

private fun successText(tool: String, raw: String): String = buildString {
    append("[tool] ").append(tool).append("\n")
    append("status: ok\n")
    appendResult(raw)
}

private fun errorText(tool: String, code: String, message: String, raw: String): String = buildString {
    val inferred = ToolStatus.from(code.ifBlank { message }.ifBlank { raw })
    append("[tool] ").append(tool).append("\n")
    append("status: error\n")
    append("code: ").append(code.ifBlank { inferred.cue }).append("\n")
    append("message: ").append(message.ifBlank { raw.take(300) }).append("\n")
    appendHint()
    appendResult(raw)
}

/** 失败时的统一兜底提示：短、不啰嗦、跨语言可读。 */
private fun StringBuilder.appendHint() {
    append("hint: the call failed - check the input/path/permissions, retry with a smaller scope, ")
    append("or pick a different tool; never invent an answer for what you did not get back.\n")
}

private fun StringBuilder.appendResult(raw: String) {
    append("result:\n")
    if (raw.length <= QuroToolEnvelope.MAX_TEXT) {
        append(raw)
    } else {
        append(raw.take(QuroToolEnvelope.MAX_TEXT))
        append("\n... [truncated: original ").append(raw.length).append(" chars]")
    }
}

private fun QuroToolResult.toModelText(tool: String): String =
    if (ok) successText(tool, result) else errorText(tool, code, message, result)
