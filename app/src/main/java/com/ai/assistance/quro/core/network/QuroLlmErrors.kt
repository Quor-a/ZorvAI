package com.ai.assistance.quro.core.network

/**
 * LLM 上游错误归一化（issue #11 问题6：上下文超限时可被感知）。
 *
 * 用户实测诉求（v1.1.4）：
 *  > 超限时客户端不预检、不报错，直接发出 → 上游 400 → 自动重试 → 界面持续转圈；
 *  > 用户看不到原因，也无自救入口。
 *
 * 本类负责第 1 步「**识别**」：不自行估算 token，只匹配上游错误文案。
 * 各家网关措辞不一（400 / 413 / 422 / 甚至 500 都可能，见 QuroConversation 的
 * 「上游直接 500 context length exceeded」实测），因此**按内容判、不按状态码判**。
 *
 * 第 2 步「停止重试」由调用方落实：识别成功后必须走**确定性失败**通道
 * （[com.ai.assistance.quro.core.QuroLlmResult.Error.terminal] = true），
 * 既不进 HTTP 重试循环，也不被任务编排器重新规划重跑——重试必然同样失败，
 * 只会让界面持续转圈（issue 明确要求「第 2 步不可省」）。
 */
object QuroLlmErrors {

    /**
     * 上下文超限提示的**稳定前缀**——UI 靠它识别错误气泡并给出「新开对话」一键入口
     * （issue #11 问题6 第3步）。改这里必须同步改 [contextLimitMessage] 的首句。
     */
    const val CONTEXT_LIMIT_MESSAGE_PREFIX = "⚠️ 本次内容超出模型上下文上限"

    /** 上下文超限的确定性文案标记（小写化后子串匹配）。 */
    private val CONTEXT_LIMIT_MARKERS = listOf(
        "maximum context length",
        "context length exceeded",
        "context_length_exceeded",
        "context length is",
        "exceeds the context window",
        "context window exceeded",
        "maximum prompt length",
        "prompt is too long",
        "input is too long",
        "string too long",          // Bedrock / Anthropic 经部分网关的措辞
        "reduce the length of the messages",
        "messages are too long",
        "too many tokens",
        "tokens exceeds",
        "token limit",
    )

    /**
     * 是否「上下文/输入超限」类确定性错误。
     *
     * 判定口径（三条并联，命中任一即真）：
     *  1. 命中 [CONTEXT_LIMIT_MARKERS] 任一标记；
     *  2. **同时**提到 token 数 **且** 带明确超限语义（exceed / too long / too many /
     *     reduce the length）——覆盖 "your messages resulted in 12000 tokens" 这类
     *     不带上面任何标记的文案；
     *  3. `you requested … tokens` 句式（issue #11 原文点名）——该句式常不带任何
     *     超限词，只靠 "requested + tokens" 就能确定是上限冲突。
     *
     * 刻意不收束「max_tokens 参数非法」这类**参数错误**（它带 maximum/token 但不带
     * 超限语义），避免把参数问题误导向「请新开对话」的错误建议。
     */
    fun isContextLengthExceeded(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val s = raw.lowercase()
        if (CONTEXT_LIMIT_MARKERS.any { s.contains(it) }) return true
        val mentionsTokens = s.contains("token")
        if (s.contains("you requested") && mentionsTokens) return true
        val overLimit = s.contains("exceed") || s.contains("too long") || s.contains("too many") ||
            s.contains("reduce the length")
        return mentionsTokens && overLimit
    }

    /**
     * 生成给用户看的上下文超限提示（第 3 步「提示 + 处置入口」）。
     *
     * @param raw 上游原始错误体（用于抽取关键句；可为空）。
     */
    fun contextLimitMessage(raw: String?): String {
        val detail = extractKeySentence(raw)
        return buildString {
            append(CONTEXT_LIMIT_MESSAGE_PREFIX)
            if (detail != null) append("（上游：$detail）")
            append("。\n")
            append("这不是网络故障，重试不会成功。请任选其一处理：\n")
            append("① 点下方「新建对话」按钮重新开始（推荐，历史不再带入）；\n")
            append("② 在本对话继续发一句「请只基于最近几条消息继续，忽略更早的历史」，")
            append("或到对话设置里调小「保留历史轮数」；\n")
            append("③ 到「设置 → 模型配置」切换上下文窗口更大的模型。")
        }
    }

    /** 从上游错误体里抽取最关键的一句（超限原因），截断到 160 字；抽不出返回 null。 */
    fun extractKeySentence(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val plain = raw
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (plain.isEmpty()) return null
        // 优先抽 {"error":{"message":"..."}} 的 message
        val jsonMsg = Regex("\"message\"\\s*:\\s*\"([^\"]{10,300})\"").find(plain)?.groupValues?.get(1)
        val sentence = jsonMsg ?: plain
        return sentence.take(160)
    }
}
