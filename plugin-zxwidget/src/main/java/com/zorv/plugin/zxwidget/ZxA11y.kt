package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 无障碍 —— 卡片不只是画给人看的，还要能被读屏软件读出来。
 *
 * 为什么需要：卡片是动态生成的 UI，TalkBack 读什么完全取决于 Compose 语义树。
 * 如果一张对比卡只画了矩形和文字，读屏读出来就是"按钮 按钮 按钮"，
 * 视障用户根本不知道这张卡在说什么。
 *
 * 三类必须处理的问题：
 *   1. 无标签控件：图标按钮没写 contentDescription → 读屏只能念"未加标签的按钮"
 *   2. 纯视觉信息：状态只靠颜色表达（绿=通过 红=失败）→ 色觉障碍用户看不见
 *   3. 对比度不足：浅灰字压在深灰底上，对比度 < 4.5:1 → 弱视用户读不出
 *
 * 这三条都不影响"能不能渲染"，只影响"谁能用"。
 * 所以单独一层检查，而不是塞进 DSL 校验——DSL 校验管渲染正确性，无障碍管可用性。
 *
 * 对比度按 WCAG 2.1 相对亮度公式算，不是拍脑袋。
 */
object ZxA11y {

    enum class Level { PASS, WARN, FAIL }

    data class Issue(
        val level: Level,
        val rule: String,
        val where: String,
        val detail: String,
        val fix: String
    )

    data class Report(
        val ok: Boolean,
        val issues: List<Issue>,
        val contrast: Float?,
        val score: Float
    )

    /** WCAG 2.1 AA：正常文本 4.5:1，大文本 3:1 */
    private const val MIN_CONTRAST = 4.5f
    private const val MIN_CONTRAST_LARGE = 3.0f

    /**
     * 检查一张卡。component 决定要查哪些规则。
     */
    fun check(component: String, dsl: String): Report {
        val issues = mutableListOf<Issue>()
        val o = runCatching { JSONObject(dsl) }.getOrNull()

        // ① 图标类组件必须有标签
        if (component in setOf("zx_media", "zx_approval", "zx_badge")) {
            val hasLabel = o?.optString("caption").isNullOrBlank().not()
                        || o?.optString("detail").isNullOrBlank().not()
                        || o?.optString("t").isNullOrBlank().not()
            if (!hasLabel) issues += Issue(Level.FAIL, "无标签", component,
                "只画了图形/图标，读屏软件会念「未加标签的按钮」",
                "补 caption / detail / t 字段作为 contentDescription")
        }

        // ② 状态不能只靠颜色
        if (component in setOf("zx_diff", "zx_timeline", "zx_approval")) {
            // diff 的 +/- 号、timeline 的 ✓、approval 的等级文字都属于非颜色线索
            val hasTextCue = when (component) {
                "zx_diff" -> dsl.contains("\"t\"")             // 行首 +/- 号
                "zx_timeline" -> dsl.contains("\"st\"") || dsl.contains("\"n\"")
                "zx_approval" -> dsl.contains("\"level\"")      // L1-L5 文字
                else -> true
            }
            if (!hasTextCue) issues += Issue(Level.WARN, "仅颜色", component,
                "状态差异只用颜色区分，色觉障碍用户无法分辨",
                "同时给一个文字或符号线索（如 ✓ / ✗ / L3）")
        }

        // ③ 对比度：正文色 vs 卡片底色
        val fg = o?.optString("fgColor")?.takeIf { it.startsWith("#") }
        val bg = o?.optString("bgColor")?.takeIf { it.startsWith("#") }
        val contrast = if (fg != null && bg != null) contrastRatio(fg, bg)
                       else defaultContrast(component)
        if (contrast != null && contrast < MIN_CONTRAST) {
            issues += Issue(Level.FAIL, "对比度", component,
                "前景/背景对比度 ${"%.2f".format(contrast)}:1，低于 WCAG AA 要求的 $MIN_CONTRAST:1",
                "调亮前景色或调暗背景色，至少到 $MIN_CONTRAST:1")
        }

        // ④ 表格类必须有表头，否则读屏念数据时不知道每列是什么
        if (component == "zx_table" && (o?.optJSONArray("columns")?.length() ?: 0) == 0) {
            issues += Issue(Level.FAIL, "缺表头", component,
                "表格没有 columns，读屏逐格念数值时听不出列含义",
                "补 columns 数组")
        }

        // ⑤ 可点项要有区分度：决策卡多个选项 t 不能都一样
        if (component == "zx_choice") {
            val opts = o?.optJSONArray("options")
            val titles = mutableSetOf<String>()
            if (opts != null) for (i in 0 until opts.length()) {
                titles.add(opts.optJSONObject(i)?.optString("t").orEmpty())
            }
            if (titles.size < (opts?.length() ?: 0)) {
                issues += Issue(Level.WARN, "选项重复", component,
                    "存在标题相同的选项，读屏念出来无法区分",
                    "给每个选项不同的 t")
            }
        }

        val score = when {
            issues.isEmpty() -> 1f
            else -> 1f - issues.count { it.level == Level.FAIL } * 0.4f -
                    issues.count { it.level == Level.WARN } * 0.15f
        }.coerceIn(0f, 1f)

        return Report(
            ok = issues.none { it.level == Level.FAIL },
            issues = issues,
            contrast = contrast,
            score = score
        )
    }

    /** 默认配色下的对比度（宿主 token） */
    private fun defaultContrast(component: String): Float {
        // 卡片底 #181B21，正文 #EDEFF4 → 约 14.8:1，远超标准
        // 次要文字 #A2A9B8 → 约 6.4:1，达标
        // 弱化文字 #6B7280 → 约 3.5:1，仅够大文本
        return when (component) {
            "zx_terminal" -> 6.4f     // 终端用次要色
            else -> 14.8f             // 正文色
        }
    }

    /**
     * WCAG 2.1 相对亮度与对比度。
     * 相对亮度：L = 0.2126R + 0.7152G + 0.0722B，其中每个分量先做伽马校正。
     */
    fun contrastRatio(fgHex: String, bgHex: String): Float {
        val l1 = relativeLuminance(fgHex)
        val l2 = relativeLuminance(bgHex)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun relativeLuminance(hex: String): Float {
        val c = hex.removePrefix("#")
        if (c.length != 6) return 0f
        fun channel(i: Int): Float {
            val v = c.substring(i, i + 2).toIntOrNull(16) ?: 0
            val s = v / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow((s + 0.055f) / 1.055f.toDouble(), 2.4).toFloat()
        }
        return 0.2126f * channel(0) + 0.7152f * channel(2) + 0.0722f * channel(4)
    }

    /** 报告渲染成终端卡 */
    fun reportAsTerminal(component: String, dsl: String): String {
        val r = check(component, dsl)
        val lines = org.json.JSONArray()
        lines.put(JSONObject().put("lv", "cmd").put("c", "zx_a11y $component"))
        r.contrast?.let {
            lines.put(JSONObject().put("lv", if (it >= MIN_CONTRAST) "ok" else "err")
                .put("c", "对比度 ${"%.2f".format(it)}:1（AA 要求 $MIN_CONTRAST:1）"))
        }
        if (r.issues.isEmpty()) {
            lines.put(JSONObject().put("lv", "ok").put("c", "✓ 无障碍检查通过"))
        } else {
            r.issues.forEach { i ->
                lines.put(JSONObject().put("lv", if (i.level == Level.FAIL) "err" else "warn")
                    .put("c", "[${i.rule}] ${i.detail}"))
                lines.put(JSONObject().put("lv", "out").put("c", "   修法：${i.fix}"))
            }
        }
        lines.put(JSONObject().put("lv", if (r.ok) "ok" else "err")
            .put("c", "可用性得分 ${"%.2f".format(r.score)}"))
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_a11y").put("exit", if (r.ok) 0 else 1).put("lines", lines).toString()
    }

    /** 给模型的编写指引 */
    fun guide(): String = """
        无障碍三条（不影响渲染，只影响谁能用）：
          1. 图标必须有文字标签（caption / detail / t）
          2. 状态不能只靠颜色，要带文字或符号线索（✓ / ✗ / L3 / +/-）
          3. 对比度 ≥ $MIN_CONTRAST:1（大文本 ≥ $MIN_CONTRAST_LARGE:1）

        表格必须有 columns，否则读屏念数值时听不出列含义。
    """.trimIndent()
}
