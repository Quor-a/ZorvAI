package com.ai.assistance.quro.core

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.parseCard
import com.ai.assistance.quro.core.cards.serializeCard
import com.ai.assistance.quro.util.QuroStageHints
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 多会话持久化（原创）：把多个对话（含消息、工具调用）以 JSON 落盘到应用私有目录，
 * 退出 APP 后再次打开可完整恢复历史聊天记录。
 * 仅依赖 Android 自带的 org.json，不引入任何序列化第三方库。
 */

/** 历史列表中展示用的轻量元数据。 */
data class QuroConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
)

/** 一个完整对话（含全部消息），用于内存与落盘。 */
data class QuroPersistedConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 保留对话轮数：null = 跟随模型默认（contextWindow）；N>0 = 仅保留最近 N 个 (用户+助手) 轮次。 */
    val historyRounds: Int? = null,
    val messages: List<QuroMessage>,
)

class QuroConversationRepository(context: Context) {
    private val file = File(context.filesDir, "quro_conversations.json")

    fun loadAll(): List<QuroPersistedConversation> {
        return runCatching {
            if (!file.exists()) return emptyList()
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val root = JSONObject(text)
            val arr = root.optJSONArray("conversations") ?: return emptyList()
            val raw = mutableListOf<QuroPersistedConversation>()
            for (i in 0 until arr.length()) {
                runCatching { parseConv(arr.getJSONObject(i)) }.getOrNull()?.let { raw.add(it) }
            }
            // 迁移：自愈旧版本遗留的脏数据（hidden 字段不存在、管道消息泄漏、垃圾工具结果）。
            // 每次加载时检查是否需要清理；仅当发现脏数据时才回写，避免每次启动都写磁盘。
            val healed = migrateAndClean(raw)
            Log.i("QuroPersist", "LOAD rawConvs=${raw.size} rawMsgs=${raw.sumOf { it.messages.size }} healedMsgs=${healed.sumOf { it.messages.size }}")
            if (healed !== raw) {
                saveAll(healed)  // 回写修复后的数据，下次不再重复迁移
            }
            healed
        }.getOrElse { emptyList() }
    }

    /**
     * 数据自愈（兼容旧版本持久化格式）：
     *
     * 1. **隐藏管道消息**：role=="tool" 或 assistant+带 toolCalls → 强制 hidden=true
     *    （parseMsg 已处理；此处做二次保险 + 清理垃圾内容）
     * 2. **空内容管道消息**：assistant 消息 content 为空且带 toolCalls → 删除（纯占位，无信息价值）
     * 3. **垃圾工具结果**：tool 结果 content 为 "33." 开头或 "<100" 等异常值 → 删除
     * 4. **空对话**：删除后没有任何真实 user/assistant 文本消息的会话 → 整个删除
     */
    private fun migrateAndClean(raw: List<QuroPersistedConversation>): List<QuroPersistedConversation> {
        var needsSave = false
        val cleaned = raw.mapNotNull { conv ->
            // 逐条迁移：丢弃管道占位、清空垃圾工具结果，保留真实对话
            val out = mutableListOf<QuroMessage>()
            for (m in conv.messages) {
                // 1) 内部管道消息自愈：仅丢弃「无任何可见信息的残留空消息」，
                //    但必须保留承载真实对话内容的消息——这正是「退出重开对话框看不到部分内容」的根因修复：
                //      · 助手思考过程（reasoning 非空）→ 渲染 💭 思考块
                //      · 助手工具调用（toolCalls 非空）→ 渲染 🔧 工具调用块
                //      · 真实工具结果（role==tool 且 toolCallId 非空且 content 非空）→ 回填 🔧 块的结果
                //    旧逻辑「m.hidden 一律丢弃」会连同思考/工具块一起删掉，导致历史对话重启后缺失。
                val hasRealContent = m.content.isNotBlank() ||
                    !m.reasoning.isNullOrBlank() ||
                    !m.toolCalls.isNullOrEmpty() ||
                    !m.attachments.isNullOrEmpty()
                val isRealToolResult = m.role == "tool" && m.toolCallId != null && m.content.isNotBlank()
                val isPipeDrop = !hasRealContent && !isRealToolResult
                if (isPipeDrop) { needsSave = true; continue }
                // 1.5) 🔧 Bug修复「⏳ 正在处理提示没有清理」：流式阶段提示（prefill 进度 /
                //    模型加载 / 思考中等占位文案）曾被旧版本作为真实 assistant 消息落盘
                //    （生成被打断/进程被杀时 commitCurrent 把半截占位持久化）。
                //    这类消息没有任何信息价值，加载时直接丢弃（但保留携带 reasoning/工具/卡片的）。
                if (m.role == "assistant" && m.reasoning.isNullOrBlank() &&
                    m.toolCalls.isNullOrEmpty() && m.attachments.isNullOrEmpty() && m.cards.isEmpty() &&
                    isTransientStageHint(m.content)
                ) { needsSave = true; continue }
                // 2) 工具结果：保留消息，但把已知的垃圾内容清空
                //    （旧版 bug 曾把 33.333… / "OK" / 纯数字 0-100 当成工具结果落盘，
                //     重启后会以「→ 33.333…」的形式污染对话气泡）
                if (m.role == "tool") {
                    val isGarbage = m.content.startsWith("33.") || m.content == "OK" ||
                        (m.content.matches(Regex("^\\d+\\.?\\d*$")) && (m.content.toDoubleOrNull() ?: 999.0) in 0.0..100.0)
                    if (isGarbage) { needsSave = true; out.add(m.copy(content = "")) }
                    else out.add(m)
                    continue
                }
                // 3) 撤销旧版「把 reasoning 回填进 content」的自愈（v201 修正）：
                //    思考应仅以 ThinkBubble 独立呈现；把 reasoning 混入正文会把 HTML 标签泄漏到气泡正文
                //    （用户截图确诊：气泡里直接渲染出原始 <...> 标签，思考混乱）。
                //    仅当检测到旧数据确已被污染——content 非空且恰好等于 reasoning 且含 '<'——
                //    才把 content 清空修复；正常回合（content 为真实文本）一律原样保留，绝不注入思考。
                //    纯思考回合（content 为空、reasoning 有值）保持 content 为空，UI 仅显示 ThinkBubble，正确。
                val healLeak = m.role == "assistant"
                    && !m.reasoning.isNullOrBlank()
                    && m.content == m.reasoning
                    && m.content.contains("<")
                if (healLeak) { needsSave = true; out.add(m.copy(content = "")) }
                else out.add(m)
            }
            // 过滤后若连一条可见消息都不剩（纯管道空壳），才丢弃整个会话；
            // 否则即使「没有真实 user/assistant 文本」，也保留残存的思考/工具块，
            // 绝不用单条占位符覆盖——避免把用户尚能查看的历史一并销毁
            // （这正是「退出重开旧对话只剩思考/工具块、文本已丢」场景的保护：不再二次破坏）。
            if (out.isEmpty()) {
                needsSave = true
                return@mapNotNull null  // 删除空/全管道会话
            }
            conv.copy(messages = out)
        }
        return if (needsSave) cleaned else raw
    }

    fun saveAll(list: List<QuroPersistedConversation>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(serializeConv(it)) }
            val root = JSONObject().put("conversations", arr)
            file.writeText(root.toString())
        }
    }

    /**
     * 流式阶段提示判定（Bug「⏳ 正在处理残留」）：统一委托共享实现
     * [com.ai.assistance.quro.util.QuroStageHints]，与 QuroAssistant 的判定严格一致，消除漂移。
     */
    private fun isTransientStageHint(content: String): Boolean =
        QuroStageHints.isTransientStageHint(content)

    private fun serializeConv(c: QuroPersistedConversation): JSONObject {
        val msgs = JSONArray()
        c.messages.forEach { msgs.put(serializeMsg(it)) }
        return JSONObject().apply {
            put("id", c.id)
            put("title", c.title)
            put("createdAt", c.createdAt)
            put("updatedAt", c.updatedAt)
            put("historyRounds", c.historyRounds ?: JSONObject.NULL)
            put("messages", msgs)
        }
    }

    private fun serializeMsg(m: QuroMessage): JSONObject {
        // 🛑 关键修复：绝不能把「无工具调用」序列化成空数组 "toolCalls": []。
        // 旧实现无条件 `val calls = JSONArray()` 会对每条消息（含纯文本终答 / 用户消息）
        // 都写入空数组；reload 时 parseMsg 读到空数组 → toolCalls 变成非 null 空列表 →
        // uiMessages 的 `m.toolCalls != null` 判定为 true → 走「工具调用块」分支、text 被强制 null
        // → 退出重开后所有文本消息正文消失（仅剩 ThinkBubble）。这正是「live 有、reload 无」的真凶。
        // 修复：仅当 toolCalls 非 null 且非空时才写该字段；否则省略（reload 时 parseMsg 会得到 null）。
        val calls = m.toolCalls?.takeIf { it.isNotEmpty() }?.let { tcs ->
            JSONArray().also { arr ->
                tcs.forEach { call ->
                    arr.put(
                        JSONObject().apply {
                            put("id", call.id)
                            put("name", call.name)
                            put("arguments", call.arguments)
                            put("result", call.result ?: JSONObject.NULL)
                        },
                    )
                }
            }
        }
        val atts = m.attachments?.takeIf { it.isNotEmpty() }?.let { als ->
            JSONArray().also { arr ->
                als.forEach { a ->
                    arr.put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("type", a.type)
                            put("uri", a.uri)
                            put("name", a.name)
                            put("mime", a.mime)
                            put("size", a.size)
                        },
                    )
                }
            }
        }
        return JSONObject().apply {
            put("id", m.id)
            put("role", m.role)
            put("content", m.content)
            put("toolCallId", m.toolCallId ?: JSONObject.NULL)
            put("toolLabel", m.toolLabel ?: JSONObject.NULL)
            put("reasoning", m.reasoning ?: JSONObject.NULL)
            put("createdAt", m.createdAt)
            put("hidden", m.hidden)
            calls?.let { put("toolCalls", it) }
            atts?.let { put("attachments", it) }
            // 🛑 关键修复（v201）：持久化气泡内富组件。v201 前遗漏 cards 字段，
            // 导致退出/切换界面重进后全部可视化组件彻底丢失。
            m.cards.takeIf { it.isNotEmpty() }?.let { cls ->
                put("cards", JSONArray().also { arr -> cls.forEach { c -> arr.put(serializeCard(c)) } })
            }
        }
    }

    private fun parseConv(o: JSONObject): QuroPersistedConversation {
        val msgsArr = o.optJSONArray("messages") ?: JSONArray()
        val msgs = mutableListOf<QuroMessage>()
        for (i in 0 until msgsArr.length()) {
            runCatching { parseMsg(msgsArr.getJSONObject(i)) }.getOrNull()?.let { msgs.add(it) }
        }
        // 旧 JSON 无 historyRounds 字段 → null → 跟随模型默认（向后兼容）
        val historyRounds = if (o.has("historyRounds") && !o.isNull("historyRounds")) o.optInt("historyRounds", -1).let { if (it < 0) null else it } else null
        return QuroPersistedConversation(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "新对话"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            historyRounds = historyRounds,
            messages = msgs,
        )
    }

    private fun parseMsg(o: JSONObject): QuroMessage {
        val callsArr = o.optJSONArray("toolCalls")
        val calls = if (callsArr != null) {
            val list = mutableListOf<QuroToolCall>()
            for (i in 0 until callsArr.length()) {
                val c = callsArr.getJSONObject(i)
                list.add(
                    QuroToolCall(
                        id = c.optString("id", ""),
                        name = c.optString("name", ""),
                        arguments = c.optString("arguments", ""),
                        result = if (c.has("result") && !c.isNull("result")) c.optString("result") else null,
                    ),
                )
            }
            list
        } else {
            null
        }
        val attsArr = o.optJSONArray("attachments")
        val atts = if (attsArr != null) {
            val list = mutableListOf<QuroAttachment>()
            for (i in 0 until attsArr.length()) {
                val a = attsArr.getJSONObject(i)
                list.add(
                    QuroAttachment(
                        id = a.optString("id", UUID.randomUUID().toString()),
                        type = a.optString("type", "file"),
                        uri = a.optString("uri", ""),
                        name = a.optString("name", "file"),
                        mime = a.optString("mime", "application/octet-stream"),
                        size = a.optLong("size", 0),
                    ),
                )
            }
            list
        } else {
            null
        }
        // 🛑 关键修复（v201）：恢复气泡内富组件。v201 前 parseMsg 不读 cards，
        // 导致重进后组件丢失；此处按 cardType 还原全部类型，失败卡片忽略不中断加载。
        val cardsArr = o.optJSONArray("cards")
        val cards = if (cardsArr != null) {
            val list = mutableListOf<QuroChatCard>()
            for (i in 0 until cardsArr.length()) {
                runCatching { parseCard(cardsArr.getJSONObject(i)) }.getOrNull()?.let { list.add(it) }
            }
            list
        } else emptyList()
        val toolCallId = if (o.has("toolCallId") && !o.isNull("toolCallId")) o.optString("toolCallId") else null
        val toolLabel = if (o.has("toolLabel") && !o.isNull("toolLabel")) o.optString("toolLabel") else null
        val reasoning = if (o.has("reasoning") && !o.isNull("reasoning")) o.optString("reasoning") else null
        val role = o.optString("role", "user")
        val hasToolCalls = calls?.isNotEmpty() ?: false
        // 自修复（兼容旧版本未持久化 hidden 的历史数据）：
        // 凡是「工具结果（role==tool）」或「助手发出的工具调用占位（带 toolCalls）」，
        // 一律视为内部管道消息并隐藏，避免重启后空内容气泡 / 垃圾工具结果外泄。
        // 真实用户/助手文本消息（role=user/assistant 且无 toolCalls）保持可见。
        val hidden = if (o.has("hidden")) o.optBoolean("hidden", false)
        else (role == "tool" || hasToolCalls)
        return QuroMessage(
            id = o.optString("id", UUID.randomUUID().toString()),
            role = role,
            content = o.optString("content", ""),
            toolCallId = toolCallId,
            toolCalls = calls,
            toolLabel = toolLabel,
            reasoning = reasoning,
            attachments = atts,
            cards = cards,
            hidden = hidden,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}
