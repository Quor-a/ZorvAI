package com.ai.assistance.mnn

import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * MNN LLM 会话封装
 * 提供高级 API 来管理 LLM 推理会话
 */
class MNNLlmSession private constructor(
    private var llmPtr: Long,
    private val modelPath: String
) {
    companion object {
        private const val TAG = "MNNLlmSession"
        
        /**
         * 从模型目录创建 LLM 会话
         * @param modelDir 模型目录（包含 llm_config.json）
         * @param backendType 后端类型（"cpu", "opencl", "metal"）
         * @param threadNum 线程数
         * @param precision 精度（"low", "normal", "high"）
         * @param memory 内存模式（"low", "normal", "high"）
         * @param tmpPath 临时文件目录（用于缓存文件），默认为模型目录
         * @return MNNLlmSession 实例，失败返回 null
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            modelDir: String,
            backendType: String = "cpu",
            threadNum: Int = 4,
            precision: String = "low",
            memory: String = "low",
            tmpPath: String? = null,
            sampler: MnnSamplerTuning = MnnSamplerTuning(),
            repetitionGuard: RepetitionGuard.Config = RepetitionGuard.Config()
        ): MNNLlmSession? {
            val configFile = File(modelDir, "llm_config.json")
            
            if (!configFile.exists()) {
                Log.e(TAG, "Config file not found: ${configFile.absolutePath}")
                return null
            }
            
            Log.d(TAG, "Creating LLM session from: ${configFile.absolutePath}")
            Log.d(TAG, "Backend: $backendType, Threads: $threadNum, Precision: $precision, Memory: $memory")
            Log.d(TAG, "Cache path: ${tmpPath ?: modelDir}")
            
            // 步骤1: 创建LLM实例（不加载）
            val llmPtr = MNNLlmNative.nativeCreateLlm(configFile.absolutePath)
            if (llmPtr == 0L) {
                Log.e(TAG, "Failed to create LLM native instance")
                return null
            }
            
            // 步骤2: 设置配置（必须在load之前！）
            // 按照官方 llm_bench.cpp 的顺序设置配置
            // tmp_path 用于存放 mnn_cachefile.bin 等临时文件
            val cachePath = tmpPath ?: modelDir
            val configs = listOf(
                """{"tmp_path":"$cachePath"}""",
                """{"async":false}""",
                """{"precision":"$precision"}""",
                """{"memory":"$memory"}""",
                """{"backend_type":"$backendType"}""",
                """{"thread_num":$threadNum}"""
            )
            
            for (config in configs) {
                if (!MNNLlmNative.nativeSetConfig(llmPtr, config)) {
                    Log.e(TAG, "Failed to set config: $config")
                    MNNLlmNative.nativeReleaseLlm(llmPtr)
                    return null
                }
                Log.d(TAG, "Config set: $config")
            }

            // 步骤2.5: 注入抗复读采样配置（必须在 load 之前！）
            // Sampler 实例是在 Llm::load() 里 createSampler 的（llm.cpp:337），load 之后再
            // setConfig 对采样管线无效。这些配置失败不阻断加载，仅降级为「无抗复读」。
            for (config in buildSamplerConfigs(configFile, sampler)) {
                if (MNNLlmNative.nativeSetConfig(llmPtr, config)) {
                    Log.d(TAG, "Sampler config set: $config")
                } else {
                    Log.w(TAG, "Failed to set sampler config (non-fatal): $config")
                }
            }

            // 步骤3: 加载模型（配置已设置）
            if (!MNNLlmNative.nativeLoadLlm(llmPtr)) {
                Log.e(TAG, "Failed to load LLM model")
                MNNLlmNative.nativeReleaseLlm(llmPtr)
                return null
            }
            
            Log.i(TAG, "LLM session created and loaded successfully")
            return MNNLlmSession(llmPtr, modelDir).apply {
                repetitionGuardConfig = repetitionGuard
            }
        }

        /** MNN 引擎内置的默认采样链（llmconfig.hpp:445）——注意其中**没有** "penalty"。 */
        private val MNN_DEFAULT_SAMPLER_CHAIN =
            listOf("topK", "tfs", "typical", "topP", "min_p", "temperature")

        /**
         * 构造抗复读采样配置 JSON 列表。
         *
         * ## 根因
         * MNN 的 `Sampler::buildPipeline`（sampler.cpp:208-210）只有在 `mixed_samplers`
         * 列表中出现字面量 `"penalty"` 时，才会把 `stepPenalty` 挂进采样管线。而引擎默认的
         * `mixed_samplers`（llmconfig.hpp:445）是 `[topK, tfs, typical, topP, min_p, temperature]`，
         * **不含 penalty**，同时默认 `repetition_penalty` = 1.0（llmconfig.hpp:485）。
         * 两者叠加 ⇒ 重复惩罚在默认配置下 100% 是死代码，模型对复读没有任何约束。
         *
         * 另外 `sampler_type` 若为 `"greedy"`，`Sampler::sample`（sampler.cpp:239）会直接
         * device 侧 argmax 短路返回，整条管线（含 penalty）都不会执行——所以必须强制 `"mixed"`。
         *
         * ## 策略
         * 尊重模型自带 `llm_config.json` 的既有取值，只在缺失或明显失效时补齐兜底值，
         * 尽量减少对模型调优结果的干扰。
         *
         * @param configFile 模型的 llm_config.json。
         * @param tuning 兜底采样参数。
         * @return 待逐条下发给 `nativeSetConfig` 的 JSON 字符串列表；[MnnSamplerTuning.enabled]
         *   为 false 时返回空列表。
         */
        private fun buildSamplerConfigs(configFile: File, tuning: MnnSamplerTuning): List<String> {
            if (!tuning.enabled) return emptyList()

            val modelConfig: JSONObject = runCatching { JSONObject(configFile.readText()) }
                .getOrElse {
                    Log.w(TAG, "Cannot parse llm_config.json for sampler tuning: ${it.message}")
                    JSONObject()
                }

            // 1) 采样链：保留模型自带顺序，仅确保 "penalty" 存在（放到最前，与引擎内部
            //    configMixed 的 "move penalty to front" 行为一致，sampler.cpp:130-143）。
            val existingChain = modelConfig.optJSONArray("mixed_samplers")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it, "") } }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val chain = (if (existingChain.isEmpty()) MNN_DEFAULT_SAMPLER_CHAIN else existingChain)
                .toMutableList()
            if (!chain.contains("penalty")) chain.add(0, "penalty")

            val configs = mutableListOf<String>()
            configs += """{"sampler_type":"mixed"}"""
            configs += """{"mixed_samplers":[${chain.joinToString(",") { JSONObject.quote(it) }}]}"""

            // 2) 惩罚强度：模型若已显式配置 > 1.0 则尊重之，否则补兜底值。
            //    注意引擎对 repetition_penalty 有 "penalty" 旧键兼容（llmconfig.hpp:485-488）。
            val modelPenalty = modelConfig
                .optDouble("repetition_penalty", modelConfig.optDouble("penalty", Double.NaN))
            val penalty = if (!modelPenalty.isNaN() && modelPenalty > 1.0) {
                modelPenalty.toFloat()
            } else {
                tuning.repetitionPenalty
            }
            configs += """{"repetition_penalty":$penalty}"""

            // 3) 惩罚窗口 / n-gram 兜底：只在模型未配置时补，避免覆盖调优结果。
            //    penalty_window 很关键——引擎的 stepPenalty 惩罚的是 mContext->history_tokens，
            //    不限窗会把 system prompt 里的人设词一起惩罚掉，反而伤害正常表达。
            if (!modelConfig.has("penalty_window")) {
                configs += """{"penalty_window":${tuning.penaltyWindow}}"""
            }
            // ⚠️ 键名是 "n_gram" 不是 "ngram"——引擎侧 ngram() 读的是 config_["n_gram"]
            //    （llmconfig.hpp:495-497），写错键会被静默忽略。
            if (!modelConfig.has("n_gram")) {
                configs += """{"n_gram":${tuning.nGram}}"""
            }
            if (!modelConfig.has("ngram_factor")) {
                // ngram_factor > 1 才会启用 n-gram 加权惩罚（sampler.cpp:277）：
                // 一旦尾部 n-gram 完整命中，该 token 惩罚直接拉到 max_penalty，等效于禁掉死循环。
                configs += """{"ngram_factor":${tuning.nGramFactor}}"""
            }

            // 4) 可选覆盖项：默认 null = 完全沿用模型自带配置。
            tuning.temperature?.let { configs += """{"temperature":$it}""" }
            tuning.topK?.let { configs += """{"topK":$it}""" }
            tuning.topP?.let { configs += """{"topP":$it}""" }

            return configs
        }
    }
    
    @Volatile
    private var released = false

    /**
     * 流式复读兜底检测配置。可运行时调整；设 `enabled = false` 可关闭这道防线。
     * 详见 [RepetitionGuard]。
     */
    @Volatile
    var repetitionGuardConfig: RepetitionGuard.Config = RepetitionGuard.Config()

    /**
     * 复读兜底触发时的诊断回调，供上层写入自有日志（设备上无 adb 时的唯一取证途径）。
     */
    @Volatile
    var onDegeneration: ((RepetitionGuard.Detection) -> Unit)? = null

    /**
     * 最近一次流式生成的复读命中详情；未触发为 null。每次 generate 开始时重置。
     * 上层可据此裁剪退化尾巴（见 [RepetitionGuard.trimDegenerateTail]）。
     */
    @Volatile
    var lastDegeneration: RepetitionGuard.Detection? = null
        private set

    private val lock = Any()

    private var activeCalls = 0

    private inline fun <T> withActiveCall(block: (Long) -> T): T {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            activeCalls += 1
            ptr = llmPtr
        }

        try {
            return block(ptr)
        } finally {
            synchronized(lock) {
                activeCalls -= 1
                (lock as java.lang.Object).notifyAll()
            }
        }
    }
    
    /**
     * 检查会话是否有效
     */
    private fun checkValid() {
        if (released || llmPtr == 0L) {
            throw RuntimeException("LLM session has been released")
        }
    }
    
    /**
     * 将文本编码为 token IDs
     */
    fun tokenize(text: String): IntArray {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeTokenize(ptr, text)
                ?: throw RuntimeException("Tokenization failed")
        }
    }
    
    /**
     * 将 token ID 解码为文本
     */
    fun detokenize(token: Int): String {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeDetokenize(ptr, token)
                ?: throw RuntimeException("Detokenization failed")
        }
    }

    fun countTokens(text: String): Int {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeCountTokens(ptr, text)
        }
    }

    fun countTokensWithHistory(history: List<Pair<String, String>>): Int {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeCountTokensWithHistory(ptr, history)
        }
    }

    fun countTokensStructured(messagesJson: String, toolsJson: String? = null): Int {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeCountTokensWithStructuredMessages(ptr, messagesJson, toolsJson)
        }
    }

    /**
     * 导出当前生效的配置。
     */
    fun dumpConfig(): String {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeDumpConfig(ptr)
                ?: throw RuntimeException("Dump config failed")
        }
    }

    /**
     * 获取最近一次推理的上下文统计。
     * 在尚未执行推理时可能返回 null。
     */
    fun getContextInfo(): MNNLlmContextInfo? {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeGetContextInfo(ptr)?.let(MNNLlmContextInfo::fromJson)
        }
    }
    
    /**
     * 应用聊天模板
     */
    fun applyChatTemplate(userContent: String): String {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeApplyChatTemplate(ptr, userContent)
                ?: userContent
        }
    }

    /**
     * 对完整历史应用聊天模板。
     */
    fun applyChatTemplate(history: List<Pair<String, String>>): String {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeApplyChatTemplateWithHistory(ptr, history)
                ?: throw RuntimeException("Apply chat template with history failed")
        }
    }

    fun applyChatTemplateStructured(messagesJson: String, toolsJson: String? = null): String {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeApplyChatTemplateWithStructuredMessages(ptr, messagesJson, toolsJson)
                ?: throw RuntimeException("Apply chat template with structured messages failed")
        }
    }
    
    /**
     * 非流式生成
     *
     * 同样受复读兜底保护，但保护方式与流式不同：JNI 侧的 `nativeGenerate`
     * （mnnllmnative.cpp:545-576）**完全忽略** callback 参数，直接
     * `llm->response(inputTokens, &outputStream, nullptr, maxTokens)` 同步跑完再返回整段文本，
     * 没有任何可供中断的回调通道。因此这里只能**事后裁剪**：拿到完整文本后跑一遍
     * [RepetitionGuard]，命中则用 [RepetitionGuard.trimDegenerateTail] 砍掉退化尾巴。
     * 省不了推理耗时，但至少不会把满屏复读交给上层。
     *
     * @param prompt 输入提示
     * @param maxTokens 最大生成 token 数（-1 表示使用默认值）
     * @return 生成的文本（若检出复读退化则已裁剪）
     */
    fun generate(prompt: String, maxTokens: Int = -1): String {
        lastDegeneration = null
        val raw = withActiveCall { ptr ->
            MNNLlmNative.nativeGenerate(ptr, prompt, maxTokens, null)
                ?: throw RuntimeException("Generation failed")
        }
        return trimIfDegenerate(raw)
    }

    /**
     * 对**已生成完毕**的整段文本做一次复读兜底判定并裁剪。
     *
     * 与流式路径共用同一份 [repetitionGuardConfig] 与 [onDegeneration] 诊断回调，
     * 保证两条路径的判定口径一致。
     *
     * 注意 [RepetitionGuard.detect] 只扫描尾部窗口，所以只能识别「结尾处的复读」——
     * 这正是退化态的形态（一旦进入退化就再也吐不出 EOS，会一直复读到 maxTokens）。
     *
     * @param raw 引擎返回的完整文本。
     * @return 未命中时原样返回；命中时返回裁剪后的文本。
     */
    private fun trimIfDegenerate(raw: String): String {
        val guard = RepetitionGuard(repetitionGuardConfig)
        if (guard.accept(raw)) return raw

        val hit = guard.detection ?: return raw
        lastDegeneration = hit
        Log.w(
            TAG,
            "Repetition guard tripped (non-streaming): phrase=\"${hit.phrase}\" x${hit.repeats} " +
                "in ${hit.totalChars} chars, trimming degenerate tail"
        )
        runCatching { onDegeneration?.invoke(hit) }
            .onFailure { Log.e(TAG, "onDegeneration hook failed", it) }
        return RepetitionGuard.trimDegenerateTail(raw, hit)
    }

    
    /**
     * 流式生成（带历史记录）
     * @param history 对话历史 (Pair<role, content>)
     * @param maxTokens 最大生成 token 数（-1 表示使用默认值）
     * @param onToken 每个 token 的回调，返回 false 可以停止生成
     * @return 是否成功
     */
    fun generateStream(
        history: List<Pair<String, String>>,
        maxTokens: Int = -1,
        onToken: (String) -> Boolean
    ): Boolean {
        val callback = guardedCallback("token callback", onToken)

        return withActiveCall { ptr ->
            MNNLlmNative.nativeGenerateStream(ptr, history, maxTokens, callback)
        }
    }

    fun generateStreamStructured(
        messagesJson: String,
        toolsJson: String? = null,
        maxTokens: Int = -1,
        onToken: (String) -> Boolean
    ): Boolean {
        val callback = guardedCallback("structured token callback", onToken)

        return withActiveCall { ptr ->
            MNNLlmNative.nativeGenerateStreamStructured(ptr, messagesJson, toolsJson, maxTokens, callback)
        }
    }

    /**
     * 把上层回调包一层复读兜底检测。
     *
     * 返回 false 会让 JNI 侧的 `CallbackStream` 置位 `shouldStop`
     * （mnnllmnative.cpp:919-921），进而让 `runStreamGenerationWithHistory` 的解码
     * 主循环（mnnllmnative.cpp:1027）立即退出——这是一条已存在的、可靠的中断通道。
     *
     * 注意：**先把增量投递给上层再判定**，保证已生成的文本不会丢，UI 表现为「说到一半停住」
     * 而不是整段消失。
     *
     * @param label 出错时的日志标签。
     * @param onToken 上层的增量回调，返回 false 表示上层主动要求停止。
     * @return 可直接交给 JNI 的 [MNNLlmNative.GenerationCallback]。
     */
    private fun guardedCallback(
        label: String,
        onToken: (String) -> Boolean
    ): MNNLlmNative.GenerationCallback {
        lastDegeneration = null
        val guard = RepetitionGuard(repetitionGuardConfig)
        return object : MNNLlmNative.GenerationCallback {
            override fun onToken(token: String): Boolean {
                return try {
                    // 1) 先投递，保住已生成内容。
                    if (!onToken(token)) return false
                    // 2) 再做退化判定。
                    if (guard.accept(token)) return true

                    val hit = guard.detection ?: return true
                    lastDegeneration = hit
                    Log.w(
                        TAG,
                        "Repetition guard tripped: phrase=\"${hit.phrase}\" x${hit.repeats} " +
                            "after ${hit.totalChars} chars, aborting generation"
                    )
                    runCatching { onDegeneration?.invoke(hit) }
                        .onFailure { Log.e(TAG, "onDegeneration hook failed", it) }
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "Error in $label", e)
                    false
                }
            }
        }
    }
    
    /**
     * 聊天生成（应用模板后生成）
     * @param userContent 用户输入
     * @param maxTokens 最大生成 token 数
     * @param onToken 流式回调
     * @return 是否成功
     */
    fun chat(
        userContent: String,
        maxTokens: Int = -1,
        onToken: (String) -> Boolean
    ): Boolean {
        // 将单个用户消息转换为历史记录格式
        val history = listOf("user" to userContent)
        return generateStream(history, maxTokens, onToken)
    }
    
    /**
     * 重置会话（清除历史和 KV-Cache）
     */
    fun reset() {
        withActiveCall { ptr ->
            MNNLlmNative.nativeReset(ptr)
            Log.d(TAG, "Session reset")
        }
    }
    
    /**
     * 取消当前的生成任务
     * 这会立即中断正在进行的推理过程
     */
    fun cancel() {
        val ptr = synchronized(lock) {
            if (released || llmPtr == 0L) {
                return
            }
            llmPtr
        }
        MNNLlmNative.nativeCancel(ptr)
        Log.d(TAG, "Session cancelled")
    }
    
    /**
     * 设置 LLM 配置
     * @param configJson JSON 格式的配置字符串
     * @return 是否设置成功
     */
    fun setConfig(configJson: String): Boolean {
        return withActiveCall { ptr ->
            val success = MNNLlmNative.nativeSetConfig(ptr, configJson)
            if (success) {
                Log.d(TAG, "Config set successfully: $configJson")
            } else {
                Log.e(TAG, "Failed to set config: $configJson")
            }
            success
        }
    }

    /**
     * 更新 max_new_tokens 配置。
     */
    fun setMaxNewTokens(maxNewTokens: Int): Boolean {
        return setConfig("""{"max_new_tokens":$maxNewTokens}""")
    }

    /**
     * 更新 system_prompt 配置。
     */
    fun setSystemPrompt(systemPrompt: String): Boolean {
        return setConfig("""{"system_prompt":${JSONObject.quote(systemPrompt)}}""")
    }

    /**
     * 更新 assistant_prompt_template 配置。
     */
    fun setAssistantPromptTemplate(template: String): Boolean {
        return setConfig("""{"assistant_prompt_template":${JSONObject.quote(template)}}""")
    }
    
    /**
     * 启用或禁用 thinking 模式（仅对支持的模型有效，如 Qwen3）
     * @param enabled 是否启用 thinking 模式
     * @return 是否设置成功
     */
    fun setThinkingMode(enabled: Boolean): Boolean {
        val configJson = """
        {
            "jinja": {
                "context": {
                    "enable_thinking": $enabled
                }
            }
        }
        """.trimIndent()
        return setConfig(configJson)
    }

    /**
     * 注册或清除音频波形回调。
     */
    fun setAudioDataCallback(callback: MNNLlmNative.AudioDataCallback?): Boolean {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeSetAudioDataCallback(ptr, callback)
        }
    }

    /**
     * 触发语音波形生成。
     */
    fun generateWavform(): Boolean {
        return withActiveCall { ptr ->
            MNNLlmNative.nativeGenerateWavform(ptr)
        }
    }
    
    /**
     * 释放会话
     */
    fun release() {
        val ptr = synchronized(lock) {
            if (released || llmPtr == 0L) {
                return
            }
            released = true
            val old = llmPtr
            llmPtr = 0L
            old
        }

        MNNLlmNative.nativeCancel(ptr)

        synchronized(lock) {
            while (activeCalls > 0) {
                try {
                    (lock as java.lang.Object).wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        MNNLlmNative.nativeReleaseLlm(ptr)
        Log.d(TAG, "Session released")
    }
    
    /**
     * 获取模型路径
     */
    fun getModelPath(): String = modelPath
    
    /**
     * 检查会话是否已释放
     */
    fun isReleased(): Boolean = released
    
    protected fun finalize() {
        release()
    }
}

