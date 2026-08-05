package com.ai.assistance.quro.core

import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.util.QuroDiag
import java.util.UUID

/**
 * 会话消息（原创）。role: system|user|assistant|tool。
 * 支持把工具调用（toolCalls）与工具结果（toolCallId）一并记录，供多轮工具编排使用。
 *
 * [hidden] 标记该消息为内部管道消息（如工具调用占位、工具原始结果），不向用户展示。
 * UI 渲染层应跳过 hidden=true 的消息，但 LLM 上下文组装仍包含它们（toLlmMessages 不受影响）。
 */
data class QuroMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<QuroToolCall>? = null,
    /** 仅用于界面展示的「产生此结果的工具名」，不参与发送给 LLM 的上下文。 */
    val toolLabel: String? = null,
    /** 模型思考过程（reasoning_content），可空；非空时在界面以「思考卡」呈现。 */
    val reasoning: String? = null,
    /** 附件（图片 / 视频 / 文件），随消息一并发送给视觉模型。 */
    val attachments: List<QuroAttachment>? = null,
    /** 气泡内富组件（AI 经 ui_widget / ui_card 下发，合体进聊天气泡，而非底部独立卡片栏）。 */
    val cards: List<QuroChatCard> = emptyList(),
    /** 发送者昵称（用户消息气泡显示用；为空则回退到当前用户资料昵称「我」）。默认 null 以保证旧消息反序列化向后兼容。 */
    val senderName: String? = null,
    /** 发送者头像 URL/Uri（用户消息气泡头像用；为空则回退到当前用户资料头像）。默认 null 以保证向后兼容。 */
    val avatarUrl: String? = null,
    /** 内部管道消息标记：true 时 UI 层不渲染此消息（LLM 上下文仍包含）。默认 false。 */
    val hidden: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 会话存储（原创，内存版；v1 不做落盘以控制风险）。 */
class QuroConversationStore {
    private val messages = mutableListOf<QuroMessage>()
    // 🔧 #765 修复：流式 onToken 在 IO 线程写、UI 在主线程读 → 裸 mutableListOf 跨线程并发损坏
    // （ConcurrentModificationException / IndexOutOfBoundsException），异常被 streamChat catch 吞掉
    // 返回截断文本。改用统一锁保护所有读写，保证线程安全。
    private val lock = Any()

    fun all(): List<QuroMessage> = synchronized(lock) { messages.toList() }
    fun add(msg: QuroMessage) {
        synchronized(lock) { messages.add(msg) }
    }

    /** 按 id 原地更新某条消息（工具执行完后回填结果到 assistant 的 toolCalls）。 */
    fun update(id: String, transform: (QuroMessage) -> QuroMessage) {
        synchronized(lock) {
            val idx = messages.indexOfFirst { it.id == id }
            if (idx >= 0) messages[idx] = transform(messages[idx])
        }
    }

    fun clear() = synchronized(lock) { messages.clear() }

    /** 按 id 删除某条消息（如本地模型加载占位气泡在工具调用轮需清除，避免残留可见）。 */
    fun remove(id: String) {
        synchronized(lock) { messages.removeAll { it.id == id } }
    }

    /**
     * 转为发送给 LLM 的消息列表（保留工具调用/结果上下文）。
     *
     * @param contextWindow 输入 token 预算（0=不限制）。非 0 时执行「上下文优化」：
     *   始终保留 system（身份/人格/工具指引），再从**最旧**的非 system 消息起裁剪历史，
     *   只丢弃过旧的聊天轮次。这样长对话不会把上下文窗口撑爆 → 避免网关/模型静默丢弃
     *   前部上下文或整个 tools 字段（表现为「丢失上下文 / 回复变水 / 工具调用失效」）。
     */
    fun toLlmMessages(system: QuroMessage? = null, contextWindow: Int = 0, historyRounds: Int = 0): List<QuroChatMessage> {
        val built = mutableListOf<QuroChatMessage>()
        system?.let { built.add(QuroChatMessage(it.role, it.content)) }
        // 🔧 #765：先取锁快照，后续遍历快照，避免与 IO 线程的 add/update 并发修改冲突。
        val snapshot = synchronized(lock) { messages.toList() }
        snapshot.forEach { m ->
            // 🔑 思考仅用于界面展示，绝不替代/混入发送给模型的 content（v201 修正）。
            // 旧逻辑在 assistant 带 reasoning 时把 content 整体替换为 reasoning（常含 HTML 标签），
            // 导致：(a) 真实正文丢失；(b) HTML 泄漏进对话上下文，污染后续回复（用户截图确诊）。
            // 策略：优先用真实正文；仅当正文确为空且存在思考时，才把「已去除 HTML 标签」的思考
            // 作为兜底上下文，避免模型拿到空历史。多步工具编排链由 toolCalls + 工具结果承载，不依赖思考。
            val content = when {
                m.role == "assistant" && m.content.isBlank() && !m.reasoning.isNullOrBlank() ->
                    m.reasoning!!.replace(Regex("<[^>]*>"), "")
                else -> m.content
            }
            built.add(QuroChatMessage(m.role, content, m.toolCalls, m.toolCallId, m.attachments))
        }
        // 「保留对话轮数」对话框级覆盖：仅保留最近 N 个 (用户+助手) 轮次，其余丢弃。
        // 仅当 historyRounds > 0 时生效；0/未设置则跳过（与历史行为完全一致）。
        val capped = if (historyRounds > 0) capRecentRounds(built, historyRounds) else built
        if (contextWindow <= 0) return pruneOrphanToolMessages(capped)

        val sysTokens = system?.let { estTokens(it.content) } ?: 0
        var budget = contextWindow - sysTokens
        val nonSys = capped.filter { it.role != "system" }
        if (budget <= 0) {
            // 预算连 system 都不够：仅保留 system + 最后一条消息，避免空请求
            return (capped.filter { it.role == "system" } + nonSys.takeLast(1))
        }

        // ═══ 串台防御（v429+）：巨型消息降权裁剪 ═══
        // HTML/代码/长文本输出（>3000字符）最容易导致 LLM 上下文混淆（把旧任务结果当当前回复）。
        // 策略：先填普通消息（高优先级），剩余预算再填巨型消息（低优先级）。
        // 这样预算紧张时巨型旧内容率先被裁剪，大幅降低"继续之前任务"类串台。
        val GIANT_THRESHOLD = 3000
        val (giantMsgs, normalMsgs) = nonSys.partition { it.content.length > GIANT_THRESHOLD }

        val kept = mutableListOf<QuroChatMessage>()
        // 第一轮：填充普通消息（从最新往最旧）
        for (m in normalMsgs.asReversed()) {
            val t = estTokens(m.content) + (m.toolCalls?.sumOf { estTokens(it.arguments) } ?: 0)
            if (kept.isEmpty() && t > budget) {
                kept.add(m)
            } else if (t <= budget) {
                kept.add(m)
                budget -= t
            } else {
                break
            }
        }
        // 第二轮：用剩余预算填充巨型消息（如果还有空间）
        for (m in giantMsgs.asReversed()) {
            val t = estTokens(m.content) + (m.toolCalls?.sumOf { estTokens(it.arguments) } ?: 0)
            if (t <= budget) {
                kept.add(m)
                budget -= t
            }
            // 巨型消息放不下就跳过，不 break（后面可能有小一点的巨型消息能放下）
        }
        return pruneOrphanToolMessages(capped.filter { it.role == "system" } + kept.asReversed())
    }

    /**
     * 保留最近 N 个 (用户+助手) 轮次：始终保留 system 提示，再从 body 中取最后 N*2 条消息。
     * 既限制历史轮数，又不破坏 system 提示与工具上下文（contextWindow 仍作为单条超大消息的安全网）。
     */
    private fun capRecentRounds(built: List<QuroChatMessage>, rounds: Int): List<QuroChatMessage> {
        val sys = built.filter { it.role == "system" }
        val body = built.filter { it.role != "system" }
        val keep = body.takeLast(rounds * 2)
        return sys + keep
    }

    /**
     * 剔除「孤儿」工具消息，保证下发给模型的工具上下文自洽（OpenAI 协议合规）。
     *
     * 问题背景：上面两步裁剪（capRecentRounds 按轮数 takeLast、contextWindow 按 token 预算丢弃最旧消息）
     * 都可能把「assistant 的 tool_calls」与紧随其后的「role=tool 结果」从中间切断——
     * 例如一个工具轮的边界正好落在保留窗口之外。一旦两者被拆散，下发给云端 API 的上下文里
     * 就会出现「带 tool_calls 却没有对应 tool 结果」或反之的非法组合，触发 400 / 工具调用失效 /
     * 模型乱回复 / 不回复（用户报「工具调用不完整」的典型根因之一）。
     *
     * 本地路径此前已在 [QuroAssistant.compactForLocal] 做了同款保护；但云端路径（toLlmMessages 直发，
     * 不经过 compactForLocal）一直缺失。这里统一在 toLlmMessages 收尾处补齐，云端/本地双路受益。
     *
     * 判定：
     *   - role=tool 且 tool_call_id 在全部 assistant.tool_calls 的 id 集合里找不到 → 孤儿结果，丢弃；
     *   - assistant 且 toolCalls 中存在任一 id 在 role=tool 的 tool_call_id 集合里找不到 → 缺结果调用，整条丢弃。
     * 成对校验、缺一则整组剔除，保证下发前工具上下文完全自洽（system 消息无工具字段，不受影响）。
     */
    private fun pruneOrphanToolMessages(list: List<QuroChatMessage>): List<QuroChatMessage> {
        val callIds = list.filter { !it.toolCalls.isNullOrEmpty() }
            .flatMap { it.toolCalls!!.map { c -> c.id } }.toSet()
        val resultIds = list.filter { it.role == "tool" }
            .mapNotNull { it.toolCallId }.toSet()
        val before = list.size
        val out = list.filterNot { m ->
            (m.role == "tool" && m.toolCallId != null && m.toolCallId !in callIds) ||
            (m.toolCalls.orEmpty().any { it.id !in resultIds })
        }
        if (out.size != before) {
            QuroDiag.log(
                "LlmMessages",
                "🔧 剔除孤儿工具消息 | ${before - out.size} 条（call/result 配对残缺，避免云端 400 / 工具失效）"
            )
        }
        return attachToolNames(out)
    }

    /**
     * 为 role="tool" 消息补全工具名（name 字段），满足 Kimi K3 等严格协议厂商。
     *
     * 背景：标准 OpenAI 格式里 tool 消息只带 tool_call_id + content，工具名写在
     * 前一条 assistant.tool_calls[].function.name 上。多数厂商能从 tool_call_id 反查，
     * 但 Kimi K3 会 400 报错：
     *   "tool messages need a resolvable tool name: carry `tool`/`name`, or match a preceding assistant tool_call by order."
     * 这里按 tool_call_id 精确反查 assistant 的 function name 并写入 tool 消息的 name 字段。
     * 因本函数必在 pruneOrphanToolMessages 之后调用（孤儿 tool 消息已剔除），凡存活的 tool
     * 消息其 tool_call_id 必能在列表内的 assistant.tool_calls 中找到对应 name，绝不产生 null name。
     * 对其它厂商无害：name 属 OpenAI 旧式标准字段，可被容忍。
     */
    private fun attachToolNames(list: List<QuroChatMessage>): List<QuroChatMessage> {
        val idToName = list.filter { !it.toolCalls.isNullOrEmpty() }
            .flatMap { it.toolCalls!!.map { c -> c.id to c.name } }
            .toMap()
        return list.map { m ->
            if (m.role == "tool" && m.toolCallId != null) {
                idToName[m.toolCallId]?.let { m.copy(toolName = it) } ?: m
            } else {
                m
            }
        }
    }

    /** token 估算（中文/英文混合取 char/4 近似）。 */
    private fun estTokens(text: String?): Int = maxOf(1, (text?.length ?: 0) / 4)
}
