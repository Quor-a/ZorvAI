package com.ai.assistance.quro.core.network

import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.mnn.MNNLlmSession
import com.ai.assistance.mnn.RepetitionGuard
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.model.QuroGgufNaming
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.util.QuroDiag
import java.io.File

/**
 * Plan C：本地 prefill 进度上屏阈值（token）。
 * 原生每轮上报的 [total] 是"本轮真正要 decode 的新增 token 数"（= promptTokens - 复用的 KV 前缀，
 * 见 llama_jni_stub.cpp 的 Plan A 实现），不是总 prompt 长度。只有超过该阈值才把进度事件透出给 UI，
 * 否则静默等首 token。选 256：手机 CPU 上一次 llama_decode chunk 约 256 token，低于此值的 prefill
 * 通常 <1s，没必要弹进度条；多轮对话（Plan A 后只新增几十 token）正好被挡住，消除用户抱怨的
 * "每轮都弹 正在处理提示词… X%"。
 */
private const val LOCAL_PREFILL_PROGRESS_TOKEN_THRESHOLD = 256

/**
 * 原生本地推理引擎（full 风味专用实现）。
 *
 * 职责：直接驱动移植进来的 MNN / llama.cpp JNI 会话（[MNNLlmSession] / [LlamaSession]），
 * 把 QuroAI 的 [QuroChatMessage] 多轮历史映射为原生输入，把原生 token 流累积为
 * [QuroLlmResult.Text]，不引入外部 chat 子系统。
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
 * - MNN 的 [MNNLlmSession.create] 不接收调用方传入的 temperature（沿用模型 llm_config.json
 *   自带值），MNN 采样温度暂未接入 UI 配置。
 *
 * 抗复读（本次修复）：MNN 引擎默认 `mixed_samplers` 不含 `"penalty"` 且 `repetition_penalty`
 * 默认 1.0，导致重复惩罚在采样管线里是死代码，模型会把人设口头禅复读到 max_new_tokens。
 * [createMnnSessionStatic] 现在会在 load 前注入 penalty 采样链（治本），
 * [MNNLlmSession] 内部再叠加一层流式重复检测兜底（治标），命中时经 [QuroDiag] 落盘取证。
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
        toolSpecsJson: String?,
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
            // 🔎 取证（设备侧无 adb，日志落 Downloads/QuroAI_logs/quro_diag_<日期>.log）：
            // 以前这里只记「被拦的 model 是谁」，没记「holder 当时到底持有什么」，
            // 于是日志里永远只有"未加载"三个字，分不清是：
            //   ① 压根没点加载（state=None）
            //   ② 点了但加载失败（state=Failed(原因)）——最容易被用户忽略，弹窗一关就没了
            //   ③ 正在加载（state=Loading）
            //   ④ 加载的是另一个模型（state=Loaded 但 held.id / held.path 与传入的对不上）
            //   ⑤ 进程重启过（pid 与 "✓ 模型已加载并常驻" 那行的 pid 不同 → 原生崩溃/被系统回收）
            // 现在把 holder 快照与传入 model 的同名字段并排打出来，一眼可判。
            val snap = holder.snapshot()
            QuroDiag.log(
                "LocalEngine",
                "✗ run gated | 门禁拦截 || 传入: id=${model.id} | type=${model.type} | " +
                    "name=${model.name} | path=${model.path} | modelName=$modelName " +
                    "|| holder: ${snap.describe()}"
            )
            return QuroLlmResult.Error(gateMessage(snap, model))
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
            QuroLocalModelType.MNN -> runMnn(model, messages, maxTokens, onToken, toolSpecsJson)
            QuroLocalModelType.LLAMA_CPP ->
                runLlama(model, modelName, messages, temperature, maxTokens, contextWindow, onToken, toolSpecsJson)
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
        toolSpecsJson: String? = null,
    ): QuroLlmResult {
        // 优先复用常驻会话
        val held = (LocalModelLoaders.get() as? LocalModelSessionHolder)?.takeIf { it.isLoaded(model) }?.borrowMnn()
        if (held != null) {
            QuroDiag.log("LocalEngine", "▶ 复用已加载常驻 MNN 会话（跳过模型加载）")
            // 串行化常驻会话生成；结束时归还计数，unload 才能安全 free。
            genLock.lock()
            try {
                return generateMnn(held, model, messages, maxTokens, onToken, toolSpecsJson)
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
            generateMnn(session, model, messages, maxTokens, onToken, toolSpecsJson)
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
        toolSpecsJson: String? = null,
    ): QuroLlmResult {
        return try {
            val t0 = System.nanoTime()
            var firstTokenMs: Long? = null
            var tokenCount = 0
            val sb = StringBuilder()
            val ok = if (toolSpecsJson != null) {
                // 结构化路径：把工具描述注入 prompt，让模型能触发工具调用。
                // MNN 原生无 parseToolCallResponse，回调收到的是 raw 模型文本（含 <tool_call> 标签），
                // 生成结束后由 QuroLocalToolsCodec.parseToolCalls 解析。
                val messagesJson = QuroLocalToolsCodec.encodeMessages(messages)
                QuroDiag.log("LocalEngine", "▶ MNN generateStreamStructured | tools=${toolSpecsJson.length} chars")
                session.generateStreamStructured(messagesJson, toolSpecsJson, maxTokens) { token ->
                    if (firstTokenMs == null) firstTokenMs = (System.nanoTime() - t0) / 1_000_000
                    tokenCount++
                    sb.append(token)
                    // 实时把「累计文本」推给 UI（与云端 onToken 语义一致）。
                    onToken?.let { cb -> runCatching { cb(sb.toString()) } }
                    true
                }
            } else {
                // 非结构化路径：原有 (role, content) 历史拼接。
                val history = buildMnnHistory(messages)
                session.generateStream(history, maxTokens) { token ->
                    if (firstTokenMs == null) firstTokenMs = (System.nanoTime() - t0) / 1_000_000
                    tokenCount++
                    sb.append(token)
                    onToken?.let { cb -> runCatching { cb(sb.toString()) } }
                    true
                }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            QuroDiag.log(
                "LocalEngine",
                "MNN generate | ${ms}ms | firstToken=${firstTokenMs ?: -1}ms | tokens=$tokenCount | ok=$ok | chars=${sb.length} | structured=${toolSpecsJson != null}"
            )
            if (sb.isEmpty()) {
                QuroLlmResult.Error("MNN 推理未产生任何输出（ok=$ok）。")
            } else {
                // 复读兜底命中时裁掉退化尾巴，只保留少量重复；未命中则原样返回。
                val degeneration = session.lastDegeneration
                val finalText = RepetitionGuard.trimDegenerateTail(sb.toString(), degeneration)
                if (degeneration != null) {
                    QuroDiag.log(
                        "LocalEngine",
                        "✂ MNN 退化尾巴已裁剪 | ${sb.length} → ${finalText.length} 字符"
                    )
                    // 把裁剪后的干净文本补推给 UI，避免界面上残留复读片段。
                    onToken?.let { cb -> runCatching { cb(finalText) } }
                }
                // 结构化路径：检查模型输出是否包含工具调用。
                if (toolSpecsJson != null) {
                    val calls = QuroLocalToolsCodec.parseToolCalls(finalText)
                    if (calls.isNotEmpty()) {
                        QuroDiag.log("LocalEngine", "✓ MNN tool calls detected | calls=${calls.size}")
                        return QuroLlmResult.ToolCalls(calls)
                    }
                }
                QuroLlmResult.Text(finalText)
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
        toolSpecsJson: String? = null,
    ): QuroLlmResult {
        // 优先复用常驻会话
        val held = (LocalModelLoaders.get() as? LocalModelSessionHolder)?.takeIf { it.isLoaded(model) }?.borrowLlama()
        if (held != null) {
            QuroDiag.log("LocalEngine", "▶ 复用已加载常驻 llama 会话（跳过模型加载）")
            // 串行化常驻会话生成（防止同一 ctx 被并发 generate 踩坏）；
            // 结束时归还计数，unload 才能安全 free。
            genLock.lock()
            try {
                return generateLlama(held, model, modelName, messages, temperature, maxTokens, onToken, toolSpecsJson)
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
            generateLlama(session, model, modelName, messages, temperature, maxTokens, onToken, toolSpecsJson)
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
        toolSpecsJson: String? = null,
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
        //
        // 工具调用路径：当 toolSpecsJson 非空时，改用 applyStructuredChatTemplate，
        // 它会把 tools JSON 渲染进 Jinja 模板，让模型看到工具描述并触发工具调用。
        val prompt = if (toolSpecsJson != null) {
            val messagesJson = QuroLocalToolsCodec.encodeMessages(messages)
            QuroDiag.log("LocalEngine", "▶ llama applyStructuredChatTemplate start | tools=${toolSpecsJson.length} chars")
            session.applyStructuredChatTemplate(messagesJson, toolSpecsJson, addAssistant = true)
        } else {
            val (roles, contents) = buildLlamaChatInputs(messages)
            QuroDiag.log("LocalEngine", "▶ llama applyChatTemplate start | msgs=${messages.size} | roles=$roles")
            session.applyChatTemplate(roles, contents, addAssistant = true)
        }
            if (prompt == null) {
                QuroDiag.log("LocalEngine", "✗ llama 应用聊天模板失败")
                return QuroLlmResult.Error("llama.cpp 应用聊天模板失败。")
            }
            QuroDiag.log("LocalEngine", "✓ llama applyChatTemplate done | promptChars=${prompt.length} | structured=${toolSpecsJson != null}")
            // ⚠️ #1116 多轮诊断：把本轮 prompt 的消息条数 / 角色分布 / 估算 token 数落盘，
            // 用于在不依赖真机的前提下，从日志侧印证「每轮 prompt 含全部历史、不被截断为仅第一条」。
            // 关键判读：msgs 应随轮次单调递增；firstUser ≠ lastUser 说明 prompt 已推进到最新轮。
            val diagUserCnt = messages.count { it.role.equals("user", true) }
            val diagAsstCnt = messages.count { it.role.equals("assistant", true) }
            val diagFirstUser = messages.firstOrNull { it.role.equals("user", true) }?.content?.take(24) ?: ""
            val diagLastUser = messages.lastOrNull { it.role.equals("user", true) }?.content?.take(24) ?: ""
            QuroDiag.log(
                "LocalPrompt",
                "llama 本轮 prompt | msgs=${messages.size} (user=$diagUserCnt/assistant=$diagAsstCnt) | " +
                    "promptChars=${prompt.length} | ~tokens=${prompt.length / 4} | " +
                    "firstUser=[$diagFirstUser] | lastUser=[$diagLastUser]"
            )

            val effMaxTokens = maxTokens.coerceIn(128, 1024)
            QuroDiag.log("LocalEngine", "▶ llama generateStream start | maxTokens=$effMaxTokens")
            // 截断估算：如果 prompt 估算 token 数超过 nCtx - effMaxTokens，原生层会从头部截断，
            // system/人格身份会被吃掉。记一条诊断日志，便于排查"第二轮失忆"问题。
            val ctxTokens = runCatching { LocalModelSessionHolder.residentCtxTokens() }.getOrDefault(0)
            if (ctxTokens > 0) {
                val estPromptTokens = prompt.length / 4  // 粗估 4 字符 ≈ 1 token
                val maxPromptTokens = ctxTokens - effMaxTokens
                if (estPromptTokens > maxPromptTokens) {
                    QuroDiag.log("LocalEngine", "⚠ prompt 可能被原生层截断 | estPromptTokens≈$estPromptTokens > maxPromptTokens=$maxPromptTokens (nCtx=$ctxTokens, maxGen=$effMaxTokens) | promptChars=${prompt.length}")
                }
            }
            val t0 = System.nanoTime()
            var firstTokenMs: Long? = null
            var tokenCount = 0
            var progressReported = false
            val sb = StringBuilder()
            val ok = session.generateStream(
                prompt,
                effMaxTokens,
                onProgress = { _, cur, total ->
                    // Plan C：只有"本轮真正要 decode 的新增 token 数"(total) 超过阈值才上屏进度，
                    // 否则静默等首 token（避免多轮对话每轮都弹"正在处理提示词… X%"）。
                    // 阈值基于原生上报的"新增 token 数"(total = promptTokens - 复用的 KV 前缀)，
                    // 不是总 prompt 长度：Plan A 落地后多轮只新增几十 token，正好被这个阈值挡住。
                    // latch（progressReported）：一旦开闸上报，本轮不再撤回（避免进度条闪烁）；
                    // 首个真 token 到达后 tokenCount>0，自然停止上报、由累计文本覆盖。
                    val opened = progressReported || total > LOCAL_PREFILL_PROGRESS_TOKEN_THRESHOLD
                    if (tokenCount == 0 && opened && total > 0) {
                        progressReported = true
                        val pct = (cur.toLong() * 100L / total.toLong()).toInt().coerceIn(1, 100)
                        onToken?.let { cb ->
                            val text = if (pct >= 100) {
                                "⏳ 上下文处理完毕，正在生成回复…"
                            } else {
                                "⏳ 正在处理提示词… $pct%（$cur/$total token）"
                            }
                            runCatching { cb(text) }
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
                // 结构化路径：用原生 parseToolCallResponse 检查模型输出是否包含工具调用。
                // llama.cpp 的 common_chat_parse 能识别多种 tool call 格式（JSON / Hermes / Mistral 等），
                // 返回 OpenAI 兼容 JSON；QuroLocalToolsCodec.parseToolCalls 负责提取 QuroToolCall 列表。
                if (toolSpecsJson != null) {
                    val toolCallJson = runCatching { session.parseToolCallResponse(sb.toString()) }.getOrNull()
                    if (toolCallJson != null) {
                        val calls = QuroLocalToolsCodec.parseToolCalls(toolCallJson)
                        if (calls.isNotEmpty()) {
                            QuroDiag.log("LocalEngine", "✓ llama tool calls detected | calls=${calls.size}")
                            return QuroLlmResult.ToolCalls(calls)
                        }
                    }
                }
                QuroLlmResult.Text(sb.toString())
            }
        } catch (e: Throwable) {
            QuroDiag.log("LocalEngine", "✗ llama 推理异常 | ${e.message}\n${e.stackTraceToString()}")
            QuroLlmResult.Error("llama.cpp 推理异常：${e.message}")
        }
    }

    /**
     * 门禁提示语：按 holder 的**真实状态**给出可执行的说明，而不是一律"请先加载"。
     *
     * 用户拿不到 adb 日志，聊天气泡就是他唯一的诊断面板。原文案在「加载失败」和
     * 「压根没加载」两种完全不同的情况下一模一样，用户只能反复去点那个注定失败的
     * 「加载」按钮。现在把 [LocalModelLoader.State.Failed] 里已经存着的真实失败原因
     * 直接抛给用户，并区分"加载中 / 加载了别的模型"两种情形。
     *
     * State.None 分支保留原文案，行为不回退。
     */
    private fun gateMessage(
        snap: LocalModelSessionHolder.Snapshot,
        model: QuroLocalModel,
    ): String {
        val base = "请先在「模型配置」中加载该本地模型后再对话（设置 → 模型配置 → 选择模型 → 点「加载」）。"
        return when (val s = snap.state) {
            is LocalModelLoader.State.Failed ->
                "本地模型加载失败，无法对话。\n原因：${s.message}\n" +
                    "请到「设置 → 模型配置 → 本地离线模型」重新导入或重新加载该模型。"

            is LocalModelLoader.State.Loading ->
                "本地模型正在加载中（首次加载 GGUF 在手机上需要数十秒），请等加载完成后再发送。"

            is LocalModelLoader.State.Loaded -> {
                val held = snap.heldModel
                if (held == null) {
                    base
                } else {
                    "当前常驻内存的是「${held.name}」（${held.type}），" +
                        "而本次对话选用的是「${model.name}」（${model.type}），不是同一个模型。\n" +
                        "请到「设置 → 模型配置 → 本地离线模型」加载「${model.name}」，" +
                        "或把对话模型改选回「${held.name}」。"
                }
            }

            is LocalModelLoader.State.None -> base
        }
    }

    private fun normalizeRole(role: String): String = when (role.lowercase()) {
        "system" -> "system"
        "assistant" -> "assistant"
        else -> "user"
    }

    /**
     * 把 [QuroChatMessage] 多轮历史映射为 llama.cpp 聊天模板所需的 (roles, contents)。
     *
     * ⚠️ 多轮正确性关键（#1116 修复）：必须**原样映射全部 messages**，
     * 不打断、不截断、不"只取最新一条"。llama.cpp 是无状态生成——每轮把完整历史交给
     * 模板渲染后从头 prefill；若此处漏掉上一轮的 assistant 回复或更早的 user 消息，
     * 模型就看不到上下文、退化到"永远答第一条"（本次 Bug 的现象 B）。
     *
     * 此函数是纯函数（仅做角色归一化 + 字段投影），可单测；断言见 LlamaChatInputTest。
     */
    private fun buildLlamaChatInputs(messages: List<QuroChatMessage>): Pair<List<String>, List<String>> {
        val roles = messages.map { normalizeRole(it.role) }
        val contents = messages.map { it.content }
        return roles to contents
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

        /**
         * 在已登记的 llama.cpp 目录里解析出真正要加载的 `.gguf` 文件。
         *
         * ⚠️ **llama.cpp 专属的老 Bug（"只有 llama 报未加载"的根因之一）**：
         * 导入侧（[com.ai.assistance.quro.ui.QuroModelConfigScreen] 的 folderPicker）用
         * `dstDir.walkTopDown()` **递归**发现 `.gguf` 并写进 `modelNames`，而这里原先只用
         * `dir.listFiles()` 扫**顶层**。于是"目录里带一层子文件夹"的模型（HuggingFace 快照式
         * 布局、或直接选了模型的父目录）会被成功登记、列表里也显示"可用模型：xxx"，
         * 但 [LocalModelSessionHolder.load] 永远解析不到文件 → load 失败 → 聊天页门禁拦截。
         * MNN 侧没有这个不对称：导入判定（顶层 `llm_config.json`）与加载解析
         * （[resolveMnnDirStatic] + 顶层 `llm_config.json`）看的是同一层目录。
         *
         * 现在解析侧与发现侧对齐，同样递归查找；并按「精确同名 → 带扩展名同名 → 目录内唯一
         * 一个 .gguf」的优先级兜底，避免误选到不相干的权重分片。
         */
        internal fun resolveLlamaModelFileStatic(folder: String, modelName: String): File? {
            val dir = File(folder)
            if (!dir.isDirectory) return null
            // 🛡️ 分片归一化（修复「分片 GGUF 点加载静默失败 / 选片不确定」的缺口，与本次门禁 Bug 同症状）：
            // 请求名若命中某分片（如 "model-00002-of-00003"），统一归一化到首分片
            // "model-00001-of-00003" —— llama.cpp 只接受首分片路径（split.no != 0 会加载失败）。
            // 非分片名（如 "qwen2.5-..."、"model" 基名）原样保留，不影响老行为。
            // 必须在快路径之前做，否则 "model-00002-of-00003" 会被顶层快路径直接命中、跳过归一化。
            val name = QuroGgufNaming.toFirstShard(modelName)
            // 1) 顶层快路径（绝大多数导入走这里，零遍历开销）
            val direct = File(dir, "$name.gguf")
            if (direct.isFile) return direct
            val direct2 = File(dir, name) // 可能已带扩展名
            if (direct2.isFile) return direct2
            // 2) 递归扫描（与导入侧 walkTopDown 对齐）
            val all = runCatching {
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
                    .toList()
            }.getOrDefault(emptyList())
            if (all.isEmpty()) return null
            val stemOf = { f: File -> QuroGgufNaming.stem(f.name) }
            // 2a) 精确同名匹配（请求名 == 某文件 stem 或全名）
            all.firstOrNull { stemOf(it).equals(name, ignoreCase = true) }?.let { return it }
            all.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
            // 2b) 纯分片目录兜底：目录下全是同一模型的不同分片时，直接定位首分片，
            //     不再依赖不可预测的 readdir 顺序（Android ext4 为哈希序，非字典序）。
            //     （请求名记的是基名 "model"、快路径与精确匹配都 miss 时，靠这里兜底。）
            val shardFiles = all.filter { QuroGgufNaming.shardBase(stemOf(it)) != null }
            if (shardFiles.size == all.size && shardFiles.isNotEmpty()) {
                val bases = shardFiles.mapNotNull { QuroGgufNaming.shardBase(stemOf(it)) }.distinct()
                if (bases.size == 1) {
                    shardFiles.firstOrNull { QuroGgufNaming.isFirstShard(stemOf(it)) }?.let { return it }
                }
            }
            // 3) 目录里只有唯一一个 .gguf → 无歧义，直接用它（覆盖 cfg.model 名字对不上的历史脏配置）
            return all.singleOrNull()
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
         * 使用应用私有缓存目录 `context.cacheDir/mnn_cache` 确保可写。
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
            val session = runCatching {
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
            } ?: return null

            // 复读兜底命中时把证据写进 App 自有日志（设备侧无 adb，这是唯一取证途径）。
            session.onDegeneration = { hit ->
                QuroDiag.log(
                    "LocalEngine",
                    "⛔ MNN 复读兜底触发 | 短语=\"${hit.phrase}\" ×${hit.repeats} | " +
                        "已输出 ${hit.totalChars} 字符 → 判定模型退化，提前结束生成。" +
                        "（若频繁触发，说明该模型的 llm_config.json 采样参数需要调高 repetition_penalty）"
                )
            }
            QuroDiag.log("LocalEngine", "✓ MNN 抗复读已启用 | 采样层 penalty 注入 + 流式重复检测兜底")
            return session
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
