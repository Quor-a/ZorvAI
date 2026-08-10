package com.ai.assistance.quro.core

import android.content.Context
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroLocalModelRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.network.QuroLocalEngine
import com.ai.assistance.quro.core.network.QuroLocalEnginePlaceholder
import com.ai.assistance.quro.core.network.LocalModelLoaders
import com.ai.assistance.quro.core.network.LocalModelLoader
import com.ai.assistance.quro.core.network.QuroLocalToolsCodec
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.tools.QuroToolEngine
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroAttachment
import org.json.JSONObject
import android.util.Log
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.util.QuroStageHints
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
     * 兜底清洗：部分小本地模型会把内部多轮上下文控制指令回显进正文
     * （如「（多轮对话上下文理解：…[第5轮] 你好）」）。这些并非真实回复，
     * 必须从最终展示文本里剥离，避免泄漏到气泡（多轮泄漏防御层）。
     * 根因修复在 QuroChatViewModel：已不再注入 [第N轮] 隐藏 user 消息，
     * 此处仅作二次保险，应对任何残留/其它来源的指令回显。
     */
    private fun sanitizeLeakedInstruction(text: String): String {
        if (text.isEmpty() || text == "(已思考完毕)") return text
        var out = text
        // 剥掉包含特征短语的整段括号（兼容中英文全角/半角括号）
        out = out.replace(
            Regex("[（(][^）)]*?(多轮对话上下文理解|系统发起了新的回复|不要重复前几轮的内容)[^）)]*?[）)]"),
            ""
        )
        // 剥掉独立的 [第N轮] 轮次标记
        out = out.replace(Regex("\\[第\\d+轮\\]"), "")
        return out.trim()
    }

    /**
     * 流式阶段提示判定（Bug「⏳ 正在处理残留」防御层）：统一委托共享实现
     * [com.ai.assistance.quro.util.QuroStageHints]，与持久化迁移的判定严格一致，消除漂移。
     */
    private fun isTransientStageHint(text: String): Boolean = QuroStageHints.isTransientStageHint(text)

    /**
     * 用户已在外部把 user 消息写入 store。这里执行编排，返回最终答复文本。
     * @param onUpdate 每次会话状态变更（含中间的工具调用 / 工具结果）后回调，
     *                 用于驱动界面实时刷新（如对话气泡即时显示「正在调用工具…」）。
     */
    /**
     * 高置信判断工具返回是否「失败」。
     * 设计目的：原逻辑用裸子串（含「失败/错误/异常/超时」）即判失败，正文里恰好提到这些字样的
     * 成功结果会被误判，进而误灌「停止重试」提示、打断正常工具调用。
     * - 优先看明确成功信号（成功/success/ok/done…）→ 不算失败；
     * - 其次匹配高置信失败短语（「调用失败」「error:」「Traceback」「http 5」…）；
     * - 兜底：仅当结果很短（≤200 字，纯错误回执）时才接受裸「失败/错误/异常/超时」整词，长文不误判。
     */
    private fun toolResultLooksFailed(text: String): Boolean {
        val t = text.lowercase()
        if (t.contains("成功") || t.contains("\"ok\"") || t.contains("\"success\"")
            || t.contains("execution success") || t.contains("操作成功") || t.contains("done")
        ) return false
        val strong = arrayOf(
            "调用失败", "执行失败", "请求失败", "操作失败", "运行失败", "任务失败", "连接失败",
            "授权失败", "初始化失败", "加载失败", "生成失败", "保存失败", "提交失败", "下载失败",
            "无法执行", "执行出错", "执行异常", "发生错误", "出现错误", "报错", "未就绪",
            "不可用", "无响应", "权限不足", "没有权限", "连接超时", "连接被拒绝", "找不到",
            "error:", "error_code", "[error]", "exception in thread", "traceback",
            "http 4", "http 5", "timed out", "connection refused", "command not found", "no such file",
        )
        if (strong.any { t.contains(it) }) return true
        if (text.length <= 200) {
            if (t.contains("失败") || t.contains("错误") || t.contains("异常") || t.contains("超时")
                || t.contains("error") || t.contains("exception") || t.contains("timeout")
            ) return true
        }
        return false
    }

    /**
     * 深度思考指令：仅当用户显式开启「深度思考」时注入，要求模型在回答前充分推理；
     * 关闭时注入轻量指令，避免无谓的长篇推理。弥补此前「深度思考」开关只控制 UI 显隐、
     * 从不真正影响模型行为的缺陷（对所有模型通用：非推理模型被引导多想，推理模型本就在想）。
     */
    private fun buildDeepThinkDirective(deepThink: Boolean): String = if (deepThink) {
        "\n\n## 深度思考（已开启）\n在回答前，请先进行充分、深入的内部思考（可包含逐步推理、方案权衡、自我质疑与纠错），确保回答严谨准确后再输出。简单问题轻量思考即可，复杂问题务必深入，不要为了快而草率作答。"
    } else {
        "\n\n## 回答风格（轻量模式）\n请直接、自然地回答，无需展开冗长的推理过程；除非问题本身需要，否则不要铺垫思考步骤。"
    }

    suspend fun ask(
        context: Context,
        cfg: QuroModelConfig,
        systemPrompt: String = "",
        autoSaveMemory: Boolean = true,
        stream: Boolean = false,
        historyRounds: Int = 0,
        deepThink: Boolean = false,
        onUpdate: (() -> Unit)? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val system = QuroMessage(
                role = "system",
                content = systemPrompt + buildDeepThinkDirective(deepThink),
            )
            var lastText = ""
            // 流式占位：首个 token 到达时创建可见气泡，后续 token 增量更新其内容。
            // 工具调用轮不会触发 content token，因此不会误建气泡。
            var streamPlaceholderId: String? = null
            var lastStreamEmitMs = 0L
            // 🔧 #765 防御：记录流式累计文本，终态 result.content 异常空白时回退到此，避免正文被截断覆盖。
            var streamedContent: String = ""
            val emit = { onUpdate?.invoke() }
            QuroAgentTrace.status("assistant", "AI 开始响应")
            // 本地离线模型使用独立的设置（localTemperature / localMaxTokens / localEnableTools），
            // 与云端模型完全隔离——用户改离线设置不影响云端，反之亦然。
        val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
        val effTemperature = if (isLocal) cfg.localTemperature else cfg.temperature
        val effMaxTokens = if (isLocal) cfg.localMaxTokens else cfg.maxTokens
        val effEnableTools = if (isLocal) cfg.localEnableTools else cfg.enableTools
        // 🔧 诊断：云模型 provider / model 打印，便于「部分模型不回复」类问题定位（结合 QuroLlm 日志的 reasoning 分支判断）。
        Log.i("QuroAssistant", "ask route: provider=${cfg.provider} model=${cfg.model} isLocal=$isLocal stream=$stream")
            // 工具集选择（原创）：默认 coreSpecs（14 个，token 占用小，兼容绝大多数 API 中转，
            // 避免代理因 tools 数量/总 token 超限而静默丢弃整个 tools 字段 → 模型拿不到工具只能纯问答）。
            // 用户在设置开启「完整工具集」后切换为 fullSpecs（~50 个，需代理支持大负载）。
            val toolSpecs: List<QuroToolSpec> = if (!effEnableTools) {
                emptyList()
            } else if (cfg.useFullTools) {
                registry.fullSpecs()
            } else {
                registry.coreSpecs()
            }
            // 记忆开关关闭时摘除 memory_* 工具，与系统提示词中的记忆段保持一致（都不注入）
            val effectiveSpecs = if (autoSaveMemory) toolSpecs else toolSpecs.filter { !it.name.startsWith("memory_") }
            Log.i("QuroAssistant", "tool mode=${if (!effEnableTools) "off" else if (cfg.useFullTools) "full(${toolSpecs.size})" else "core(${toolSpecs.size})"}")
            // 工具调用轮次：0=不限制（默认），ReAct 循环持续到模型返回最终 Text 答复，
            // **没有步数上限，可一直链式编排直到任务真正完成**。
            // 仅保留一个极高的安全天花板（默认 2000，真实任务远不会触及）作最后兜底；
            // 真正防死循环的机制是下方的「重复调用检测」，而非低轮次封顶。
            // 离线模型（1.2B）工具编排能力弱，一旦进入工具循环极易卡死/乱码；
            // 单独收紧上限到 12 轮（云端仍是 2000 兜底），保证离线工具任务必定终止、不冻结。
            val roundLimit = if (isLocal) 12 else if (cfg.maxToolRounds <= 0) 2000 else cfg.maxToolRounds
            var round = 0
            var prevCallSig: String? = null   // 上一轮工具调用签名，用于死循环检测
            var repeatStreak = 0
            var warnedForSig: String? = null  // 同一失败签名只提示一次，避免每条重复失败都再灌一条 [系统提示]
            while (round < roundLimit) {
                // 协作取消点：用户点击「停止生成」取消父 Job 后，下一轮循环立即抛 CancellationException，
                // 避免生成协程在「思考中」卡死无法中断（配合下方 client.chat 的取消透传）。
                coroutineContext[Job]?.ensureActive()
                round++
                // 任何一步抛异常都兜底成错误文本，绝不让协程崩掉导致界面「卡死在思考中」
                val llmMessages = runCatching { store.toLlmMessages(system, cfg.contextWindow, historyRounds) }.getOrElse { emptyList() }
                // 流式增量回调（云端 / 本地离线模型**共用**）。参数 acc 为「累计文本」。
                // ⚠️ #1112 修复：此前本地（MNN / llama.cpp）路径压根不传 onToken，且下方 streaming
                //   还对本地强制置 false —— 本地推理整条链零流式。手机 CPU 上一次生成动辄数十秒到
                //   数分钟，UI 在跑完之前一个字都拿不到，用户观感就是「不闪退但也不回复」。
                //   现在两条路一致，本地也每 token 即时上屏。
                val emitStreamToken: (String) -> Unit = { acc ->
                    // 首个 token：创建可见占位气泡；其后增量更新内容。
                    // 节流 emit 到 ~100ms（≈10 帧/秒）：既让 AI 回复「一点一点」顺滑冒字，
                    // 又不至于每个 token 都触发一次重组把低端机拖卡。
                    // 🔧 #765 防御：store 已线程安全；这里再包 runCatching，万一对 store 的写仍抛异常，
                    //   仅跳过本次 emit 而不让 streamChat 的 catch 把整段输出吞成截断文本。
                    streamedContent = acc
                    runCatching {
                        if (streamPlaceholderId == null) {
                            val p = QuroMessage(role = "assistant", content = acc)
                            store.add(p)
                            streamPlaceholderId = p.id
                            lastStreamEmitMs = System.currentTimeMillis()
                            emit()
                        } else {
                            store.update(streamPlaceholderId!!) { it.copy(content = acc) }
                            val now = System.currentTimeMillis()
                            if (now - lastStreamEmitMs >= 100L) {
                                lastStreamEmitMs = now
                                emit()
                            }
                        }
                    }
                    Unit
                }
                // 🧠 流式思考回调：思考段边产出边实时上屏（与可见文本双通道并行）。
                // 思考流可能先于可见文本到达，此时先建一条空 content 占位气泡；
                // 后续可见文本流到达时 emitStreamToken 会接着填 content（copy 只改 content，不丢 reasoning）。
                val emitThinkingToken: (String) -> Unit = { acc ->
                    runCatching {
                        if (streamPlaceholderId == null) {
                            val p = QuroMessage(role = "assistant", content = "", reasoning = acc)
                            store.add(p)
                            streamPlaceholderId = p.id
                            lastStreamEmitMs = System.currentTimeMillis()
                            emit()
                        } else {
                            store.update(streamPlaceholderId!!) { it.copy(reasoning = acc) }
                            val now = System.currentTimeMillis()
                            if (now - lastStreamEmitMs >= 100L) {
                                lastStreamEmitMs = now
                                emit()
                            }
                        }
                    }
                    Unit
                }
                val result = runCatching {
                    if (cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP") {
                        // 本地离线模型（MNN / llama.cpp）：走本地推理引擎，不发起 HTTP 请求。
                        // contextWindow 必须下传：本地会话据此决定 n_ctx，否则原生层按 2048 截断 prompt。
                        //
                        // ⏳ #1113：本地路径在「加载 GGUF → 建 context → prefill 整段 prompt」这段时间里
                        // 一个 token 都不会产出，手机 CPU 上常需 5~60 秒。此前 UI 全程空白，用户无法区分
                        // 「正在算」和「已经死了」（观感就是"一直进行中却不回复"）。这里先推一条占位文案，
                        // 首个真 token 到达时会被 emitStreamToken 的累计文本整体覆盖，不会残留。
                        if (stream) {
                            // 🔧 v454 修复「等待气泡做不出来」：此前这里离线一上来就 emitStreamToken
                            // 建了一条「⏳ 正在加载本地模型…」占位气泡 → 聚合时 hasAssistantMsg=true →
                            // ChatScreen 的 WaitingDots（等等小组件）那条 `if (!hasAssistantMsg)` 永远进不去，
                            // 跳动圆点永远不显示。现在不再预先建占位气泡：等待期由 ChatScreen 的
                            // WaitingDots 独立呈现（内容区 loading，头像/名字不变）；首个真 token 到达时
                            // emitStreamToken 自然建气泡并覆盖。resident 会话的 prefill 进度（"正在处理提示词… X%"）
                            // 仍由 generateLlama 的 onProgress 实时推送，不冲突。
                            Unit
                        }
                        routeLocal(
                            context,
                            cfg,
                            llmMessages,
                            if (stream) emitStreamToken else null,
                            if (effEnableTools && isLocal) {
                                // 离线模型工具集：保留设备/系统工具（应用已获权限，离线也应可用——
                                // 手电筒、振动、电量、WiFi、网络、传感器、剪贴板、应用、通知、蓝牙、
                                // 时间、设备信息、计算、TTS、闹钟），并仅在 autoSaveMemory 开启时并入记忆库工具。
                                // 此前一刀切只留 memory_*，导致「打开手电筒」等离线设备指令完全无法调用
                                // （用户已确认手机有闪光灯且应用已获 CAMERA 权限）。
                                // 仍排除 skill__* 技能工具与重型云端专属工具，避免 1.2B 模型工具过多卡死/乱码。
                                val deviceToolNames = setOf(
                                    "toggle_flashlight", "vibrate",
                                    "get_battery", "get_wifi_info", "get_network_info", "get_sensors",
                                    "get_clipboard", "set_clipboard",
                                    "list_installed_apps", "launch_app", "search_and_launch_app",
                                    "get_active_notifications", "get_bluetooth_status",
                                    "get_current_time", "get_device_info", "calculate",
                                    "speak", "stop_speak", "set_alarm"
                                )
                                val offlineSpecs = buildList {
                                    addAll(registry.coreSpecs().filter { it.name in deviceToolNames })
                                    if (autoSaveMemory) {
                                        addAll(registry.fullSpecs().filter { it.name.startsWith("memory_") })
                                    }
                                }
                                if (offlineSpecs.isNotEmpty()) QuroLocalToolsCodec.encodeTools(offlineSpecs) else null
                            } else if (effEnableTools && effectiveSpecs.isNotEmpty()) {
                                QuroLocalToolsCodec.encodeTools(effectiveSpecs)
                            } else null,
                            if (stream) emitThinkingToken else null,
                            isCanceled = { coroutineContext[Job]?.isActive != true },
                        )
                    } else {
                        val streaming = stream
                        client.chat(
                            baseUrl = cfg.baseUrl,
                            apiKey = cfg.apiKey,
                            model = cfg.model,
                            messages = llmMessages,
                            temperature = effTemperature,
                            maxTokens = effMaxTokens,
                            tools = effectiveSpecs,
                            stream = streaming,
                            // 注意：v384 已根除重组期重编译正则的 ANR 真凶，此处无需再用 500ms 粗节流保命。
                            onToken = if (streaming) emitStreamToken else null,
                            // 🧠 补齐云模型流式思考上屏通道（此前只有本地路径有，云端被简化掉了）。
                            // 与 emitThinkingToken 对称：思考先到 → 建 reasoning 占位；content 到达 → 填充正文。
                            onThinking = if (streaming) emitThinkingToken else null,
                        )
                    }
                }.getOrElse { e ->
                    // 🔑 关键：取消信号（CancellationException）必须原样向上抛，
                    // 否则会被包成「请求失败」假错误，导致「停止生成」后仍落一条报错气泡。
                    if (e is CancellationException) {
                        // 🔧 v454：打断/切走导致生成中止时，清理可能残留的「⏳ 正在处理提示词… X%」
                        // 占位气泡（其 content 仍是进度文案），避免气泡卡在半截进度上。
                        // 置空后聚合阶段会因「无可见内容」自动跳过该占位，只留 ViewModel 追加的「⏹ 已停止生成」。
                        streamPlaceholderId?.let { sid ->
                            runCatching { store.update(sid) { it.copy(content = "", reasoning = null) } }
                        }
                        throw e
                    }
                    // 🔧 #1113-3：错误必须自报家门。此前文案只有「请求失败：xxx」，
                    // 分不清是本地推理挂了还是云端连不上 —— 用户看到 SocketTimeout
                    // 「after 30000ms」时，我方连"到底走的哪条路"都判断不了，白烧几轮。
                    val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
                    val srcTag = if (isLocal) "本地·${cfg.provider}" else "云端·${cfg.provider}"
                    val hint = if (!isLocal && (e is java.net.SocketTimeoutException ||
                            e is java.net.UnknownHostException || e is java.net.ConnectException)) {
                        "\n\n⚠️ 这是**云端网络**请求失败，说明当前会话用的是云模型（provider=${cfg.provider}），" +
                            "不是本地离线模型。若你本意是用本地模型，请到「模型配置」重新选中本地模型并确认已「加载」。"
                    } else ""
                    QuroDiag.log(
                        "AskFail",
                        "provider=${cfg.provider} model=${cfg.model} local=$isLocal " +
                            "err=${e.javaClass.simpleName}: ${e.message}"
                    )
                    QuroLlmResult.Error("请求失败[$srcTag]：${e.message}$hint")
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
                        // 🔧 #879-B3：#765 防御记录的流式累计文本适时兜底——若客户端流式正常产出、
                        // 但最终 QuroLlmResult.Text.content 却为空（与 QuroLlmClient 行为不一致，多见于
                        // 本地离线引擎边界），用 streamedContent 回退，避免正文被「(已思考完毕)」覆盖。
                        val safeContent = sanitizeLeakedInstruction(
                            result.content.takeIf { it.isNotBlank() && !isTransientStageHint(it) }
                                ?: streamedContent.takeIf { it.isNotBlank() && !isTransientStageHint(it) }
                                ?: "(已思考完毕)"
                        )
                        val safeReasoning = result.reasoning?.takeIf { it.isNotBlank() }
                        lastText = safeContent
                        if (streamPlaceholderId != null) {
                            // 流式已逐字把内容写入占位气泡：这里仅补回 reasoning 字段并做终态收尾，
                            // 不再重复落库，避免「双气泡」。
                            store.update(streamPlaceholderId!!) { it.copy(content = lastText, reasoning = safeReasoning) }
                            emit()
                            return@withContext lastText
                        }
                        // 非流式（或流式未触发任何 content token，如纯 reasoning 的 MiMo reason 模式）：
                        // 按原逻辑落一条新气泡。
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
                        // 🔑 工具调用轮保留模型给出的前缀正文（content），与 reasoning 各走各字段：
                        // 模型常先说一句「好的，我来查一下…」再发起 tool_calls——此前 content 被强制清空，
                        // 导致工具轮干瘪、无法「回复 + 工具」自由混合（用户报「回复简短/不能组合」的根因）。
                        // 仅避免把 reasoning 当 content（那才是合规问题）；模型显式给的 content 一律保留，
                        // ChatScreen 聚合时并入气泡正文，回传时也携带，利于链式多步工具编排。
                        val assistantMsg = QuroMessage(
                            role = "assistant",
                            content = result.content ?: "",
                            toolCalls = callsWithId,
                            reasoning = roundReasoning,
                            hidden = true,
                        )
                        // 🔧 #879-B1：本地模型首次加载时推过一条「⏳ 正在加载本地模型…」可见占位气泡
                        // （streamPlaceholderId）。若本轮直接是工具调用（无正文 token），该占位不会被
                        // 终态 Text 覆盖 → 残留为可见气泡。工具调用轮的真实内容落在 hidden 占位里，
                        // 故此处显式删除加载占位，绝不残留「⏳ 正在加载本地模型并处理上下文…」。
                        if (streamPlaceholderId != null) {
                            store.remove(streamPlaceholderId!!)
                            streamPlaceholderId = null
                        }
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
                        val t0 = System.currentTimeMillis()
                        val results = runCatching { engine.execute(context, callsWithId) }
                            .getOrElse { e -> callsWithId.map { QuroToolResult(it.name, "工具执行异常：${e.message}") } }
                        val dur = System.currentTimeMillis() - t0
                        // 🔑 关键：把执行结果**回填进 assistant 消息的 toolCalls**（自包含）。
                        // UI 之后直接从这一条 assistant 消息读出「工具名 + 参数 + 结果」三件套，
                        // 彻底不再依赖「跨消息 resultMap 按 toolCallId 匹配 role=tool 结果」这种脆弱写法——
                        // 后者一旦 role=tool 消息被丢 / 被迁移裁剪 / id 错位，工具块就会「缺失结果」。
                        // 🔧 #879：同时回填本次执行耗时 durationMs，供 UI 展示「耗时 Xms」。
                        val enrichedCalls = callsWithId.zip(results) { call, r -> call.copy(result = r.result, durationMs = dur) }
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
                        // AI 发文件：工具 attach_file 成功 → 作为可见 AI 气泡附件呈现（图片/视频/文档直接预览）
                        val aiAttPairs = callsWithId.zip(results).mapNotNull { (call, r) ->
                            if (call.name != "attach_file") return@mapNotNull null
                            parseAttachFileResult(r.result)?.let { att ->
                                val cap = runCatching { JSONObject(r.result).optString("caption", "") }.getOrDefault("")
                                att to cap
                            }
                        }
                        if (aiAttPairs.isNotEmpty()) {
                            val caption = aiAttPairs.firstOrNull()?.second ?: ""
                            store.add(
                                QuroMessage(
                                    role = "assistant",
                                    content = caption,
                                    attachments = aiAttPairs.map { it.first },
                                )
                            )
                            emit()
                        }
                        // 轨迹：把工具执行结果写入总线
                        callsWithId.zip(results).forEach { (c, r) ->
                            QuroAgentTrace.result("tool", c.name, r.result)
                        }
                        // 🔁 工具调用重复 → 区分「成功重复」与「失败重试」（非代码强制中断）：
                        // 模型「连续请求完全相同的同一组工具调用」（name+arguments 一致）本身不等于出错——
                        // 成功的任务也可能合法地多次调用同一工具（如批量 input_text 逐字输入、连续 tap_screen 点击）。
                        // 真正需要干预的信号是：调用重复「且」工具返回结果本身呈现失败特征
                        // （含「失败/错误/异常/超时」或 error/exception/timeout 等关键词），说明该调用未生效、模型在盲目重试。
                        // 此时才把「工具失败」信号作为 hidden system 提示回灌，由 AI 自决结束旧尝试、换思路继续；代码不直接中断。
                        // 结果正常的重复调用（合法成功场景）完全不干预，连计数都不累积，避免误伤。
                        // 仅保留极高兜底（repeatStreak>=10 且持续失败）：AI 长时间收到提示仍不纠正才强制停止防卡死。
            val sig = result.calls.joinToString("|") { "${it.name}:${it.arguments}" }
            if (sig == prevCallSig) {
                // 仅当本次重复调用的工具结果确为「失败」（高置信判定，避免正文提到失败/错误字样就误判）时，才视为失败重试：
                val anyFailed = results.any { toolResultLooksFailed(it.result) }
                if (anyFailed) {
                    repeatStreak++
                    // 同一失败签名只提示一次，避免每条重复失败都再灌一条 [系统提示] 污染上下文/打扰模型
                    if (warnedForSig != sig) {
                        val failedTool = result.calls.firstOrNull()?.name ?: "工具"
                        store.add(
                            QuroMessage(
                                role = "system",
                                content = "[系统提示] 你连续多次调用了完全相同的工具「$failedTool」（参数也相同），" +
                                    "且其返回结果持续包含失败/错误信息，说明该调用很可能未生效。" +
                                    "请主动结束当前尝试，重新思考任务目标，换用不同的工具或方法，不要继续重试同一调用。",
                                hidden = true,
                            ),
                        )
                        warnedForSig = sig
                    }
                    if (repeatStreak >= 10) {
                        lastText = "⚠️ 检测到工具调用长时间陷入重复失败（未自行纠正），已停止以避免卡死。可调整指令或检查工具后重试。"
                        store.add(QuroMessage(role = "assistant", content = lastText))
                        emit()
                        return@withContext lastText
                    }
                } else {
                    // 结果正常：合法的「成功重复调用」，完全不干预，重置计数避免误累积
                    repeatStreak = 0
                    warnedForSig = null
                    prevCallSig = sig
                }
            } else {
                repeatStreak = 0
                warnedForSig = null
                prevCallSig = sig
            }
                    }
                    is QuroLlmResult.Error -> {
                        // 纯同步：chat() 自带 5xx/429 重试与友好错误提示，失败即明确展示报错气泡。
                        lastText = "⚠️ ${result.message}"
                        // #1113：若流式占位气泡已创建（本地路径的「⏳ 正在加载…」或云端已冒出的半截文本），
                        // 必须**复用**它写入错误，否则会残留一条占位气泡 + 再多一条错误气泡（两条并排）。
                        val ph = streamPlaceholderId
                        if (ph != null) {
                            runCatching { store.update(ph) { it.copy(content = lastText) } }
                        } else {
                            store.add(QuroMessage(role = "assistant", content = lastText))
                        }
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
     * 通过反射交给 full 风味的原生引擎 [QuroLocalEngineNative] 执行；fdroid 风味回退
     * [QuroLocalEnginePlaceholder]（原生运行时未编入，给出明确提示，不崩溃）。
     */
    private fun routeLocal(
        context: Context,
        cfg: QuroModelConfig,
        messages: List<QuroChatMessage>,
        onToken: ((String) -> Unit)? = null,
        toolSpecsJson: String? = null,
        onThinking: ((String) -> Unit)? = null,
        isCanceled: () -> Boolean = { false },
    ): QuroLlmResult {
        val repo = QuroLocalModelRepository(context.applicationContext)
        val all = repo.loadAll()
        // 优先用 holder 里已加载的模型——用户在「模型配置」点了「加载」的那个就是 holder 里的。
        // load() 会先 unload 旧模型再加载新的，所以 holder 里的永远是用户最后加载的。
        // 不再死按 cfg.localModelPath 匹配——cfg 可能因各种原因没同步（比如卡片选择和加载按钮脱节）。
        val loader = LocalModelLoaders.get()
        val local = all.firstOrNull { loader.isLoaded(it) }
            ?: all.firstOrNull { it.path == cfg.localModelPath }
            ?: all.firstOrNull { it.type.name == cfg.provider }
        if (local == null) {
            return QuroLlmResult.Error(
                "未找到已登记的本地模型（${cfg.provider}）。请到「模型配置 → 本地离线模型」添加并选择。"
            )
        }
        return resolveLocalEngine().run(
            local,
            cfg.model,
            compactForLocal(messages),
            cfg.localTemperature,
            cfg.localMaxTokens,
            cfg.contextWindow,
            toolSpecsJson,
            onToken,
            onThinking,
            isCanceled,
        )
    }

    /**
     * 本地路径**兜底**瘦身（#1113）。
     *
     * [QuroChatViewModel.buildSystemPrompt] 已针对本地 provider 走极简分支，这里是第二道闸门，
     * 覆盖两类漏网情况：
     *   1. 其它入口（语音球 / 键盘 / 未来新增调用方）直接拼了一份完整版 system prompt；
     *   2. 人格卡正文 / 记忆条目本身就写得很长，即便走了极简分支仍然超预算。
     *
     * 为什么必须自己截：原生层 `nativeGenerateStream` 超长时是从 **头部** 丢 token
     * （`promptTokens.erase(begin, begin+drop)`），会把身份/人格整段砍掉、只留尾巴，
     * 模型直接失忆。这里反过来 **保头部丢尾部**，让身份始终活下来。
     *
     * 预算取值：maxSystemChars / maxTotalChars 按常驻会话实际 n_ctx 推导——
     * usableTokens = n_ctx - 预留（1/4 n_ctx 或最多 1024，给生成留余量），
     * maxTotalChars = usableTokens / 0.75 * 0.9（0.75 ≈ chars/token，0.9 留 10% 余量），
     * maxSystemChars = maxTotalChars * 0.42（system 占比 ≤ 42%，保护对话历史）。
     * n_ctx 未知时回退到保守默认 3072 token。
     */
    private fun compactForLocal(messages: List<QuroChatMessage>): List<QuroChatMessage> {
        // ⚠️ #1113-2 回滚：上一轮误判「after 30000ms」是 prefill 超时，把预算砍到 800/2000，
        // 结果把用户配置好的人设/系统提示词腰斩。真凶是 OkHttp SocketTimeoutException
        // （failed to connect ... after 30000ms），与 prompt 长度无关。恢复原预算，
        // 不再拿用户的人设去换一个根本不存在的超时。
        //
        // D2-1a：预算不再硬编码，按常驻会话真实 n_ctx 推导，避免 n_ctx=6144 时
        // 预算仍按 8192 算导致超截，或 n_ctx=4096 时预算过大导致原生层头部截断。
        val ctxTokens = runCatching { LocalModelLoaders.get().residentCtxTokens() }.getOrDefault(0)
        val usableTokens = if (ctxTokens > 0) ctxTokens - maxOf(32, minOf(1024, ctxTokens / 4)) else 3072
        val maxTotalChars = (usableTokens / 0.75f * 0.9f).toInt()
        val maxSystemChars = (maxTotalChars * 0.42f).toInt()
        val rawTotal = messages.sumOf { it.content.length }

        // 1) system 超预算 → 保留头部（身份在最前），尾部裁掉并留一行说明
        var out = messages.map { m ->
            if (m.role.equals("system", true) && m.content.length > maxSystemChars) {
                m.copy(content = m.content.take(maxSystemChars).trimEnd() + "\n（后续设定因本地上下文限制已省略）")
            } else {
                m
            }
        }

        // 2) 总量仍超预算 → 从**最旧的非 system 消息**开始丢，保住 system 与最新几轮对话
        if (out.sumOf { it.content.length } > maxTotalChars) {
            val kept = ArrayDeque<QuroChatMessage>()
            var used = out.filter { it.role.equals("system", true) }.sumOf { it.content.length }
            for (m in out.asReversed()) {
                if (m.role.equals("system", true)) continue
                if (used + m.content.length > maxTotalChars && kept.isNotEmpty()) break
                kept.addFirst(m)
                used += m.content.length
            }
            out = out.filter { it.role.equals("system", true) } + kept
        }

        val finalTotal = out.sumOf { it.content.length }
        if (finalTotal != rawTotal || out.size != messages.size) {
            QuroDiag.log(
                "LocalPrompt",
                "⚠ 本地兜底瘦身 | msgs ${messages.size}→${out.size} | chars $rawTotal→$finalTotal " +
                    "(超出本地预算，已保头部裁尾部)"
            )
        } else {
            QuroDiag.log("LocalPrompt", "本地 prompt 规模 OK | msgs=${out.size} | chars=$finalTotal")
        }

        // 🔧 孤儿工具消息清理：上面「丢最旧非 system 消息」可能把 tool 配对的一方裁掉，
        // 留下孤儿（role=tool 结果找不到对应 assistant 调用，或 assistant 调用找不到对应 tool 结果）。
        // 孤儿会让下游模型把非法上下文当成正常轮次 → 表现为「乱回复 / 不回复」。
        // 压缩后成对校验，剔除残缺配对，保证下发给模型的工具上下文自洽。
        val callIds = out.filter { !it.toolCalls.isNullOrEmpty() }
            .flatMap { it.toolCalls!!.map { c -> c.id } }.toSet()
        val resultIds = out.filter { it.role == "tool" }
            .mapNotNull { it.toolCallId }.toSet()
        val beforeOrphan = out.size
        out = out.filterNot { m ->
            (m.role == "tool" && m.toolCallId != null && m.toolCallId !in callIds) ||
            (m.toolCalls.orEmpty().any { it.id !in resultIds })
        }
        if (out.size != beforeOrphan) {
            QuroDiag.log("LocalPrompt", "🔧 剔除孤儿工具消息 | ${beforeOrphan - out.size} 条（call/result 配对残缺）")
        }
        return out
    }

    /**
     * 在 full 风味下通过反射实例化原生本地引擎 [QuroLocalEngineNative]；
     * fdroid 风味未编译该类，反射失败回退 [QuroLocalEnginePlaceholder]（明确提示、不崩溃）。
     */
    private fun resolveLocalEngine(): QuroLocalEngine {
        return try {
            val clazz = Class.forName("com.ai.assistance.quro.core.network.QuroLocalEngineNative")
            clazz.getDeclaredConstructor().newInstance() as QuroLocalEngine
        } catch (_: Throwable) {
            QuroLocalEnginePlaceholder
        }
    }
}

/** 解析 attach_file 工具返回的 JSON，构造成可渲染的 QuroAttachment（失败返回 null）。 */
private fun parseAttachFileResult(result: String): QuroAttachment? {
    return runCatching {
        val o = JSONObject(result)
        if (!o.optBoolean("ok", false)) return null
        val name = o.optString("name", "")
        val type = o.optString("type", "file")
        val path = o.optString("path", "")
        val size = o.optLong("size", 0)
        if (name.isBlank() || path.isBlank()) return null
        val mime = when (type) {
            "image" -> "image/*"
            "video" -> "video/*"
            else -> "application/octet-stream"
        }
        QuroAttachment(type = type, uri = path, name = name, mime = mime, size = size)
    }.getOrNull()
}
