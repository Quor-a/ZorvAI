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
        offloadKqv: Boolean,
        /**
         * L5 · 温控自适应采样间隔（毫秒）。**0 = 禁用**（默认）。
         *
         * 启用后原生层会在 prefill 的 chunk 边界与 decode 的 token 边界读取 SoC
         * thermal headroom，并按档位**主动**下调线程数 —— 持续推理时这能保住约 77%
         * 峰值吞吐，而不是被平台断崖式降频打到 31%。
         *
         * 与 nativeCreateSession 的 JNI 签名
         * `(Ljava/lang/String;IIIIIZZZZI)J` **必须一致**：这里的顺序就是
         * cpp 形参顺序，错一个就是运行期 UnsatisfiedLinkError。
         */
        thermalPollMs: Int
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
        /** 正文段增量。@return true 继续生成，false 停止生成 */
        fun onToken(token: String): Boolean

        /**
         * 思考段增量（模型 `<think>…</think>` 内部内容）。
         *
         * 引擎侧 L4 分流器已经把思考标签吃掉、并把内容拆成两个通道，
         * 所以这里拿到的是**不带标签的纯净思考文本**，直接送 UI 的「思考区」即可。
         *
         * 声明为**抽象方法**而不是给默认实现，是刻意的：
         * 若默认实现转调 [onToken]，未升级的实现类会把不带标签的思考原文当正文
         * 吐出去（实时上屏），而且编译期毫无提示。
         * 抽象方法能让**所有实现类编译报错**，强制每个实现点显式处理这个通道。
         *
         * 原生侧按 `onThinking(Ljava/lang/String;)Z` 用 GetMethodID 探测，
         * 探测不到时会退化成"把思考内容包回 `<think>` 标签走 onToken"，
         * 因此这里**必须**与原生签名严格一致。
         *
         * @return true 继续生成，false 停止生成
         */
        fun onThinking(token: String): Boolean

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
