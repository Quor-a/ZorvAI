package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 待弹出的 Web UI 卡片（ZorvAI 第三个对话框）。
 *
 * @param id       唯一 ID
 * @param component zx 组件名（zx_compare / zx_table / …）
 * @param title    对话框标题，空则用组件名
 * @param dsl      卡片 DSL JSON 原文
 * @param timeout  等待用户交互的超时（秒）
 * @param latch    用户交互 / 关闭 / 超时时 countDown
 * @param result   用户交互的结构化描述（null = 用户关闭未交互 / 超时）
 */
data class ZxWebCardData(
    val id: String,
    val component: String,
    val title: String,
    val dsl: String,
    val timeout: Int,
    val latch: CountDownLatch,
    val result: AtomicReference<String?>,
)

/**
 * Web UI 卡片队列：工具线程提交，UI 线程（ChatScreen）消费。
 *
 * 与 [VisualPopupQueue] 同一套思路——工具在后台线程阻塞等用户，
 * UI 侧 collect eventFlow 弹对话框，用户一交互就 submitResult 放行。
 */
object ZxWebCardQueue {
    private const val TAG = "ZxWebCardQueue"

    val pendingCards = mutableListOf<ZxWebCardData>()

    private val _eventChannel = Channel<CardEvent>(Channel.BUFFERED)
    val eventFlow = _eventChannel.receiveAsFlow()

    sealed class CardEvent {
        data class CardAdded(val id: String) : CardEvent()
        data class CardRemoved(val id: String) : CardEvent()
    }

    /**
     * 用户在卡片上完成一次交互：放行工具并带回结构化描述。
     *
     * @return true 表示确实有一张在等的卡片被放行（说明这次交互是某个 zx_web_card 工具调用的结果）；
     *         false 表示没有对应卡片（例如卡片来自动态 UI 动作或历史重放，调用方应改走 vm.send）。
     */
    fun submitInteraction(id: String, summary: String): Boolean {
        synchronized(pendingCards) {
            val idx = pendingCards.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val card = pendingCards[idx]
            card.result.set(summary)
            card.latch.countDown()
            pendingCards.removeAt(idx)
            _eventChannel.trySend(CardEvent.CardRemoved(id))
            Log.d(TAG, "用户交互，放行卡片: $id")
            return true
        }
    }

    /**
     * 按组件名放行最新一张在等的卡片。
     *
     * 专供 Web UI 第三个对话框：卡片是在消息流里**内联**渲染的，JS 回传的载荷里
     * 只有组件名与交互明细，拿不到卡片 ID，所以按组件名匹配。
     *
     * @return true 表示命中了在等的卡片（工具已放行）；false 表示没有工具在等这张卡。
     */
    fun submitByComponent(component: String, summary: String): Boolean {
        if (component.isBlank()) return false
        synchronized(pendingCards) {
            val idx = pendingCards.indexOfLast { it.component == component }
            if (idx < 0) return false
            val card = pendingCards[idx]
            card.result.set(summary)
            card.latch.countDown()
            pendingCards.removeAt(idx)
            _eventChannel.trySend(CardEvent.CardRemoved(card.id))
            Log.d(TAG, "内联交互命中组件 $component，放行卡片: ${card.id}")
            return true
        }
    }

    /** 用户关掉对话框没交互 / 超时：同样放行，但 result 留 null。 */
    fun dismiss(id: String) {
        synchronized(pendingCards) {
            val idx = pendingCards.indexOfFirst { it.id == id }
            if (idx < 0) return
            val card = pendingCards[idx]
            card.latch.countDown()
            pendingCards.removeAt(idx)
            _eventChannel.trySend(CardEvent.CardRemoved(id))
            Log.d(TAG, "卡片关闭，放行: $id")
        }
    }

    fun getCurrentCard(): ZxWebCardData? =
        synchronized(pendingCards) { pendingCards.firstOrNull() }

    fun getCardById(id: String): ZxWebCardData? =
        synchronized(pendingCards) { pendingCards.find { it.id == id } }

    fun addCard(card: ZxWebCardData) {
        synchronized(pendingCards) {
            pendingCards.add(card)
            _eventChannel.trySend(CardEvent.CardAdded(card.id))
        }
    }
}

/**
 * Web UI 卡片工具（ZorvAI 第三个对话框的 AI 侧入口）。
 *
 * 为什么必须有这个工具：此前只有「输入框工具菜单里的开关 + 一句提示词」，
 * 模型侧**没有任何可调用的入口**，它只会转用自己熟悉的 ui_card/ui_widget，
 * 第三个对话框因此永远弹不出来。本工具与 visual_popup 同族——
 * AI 调一次 → 弹 Web UI 卡片 → 用户在卡片上操作 → 交互结构化回传 → AI 接着推理。
 */
class ZxWebCardTool : QuroTool {

    override val name = "zx_web_card"

    override val description = """Web UI 卡片（ZorvAI 第三个对话框）：弹出可交互卡片让用户当场操作。
适用：需要用户在卡片上点选/填写/翻页/确认的场景（方案对比、数据表格、表单、时间线、看板、审批流、终端回放、仪表盘等）。
参数：
{
  "component":"组件名，必须来自 zx_catalog 名册（477 个，全部 zx_ 开头）",
  "dsl":{该组件的数据 JSON，字段按 zx_catalog component=X 返回的契约与 example 填},
  "title":"对话框标题（可选，缺省用组件名）",
  "timeout":120
}
⚠ 先用 zx_catalog 确认组件名与字段（可传 component 拿该组件的字段契约 + 示例 DSL），
  不要凭印象拼组件名或字段名——名册外的名字会落到通用渲染，效果差。
字段词汇约定：n=名称, v=数值, d=描述, t=文本, k=键, s/e=起止, at=时间, st=状态, who=人, u=单位。
返回：用户在卡片上的交互（组件名 + 动作 + 被操作对象明细），或 cancelled/timeout。
⚠ 纯展示用 visual_popup；气泡内富卡片用 ui_card/ui_widget；本工具用于「要用户动手操作」的整框卡片。"""

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "component":{"type":"string","description":"zx 组件名，必须以 zx_ 开头"},
            "dsl":{"description":"该组件的数据契约 JSON（对象）","type":"object"},
            "title":{"type":"string","description":"对话框标题（可选）"},
            "timeout":{"type":"integer","description":"等待用户交互的超时秒数，默认120"}
        },
        "required":["component","dsl"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val component = args.optString("component", "").trim()
        if (component.isBlank()) return zxErr("zx_web_card 需要 component（zx_ 开头的组件名）")

        // dsl 允许对象或字符串两种写法；对象直接转字符串下发
        val dsl = when (val raw = args.opt("dsl")) {
            is JSONObject -> raw.toString()
            is String -> raw.trim()
            else -> ""
        }
        if (dsl.isBlank() || dsl == "{}" || dsl == "null") {
            return zxErr("zx_web_card 需要 dsl（组件的数据 JSON）")
        }

        val title = args.optString("title", "").trim().ifBlank { component }
        val timeout = args.optInt("timeout", 120).coerceIn(10, 600)
        val id = "zxcard_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        ZxWebCardQueue.addCard(
            ZxWebCardData(
                id = id, component = component, title = title, dsl = dsl,
                timeout = timeout, latch = latch, result = result,
            )
        )
        Log.d(TAG, "弹出 Web UI 卡片: $component (ID: $id, 超时: ${timeout}s)")

        val answered = latch.await(timeout.toLong(), TimeUnit.SECONDS)
        val summary = result.get()
        return when {
            answered && summary != null -> summary
            answered -> """{"ok":false,"cancelled":true,"component":"$component","hint":"用户关闭了卡片，未发生交互"}"""
            else -> {
                ZxWebCardQueue.dismiss(id)
                """{"ok":false,"timeout":true,"component":"$component","hint":"用户 $timeout 秒内未操作卡片"}"""
            }
        }
    }

    private fun zxErr(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()

    companion object {
        private const val TAG = "ZxWebCardTool"
    }
}
