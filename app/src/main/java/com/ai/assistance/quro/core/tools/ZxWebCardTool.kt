package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 一块正在等用户操作的 zxwidget 动态交互界面。
 *
 * @param id       唯一 ID
 * @param component zx 组件名（zx_compare / zx_table / …）
 * @param title    界面标题，空则用组件名
 * @param dsl      组件数据 JSON 原文
 * @param timeout  等待用户交互的超时（秒）
 * @param latch    用户交互 / 超时时 countDown
 * @param result   用户交互的结构化描述（null = 用户始终没操作 / 超时）
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
 * zxwidget 队列：工具线程提交，交互回来就放行。
 *
 * 与 [VisualPopupQueue] 同一套思路——工具在后台线程阻塞等用户，
 * 区别是**没有弹窗**：zxwidget 由消息流内联渲染，交互由渲染它的那个界面回传。
 * 所以这里不再有「UI 消费队列弹框」的订阅通道（[eventFlow] 已移除）——
 * 渲染不再需要队列通知，留着的唯一结果是把「弹窗时代」的入口留给下一个人。
 */
object ZxWebCardQueue {
    private const val TAG = "ZxWebCardQueue"

    val pendingCards = mutableListOf<ZxWebCardData>()

    /**
     * 按组件名放行最新一张在等的界面。
     *
     * zxwidget 是在消息流里**内联**渲染的，回传的载荷里只有组件名与交互明细、
     * 拿不到内部 ID，所以按组件名匹配（同一个组件名理论上有多个时取最新的那个）。
     *
     * @return true 表示命中了正在等待的界面（工具已放行，本轮对话直接继续）；
     *         false 表示没有工具在等（历史重放 / 动态 UI 动作来源，调用方应改走 vm.send）。
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
            Log.d(TAG, "内联交互命中组件 $component，放行: ${card.id}")
            return true
        }
    }

    /** 用户没交互就走开 / 等待超时：同样放行，但 result 留 null。 */
    fun dismiss(id: String) {
        synchronized(pendingCards) {
            val idx = pendingCards.indexOfFirst { it.id == id }
            if (idx < 0) return
            val card = pendingCards[idx]
            card.latch.countDown()
            pendingCards.removeAt(idx)
            Log.d(TAG, "界面生命周期结束，放行: $id")
        }
    }

    fun addCard(card: ZxWebCardData) {
        synchronized(pendingCards) { pendingCards.add(card) }
    }
}

/**
 * zxwidget 动态交互界面工具 —— AI 侧入口。
 *
 * 交付形态（用户 2026-10-13 拍板，别改回去）：**不是卡片、不是弹窗**。
 * 调一次，一块可交互界面就**直接长在当前对话框的消息流里**（撑满宽度，与正文并列），
 * 用户在界面上点选/填写，交互结构化回传，模型接着推理。
 * 两个对话框都支持它：ZorvAI 原生对话框（宿主内嵌 WebView）与 Web UI 对话框
 * （网页自己的 DOM，共用同一份 `assets/zx/zxwidget.js`）。
 *
 * 历史形态「整框弹窗装一张卡」已被否掉并下线——若发现界面又变成弹窗，
 * 说明渲染又走回了旧路（检查 `QuroMessage` 上的 zx_web_card 工具调用是否有被内联渲染）。
 *
 * 为什么必须有这个工具：此前只有「输入框工具菜单里的开关 + 一句提示词」，
 * 模型侧**没有任何可调用的入口**，它只会转用自己熟悉的 ui_card/ui_widget，
 * zxwidget 因此永远出不来。本工具给模型一个明确入口。
 */
class ZxWebCardTool : QuroTool {

    override val name = "zx_web_card"

    override val description = """zxwidget 动态交互界面：把结构化结果编译成**可交互控件，直接渲染在当前对话框里**（不是弹窗、不是卡片）。
适用：需要用户当场动手操作的场景（方案对比、数据表格、表单、时间线、看板、审批流、终端回放、仪表盘等）。
参数：
{
  "component":"组件名，必须来自 zx_catalog 名册（477 个，全部 zx_ 开头）",
  "dsl":{该组件的数据 JSON，字段按 zx_catalog component=X 返回的契约与 example 填},
  "title":"界面标题（可选，缺省用组件名）",
  "timeout":120
}
⚠ 先用 zx_catalog 确认组件名与字段（可传 component 拿该组件的字段契约 + 示例 DSL），
  不要凭印象拼组件名或字段名——名册外的名字会落到通用渲染，效果差。
字段词汇约定：n=名称, v=数值, d=描述, t=文本, k=键, s/e=起止, at=时间, st=状态, who=人, u=单位。
返回：用户在界面上的交互（组件名 + 动作 + 被操作对象明细），或 cancelled/timeout。
⚠ 纯展示用 visual_popup；气泡内小卡片用 ui_card/ui_widget；本工具用于「要用户动手操作」的动态交互界面。
⚠ 一块界面只画一次；需要多块就多次调用，它们会依次内联在消息流里。"""

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
        Log.d(TAG, "弹出 zxwidget 卡片: $component (ID: $id, 超时: ${timeout}s)")

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
