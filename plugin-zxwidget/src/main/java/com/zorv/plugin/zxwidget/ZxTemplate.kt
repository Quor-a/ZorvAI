package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模板库 —— 一张卡存成模板，下次 AI 直接按参数实例化。
 *
 * 解决的问题：AI 每次都要重新拼完整 DSL。同样的"机型对比卡"，
 * 第一次写对了，第十次可能字段名写歪、少个必填项，然后被校验打回来重发。
 * 模板把"正确的形状"固化下来，AI 只填参数。
 *
 * 模板里的参数用 {{$name}} 占位（区别于跨卡引用的 {{ref.xxx.yyy}}）：
 *   {{$xxx}}        → 实例化时由 AI 传入
 *   {{ref.xxx.yyy}} → 实例化后仍然保留，指向运行时的另一张卡
 *
 * 两种占位符共存是刻意的：模板解决"形状对不对"，引用解决"数据跟不跟得上"。
 */
object ZxTemplate {

    data class Template(
        val name: String,
        val component: String,
        val dsl: String,
        val params: List<String>,
        val desc: String
    )

    private val store = linkedMapOf<String, Template>()

    private val PARAM_RE = Regex("\\{\\{\\s*\\$([A-Za-z0-9_]+)\\s*\\}\\}")

    /** 从一张已渲染的卡存成模板：把它的数据值抽成参数占位 */
    fun saveFromCard(name: String, cardId: String, desc: String, paramPaths: List<String>): Result {
        val node = ZxRef.get(cardId) ?: return Result(false, "卡片 $cardId 不存在")
        var dsl = node.dsl
        val params = mutableListOf<String>()
        for (p in paramPaths) {
            val key = p.substringAfterLast('.').substringBefore('[')
            val value = ZxRef.readValue(cardId, p) ?: continue
            if (value.isBlank()) continue
            dsl = dsl.replace(value, "{{$$key}}")
            params.add(key)
        }
        if (params.isEmpty())
            return Result(false, "没有抽到任何参数，检查 paramPaths 是否指向有值的字段")
        store[name] = Template(name, node.component, dsl, params, desc)
        ZxAudit.log(ZxAudit.Kind.TEMPLATE, cardId, "存为模板 $name（参数 ${params.joinToString()}）")
        return Result(true, "已存模板 $name · ${node.component} · 参数 ${params.joinToString()}")
    }

    /** 直接用 DSL 存模板（AI 手写） */
    fun save(name: String, component: String, dsl: String, desc: String): Result {
        if (!name.matches(Regex("[a-z0-9_]{2,32}")))
            return Result(false, "模板名只能含小写字母/数字/下划线，2-32 字符：$name")
        val params = PARAM_RE.findAll(dsl).map { it.groupValues[1] }.distinct().toList()
        store[name] = Template(name, component, dsl, params, desc)
        ZxAudit.log(ZxAudit.Kind.TEMPLATE, name, "注册模板 · 参数 ${params.joinToString()}")
        return Result(true, "已存模板 $name · 参数 ${params.joinToString().ifEmpty { "无" }}")
    }

    fun get(name: String): Template? = store[name]
    fun list(): List<Template> = store.values.toList()

    /**
     * 实例化：填参数 → 校验 → 返回可直接下发的 DSL。
     * 校验在实例化后做，因为参数值可能把形状改坏。
     */
    fun instantiate(name: String, args: Map<String, String>): Result {
        val t = store[name] ?: return Result(false, "模板不存在：$name，可用：${store.keys.joinToString()}")
        var dsl = t.dsl
        val missing = mutableListOf<String>()
        for (p in t.params) {
            val v = args[p]
            if (v == null) { missing.add(p); continue }
            dsl = dsl.replace(Regex("\\{\\{\\s*\\$$p\\s*\\}\\}"), v)
        }
        if (missing.isNotEmpty())
            return Result(false, "缺少参数：${missing.joinToString()}。该模板需要：${t.params.joinToString()}")

        // 残留占位符说明有参数没被声明，直接报出来而不是留个洞
        val leftover = PARAM_RE.findAll(dsl).map { it.groupValues[1] }.toList()
        if (leftover.isNotEmpty())
            return Result(false, "DSL 里还有未填充的占位符：${leftover.joinToString()}")

        val check = ZxDsl.validate(t.component, dsl)
        if (!check.ok) return Result(false, "实例化后校验失败：${check.reason}")

        return Result(true, dsl)
    }

    /** 模板清单渲染成终端卡 */
    fun listAsTerminal(): String {
        val lines = JSONArray()
        if (store.isEmpty()) {
            lines.put(JSONObject().put("lv", "warn").put("c", "还没有存任何模板"))
        } else {
            lines.put(JSONObject().put("lv", "cmd").put("c", "zx_template list"))
            store.values.forEach { t ->
                lines.put(JSONObject().put("lv", "ok").put("c", "${t.name}  [${t.component}]"))
                lines.put(JSONObject().put("lv", "out")
                    .put("c", "    ${t.desc} · 参数 ${t.params.joinToString().ifEmpty { "无" }}"))
            }
        }
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_template list").put("exit", 0).put("lines", lines).toString()
    }

    /* ── 内置模板：开箱即用，也让模型知道该怎么写 ─────────────── */

    fun installBuiltins() {
        save("phone_compare", "zx_compare", """
            {"type":"zx_compare","title":"{{${'$'}title}}",
             "columns":[{{${'$'}col0}},{{${'$'}col1}},{{${'$'}col2}}],
             "rows":[{"k":"价格","v":["{{${'$'}p0}}","{{${'$'}p1}}","{{${'$'}p2}}"]}],
             "onPick":{"action":"tool_call","name":"zx_select","args":{"choice":"{{item.key}}"}}}
        """.trimIndent(), "三列机型/方案对比，带点选回传")

        save("budget_chart", "zx_chart", """
            {"type":"zx_chart","style":"bar","title":"{{${'$'}title}}",
             "points":[{{${'$'}v0}},{{${'$'}v1}},{{${'$'}v2}}],
             "labels":["{{${'$'}l0}}","{{${'$'}l1}}","{{${'$'}l2}}"]}
        """.trimIndent(), "三项预算分布柱状图")

        save("deploy_stepper", "zx_stepper", """
            {"type":"zx_stepper","title":"{{${'$'}title}}",
             "steps":[{"t":"{{${'$'}s0}}","d":"{{${'$'}d0}}"},
                      {"t":"{{${'$'}s1}}","d":"{{${'$'}d1}}"},
                      {"t":"{{${'$'}s2}}","d":"{{${'$'}d2}}"}]}
        """.trimIndent(), "三步部署向导")
    }

    fun sampleUsage(): String = """
        zx_template(save, name="phone_compare", ...)
        zx_template(use,  name="phone_compare",
                    args={"title":"旗舰对比","col0":"A","col1":"B","col2":"C",
                          "p0":"¥2199","p1":"¥3299","p2":"¥4699"})
        → 实例化 + 校验 + 下发，一条链路走完
    """.trimIndent()

    fun clear() { store.clear() }
}
