package com.ai.assistance.mnn

/**
 * 把原生层（mnnllmnative.cpp）回传的错误串翻译成用户能看懂的中文提示。
 *
 * ## 存在原因
 * JNI 的 `nativeGenerateStreamStructured` 只能返回 `boolean`，失败细节全落在 logcat。
 * 手机侧没有 adb，用户唯一能看到的就是上层那句「MNN 推理未产生任何输出（ok=false）」——
 * 既不知道是模型问题、模板问题还是 tokenizer 问题，也不知道下一步该做什么。
 *
 * 现在原生层失败时会写入 `"错误码|英文补充说明"`（见 mnnllmnative.cpp 的 `setLastError`），
 * 由本类翻译成「发生了什么 + 该怎么办」两段式中文。
 *
 * ## 约定
 * 错误码字符串必须与 native 侧保持一致，改名要两边一起改。
 * 未知错误码不会丢信息——会原样带出补充说明，避免出现比原来还模糊的提示。
 */
object MnnNativeError {

    /** 原生错误串的分隔符：`错误码|补充说明`。 */
    private const val SEPARATOR = '|'

    /**
     * 解析后的原生错误。
     *
     * @property code 稳定错误码（如 `E_MNN_TEMPLATE_EMPTY`）；无法识别时为 `E_MNN_UNKNOWN`。
     * @property detail 原生给出的英文补充说明，可能为空串。
     * @property message 面向用户的完整中文提示（含处置建议）。
     */
    data class Parsed(
        val code: String,
        val detail: String,
        val message: String,
    )

    /**
     * 把原生错误串翻译为中文提示。
     *
     * @param raw `nativeGetLastError` 的返回值；null 或空串表示原生没留下任何线索。
     * @param fallback 原生无错误信息时使用的兜底文案。
     * @return 解析结果；[raw] 为空时 code 为 `E_MNN_NO_DETAIL`。
     */
    fun parse(raw: String?, fallback: String): Parsed {
        if (raw.isNullOrBlank()) {
            return Parsed(code = "E_MNN_NO_DETAIL", detail = "", message = fallback)
        }
        val sepIndex = raw.indexOf(SEPARATOR)
        val code = if (sepIndex > 0) raw.substring(0, sepIndex).trim() else raw.trim()
        val detail = if (sepIndex > 0 && sepIndex + 1 < raw.length) {
            raw.substring(sepIndex + 1).trim()
        } else {
            ""
        }
        return Parsed(code = code, detail = detail, message = describe(code, detail))
    }

    /**
     * 错误码 → 中文提示（含处置建议）。
     *
     * @param code 原生错误码。
     * @param detail 英文补充说明，附在括号里，方便反馈问题时贴日志。
     */
    private fun describe(code: String, detail: String): String {
        val body = when (code) {
            "E_MNN_NO_MESSAGES" ->
                "没有可发送的对话内容：本轮消息在传给推理引擎前就是空的。" +
                    "请确认输入框不为空，或系统提示词没有把整段对话清掉。"

            "E_MNN_BAD_MESSAGES" ->
                "对话内容序列化异常：传给推理引擎的消息不是合法 JSON 数组。" +
                    "这是应用内部错误，请把本条日志反馈给开发者。"

            "E_MNN_NO_VALID_MESSAGE" ->
                "对话内容为空：所有消息都被过滤掉了（通常是内容全为空白）。" +
                    "请输入实际内容后重试。"

            "E_MNN_TEMPLATE_THROW" ->
                "对话模板渲染失败：模型 llm_config.json 里的 chat_template 有语法错误，" +
                    "jinja 渲染时抛出异常。请更换模型，或让模型作者修正模板。"

            "E_MNN_TEMPLATE_EMPTY" ->
                "对话模板渲染结果为空：该模型没有配置可用的 chat_template，" +
                    "内置的 ChatML 兜底模板也没能生成有效提示词。" +
                    "常见于「只导出了权重、没导出对话模板」的模型——" +
                    "请改用带完整 llm_config.json（含 jinja.chat_template）的 MNN 模型。"

            "E_MNN_EMPTY_TOKENS" ->
                "分词失败：提示词已生成，但 tokenizer 编码出 0 个 token。" +
                    "通常是模型目录里的 tokenizer 文件缺失或与权重不匹配，" +
                    "请重新完整下载该模型。"

            "E_MNN_STREAM_THROW" ->
                "推理过程中原生层抛出异常。可能是内存不足或模型文件损坏，" +
                    "请尝试降低上下文长度 / 换用更小的模型 / 重新下载模型。"

            else ->
                "推理引擎返回了未识别的错误（$code）。"
        }
        return if (detail.isEmpty()) body else "$body（原生诊断：$detail）"
    }
}
