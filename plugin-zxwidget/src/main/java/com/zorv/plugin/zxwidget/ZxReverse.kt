package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 意图反向编译 —— 从已有卡片反推意图，让模型能复用上一轮的卡。
 *
 * ⑫ 做了意图协议：模型只输出意图，本地编译出 DSL。
 * 省下的 token 全部还给推理预算（实测手写 ≈400 token → 意图 ≈40 token）。
 *
 * 但只有正向是不够的。真实会话里最常见的不是"从零画一张"，
 * 而是**"再来一张差不多那样的，换个数据"**：
 *
 *   用户：「刚才那张对比卡，把 iPhone 也加进去」
 *   模型：？
 *
 * 它有两个选择，都不好：
 *   一、凭记忆重写整份 DSL——400 token，而且容易写错形状
 *   二、把上一张卡的 DSL 读进来——更贵，而且它真正要的只是"那个形状"
 *
 * 反向编译要做的是：**把卡片压回意图**。
 * 模型拿到的是 `{"intent":"compare","subjects":[...]}`，
 * 改一个字段再发出去，走 ⑫ 的正向编译重新出卡。
 *
 * 三处必须想清楚：
 *
 *   一、**不保证无损**。DSL 里有渲染细节（配色、字号、内边距），
 *       意图里没有。这是有意的——那些细节本来就不该进上下文。
 *       反推出来的是"形状 + 数据"，不是完整 DSL。
 *
 *   二、**认不出来就明说**。不能猜出一个看起来像的意图——
 *       模型会当成真的用，然后画出一张用户没要的卡。
 *       宁可回"无法反推"，让模型自己重写。
 *
 *   三、**数据要能抽出来**。反推的价值一半在形状、一半在数据。
 *       模型要改的是数据（加一个 iPhone），不是形状。
 */
object ZxReverse {

    data class Intent(
        val intent: String,
        val fields: Map<String, Any?>,
        /** 置信度：0-1。低于阈值应当视为"没认出来" */
        val confidence: Double,
        val note: String = ""
    ) {
        fun toJson(): String = JSONObject().apply {
            put("intent", intent)
            fields.forEach { (k, v) -> put(k, v) }
            put("_confidence", confidence)
            if (note.isNotBlank()) put("_note", note)
        }.toString()

        fun tokens(): Int = toJson().length / 3
    }

    /** 置信度低于这个值就当没认出来 */
    private const val MIN_CONF = 0.5

    /**
     * 从 DSL 反推意图。认不出来返回 null（不是猜一个）。
     */
    fun infer(component: String, dsl: JSONObject): Intent? = when (component) {
        "zx_compare" -> compare(dsl)
        "zx_table"   -> table(dsl)
        "zx_chart"   -> chart(dsl)
        "zx_metrics" -> metrics(dsl)
        "zx_form"    -> form(dsl)
        "zx_timeline"-> timeline(dsl)
        "zx_choice"  -> choice(dsl)
        "zx_progress"-> progress(dsl)
        "zx_rank"    -> rank(dsl)
        "zx_stat"    -> stat(dsl)
        else -> null
    }

    private fun compare(o: JSONObject): Intent? {
        val cols = o.optJSONArray("columns") ?: o.optJSONArray("cols") ?: return null
        val dims = o.optJSONArray("rows")
        val subjects = (0 until cols.length()).map { cols.optString(it) }
        if (subjects.isEmpty()) return null
        val rows = (0 until (dims?.length() ?: 0)).mapNotNull { i ->
            val r = dims!!.optJSONObject(i) ?: return@mapNotNull null
            val v = r.optJSONArray("v")
            r.optString("n", "") to (0 until (v?.length() ?: 0)).map { v!!.opt(it) }
        }
        return Intent("compare",
            mapOf("title" to o.optString("title", ""),
                  "subjects" to subjects,
                  "rows" to rows),
            if (rows.isEmpty()) .7 else .95,
            "改 subjects 增列、改 rows 增行，再走 zx_intent 重新编译")
    }

    private fun table(o: JSONObject): Intent? {
        val cols = o.optJSONArray("columns") ?: return null
        val rows = o.optJSONArray("rows") ?: return null
        val names = (0 until cols.length()).map { cols.optString(it) }
        val data = (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONObject(i) ?: return@mapNotNull null
            r.optJSONArray("v")?.let { v -> (0 until v.length()).map { v.opt(it) } }
                ?: (0 until names.size).map { r.opt(names[it]) }
        }
        return Intent("table", mapOf("columns" to names, "rows" to data), .92)
    }

    private fun chart(o: JSONObject): Intent? {
        val style = o.optString("style", "bar")
        val labels = o.optJSONArray("labels") ?: return null
        val pts = o.optJSONArray("points") ?: return null
        val ls = (0 until labels.length()).map { labels.optString(it) }
        // 多序列：points 里嵌数组
        val series = if (pts.optJSONArray(0) != null) {
            (0 until pts.length()).map { i ->
                val a = pts.optJSONArray(i)!!
                (0 until a.length()).map { a.optDouble(it, 0.0) }
            }
        } else listOf((0 until pts.length()).map { pts.optDouble(it, 0.0) })
        return Intent("chart", mapOf("style" to style, "labels" to ls, "series" to series),
                      if (series.size > 1) .8 else .95)
    }

    private fun metrics(o: JSONObject): Intent? {
        val items = o.optJSONArray("items") ?: return null
        val list = (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            mapOf("n" to it.optString("n", ""), "v" to it.opt("v"),
                  "d" to it.optString("d", ""))
        }
        if (list.isEmpty()) return null
        return Intent("metrics", mapOf("items" to list), .9)
    }

    private fun form(o: JSONObject): Intent? {
        val fields = o.optJSONArray("fields") ?: return null
        val list = (0 until fields.length()).mapNotNull { i ->
            val f = fields.optJSONObject(i) ?: return@mapNotNull null
            mapOf("k" to f.optString("k", ""), "n" to f.optString("n", ""),
                  "t" to f.optString("t", "text"), "v" to f.opt("v"))
        }
        if (list.isEmpty()) return null
        return Intent("form", mapOf("fields" to list), .88,
                      "只回形状不回已填的值——那些是用户输入，不该进上下文")
    }

    private fun timeline(o: JSONObject): Intent? {
        val steps = o.optJSONArray("steps") ?: return null
        val list = (0 until steps.length()).mapNotNull { i ->
            val s = steps.optJSONObject(i) ?: return@mapNotNull null
            mapOf("n" to s.optString("n", ""), "st" to s.optString("st", "pending"))
        }
        if (list.isEmpty()) return null
        return Intent("timeline", mapOf("steps" to list), .9)
    }

    private fun choice(o: JSONObject): Intent? {
        val opts = o.optJSONArray("options") ?: o.optJSONArray("opts") ?: return null
        val list = (0 until opts.length()).mapNotNull { i ->
            val x = opts.optJSONObject(i) ?: return@mapNotNull null
            mapOf("n" to x.optString("n", ""), "v" to x.opt("v"),
                  "rec" to x.optBoolean("rec", false))
        }
        if (list.isEmpty()) return null
        return Intent("choice", mapOf("options" to list), .9)
    }

    private fun progress(o: JSONObject): Intent? {
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return null
        return Intent("progress", mapOf("v" to v, "n" to o.optString("n", "")), .95)
    }

    private fun rank(o: JSONObject): Intent? {
        val items = o.optJSONArray("items") ?: return null
        val list = (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            mapOf("n" to it.optString("n", ""), "v" to it.opt("v"))
        }
        if (list.isEmpty()) return null
        return Intent("rank", mapOf("items" to list), .93)
    }

    private fun stat(o: JSONObject): Intent? {
        val items = o.optJSONArray("items") ?: return null
        val list = (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            mapOf("n" to it.optString("n", ""), "v" to it.opt("v"),
                  "lb" to it.optBoolean("lower", false))
        }
        if (list.isEmpty()) return null
        return Intent("stat", mapOf("items" to list), .9)
    }

    /** 比较：反推后的意图比原 DSL 省多少 */
    fun saving(component: String, dsl: JSONObject): String {
        val raw = dsl.toString().length / 3
        val it = infer(component, dsl)
            ?: return "无法反推 $component —— 宁可让模型自己重写，也不要猜一个看起来像的"
        return "原 DSL ≈ $raw token → 意图 ≈ ${it.tokens()} token" +
               "（省 ${((1.0 - it.tokens().toDouble() / raw) * 100).toInt()}%）\n" +
               "置信度 ${it.confidence}" +
               (if (it.confidence < MIN_CONF) "（低于 $MIN_CONF，建议当作没认出来）" else "")
    }

    fun report(): String = buildString {
        appendLine("可反推的组件：compare / table / chart / metrics / form /")
        appendLine("              timeline / choice / progress / rank / stat")
        appendLine()
        appendLine("⑫ 做了正向：模型只输出意图，本地编译出 DSL，省下的 token 还给推理。")
        appendLine("但只有正向不够——真实会话里最常见的不是「从零画一张」，")
        appendLine("而是「再来一张差不多那样的，换个数据」。")
        appendLine()
        appendLine("三处必须想清楚：")
        appendLine("  一、不保证无损——DSL 里的配色/字号/内边距不会进意图，这是有意的")
        appendLine("  二、认不出来就明说，不猜一个看起来像的")
        appendLine("  三、数据要能抽出来——模型要改的是数据，不是形状")
    }

    fun guide(): String = """
        zx_reverse(op=infer|saving|report|guide, component?, dsl?)

        从已有卡片反推意图，让模型能复用上一轮的卡。

        ⑫ 做了正向（意图 → DSL），但只有正向不够。
        真实会话里最常见的不是「从零画一张」，
        而是「再来一张差不多那样的，换个数据」：

          用户：「刚才那张对比卡，把 iPhone 也加进去」
          模型：？

        它有两个选择，都不好：
          一、凭记忆重写整份 DSL——400 token，而且容易写错形状
          二、把上一张卡的 DSL 读进来——更贵，而它真正要的只是「那个形状」

        反向编译把卡片压回意图。模型拿到的是
          {"intent":"compare","subjects":[...]}
        改一个字段再发出去，走 ⑫ 的正向编译重新出卡。

        三处必须想清楚：
          一、不保证无损——配色/字号/内边距不进意图，这是有意的
          二、认不出来就明说——猜一个看起来像的会让模型画出用户没要的卡
          三、数据要能抽出来——模型要改的是数据（加一个 iPhone），不是形状
    """.trimIndent()
}
