package com.ai.assistance.quro.genui.app.agent

/**
 * Agent 事件流 —— 把"它在想什么、做了什么"变成可观察的数据。
 *
 * 设计意图：
 * 原版 AgentLoop 只有一个 `onStatus(String)` 回调，把所有过程压成一行状态文字
 * （"调用工具：web_search…"）。用户看不到：为什么调这个工具、参数是什么、
 * 花了多久、返回了什么、失败为什么失败。整个过程是个黑盒。
 *
 * 这里把过程拆成结构化事件，UI 层可以：
 *  - 实时展示"思考中 → 决策 → 调用 → 结果"的时间线
 *  - 生成结束后回看完整过程（不需要重跑）
 *  - 出错时精确定位是哪一步、哪个工具、什么原因
 *
 * 线程：事件由 IO 线程发出（Agent 在 IO 线程跑），UI 层自行 post 回主线程。
 */
sealed class AgentEvent {
    /** 事件发生的时间戳（毫秒） */
    abstract val ts: Long

    /** 启动：开始一次生成 */
    data class Started(
        val prompt: String,
        val provider: String,
        val model: String,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 思考中：模型正在处理（等待首个 token 或长耗时阶段） */
    data class Thinking(
        val note: String,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 决策结论：模型决定要调哪些工具（或不需要工具） */
    data class Decided(
        val toolCount: Int,
        val reason: String,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 工具调用开始（已通过授权门禁） */
    data class ToolStarted(
        val callId: String,
        val tool: String,
        val argsBrief: String,
        val level: Int,
        val round: Int,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 工具等待授权（UI 弹卡期间） */
    data class ToolAwaitingAuth(
        val callId: String,
        val tool: String,
        val argsBrief: String,
        val level: Int,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 工具被拒（用户或策略） */
    data class ToolDenied(
        val callId: String,
        val tool: String,
        val reason: String,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 工具执行完成 */
    data class ToolFinished(
        val callId: String,
        val tool: String,
        val ms: Long,
        val ok: Boolean,
        val summary: String,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 开始流式渲染界面 */
    data class Rendering(
        val note: String,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 渲染进度（首次达到某个体量时播报，避免刷屏） */
    data class RenderProgress(
        val bytes: Long,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /**
     * 绘制过程推进 —— "正在画什么"。
     *
     * 为什么需要它：RenderProgress 只报裸字节数（"12.3KB"），用户看不出**画到哪了**。
     * 这条事件由流式 HTML 实时解析得出，是**真实观测**而非编造：
     * 解析器按文档顺序识别结构里程碑（头部就绪 / 样式就绪 / 正在写某个区块 / 图表正在成型 /
     * 脚本就绪 / 收尾），每进入一个新阶段就播报一次。
     *
     * [step] 是当前阶段，[detail] 是具体在做什么（如"正在写「本月账单」区块"），
     * [pct] 是粗粒度完成度（0..1，按阶段权重估，非精确但单调不减，用于画进度）。
     */
    data class Painting(
        val step: PaintStep,
        val detail: String,
        val pct: Float,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 绘制里程碑。顺序即真实文档顺序，便于 UI 画阶段轴。 */
    enum class PaintStep { HEAD, STYLE, LAYOUT, CONTENT, CHART, SCRIPT, POLISH }

    /** 结束 */
    data class Finished(
        val title: String,
        val bytes: Long,
        val toolCalls: Int,
        val elapsedMs: Long,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()

    /** 出错 */
    data class Failed(
        val message: String,
        val partialBytes: Long,
        override val ts: Long = System.currentTimeMillis()
    ) : AgentEvent()
}

/**
 * 思考时间线 —— 一次生成过程的完整记录。
 * 由 UI 层持有，实时追加；生成结束后可回看（序列化到界面栈）。
 */
class ThinkingTimeline {

    private val _entries = mutableListOf<Entry>()
    val entries: List<Entry> get() = synchronized(_entries) { _entries.toList() }

    data class Entry(
        val kind: Kind,
        val text: String,
        val detail: String = "",
        val ts: Long = System.currentTimeMillis(),
        val level: Int = 0,
        val running: Boolean = false
    )

    enum class Kind { START, THINK, DECIDE, TOOL, AUTH, DENIED, RESULT, RENDER, PAINT, DONE, ERROR }

    /** 追加一条，返回其索引（供后续更新 running 状态） */
    fun add(kind: Kind, text: String, detail: String = "", level: Int = 0, running: Boolean = false): Int =
        synchronized(_entries) {
            _entries.add(Entry(kind, text, detail, System.currentTimeMillis(), level, running))
            _entries.lastIndex
        }

    /** 更新某条（如工具从 running → 完成） */
    fun update(index: Int, running: Boolean, text: String? = null, detail: String? = null) {
        synchronized(_entries) {
            val e = _entries.getOrNull(index) ?: return
            _entries[index] = e.copy(text = text ?: e.text, detail = detail ?: e.detail, running = running)
        }
    }

    fun clear() = synchronized(_entries) { _entries.clear() }

    /** 供界面栈持久化：只存可读文本，够回看即可 */
    fun toJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        entries.forEach { e ->
            arr.put(org.json.JSONObject()
                .put("k", e.kind.name).put("t", e.text).put("d", e.detail)
                .put("ts", e.ts).put("lv", e.level))
        }
        return arr
    }
}
