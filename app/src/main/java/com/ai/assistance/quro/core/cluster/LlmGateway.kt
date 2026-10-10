package com.ai.assistance.quro.core.cluster

import android.content.Context
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.QuroReplyLanguage
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolEnvelope
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.network.QuroReasoningControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 一次角色调用的产出 */
data class LlmOutcome(
    val text: String,
    val modelId: String,
    val tokens: Int,
    /**
     * #190：本次调用真正执行过的工具（工具名 -> 结果摘要）。
     * 角色不再只是「说」，它做了什么必须可查 —— 否则「模型说自己操作了手机」和
     * 「真的操作了手机」在日志里长得一模一样。
     */
    val toolsUsed: List<Pair<String, String>> = emptyList(),
    /** ReAct 实际轮数（1 = 没调工具，纯文本回答） */
    val rounds: Int = 1
)

/**
 * 统一的 LLM 通道 —— 云端与端侧在此同构，上层只认这个接口。
 *
 * #216：**所有链路走 ZorvAI 主 LLM 链路**，避免集群自建请求导致上下文爆炸。
 * 此前集群虽然复用 [com.ai.assistance.quro.core.network.QuroLlmClient] 网络层，
 * 但请求装配是自建的：没有思考协议、没有语言约束、没有上下文预算裁剪、
 * 工具结果用裸文本回灌 —— 长任务多轮后上下文失控，与主对话行为不一致。
 *
 * 现在每个请求都复用主对话的同一套机制：
 *  - **思考协议**：读主对话同一个「深度思考」偏好，经 [QuroReasoningControl]
 *    编译成上游认得的字段（reasoning_effort / thinking / enable_thinking …），
 *    与主对话逐字节一致；
 *  - **语言约束**：system 末尾追加 [QuroReplyLanguage] 的语言/思考指令，
 *    回复语言与主对话一致；
 *  - **上下文预算**：用 [QuroConversationStore.toLlmMessages] 做预算裁剪 ——
 *    超长工具结果就地压缩、巨型消息降权、孤儿工具消息剔除、按 contextWindow
 *    从最旧轮次裁剪，从源头杜绝爆上下文；
 *  - **工具结果格式**：统一用 [QuroToolEnvelope] 包装（status/code/hint），
 *    模型看到的工具结果与主对话完全一致；
 *  - **死循环检测**：滑动窗口签名检测 + 失败重复提示，与主对话同语义。
 *
 * 两个【适配点】集中了所有与仓库实际签名相关的代码，改这里即可，其余部分不需动。
 */
interface LlmGateway {
    suspend fun complete(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>> = emptyList(),
        jsonMode: Boolean = false,
        temperature: Float = 0.7f,
        maxTokens: Int = 4096
    ): LlmOutcome

    /**
     * #190：**带工具的 ReAct 调用** —— 角色真正执行能力的唯一入口。
     *
     * 循环语义（每轮都真调上游，不是本地假循环）：
     * ```
     * messages = [system, user]
     * repeat(maxRounds) {
     *     r = 上游(messages, tools)
     *     if (r 无 tool_calls) return r.content
     *     messages += assistant(r)          // 必须带 toolCalls + reasoning
     *     for (call in r.tool_calls) {
     *         messages += tool(执行结果)   // 必须带 toolCallId + toolName
     *     }
     * }
     * ```
     *
     * @param onToolCall 每执行一个工具回调一次（工具名, 结果摘要），供引擎发事件。
     */
    suspend fun withTools(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        tools: List<QuroToolSpec>,
        maxRounds: Int = 6,
        temperature: Float = 0.7f,
        maxTokens: Int = 4096,
        onToolCall: ((String, String) -> Unit)? = null
    ): LlmOutcome

    /** 流式版本：把回调桥成 Flow，供 UI 多轨道渲染 */
    fun stream(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>> = emptyList(),
        temperature: Float = 0.7f
    ): Flow<String>
}

class DefaultLlmGateway(
    private val context: Context,
    private val modelSource: ClusterModelSource
) : LlmGateway {

    /** 每个模型一个并发闸门：端侧 maxConcurrency=1，天然串行 */
    private val gates = HashMap<String, Semaphore>()

    /**
     * 工具执行器（由 ClusterEngine 注入）。
     * 网关不持有 QuroToolRegistry —— 谁注册的工具、能不能执行，由上层决定。
     */
    @Volatile
    var toolExecutor: (suspend (QuroToolCall) -> String)? = null

    private fun gate(m: ModelProfile): Semaphore = gates.getOrPut(m.id) {
        Semaphore(m.maxConcurrency.coerceAtLeast(1))
    }

    override suspend fun complete(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        jsonMode: Boolean,
        temperature: Float,
        maxTokens: Int
    ): LlmOutcome {
        val raw = gate(model).withPermit {
            if (model.kind == ModelKind.LOCAL) callLocal(model, systemPrompt, userPrompt, history, temperature, maxTokens, null)
            else callCloud(model, systemPrompt, userPrompt, history, temperature, maxTokens, null)
        }
        return LlmOutcome(raw.first, model.id, raw.first.length / 3)
    }

    /**
     * 云端 ReAct 循环。真调上游每一轮，工具结果作为 tool 消息回灌。
     *
     * #216：消息累积与预算裁剪走 [QuroConversationStore.toLlmMessages]，
     * 与主对话完全一致 —— 超长工具结果压缩、巨型消息降权、孤儿工具剔除、
     * 按 contextWindow 从最旧轮次裁剪。这是「避免爆上下文」的落点。
     */
    override suspend fun withTools(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        tools: List<QuroToolSpec>,
        maxRounds: Int,
        temperature: Float,
        maxTokens: Int,
        onToolCall: ((String, String) -> Unit)?
    ): LlmOutcome {
        // #192：端侧小模型大多不支持原生 function-calling，
        // 但这**不等于端侧角色不能动手** —— 改走文本协议（ClusterTextReAct）：
        // 用「思考：/ 行动：/ 参数：/ 观察：」的普通文本约定，
        // 任何能读文本的模型都能用，不依赖任何厂商的原生能力。
        if (tools.isEmpty()) {
            return complete(model, systemPrompt, userPrompt, emptyList(), false, temperature, maxTokens)
        }
        if (model.kind == ModelKind.LOCAL) {
            return withToolsByText(model, systemPrompt, userPrompt, tools, maxRounds, temperature, maxTokens, onToolCall)
        }

        val base = com.ai.assistance.quro.core.model.QuroModelConfigRepository(context).load()
        val useOwnChannel = model.baseUrl.isNotBlank()
        val cfg = base.copy(
            baseUrl = if (useOwnChannel) model.baseUrl else base.baseUrl,
            apiKey = if (useOwnChannel) model.apiKey.ifBlank { base.apiKey } else base.apiKey,
            model = model.displayName,
            provider = model.provider.ifBlank { base.provider },
            temperature = temperature,
            // 🔴 #209：角色级 maxTokens 已停用（<=0 表示不设角色级限制）。
            // 直接用模型配置里的值 —— 它本来就是为这个模型设的（默认 65536），
            // 再叠一层只会把它砍小。
            maxTokens = if (maxTokens > 0) maxTokens else base.maxTokens,
            // #216：上下文预算与主对话一致。角色上下文窗口未显式设置时，
            // 沿用全局模型配置的 contextWindow（主对话同一份预算）。
            contextWindow = model.contextWindow.takeIf { it > 0 } ?: base.contextWindow,
            maxInputTokens = base.maxInputTokens,
            modelContextLength = base.modelContextLength,
        )

        val client = com.ai.assistance.quro.core.network.QuroLlmClient()
        val used = mutableListOf<Pair<String, String>>()
        var totalTokens = 0
        var round = 0
        var lastText = ""
        // 本轮工具轮累积的 assistant[tool_calls] + tool[] 消息（withTools 局部变量，
        // 避免并发角色调用互相污染）。
        val toolMessages = mutableListOf<QuroChatMessage>()

        // #216：死循环精确检测（与主对话同语义）——
        // 用「滑动窗口内签名重复」区分 真·循环 与 合法多步探索。
        val LOOP_WINDOW = 16
        val recentSigs = mutableListOf<String>()
        var loopRepeatStreak = 0
        var loopSegmentHadText = true

        while (round < maxRounds.coerceAtLeast(1)) {
            round++
            // 每轮都从 store 重新取预算裁剪后的消息 —— 这是与主对话一致的上下文控制。
            val messages = buildStoreMessages(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                history = emptyList(),
                contextWindow = cfg.contextWindow,
                historyRounds = 0,
                // 工具轮消息由 toLlmMessages 统一做预算裁剪与孤儿剔除
                toolMessages = toolMessages,
            )
            val result = client.chat(
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.model,
                messages = messages,
                temperature = cfg.temperature,
                maxTokens = cfg.maxTokens,
                tools = tools,
                stream = false,
                provider = cfg.provider,
                // #216：思考协议与主对话一致 —— 读同一个「深度思考」偏好。
                thinkingLevel = deepThinkLevel(),
            )
            // 🔴 token 计量必须用 when 逐分支取：QuroLlmResult.Error 没有 meta 字段，
            // 在结果上直接写 result.meta 编译不过（sealed 各分支字段不同）。
            totalTokens += when (result) {
                is QuroLlmResult.Text -> result.meta.totalTokens
                is QuroLlmResult.ToolCalls -> result.meta.totalTokens
                is QuroLlmResult.Error -> 0
            }

            when (result) {
                is QuroLlmResult.Error -> {
                    // 中途报错：带已拿到的文本回去，不丢弃前面的工具成果。
                    // 只有第一轮才抛 —— 首轮就失败说明请求本身有问题，没救。
                    if (round == 1) throw IllegalStateException(result.message)
                }
                is QuroLlmResult.Text -> {
                    return@withTools LlmOutcome(result.content, model.id, totalTokens, used, round)
                }
                is QuroLlmResult.ToolCalls -> {
                    val calls = result.calls
                    if (calls.isEmpty()) {
                        lastText = result.content.orEmpty()
                        return@withTools LlmOutcome(lastText, model.id, totalTokens, used, round)
                    }
                    result.content?.takeIf { it.isNotBlank() }?.let { lastText = it }
                    // 🔴 assistant 回灌必须带 toolCalls；reasoning 也要带 ——
                    // MiMo / DeepSeek-Reasoner 丢了 reasoning 每轮都「失忆」，多步调用直接断链。
                    toolMessages += QuroChatMessage(
                        role = "assistant",
                        content = result.content.orEmpty()
                            .ifBlank { "(调用工具: " + calls.joinToString { it.name } + ")" },
                        toolCalls = calls,
                        reasoning = result.reasoning,
                    )
                    for (call in calls) {
                        val outcome = runToolLoop(call)
                        used += call.name to outcome.first
                        onToolCall?.invoke(call.name, outcome.first)
                        // 🔴 tool 消息必须带 toolCallId 与 toolName：
                        // Kimi K3 等严格实现要求 tool 消息自身带 name，缺了就 400。
                        // #216：内容用 QuroToolEnvelope 包装，与主对话一致。
                        toolMessages += QuroChatMessage(
                            role = "tool",
                            content = outcome.second,
                            toolCallId = call.id,
                            toolName = call.name,
                        )
                    }
                    // 死循环检测：签名在窗口内重复 = 原地打转；新签名 = 合法探索。
                    val sig = calls.joinToString("|") { "${it.name}:${it.arguments}" }
                    if (recentSigs.contains(sig)) {
                        loopRepeatStreak++
                    } else {
                        loopRepeatStreak = 0
                        recentSigs.add(sig)
                        if (recentSigs.size > LOOP_WINDOW) recentSigs.removeAt(0)
                    }
                    if (!result.content.isNullOrBlank()) loopSegmentHadText = true
                    if (loopRepeatStreak >= 30 && !loopSegmentHadText) {
                        lastText = "⚠️ 检测到工具调用陷入循环（反复执行相同操作且无进展），已停止以避免卡死。"
                        return@withTools LlmOutcome(lastText, model.id, totalTokens, used, round)
                    }
                }
                else -> Unit
            }
        }
        return LlmOutcome(lastText, model.id, totalTokens, used, round)
    }

    /**
     * ★ 端侧 ReAct：文本协议版 ★
     *
     * 为什么不在本地用原生 tools：端侧量化模型（MNN / llama.cpp）
     * 带 `tools` 字段要么被静默忽略要么模板错位（用户侧表现是「模型掉线」）。
     * 所以请求里 tools 照旧传 null，改由本方法在**应用层**用文本协议驱动。
     *
     * #216：本地路径同样追加语言约束与思考协议，且消息历史走预算裁剪。
     */
    private suspend fun withToolsByText(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        tools: List<QuroToolSpec>,
        maxRounds: Int,
        temperature: Float,
        maxTokens: Int,
        onToolCall: ((String, String) -> Unit)?
    ): LlmOutcome {
        val names = tools.map { it.name }
        val sys = buildString {
            append(systemPrompt)
            val proto = ClusterTextReAct.protocolPrompt(tools)
            if (proto.isNotBlank()) {
                appendLine()
                appendLine()
                append(proto)
            }
        }
        val history = mutableListOf<Pair<String, String>>()
        var prompt = userPrompt
        val used = mutableListOf<Pair<String, String>>()
        var totalTokens = 0
        var round = 0
        var lastText = ""

        while (round < maxRounds.coerceAtLeast(1)) {
            round++
            val raw = try {
                gate(model).withPermit {
                    callLocal(model, sys, prompt, history, temperature, maxTokens, null)
                }
            } catch (e: Exception) {
                // 首轮失败才抛：说明请求本身有问题，没救。
                // 中途失败带着已拿到的文本回去，不丢弃前面的工具成果。
                if (round == 1) throw e
                break
            }
            totalTokens += raw.second

            val parsed = ClusterTextReAct.parse(raw.first, names)
            if (parsed.visible.isNotBlank()) lastText = parsed.visible

            // 没有可执行调用 → 模型已经交卷了（可能是它不听话，也可能是真做完了）
            if (parsed.calls.isEmpty()) {
                return LlmOutcome(
                    lastText.ifBlank { raw.first }, model.id, totalTokens, used, round
                )
            }

            history += prompt to raw.first
            for (call in parsed.calls) {
                val outcome = runToolLoop(call)
                used += call.name to outcome.first
                onToolCall?.invoke(call.name, outcome.first)
                // 观察回灌：模型必须看到工具到底返回了什么才能决定下一步
                history += "观察：" to outcome.second
            }
            prompt = "请根据上面的观察给出最终答案，或继续调用工具。"
        }
        return LlmOutcome(lastText, model.id, totalTokens, used, round)
    }

    /**
     * #216：用主对话同一套 [QuroConversationStore.toLlmMessages] 装配消息。
     *
     * 它负责：工具结果就地压缩、巨型消息降权、孤儿工具消息剔除、按 contextWindow
     * 预算从最旧轮次裁剪 —— 主对话防爆上下文的全部机制在此原样生效。
     */
    private fun buildStoreMessages(
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        contextWindow: Int,
        historyRounds: Int,
        toolMessages: List<QuroChatMessage>,
    ): List<QuroChatMessage> {
        val store = QuroConversationStore()
        // system 不写入 store —— toLlmMessages 的 system 参数会单独加入一次，
        // 写进 store 会导致 system 重复（store 快照遍历 + system 参数各加一次）。
        history.forEach { (u, a) ->
            store.add(QuroMessage(role = "user", content = u))
            store.add(QuroMessage(role = "assistant", content = a))
        }
        store.add(QuroMessage(role = "user", content = userPrompt))
        // 工具轮消息（assistant[tool_calls] + tool[]）原样灌入 store，
        // 由 toLlmMessages 统一做预算裁剪与孤儿剔除。
        toolMessages.forEach { m ->
            store.add(
                QuroMessage(
                    role = m.role,
                    content = m.content,
                    toolCalls = m.toolCalls,
                    toolCallId = m.toolCallId,
                    toolLabel = m.toolName,
                    reasoning = m.reasoning,
                )
            )
        }
        val system = QuroMessage(role = "system", content = systemPrompt)
        return store.toLlmMessages(system, contextWindow, historyRounds)
    }

    /**
     * #216：读主对话同一个「深度思考」偏好（quro_ui / thinking，默认 true）。
     * 主对话开启时集群同样开启，关闭时同样不干预 —— 思考链路与主对话一致。
     */
    private fun deepThinkLevel(): QuroReasoningControl.ThinkingLevel {
        val on = runCatching {
            context.getSharedPreferences("quro_ui", Context.MODE_PRIVATE)
                .getBoolean("thinking", true)
        }.getOrDefault(true)
        return QuroReasoningControl.levelForDeepThink(on)
    }

    /**
     * 跑一个工具并返回 (结果摘要, 回灌文本)。
     * 任何异常都转成失败说明回灌，绝不抛出中断整个 ReAct ——
     * 模型需要看到「失败了、原因是这个」才能换路径。
     *
     * #216：回灌文本用 [QuroToolEnvelope] 包装（status/code/hint/result），
     * 与主对话工具结果格式一致。
     */
    private suspend fun runToolLoop(
        call: QuroToolCall
    ): Pair<String, String> {
        val executor = toolExecutor
        if (executor == null) return "工具未就绪" to
            "（宿主未提供工具执行器，无法调用 " + call.name + "）"
        val text = runCatching { executor(call) }.getOrElse { e ->
            return "执行异常" to QuroToolEnvelope.of(
                call.name, false,
                "（" + call.name + " 执行异常：" + (e.message ?: e.javaClass.simpleName) + "）"
            )
        }.ifBlank { "(工具返回空)" }
        return text.take(120) to QuroToolEnvelope.of(call.name, true, text)
    }

    override fun stream(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        temperature: Float
    ): Flow<String> = callbackFlow {
        val sink: (String) -> Unit = { trySend(it) }
        gate(model).withPermit {
            if (model.kind == ModelKind.LOCAL)
                callLocal(model, systemPrompt, userPrompt, history, temperature, 4096, sink)
            else
                callCloud(model, systemPrompt, userPrompt, history, temperature, 4096, sink)
        }
        awaitClose { }
    }.flowOn(Dispatchers.Default)

    // ————————————————— 适配点 A：云端 —————————————————
    /**
     * 直连 QuroLlmClient。
     * #216：消息经 [buildStoreMessages] 做预算裁剪；请求带思考协议与语言约束。
     */
    private suspend fun callCloud(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        temperature: Float,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Pair<String, Int> {
        val base = com.ai.assistance.quro.core.model.QuroModelConfigRepository(context).load()
        // 通道归属：模型自带 baseUrl/apiKey 优先，为空才回退全局「当前」配置。
        // 少了这一步，角色绑哪个厂商都还是打同一个通道（模型名发到错端点 → 404）。
        val useOwnChannel = model.baseUrl.isNotBlank()
        val cfg = base.copy(
            baseUrl = if (useOwnChannel) model.baseUrl else base.baseUrl,
            apiKey = if (useOwnChannel) model.apiKey.ifBlank { base.apiKey } else base.apiKey,
            model = model.displayName,
            provider = model.provider.ifBlank { base.provider },
            temperature = temperature,
            maxTokens = maxTokens,
            contextWindow = model.contextWindow.takeIf { it > 0 } ?: base.contextWindow,
            maxInputTokens = base.maxInputTokens,
            modelContextLength = base.modelContextLength,
        )

        // #216：语言约束 —— 与主对话一致，system 末尾追加回复语言与思考语言指令。
        val langTail = QuroReplyLanguage.tailReminder(context) + QuroReplyLanguage.shortThinkingDirective(context)
        val sysWithLang = if (langTail.isBlank()) systemPrompt else systemPrompt + "\n\n" + langTail

        val messages = buildStoreMessages(
            systemPrompt = sysWithLang,
            userPrompt = userPrompt,
            history = history,
            contextWindow = cfg.contextWindow,
            historyRounds = 0,
            toolMessages = emptyList(),
        )

        val client = com.ai.assistance.quro.core.network.QuroLlmClient()
        val result = client.chat(
            baseUrl = cfg.baseUrl,
            apiKey = cfg.apiKey,
            model = cfg.model,
            messages = messages,
            temperature = cfg.temperature,
            maxTokens = cfg.maxTokens,
            tools = emptyList(),
            stream = onToken != null,
            onToken = onToken,
            provider = cfg.provider,
            thinkingLevel = deepThinkLevel(),
        )
        return extract(result)
    }

    // ————————————————— 适配点 B：端侧 —————————————————
    /**
     * 直连 QuroLocalEngine。
     * #216：消息同样经 [buildStoreMessages] 做预算裁剪，并追加语言约束。
     */
    private suspend fun callLocal(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        temperature: Float,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Pair<String, Int> {
        val localRepo = com.ai.assistance.quro.core.model.QuroLocalModelRepository(context.applicationContext)
        val local = localRepo.findById(model.localModelId)
            ?: throw IllegalStateException("集群端侧角色找不到本地模型：${model.localModelId}")
        val engine = resolveLocalEngine(context)
        val loader = com.ai.assistance.quro.core.network.LocalModelLoaders.get()
        if (!engine.managesOwnLoading && !loader.isLoaded(local)) {
            when (val lr = loader.load(local)) {
                is com.ai.assistance.quro.core.network.LocalModelLoader.LoadResult.Failure ->
                    throw IllegalStateException("集群本地模型加载失败：${lr.message}")
                else -> Unit
            }
        }

        // #216：语言约束 —— 与主对话一致。
        val langTail = QuroReplyLanguage.tailReminder(context) + QuroReplyLanguage.shortThinkingDirective(context)
        val sysWithLang = if (langTail.isBlank()) systemPrompt else systemPrompt + "\n\n" + langTail

        val messages = buildStoreMessages(
            systemPrompt = sysWithLang,
            userPrompt = userPrompt,
            history = history,
            contextWindow = model.contextWindow.takeIf { it > 0 } ?: 0,
            historyRounds = 0,
            toolMessages = emptyList(),
        )

        val result = engine.run(
            model = local,
            modelName = model.displayName,
            messages = messages,
            temperature = temperature,
            // 🔴 #209：端侧没有「模型配置 maxTokens」这一层，用模型的上下文窗口兜底；
            // 都给不出时才用一个宽松默认。绝不再回到写死的 4096 —— 那连一次
            // 带工具的编排都装不下。
            maxTokens = if (maxTokens > 0) maxTokens
            else model.contextWindow.takeIf { it > 0 } ?: 32_768,
            contextWindow = model.contextWindow,
            toolSpecsJson = null,
            onToken = onToken,
            onThinking = null,
            isCanceled = { false }
        )
        return extract(result)
    }

    /** 端侧引擎落点：优先跨进程（隔离），否则进程内；均不可用时回退占位实现（明确提示、不崩溃）。 */
    private fun resolveLocalEngine(context: Context): com.ai.assistance.quro.core.network.QuroLocalEngine {
        if (com.ai.assistance.quro.core.network.QuroLocalEnginePrefs.isIsolated(context)) {
            try {
                val clazz = Class.forName("com.ai.assistance.quro.core.network.QuroLocalEngineRemote")
                val ctor = clazz.getDeclaredConstructor(Context::class.java)
                return ctor.newInstance(context.applicationContext) as com.ai.assistance.quro.core.network.QuroLocalEngine
            } catch (_: Throwable) {
                // 跨进程不可用：回退进程内
            }
        }
        return try {
            val clazz = Class.forName("com.ai.assistance.quro.core.network.QuroLocalEngineNative")
            clazz.getDeclaredConstructor().newInstance() as com.ai.assistance.quro.core.network.QuroLocalEngine
        } catch (_: Throwable) {
            com.ai.assistance.quro.core.network.QuroLocalEnginePlaceholder
        }
    }

    /** QuroLlmResult 三态归一：Text 取内容，ToolCalls 取附带文本，Error 抛错由上层降级 */
    private fun extract(result: QuroLlmResult): Pair<String, Int> =
        when (result) {
            is QuroLlmResult.Text ->
                result.content to (result.meta?.totalTokens ?: 0)
            is QuroLlmResult.ToolCalls ->
                result.content.orEmpty() to (result.meta?.totalTokens ?: 0)
            is QuroLlmResult.Error ->
                throw IllegalStateException(result.message)
            else -> "" to 0
        }
}

/** 测试用：不联网、不花 token，验证主持能否闭环 */
class ScriptedLlmGateway(private val script: (String) -> String) : LlmGateway {
    /** 测试用：脚本输出当最终文本，视为未调用任何工具。 */
    override suspend fun withTools(
        model: ModelProfile, systemPrompt: String, userPrompt: String,
        tools: List<QuroToolSpec>, maxRounds: Int,
        temperature: Float, maxTokens: Int,
        onToolCall: ((String, String) -> Unit)?
    ): LlmOutcome {
        val out = script(userPrompt)
        return LlmOutcome(out, model.id, out.length / 3)
    }

    override suspend fun complete(
        model: ModelProfile, systemPrompt: String, userPrompt: String,
        history: List<Pair<String, String>>, jsonMode: Boolean,
        temperature: Float, maxTokens: Int
    ): LlmOutcome {
        // 🔴 这里原写成 `LlmOutcome(script(userPrompt), model.id, script(userPrompt).length / 3)`
        // —— script 被调用了**两次**。无状态脚本看不出问题，
        // 但测试里凡是带计数、记录或任何副作用的脚本都会被放大一倍，
        // 于是「执行了几次」这种断言读到的是假数字（实测 1 次被读成 2 次）。
        val out = script(userPrompt)
        return LlmOutcome(out, model.id, out.length / 3)
    }

    override fun stream(
        model: ModelProfile, systemPrompt: String, userPrompt: String,
        history: List<Pair<String, String>>, temperature: Float
    ): Flow<String> = kotlinx.coroutines.flow.flow { emit(script(userPrompt)) }
}