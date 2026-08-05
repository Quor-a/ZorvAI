package com.ai.assistance.quro.core.network

import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.mnn.MNNLlmSession
import com.ai.assistance.mnn.MnnModelCapabilities
import com.ai.assistance.mnn.MnnThinkContent
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
 * 流式 `<think>` 思考段剥离器（回归 #1 流式侧修复）。
 *
 * 原生 MNN 会在生成过程中把 `<think>…</think>` 思考原文实时吐给回调；旧逻辑直接把累积缓冲
 * `sb.toString()` 推给 UI，导致用户能在打字机上**实时看到**思考过程（甚至未闭合的残留
 * `<think>` 标签一直挂在气泡里）。本类在每次 [accept] 时增量维护两份状态：
 *
 * - [raw]：完整原始文本（含思考段），供终态 [MnnThinkContent.split] / [RepetitionGuard] 使用；
 * - 可见文本：剔除已完成 `<think>…</think>` 块、且**丢弃当前未闭合 `<think>` 尾部**的干净文本，
 *   作为流式回调的返回值实时上屏。
 *
 * 终态仍由 [MnnThinkContent.split] 做最终清洗（`generateMnn` 末尾无条件补推 [finalText]），
 * 二者互补：流式阶段让用户“看不到”思考，终态保证气泡最终态一定是干净答案。
 *
 * 增量扫描用状态机处理 token 被标签边界劈开的情况（如 `"<thi"` + `"nk>world"`），并保守地
 * 暂缓提交可能构成 `<think>` / `</think>` 前缀的尾部，避免标签泄漏。
 */
private class StreamingThinkStripper {
    /** 完整原始累积文本（保留思考段），终态解析用。 */
    private val raw: StringBuilder = StringBuilder()

    /** 已确认的可见文本（已丢掉完整思考块）。 */
    private val visible: StringBuilder = StringBuilder()

    /** 累积的思考文本（<think>…</think> 内部内容），用于流式实时透传给 UI。 */
    private val thinking: StringBuilder = StringBuilder()

    /** 尚未能判定归属的尾部缓冲（可能含未闭合标签前缀或思考内容）。 */
    private val pending: StringBuilder = StringBuilder()

    /** 当前是否处于 `<think>` 块内部（该段内容需最终丢弃）。 */
    private var inThink: Boolean = false

    /**
     * 喂入一个增量 token，返回**当前完整可见文本**（剔除思考块及未闭合思考尾部）。
     *
     * @param chunk 原生本次吐出的文本片段（可能为空）。
     * @return 截至当前的可见文本，可直接推给 UI 流式展示。
     */
    fun accept(chunk: String): String {
        if (chunk.isNotEmpty()) {
            raw.append(chunk)
            pending.append(chunk)
        }
        drainPending()
        return visible.toString()
    }

    /**
     * 增量扫描 [pending]：把能确定的可见字符提交到 [visible]、把思考内容丢弃，
     * 并把可能构成标签前缀的尾部保留在 [pending] 中等待后续 chunk。
     */
    private fun drainPending() {
        while (pending.isNotEmpty()) {
            val pendLow = pending.toString().lowercase()
            if (!inThink) {
                // 找开标签 "<think"（含 "<think>" / "<think >" 等变体），遇到 '>' 即视为开标签结束。
                val openStart = pendLow.indexOf(OPEN_MARKER)
                if (openStart >= 0) {
                    if (openStart > 0) visible.append(pending, 0, openStart)
                    val gt = pending.indexOf('>', openStart)
                    if (gt < 0) break // 形如 "<think"（尚未见到 '>'）：保留整个 pending 等待后续
                    pending.delete(0, gt + 1)
                    inThink = true
                    continue
                }
                // 没找到完整开标签：提交除“可能构成 <think 前缀”的尾部外的所有内容，避免标签泄漏。
                val hold = tailPrefixLen(pendLow, OPEN_MARKER)
                if (hold > 0) {
                    visible.append(pending, 0, pending.length - hold)
                    pending.delete(0, pending.length - hold)
                } else {
                    visible.append(pending)
                    pending.setLength(0)
                }
                break
            } else {
                // 在思考块内：找闭合标签 "</think>"。
                val closeStart = pendLow.indexOf(CLOSE_TAG)
                if (closeStart >= 0) {
                    thinking.append(pending, 0, closeStart) // 思考内容累积（流式透传给 UI）
                    pending.delete(0, closeStart + CLOSE_TAG.length) // 丢弃闭合标签及其前的思考内容
                    inThink = false
                    continue
                }
                // 没找到闭合：保留可能构成 "</think>" 前缀的尾部，其余思考内容累积进 thinking。
                val hold = tailPrefixLen(pendLow, CLOSE_TAG)
                if (hold > 0) {
                    if (pending.length - hold > 0) thinking.append(pending, 0, pending.length - hold)
                    pending.delete(0, pending.length - hold)
                } else {
                    thinking.append(pending) // 整个 pending 都是思考内容
                    pending.setLength(0)
                }
                break
            }
        }
    }

    /**
     * 返回 [low] 尾部有多少字符是 [marker] 的**前缀**（大小写不敏感）。
     * 用于在标签被 chunk 边界劈开时保守地暂缓提交，避免 `<` 等字符泄漏上屏。
     */
    private fun tailPrefixLen(low: String, marker: String): Int {
        val max = low.length.coerceAtMost(marker.length - 1)
        for (len in max downTo 1) {
            if (low.endsWith(marker.substring(0, len))) return len
        }
        return 0
    }

    /** 完整原始文本（含思考段），供终态解析。 */
    fun rawText(): String = raw.toString()

    /** 当前累积的思考文本（流式阶段实时更新），供 UI 边想边展示。 */
    fun thinkingText(): String = thinking.toString()

    /** 复位所有状态（降级重试结构化生成时调用，避免旧缓冲污染新路径）。 */
    fun reset() {
        raw.setLength(0)
        visible.setLength(0)
        thinking.setLength(0)
        pending.setLength(0)
        inThink = false
    }

    private companion object {
        private const val OPEN_MARKER = "<think"
        private const val CLOSE_TAG = "</think>"
    }
}

/**
 * 流式展示内容映射：思考段内部（[visible] 为空）时给出「思考中」占位，
 * 避免气泡在长思考阶段长时间冻结在 prefill 进度文案（如"正在处理提示词 1%"）上。
 */
private fun streamDisplay(visible: String): String =
    if (visible.isNotEmpty()) visible else "本地模型思考中…"

/**
 * 终态兜底：无论 [MnnThinkContent.split] 是否识别，都再扫一遍残留的 `<think>…</think>`
 * （含全角 ＜／＞、大小写变体、未闭合尾随），确保思考段绝不会原样漏进可见正文。
 *
 * 思考内容已由 [MnnThinkContent.split] 或流式 [onThinking] 通道单独捕获进 `reasoning`，
 * 因此这里从可见正文里清掉它是安全的；即便误伤极个别正文里字面出现的 "<think>"，
 * 代价也远小于把整段自言自语泄漏给用户（"思考泄漏"回归）。
 */
private fun stripResidualThink(raw: String): String {
    val norm = raw.replace('＜', '<').replace('＞', '>').replace('／', '/')
    // 成对标签（容忍标签内空白 / 换行）
    var cleaned = norm.replace(Regex("(?is)<think\\s*>.*?</think\\s*>"), " ")
    // 未闭合：<think 之后到文末全部清掉（生成被 max_tokens 截断的典型形态）
    val openIdx = cleaned.indexOf("<think", ignoreCase = true)
    if (openIdx >= 0) cleaned = cleaned.substring(0, openIdx)
    return cleaned.replace(Regex("[ \\t　]{2,}"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
}

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
        onThinking: ((String) -> Unit)?,
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
            QuroLocalModelType.MNN -> runMnn(model, messages, maxTokens, onToken, toolSpecsJson, onThinking)
            QuroLocalModelType.LLAMA_CPP ->
                runLlama(model, modelName, messages, temperature, maxTokens, contextWindow, onToken, toolSpecsJson, onThinking)
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
        onThinking: ((String) -> Unit)? = null,
    ): QuroLlmResult {
        // 优先复用常驻会话
        val held = (LocalModelLoaders.get() as? LocalModelSessionHolder)?.takeIf { it.isLoaded(model) }?.borrowMnn()
        if (held != null) {
            QuroDiag.log("LocalEngine", "▶ 复用已加载常驻 MNN 会话（跳过模型加载）")
            // 串行化常驻会话生成；结束时归还计数，unload 才能安全 free。
            genLock.lock()
            try {
                return generateMnn(held, model, messages, maxTokens, onToken, toolSpecsJson, onThinking)
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
            generateMnn(session, model, messages, maxTokens, onToken, toolSpecsJson, onThinking)
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
        onThinking: ((String) -> Unit)? = null,
    ): QuroLlmResult {
        return try {
            val t0 = System.nanoTime()
            var firstTokenMs: Long? = null
            var tokenCount = 0
            // 🔧 4D：把透传给原生的 maxTokens 钳制到一个合理上限。
            // 旧代码直接透传调用方给的 maxTokens，而 MNN 原生上限是 8192（mnnllmnative.cpp:1012），
            // 手机 CPU 上对 8192 token 解码要数分钟 → 离线推理卡死/永久转圈（症状 4 主因）。
            // 但 1024 对思考模型偏紧（思考+回答易截断），上调到 2048：思考模型有真实余量，
            // 非思考模型也仍在可控解码时长内（MNN 原生上限 8192，留有富余）。
            val effMaxTokens = maxTokens.coerceIn(128, 2048)
            // 🧠 1.A：流式思考剥离器——增量维护「可见文本」（剔除 <think> 块，含未闭合尾部），
            // 同时累积原始全文供终态解析。这样生成过程中用户不会实时看到思考原文。
            val stripper = StreamingThinkStripper()
            // 工具指令（system 文本降级注入）只在此处算一次，结构化与降级路径共用，避免重复注入。
            val effectiveMessages = if (toolSpecsJson != null) {
                maybeInjectToolInstruction(model, messages, toolSpecsJson)
            } else {
                messages
            }
            val ok = if (toolSpecsJson != null) {
                // 结构化路径：把工具描述注入 prompt，让模型能触发工具调用。
                // MNN 原生无 parseToolCallResponse，回调收到的是 raw 模型文本（含 <tool_call> 标签），
                // 生成结束后由 QuroLocalToolsCodec.parseDetailed 解析。
                val messagesJson = QuroLocalToolsCodec.encodeMessages(effectiveMessages)
                QuroDiag.log("LocalEngine", "▶ MNN generateStreamStructured | tools=${toolSpecsJson.length} chars | effMax=$effMaxTokens")
                val structuredOk = session.generateStreamStructured(messagesJson, toolSpecsJson, effMaxTokens) { token ->
                    if (firstTokenMs == null) firstTokenMs = (System.nanoTime() - t0) / 1_000_000
                    tokenCount++
                    // 流式阶段即剥离 <think> 块，避免用户实时看到思考原文（症状 1 流式侧）。
                    val visible = stripper.accept(token)
                    onToken?.let { cb -> runCatching { cb(streamDisplay(visible)) } }
                    onThinking?.let { cb -> runCatching { cb(stripper.thinkingText()) } }
                    true
                }
                if (!structuredOk || stripper.rawText().isEmpty()) {
                    // 🔧 2.B 降级兜底（健壮性）：结构化渲染失败 / 无输出（ok=false 或空）——直接卡死
                    // 比无声失败更糟。退回普通 generateStream，把已注入 system 文本的工具定义再试一次。
                    // effectiveMessages 已含工具指令，此处绝不重复注入。
                    QuroDiag.log(
                        "LocalEngine",
                        "⚠ MNN 结构化生成未产出（ok=$structuredOk, chars=${stripper.rawText().length}），" +
                            "降级为普通 generateStream 重试（工具定义已注入 system 文本）"
                    )
                    stripper.reset()
                    val history = buildMnnHistory(effectiveMessages)
                    val fallbackOk = session.generateStream(history, effMaxTokens) { token ->
                        if (firstTokenMs == null) firstTokenMs = (System.nanoTime() - t0) / 1_000_000
                        tokenCount++
                        val visible = stripper.accept(token)
                        onToken?.let { cb -> runCatching { cb(streamDisplay(visible)) } }
                        onThinking?.let { cb -> runCatching { cb(stripper.thinkingText()) } }
                        true
                    }
                    fallbackOk
                } else {
                    structuredOk
                }
            } else {
                // 非结构化路径：原有 (role, content) 历史拼接。
                val history = buildMnnHistory(effectiveMessages)
                session.generateStream(history, effMaxTokens) { token ->
                    if (firstTokenMs == null) firstTokenMs = (System.nanoTime() - t0) / 1_000_000
                    tokenCount++
                    val visible = stripper.accept(token)
                    onToken?.let { cb -> runCatching { cb(streamDisplay(visible)) } }
                    onThinking?.let { cb -> runCatching { cb(stripper.thinkingText()) } }
                    true
                }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            QuroDiag.log(
                "LocalEngine",
                "MNN generate | ${ms}ms | firstToken=${firstTokenMs ?: -1}ms | tokens=$tokenCount | ok=$ok | chars=${stripper.rawText().length} | structured=${toolSpecsJson != null}"
            )
            if (stripper.rawText().isEmpty()) {
                // 🛡️ B-1：不再用「未产生任何输出（ok=false）」糊弄用户。
                // 原生层失败时会把具体原因写进 last-error 通道（模板渲染为空 / 分词为 0 /
                // 原生异常……），这里取回已翻译好的中文提示直接展示。
                val nativeError = session.lastNativeError
                val reason = nativeError?.message ?: buildMnnEmptyOutputHint(toolSpecsJson != null)
                QuroDiag.log(
                    "LocalEngine",
                    "✗ MNN 无输出 | ok=$ok | code=${nativeError?.code ?: "(无)"} | " +
                        "detail=${nativeError?.detail ?: "(无)"}"
                )
                QuroLlmResult.Error(reason)
            } else {
                // 复读兜底命中时裁掉退化尾巴，只保留少量重复；未命中则原样返回。
                val degeneration = session.lastDegeneration
                var finalText = RepetitionGuard.trimDegenerateTail(stripper.rawText(), degeneration)
                if (degeneration != null) {
                    QuroDiag.log(
                        "LocalEngine",
                        "✂ MNN 退化尾巴已裁剪 | ${stripper.rawText().length} → ${finalText.length} 字符"
                    )
                }

                // 🧠 B-4：切出 <think>…</think> 思考段。
                // 必须在工具解析之前做——思考段里常常有"示例性"的 <tool_call> 片段，
                // 不切掉会被当成真实调用触发一次莫名其妙的工具执行。
                val split = MnnThinkContent.split(finalText)
                val reasoning: String? = split.reasoning.takeIf { it.isNotEmpty() }
                if (reasoning != null) {
                    QuroDiag.log(
                        "LocalEngine",
                        "🧠 MNN thinking 段已分离 | reasoning=${reasoning.length} 字符 | " +
                            "answer=${split.answer.length} 字符" +
                            if (split.answerFromReasoning) " | ⚠ 正文为空，已回退展示思考内容" else ""
                    )
                }
                // 🔧 修复「思考泄漏」：无条件取 split.answer 作为可见正文，不再仅在 reasoning!=null
                // 时才切。此前若该模型思考格式未被 MnnThinkContent.split 识别（reasoning=null），
                // finalText 仍是含 <think> 的 stripper.rawText()，被原样推上屏 → 用户看到原始思考段。
                // 再叠加 stripResidualThink 兜底，任何残留 <think> 都不可能漏进正文。
                finalText = stripResidualThink(split.answer)

                // 模型只吐了思考、正文为空：兜底说明，避免气泡空白 / 残留"思考中"占位（#offline-empty-answer）。
                if (finalText.isEmpty()) {
                    finalText = "（本地模型仅完成了思考过程，未生成可展示的回复。）"
                }

                // 把清洗后的干净文本（已切走思考段 / 裁掉退化尾巴）**无条件**补推给 UI，
                // 确保界面最终态永远是干净答案，永远不会残留原始流缓冲里的 <think> 标签或复读片段。
                // ⚠️ 必须是无条件：旧逻辑仅在 reasoning/degeneration != null 时才补推，但
                // MnnThinkContent.split 没认出思考标签时 reasoning 为 null → 条件不成立 → 漏推，
                // UI 一直挂着含 <think> 的原始流缓冲 → "思考泄漏"回归（症状 1）。
                onToken?.let { cb -> runCatching { cb(finalText) } }

                // 结构化路径：检查模型输出是否包含工具调用。
                if (toolSpecsJson != null) {
                    // 先解析已剥离思考段的正文（常规：<tool_call> 在 </think> 之后）
                    var parsed = QuroLocalToolsCodec.parseDetailed(finalText)
                    // 🧠 修复（离线工具+思考不可用）：思考模型常把真实 <tool_call> 放在 <think> 块内输出，
                    // 上面的 split 已把思考段切走，会导致工具调用被一并丢弃 → "有思考+工具却不能用"。
                    // 若正文里没解析到调用、但原始全文（含思考段）里有，则从全文恢复真实工具调用。
                    if (parsed.calls.isEmpty() && reasoning != null) {
                        val fromFull = QuroLocalToolsCodec.parseDetailed(stripper.rawText())
                        if (fromFull.calls.isNotEmpty()) {
                            QuroDiag.log(
                                "LocalEngine",
                                "✓ MNN 从思考段内恢复工具调用 | calls=${fromFull.calls.size} | " +
                                    "names=${fromFull.calls.joinToString(",") { it.name }}"
                            )
                            parsed = fromFull
                        }
                    }
                    if (parsed.calls.isNotEmpty()) {
                        QuroDiag.log(
                            "LocalEngine",
                            "✓ MNN tool calls detected | calls=${parsed.calls.size} | " +
                                "names=${parsed.calls.joinToString(",") { it.name }}" +
                                (parsed.diagnostic?.let { " | 诊断=$it" } ?: "")
                        )
                        return QuroLlmResult.ToolCalls(parsed.calls, reasoning = reasoning)
                    }
                    // 🛡️ B-2：模型看起来想调工具却没解析出来 —— 这种"无声失败"以前完全不可见。
                    if (parsed.sawMarker) {
                        QuroDiag.log(
                            "LocalEngine",
                            "⚠ MNN 疑似工具调用解析失败 | 诊断=${parsed.diagnostic ?: "(无)"} | " +
                                "原文前 200 字=${finalText.take(200)}"
                        )
                        finalText = finalText + "\n\n⚠️ 模型尝试调用工具但输出格式不规范，" +
                            "本次未能执行（${parsed.diagnostic ?: "格式无法识别"}）。" +
                            "该模型可能未针对工具调用训练，建议在模型配置里关闭「本地工具调用」。"
                        onToken?.let { cb -> runCatching { cb(finalText) } }
                    }
                    // 🔎 回归 #2 诊断（model did not emit tool_call）：工具已配置、模型也没吐
                    // <tool_call> 标记，说明模板并未真正把工具描述交付给模型
                    // （caps.supportsTools 误判的典型症状）。这一诊断替代此前"开了工具调用却
                    // 毫无反应"的完全无声失败；结合上面的 withToolInstruction 注入，绝大多数
                    // 模型现在应能拿到工具描述。
                    if (!parsed.sawMarker) {
                        QuroDiag.log(
                            "LocalEngine",
                            "⚠ MNN 工具未触发 | 模型未输出 <tool_call> 标记（sawMarker=false），" +
                                "模板可能未真正交付工具描述 | 诊断=${parsed.diagnostic ?: "(无)"}"
                        )
                    }
                }
                QuroLlmResult.Text(finalText, reasoning = reasoning)
            }
        } catch (e: Throwable) {
            QuroDiag.log("LocalEngine", "✗ MNN 推理异常 | ${e.message}\n${e.stackTraceToString()}")
            QuroLlmResult.Error("MNN 推理异常：${e.message}")
        }
    }

    /**
     * 始终把工具定义注入 MNN 的 system 指令（回归 #2 修复）。
     *
     * 判定依据来自 [MnnModelCapabilities.probe]（读模型自带 llm_config.json 的 chat_template），
     * 但不是按它来决定"要不要注入"——[MnnModelCapabilities.probe] 仅在 chat_template 字符串
     * **包含** "tools" 子串时就乐观置 `supportsTools=true`，而很多模型的模板提到 "tools"
     * 却并不真正渲染 jinja 的 tools 上下文，于是原生 `set_config(tools)` 路径被静默丢弃，
     * 模型永远看不到工具描述 → 不吐 `<tool_call>`，且无 ok=false、无崩溃，表现为"开了工具调用
     * 却毫无反应"的无声失败。
     *
     * 因此这里**不依赖 `caps.supportsTools` 这个不可靠信号**：无论 caps 怎么说，都走
     * [QuroLocalToolsCodec.withToolInstruction] 把工具定义作为 system 文本降级注入一份
     * （belt-and-suspenders，与原生 jinja context 那条路径并行生效），保证模型至少能从 system
     * 文本里读到工具描述。仅在无法定位模型目录 / 能力探测彻底失败时，才回退原样返回（此时本就无法注入）。
     *
     * @param model 当前本地模型（用于定位模型目录）。
     * @param messages 原始消息列表。
     * @param toolSpecsJson OpenAI 兼容的 tools JSON 数组字符串。
     * @return 注入了 system 工具指令的新消息列表；无法定位模型时原样返回。
     */
    private fun maybeInjectToolInstruction(
        model: QuroLocalModel,
        messages: List<QuroChatMessage>,
        toolSpecsJson: String,
    ): List<QuroChatMessage> {
        // 仅用于打一行诊断，不再决定注入与否。
        val dir = resolveMnnDirStatic(model.path) ?: return messages
        val caps = runCatching { MnnModelCapabilities.probe(dir) }.getOrNull()

        val note = when {
            caps == null -> "（能力探测失败，仍注入 system 指令）"
            !caps.hasChatTemplate -> "（无 jinja.chat_template，已用内置 ChatML 兜底，仍注入 system 指令）"
            caps.supportsTools -> "（模板含 tools 子串，仍额外注入 system 指令做双保险）"
            else -> "（模板不消费 tools，已降级为 system 指令注入）"
        }
        QuroDiag.log(
            "LocalEngine",
            "🔧 MNN 工具指令注入 | $note"
        )
        return QuroLocalToolsCodec.withToolInstruction(messages, toolSpecsJson)
    }

    /**
     * 原生层没留下具体原因时的兜底提示。
     *
     * 之所以还要分「是否开了工具调用」两版：这两条路径在原生侧走的是**完全不同**的函数
     * （结构化模板 vs 直接历史拼接），排查方向也不一样，给一句笼统的话等于什么都没说。
     *
     * @param structured 本轮是否走了带工具定义的结构化路径。
     * @return 面向用户的中文提示。
     */
    private fun buildMnnEmptyOutputHint(structured: Boolean): String = if (structured) {
        "开启工具调用后，MNN 推理没有产生任何内容，且原生层未返回具体原因。" +
            "多数情况是该模型没有针对工具调用训练（llm_config.json 的 chat_template 不处理 tools）。" +
            "建议：在「模型配置」里关闭「本地工具调用」后重试；若关闭后能正常对话，即可确认是模型能力问题。"
    } else {
        "MNN 推理没有产生任何内容，且原生层未返回具体原因。" +
            "请确认模型目录完整（llm_config.json、权重、tokenizer 文件齐全），或尝试重新加载模型。"
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
        onThinking: ((String) -> Unit)? = null,
    ): QuroLlmResult {
        // 优先复用常驻会话
        val held = (LocalModelLoaders.get() as? LocalModelSessionHolder)?.takeIf { it.isLoaded(model) }?.borrowLlama()
        if (held != null) {
            QuroDiag.log("LocalEngine", "▶ 复用已加载常驻 llama 会话（跳过模型加载）")
            // 串行化常驻会话生成（防止同一 ctx 被并发 generate 踩坏）；
            // 结束时归还计数，unload 才能安全 free。
            genLock.lock()
            try {
                return generateLlama(held, model, modelName, messages, temperature, maxTokens, onToken, toolSpecsJson, onThinking)
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
        // 但 1024 对思考模型偏紧（思考+回答易截断），上调到 2048 给思考模型真实余量。
        val effMaxTokens = maxTokens.coerceIn(128, 2048)
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
            generateLlama(session, model, modelName, messages, temperature, maxTokens, onToken, toolSpecsJson, onThinking)
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
        onThinking: ((String) -> Unit)? = null,
    ): QuroLlmResult {
        return try {
            // 🔧 回归 #3：每轮生成前先把 KV / 上下文前缀缓存清掉，确保本轮是**纯无状态**生成。
            // Kotlin 侧本来就每轮把完整历史重新交给模板从头 prefill（llama.cpp 无状态），
            // 而原生层（llama_jni_stub.cpp 的 Plan A KV 前缀复用）会在轮间复用 KV——
            // 当模板把共享前缀重渲染出 token 漂移（addAssistant=true 平移尾部）或触发头部截断时，
            // 复用的 KV 错位 → 模型在陈旧/残缺 KV 上续写 → 复读旧答案（history loop）。
            // 既然 Kotlin 已每轮重发全量历史，原生 KV 复用纯属冗余且有害，这里直接 invalidate。
            // 代价仅是放弃 Plan A 的 prefill 加速，换取多轮历史不再错位——可接受。
            QuroDiag.log("LocalEngine", "▶ llama resetContext start (无状态化本轮)")
            runCatching { session.resetContext() }
            QuroDiag.log("LocalEngine", "✓ llama resetContext done")

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

            val effMaxTokens = maxTokens.coerceIn(128, 2048)
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
            // 🧠 思考段流式剥离（与 MNN 对齐）：llama.cpp 思考模型（Qwen3 / DeepSeek-R1 系）会把
            // <think>…</think> 直接吐进 token 流，若不剥离，思考原文会实时上屏并最终残留在气泡里
            // （"有思考但是不能用"的 CPP 侧观感）。复用 MNN 路径同款 StreamingThinkStripper，
            // 仅把剔除思考块后的可见文本推给 UI；终态再统一清洗。
            val stripper = StreamingThinkStripper()
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
                // 🧠 流式阶段即剥离 <think> 块，避免用户实时看到思考原文（与 MNN 对齐）。
                val visible = stripper.accept(token)
                onToken?.let { cb -> runCatching { cb(streamDisplay(visible)) } }
                onThinking?.let { cb -> runCatching { cb(stripper.thinkingText()) } }
                true
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            QuroDiag.log(
                "LocalEngine",
                "✓ llama generate done | ${ms}ms | firstToken=${firstTokenMs ?: -1}ms | tokens=$tokenCount | ok=$ok | chars=${sb.length}"
            )
            // ⚠️ 只要没有任何输出就判失败（ok=true 且空输出 = 首个 token 即 EOG，旧逻辑漏判）。
            val nativeErr = runCatching { session.lastError() }.getOrNull()
            if (stripper.rawText().isEmpty()) {
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
                // 🧠 切出 <think>…</think> 思考段（与 MNN 对齐）。必须在工具解析之前做，
                // 否则思考段里示例性的 <tool_call> 会被误当成真实调用。
                val split = MnnThinkContent.split(stripper.rawText())
                val reasoning: String? = split.reasoning.takeIf { it.isNotEmpty() }
                if (reasoning != null) {
                    QuroDiag.log(
                        "LocalEngine",
                        "🧠 llama thinking 段已分离 | reasoning=${reasoning.length} 字符 | answer=${split.answer.length} 字符" +
                            if (split.answerFromReasoning) " | ⚠ 正文为空，已回退展示思考内容" else ""
                    )
                }
                // 终态无条件把干净正文（已切走思考段）补推给 UI，确保气泡最终态不含 <think> 残留。
                val answer = if (split.answer.isEmpty()) "（本地模型仅完成了思考过程，未生成可展示的回复。）" else split.answer
                val finalText = stripResidualThink(answer)
                onToken?.let { cb -> runCatching { cb(finalText) } }

                // 结构化路径：原生 parseToolCallResponse 优先；未命中（模板无 parser / 思考段内 <tool_call>）
                // 时回退通用文本解析，避免工具调用被吞。
                if (toolSpecsJson != null) {
                    val detailed = QuroLocalToolsCodec.parseDetailed(finalText)
                    val toolCallJson = runCatching { session.parseToolCallResponse(stripper.rawText()) }.getOrNull()
                    var calls = if (toolCallJson != null) QuroLocalToolsCodec.parseToolCalls(toolCallJson) else emptyList()
                    if (calls.isEmpty()) {
                        calls = detailed.calls
                        // 正文无调用但思考段内有真实 <tool_call>：从全文恢复（与 MNN 对齐）。
                        if (calls.isEmpty() && reasoning != null) {
                            val fromFull = QuroLocalToolsCodec.parseDetailed(stripper.rawText())
                            if (fromFull.calls.isNotEmpty()) {
                                QuroDiag.log(
                                    "LocalEngine",
                                    "✓ llama 从思考段内恢复工具调用 | calls=${fromFull.calls.size} | names=${fromFull.calls.joinToString(",") { it.name }}"
                                )
                                calls = fromFull.calls
                            }
                        }
                    }
                    if (calls.isNotEmpty()) {
                        QuroDiag.log(
                            "LocalEngine",
                            "✓ llama tool calls detected | calls=${calls.size} | names=${calls.joinToString(",") { it.name }}"
                        )
                        return QuroLlmResult.ToolCalls(calls, reasoning = reasoning)
                    }
                    if (detailed.sawMarker) {
                        QuroDiag.log(
                            "LocalEngine",
                            "⚠ llama 疑似工具调用解析失败 | 诊断=${detailed.diagnostic ?: "(无)"} | 原文前 200 字=${finalText.take(200)}"
                        )
                        val withNote = finalText + "\n\n⚠️ 模型尝试调用工具但输出格式不规范，本次未能执行（${detailed.diagnostic ?: "格式无法识别"}）。建议关闭「本地工具调用」。"
                        onToken?.let { cb -> runCatching { cb(withNote) } }
                        return QuroLlmResult.Text(withNote, reasoning = reasoning)
                    }
                    QuroDiag.log("LocalEngine", "⚠ llama 工具未触发 | 模型未输出 <tool_call>（sawMarker=false）")
                }
                QuroLlmResult.Text(finalText, reasoning = reasoning)
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

            // 🔎 B-4：从模型自带 llm_config.json 探测真实能力（不按模型名猜白名单）。
            // 这条日志是排查「有工具调用/思考但是不能用」的第一现场：
            // tools=✗ 说明模型模板压根不消费 tools，开工具调用只会得到垃圾输出或 0 输出。
            val caps = MnnModelCapabilities.probe(dir)
            QuroDiag.log("LocalEngine", "🔎 MNN 模型能力探测 | ${caps.summary()}")
            if (!caps.hasChatTemplate) {
                QuroDiag.log(
                    "LocalEngine",
                    "⚠ 该模型没有 jinja.chat_template，结构化（工具调用）路径将使用内置 ChatML 兜底模板；" +
                        "若模型不是 ChatML 体系，输出质量会明显下降。"
                )
            }
            // 思考模型：显式打开 enable_thinking。以前这个开关写好了却从来没人调用，
            // 导致 Qwen3 一类模型在 MNN 上永远拿不到思考段（"有思考但是不能用"）。
            if (caps.supportsThinkingToggle) {
                val applied = runCatching { session.setThinkingMode(true) }.getOrDefault(false)
                QuroDiag.log(
                    "LocalEngine",
                    if (applied) "🧠 MNN thinking 模式已开启（模板支持 enable_thinking）"
                    else "⚠ MNN thinking 模式开启失败（enable_thinking 注入被原生层拒绝），按普通模型继续"
                )
            }
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
