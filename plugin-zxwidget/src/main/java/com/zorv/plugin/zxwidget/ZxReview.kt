package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 卡片自评 —— 渲染完自己判一遍可读性，不达标就换组件。
 *
 * 为什么需要：AI 选组件是"发送前"的决策，它看不见渲染结果。
 * 一张表 20 行塞进 300dp 高的气泡里、一个 12 列的对比卡——
 * 语法完全合法，校验全过，但用户看到的就是一团糊。
 * 校验只能保证"画得出来"，保证不了"看得清"。
 *
 * 所以要有渲染后的自评闭环：
 *   渲染 → 量一遍实际尺寸 → 打分 → 不达标就自动换组件 → 再渲染
 *
 * 打分维度（每个都对应一种具体的"看不清"）：
 *   1. 高度溢出：算出来的高度超过气泡可视区 → 用户得疯狂滚动
 *   2. 单列过窄：列数太多导致每列宽度不足 → 数字被截断
 *   3. 信息密度过低：一大片地方只放了一句话 → 不如用文本
 *   4. 需要横向滚动：表格/看板列数超限 → 移动端体验很差
 *   5. 交互目标过小：可点区域小于 24dp → 手指点不中
 *
 * 三条硬规则：
 *   1. 自评只读尺寸不读内容——不分析文本语义，避免又引入一次推理开销
 *   2. 自动换组件只能降级不能升级（换更简单的），且最多换一次，防止来回震荡
 *   3. 换了必须告诉用户为什么，不能悄悄换掉 AI 选的东西
 */
object ZxReview {

    /** 量到的实际尺寸 */
    data class Metrics(
        val widthDp: Int,
        val heightDp: Int,
        val rows: Int = 0,
        val cols: Int = 0,
        val textChars: Int = 0
    )

    /** 一项评分 */
    data class Finding(
        val dim: String,
        val score: Float,      // 0-1，1 为最好
        val detail: String,
        val fatal: Boolean = false
    )

    data class Review(
        val ok: Boolean,
        val score: Float,
        val findings: List<Finding>,
        val suggestion: String?,
        val replaceWith: String?
    )

    /** 气泡可视高度（超出就要滚） */
    private const val VIEWPORT_DP = 420
    /** 单列最小可读宽度 */
    private const val MIN_COL_DP = 56
    /** 最小可点区域 */
    private const val MIN_TOUCH_DP = 24

    fun review(component: String, m: Metrics): Review {
        val f = mutableListOf<Finding>()

        // ① 高度溢出
        val overflow = m.heightDp.toFloat() / VIEWPORT_DP
        f += when {
            overflow > 2.0f -> Finding("高度", 0.1f, "内容高 ${m.heightDp}dp，超过可视区 2 倍（${VIEWPORT_DP}dp），用户要大幅滚动", fatal = true)
            overflow > 1.3f -> Finding("高度", 0.45f, "内容高 ${m.heightDp}dp，超过可视区，需要滚动")
            else -> Finding("高度", 1f, "高 ${m.heightDp}dp，在可视区内")
        }

        // ② 单列过窄
        if (m.cols > 0) {
            val per = m.widthDp.toFloat() / m.cols
            f += when {
                per < MIN_COL_DP * 0.6f -> Finding("列宽", 0.1f, "$m.cols 列，每列仅 ${"%.0f".format(per)}dp，内容会被截断", fatal = true)
                per < MIN_COL_DP -> Finding("列宽", 0.5f, "$m.cols 列，每列 ${"%.0f".format(per)}dp，偏窄")
                else -> Finding("列宽", 1f, "每列 ${"%.0f".format(per)}dp，够宽")
            }
        }

        // ③ 信息密度过低：占了大地方却没多少字
        if (m.heightDp > 160 && m.textChars < 40 && m.rows <= 1) {
            f += Finding("密度", 0.3f, "占 ${m.heightDp}dp 却只有 ${m.textChars} 个字符，用纯文本更合适")
        }

        // ④ 需要横向滚动
        if (component in setOf("zx_table", "zx_kanban", "zx_compare") && m.cols > 4) {
            f += Finding("横滚", 0.35f, "$component 有 ${m.cols} 列，移动端需要横向滚动才能看全")
        }

        // ⑤ 交互目标过小（行高按总高/行数估）
        if (m.rows > 0) {
            val perRow = m.heightDp.toFloat() / m.rows
            if (perRow < MIN_TOUCH_DP && component in setOf("zx_choice", "zx_kanban")) {
                f += Finding("可点区", 0.4f, "每行约 ${"%.0f".format(perRow)}dp，小于 ${MIN_TOUCH_DP}dp，手指容易点错")
            }
        }

        val score = if (f.isEmpty()) 1f else f.map { it.score }.average().toFloat()
        val fatal = f.firstOrNull { it.fatal }
        val ok = fatal == null && score >= 0.6f

        return Review(
            ok = ok,
            score = score,
            findings = f,
            suggestion = if (ok) null else buildSuggestion(component, f, m),
            // 只能降级，且由调用方限制"最多换一次"
            replaceWith = if (ok) null else pickReplacement(component, f)
        )
    }

    private fun buildSuggestion(component: String, f: List<Finding>, m: Metrics): String {
        val worst = f.minBy { it.score }
        return "「${worst.dim}」得分最低（${"%.2f".format(worst.score)}）：${worst.detail}。" +
               "建议改为 ${pickReplacement(component, f) ?: "简化后再渲染"}。"
    }

    /** 降级选型：换更"矮"或更"窄"的组件 */
    private fun pickReplacement(component: String, f: List<Finding>): String? {
        val dim = f.minBy { it.score }.dim
        return when (dim) {
            "高度", "横滚" -> when (component) {
                "zx_table" -> "zx_terminal"     // 终端可折叠，且单列
                "zx_kanban" -> "zx_table"
                "zx_compare" -> "zx_choice"     // 决策卡竖向排列，不横滚
                "zx_chart" -> "zx_table"
                else -> null
            }
            "列宽" -> when (component) {
                "zx_table" -> "zx_terminal"
                "zx_compare" -> "zx_choice"
                else -> null
            }
            "密度" -> "text"                    // 直接退化成文本
            "可点区" -> "zx_choice"             // 决策卡行高更大
            else -> null
        }
    }

    /**
     * 自评 + 自动重渲染。最多换一次，防止 A→B→A 震荡。
     * 返回是否发生了替换。
     */
    fun autoFix(cardId: String, component: String, dsl: String, m: Metrics, ctx: RenderContext): Result {
        val r = review(component, m)
        if (r.ok) return Result(true, "自评通过（${"%.2f".format(r.score)}），无需调整")

        val to = r.replaceWith
            ?: return Result(false, "自评不通过（${"%.2f".format(r.score)}）但没有可降级的组件，保持原样：${r.suggestion}")

        if (to == "text") {
            ZxCardStore.emit("已自动简化", "zx_terminal",
                ZxReview.explainAsTerminal(component, m, r))
            return Result(true, "自评不通过，已退化为文本说明：${r.suggestion}")
        }

        // 换组件：DSL 需要按目标组件重建，这里走模板/路由，不硬转
        val env = ZxRouter.snapshot()
        val decision = ZxRouter.decide("output", ZxRouter.Ctx(
            online = env.online, localEngine = env.localEngine,
            tokenBudget = env.tokenBudget, widthDp = m.widthDp, dataPoints = m.rows
        ))
        ZxCardStore.emit("自评不通过，已自动改为 $to", to, dsl)
        ZxAudit.log(ZxAudit.Kind.REVIEW, cardId, "$component → $to（${"%.2f".format(r.score)}）：${r.suggestion}")
        ctx.callTool("zx_select", mapOf("choice" to to, "source" to "zx_review_autofix"))
        return Result(true, "自评 ${"%.2f".format(r.score)} 不达标，已自动改为 $to：${r.suggestion}")
    }

    /** 自评报告渲染成终端卡 */
    fun explainAsTerminal(component: String, m: Metrics, r: Review): String {
        val lines = org.json.JSONArray()
        lines.put(JSONObject().put("lv", "cmd").put("c", "zx_review $component"))
        lines.put(JSONObject().put("lv", "out").put("c",
            "实测 ${m.widthDp}×${m.heightDp}dp · ${m.rows} 行 ${m.cols} 列 · ${m.textChars} 字符"))
        r.findings.forEach { f ->
            lines.put(JSONObject().put("lv", if (f.score >= 0.6f) "ok" else "warn")
                .put("c", "${"%.2f".format(f.score)}  ${f.dim}  ${f.detail}"))
        }
        lines.put(JSONObject().put("lv", if (r.ok) "ok" else "err")
            .put("c", "总分 ${"%.2f".format(r.score)} · ${if (r.ok) "通过" else "不通过"}"))
        if (r.suggestion != null)
            lines.put(JSONObject().put("lv", "warn").put("c", r.suggestion!!))
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_review").put("exit", if (r.ok) 0 else 1)
            .put("lines", lines).toString()
    }

    /** 供模型参考：什么样的尺寸算合格 */
    fun guide(): String = """
        自评阈值（渲染后实测，非发送前估算）：
          高度  ≤ ${VIEWPORT_DP}dp       超出 2 倍判致命
          列宽  ≥ ${MIN_COL_DP}dp/列     低于 60% 判致命
          密度  >160dp 时应 >40 字符
          横滚  table/kanban/compare 列数 ≤ 4
          可点  ≥ ${MIN_TOUCH_DP}dp/行

        不达标自动降级（只降不升，最多一次）：
          zx_table → zx_terminal   zx_kanban → zx_table
          zx_compare → zx_choice   zx_chart → zx_table
    """.trimIndent()
}
