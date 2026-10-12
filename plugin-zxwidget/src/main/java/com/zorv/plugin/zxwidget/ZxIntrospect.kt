package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 卡片的自解释协议 —— 让卡片自己告诉模型"该怎样用我"。
 *
 * 为什么需要：现在的流程是
 *   模型想画 → 查 discover 列表 → 猜字段 → 写 DSL → 校验可能失败 → 重写
 *
 * 失败的那一步代价很高：一次完整推理。
 * 而失败的原因高度重复——不是模型不会写 JSON，
 * 是它不知道这个组件**要求什么**。
 *
 * 已有的缓解措施都只覆盖了一部分：
 *   · spec        — 一行规格，太短，说不清约束
 *   · sample      — 一个例子，模型会照抄结构但改坏数值边界
 *   · ui_validate — 事后校验，失败时已经浪费了一轮
 *
 * 自解释要做的是**把约束前置**：
 *   模型问一次 → 拿到完整契约 → 一次写对
 *
 * 契约包含四段，缺一段都不算"自解释"：
 *
 *   一、**骨架**：最小可渲染的 DSL，字段全带类型和是否必填。
 *       不是 sample——sample 是"一个能跑的例子"，
 *       骨架是"结构本身"。
 *
 *   二、**约束**：哪些是硬约束（违反就渲染失败）。
 *       校验器已经知道，但那份知识锁在 Kotlin 里没暴露出来。
 *       所以这里直接复用 ZxDsl 的失败原因，
 *       不另写一份——另写就会漂移。
 *
 *   三、**动作**：这张卡上用户能做什么、回传什么。
 *       模型不知道这张卡点下去会发生什么，
 *       就不会在后续轮次里利用它。
 *
 *   四、**反例**：常见的错误写法。
 *       告诉模型"别这么写"比"应该这么写"更有效——
 *       错误往往集中在两三种模式上。
 *
 * 关键设计：introspect 的输出**必须短**。
 * 它的价值是省 token，如果自己就吃掉 800 token 就本末倒置了。
 * 所以默认只回骨架 + 硬约束，动作和反例按需取。
 */
object ZxIntrospect {

    enum class Part { SKELETON, RULES, ACTIONS, ANTI }

    /** 各组件的硬约束描述。与 ZxDsl 校验同源，改校验时这里也要改。 */
    private val RULES = mapOf(
        "zx_table" to listOf("rows[i].v 长度必须等于列数，否则整行错位"),
        "zx_chart" to listOf("points 必须是数值", "labels 数量须与 points 一致"),
        "zx_diff" to listOf("每个 hunk 必须有 path", "t 只能是 add/del/mod"),
        "zx_histogram" to listOf("bins 的 to 必须递增，区间不可重叠"),
        "zx_boxplot" to listOf("q1 ≤ q2 ≤ q3", "中位数必须落在箱体内"),
        "zx_pareto" to listOf("items 必须按 v 降序，否则累计线会回头"),
        "zx_control" to listOf("lcl < ucl", "cl 必须落在 [lcl, ucl] 内"),
        "zx_sunburst" to listOf("外层合计不可超过内层"),
        "zx_nps" to listOf("total 必须等于三段之和"),
        "zx_gauge" to listOf("v 必须落在 [min, max] 内"),
        "zx_stepper" to listOf("v 必须落在步长刻度上"),
        "zx_breadcrumb" to listOf("cur 必须落在 items 范围内"),
        "zx_logview" to listOf("lv 必填，只能是 err/warn/info/ok/dbg")
    )

    /** 各组件上用户能做的动作与回传内容 */
    private val ACTIONS = mapOf(
        "zx_table" to listOf("点单元格 → zx_select(choice=该格文本)"),
        "zx_diff" to listOf("逐块勾选 → zx_diff_apply(accepted=序号列表)"),
        "zx_form" to listOf("填写后提交 → zx_form_submit(payload=JSON)"),
        "zx_conflict" to listOf("选本地/远端/手工 → zx_select(side, path)"),
        "zx_export" to listOf("选格式后点导出 → zx_form_submit(payload=fmt)"),
        "zx_baseline" to listOf("点行 → zx_select(choice, cur, tgt)"),
        "zx_scorecard" to listOf("点分值 → zx_select(choice, v)")
    )

    /** 常见错误写法。告诉模型"别这么写"比"应该这么写"更有效。 */
    private val ANTI = mapOf(
        "zx_table" to listOf("rows 用对象而不是数组", "忘了给 columns，只给 rows"),
        "zx_chart" to listOf("points 里混了字符串 \"12\" 而不是 12"),
        "zx_histogram" to listOf("bins 给了 n 但没给 v"),
        "zx_pareto" to listOf("items 按升序排（会直接被拦）"),
        "zx_nps" to listOf("只给 total 和 promoters，漏了另外两段"),
        "zx_gauge" to listOf("只给 v 不给 max——指针不知道量程")
    )

    /**
     * 生成契约。默认只给骨架 + 硬约束——
     * 自解释的价值是省 token，自己吃掉 800 token 就本末倒置了。
     */
    fun describe(name: String, parts: Set<Part> = setOf(Part.SKELETON, Part.RULES)): String {
        val meta = ZxRegistry.get(name)
            ?: return "未注册组件：$name。用 zx_discover(op=list) 看当前可用清单。"
        val sb = StringBuilder()
        sb.appendLine("$name · ${meta.label}")
        sb.appendLine("  ${meta.summary}")
        if (meta.minWidthDp > 0) sb.appendLine("  最小宽度 ${meta.minWidthDp}dp")
        sb.appendLine()

        if (Part.SKELETON in parts) {
            sb.appendLine("【骨架】")
            sb.appendLine("  " + skeleton(name))
            sb.appendLine()
        }
        if (Part.RULES in parts) {
            val r = RULES[name]
            sb.appendLine("【硬约束】")
            if (r.isNullOrEmpty()) {
                sb.appendLine("  ${meta.spec}")
                sb.appendLine("  （该组件没有额外的跨字段约束，按 spec 写即可）")
            } else r.forEach { sb.appendLine("  · $it") }
            sb.appendLine()
        }
        if (Part.ACTIONS in parts) {
            sb.appendLine("【用户能做什么】")
            val a = ACTIONS[name]
            if (a.isNullOrEmpty()) sb.appendLine("  · 点选回传 zx_select(choice)")
            else a.forEach { sb.appendLine("  · $it") }
            sb.appendLine()
        }
        if (Part.ANTI in parts) {
            sb.appendLine("【常见错误写法】")
            val x = ANTI[name]
            if (x.isNullOrEmpty()) sb.appendLine("  （暂无记录）")
            else x.forEach { sb.appendLine("  ✗ $it") }
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    /**
     * 骨架：字段全带类型和是否必填。
     * 不是 sample——sample 是"一个能跑的例子"，骨架是"结构本身"。
     */
    fun skeleton(name: String): String = when {
        RULES.containsKey(name) || ZxDsl14.ALL.contains(name) ||
        ZxDsl13.ALL.contains(name) || ZxDsl12.ALL.contains(name) -> {
            // 从 spec 推导骨架：spec 已经是压缩过的结构描述
            val spec = ZxRegistry.spec(name)
            if (spec.isNotBlank()) spec else ZxRegistry.sample(name)
        }
        else -> ZxRegistry.sample(name).replace(Regex("\\s+"), " ").take(160)
    }

    /** 一次性看多个组件的约束（用于下发前的批量预检） */
    fun rulesFor(names: List<String>): String = buildString {
        names.forEach { n ->
            val r = RULES[n]
            if (!r.isNullOrEmpty()) {
                appendLine("$n：")
                r.forEach { appendLine("  · $it") }
            }
        }
        if (isEmpty()) appendLine("这些组件没有额外的跨字段约束。")
    }

    /** 自检：RULES 里登记的名字是否都还在注册表里（防止删了组件忘了删规则） */
    fun audit(): List<String> = (RULES.keys + ACTIONS.keys + ANTI.keys)
        .distinct()
        .filter { ZxRegistry.get(it) == null }
        .map { "自解释表里登记了 $it，但注册表里没有这个组件——删组件时忘了删规则" }

    /** 反查：注册表里有、但自解释表没覆盖的（不一定错，但值得知道） */
    fun uncovered(): List<String> =
        ZxRegistry.names().filter { it !in RULES && it !in ACTIONS && it !in ANTI }

    fun report(): String = buildString {
        val a = audit()
        val u = uncovered()
        appendLine("自解释协议覆盖：${RULES.size + ACTIONS.size + ANTI.size} 个组件有额外说明")
        appendLine("未覆盖：${u.size} 个（只有 spec，没有硬约束/动作/反例）")
        if (a.isNotEmpty()) { appendLine(); a.forEach { appendLine("⚠ $it") } }
        appendLine()
        appendLine("契约四段，缺一段都不算「自解释」：")
        appendLine("  一、骨架 — 最小可渲染 DSL，字段带类型和是否必填")
        appendLine("  二、约束 — 哪些是硬约束（复用 ZxDsl 的失败原因，不另写一份）")
        appendLine("  三、动作 — 用户能做什么、回传什么")
        appendLine("  四、反例 — 常见错误写法")
    }

    fun guide(): String = """
        zx_introspect(op=describe|rules|report|audit|guide, component?, names?, parts?)

        让卡片自己告诉模型「该怎样用我」。

        为什么需要：现在的流程是
          模型想画 → 查 discover → 猜字段 → 写 DSL → 校验失败 → 重写
        失败的那一步代价是一次完整推理，而失败原因高度重复——
        不是模型不会写 JSON，是它不知道这个组件要求什么。

        spec 太短、sample 会被照抄改坏、ui_validate 是事后——
        都不够。自解释要做的是把约束前置：问一次，一次写对。

        契约四段：
          一、骨架 — 最小可渲染 DSL（不是 sample：
               sample 是「一个能跑的例子」，骨架是「结构本身」）
          二、约束 — 硬约束（直接复用 ZxDsl 的失败原因，
               不另写一份，另写就会漂移）
          三、动作 — 用户能做什么、回传什么
          四、反例 — 常见错误写法（
               告诉模型「别这么写」比「应该这么写」更有效）

        输出必须短——它的价值是省 token，
        自己吃掉 800 token 就本末倒置了。
        默认只回骨架 + 硬约束，动作和反例按需取。
    """.trimIndent()
}
