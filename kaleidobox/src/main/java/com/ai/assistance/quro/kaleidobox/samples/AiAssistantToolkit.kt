package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：AI 对话框（完整聊天客户端）。
 *
 * 直接调用宿主已配的 AI 引擎（[ai.chat]），并补齐一个聊天 app 该有的能力：
 *   - 多会话管理：新建 / 切换 / 重命名 / 删除，全部持久化（[data.kv]）；
 *   - 重新生成、编辑并重发、复制末条回复、导出对话；
 *   - **世界书注入**：发送前用 [worldbook.entries] 取世界书，按关键词扫描并把命中条目注入 system。
 */
class AiAssistantToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    private val kvSessions = "ai_sessions"

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_aiassistant" -> KValue.Str("AI 对话框：多会话聊天，支持重新生成、编辑重发，并按世界书关键词自动注入背景。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 会话持久化

    private fun loadSessions(): MutableList<MutableMap<String, Any?>> {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to kvSessions)) ?: KValue.Null
        val parsed = runCatching { Json.parse((r as? KValue.Str)?.value ?: "") }.getOrNull()
        val out = mutableListOf<MutableMap<String, Any?>>()
        (parsed as? List<*>)?.forEach { row ->
            val m = row as? Map<*, *> ?: return@forEach
            val mm = LinkedHashMap<String, Any?>()
            m.forEach { (k, v) -> mm[k.toString()] = v }
            out.add(mm)
        }
        return out
    }

    private fun saveSessions(list: List<Map<String, Any?>>) {
        host?.call(
            "data.kv",
            KValue.obj("op" to "set", "key" to kvSessions, "value" to Json.write(KValue.of(list))),
        )
    }

    private fun newSession(): MutableMap<String, Any?> = mutableMapOf(
        "id" to "s_${System.currentTimeMillis()}",
        "title" to "新对话",
        "messages" to mutableListOf<Any?>(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun msgsOf(session: Map<String, Any?>?): MutableList<MutableMap<String, Any?>> {
        val raw = session?.get("messages") as? List<*> ?: return mutableListOf()
        return raw.mapNotNull { it as? Map<*, *> }.map { m ->
            val mm = LinkedHashMap<String, Any?>()
            m.forEach { (k, v) -> mm[k.toString()] = v }
            mm
        }.toMutableList()
    }

    private fun sessionsFromState(state: Map<String, Any?>): MutableList<MutableMap<String, Any?>> {
        val raw = state["sessions"] as? List<*>
        if (raw == null) {
            val loaded = loadSessions()
            return if (loaded.isEmpty()) mutableListOf(newSession()) else loaded
        }
        return raw.mapNotNull { it as? Map<*, *> }.map { m ->
            val mm = LinkedHashMap<String, Any?>()
            m.forEach { (k, v) -> mm[k.toString()] = v }
            mm
        }.toMutableList()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        var sessions = sessionsFromState(state)
        if (sessions.isEmpty()) sessions = mutableListOf(newSession())
        val activeId = (state["sessionId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (sessions.first()["id"] as? String).orEmpty()
        val session = sessions.firstOrNull { it["id"] == activeId } ?: sessions.first()
        val messages = msgsOf(session)
        val input = (state["input"] as? String) ?: ""
        val persona = (session["system"] as? String) ?: ""
        val aiReady = isAiAvailable()

        val content = buildList<UiNode> {
            add(SamplesUi.section("sec", "AI 对话框 · ${messages.size} 条消息"))
            add(sessionBar(sessions, activeId))
            // 角色卡 / 系统提示：本会话生效，与世界书注入叠加
            add(
                SamplesUi.field(
                    "persona", "角色设定 / 系统提示（本会话生效）",
                    Bound.Ref("persona", persona), Action.of("persona"),
                    singleLine = false, minH = 72,
                )
            )

            if (!aiReady) add(
                SamplesUi.hint(
                    "ai_warn",
                    "⚠ AI 引擎未配置：请在 ZorvAI 主对话「设置 → 模型」里填写 API Key / BaseURL / 模型，本插件复用主对话的同一个 AI。"
                )
            )
            if (messages.isEmpty())
                add(SamplesUi.hint("bub_empty", "（这个会话还没有消息，下面说点什么吧）"))
            else messages.forEachIndexed { i, m ->
                val role = (m["role"] as? String) ?: "ai"
                add(SamplesUi.bubble("b$i", role, (m["text"] as? String) ?: ""))
                val tools = (m["tools"] as? List<*>)?.mapNotNull { it?.toString() }
                if (!tools.isNullOrEmpty())
                    add(SamplesUi.hint("bt$i", "🛠 本次调用了工具：\n" + tools.joinToString("\n")))
            }
        }

        val hasAi = messages.any { (it["role"] as? String) == "ai" }
        val hasUser = messages.any { (it["role"] as? String) == "user" }

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
                if (hasAi) SamplesUi.secondaryAction("regen", "重新生成") else UiNode.Spacer("sp0", 0),
                if (hasUser) SamplesUi.secondaryAction("editLast", "编辑末条提问并重发") else UiNode.Spacer("sp1", 0),
                if (hasAi) SamplesUi.secondaryAction("copy", "复制末条回复") else UiNode.Spacer("sp2", 0),
                UiNode.Row(
                    "expRow",
                    children = listOf(
                        SamplesUi.secondaryAction("export", "导出会话", Mod(weight = 1f)),
                        SamplesUi.secondaryAction(
                            "clearSession", "清空本会话",
                            Mod(weight = 1f, padding = Edges(0, 0, 0, 6)),
                        ),
                    ),
                ),
            ),
        )
    }

    /** 会话条：切换 + 新建 + 删除 + 重命名。 */
    private fun sessionBar(sessions: List<Map<String, Any?>>, activeId: String): UiNode = UiNode.Column(
        "sbar",
        modifier = Mod(padding = Edges(0, 0, 0, 8)),
        children = listOf(
            UiNode.Scroll(
                "sbar_scroll", vertical = false,
                modifier = Mod(width = Size.Fill),
                child = UiNode.Row(
                    "sbar_r",
                    children = sessions.map { s ->
                        val id = (s["id"] as? String).orEmpty()
                        val title = (s["title"] as? String)?.ifBlank { "未命名" } ?: "未命名"
                        UiNode.Button(
                            "s_$id", Bound.Lit(title.take(12)), Action.of("switch", "id" to id),
                            variant = if (id == activeId) UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                            modifier = Mod(padding = Edges(0, 0, 0, 8)),
                        )
                    },
                ),
            ),
            UiNode.Row(
                "sbar_ops",
                children = listOf(
                    SamplesUi.button("newSession", "新建会话", variant = UiNode.Button.Variant.TONAL, modifier = Mod(weight = 1f)),
                    SamplesUi.button("renameSession", "重命名", variant = UiNode.Button.Variant.TONAL,
                        modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 6))),
                    SamplesUi.button("delSession", "删除会话", variant = UiNode.Button.Variant.TONAL,
                        modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 8))),
                ),
            ),
        ),
    )

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val state = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()

        var sessions = sessionsFromState(state)
        if (sessions.isEmpty()) sessions = mutableListOf(newSession())
        var activeId = (state["sessionId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (sessions.first()["id"] as? String).orEmpty()
        var input = (state["input"] as? String) ?: ""
        var persona = (state["persona"] as? String)
            ?: (sessions.firstOrNull { it["id"] == activeId }?.get("system") as? String) ?: ""

        fun active(): MutableMap<String, Any?> =
            sessions.firstOrNull { it["id"] == activeId } ?: sessions.first().also { activeId = it["id"] as String }
        fun setMessages(list: List<Map<String, Any?>>) {
            active()["messages"] = list.toMutableList()
            saveSessions(sessions)
        }

        when (actionId) {
            "input" -> input = payload["value"]?.asString() ?: ""

            "persona" -> {
                persona = payload["value"]?.asString() ?: ""
                active()["system"] = persona
                saveSessions(sessions)
            }

            "clearSession" -> {
                setMessages(emptyList())
                input = ""
            }

            "switch" -> {
                activeId = payload["id"]?.asString() ?: activeId
                persona = (active()["system"] as? String) ?: ""
                input = ""
            }
            "newSession" -> {
                val ns = newSession()
                sessions.add(0, ns)
                activeId = ns["id"] as String
                input = ""
                persona = ""
                saveSessions(sessions)
            }
            "delSession" -> {
                if (sessions.size > 1) {
                    sessions.removeAll { it["id"] == activeId }
                    activeId = (sessions.first()["id"] as String)
                    input = ""
                    persona = (active()["system"] as? String) ?: ""
                    saveSessions(sessions)
                } else {
                    host?.call("ui.toast", KValue.obj("text" to "至少保留一个会话"))
                }
            }
            "renameSession" -> {
                val first = msgsOf(active()).firstOrNull { (it["role"] as? String) == "user" }?.get("text") as? String
                val title = first?.take(16)?.replace("\n", " ") ?: "未命名会话"
                active()["title"] = title
                saveSessions(sessions)
                host?.call("ui.toast", KValue.obj("text" to "已按首条提问重命名"))
            }

            "send" -> {
                val q = input.trim()
                if (q.isNotEmpty()) {
                    val msgs = msgsOf(active())
                    msgs.add(mutableMapOf("role" to "user", "text" to q))
                    val ans = ask(msgs, persona)
                    msgs.add(mutableMapOf("role" to "ai", "text" to ans.first, "tools" to ans.second))
                    setMessages(msgs)
                    if ((active()["title"] as? String) in listOf(null, "", "新对话")) {
                        active()["title"] = q.take(16)
                        saveSessions(sessions)
                    }
                    input = ""
                }
            }
            "regen" -> {
                val msgs = msgsOf(active())
                // 丢弃末尾的 AI 回复，重新生成
                while (msgs.isNotEmpty() && (msgs.last()["role"] as? String) == "ai") msgs.removeAt(msgs.size - 1)
                if (msgs.isNotEmpty()) {
                    val ans = ask(msgs, persona)
                    msgs.add(mutableMapOf("role" to "ai", "text" to ans.first, "tools" to ans.second))
                    setMessages(msgs)
                }
            }
            "editLast" -> {
                val msgs = msgsOf(active())
                val idx = msgs.indexOfLast { (it["role"] as? String) == "user" }
                if (idx >= 0) {
                    input = (msgs[idx]["text"] as? String) ?: ""
                    // 截断到该条之前，用户改完重发
                    setMessages(msgs.subList(0, idx).toList())
                }
            }
            "copy" -> {
                val last = msgsOf(active()).lastOrNull { (it["role"] as? String) == "ai" }?.get("text") as? String
                if (!last.isNullOrBlank()) {
                    host?.call("ui.clipboard", KValue.obj("text" to last))
                    host?.call("ui.toast", KValue.obj("text" to "已复制末条回复"))
                }
            }
            "export" -> {
                val title = (active()["title"] as? String) ?: "会话"
                val text = buildString {
                    appendLine("# $title")
                    msgsOf(active()).forEach { m ->
                        val role = if ((m["role"] as? String) == "user") "我" else "AI"
                        appendLine("$role：${m["text"] ?: ""}")
                        appendLine()
                    }
                }
                host?.call("ui.clipboard", KValue.obj("text" to text))
                host?.call("ui.toast", KValue.obj("text" to "会话已导出到剪贴板"))
            }
        }

        return KValue.obj(
            "sessions" to sessions, "sessionId" to activeId,
            "input" to input, "persona" to persona,
        )
    }

    /** 组装对话历史 + 角色设定 + 世界书注入，调用宿主 AI。返回 (回复文本, 工具列表)。 */
    private fun ask(msgs: List<Map<String, Any?>>, persona: String): Pair<String, List<String>> {
        val history = msgs.map { m ->
            KValue.obj(
                "role" to ((m["role"] as? String) ?: "user").let { if (it == "ai") "assistant" else it },
                "content" to ((m["text"] as? String) ?: ""),
            )
        }
        val r = host?.call(
            "ai.chat",
            KValue.obj(
                "messages" to KValue.Arr(history),
                "system" to buildSystem(msgs, persona),
            ),
        ) ?: KValue.Null
        return when (r) {
            is KValue.Obj -> (r.value["text"]?.asString() ?: "") to
                ((r.value["tools"] as? KValue.Arr)?.value?.mapNotNull { it.asString() } ?: emptyList())
            is KValue.Str -> r.value to emptyList()
            is KValue.Err -> "[${r.code}] ${r.message}" to emptyList()
            else -> "（无响应）" to emptyList()
        }
    }

    /** system 提示：会话角色设定（角色卡）+ 命中世界书条目注入。 */
    private fun buildSystem(msgs: List<Map<String, Any?>>, persona: String): String {
        val base = "你是 ZorvAI 内置的 AI 助手插件，运行在用户的 Android 设备上，直接调用 ZorvAI 已配置的 AI 引擎。" +
            "你拥有以下工具：terminal_run（执行 shell 命令）、web_fetch（抓取网页）、device_info（设备信息）、" +
            "clipboard_get/clipboard_set（读写剪贴板）。当用户需要查资料、执行命令、查设备信息或操作剪贴板时，" +
            "请优先调用对应工具获取真实结果，再给出简体中文、简洁专业的回答。"

        // 角色卡：用户在会话里填的角色设定 / 系统提示
        val head = if (persona.isBlank()) base else "$base\n\n[角色设定]\n$persona"

        // 世界书：扫描最近若干条消息，按关键词命中后注入
        val recent = msgs.takeLast(6).joinToString("\n") { (it["text"] as? String) ?: "" }
        val entries = loadLorebook()
        if (entries.isEmpty() || recent.isBlank()) return head
        val hits = LorebookEngine.scan(entries, recent, recursion = true)
        val injection = LorebookEngine.buildInjection(hits)
        return if (injection.isBlank()) head else "$head\n\n$injection"
    }

    private fun loadLorebook(): List<LoreEntry> {
        val r = runCatching { host?.call("worldbook.entries", KValue.obj()) }.getOrNull() ?: return emptyList()
        return LorebookEngine.parse((r as? KValue.Str)?.value)
    }

    /** 探测宿主是否已配置可用 AI 提供商；探测失败则乐观判定为可用，避免误报。 */
    private fun isAiAvailable(): Boolean {
        val r = runCatching { host?.call("ai.available", KValue.obj()) }.getOrNull() ?: return true
        return when (r) {
            is KValue.Bool -> r.value
            else -> true
        }
    }
}
