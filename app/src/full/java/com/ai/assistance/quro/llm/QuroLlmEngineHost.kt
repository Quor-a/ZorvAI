package com.ai.assistance.quro.llm

import android.os.SystemClock
import android.util.Log
import com.ai.assistance.llama.LlamaNative
import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.mnn.MNNLlmSession
import com.ai.assistance.mnn.MnnSamplerTuning
import com.ai.assistance.quro.core.network.QuroLocalEnginePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * :llm 进程内的引擎宿主 —— L1 编排层在服务端的落点。
 *
 * 职责边界（刻意收窄，别往里塞东西）：
 *   - 只做「会话注册表 + 串行化 + 取消 + 生命周期」。
 *   - **不做** prompt 组装、工具调用编排、记忆拼装 —— 那些是主进程的事，
 *     而且它们要在进程隔离下保持可热更新（改一行逻辑不用重启引擎进程）。
 *
 * 三条不可违反的约束：
 *
 * 1) **同一会话同时只跑一次生成**。
 *    引擎的 KV cache 是单份可变状态，两个线程同时 prefill/decode 是数据竞争，
 *    不是「结果混乱」而是**堆损坏**。用每会话单线程 Executor 从结构上排除，
 *    不靠调用方自觉。
 *
 * 2) **同一时刻只让一个引擎持有大模型权重**（硬规则第 3 条）。
 *    两个 2–3GB 的权重同驻，中端机必被 LMK 带走。切换引擎时先卸载旧的，
 *    且必须先真正释放（release 内部会等在飞调用退出）再加载新的。
 *
 * 3) **cancel 必须能被任意线程立刻打断推理**。
 *    不是设标志位等它自然结束 —— 一次 decode 可能还要跑几十秒。
 *    这里走引擎自己的 cancel()（原生侧是 abort 标志 + 立即返回），
 *    回调侧再用 [GenRun.cancelled] 做二次拦截，保证回调不会再往外吐。
 */
internal class QuroLlmEngineHost private constructor() {

    companion object {
        private const val TAG = "QuroLlm.Host"

        /** 关会话时等待在飞生成退出的上限。超时也强行释放，避免界面永久卡住。 */
        private const val CLOSE_GRACE_MS = 5_000L

        @Volatile
        private var INSTANCE: QuroLlmEngineHost? = null

        fun instance(): QuroLlmEngineHost = INSTANCE ?: synchronized(this) {
            INSTANCE ?: QuroLlmEngineHost().also { INSTANCE = it }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 绑定：一次 openSession 产出一条绑定（一个模型 = 一个引擎实例）
    // ─────────────────────────────────────────────────────────────────────
    // sealed 而非普通 interface：下面 doGenerate / lastErrorOf 里的 when 需要
    // 编译器保证「新增引擎实现时必须处理」——普通 interface 的 when 不穷尽会
    // 直接编译失败或静默走 else，两种都比 sealed 差。
    private sealed interface Bind {
        val engineName: String
        val modelPath: String
        /** 权重占用估算，用于内存预算判断与 UI 展示。 */
        fun weightBytes(): Long
        /** 实际生效的后端描述（JSON），给 UI 显示「到底跑在什么上面」。 */
        fun backendJson(): String
        /** 立即中断当前推理。任意线程可调。 */
        fun cancel()
        /** 释放权重，返回是否干净释放。 */
        fun release(): Boolean
    }

    private class LlamaBind(
        val session: LlamaSession,
        override val modelPath: String,
        val nGpuLayers: Int,
    ) : Bind {
        override val engineName = "llama.cpp"
        override fun weightBytes(): Long = runCatching { File(modelPath).length() }.getOrDefault(0L)
        override fun backendJson(): String = JSONObject()
            .put("engine", engineName)
            .put("prefill", if (nGpuLayers > 0) "GPU" else "CPU")
            .put("decode", "CPU")
            .put("gpuLayers", nGpuLayers)
            .toString()

        override fun cancel() = runCatching { session.cancel() }.let { }
        override fun release(): Boolean = runCatching {
            session.release(); true
        }.getOrDefault(false)
    }

    private class MnnBind(
        val session: MNNLlmSession,
        override val modelPath: String,
        val backend: String,
        val weightBytesCached: Long,
    ) : Bind {
        override val engineName = "MNN"
        override fun weightBytes(): Long = weightBytesCached
        override fun backendJson(): String = JSONObject()
            .put("engine", engineName)
            .put("backend", backend)
            .toString()

        override fun cancel() = runCatching { session.cancel() }.let { }
        override fun release(): Boolean = runCatching {
            session.release(); true
        }.getOrDefault(false)
    }

    /**
     * 一次绑定的运行态：会话 id、串行执行器、当前在飞的请求。
     * 一个 Bind 对应一个 GenRun 容器（而不是把状态塞进 Session 本身），
     * 这样 release 时只要丢掉整个容器，不会留下半截状态。
     */
    private class GenRun(val bind: Bind) {
        val id: Long = SEQ.incrementAndGet()
        /** 单线程：从结构上排除「同会话并发推理」这类堆损坏。 */
        val worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "quro-llm-gen-${id}").apply { isDaemon = true }
        }
        val activeReq = AtomicLong(0L)
        val cancelled = AtomicBoolean(false)
        val closed = AtomicBoolean(false)

        companion object {
            private val SEQ = AtomicLong(1000L)
        }
    }

    private val runs = ConcurrentHashMap<Long, GenRun>()

    // ─────────────────────────────────────────────────────────────────────
    // 会话生命周期
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 打开一个会话。**阻塞**，可能几十秒（大模型加载）。调用方负责加超时。
     *
     * 返回 JSON 信封而不是抛异常：跨 Binder 传自定义异常会丢错误码
     * （详见 IQuroLlmService.openSession 的注释）。
     *   成功 → {"ok":true,"sessionId":N,"engine":"…","backend":{…}}
     *   失败 → {"ok":false,"code":N,"message":"…"}
     */
    @Synchronized
    fun openSession(modelPath: String, optionsJson: String): String {
        val opts = runCatching { JSONObject(optionsJson) }.getOrDefault(JSONObject())
        val threads = opts.optInt("threads", 4).coerceIn(1, 16)
        val ctxSize = opts.optInt("contextSize", 2048)

        val file = File(modelPath)
        if (!file.exists()) {
            return failure(QuroLlmError.MODEL_NOT_FOUND, "模型不存在：$modelPath")
        }

        val isDir = file.isDirectory
        val hasMnnConfig = isDir && File(file, "llm_config.json").exists()
        val isGguf = file.isFile && modelPath.endsWith(".gguf", ignoreCase = true)

        val bind: Bind = try {
            when {
                isGguf -> createLlama(file, threads, ctxSize, opts)
                hasMnnConfig -> createMnn(file, threads, opts)
                isDir -> return failure(
                    QuroLlmError.MODEL_INCOMPLETE,
                    "目录里没有 llm_config.json，不是 MNN LLM 模型目录：$modelPath"
                )
                else -> return failure(
                    QuroLlmError.MODEL_FORMAT_UNSUPPORTED,
                    "无法识别的模型格式（既不是 .gguf，也不是含 llm_config.json 的目录）：$modelPath"
                )
            }
        } catch (f: LlmFailure) {
            Log.w(TAG, "openSession 失败 code=${f.code} msg=${f.message}")
            return failure(f.code, f.message)
        } catch (t: Throwable) {
            // 加载期最容易出的是 OutOfMemoryError（大模型 + 低端机）。
            // 必须在这里就变成可读错误 —— 让它冒泡出去，主进程看到的是
            // 「推理进程已死」，完全无法排障。
            Log.e(TAG, "openSession 未预期异常", t)
            return failure(
                QuroLlmError.LOAD_FAILED,
                "模型加载异常：${t.javaClass.simpleName}: ${t.message}"
            )
        }

        // 硬规则第 3 条：加载新权重要先把旧权重真正释放掉。
        // 顺序很关键 —— 必须先 evict 再 put，否则两个权重会同时驻留。
        evictOthersExcept(bind.engineName)

        val run = GenRun(bind)
        runs[run.id] = run
        Log.i(TAG, "openSession ok id=${run.id} engine=${bind.engineName} path=$modelPath")
        return JSONObject()
            .put("ok", true)
            .put("sessionId", run.id)
            .put("engine", bind.engineName)
            .put("backend", bind.backendJson())
            .toString()
    }

    private fun failure(code: Int, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()

    fun closeSession(sessionId: Long) {
        val run = runs.remove(sessionId) ?: return
        run.closed.set(true)
        run.cancelled.set(true)
        runCatching { run.bind.cancel() }
        // 等在飞生成退出再释放：引擎的 release 内部有自保，但先让生成线程
        // 走到 cancel 分支能少一次「释放与在飞调用竞争」的窗口。
        run.worker.shutdown()
        runCatching { run.worker.awaitTermination(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS) }
        val ok = run.bind.release()
        Log.i(TAG, "closeSession id=$sessionId released=$ok")
    }

    fun sessionLoaded(sessionId: Long): Boolean = runs.containsKey(sessionId)

    /** 卸载所有会话（shutdown / 换引擎用）。 */
    @Synchronized
    fun closeAll() {
        runs.keys.toList().forEach { closeSession(it) }
    }

    /** 当前是否已有某个引擎持有权重，返回引擎名；null 表示都空着。 */
    private fun loadedEngine(): String? = runs.values.firstOrNull()?.bind?.engineName

    private fun evictOthersExcept(engineName: String) {
        val stale = runs.filterValues { it.bind.engineName != engineName }.keys.toList()
        if (stale.isNotEmpty()) {
            Log.i(TAG, "内存互斥：卸载 ${stale.size} 个旧绑定（要加载 $engineName）")
            stale.forEach { closeSession(it) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 生成
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 提交一次生成。立即返回 —— 真正的推理在会话自己的线程上跑，
     * 结果经 [sink] 回流。这个「立即返回」是 cancel 能及时生效的前提。
     */
    fun generate(sessionId: Long, reqId: Long, requestJson: String, sink: Sink) {
        val run = runs[sessionId]
        if (run == null) {
            sink.error(reqId, QuroLlmError.SESSION_NOT_FOUND, "会话不存在或已关闭：$sessionId")
            return
        }
        val req = runCatching { JSONObject(requestJson) }
            .getOrElse {
                sink.error(reqId, QuroLlmError.BAD_ARGUMENT, "requestJson 不是合法 JSON：${it.message}")
                return
            }

        // 同会话并发保护：CAS 失败说明已有在飞请求。
        if (!run.activeReq.compareAndSet(0L, reqId)) {
            sink.error(
                reqId, QuroLlmError.SESSION_BUSY,
                "该会话已有进行中的生成（reqId=${run.activeReq.get()}）"
            )
            return
        }
        run.cancelled.set(false)

        run.worker.execute {
            val started = SystemClock.elapsedRealtime()
            try {
                if (run.closed.get()) {
                    sink.error(reqId, QuroLlmError.CANCELLED, "会话已关闭")
                    return@execute
                }
                sink.ready(reqId, run.bind.engineName, run.bind.backendJson())
                doGenerate(run, reqId, req, sink, started)
            } catch (t: Throwable) {
                Log.e(TAG, "generate 异常 reqId=$reqId", t)
                sink.error(reqId, QuroLlmError.GENERATE_FAILED, t.message ?: t.javaClass.simpleName)
            } finally {
                run.activeReq.compareAndSet(reqId, 0L)
            }
        }
    }

    private fun doGenerate(run: GenRun, reqId: Long, req: JSONObject, sink: Sink, startedMs: Long) {
        val bind = run.bind
        val maxTokens = req.optInt("maxTokens", 512)
        val messages = req.optJSONArray("messages")
        val prompt = req.optString("prompt", "")
        val tools = req.optString("tools", "")
        var emittedAny = false

        val onToken: (String) -> Boolean = { text ->
            // 双重拦截：引擎的 cancel 已经打断原生循环，这里再挡一次，
            // 避免「取消后还往外吐了最后几个 token」这种观感问题。
            if (run.cancelled.get()) {
                false
            } else {
                emittedAny = true
                sink.token(reqId, text)
                !run.cancelled.get()
            }
        }

        val ok: Boolean = when (bind) {
            is LlamaBind -> {
                applyLlamaSampling(bind.session, req)
                bind.session.generateStream(
                    prompt = prompt,
                    maxTokens = maxTokens,
                    onProgress = { _, done, total -> sink.prefill(reqId, done, total) },
                    onToken = onToken
                )
            }

            is MnnBind -> {
                applyMnnSampling(bind.session, req)
                if (messages != null && messages.length() > 0) {
                    // 结构化路径：由原生层套模型自带 chat_template。
                    bind.session.generateStreamStructured(
                        messagesJson = messages.toString(),
                        toolsJson = tools.takeIf { it.isNotBlank() },
                        maxTokens = maxTokens,
                        onToken = onToken
                    )
                } else {
                    // 没有结构化消息时退化为单轮。注意 MNN 的流式接口只接 history，
                    // 会再套一次模板 —— 这是引擎既定行为，不是这里漏了处理。
                    bind.session.generateStream(
                        history = listOf("user" to prompt),
                        maxTokens = maxTokens,
                        onToken = onToken
                    )
                }
            }
        }

        val elapsed = SystemClock.elapsedRealtime() - startedMs
        when {
            run.cancelled.get() -> sink.done(reqId, "cancel", statsJson(bind, elapsed, 0))
            !ok -> sink.error(reqId, QuroLlmError.GENERATE_FAILED, lastErrorOf(bind))
            !emittedAny -> sink.error(reqId, QuroLlmError.EMPTY_OUTPUT, "推理未产生任何输出")
            else -> sink.done(reqId, "stop", statsJson(bind, elapsed, 0))
        }
    }

    private fun lastErrorOf(bind: Bind): String = when (bind) {
        is LlamaBind -> bind.session.lastError() ?: "llama.cpp 未给出原因"
        is MnnBind -> "MNN 推理失败（详见 logcat 的 MNNLlmSession 标签）"
    }

    private fun applyLlamaSampling(session: LlamaSession, req: JSONObject) {
        runCatching {
            session.setSamplingParams(
                temperature = req.optDouble("temperature", 0.7).toFloat(),
                topP = req.optDouble("topP", 0.9).toFloat(),
                topK = req.optInt("topK", 40),
                repetitionPenalty = req.optDouble("repetitionPenalty", 1.1).toFloat(),
                frequencyPenalty = req.optDouble("frequencyPenalty", 0.0).toFloat(),
                presencePenalty = req.optDouble("presencePenalty", 0.0).toFloat(),
                penaltyLastN = req.optInt("penaltyLastN", 64)
            )
        }.onFailure { Log.w(TAG, "setSamplingParams 失败（沿用引擎默认值）", it) }
    }

    private fun applyMnnSampling(session: MNNLlmSession, req: JSONObject) {
        // MNN 的采样参数走 config JSON（原生层解析），字段名与 llm_config.json 一致。
        val cfg = JSONObject()
            .put("temperature", req.optDouble("temperature", 0.7))
            .put("topP", req.optDouble("topP", 0.9))
            .put("topK", req.optInt("topK", 40))
        runCatching { session.setConfig(cfg.toString()) }
            .onFailure { Log.w(TAG, "MNN setConfig 失败（沿用模型自带配置）", it) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 取消
    // ─────────────────────────────────────────────────────────────────────

    /** 任意线程可调；命中后立刻让在飞推理返回（不是等它跑完）。 */
    fun cancel(reqId: Long) {
        runs.values.firstOrNull { it.activeReq.get() == reqId }?.let { run ->
            run.cancelled.set(true)
            runCatching { run.bind.cancel() }
            Log.i(TAG, "cancel reqId=$reqId 已下发到 ${run.bind.engineName}")
        }
    }

    fun cancelAll(sessionId: Long) {
        runs[sessionId]?.let { run ->
            run.cancelled.set(true)
            runCatching { run.bind.cancel() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 可观测性
    // ─────────────────────────────────────────────────────────────────────

    fun stats(sessionId: Long): String {
        val run = runs[sessionId] ?: return JSONObject()
            .put("loaded", false)
            .put("reason", "会话不存在")
            .toString()
        return JSONObject()
            .put("loaded", true)
            .put("sessionId", sessionId)
            .put("engine", run.bind.engineName)
            .put("modelPath", run.bind.modelPath)
            .put("weightBytes", run.bind.weightBytes())
            .put("backend", run.bind.backendJson())
            .put("activeReq", run.activeReq.get())
            .toString()
    }

    fun diagnostics(): String = JSONObject()
        .put("sessions", runs.size)
        .put("loadedEngine", loadedEngine() ?: JSONObject.NULL)
        .put("llamaAvailable", LlamaNative.nativeIsAvailable())
        .put("llamaWhyNot", LlamaNative.nativeGetUnavailableReason())
        .put("hostingModels", JSONArray(runs.values.map { it.bind.modelPath }))
        .toString()

    // ─────────────────────────────────────────────────────────────────────
    // 引擎构造
    // ─────────────────────────────────────────────────────────────────────

    private fun createLlama(file: File, threads: Int, ctxSize: Int, opts: JSONObject): Bind {
        if (!LlamaSession.isAvailable()) {
            throw LlmFailure(
                QuroLlmError.ENGINE_UNAVAILABLE,
                "llama.cpp 不可用：${LlamaSession.getUnavailableReason()}"
            )
        }
        val nGpuLayers = opts.optInt("gpuLayers", 0)
        val cfg = LlamaSession.Config(
            nThreads = threads,
            nCtx = ctxSize,
            nBatch = opts.optInt("batchSize", 512),
            nUBatch = opts.optInt("ubatchSize", 512),
            nGpuLayers = nGpuLayers,
            // 这两个默认值不能动：以前反了，直接导致「一直卡在模型加载」。
            // 见 LlamaSession.Config 上的长注释。
            useMmap = opts.optBoolean("useMmap", false),
            flashAttention = opts.optBoolean("flashAttention", false),
            kvUnified = opts.optBoolean("kvUnified", true),
            offloadKqv = opts.optBoolean("offloadKqv", false),
            // L5 · 温控自适应：0 = 关闭。这条路径跑在 `:llm` 子进程，
            // 无 Context 可传 —— QuroLocalEnginePrefs 内部走 QuroApplication.appCtx。
            thermalPollMs = QuroLocalEnginePrefs.thermalPollMs()
        )
        val session = LlamaSession.create(file.absolutePath, cfg)
            ?: throw LlmFailure(
                QuroLlmError.LOAD_FAILED,
                "llama.cpp 会话创建失败：${LlamaSession.getUnavailableReason()}"
            )
        return LlamaBind(session, file.absolutePath, nGpuLayers)
    }

    private fun createMnn(dir: File, threads: Int, opts: JSONObject): Bind {
        val backend = opts.optString("mnnBackend", "cpu")
        val precision = opts.optString("precision", "low")
        val memory = opts.optString("memory", "low")
        val sampler = MnnSamplerTuning(
            enabled = opts.optBoolean("samplerEnabled", true),
            repetitionPenalty = opts.optDouble("repetitionPenalty", 1.1).toFloat(),
            temperature = if (opts.has("temperature")) opts.optDouble("temperature", 0.7).toFloat() else null,
            topK = if (opts.has("topK")) opts.optInt("topK", 40) else null,
            topP = if (opts.has("topP")) opts.optDouble("topP", 0.9).toFloat() else null
        )
        val session = MNNLlmSession.create(
            modelDir = dir.absolutePath,
            backendType = backend,
            threadNum = threads,
            precision = precision,
            memory = memory,
            tmpPath = opts.optString("cachePath").takeIf { it.isNotBlank() },
            sampler = sampler
        ) ?: throw LlmFailure(
            QuroLlmError.LOAD_FAILED,
            "MNN 会话创建失败（目录 ${dir.absolutePath}，backend=$backend，precision=$precision）"
        )
        // MNN 一个模型目录里权重分散在多个文件，逐个累加得到权重占用估算。
        val bytes = dir.walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .sumOf { it.length() }
        return MnnBind(session, dir.absolutePath, backend, bytes)
    }

    private fun statsJson(bind: Bind, elapsedMs: Long, tokens: Int): String = JSONObject()
        .put("elapsedMs", elapsedMs)
        .put("tokens", tokens)
        .put("engine", bind.engineName)
        .put("weightBytes", bind.weightBytes())
        .toString()

    /**
     * 回调出口。把「往 Binder 另一头发」这件事抽出来，宿主就不需要知道
     * AIDL 的存在，MNN/llama 两条路径也能共用同一套终态语义。
     */
    internal interface Sink {
        fun ready(reqId: Long, engineName: String, backendJson: String)
        fun prefill(reqId: Long, done: Int, total: Int)
        fun token(reqId: Long, text: String)
        /**
         * 思考段累计文本（`<think>…</think>` 内的内容）。
         *
         * 仅 execLocal 通道会产生它：抽象路径（generate）的引擎回调目前不区分思考段，
         * 思考文本混在 token 流里由主进程一侧剥离。
         */
        fun thinking(reqId: Long, text: String)
        fun done(reqId: Long, finishReason: String, statsJson: String)
        fun error(reqId: Long, code: Int, message: String)
    }
}

/** 带错误码的失败。AIDL 层会把它翻成 [android.os.ServiceSpecificException]。 */
internal class LlmFailure(val code: Int, override val message: String) : RuntimeException(message)
