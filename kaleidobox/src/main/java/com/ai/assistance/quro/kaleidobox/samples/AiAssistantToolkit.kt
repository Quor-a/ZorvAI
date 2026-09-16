package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：AI 助手（完整 app）。
 *
 * 直接调用宿主已配的 AI 对话引擎（[com.ai.assistance.quro.kaleidobox.android.KaleidoAppBridge.aiChat]），
 * 呈现多轮对话气泡的整屏聊天，并补齐聊天 app 该有的能力：
 *   - 对话【持久化】（[data.kv]，关掉再开还在）；
 *   - 复制末条回复、一键新对话；
 *   - 显示消息条数，工具调用回显。
 */
class AiAssistantToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    private val kvKey = "ai_messages"

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_aiassistant" -> KValue.Str("AI 助手：直接调用宿主已配的 AI 引擎对话，支持多轮与持久化。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 持久化

    private fun loadMessages(): List<Map<String, Any?>> {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to kvKey)) ?: KValue.Null
        val str = (r as? KValue.Str)?.value ?: return emptyList()
        val parsed = runCatching { Json.parse(str) }.getOrNull()
        return (parsed as? List<*>)?.mapNotNull { it as? Map<*, *> }
            ?.map { it.mapKeys { k -> k.key.toString() } } ?: emptyList()
    }

    private fun saveMessages(list: List<Map<String, Any?>>) {
        host?.call(
            "data.kv",
            KValue.obj("op" to "set", "key" to kvKey, "value" to Json.write(KValue.of(list))),
        )
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        var messages = readMessages(state)
        if (messages.isEmpty()) messages = loadMessages()
        val input = (state["input"] as? String) ?: ""
        val aiReady = isAiAvailable()
        val lastAi = messages.lastOrNull { (it["role"] as? String) == "ai" }

        val bubbles = buildList<UiNode> {
            if (!aiReady) add(
                SamplesUi.hint(
                    "ai_warn",
                    "⚠ AI 引擎未配置：请在 ZorvAI 主对话「设置 → 模型」里填写 API Key / BaseURL / 模型，本插件会直接复用主对话的同一个 AI。"
                )
            )
            if (messages.isEmpty())
                add(SamplesUi.hint("bub_empty", "（还没有对话，下面说点什么吧）"))
            else messages.forEachIndexed { i, m ->
                val role = (m["role"] as? String) ?: "ai"
                val text = (m["text"] as? String) ?: ""
                add(SamplesUi.bubble("b$i", role, text))
                val tools = (m["tools"] as? List<*>)?.mapNotNull { it?.toString() }
                if (!tools.isNullOrEmpty()) {
                    add(
                        SamplesUi.hint(
                            "bt$i",
                            "🛠 本次调用了工具：\n" + tools.joinToString("\n"),
                        )
                    )
                }
            }
        }

        val content = buildList<UiNode> {
            add(SamplesUi.section("sec", "AI 助手 · 多轮对话 · ${messages.size} 条消息"))
            addAll(bubbles)
        }

        return SamplesUi.scrollPage(
            "root",
            content = content,
            actions = listOf(
                UiNode.Row(
                    "inRow",
                    modifier = Mod(padding = Edges(0, 0, 0, 8)),
                    children = listOf(
                        UiNode.TextField(
                            "input", Bound.Ref("input"), Action.of("input"),
                            label = "说点什么…", singleLine = true,
                            modifier = Mod(weight = 1f, minHeight = 52),
                        ),
                        UiNode.Button(
                            "send", Bound.Lit("发送"), Action.of("send"),
                            variant = UiNode.Button.Variant.FILLED,
                            modifier = Mod(padding = Edges(0, 0, 0, 8), minHeight = 52),
                        ),
                    ),
                ),
                if (lastAi != null) SamplesUi.secondaryAction("copy", "复制末条回复") else UiNode.Spacer("sp0", 0),
                SamplesUi.secondaryAction("clear", "新对话（清空）"),
            ),
        )
    }

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val state = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()
        var input = (state["input"] as? String) ?: ""
        var messages = readMessages(state).toMutableList()
        if (messages.isEmpty()) messages = loadMessages().toMutableList()

        when (actionId) {
            "input" -> input = payload["value"]?.asString() ?: ""
            "send" -> {
                val q = input.trim()
                if (q.isNotEmpty()) {
                    messages.add(mapOf("role" to "user", "text" to q))
                    // 关键：把完整对话历史（含本轮 user 消息）一并传给引擎，
                    // 这样模型才有"记忆"，真正像助手而非一次性回声。
                    val history = messages.map { m ->
                        KValue.obj(
                            "role" to (m["role"] as? String ?: "user"),
                            "content" to (m["text"] as? String ?: ""),
                        )
                    }
                    val r = host?.call(
                        "ai.chat",
                        KValue.obj(
                            "messages" to KValue.Arr(history),
                            "system" to "你是 ZorvAI 内置的 AI 助手插件，运行在用户的 Android 设备上，直接调用 ZorvAI 已配置的 AI 引擎。" +
                                "你拥有以下工具：terminal_run（执行 shell 命令）、web_fetch（抓取网页）、device_info（设备信息）、" +
                                "clipboard_get/clipboard_set（读写剪贴板）。当用户需要查资料、执行命令、查设备信息或操作剪贴板时，" +
                                "请优先调用对应工具获取真实结果，再给出简体中文、简洁专业的回答。",
                        ),
                    ) ?: KValue.Null
                    // 宿主 ai.chat 返回 KValue.Obj(text, tools)；兼容旧式 Str/Err。
                    val (ans, tools) = when (r) {
                        is KValue.Obj -> (r.value["text"]?.asString() ?: "") to
                            ((r.value["tools"] as? KValue.Arr)?.value?.mapNotNull { it.asString() } ?: emptyList())
                        is KValue.Str -> r.value to emptyList()
                        is KValue.Err -> "[${r.code}] ${r.message}" to emptyList()
                        else -> "（无响应）" to emptyList()
                    }
                    messages.add(mapOf("role" to "ai", "text" to ans, "tools" to tools))
                    input = ""
                    saveMessages(messages)
                }
            }
            "copy" -> {
                val last = messages.lastOrNull { (it["role"] as? String) == "ai" }?.get("text") as? String
                if (!last.isNullOrBlank()) {
                    host?.call("ui.clipboard", KValue.obj("text" to last))
                    host?.call("ui.toast", KValue.obj("text" to "已复制末条回复"))
                }
            }
            "clear" -> {
                messages.clear()
                input = ""
                saveMessages(messages)
            }
        }
        return KValue.obj("input" to input, "messages" to messages)
    }

    /** 探测宿主是否已配置可用 AI 提供商；探测失败则乐观判定为可用，避免误报。 */
    private fun isAiAvailable(): Boolean {
        val r = runCatching { host?.call("ai.available", KValue.obj()) }.getOrNull() ?: return true
        return when (r) {
            is KValue.Bool -> r.value
            else -> true
        }
    }

    private fun readMessages(state: Map<String, Any?>): List<Map<String, Any?>> =
        (state["messages"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
            ?.map { it.mapKeys { k -> k.key.toString() } } ?: emptyList()
}
