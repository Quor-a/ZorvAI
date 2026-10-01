package com.ai.assistance.quro.core

import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.network.QuroModelContextBudget
import com.ai.assistance.quro.util.QuroDiag
import java.util.UUID

/** 单条工具结果回传给模型时的最大保留字符数，超出部分就地截断（保留头 + 关键行 + 尾）。 */
internal const val TOOL_RESULT_CAP = 1600

/**
 * 单次请求内**所有**工具结果的合计字符预算。
 *
 * [TOOL_RESULT_CAP] 只管单条。一次长任务动辄几十次工具调用，每条都贴到上限仍会把上下文撑爆 ——
 * 上下文是有限资源，每多一个 token 都在消耗模型的注意力预算（Anthropic「有效上下文工程」的观点）。
 * 超出本预算时，从**最旧**的工具结果开始做二次激进截断：最新的结果才是当前决策依据，
 * 最旧的早已被后续推理消化掉。
 */
internal const val TOOL_RESULTS_TOTAL_CAP = 24_000

/** 总量超预算时，二次激进截断的单条上限。 */
private const val TOOL_RESULT_CAP_TIGHT = 400

/** 关键行扫描的输入上限：极端巨型输出只扫前 200 万字符，避免 O(n) 扫描拖慢上下文组装。 */
private const val KEY_LINE_SCAN_LIMIT = 2_000_000

/** 超过此长度的单行不参与关键行抽取（避免把一整行 base64 / minified JS 当成「错误行」）。 */
private const val KEY_LINE_MAX_SOURCE = 2_000

/**
 * 「关键行」判定词表：命中任一即认为该行值得保留。
 *
 * 覆盖中英双语的错误 / 异常 / 失败 / 警告词汇，外加构建与测试场景的高频词
 * （`exit code`、`segmentation fault`、`oom` …）。宁可多留几行无关内容，
 * 也不能把真正的错误行漏掉 —— 后者会让模型在「看不到错误」的状态下反复盲重试。
 */
private val KEY_LINE_MARKERS = listOf(
    // 英文：通用错误与异常
    "error", "exception", "failed", "failure", "fatal", "traceback", "stack trace",
    "caused by", "cannot", "can't", "denied", "refused", "timeout", "timed out",
    "not found", "no such file", "panic", "abort", "unable to", "invalid",
    "warning", "missing", "conflict", "unresolved",
    // 中文
    "错误", "失败", "异常", "警告", "无法", "拒绝", "超时", "找不到", "不存在",
    "缺少", "冲突", "未找到", "不支持", "不合法", "已终止", "中断",
    // 构建 / 测试 / 运行时
    "build failed", "compilation error", "test failed", "assertion",
    "exit code", "exit status", "non-zero", "segmentation fault",
    "out of memory", "oom", "killed", "permission denied", "read-only",
)

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * 模型硬输入上限（token）的**绝对天花板**（= [QuroModelContextBudget.ABSOLUTE_MAX_INPUT_TOKENS]）。
 *
 * ⚠️ 它只用于「向上钳制」，**不再充当「未知模型的兜底预算」**。
 * 兜底一律走 [QuroModelContextBudget.CONSERVATIVE_INPUT_TOKENS]（32768）——
 * 把「未知」当成 1M 会让上下文裁剪永不触发，直到上游 500 才暴露，
 * 等于这个安全网在最需要它的场景里失效。
 */
internal const val MODEL_MAX_INPUT_TOKENS = QuroModelContextBudget.ABSOLUTE_MAX_INPUT_TOKENS

/**
 * 就地压缩超长工具结果（role=tool 的 content），防止命令类工具（terminal_exec / root_exec 等）
 * 的巨型输出在上下文裁剪时被整条丢弃，进而拆散工具轮、诱发孤儿调用与模型「跑偏 / 乱执行」。
 *
 * 两阶段：
 * 1. **单条超限** → 头 + 关键行 + 尾（超 [TOOL_RESULT_CAP] 的条目）；
 * 2. **合计超总量预算**（[TOOL_RESULTS_TOTAL_CAP]）→ 从最旧的开始二次激进截断。
 *
 * 工具轮始终 call↔result 成对，任何阶段都只改 content、不删消息。
 *
 * @param archive 归档器：把**完整**原文落盘并返回可读路径，失败返回 null。见 [truncateToolResult]。
 */
internal fun compactToolResults(
    list: List<QuroChatMessage>,
    archive: ((String) -> String?)? = null,
): List<QuroChatMessage> {
    val toolIndices = list.indices.filter { list[it].role == "tool" }
    if (toolIndices.isEmpty()) return list
    val oversized = toolIndices.filter { list[it].content.length > TOOL_RESULT_CAP }
    val totalChars = toolIndices.sumOf { list[it].content.length }
    if (oversized.isEmpty() && totalChars <= TOOL_RESULTS_TOTAL_CAP) return list

    val out = list.toMutableList()
    // 阶段 1：单条超限 → 头 + 关键行 + 尾
    oversized.forEach { i ->
        val m = out[i]
        out[i] = m.copy(content = truncateToolResult(m.content, TOOL_RESULT_CAP, archive))
    }
    // 阶段 2：合计仍超预算 → 从最旧开始激进截断（越旧越可能已被后续推理消化）
    var running = out.indices.filter { out[it].role == "tool" }.sumOf { out[it].content.length }
    if (running > TOOL_RESULTS_TOTAL_CAP) {
        for (i in out.indices) {
            if (running <= TOOL_RESULTS_TOTAL_CAP) break
            val m = out[i]
            if (m.role != "tool" || m.content.length <= TOOL_RESULT_CAP_TIGHT) continue
            val shrunk = truncateToolResult(m.content, TOOL_RESULT_CAP_TIGHT, archive)
            running -= (m.content.length - shrunk.length)
            out[i] = m.copy(content = shrunk)
        }
    }
    return out
}

/**
 * 截断超长工具结果：头部 + **被省略部分中的关键行** + 尾部。
 *
 * 为什么必须有「关键行」：编译错误、堆栈 `Caused by`、`FAILED` 往往出现在输出的**中间**，
 * 而纯「头 40% + 尾 60%」恰好把它们整段丢掉 —— 模型看不到错误，就只能反复盲重试。
 *
 * @param cap 期望保留的字符数（头尾合计）。
 * @param archive 归档器：把**完整**原文落盘并返回可读路径；返回 null 表示归档失败。
 *   归档成功时文案给出**真实路径**；失败时文案引导「重新执行并缩小范围」，
 *   **绝不**写「见本机文件」这类没有指路的承诺 —— 那会把模型引去找一个不存在的文件，
 *   进而编造文件内容（幻觉）。
 */
internal fun truncateToolResult(
    text: String,
    cap: Int = TOOL_RESULT_CAP,
    archive: ((String) -> String?)? = null,
): String {
    val head = (cap * 4 / 10).coerceAtLeast(200)
    val tail = (cap - head).coerceAtLeast(200)
    // 🔧 按「码点」而非 UTF-16 字符截断：原 take/takeLast 可能把 emoji / 代理对切成孤立代理项
    // （lone surrogate）→ 该孤立代理项进入请求体后会让严格上游 JSON 解析失败 → 500。
    val cps = text.codePoints().toArray()
    val headStr = takeCodePoints(cps, head)
    val tailStr = takeLastCodePoints(cps, tail)
    // 被丢弃的中间段 —— 关键行就从这里捞回来
    val midStart = head.coerceAtMost(cps.size)
    val midEnd = (cps.size - tail).coerceAtLeast(midStart)
    val middle = if (midEnd > midStart) String(cps, midStart, midEnd - midStart) else ""
    val keyLines = extractKeyLines(middle)
    val archivedPath = archive?.invoke(text)
    return buildString {
        append(headStr).append("\n")
        if (keyLines.isNotBlank()) {
            append("〔以上为开头；中间 ${text.length - head - tail} 字符已省略，以下是其中的关键行〕\n")
            append(keyLines).append("\n")
        }
        append("〔工具输出过长已截断：原文 ${text.length} 字符，保留头 $head + 尾 $tail〕")
        append(
            if (archivedPath != null) {
                "\n〔完整原文已归档到文件：$archivedPath（可用文件读取工具取回）〕"
            } else {
                "\n〔如需完整内容，请重新执行该工具并缩小输出范围（例如只取尾部、加过滤条件或分段读取）〕"
            }
        )
        append("\n").append(tailStr)
    }
}

/**
 * 从被丢弃的中间段里抽取「关键行」（错误 / 异常 / 失败 / 警告）。
 *
 * 按原顺序保留首个命中行、压缩行内空白、去重，并受「行数」与「字符数」双重约束。
 * 一行都抽不到时返回空串，调用方据此不输出该段（不产生「以下是关键行：」却空着的空洞文案）。
 */
internal fun extractKeyLines(
    dropped: String,
    maxLines: Int = 12,
    maxChars: Int = 600,
): String {
    if (dropped.isBlank()) return ""
    val scan = if (dropped.length > KEY_LINE_SCAN_LIMIT) dropped.take(KEY_LINE_SCAN_LIMIT) else dropped
    val seen = HashSet<String>()
    val picked = ArrayList<String>()
    var used = 0
    for (raw in scan.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.length > KEY_LINE_MAX_SOURCE) continue
        // 纯符号行（分隔线等）没有信息量
        if (!line.any { it.isLetterOrDigit() }) continue
        if (!KEY_LINE_MARKERS.any { line.contains(it, ignoreCase = true) }) continue
        val compact = line.replace(WHITESPACE_RUN, " ").trim()
        if (!seen.add(compact)) continue
        if (picked.size >= maxLines) break
        if (used + compact.length > maxChars) break
        picked.add(compact)
        used += compact.length + 1
    }
    return picked.joinToString("\n")
}

/** 取前 n 个 Unicode 码点（避免切裂代理对）。 */
private fun takeCodePoints(cps: IntArray, n: Int): String {
    if (n <= 0 || cps.isEmpty()) return ""
    return String(cps, 0, n.coerceAtMost(cps.size))
}

/** 取后 n 个 Unicode 码点（避免切裂代理对）。 */
private fun takeLastCodePoints(cps: IntArray, n: Int): String {
    if (n <= 0 || cps.isEmpty()) return ""
    val start = (cps.size - n).coerceAtLeast(0)
    return String(cps, start, cps.size - start)
}

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
    /** 生成式 UI 卡片：本消息关联的 :genui artifact id 列表（AI 自写 JSX/HTML 渲染进对话框）。 */
    val genUiCardIds: List<String> = emptyList(),
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
     *   预算值应由 [QuroModelContextBudget] 解析得出（它会按模型名推断真实上限，
     *   不再把「未知」当成 1M 而让裁剪永不触发）。
     * @param archive 工具结果归档器：超长结果被截断时把**完整**原文落盘并返回路径，
     *   使截断文案能给出真实可读位置。传 null（默认）则文案引导「重新执行并缩小范围」，
     *   两种情况下都不会出现指向不存在文件的虚假指引。
     */
    fun toLlmMessages(
        system: QuroMessage? = null,
        contextWindow: Int = 0,
        historyRounds: Int = 0,
        archive: ((String) -> String?)? = null,
    ): List<QuroChatMessage> {
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
            built.add(QuroChatMessage(m.role, content, m.toolCalls, m.toolCallId, m.attachments, reasoning = m.reasoning))
        }
        // 「保留对话轮数」对话框级覆盖：仅保留最近 N 个 (用户+助手) 轮次，其余丢弃。
        // 仅当 historyRounds > 0 时生效；0/未设置则跳过（与历史行为完全一致）。
        var capped = if (historyRounds > 0) capRecentRounds(built, historyRounds) else built
        // ═══ 工具结果就地压缩（防「跑偏/乱执行」）═══
        // terminal_exec / root_exec 等命令工具输出可能极长（构建日志、ls -R、长文本）。
        // 若整条 role=tool 结果在下方「巨型消息降权」被裁掉，而对应的 assistant tool_calls 仍被保留，
        // 就会形成孤儿调用 → pruneOrphanToolMessages 整条删除 → 模型丢失「我跑过什么 / 结果如何」的记忆，
        // 下一轮便重发同一条命令、或把上下文碎片当成新指令 → 表现为「跑偏 / 乱执行」。
        // 这里在裁剪前先把超长工具结果就地截断（保留头 + 尾 + 长度说明），使其始终能留在上下文里，
        // 工具轮 call↔result 始终成对，从根本上消除孤儿、保住模型对工具执行的历史记忆。
        capped = compactToolResults(capped, archive)
        // 🔧 硬输入上限安全网（toolfix7）：用户把 contextWindow 设 0（「不限制」）时，旧逻辑完全不裁剪、
        // 每轮全量发送历史；长对话 / 多工具轮后总输入超过模型硬上限（如 262144）即被上游 500
        // 「context length exceeded」。现以模型真实输入上限（262144）作为预算：平时（远小于上限）不裁
        // 你的长上下文，只在逼近上限才裁最旧轮次——既保住长上下文，又不再因溢出而 500。
        // 未显式给出预算时按**保守值**兜底，而不是天花板 —— 调用方漏传预算不该等于「不设防」。
        // （子智能体路径 toLlmMessages(system, 0, 0) 走的就是这里；其消息量远小于保守值，行为不变。）
        val requested = if (contextWindow > 0) contextWindow else QuroModelContextBudget.CONSERVATIVE_INPUT_TOKENS
        val ceiling = requested.coerceAtMost(MODEL_MAX_INPUT_TOKENS)
        if (ceiling <= 0) return pruneOrphanToolMessages(capped)

        val sysTokens = system?.let { estTokens(it.content) } ?: 0
        var budget = ceiling - sysTokens
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
        // 🔧 用原始下标标记每条消息，确保最终严格按时间先后排列。
        // 旧逻辑把 normal / giant 分两路各自 asReversed 后拼接、再整体 asReversed，
        // 会把「所有巨型消息」排到「所有普通消息」之前，彻底打乱时序：
        // 例如 round2 的巨型工具结果会被摆到 round1 的普通消息之前；若它对应的
        // assistant tool_calls 在更晚轮次，则 role=tool 排在 tool_calls 之前 → 严格上游 400/500。
        // 现改为：greedy 决策「保/丢」时只收集下标，最后一步按原始下标还原顺序。
        val indexed = nonSys.mapIndexed { i, m -> i to m }
        val (giantIdx, normalIdx) = indexed.partition { it.second.content.length > GIANT_THRESHOLD }

        val keptIdx = mutableListOf<Int>()
        // 第一轮：填充普通消息（高优先级，从最新往最旧）
        for ((i, m) in normalIdx.asReversed()) {
            val t = estTokens(m.content) + (m.toolCalls?.sumOf { estTokens(it.arguments) } ?: 0)
            if (keptIdx.isEmpty() && t > budget) {
                keptIdx.add(i) // 最新一条超预算也强制保留，避免空请求
            } else if (t <= budget) {
                keptIdx.add(i)
                budget -= t
            } else {
                break
            }
        }
        // 第二轮：用剩余预算填充巨型消息（低优先级，放不下跳过不 break）
        for ((i, m) in giantIdx.asReversed()) {
            val t = estTokens(m.content) + (m.toolCalls?.sumOf { estTokens(it.arguments) } ?: 0)
            if (t <= budget) {
                keptIdx.add(i)
                budget -= t
            }
        }
        // 按原始时间顺序还原（keptIdx 仅收集「保/丢」决策，顺序在此一步纠正）
        keptIdx.sort()
        val kept = keptIdx.map { nonSys[it] }
        return pruneOrphanToolMessages(capped.filter { it.role == "system" } + kept)
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
