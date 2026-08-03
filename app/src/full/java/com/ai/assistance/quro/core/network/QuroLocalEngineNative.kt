package com.ai.assistance.quro.core.network

import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.mnn.MNNLlmSession
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.util.QuroDiag
import java.io.File

/**
 * 原生本地推理引擎（full 风味专用实现）。
 *
 * 职责：直接驱动移植进来的 MNN / llama.cpp JNI 会话（[MNNLlmSession] / [LlamaSession]），
 * 把 QuroAI 的 [QuroChatMessage] 多轮历史映射为原生输入，把原生 token 流累积为
 * [QuroLlmResult.Text]，而不引入 operit 的 AIService / PromptTurn / Stream 那套 chat 子系统。
 *
 * 风味隔离约束：本文件位于 `app/src/full/java` 风味源码集，仅 `full` 风味可编译
 * （因为 `:mnn` / `:llama` 仅 `fullImplementation`）。[QuroAssistant.routeLocal] 在 `main`
 * 源码集通过反射实例化本类；`fdroid` 风味因 classpath 不含本类，反射失败回退
 * [QuroLocalEnginePlaceholder]。
 *
 * 会话复用（#1114）：若用户已通过 [LocalModelSessionHolder] 显式加载某个本地模型，
 * [run] 会复用常驻会话（跳过每次重建 GGUF 的秒级开销）；否则走原有"每条消息重建会话"的
 * 兜底路径（正确性优先，性能略差）。两种路径共用 [generateLlama] / [generateMnn] 的生成逻辑。
 *
 * 已知局限（Phase 2 范围外，留待后续优化）：
 * - MNN 的 [MNNLlmSession.create] 不接收 temperature，MNN 采样温度暂未接入。
 * - 工具调用（grammar）走 llama 的 applyStructuredChatTemplate，归 Phase 3 流式/工具链接入。
 */
class QuroLocalEngineNative : QuroLocalEngine {

    override fun run(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        contextWindow: Int,
        onToken: ((String) -> Unit)?,
    ): QuroLlmResult {
        // 🛡️ #1114 聊天门禁：本地模型必须先经「模型配置」显式加载进内存（常驻会话），
        // 才能对话。否则会退化为「每条消息重建 GGUF / MNN 会话」——手机上要几十秒到数分钟，
        // 表现就是用户截图里那种永久转圈的「⏳ 正在加载本地模型并处理上下文…」。
        // 这里直接返回明确错误，让聊天页弹出提示气泡，而不是干等一个拿不到的回复。
        // （fdroid 风味未编译本类，routeLocal 回退 Placeholder，不会走到这里；full 风味
        //  LocalModelLoaders.get() 返回 LocalModelSessionHolder，未加载时即门禁拦截。）
        val holder = LocalModelLoaders.get() as? LocalModelSessionHolder
        if (holder != null && !holder.isLoaded(model)) {
            val msg = "请先在「模型配置」中加载该本地模型后再对话（设置 → 模型配置 → 选择模型 → 点「加载」）。"
            QuroDiag.log(
                "LocalEngine",
                "✗ run gated | 模型未加载 | type=${model.type} | id=${model.id} | name=${model.name}"
            )
            return QuroLlmResult.Error(msg)
        }

        val engineTag = when (model.type) {
            QuroLocalModelType.MNN -> "MNN"
            QuroLocalModelType.LLAMA_CPP -> "LLAMA_CPP"
        }
        val t0 = System.nanoTime()
        QuroDiag.log(
            "LocalEngine",
            "▶ run start | engine=$engineTag | path=${model.path} | modelName=$modelName | " +
                "msgs=${messages.size} | temp=$temperature | max=$maxTokens | " +
                "ctxWindow=$contextWindow | stream=${onToken != null}"
        )
        val result = when (model.type) {
            QuroLocalModelType.MNN -> runMnn(model, messages, maxTokens, onToken)
            QuroLocalModelType.LLAMA_CPP ->
                runLlama(model, modelName, messages, temperature, maxTokens, contextWindow, onToken)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        when (result) {
            is QuroLlmResult.Text -> QuroDiag.log(
                "LocalEngine",
                "✓ run done | engine=$engineTag | ${ms}ms | chars=${result.content.length} | " +
                    "preview=${result.content.take(120).replace("\n", " ")}"
            )
            is QuroLlmResult.ToolCalls -> QuroDiag.log(
                "LocalEngine",
                "✓ run done (toolcalls) | engine=$engineTag | ${ms}ms | calls=${result.calls.size}"
            )
            is QuroLlmResult.Error -> QuroDiag.log(
                "LocalEngine",
                "✗ run error | engine=$engineTag | ${ms}ms | ${result.message}"
            )
        }
        return result
    }

    // ------------------------------------------------------------------------------------------
    // MNN
    // ------------------------------------------------------------------------------------------

    private fun runMnn(
        model: QuroLocalModel,
        messages: List<QuroChatMessage>,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): QuroLlmResult {
        // 优先复用常驻会话
        val held = (LocalModelLoaders.get() as? LocalModelSessionHolder)?.takeIf { it.isLoaded(model) }?.borrowMnn()
        if (held != null) {
            QuroDiag.log("LocalEngine", "▶ 复用已加载常驻 MNN 会话（跳过模型加载）")
            // 串行化常驻会话生成；结束时归还计数，unload 才能安全 free。
            genLock.lock()
            try {
                return generateMnn(held, model, messages, maxTokens, onToken)
            } finally {
                genLock.unlock()
                (LocalModelLoaders.get() as? LocalModelSessionHolder)?.returnMnn()
            }
        }

        val modelDir = resolveMnnDirStatic(model.path)
        if (modelDir == null) {
            QuroDiag.log("LocalEngine", "✗ MNN 模型路径无效 | path=${model.path}")
            return QuroLlmResult.Error(
                "MNN 模型路径无效（需指向含 llm_config.json 的模型目录，或指向该目录内的 .mnn 文件）：" +
                    "path=${model.path}"
            )
        }
        // 🛡️ Java 侧预检：MNN 目录须含可读的 llm_config.json，否则原生层大概率 abort。
        val configFile = File(modelDir, "llm_config.json")
        if (!configFile.isFile || configFile.length() <= 0L) {
            QuroDiag.log("LocalEngine", "✗ MNN 预检失败 | 缺 llm_config.json | dir=${modelDir.absolutePath}")
            return QuroLlmResult.Error(
                "MNN 模型目录缺少 llm_config.json 或文件为空：${modelDir.absolutePath}。" +
                    "请选择含 llm_config.json 的模型目录。"
            )
        }

        val session = createMnnSessionStatic(modelDir, model)
        if (session == null) {
            QuroDiag.log("LocalEngine", "✗ MNN 会话创建失败 | dir=${modelDir.absolutePath}")
            return QuroLlmResult.Error("MNN 会话创建失败（模型目录：${modelDir.absolutePath}）。请确认含 llm_config.json 且权重文件完整。")
        }

        return try {
            generateMnn(session, model, messages, maxTokens, onToken)
        } finally {
            // 不跨 run 缓存会话：每次重建以保证多轮历史正确性（KV-Cache 不会被旧上下文污染）。
            runCatching { session.release() }
        }
    }

    private fun generateMnn(
        session: MNNLlmSession,
        model: QuroLocalModel,
        messages: List<QuroChatMessage>,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): QuroLlmResult {
        return try {
            val t0 = System.nanoTime()
            var firstTokenMs: Long? = null
            var tokenCount = 0
            // MNN 的 generateStream 接收完整 (role, content) 历史并生成一次补全。
            val history = buildMnnHistory(messages)
            val sb = StringBuilder()
            val ok = session.generateStream(history, maxTokens) { token ->
                if (firstTokenMs == null) firstTokenMs = (System.nanoTime() - t0) / 1_000_000
                tokenCount++
                sb.append(token)
                // 实时把「累计文本」推给 UI（与云端 onToken 语义一致）。
                onToken?.let { cb -> runCatching { cb(sb.toString()) } }
                true // 返回 false 可中断生成；这里始终继续
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            QuroDiag.log(
                "LocalEngine",
                "MNN generate | ${ms}ms | firstToken=${firstTokenMs ?: -1}ms | tokens=$tokenCount | ok=$ok | chars=${sb.length}"
            )
            if (!ok && sb.isEmpty()) {
                QuroLlmResult.Error("MNN 推理失败（会话返回 false 且无输出）。")
            } else {
                QuroLlmResult.Text(sb.toString())
            }
        } catch (e: Throwable) {
            QuroDiag.log("LocalEngine", "✗ MNN 推理异常 | ${e.message}\n${e.stackTraceToString()}")
            QuroLlmResult.Error("MNN 推理异常：${e.message}")
        }
    }

    /**
     * 把 QuroChatMessage 历史映射为 MNN 的 List<Pair<role, content>>。
     * MNN 仅识别 user / assistant / system 三种角色；tool 角色退化为 user 拼接结果，
     * 避免传入未知角色导致原生层处理异常。
     */
    private fun buildMnnHistory(messages: List<QuroChatMessage>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (m in messages) {
            val content = m.content
            if (content.isBlank()) continue
            val role = when (m.role.lowercase()) {
                "system" -> "system"
                "assistant" -> "assistant"
                "tool" -> "user" // MNN 无 tool 角色，退化为 user
                else -> "user"
            }
            out.add(role to content)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // llama.cpp
    // ------------------------------------------------------------------------------------------

    // ⚠️ 串行化锁见 companion object 的 [genLock]——必须是**静态**锁！
    // QuroAssistant.routeLocal 每次都反射 new 一个 QuroLocalEngineNative 实例（resolveLocalEngine），
    // 实例级锁无法跨消息串行化 → 多发消息会并发 generate 同一个常驻 ctx → 堆损坏崩溃
    // （signal=11 @ ggml_vec_dot_q5_K_q8_K + signal=6 @ ggml_abort/free，与 #1114 生命周期竞态同签名）。
    // 上一版（lifecyclefix）把锁写成实例字段 → 等于没锁 → 你刚发的崩溃日志正是这个漏锁导致的。

    private fun runLlama(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        contextWindow: Int,
        onToken: ((String) -> Unit)?,
    ): QuroLlmResult {
        // 优先复用常驻会话
        val held = (LocalModelLoaders.get() as? LocalModelSessionHolder)?.takeIf { it.isLoaded(model) }?.borrowLlama()
        if (held != null) {
            QuroDiag.log("LocalEngine", "▶ 复用已加载常驻 llama 会话（跳过模型加载）")
            // 串行化常驻会话生成（防止同一 ctx 被并发 generate 踩坏）；
            // 结束时归还计数，unload 才能安全 free。
            genLock.lock()
            try {
                return generateLlama(held, model, modelName, messages, temperature, maxTokens, onToken)
            } finally {
                genLock.unlock()
                (LocalModelLoaders.get() as? LocalModelSessionHolder)?.returnLlama()
            }
        }

        val pathModel = resolveLlamaModelFileStatic(model.path, modelName)
        if (pathModel == null) {
            QuroDiag.log("LocalEngine", "✗ llama 模型文件未找到 | folder=${model.path} | modelName=$modelName")
            return QuroLlmResult.Error(
                "llama.cpp 模型文件未找到（folder=${model.path}, modelName=$modelName）。" +
                    "请确认该文件夹下存在对应 .gguf 文件。"
            )
        }
        // 🛡️ Java 侧预检：拒绝非 GGUF / 不完整的文件。脏文件直接喂给原生层会触发 C++ abort
        // （Java try/catch 与 UncaughtExceptionHandler 都抓不到原生崩溃 → 进程直接死、无日志）。
        val pre = precheckLlamaFileStatic(pathModel)
        if (pre != null) {
            QuroDiag.log("LocalEngine", "✗ llama 预检失败 | $pre")
            return QuroLlmResult.Error(pre)
        }

        // ⚠️ #1113：n_ctx 与线程数都必须按**实际负载**定，不能拍脑袋。
        //
        // 上一版把 n_ctx 一律抬到 contextWindow.coerceIn(2048, 8192) 是错的：
        // 手机端 n_ctx 开得越大，KV-Cache 内存越大（3B 模型 f16 KV @8192 ≈ 300MB）、
        // prefill 也越慢，而真正需要的上下文其实取决于这次喂进来多少字。
        // 现在上层（QuroAssistant.compactForLocal + 本地极简 system prompt）已把消息总量
        // 压到 ≤9,000 字符，绝大多数轮次只有 1,500~2,500 字符 ≈ 1,000~1,700 token。
        // 于是按实际估算开窗口，向上取整到 1024 的倍数，夹在 [2048, 8192]。
        val promptChars = messages.sumOf { it.content.length }
        // 中文约 1.5 char/token，英文约 4 char/token；取保守的 1.5 避免低估。
        val estPromptTokens = (promptChars * 2 / 3) + 64
        // 本地生成上限：手机 CPU 每 token 几十~几百毫秒，放任 4096 会跑好几分钟。
        val effMaxTokens = maxTokens.coerceIn(128, 1024)
        val needed = estPromptTokens + effMaxTokens + 256
        val nCtx = ((needed + 1023) / 1024 * 1024).coerceIn(2048, 8192)
        // 线程数：原硬编码 4，现代手机 6~8 核，prefill 是纯 CPU 密集型，提上去直接缩短首 token 时延。
        // 现在优先取用户在「模型配置」里设的值（0 = 自动 → CPU 核数夹 2..8）。
        val nThreads = model.resolveThreads()
        // 用户显式设了 contextSize 就照办（夹在合法区间），否则用上面按 prompt 估算的自适应窗口。
        val effCtx = if (model.contextSize > 0) model.contextSize.coerceIn(512, 32768) else nCtx
        val cfg = LlamaSession.Config(
            nThreads = nThreads,
            nCtx = effCtx,
                nGpuLayers = if (model.gpuLayers > 0) model.gpuLayers else 99,
                useMmap = model.useMmap,
            kvUnified = model.kvUnified,
        )
        QuroDiag.log(
            "LocalEngine",
            "▶ llama createSession | nCtx=$effCtx(auto=$nCtx) | nThreads=$nThreads | " +
                "gpuLayers=${cfg.nGpuLayers} | useMmap=${cfg.useMmap} | kvUnified=${cfg.kvUnified} | " +
                "promptChars=$promptChars | estPromptTokens=$estPromptTokens | " +
                "maxTokens=$maxTokens→$effMaxTokens | (contextWindow=$contextWindow 仅供参考)"
        )
        val session = LlamaSession.create(pathModel.absolutePath, cfg)
        if (session == null) {
            QuroDiag.log("LocalEngine", "✗ llama 会话创建失败 | file=${pathModel.absolutePath} | reason=${LlamaSession.getUnavailableReason()}")
            return QuroLlmResult.Error(
                "llama.cpp 会话创建失败（${pathModel.absolutePath}）。" +
                    "原因：${LlamaSession.getUnavailableReason()}"
            )
        }

        return try {
            generateLlama(session, model, modelName, messages, temperature, maxTokens, onToken)
        } finally {
            runCatching { session.release() }
            QuroDiag.log("LocalEngine", "✓ llama session released")
        }
    }

    private fun generateLlama(
        session: LlamaSession,
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): QuroLlmResult {
        return try {
            QuroDiag.log("LocalEngine", "▶ llama setSamplingParams start")
            // 设置采样参数（仅 temperature 来自调用方，其余取保守默认值）。
            runCatching {
                session.setSamplingParams(
                    temperature = temperature,
                    topP = 0.9f,
                    topK = 40,
                    repetitionPenalty = 1.1f,
                    frequencyPenalty = 0f,
                    presencePenalty = 0f,
                    penaltyLastN = 64,
                )
            }
            QuroDiag.log("LocalEngine", "✓ llama setSamplingParams done")

        // 用聊天模板把历史拼成单条 prompt。
        // ⚠️ 关键修复（#1112 "不回复"根因）：必须传 addAssistant = true，即 llama.cpp 的
        // add_generation_prompt = true —— 在 prompt 末尾追加 assistant 轮起始符
        // （如 <|im_start|>assistant\n），告诉模型"现在轮到你生成回复"。
        // 之前误传 false：prompt 缺少 assistant 标记，聊天模型判定对话已结束、首 token 直接吐 EOS
        // → 生成循环立即 break → sb 为空 → QuroLlmResult.Text("")（表现为"不闪退但不回复"）。
        // PocketPal 在普通聊天补全里正是用 add_generation_prompt=true，故同款 GGUF 在它那里正常。
        val roles = messages.map { normalizeRole(it.role) }
        val contents = messages.map { it.content }
        QuroDiag.log("LocalEngine", "▶ llama applyChatTemplate start | roles=$roles")
        val prompt = session.applyChatTemplate(roles, contents, addAssistant = true)
            if (prompt == null) {
                QuroDiag.log("LocalEngine", "✗ llama 应用聊天模板失败 | roles=$roles")
                return QuroLlmResult.Error("llama.cpp 应用聊天模板失败（roles=$roles）。")
            }
            QuroDiag.log("LocalEngine", "✓ llama applyChatTemplate done | promptChars=${prompt.length}")

            val effMaxTokens = maxTokens.coerceIn(128, 1024)
            QuroDiag.log("LocalEngine", "▶ llama generateStream start | maxTokens=$effMaxTokens")
            val t0 = System.nanoTime()
            var firstTokenMs: Long? = null
            var tokenCount = 0
            val sb = StringBuilder()
            val ok = session.generateStream(
                prompt,
                effMaxTokens,
                onProgress = { _, cur, total ->
                    // prefill 在手机 CPU 上常需数十秒，期间一个 token 都没有，UI 全程空白，
                    // 用户观感就是"卡死/不回复"。这里先占位上屏；首个真 token 到达后，
                    // onToken 推的是**累计全量文本**，会自然把这行进度覆盖掉。
                    if (tokenCount == 0 && total > 0) {
                        val pct = (cur.toLong() * 100L / total.toLong()).toInt().coerceIn(0, 99)
                        onToken?.let { cb ->
                            runCatching { cb("⏳ 正在处理提示词… $pct%（$cur/$total token）") }
                        }
                    }
                },
            ) { token ->
                if (firstTokenMs == null) {
                    firstTokenMs = (System.nanoTime() - t0) / 1_000_000
                    QuroDiag.log("LocalEngine", "· llama first token @${firstTokenMs}ms")
                }
                tokenCount++
                sb.append(token)
                // ⚠️ #1112 修复其一（决定性）：把「累计文本」实时推给 UI。
                // 手机 CPU 上 prefill + 解码常需数十秒到数分钟，此前本地路径完全不回吐 token，
                // UI 在整段生成结束前一个字都拿不到 → 用户观感就是「不闪退但也不回复」。
                // PocketPal 正是每 token 经 JSI 回调即时上屏，所以同款 GGUF 在它那里"有反应"。
                onToken?.let { cb -> runCatching { cb(sb.toString()) } }
                true
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            QuroDiag.log(
                "LocalEngine",
                "✓ llama generate done | ${ms}ms | firstToken=${firstTokenMs ?: -1}ms | tokens=$tokenCount | ok=$ok | chars=${sb.length}"
            )
            // ⚠️ 这里以前写的是 `if (!ok && sb.isEmpty())` —— 只有原生返回 false 才报错。
            // 但最常见的"不回复"恰恰是 **ok=true 且 sb 为空**：模型第一个 token 就吐 EOG，
            // 生成循环立刻 break，原生认为"正常结束"返回 true → 走到 Text("") →
            // 聊天气泡纯空白、无任何提示，日志里也看不出所以然。
            // 现在只要没有任何输出就判失败，并把原生记录的真实原因带出来给用户看。
            val nativeErr = runCatching { session.lastError() }.getOrNull()
            if (sb.isEmpty()) {
                val reason = nativeErr
                    ?: if (!ok) "原生会话返回失败且无输出" else "模型未产生任何输出（首个 token 即结束符）"
                QuroDiag.log("LocalEngine", "✗ llama 无输出 | ok=$ok | nativeErr=$nativeErr")
                QuroLlmResult.Error(
                    "本地模型没有产生任何回复。\n原因：$reason\n" +
                        "（模型：$modelName）"
                )
            } else {
                if (nativeErr != null) {
                    QuroDiag.log("LocalEngine", "⚠ llama 部分输出但有原生错误 | $nativeErr")
                }
                QuroLlmResult.Text(sb.toString())
            }
        } catch (e: Throwable) {
            QuroDiag.log("LocalEngine", "✗ llama 推理异常 | ${e.message}\n${e.stackTraceToString()}")
            QuroLlmResult.Error("llama.cpp 推理异常：${e.message}")
        }
    }

    private fun normalizeRole(role: String): String = when (role.lowercase()) {
        "system" -> "system"
        "assistant" -> "assistant"
        else -> "user"
    }

    /**
     * Java 侧 GGUF 预检：在把文件喂给原生 [LlamaSession.create] 之前，先拦截明显非法的输入。
     *
     * 原生层（C++ llama.cpp）遇到非 GGUF / 截断 / 损坏文件会直接 abort 进程，
     * Java 的 try/catch 与 UncaughtExceptionHandler 都抓不到，表现为「闪退却无任何 Java 日志」。
     * 这里的预检把这类脏文件挡在原生层之外，让 [run] 能返回 [QuroLlmResult.Error]
     * 走正常的 UI 提示路径，而不是崩进程。
     *
     * @return 错误描述字符串（应转给用户）；null 表示通过预检。
     */
    private fun precheckLlamaFile(file: File): String? = precheckLlamaFileStatic(file)

    companion object {
        // 🔒 静态串行锁：常驻会话（LocalModelSessionHolder._llama / _mnn）是进程级单例，所有消息的
        // generate 都跑在同一个 ctx 上。必须**静态**串行化，否则多消息并发 generate 会互相踩坏原生状态
        // （SIGSEGV @ ggml_vec_dot_q5_K_q8_K + SIGABRT @ ggml_abort/free）。
        // 说明：genLock 与 unload 的 gate 不嵌套——生成结束先 unlock 再 returnXxx（归还 activeGen），
        // unload 只等 activeGen 归零，从不抢 genLock，故无死锁。
        private val genLock = java.util.concurrent.locks.ReentrantLock()

        internal fun resolveLlamaModelFileStatic(folder: String, modelName: String): File? {
            val dir = File(folder)
            if (!dir.isDirectory) return null
            val direct = File(dir, "$modelName.gguf")
            if (direct.isFile) return direct
            val direct2 = File(dir, modelName) // 可能已带扩展名
            if (direct2.isFile) return direct2
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
                ?.firstOrNull { it.name.removeSuffix(".gguf").equals(modelName, ignoreCase = true) }
        }

        internal fun resolveMnnDirStatic(path: String): File? {
            val f = File(path)
            return when {
                f.isDirectory -> f
                f.isFile -> f.parentFile
                else -> null
            }
        }

        /**
         * MNN 算子缓存目录（**MNN 慢的根因修复**）。
         *
         * MNN 首次加载会把编译好的算子/后端信息写进 `tmp_path` 下的 `mnn_cachefile.bin`，
         * 之后每次加载直接复用。此前 QuroAI 从不传 tmpPath → MNNLlmSession 兜底成
         * **模型目录**，而模型通常在 `/storage/emulated/0/Download/...`，App 对该目录
         * 往往没有写权限 → 缓存写失败 → **每次推理都从头编译算子**，于是"能回复但很慢"。
         * 参考实现（operit MNNProvider）用的是 `context.cacheDir/mnn_cache`，这里对齐。
         *
         * @return 可写缓存目录绝对路径；拿不到 Context 或创建失败时返回 null（退回旧行为，不阻断）。
         */
        internal fun mnnCacheDirStatic(): String? {
            val ctx = com.ai.assistance.quro.activity.QuroApplication.appCtx ?: run {
                QuroDiag.log("LocalEngine", "⚠ MNN 缓存目录不可用：appCtx 为空，将退回模型目录（可能很慢）")
                return null
            }
            val dir = File(ctx.cacheDir, "mnn_cache")
            if (!dir.isDirectory) runCatching { dir.mkdirs() }
            if (!dir.isDirectory || !dir.canWrite()) {
                QuroDiag.log("LocalEngine", "⚠ MNN 缓存目录不可写：${dir.absolutePath}")
                return null
            }
            return dir.absolutePath
        }

        /**
         * 按模型自带的运行参数创建 MNN 会话（后端 / 线程数 / 精度 / 内存模式 / 算子缓存目录）。
         * [QuroLocalEngineNative] 与 [LocalModelSessionHolder] 共用，保证两条路径参数一致
         * （以前两边都各自调无参 create，等于全部走硬编码 cpu/4线程/缓存写模型目录）。
         */
        internal fun createMnnSessionStatic(dir: File, model: QuroLocalModel): MNNLlmSession? {
            val backend = model.resolveBackend()
            val threads = model.resolveThreads()
            val precision = model.resolvePrecision()
            val memory = model.resolveMemoryMode()
            val cache = mnnCacheDirStatic()
            QuroDiag.log(
                "LocalEngine",
                "▶ MNN createSession | dir=${dir.absolutePath} | backend=$backend | threads=$threads | " +
                    "precision=$precision | memory=$memory | cache=${cache ?: "(模型目录·未优化)"}"
            )
            return runCatching {
                MNNLlmSession.create(
                    modelDir = dir.absolutePath,
                    backendType = backend,
                    threadNum = threads,
                    precision = precision,
                    memory = memory,
                    tmpPath = cache,
                )
            }.getOrElse {
                QuroDiag.log("LocalEngine", "✗ MNN createSession 异常 | ${it.message}")
                null
            }
        }

        internal fun precheckLlamaFileStatic(file: File): String? {
            if (!file.isFile) {
                return "llama.cpp 模型文件不存在或不是常规文件：${file.absolutePath}"
            }
            if (file.length() <= 0L) {
                return "llama.cpp 模型文件为空（0 字节）：${file.absolutePath}"
            }
            // 合法 GGUF 文件前 4 字节为 ASCII "GGUF"（0x47 0x47 0x55 0x46）。
            val magic = ByteArray(4)
            val read: Int = try {
                file.inputStream().use { it.read(magic, 0, 4) }
            } catch (e: Throwable) {
                return "读取 llama.cpp 模型文件失败：${e.message}（${file.absolutePath}）"
            }
            if (read < 4) {
                return "llama.cpp 模型文件过小（不足 4 字节，无法校验 GGUF 文件头）：${file.absolutePath}"
            }
            val isGguf = magic[0] == 0x47.toByte() &&
                magic[1] == 0x47.toByte() &&
                magic[2] == 0x55.toByte() &&
                magic[3] == 0x46.toByte()
            if (!isGguf) {
                return "llama.cpp 模型文件不是合法的 GGUF 格式（文件头前 4 字节应为 'GGUF'，实际为 " +
                    "0x%02X 0x%02X 0x%02X 0x%02X）：%s".format(
                        magic[0].toUByte().toInt(),
                        magic[1].toUByte().toInt(),
                        magic[2].toUByte().toInt(),
                        magic[3].toUByte().toInt(),
                        file.absolutePath
                    )
            }
            return null
        }
    }
}
