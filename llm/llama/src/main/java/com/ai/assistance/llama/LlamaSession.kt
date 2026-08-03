package com.ai.assistance.llama

class LlamaSession private constructor(
    private var sessionPtr: Long
) {

    /**
     * llama.cpp 会话参数。
     *
     * ⚠️ 默认值必须与已验证可用的参考实现（operit `buildAndroidLlamaSessionConfig`）保持一致，
     * 否则会出现「一直卡在模型加载」：
     * - [useMmap] = **false**：Android 上 GGUF 常放在外部存储 / SAF 挂载点（/storage/emulated/...），
     *   mmap 到这类文件系统会退化成随机页错误逐页读盘，几 GB 的权重能卡几分钟甚至永远不返回。
     *   关掉 mmap 改为一次性顺序读入，加载慢但**可预期**且不会卡死。
     * - [kvUnified] = **true**：单序列推理用统一 KV 缓存，llama_context 初始化时只分配一份，
     *   避免按 n_seq_max 预分配多份 KV（手机内存下容易 OOM 或长时间等待分配）。
     * 这两个默认值以前是 true / false（正好反了），是本地 llama.cpp「卡加载」的直接原因。
     */
    data class Config(
        val nThreads: Int = 4,
        val nCtx: Int = 2048,
        val nBatch: Int = 512,
        val nUBatch: Int = 512,
        val nGpuLayers: Int = 0,
        val useMmap: Boolean = false,
        val flashAttention: Boolean = false,
        val kvUnified: Boolean = true,
        val offloadKqv: Boolean = false
    )

    companion object {
        fun isAvailable(): Boolean = runCatching { LlamaNative.nativeIsAvailable() }.getOrDefault(false)

        fun getUnavailableReason(): String = runCatching { LlamaNative.nativeGetUnavailableReason() }
            .getOrDefault("llama.cpp backend unavailable")

        fun create(
            pathModel: String,
            config: Config
        ): LlamaSession? {
            if (!isAvailable()) return null
            val ptr = LlamaNative.nativeCreateSession(
                pathModel = pathModel,
                nThreads = config.nThreads,
                nCtx = config.nCtx,
                nBatch = config.nBatch,
                nUBatch = config.nUBatch,
                nGpuLayers = config.nGpuLayers,
                useMmap = config.useMmap,
                flashAttention = config.flashAttention,
                kvUnified = config.kvUnified,
                offloadKqv = config.offloadKqv
            )
            if (ptr == 0L) return null
            return LlamaSession(ptr)
        }
    }

    @Volatile
    private var released = false

    private val lock = Any()

    private fun checkValid() {
        if (released || sessionPtr == 0L) {
            throw RuntimeException("LlamaSession has been released")
        }
    }

    fun countTokens(text: String): Int {
        synchronized(lock) {
            checkValid()
            return LlamaNative.nativeCountTokens(sessionPtr, text)
        }
    }

    /**
     * @param onProgress 生成前阶段进度回调（stage, current, total），目前 stage 只有 "prefill"。
     *   prefill 在手机 CPU 上可能几十秒不吐 token，用它让 UI 不至于全程空白。
     */
    fun generateStream(
        prompt: String,
        maxTokens: Int,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onToken: (String) -> Boolean,
    ): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeGenerateStream(
            ptr,
            prompt,
            maxTokens,
            object : LlamaNative.GenerationCallback {
                override fun onToken(token: String): Boolean = onToken(token)
                override fun onProgress(stage: String, current: Int, total: Int) {
                    onProgress?.invoke(stage, current, total)
                }
            }
        )
    }

    /**
     * 最近一次原生失败的人类可读原因；null 表示没有记录到失败。
     * 会话已释放时返回 null 而不抛异常——调用方通常是在失败后的错误处理路径里问它。
     */
    fun lastError(): String? {
        val ptr: Long
        synchronized(lock) {
            if (released || sessionPtr == 0L) return null
            ptr = sessionPtr
        }
        return runCatching { LlamaNative.nativeGetLastError(ptr) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun applyStructuredChatTemplate(
        messagesJson: String,
        toolsJson: String?,
        addAssistant: Boolean
    ): String? {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeApplyStructuredChatTemplate(
            ptr,
            messagesJson,
            toolsJson,
            addAssistant
        )
    }

    fun clearToolCallGrammar(): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeClearToolCallGrammar(ptr)
    }

    fun parseToolCallResponse(content: String): String? {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeParseToolCallResponse(ptr, content)
    }

    fun applyChatTemplate(
        roles: List<String>,
        contents: List<String>,
        addAssistant: Boolean
    ): String? {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeApplyChatTemplate(
            ptr,
            roles.toTypedArray(),
            contents.toTypedArray(),
            addAssistant
        )
    }

    fun setSamplingParams(
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        frequencyPenalty: Float,
        presencePenalty: Float,
        penaltyLastN: Int = 64
    ): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeSetSamplingParams(
            ptr,
            temperature,
            topP,
            topK,
            repetitionPenalty,
            frequencyPenalty,
            presencePenalty,
            penaltyLastN
        )
    }

    fun cancel() {
        synchronized(lock) {
            if (released || sessionPtr == 0L) return
            LlamaNative.nativeCancel(sessionPtr)
        }
    }

    fun release() {
        val ptr: Long
        synchronized(lock) {
            if (released) return
            released = true
            ptr = sessionPtr
            sessionPtr = 0L
        }
        if (ptr != 0L) {
            LlamaNative.nativeReleaseSession(ptr)
        }
    }
}
