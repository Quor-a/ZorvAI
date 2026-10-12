package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * quro-ui DSL 校验 —— 对齐宿主自带的 ui_validate 思路：
 * 模型输出 JSON 后立刻自检，非法就回喂修正，而不是在气泡里渲染出一屏空白。
 *
 * 只做三件事：形状对不对、必填字段在不在、动作类型合不合法。
 * 不做样式纠错——那是 ui_dsl_spec 的职责。
 */
object ZxDsl {

    data class Result(val ok: Boolean, val reason: String = "")

    /** zx_* 支持的 12 种动作，与宿主 quro-ui 动作表保持一致 */
    private val ACTIONS = setOf(
        "callback", "tool_call", "skill", "open_url", "copy",
        "open_app", "toggle", "open_screen",
        "render_html", "render_vispro", "visual_popup", "visual_ask"
    )

    private val KINDS = setOf("segment", "slider", "text", "check", "date")

    /** 12 个组件全目录（第一批 + 第二批，历史引用保留） */
    val COMPONENTS: List<String> = listOf(
        "zx_compare", "zx_form", "zx_timeline", "zx_choice",
        "zx_table", "zx_chart", "zx_kanban", "zx_diff",
        "zx_terminal", "zx_approval", "zx_media", "zx_stepper"
    )

    /**
     * 全量目录：三批实体组件 + 全部容器。
     * 新增批次后这里要同步，否则 /zx 指令与 ACI 目录会漏。
     */
    internal val CONTAINERS = listOf("zx_tabs", "zx_accordion", "zx_split", "zx_carousel")
    val ALL_COMPONENTS: List<String> = COMPONENTS + ZxDsl2.ALL.filter { it !in COMPONENTS } +
            ZxDsl3.ALL + CONTAINERS + ZxDsl3.CONTAINERS + ZxDsl4.ALL + ZxDsl5.ALL + ZxDsl6.ALL + ZxDsl7.ALL + ZxDsl8.ALL + ZxDsl9.ALL + ZxDsl10.ALL + ZxDsl11.ALL + ZxDsl12.ALL + ZxDsl13.ALL + ZxDsl14.ALL + ZxDsl15.ALL + ZxDsl16.ALL + ZxDsl17.ALL + ZxDsl18.ALL + ZxDsl19.ALL + ZxDsl20.ALL + ZxDsl21.ALL + ZxDsl22.ALL + ZxDsl23.ALL + ZxDsl24.ALL + ZxDsl25.ALL + ZxDsl26.ALL + ZxDsl27.ALL + ZxDsl28.ALL + ZxDsl29.ALL + ZxDsl30.ALL + ZxDsl31.ALL + ZxDsl32.ALL + ZxDsl33.ALL + ZxDsl34.ALL + ZxDsl35.ALL + ZxDsl36.ALL + ZxDsl37.ALL + ZxDsl38.ALL + ZxDsl39.ALL + ZxDsl40.ALL


    fun validate(component: String, raw: String): Result {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return Result(false, "JSON 无法解析：${e.message}")
        }

        if (root.optString("type") != component)
            return Result(false, "type 字段应为 $component，实际为 ${root.optString("type")}")

        return validateAny(component, root)
    }

    /**
     * 已解析对象的统一校验入口。容器递归校验子节点时要调这个——
     * 走 validate(component, raw) 会把 JSONObject 再序列化一遍，纯属浪费。
     */
    fun validateAny(component: String, root: JSONObject): Result {
        return when (component) {
            // 第一批
            "zx_compare"  -> compare(root)
            "zx_form"     -> form(root)
            "zx_timeline" -> timeline(root)
            "zx_choice"   -> choice(root)
            // 第三批（含 3 个新容器）
            in ZxDsl3.ALL -> ZxDsl3.validate(component, root).toResult()
            in ZxDsl3.CONTAINERS -> ZxDsl3.validate(component, root).toResult()
            // 第四批
            in ZxDsl4.ALL -> ZxDsl4.validate(component, root).toResult()
            // 第五批（输入型）
            in ZxDsl5.ALL -> ZxDsl5.validate(component, root).toResult()
            // 第六批（数据密集 + 直接操纵）
            in ZxDsl6.ALL -> ZxDsl6.validate(component, root).toResult()
            // 第七批（引导 / 识别 / 编排）
            in ZxDsl7.ALL -> ZxDsl7.validate(component, root).toResult()
            // 第八批（表单原语 / 状态呈现 / 集合展示）
            in ZxDsl8.ALL -> ZxDsl8.validate(component, root).toResult()
            // 第九批（复合图表 / 结构图 / 复合容器）
            in ZxDsl9.ALL -> ZxDsl9.validate(component, root).toResult()
            // 第十批（高信息密度 / 专用输入 / 装饰性呈现）
            in ZxDsl10.ALL -> ZxDsl10.validate(component, root).toResult()
            // 第十一批（富内容 / 空间布局 / 系统级界面）
            in ZxDsl11.ALL -> ZxDsl11.validate(component, root).toResult()
            // 第十二批（组织 / 流向 / 导航 / 采集）
            in ZxDsl12.ALL -> ZxDsl12.validate(component, root).toResult()
            // 第十三批（对照 / 诊断 / 协同）
            in ZxDsl13.ALL -> ZxDsl13.validate(component, root).toResult()
            // 第十四批（分布 / 富媒体 / 产出）
            in ZxDsl14.ALL -> ZxDsl14.validate(component, root).toResult()
            // 第十五批（关系 / 文本 / 小众图表）
            in ZxDsl15.ALL -> ZxDsl15.validate(component, root).toResult()
            // 第十六批（时间 / 对比 / 状态机）
            in ZxDsl16.ALL -> ZxDsl16.validate(component, root).toResult()
            // 第十七批（空间 / 符号 / 动效）
            in ZxDsl17.ALL -> ZxDsl17.validate(component, root).toResult()
            // 第十八批（编辑 / 映射 / 度量）
            in ZxDsl18.ALL -> ZxDsl18.validate(component, root).toResult()
            // 第十九批（序列 / 结构 / 证据）
            in ZxDsl19.ALL -> ZxDsl19.validate(component, root).toResult()
            // 第二十批（密度 / 分布 / 编码）
            in ZxDsl20.ALL -> ZxDsl20.validate(component, root).toResult()
            // 第二十一批（对齐 / 差异 / 编排）
            in ZxDsl21.ALL -> ZxDsl21.validate(component, root).toResult()
            // 第二十二批（追溯 / 关系强度 / 定位）
            in ZxDsl22.ALL -> ZxDsl22.validate(component, root).toResult()
            // 第二十三批（推演 / 规则操纵）
            in ZxDsl23.ALL -> ZxDsl23.validate(component, root).toResult()
            // 第二十四批（统计推断 / 一致性）
            in ZxDsl24.ALL -> ZxDsl24.validate(component, root).toResult()
            // 第二十五批（稳健性 / 敏感性 / 效应量）
            in ZxDsl25.ALL -> ZxDsl25.validate(component, root).toResult()
            // 第二十六批（因果与可信度）
            in ZxDsl26.ALL -> ZxDsl26.validate(component, root).toResult()
            // 第二十七批（预测与决策）
            in ZxDsl27.ALL -> ZxDsl27.validate(component, root).toResult()
            // 第二十八批（行动之后）
            in ZxDsl28.ALL -> ZxDsl28.validate(component, root).toResult()
            // 第二十九批（知识与判断的留存）
            in ZxDsl29.ALL -> ZxDsl29.validate(component, root).toResult()
            // 第三十批（组织与治理）
            in ZxDsl30.ALL -> ZxDsl30.validate(component, root).toResult()
            // 第三十一批（模拟与推演）
            in ZxDsl31.ALL -> ZxDsl31.validate(component, root).toResult()
            // 第三十二批（议价、竞标与序贯决策）
            in ZxDsl32.ALL -> ZxDsl32.validate(component, root).toResult()
            // 第三十三批（消费、开发与娱乐）
            in ZxDsl33.ALL -> ZxDsl33.validate(component, root).toResult()
            // 第三十四批（日常界面）
            in ZxDsl34.ALL -> ZxDsl34.validate(component, root).toResult()
            // 第三十五批（生活场景）
            in ZxDsl35.ALL -> ZxDsl35.validate(component, root).toResult()
            // 第三十六批（生活操作）
            in ZxDsl36.ALL -> ZxDsl36.validate(component, root).toResult()
            // 第三十七批（跨多次、有外部依赖）
            in ZxDsl37.ALL -> ZxDsl37.validate(component, root).toResult()
            // 第三十八批（出事了之后怎么办）
            in ZxDsl38.ALL -> ZxDsl38.validate(component, root).toResult()
            // 第三十九批（责任在两个主体之间转移）
            in ZxDsl39.ALL -> ZxDsl39.validate(component, root).toResult()
            // 第四十批（交接完成 ≠ 责任结束）
            in ZxDsl40.ALL -> ZxDsl40.validate(component, root).toResult()
            // 热更新组件：降级后交给底层组件校验
            // 热更新组件：降级后交给底层组件校验；其余交给第二批兜底
            else -> if (ZxHot.has(component)) ZxHot.validate(component, root).toResult()
                    else ZxDsl2.validate(component, root).toResult()
        }
    }

    private fun Pair<Boolean, String>.toResult(): Result = Result(first, second)

    /** 示例 DSL（/zx 指令与 codeRuntime 兜底） */
    fun sample(component: String): String = ZxDsl2.sample(component)

    /** 字段规格塞进终端卡 */
    fun specAsTerminal(component: String?): String = ZxDsl2.specAsTerminal(component)

    /** 单组件字段规格（跨批统一入口，未登记批次的组件也能问） */
    fun spec(component: String): String = ZxDsl2.spec(component)

    private fun compare(o: JSONObject): Result {
        val cols = o.optJSONArray("columns") ?: return Result(false, "缺少 columns（列头数组）")
        if (cols.length() < 2) return Result(false, "columns 至少 2 列")

        val rows = o.optJSONArray("rows") ?: return Result(false, "缺少 rows")
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: return Result(false, "rows[$i] 不是对象")
            if (!r.has("k")) return Result(false, "rows[$i] 缺少 k（行名）")
            val v = r.optJSONArray("v") ?: return Result(false, "rows[$i] 缺少 v（值数组）")
            // 关键：值个数必须与列数对齐，否则表格渲染会错位
            if (v.length() != cols.length())
                return Result(false, "rows[$i].v 有 ${v.length()} 个值，但 columns 有 ${cols.length()} 列")
        }
        o.optJSONObject("onPick")?.let { return action(it, "onPick") }
        return Result(true)
    }

    private fun form(o: JSONObject): Result {
        val fields = o.optJSONArray("fields") ?: return Result(false, "缺少 fields")
        if (fields.length() == 0) return Result(false, "fields 不能为空")
        val seen = mutableSetOf<String>()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: return Result(false, "fields[$i] 不是对象")
            val id = f.optString("id")
            if (id.isBlank()) return Result(false, "fields[$i] 缺少 id")
            if (!seen.add(id)) return Result(false, "fields 中 id 重复：$id（会导致 {{id}} 取值歧义）")
            val kind = f.optString("kind")
            if (kind !in KINDS) return Result(false, "fields[$i].kind 非法：$kind，可选 ${KINDS.joinToString()}")
            if (kind == "slider") {
                val min = f.optDouble("min", Double.NaN)
                val max = f.optDouble("max", Double.NaN)
                if (min.isNaN() || max.isNaN()) return Result(false, "fields[$i] 为 slider 时必须给 min / max")
                if (min >= max) return Result(false, "fields[$i] 的 min 必须小于 max")
            }
            if (kind == "segment" || kind == "check") {
                val opts = f.optJSONArray("options")
                if (opts == null || opts.length() == 0)
                    return Result(false, "fields[$i] 为 $kind 时必须给非空 options")
            }
        }
        val submit = o.optJSONObject("submit") ?: return Result(false, "缺少 submit（提交动作）")
        return action(submit, "submit")
    }

    private fun timeline(o: JSONObject): Result {
        val steps = o.optJSONArray("steps") ?: return Result(false, "缺少 steps")
        if (steps.length() == 0) return Result(false, "steps 不能为空")
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: return Result(false, "steps[$i] 不是对象")
            if (s.optString("n").isBlank()) return Result(false, "steps[$i] 缺少 n（步骤名）")
        }
        // bind 指向 agent.run_state 时由宿主执行器实时推送状态；未绑定则退化为静态列表
        if (o.optString("bind").isBlank())
            return Result(false, "建议声明 bind=agent.run_state，否则时间轴不会随执行推进")
        return Result(true)
    }

    private fun choice(o: JSONObject): Result {
        val opts = o.optJSONArray("options") ?: return Result(false, "缺少 options")
        if (opts.length() < 2) return Result(false, "options 至少 2 项，否则不构成「选择」")
        val keys = mutableSetOf<String>()
        var rec = 0
        for (i in 0 until opts.length()) {
            val op = opts.optJSONObject(i) ?: return Result(false, "options[$i] 不是对象")
            val k = op.optString("key")
            if (k.isBlank()) return Result(false, "options[$i] 缺少 key")
            if (!keys.add(k)) return Result(false, "options 中 key 重复：$k")
            if (op.optBoolean("rec")) rec++
        }
        if (rec > 1) return Result(false, "只能有 1 个 rec=true 的推荐项，当前有 $rec 个")
        o.optJSONObject("onSelect")?.let { return action(it, "onSelect") }
        return Result(true)
    }

    private fun action(a: JSONObject, where: String): Result {
        val type = a.optString("action")
        if (type !in ACTIONS)
            return Result(false, "$where.action 非法：$type，可选 ${ACTIONS.joinToString()}")
        // tool_call / skill / callback 必须给 name，否则回传时找不到落点
        if (type in setOf("tool_call", "skill", "callback") && a.optString("name").isBlank())
            return Result(false, "$where.action=$type 时必须给 name")
        return Result(true)
    }

    /** 数据侧：把 rows 打平成渲染层好吃的形状 */
    fun flattenRows(o: JSONObject): List<Pair<String, List<String>>> {
        val rows = o.optJSONArray("rows") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONObject(i) ?: return@mapNotNull null
            val v = r.optJSONArray("v") ?: return@mapNotNull null
            r.optString("k") to (0 until v.length()).map { v.opt(it).toString() }
        }
    }

    fun columns(o: JSONObject): List<String> {
        val a: JSONArray = o.optJSONArray("columns") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }
}
