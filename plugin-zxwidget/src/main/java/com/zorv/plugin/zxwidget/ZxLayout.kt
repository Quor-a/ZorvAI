package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * 多卡布局编排器 —— AI 只说"这几个一起看"，由本地决定怎么摆。
 *
 * 为什么不让模型直接输出容器 DSL：
 *   1. 模型的输出在"发送前"产生，它不知道屏幕实际多宽、有几张卡
 *   2. 容器选择是纯机械决策（数卡 + 量宽），占推理预算不划算
 *   3. 让模型写嵌套 JSON 会显著增加非法概率
 *
 * 所以模型给一组卡 ID 和一句意图，本地算出容器 + 分栏 + 顺序。
 *
 * 决策顺序（每一步都可被显式参数覆盖）：
 *   容器 ← 卡片数与语义
 *   分栏 ← 可用宽度 ÷ 每张卡的最小可读宽度
 *   顺序 ← 意图（compare 并排 / flow 保持 / rank 按权重降序）
 */
object ZxLayout {

    enum class Container(val label: String) {
        TABS("标签切换"), SPLIT("并排"), CAROUSEL("轮播"),
        ACCORDION("折叠"), GRID("网格"), STACK("单张顺序")
    }

    data class Request(
        val cardIds: List<String>,
        val intent: String = "compare",   // compare / flow / rank / detail
        val widthDp: Int = 360,
        val title: String = ""
    )

    data class Plan(
        val container: Container,
        val columns: Int,
        val order: List<String>,
        val reason: String,
        val dsl: String
    ) {
        fun summary(): String = buildString {
            appendLine("编排结果：${container.label} · $columns 栏")
            appendLine("原因：$reason")
            appendLine("顺序：${order.joinToString(" → ")}")
        }
    }

    /** 各类组件的最小可读宽度——低于这个宽度内容会截断 */
    private val MIN_WIDTH = mapOf(
        "zx_table" to 300, "zx_kanban" to 360, "zx_editor" to 300,
        "zx_calendar" to 300, "zx_matrix" to 320, "zx_gantt" to 320,
        "zx_treemap" to 260, "zx_chord" to 260, "zx_wordcloud" to 240,
        "zx_hotspot" to 300, "zx_resizable" to 300, "zx_transfer" to 300
    )

    private fun minWidth(component: String): Int = MIN_WIDTH[component] ?: 200

    /**
     * 算编排方案。
     * 纯函数：不读注册表以外的状态，同样的输入永远给同样的输出——
     * 这样 AI 可以先用 zx_layout(op=preview) 看方案再决定要不要真的排。
     */
    fun plan(req: Request): Plan {
        val metas = req.cardIds.mapNotNull { id ->
            ZxRef.get(id)?.let { id to (ZxRegistry.get(it.component) ?: null) }
        }
        val ids = if (metas.isEmpty()) req.cardIds else metas.map { it.first }

        if (ids.isEmpty()) return Plan(Container.STACK, 1, emptyList(), "没有可用卡片", "{}")

        val wide = ids.map { id ->
            minWidth(ZxRef.get(id)?.component ?: "")
        }
        val maxNeed = wide.maxOrNull() ?: 200

        /* 一、选容器 */
        val container: Container
        val reason: StringBuilder = StringBuilder()

        when {
            ids.size == 1 -> {
                container = Container.STACK
                reason.append("只有 1 张卡，不需要容器")
            }
            req.intent == "flow" -> {
                container = Container.ACCORDION
                reason.append("意图是有先后流程，用折叠按顺序展开")
            }
            req.intent == "rank" -> {
                container = Container.STACK
                reason.append("意图是排名，纵向排列比并排更好比大小")
            }
            req.intent == "detail" -> {
                container = Container.TABS
                reason.append("意图是详情，用标签切换避免同时展开太长")
            }
            // compare：能并排就并排，排不下就退标签
            req.widthDp >= maxNeed * 2 && ids.size == 2 -> {
                container = Container.SPLIT
                reason.append("2 张卡，宽度 ${req.widthDp}dp 够并排（各需 ${maxNeed}dp）")
            }
            ids.size <= 4 && req.widthDp >= 300 -> {
                container = Container.TABS
                reason.append("${ids.size} 张卡，宽度不足以并排，用标签切换")
            }
            ids.size > 4 -> {
                container = Container.GRID
                reason.append("${ids.size} 张卡超过标签上限（4），用网格")
            }
            else -> {
                container = Container.CAROUSEL
                reason.append("窄屏多卡，用轮播横滑")
            }
        }

        /* 二、算分栏 */
        val columns = when (container) {
            Container.SPLIT -> (req.widthDp / maxNeed).coerceIn(1, 2)
            Container.GRID -> (req.widthDp / 160).coerceIn(1, 3)
            else -> 1
        }
        if (container == Container.GRID)
            reason.append("；网格按 160dp/栏 得 $columns 栏")
        else if (container == Container.SPLIT)
            reason.append("；$columns 栏")

        /* 三、定顺序 */
        val order = when (req.intent) {
            // rank：按卡片第一个数值字段降序——排名场景下顺序就是信息
            "rank" -> ids.sortedByDescending { firstNumber(it) ?: Double.NEGATIVE_INFINITY }
            else -> ids
        }
        if (req.intent == "rank") reason.append("；按首个数值降序排")

        return Plan(container, columns, order, reason.toString(), buildDsl(container, columns, order, req))
    }

    private fun firstNumber(cardId: String): Double? {
        val node = ZxRef.get(cardId) ?: return null
        val root = runCatching { JSONObject(node.dsl) }.getOrNull() ?: return null
        fun scan(o: JSONObject): Double? {
            val keys = o.keys()
            while (keys.hasNext()) {
                val v = o.opt(keys.next())
                when (v) {
                    is Number -> return v.toDouble()
                    is JSONObject -> scan(v)?.let { return it }
                    is JSONArray -> for (i in 0 until v.length()) {
                        val e = v.opt(i)
                        if (e is Number) return e.toDouble()
                        if (e is JSONObject) scan(e)?.let { return it }
                    }
                }
            }
            return null
        }
        return scan(root)
    }

    /**
     * 生成容器 DSL。
     * 只引用已有卡片 ID，不内联复制卡片内容——
     * 复制会导致同一张卡有两份状态，改了其中一份另一份不跟着变。
     */
    private fun buildDsl(c: Container, columns: Int, order: List<String>, req: Request): String {
        val arr = JSONArray()
        order.forEach { id ->
            val node = ZxRef.get(id)
            arr.put(JSONObject().put("ref", id)
                .put("type", node?.component ?: "zx_terminal")
                .put("title", node?.component ?: id))
        }
        val o = JSONObject().put("title", req.title)
        when (c) {
            Container.TABS -> { o.put("type", "zx_tabs"); o.put("tabs", arr) }
            Container.SPLIT -> { o.put("type", "zx_split"); o.put("children", arr); o.put("ratio", 0.5) }
            Container.CAROUSEL -> { o.put("type", "zx_carousel"); o.put("children", arr) }
            Container.ACCORDION -> { o.put("type", "zx_accordion"); o.put("children", arr) }
            Container.GRID -> { o.put("type", "zx_grid"); o.put("children", arr); o.put("cols", columns) }
            Container.STACK -> { o.put("type", "zx_stack"); o.put("children", arr) }
        }
        return o.toString(2)
    }

    /** 给模型的说明 */
    fun guide(): String = """
        zx_layout(op=plan|preview|guide, cards=逗号分隔ID, intent=compare|flow|rank|detail)

        模型只说「这几个一起看」，本地决定容器 / 分栏 / 顺序。

        为什么不让模型直接输出容器 DSL：
          · 模型输出在发送前产生，不知道屏幕实际多宽、有几张卡
          · 容器选择是纯机械决策（数卡 + 量宽），占推理预算不划算
          · 让模型写嵌套 JSON 会显著增加非法概率

        intent 语义：
          compare  并排对比（默认），排不下自动退标签
          flow     有先后，用折叠
          rank     排名，纵向 + 按首个数值降序
          detail   详情，用标签避免一次展开太长

        关键：DSL 里只引用卡片 ID，不复制内容——
        复制会让同一张卡有两份状态，改一份另一份不跟着变。
    """.trimIndent()
}
