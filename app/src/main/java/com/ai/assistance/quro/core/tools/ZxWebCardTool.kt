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
 * 组件契约索引（进程级缓存）。
 *
 * 🔴 存在的唯一理由：**在阻塞 [ZxWebCardTool] 那 120 秒之前**把两类必错拦下来。
 *
 * zxwidget 是「阻塞等用户交互」的形态：工具一进去就 `latch.await(120s)`。
 * 若参数必错（组件名不在名册 / dsl 里一个可用值都没有），用户看到的是一块
 * **空壳界面**（契约画出了表头与格子，格子里什么都没有）然后干等两分钟，
 * 模型最后收到一句 timeout —— 这正是用户报的「zxwidget 根本渲染不了」。
 *
 * 拦在入口的收益是双向的：用户不再对着空盒子等，模型立刻拿到
 * 「组件名不对，名册里最接近的是 X / 你应该先调 zx_catalog」并自行修正。
 *
 * 判据刻意**窄**：只拒「名册里没有的名字」与「一个标量都没有的 dsl」。
 * 字段命名是否贴合契约**不在这里把关** —— 运行时已有自造字段名的兜底采集，
 * 在这里多判一层只会把能画的数据判死。
 */
private object ZxContractIndex {
    @Volatile private var root: JSONObject? = null
    @Volatile private var names: Set<String> = emptySet()

    fun ensure(context: Context): Boolean {
        if (root != null) return true
        return synchronized(this) {
            if (root != null) return true
            val r = runCatching {
                JSONObject(context.assets.open("zx_contracts.json").bufferedReader().use { it.readText() })
            }.getOrNull() ?: return false
            val arr = r.optJSONArray("components") ?: return false
            val set = HashSet<String>(arr.length() * 2)
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
            root = r
            names = set
            true
        }
    }

    fun has(name: String): Boolean = names.contains(name)

    /**
     * 名册里最接近的几个名字，给模型一个能直接抄的替代。
     * 先按「前缀相邻 / 共有词根」挑，挑不满再用字母序补足 ——
     * 目的是让模型**看到真实存在的组件名**，而不是让它再猜一次。
     */
    fun nearest(name: String, limit: Int = 8): List<String> {
        if (names.isEmpty()) return emptyList()
        val lower = name.lowercase()
        val body = lower.removePrefix("zx_")
        val head = body.take(3)
        val scored = names.map { n ->
            val l = n.lowercase()
            val b = l.removePrefix("zx_")
            var score = 0
            if (head.isNotEmpty() && b.startsWith(head)) score += 3
            if (b.take(2) == body.take(2)) score += 2
            if (l.contains(body) || body.contains(b)) score += 2
            score to n
        }.sortedWith(compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second })
        val hit = scored.filter { it.first > 0 }.map { it.second }
        return (hit + scored.map { it.second }).distinct().take(limit)
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

        // ── 入口预检：把「必然画成空壳」的两种参数拦在阻塞之前 ──────────────
        // 见 [ZxContractIndex] 的说明：不拦，就是一块空盒子 + 干等 120 秒 + 一句 timeout。
        if (ZxContractIndex.ensure(context)) {
            if (!ZxContractIndex.has(component)) {
                val near = ZxContractIndex.nearest(component)
                return JSONObject().put("ok", false)
                    .put("error", "组件名 $component 不在 zxwidget 名册里（477 个组件，全部 zx_ 开头）")
                    .put("nearest", org.json.JSONArray(near))
                    .put("hint", "名册外的名字会落到通用渲染，画出来是一块空盒子。请先调 zx_catalog" +
                        "（可传 component 拿字段契约与示例 DSL，或传 query 过滤），再用名册里的名字重调本工具。")
                    .toString()
            }
        }
        val dslObj = runCatching { JSONObject(dsl) }.getOrNull()
        if (dslObj != null && !hasUsableValue(dslObj)) {
            return JSONObject().put("ok", false)
                .put("error", "dsl 里没有任何可用值（全为空对象/空数组/空串）")
                .put("dslKeys", org.json.JSONArray(dslObj.keys().asSequence().toList()))
                .put("hint", "请把要展示的数据真正填进 dsl：数组字段（items/rows/nodes…）至少一项、" +
                    "或至少一个能显示的标量。空 dsl 会渲染成一块空盒子。")
                .toString()
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

    /**
     * dsl 里是否至少有一个「能显示出来的值」。
     *
     * 判据与运行时的可见性判据对齐：非空数组（≥1 项）算有、非空标量算有、
     * 嵌套对象**递归看一层**也算有 —— 模型常写成 `{"data":{"items":[…]}}`，
     * 那种是可用数据，不能误判成空。
     *
     * 只看「有没有值」，不看**字段名是否贴合契约** —— 后者由运行时的
     * 自造字段名兜底采集（harvest）处理，在这里多判一层会把能画的数据判死。
     */
    private fun hasUsableValue(o: JSONObject): Boolean {
        val keys = o.keys()
        while (keys.hasNext()) {
            when (val v = o.opt(keys.next())) {
                null, JSONObject.NULL -> continue
                is org.json.JSONArray -> if (v.length() > 0) return true
                is JSONObject -> if (hasUsableValue(v)) return true
                else -> if (v.toString().trim().isNotEmpty()) return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "ZxWebCardTool"
    }
}
