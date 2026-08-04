package com.ai.assistance.llama

object LlamaNative {

    init {
        LlamaLibraryLoader.loadLibraries()
    }

    @JvmStatic external fun nativeIsAvailable(): Boolean

    @JvmStatic external fun nativeGetUnavailableReason(): String

    @JvmStatic
    external fun nativeCreateSession(
        pathModel: String,
        nThreads: Int,
        nCtx: Int,
        nBatch: Int,
        nUBatch: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        flashAttention: Boolean,
        kvUnified: Boolean,
        offloadKqv: Boolean
    ): Long

    @JvmStatic external fun nativeReleaseSession(sessionPtr: Long)

    @JvmStatic external fun nativeCancel(sessionPtr: Long)

    @JvmStatic external fun nativeResetKv(sessionPtr: Long)

    @JvmStatic external fun nativeCountTokens(sessionPtr: Long, text: String): Int

    @JvmStatic
    external fun nativeSetSamplingParams(
        sessionPtr: Long,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        frequencyPenalty: Float,
        presencePenalty: Float,
        penaltyLastN: Int
    ): Boolean

    @JvmStatic
    external fun nativeApplyChatTemplate(
        sessionPtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean
    ): String?

    @JvmStatic
    external fun nativeApplyStructuredChatTemplate(
        sessionPtr: Long,
        messagesJson: String,
        toolsJson: String?,
        addAssistant: Boolean
    ): String?

    @JvmStatic
    external fun nativeGenerateStream(
        sessionPtr: Long,
        prompt: String,
        maxTokens: Int,
        callback: GenerationCallback
    ): Boolean

    @JvmStatic
    external fun nativeClearToolCallGrammar(sessionPtr: Long): Boolean

    @JvmStatic
    external fun nativeParseToolCallResponse(
        sessionPtr: Long,
        content: String
    ): String?

    /**
     * 取回本会话最近一次失败的人类可读原因，无失败时返回 null。
     *
     * 存在意义：原生层的失败以前只写 logcat，用户端一律只看到"没反应"，
     * 排障必须依赖 adb —— 用户拿不到，就只能靠猜。有了它，失败原因能直接进聊天气泡。
     */
    @JvmStatic
    external fun nativeGetLastError(sessionPtr: Long): String?

    interface GenerationCallback {
        fun onToken(token: String): Boolean

        /**
         * 生成前各阶段的进度（目前只有 stage="prefill"）。
         *
         * prefill 在手机 CPU 上可能耗时数十秒，期间一个 token 都吐不出来，UI 全程空白，
         * 用户观感就是"卡死/不回复"。有了它就能把"正在处理提示词 x/y"实时上屏。
         * 默认空实现：原生层用 GetMethodID 探测，找不到会静默降级，不影响生成。
         */
        fun onProgress(stage: String, current: Int, total: Int) {}
    }
}
