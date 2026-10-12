package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 意图协议 —— AI 只说"要什么"，不说"怎么画"。
 *
 * 解决的问题：DSL 是给渲染器看的，不是给模型看的。
 * 让模型每次输出几十行 JSON，是在烧 token 买本可以避免的错误——
 * 字段名拼错、数组长度对不齐、类型写反，然后被校验打回来重发一轮。
 *
 * 意图协议把这件事反过来：
 *   模型：{"intent":"compare","subjects":["A","B","C"],"dims":["价格","跑分"]}
 *   本地：选模板 → 填参数 → 校验 → 自愈 → 下发
 *
 * 同样一次"三列对比"，DSL 要 ~400 token，意图只要 ~40 token。
 * 差出来的 token 全部还给推理预算。
 *
 * 三条硬规则：
 *   1. 意图只能引用已注册的意图类型，不能自带 DSL（否则退化回原问题）
 *   2. 本地编译必须走与手写 DSL 完全相同的校验，不因为是"编译出来的"就放宽
 *   3. 编译结果要能被模型读懂——回报用了哪个模板、填了什么，失败时给可执行建议
 */
object ZxIntent {

    /** 意图类型注册表：名字 -> (默认模板, 必填字段, 可选字段) */
    private data class Spec(
        val template: String,
        val required: List<String>,
        val optional: List<String> = emptyList(),
        val hint: String
    )

    private val REGISTRY = mapOf(
        "compare" to Spec("phone_compare",
            listOf("title", "col0", "col1", "col2", "p0", "p1", "p2"),
            hint = "多对象对比。subjects 给 2-3 个，dims 给对比维度。"),
        "trend" to Spec("budget_chart",
            listOf("title", "v0", "v1", "v2", "l0", "l1", "l2"),
            hint = "趋势/分布。points 给数值，labels 给坐标名。"),
        "guide" to Spec("deploy_stepper",
            listOf("title", "s0", "d0", "s1", "d1", "s2", "d2"),
            hint = "分步向导。steps 给 3 步，每步有标题与说明。")
    )

    /** 编译结果 */
    data class Compiled(
        val ok: Boolean,
        val component: String = "",
        val dsl: String = "",
        val template: String = "",
        val reason: String = "",
        /** 相比手写 DSL 省下的估算 token */
        val savedTokens: Int = 0
    )

    fun types(): String = REGISTRY.entries.joinToString("\n") {
        "  ${it.key}  ${it.value.hint}\n    必填：${it.value.required.joinToString()}"
    }

    /**
     * 编译一个意图。intentJson 形如：
     *   {"intent":"compare","args":{"title":"…","col0":"A",...}}
     * 或者更自然的：
     *   {"intent":"compare","title":"…","subjects":["A","B","C"],"prices":[2199,3299,4699]}
     */
    fun compile(raw: String): Compiled {
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Compiled(false, reason = "意图不是合法 JSON：$raw")

        val type = o.optString("intent")
        val spec = REGISTRY[type]
            ?: return Compiled(false, reason = "未注册的意图：$type。可用：${REGISTRY.keys.joinToString()}\n${types()}")

        // 归一化：支持 subjects/prices 这类自然写法，也支持 args 直填
        val args = normalize(type, o)
        val missing = spec.required.filter { args[it].isNullOrBlank() }
        if (missing.isNotEmpty())
            return Compiled(false, reason = "缺少字段：${missing.joinToString()}。\n${spec.hint}")

        // 选模板 → 填参数 → 校验（与手写 DSL 同一套）
        val t = ZxTemplate.get(spec.template)
            ?: return Compiled(false, reason = "模板 ${spec.template} 未安装，请先 zx_template(op=list)")

        val r = ZxTemplate.instantiate(spec.template, args.filterValues { !it.isNullOrBlank() }.mapValues { it.value!! })
        if (!r.ok) {
            // 交给自愈再试一次，别让模型为格式问题重发一轮
            val healed = ZxHeal.attempt(spec.template, r.reason, args)
            if (healed.ok) {
                return Compiled(true, component = t.component, dsl = healed.dsl,
                    template = spec.template,
                    reason = "已编译（经自愈：${healed.fixes.joinToString("；")}）",
                    savedTokens = estimate(raw, healed.dsl))
            }
            return Compiled(false, reason = "实例化失败：${r.reason}")
        }

        // 编译出来的 DSL 同样要过校验
        val check = ZxDsl.validate(t.component, r.reason)
        if (!check.ok) return Compiled(false, reason = "编译结果校验失败：${check.reason}")

        return Compiled(true, component = t.component, dsl = r.reason,
            template = spec.template, reason = "已编译为 ${t.component}",
            savedTokens = estimate(raw, r.reason))
    }

    /** 把自然写法归一化成模板参数 */
    private fun normalize(type: String, o: JSONObject): Map<String, String?> {
        val out = linkedMapOf<String, String?>()
        // args 直填
        val args = o.optJSONObject("args")
        if (args != null) args.keys().forEach { out[it] = args.optString(it) }
        // 顶层字段
        o.keys().forEach { if (it != "intent" && it != "args") out[it] = o.optString(it) }

        when (type) {
            "compare" -> {
                val subjects = o.optJSONArray("subjects")
                if (subjects != null) for (i in 0 until minOf(subjects.length(), 3)) {
                    out["col$i"] = subjects.optString(i)
                }
                val prices = o.optJSONArray("prices")
                if (prices != null) for (i in 0 until minOf(prices.length(), 3)) {
                    out["p$i"] = prices.opt(i).toString()
                }
            }
            "trend" -> {
                val pts = o.optJSONArray("points")
                if (pts != null) for (i in 0 until minOf(pts.length(), 3)) out["v$i"] = pts.opt(i).toString()
                val lbs = o.optJSONArray("labels")
                if (lbs != null) for (i in 0 until minOf(lbs.length(), 3)) out["l$i"] = lbs.optString(i)
            }
            "guide" -> {
                val steps = o.optJSONArray("steps")
                if (steps != null) for (i in 0 until minOf(steps.length(), 3)) {
                    val s = steps.optJSONObject(i) ?: steps.optString(i)
                    if (s is JSONObject) { out["s$i"] = s.optString("t"); out["d$i"] = s.optString("d") }
                }
            }
        }
        return out
    }

    /** 省下的 token：意图长度 vs 手写等价 DSL 长度，按 1 token ≈ 3 字符估 */
    private fun estimate(intentRaw: String, dsl: String): Int =
        ((dsl.length - intentRaw.length) / 3).coerceAtLeast(0)

    /** 编译过程渲染成终端卡，让模型和用户都看得见发生了什么 */
    fun explainAsTerminal(raw: String): String {
        val c = compile(raw)
        val lines = JSONArray()
        lines.put(JSONObject().put("lv", "cmd").put("c", "zx_intent compile"))
        lines.put(JSONObject().put("lv", "out").put("c", "输入：$raw"))
        if (!c.ok) {
            lines.put(JSONObject().put("lv", "err").put("c", c.reason))
        } else {
            lines.put(JSONObject().put("lv", "ok").put("c", "→ ${c.component}  模板=${c.template}"))
            lines.put(JSONObject().put("lv", "out").put("c", "省下约 ${c.savedTokens} token（${c.reason}）"))
            lines.put(JSONObject().put("lv", "out").put("c", c.dsl))
        }
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_intent").put("exit", if (c.ok) 0 else 1).put("lines", lines).toString()
    }

    fun sample(): String = """
        // 手写 DSL ≈ 400 token
        {"type":"zx_compare","title":"旗舰对比","columns":["A","B","C"],
         "rows":[{"k":"价格","v":["¥2199","¥3299","¥4699"]}], ...}

        // 意图协议 ≈ 40 token，本地编译出同样的卡
        {"intent":"compare","title":"旗舰对比",
         "subjects":["A","B","C"],"prices":[2199,3299,4699]}
    """.trimIndent()
}
