package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 渲染决策路由 —— 决定"这一轮到底该画什么"。
 *
 * 同一个意图在不同条件下最优解不同：
 *   联网 + 大模型 + 屏幕够宽 → 图表最好读
 *   离线 + 端侧小模型       → 图表画得出来，但数据抓不来，画了也是空的
 *   极窄屏 / token 快用完   → 表格比图表省，文本比表格省
 *
 * 所以不能直接让模型拍脑袋选组件，要在下发前过一遍路由：
 *   1. 算每个候选组件的得分（信息密度 × 条件适配 ÷ 成本）
 *   2. 取最高分，同时给出降级链
 *   3. 条件不满足时逐级降级，不硬画
 *
 * 得分是启发式的，不是魔法数字——目的是让降级有依据、可解释，
 * 而不是模型心情好就画图表，心情不好就丢一段文字。
 */
object ZxRouter {

    /** 渲染时的环境条件 */
    data class Ctx(
        val online: Boolean,
        /** 端侧引擎（MNN / llama.cpp）是否就绪 */
        val localEngine: Boolean,
        /** 剩余 token 预算，低于阈值就往省的方向降级 */
        val tokenBudget: Int,
        /** 屏幕宽度 dp，窄屏不适合多列 */
        val widthDp: Int,
        /** 本轮数据点数量，太少画图表没意义 */
        val dataPoints: Int = 0
    )

    /** 决策结果 */
    data class Decision(
        val component: String,
        val score: Float,
        val reason: String,
        /** 条件恶化时的降级顺序 */
        val fallback: List<String>
    )

    /** 组件固有属性：信息密度 / 交互成本 / 最小可用宽度 / 是否需要网络 */
    private data class Spec(
        val density: Float,   // 0-1，同样面积能装多少信息
        val cost: Float,      // 0-1，渲染与 token 开销
        val minWidth: Int,
        val needsNet: Boolean
    )

    private val SPECS = mapOf(
        "zx_chart"    to Spec(0.92f, 0.60f, 240, false),
        "zx_table"    to Spec(0.80f, 0.45f, 200, false),
        "zx_compare"  to Spec(0.78f, 0.40f, 260, false),
        "zx_kanban"   to Spec(0.70f, 0.55f, 300, false),
        "zx_diff"     to Spec(0.75f, 0.50f, 260, false),
        "zx_timeline" to Spec(0.55f, 0.30f, 200, false),
        "zx_form"     to Spec(0.40f, 0.35f, 240, false),
        "zx_choice"   to Spec(0.45f, 0.25f, 220, false),
        "zx_stepper"  to Spec(0.35f, 0.30f, 220, false),
        "zx_terminal" to Spec(0.50f, 0.20f, 200, false),
        "zx_media"    to Spec(0.85f, 0.75f, 240, true),
        "zx_approval" to Spec(0.30f, 0.20f, 220, false)
    )

    /** 意图 → 候选组件（按常规优先级） */
    private val INTENTS = mapOf(
        "compare"  to listOf("zx_compare", "zx_table", "zx_chart"),
        "trend"    to listOf("zx_chart", "zx_table", "zx_terminal"),
        "collect"  to listOf("zx_form", "zx_choice", "zx_stepper"),
        "progress" to listOf("zx_timeline", "zx_stepper", "zx_terminal"),
        "code"     to listOf("zx_diff", "zx_terminal", "zx_table"),
        "decide"   to listOf("zx_choice", "zx_compare", "zx_form"),
        "output"   to listOf("zx_terminal", "zx_table"),
        "authorize" to listOf("zx_approval", "zx_choice"),
        "preview"  to listOf("zx_media", "zx_terminal")
    )

    private const val TOKEN_LOW = 800

    /**
     * 环境条件由宿主注入，路由不自己猜。
     * 未注入时按"在线 + 端侧就绪 + 宽屏"的常规值走，保证不因为拿不到环境就全降级成文本。
     */
    data class Env(
        val online: Boolean = true,
        val localEngine: Boolean = true,
        val tokenBudget: Int = 4000,
        val widthDp: Int = 360
    )

    private var envProbe: () -> Env = { Env() }
    fun attachProbe(probe: () -> Env) { envProbe = probe }
    fun snapshot(): Env = runCatching { envProbe() }.getOrDefault(Env())

    fun decide(intent: String, ctx: Ctx): Decision {
        val candidates = INTENTS[intent] ?: listOf("zx_table", "zx_terminal")
        val scored = candidates.map { it to score(it, ctx) }
        // 条件不满足的直接判负，不参与竞争
        val viable = scored.filter { it.second > 0f }
        if (viable.isEmpty()) {
            // 全部不可用 → 退到纯文本，这是唯一兜底
            return Decision("text", 0f,
                "当前条件下没有可用组件（离线且无端侧引擎 / 屏幕过窄 / token 耗尽），降级为纯文本。",
                emptyList())
        }
        val best = viable.maxBy { it.second }
        val reason = explain(best.first, ctx, best.second)
        val chain = viable.sortedByDescending { it.second }.map { it.first }
        return Decision(best.first, best.second, reason, chain)
    }

    private fun score(component: String, ctx: Ctx): Float {
        val s = SPECS[component] ?: return 0f
        // 硬约束：宽度不够、需要网络但离线 → 直接 0
        if (ctx.widthDp < s.minWidth) return 0f
        if (s.needsNet && !ctx.online) return 0f

        var v = s.density * (1f - s.cost * 0.5f)

        // token 紧张时，高开销组件按比例扣分
        if (ctx.tokenBudget < TOKEN_LOW) {
            v -= s.cost * (1f - ctx.tokenBudget.toFloat() / TOKEN_LOW) * 0.6f
        }
        // 离线但有端侧引擎：本地能画，只是数据可能不全，轻微扣分而非禁用
        if (!ctx.online) {
            v -= if (ctx.localEngine) 0.08f else 0.45f
        }
        // 数据点太少，图表没意义
        if (component == "zx_chart" && ctx.dataPoints in 1..1) v -= 0.5f
        // 屏幕宽的时候，多列组件更占优势
        if (ctx.widthDp >= 360 && component in setOf("zx_compare", "zx_table", "zx_kanban")) v += 0.06f

        return v.coerceIn(0f, 1f)
    }

    private fun explain(c: String, ctx: Ctx, score: Float): String = buildString {
        append("选 $c（得分 %.2f）：".format(score))
        val s = SPECS[c]!!
        append("信息密度 %.2f、成本 %.2f。".format(s.density, s.cost))
        if (!ctx.online) append(if (ctx.localEngine) "离线但端侧引擎就绪，可本地渲染。" else "离线且无端侧引擎，已大幅降权。")
        if (ctx.tokenBudget < TOKEN_LOW) append("token 余量 ${ctx.tokenBudget}，高开销组件已扣分。")
        if (ctx.widthDp < 300) append("窄屏 ${ctx.widthDp}dp，多列组件受限。")
    }

    /** 当前条件下，某组件的下一级降级目标 */
    fun fallbackOf(component: String, ctx: Ctx): String? {
        val s = SPECS[component] ?: return "text"
        // 同类中找成本更低、宽度要求更低的
        return SPECS.entries
            .filter { it.key != component && it.value.cost < s.cost && it.value.minWidth <= ctx.widthDp
                      && (!it.value.needsNet || ctx.online) }
            .maxByOrNull { it.value.density }?.key ?: "text"
    }

    /** 决策过程渲染成终端卡，让用户看得见 AI 为什么这么画 */
    fun explainAsTerminal(intent: String, ctx: Ctx): String {
        val d = decide(intent, ctx)
        val lines = JSONArray()
        lines.put(JSONObject().put("lv", "cmd").put("c", "zx_route intent=$intent"))
        lines.put(JSONObject().put("lv", "out").put("c",
            "条件：${if (ctx.online) "在线" else "离线"} · 端侧引擎 ${if (ctx.localEngine) "就绪" else "未就绪"} · " +
            "token ${ctx.tokenBudget} · ${ctx.widthDp}dp · ${ctx.dataPoints} 点"))
        lines.put(JSONObject().put("lv", "ok").put("c", "→ ${d.component}  (${"%.2f".format(d.score)})"))
        lines.put(JSONObject().put("lv", "out").put("c", d.reason))
        if (d.fallback.size > 1) {
            lines.put(JSONObject().put("lv", "out").put("c", "降级链：${d.fallback.joinToString(" → ")}"))
        }
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_route").put("exit", 0).put("lines", lines).toString()
    }

    /** 供模型直接抄的调用示例 */
    fun sample(): String = """
        // 让路由决定画什么，而不是模型硬选
        zx_route(intent="trend", data_points=7)
        → zx_chart（在线 · 360dp · 7 点）
        → 离线时自动降级为 zx_table
    """.trimIndent()
}
