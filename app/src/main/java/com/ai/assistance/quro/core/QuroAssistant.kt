package com.ai.assistance.quro.core

import android.content.Context
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroLocalModelRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.network.QuroLocalEnginePlaceholder
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.tools.QuroToolEngine
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.QuroToolResult
import android.util.Log
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Quro 助手编排核心：对话 + 工具调用的 ReAct 式循环。
 * - 把用户消息写入会话
 * - 调用 LLM；若返回工具调用，则执行工具并把结果回灌，再让 LLM 产出最终答复
 */
class QuroAssistant(
    private val client: QuroLlmClient,
    private val registry: QuroToolRegistry,
    private val store: QuroConversationStore,
) {
    private val engine = QuroToolEngine(registry)

    /**
     * 用户已在外部把 user 消息写入 store。这里执行编排，返回最终答复文本。
     * @param onUpdate 每次会话状态变更（含中间的工具调用 / 工具结果）后回调，
     *                 用于驱动界面实时刷新（如对话气泡即时显示「正在调用工具…」）。
     */
    suspend fun ask(
        context: Context,
        cfg: QuroModelConfig,
        systemPrompt: String = "",
        autoSaveMemory: Boolean = true,
        onUpdate: (() -> Unit)? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val system = QuroMessage(role = "system", content = systemPrompt)
            var lastText = ""
            val emit = { onUpdate?.invoke() }
            QuroAgentTrace.status("assistant", "AI 开始响应")
            // 工具集选择（原创）：默认 coreSpecs（14 个，token 占用小，兼容绝大多数 API 中转，
            // 避免代理因 tools 数量/总 token 超限而静默丢弃整个 tools 字段 → 模型拿不到工具只能纯问答）。
            // 用户在设置开启「完整工具集」后切换为 fullSpecs（~50 个，需代理支持大负载）。
            val toolSpecs: List<QuroToolSpec> = if (!cfg.enableTools) {
                emptyList()
            } else if (cfg.useFullTools) {
                registry.fullSpecs()
            } else {
                registry.coreSpecs()
            }
            // 记忆开关关闭时摘除 memory_* 工具，与系统提示词中的记忆段保持一致（都不注入）
            val effectiveSpecs = if (autoSaveMemory) toolSpecs else toolSpecs.filter { !it.name.startsWith("memory_") }
            Log.i("QuroAssistant", "tool mode=${if (!cfg.enableTools) "off" else if (cfg.useFullTools) "full(${toolSpecs.size})" else "core(${toolSpecs.size})"}")
            // 工具调用轮次：0=不限制（默认），ReAct 循环持续到模型返回最终 Text 答复，
            // **没有步数上限，可一直链式编排直到任务真正完成**。
            // 仅保留一个极高的安全天花板（默认 2000，真实任务远不会触及）作最后兜底；
            // 真正防死循环的机制是下方的「重复调用检测」，而非低轮次封顶。
            val roundLimit = if (cfg.maxToolRounds <= 0) 2000 else cfg.maxToolRounds
            var round = 0
            var prevCallSig: String? = null   // 上一轮工具调用签名，用于死循环检测
            var repeatStreak = 0
            while (round < roundLimit) {
                // 协作取消点：用户点击「停止生成」取消父 Job 后，下一轮循环立即抛 CancellationException，
                // 避免生成协程在「思考中」卡死无法中断（配合下方 client.chat 的取消透传）。
                coroutineContext[Job]?.ensureActive()
                round++
                // 任何一步抛异常都兜底成错误文本，绝不让协程崩掉导致界面「卡死在思考中」
                val llmMessages = runCatching { store.toLlmMessages(system, cfg.contextWindow) }.getOrElse { emptyList() }
                val result = runCatching {
                    if (cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP") {
                        // 本地离线模型（MNN / llama.cpp）：走本地推理引擎，不发起 HTTP 请求
                        routeLocal(context, cfg, llmMessages)
                    } else {
                        client.chat(
                            baseUrl = cfg.baseUrl,
                            apiKey = cfg.apiKey,
                            model = cfg.model,
                            messages = llmMessages,
                            temperature = cfg.temperature,
                            maxTokens = cfg.maxTokens,
                            tools = effectiveSpecs,
                        )
                    }
                }.getOrElse { e ->
                    // 🔑 关键：取消信号（CancellationException）必须原样向上抛，
                    // 否则会被包成「请求失败」假错误，导致「停止生成」后仍落一条报错气泡。
                    if (e is CancellationException) throw e
                    QuroLlmResult.Error("请求失败：${e.message}")
                }

                when (result) {
                    is QuroLlmResult.Text -> {
                        // 🛡️ 内容提取：只取模型返回的正式 content；reasoning 绝不进 content。
                        //   此前 content=reasoning 导致「思考 HTML 同时出现在正文气泡和 ThinkBubble」。
                        //   reasoning 仅通过 reasoning 字段传递，由 ChatScreen 的 ThinkBubble 按需渲染。
                        // 🔧 组件卡片不再需要迁移：ui_widget / ui_card 经 onCard 桥落到
                        //   QuroChatViewModel.attachCardToLastAssistant，后者已优先挂到本轮 hidden 占位
                        //   （带 toolCalls），ChatScreen 聚合同回合消息时会把该占位上的 cards 一并渲染进气泡。
                        //   content 始终保持干净，思考绝不泄漏到正文。
                        val hasReasoning = result.reasoning.isNullOrBlank().not()
                        // 🛡️ 当模型（如 MiMo reason 模式）最终一轮 content 为空、仅返回
                        //   reasoning_content 时，真正的答复就藏在 reasoning 里。若仍按「content 空→留空」
                        //   处理，答复会被塞进 ThinkBubble（思考中）而正文气泡为空 —— 用户看到的就是
                        //   「✦ 思考中 · N 工具」却没有实际回复（回复融化到思考里）。
                        // 修复：content 为空且有 reasoning 时，把 reasoning 当作正文落 content，并清空
                        //   reasoning 字段，避免同一段文字既当正文又当思考重复渲染。
                        // 🛡️ v232 修复「思考中内容混入实际回复」：reasoning 绝不进 content。
                        //   此前 content 为空且带 reasoning 时（MiMo 等 reason 模式），会把思考文本当成正文气泡内容，
                        //   表现为「有时候会、有时候不会」地把思考混进回复。现在思考只走 reasoning 字段
                        //   （独立 ThinkBlock 渲染），content 始终干净；若模型最终确实没给正文，仅给极简占位，
                        //   思考过程照常在「思考中」里可见。
                        val safeContent = result.content.takeIf { it.isNotBlank() } ?: "(已思考完毕)"
                        val safeReasoning = result.reasoning?.takeIf { it.isNotBlank() }
                        lastText = safeContent
                        store.add(
                            QuroMessage(
                                role = "assistant",
                                content = lastText,
                                reasoning = safeReasoning,
                            )
                        )
                        emit()
                        return@withContext lastText
                    }
                    is QuroLlmResult.ToolCalls -> {
                        // 同一轮可能返回多个 tool_call（模型批量并发调用）。
                        // ⚠️ 每个 tool_call 必须拥有**唯一** id（OpenAI 协议：assistant 消息里的
                        // tool_calls 各 id 不可重复，tool 结果消息的 tool_call_id 须回指原 call）。
                        // 旧实现把整轮所有 call 都 copy 成同一个 callId → id 撞车、结果对不上，
                        // 导致模型一次性吐多个工具时整轮错乱，只能退化成「一轮一个」。
                        val base = "call_${System.nanoTime()}_$round"
                        val callsWithId = result.calls.mapIndexed { idx, c -> c.copy(id = "${base}_$idx") }
                        // 🔑 关键修复：MiMo 等模型在返回 tool_calls 的同时会附带 reasoning_content
                        // （本轮思考过程）。此前 ToolCalls 结果类型不携带 reasoning → 思考内容被直接丢弃，
                        // 模型下一轮在「失忆」状态下做决策，无法链式编排多步工具调用。
                        // 现在 reasoning 被完整保留在 assistant 消息中，回传给 LLM 时一并携带，
                        // 模型能看到自己上一步的推理并在此基础上继续决策。
                        val roundReasoning = result.reasoning?.takeIf { it.isNotBlank() }
                        // 先落 assistant 占位（带工具调用、结果暂空）→ UI 立即显示「🔧 调用工具…」进度。
                        // ⚠️ 关键修正：工具调用轮的 content 必须为空，思考只走 reasoning 字段。
                        // 此前 content = roundReasoning 会把「思考文本」同时塞进 content 与 reasoning，
                        // ChatScreen 聚合渲染时又把 content 并进回复正文 → 思考与回复文本混合显示。
                        // 且 OpenAI 协议要求带 tool_calls 的 assistant 消息 content 应为空/ null，
                        // content=reasoning 本身也不合规。真正的回复文本在最终 QuroLlmResult.Text 轮才落库。
                        val assistantMsg = QuroMessage(
                            role = "assistant",
                            content = "",
                            toolCalls = callsWithId,
                            reasoning = roundReasoning,
                            hidden = true,
                        )
                        store.add(assistantMsg)
                        emit()
                        Log.i("QuroAssistant", "TOOLCALL round=$round storedCalls=${callsWithId.size} reasoningBlank=${roundReasoning.isNullOrBlank()} ids=${callsWithId.joinToString(","){it.id}}")
                        // 轨迹：把工具调用作为「行动」写入 AI 执行轨迹总线（终端改造后的可视化数据源）
                        callsWithId.forEach { c ->
                            QuroAgentTrace.action("tool", "调用 ${c.name}", c.arguments)
                        }
                        if (roundReasoning != null) {
                            // ⚠️ 清洗思考文本里的 HTML 标签，避免原始 ``/`` 等泄漏到「执行轨迹」面板
                            //   （与气泡正文泄漏同源：模型在 reasoning 里输出 HTML，未过滤直接进轨迹总线 → 行动轨迹异常）
                            val cleanReasoning = roundReasoning
                                .replace(Regex("<[^>]*>"), " ")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                            QuroAgentTrace.thought("llm", "思考", cleanReasoning)
                        }
                        // 工具执行异常不得上抛：降级为每个 call 各一条错误结果，保持 id 配对正确，
                        // 让 LLM 能看到错误并自行兜底答复。
                        val results = runCatching { engine.execute(context, callsWithId) }
                            .getOrElse { e -> callsWithId.map { QuroToolResult(it.name, "工具执行异常：${e.message}") } }
                        // 🔑 关键：把执行结果**回填进 assistant 消息的 toolCalls**（自包含）。
                        // UI 之后直接从这一条 assistant 消息读出「工具名 + 参数 + 结果」三件套，
                        // 彻底不再依赖「跨消息 resultMap 按 toolCallId 匹配 role=tool 结果」这种脆弱写法——
                        // 后者一旦 role=tool 消息被丢 / 被迁移裁剪 / id 错位，工具块就会「缺失结果」。
                        val enrichedCalls = callsWithId.zip(results) { call, r -> call.copy(result = r.result) }
                        store.update(assistantMsg.id) { it.copy(toolCalls = enrichedCalls) }
                        emit()
                        // 仍为 LLM 保留 role=tool 结果管道（下一轮上下文需要，与 UI 展示解耦）。
                        callsWithId.zip(results).forEach { (call, r) ->
                            store.add(
                                QuroMessage(
                                    role = "tool",
                                    content = r.result,
                                    toolCallId = call.id,
                                    toolLabel = r.name,
                                    hidden = true,
                                ),
                            )
                            emit()
                        }
                        // 轨迹：把工具执行结果写入总线
                        callsWithId.zip(results).forEach { (c, r) ->
                            QuroAgentTrace.result("tool", c.name, r.result)
                        }
                        // 🔁 死循环检测（真正的「无限制但防卡死」机制）：
                        // 若模型连续向回请求「完全相同的同一组工具调用」（name+arguments 完全一致），
                        // 说明已陷入重复循环（典型为某工具结果异常，模型反复重试同一动作）。
                        // 此时主动断开，避免无限占用把界面卡死；本轮工具结果已回灌，上下文仍完整。
                        // 正常任务的「不同步骤」永远签名不同，不会被误伤 → 可一直链式编排。
                        val sig = result.calls.joinToString("|") { "${it.name}:${it.arguments}" }
                        if (sig == prevCallSig) {
                            repeatStreak++
                            if (repeatStreak >= 2) {
                                lastText = "⚠️ 检测到工具调用陷入重复循环（连续相同调用），已停止以避免卡死。可调整指令或检查工具返回结果后重试。"
                                store.add(QuroMessage(role = "assistant", content = lastText))
                                emit()
                                return@withContext lastText
                            }
                        } else {
                            repeatStreak = 0
                            prevCallSig = sig
                        }
                    }
                    is QuroLlmResult.Error -> {
                        // 纯同步：chat() 自带 5xx/429 重试与友好错误提示，失败即明确展示报错气泡。
                        lastText = "⚠️ ${result.message}"
                        store.add(QuroMessage(role = "assistant", content = lastText))
                        emit()
                        return@withContext lastText
                    }
                }
            }
            if (lastText.isEmpty()) {
                lastText = if (cfg.maxToolRounds <= 0)
                    "（已达到工具调用安全上限 2000 轮，未能生成最终答复）"
                else
                    "（已达到最大工具轮次 ${cfg.maxToolRounds}，未能生成最终答复）"
            }
            lastText
        }

    /**
     * 本地离线模型路由（原创）：根据 cfg.provider（MNN / LLAMA_CPP）找到已登记的本地模型，
     * 交给 [QuroLocalEnginePlaceholder] 执行（原生运行时未接入时给出明确提示，不崩溃）。
     */
    private fun routeLocal(
        context: Context,
        cfg: QuroModelConfig,
        messages: List<QuroChatMessage>,
    ): QuroLlmResult {
        val repo = QuroLocalModelRepository(context.applicationContext)
        val all = repo.loadAll()
        val local = all.firstOrNull { it.type.name == cfg.provider && it.path == cfg.localModelPath }
            ?: all.firstOrNull { it.type.name == cfg.provider }
        if (local == null) {
            return QuroLlmResult.Error(
                "未找到已登记的本地模型（${cfg.provider}）。请到「模型配置 → 本地离线模型」添加并选择。"
            )
        }
        return QuroLocalEnginePlaceholder.run(local, cfg.model, messages, cfg.temperature, cfg.maxTokens)
    }
}
