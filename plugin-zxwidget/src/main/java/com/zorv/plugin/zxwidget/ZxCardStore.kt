package com.zorv.plugin.zxwidget

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 卡片总线：AI_TOOL 线程 与 气泡渲染线程 之间的唯一交接点。
 *
 * ZorvAI 的 AI_TOOL execute 跑在工具线程（非主线程），
 * 而 CHAT_CARD 渲染发生在主线程组合期。两边不能直接互相调用，
 * 所以用一条 SharedFlow 把"AI 决定画什么"推给气泡。
 *
 * 这也是解决官方那条已知取舍的关键：
 * 「流式增量回写未做，onDone 一次性回写整屏」
 * —— 这里让 AI 可以在一轮里 emit 多次，气泡就一层一层长出来。
 */
object ZxCardStore {

    data class CardEvent(
        val summary: String?,
        val component: String,
        val dsl: String,
        /** 流式生长：false 表示这一帧只是增量，不要重画整张卡 */
        val done: Boolean = true
    )

    private val _events = MutableSharedFlow<CardEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CardEvent> = _events.asSharedFlow()

    /** AI_TOOL 侧调用（工具线程） */
    fun emit(summary: String?, component: String, dsl: String, done: Boolean = true) {
        _events.tryEmit(CardEvent(summary, component, dsl, done))
    }

    /** 增量：AI 想让时间轴/表格多长一行，而不重画整卡 */
    fun patch(component: String, patchJson: String) {
        _events.tryEmit(CardEvent(null, component, patchJson, done = false))
    }

    /* ── 历史留痕：让 RAG_SOURCE 能把"AI 画过的卡"变成可检索知识 ── */
    private val history = ArrayDeque<CardEvent>(200)

    init {
        // 顺手记录，供 ragSource("zx_cards") 检索
        _events.subscriptionCount // no-op，保持 flow 语义清晰
    }

    fun record(e: CardEvent) {
        history.addLast(e)
        if (history.size > 200) history.removeFirst()
    }

    /** 极简关键词检索：命中 dsl 或 summary 即返回原文片段 */
    fun search(query: String, topK: Int): List<String> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return history.asReversed()
            .filter { it.dsl.contains(q, true) || (it.summary?.contains(q, true) == true) }
            .take(topK)
            .map { "[${it.component}] ${it.summary ?: ""}\n${it.dsl.take(400)}" }
    }

    /** /zxclear */
    /**
     * 按 cardId 取最近的 DSL，不存在返回 null（供 ZxIndex.audit 用）。
     *
     * 为什么只查历史流而不维护一张"当前卡片表"：
     * 卡片由宿主 ChatScreen 持有，插件拿不到它的生命周期。
     * 维护一张自己的表就会和宿主那份漂移——
     * 宿主删了卡，插件表里还留着。
     * 索引自检要的就是"这张卡还在不在"，
     * 所以宁可基于流里实际发生过的事件判断。
     */
    fun find(cardId: String): String? =
        if (cardId.isBlank()) null
        else history.firstOrNull { e ->
            runCatching { org.json.JSONObject(e.dsl).optString("id") == cardId }.getOrDefault(false)
        }?.dsl

    fun clear() {
        history.clear()
        _events.tryEmit(CardEvent(null, "__clear__", "{}"))
    }
}

/** 执行状态：zx_timeline 的 bind=agent.run_state 数据源 */
data class RunState(
    val stepNames: List<String>,
    val doneCount: Int
)
