package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 组件自组合协议 —— 按语义把已有组件拼成新组件，不再手工加。
 *
 * **为什么现在才需要**
 *
 * 从第一批到第二十八批，组件数一路涨到 311 个，每一次都是**手工加**：
 * 写一个渲染函数、写一份校验、登记一次、补示例和规格。
 *
 * 手工加有个绕不过去的天花板：**组合爆炸**。
 *
 *   已有 zx_forecast（预测）     想要"预测 vs 实际"
 *   已有 zx_feedback（结果回收）   → 于是加了 zx_feedback，它其实是 forecast 的特例
 *   已有 zx_table（表格）        想要"可编辑的分组表"
 *   已有 zx_spreadsheet（表格）    → 又是一个特例
 *
 * 继续这么加下去，第 500 个组件会是第 499 个的微调版，
 * 而模型要在 500 个名字里挑，挑错的概率比挑对高。
 *
 * 所以换个思路：**不新增组件，而是把已有组件拼起来。**
 *
 *   compose("forecast_actual", 主轴=zx_forecast, 叠加=zx_feedback)
 *   compose("table_editable",  主轴=zx_table,    叠加=zx_spreadsheet 的编辑层)
 *
 * 拼出来的东西**不是一个新组件**，而是一份"这个组合叫什么、由什么拼成"的声明。
 * 渲染时按顺序渲染主轴 + 叠加层，校验时对每层各跑一遍。
 *
 * ── 五条约束 ────────────────────────────────────────────────
 *
 * 一、**只能引用已注册组件，不能凭空造**。
 *     组合是"已有能力的新排列"，不是新能力的来源。
 *     允许凭空造等于开一个后门，而且那个后门还没有校验。
 *
 * 二、**不能覆盖内置组件名**。
 *     用户如果把 `zx_table` 覆盖成自己的组合，
 *     所有历史卡片（别人的、上一轮的）都会变样——这是最隐蔽的破坏。
 *
 * 三、**深度上限 3 层**。
 *     组合套组合，第 N 层要渲染 2^N 次。没有上限的话，
 *     一个循环引用就能把 UI 栈打爆。
 *
 * 四、**每层的 DSL 都要各自校验**。
 *     组合校验 ≠ 只校验主轴。叠加层的 DSL 如果非法，
 *     界面会显示一个"看起来能用但点了没反应"的层——
 *     这比整体渲染失败更糟。**单层非法则整个组合拒绝**，
 *     不允许装一半。
 *
 * 五、**拼出来的组合要能被反编译回意图**（与 ⑯ 反向编译同源）。
 *     否则它会变成一块黑箱：模型不知道它是什么，只能试。
 *
 * ── 与热更新的区别 ──────────────────────────────────────────
 *
 * ⑮ 热更新（ZxHot）也能造出新东西，但那三件事是：
 *   模板（别名+预设）/ 组合（容器拼装）/ 字段映射
 * 它的"组合"是**把几张卡摆进一个容器**（tabs、split）。
 *
 * 这个模块是**同一个气泡位里多层叠加**（主轴画底、叠加层画在上面），
 * 两者不冲突，但也不要混为一谈：一个是排版，一个是分层。
 */
object ZxCompose {

    /** 一层 */
    data class Layer(
        val component: String,
        /** 这一层的 DSL；未指定的字段回落到主轴 */
        val dsl: JSONObject,
        /** 叠在什么位置 */
        val at: Pos = Pos.OVERLAY
    )

    enum class Pos(val label: String, val desc: String) {
        BASE("主轴", "画在最底下，决定卡片的基本形状"),
        OVERLAY("叠加", "画在主轴上面，补充信息"),
        FOOTER("脚注", "画在最下面，通常是结论或动作条")
    }

    /** 一个组合 */
    data class Recipe(
        val name: String,
        val label: String,
        val desc: String,
        val layers: List<Layer>,
        val ts: Long = System.currentTimeMillis()
    ) {
        fun depth(seen: Int = 0): Int {
            if (seen > 8) return 99
            var d = 1
            layers.forEach { l ->
                val sub = table[l.component]
                if (sub != null) d = maxOf(d, 1 + sub.depth(seen + 1))
            }
            return d
        }
        fun components() = layers.map { it.component }
    }

    private val table = LinkedHashMap<String, Recipe>()
    private val conflicts = ArrayList<String>()

    /** 内置组件名，不允许被覆盖 */
    private fun builtin(name: String) = ZxRegistry.has(name)

    fun reg(r: Recipe): Pair<Boolean, String> {
        // 二、不能覆盖内置名
        if (builtin(r.name))
            return false to "「${r.name}」是内置组件名——**不能覆盖**。\n" +
                            "覆盖它之后，所有历史卡片（别人的、上一轮的）都会变样，" +
                            "这是最隐蔽的破坏。换一个名字。"
        if (r.layers.isEmpty()) return false to "组合至少需要 1 层"
        if (r.layers.size > 4) return false to "组合最多 4 层，实际 ${r.layers.size} 层——再多就叠糊了"
        // 一、只能引用已注册组件
        r.layers.forEach { l ->
            if (!ZxRegistry.has(l.component) && !table.containsKey(l.component))
                return false to "层「${l.component}」既不是内置组件也不是已注册组合——" +
                                "**组合只能排列已有能力，不能凭空造**（凭空造的东西没有校验）"
        }
        // 主轴必须有且只有一个
        val bases = r.layers.count { it.at == Pos.BASE }
        if (bases != 1)
            return false to "组合必须有且只有 1 个主轴，实际 $bases 个——" +
                            "没有主轴就不知道基本形状，两个主轴会互相盖住"
        // 三、深度上限
        val d = r.depth()
        if (d > 3)
            return false to "组合深度 $d 超过上限 3——**组合套组合会指数级膨胀**，" +
                            "而且循环引用会把 UI 栈打爆"
        val old = table[r.name]
        if (old != null) conflicts.add("${r.name}：${old.label} → ${r.label}")
        table[r.name] = r
        return true to "已注册组合「${r.label}」（${r.name}），${r.layers.size} 层，深度 $d"
    }

    /**
     * 校验一个组合的 DSL。
     *
     * 关键：**每层各自校验，单层非法则整个拒绝**——
     * 装一半会得到一个"看起来能用但点了没反应"的界面。
     */
    fun validate(name: String, root: JSONObject): Pair<Boolean, String> {
        val r = table[name] ?: return false to "未注册组合：$name"
        val layersA = root.optJSONArray("layers")
            ?: return false to "组合 DSL 需要 layers 数组"
        if (layersA.length() != r.layers.size)
            return false to "layers 有 ${layersA.length()} 层，但组合「${r.label}」声明了 " +
                            "${r.layers.size} 层——对不上"
        r.layers.forEachIndexed { k, def ->
            val o = layersA.optJSONObject(k)
                ?: return false to "layers[$k] 不是对象"
            // 允许省略 type，用组合声明里的默认
            val comp = o.optString("type", def.component)
            if (comp != def.component)
                return false to "layers[$k].type=\"$comp\"，但这一层声明的是 " +
                                "\"${def.component}\"——层序不能换"
            val chk = if (table.containsKey(comp)) validate(comp, o).let { ZxDsl.Result(it.first, it.second) }
                      else ZxDsl.validateAny(comp, o)
            // 四、单层非法则整个拒绝
            if (!chk.ok)
                return false to "第 $k 层（$comp）校验不通过：${chk.reason}\n" +
                                "**整个组合拒绝渲染**——装一半会得到" +
                                "「看起来能用但点了没反应」的界面"
        }
        return true to ""
    }

    fun has(name: String) = table.containsKey(name)
    fun get(name: String) = table[name]
    fun all() = table.values.toList()

    /** 反编译：组合 → 意图（与 ⑯ 同源，不做黑箱） */
    fun intent(name: String): String? {
        val r = table[name] ?: return null
        val parts = r.layers.map { l ->
            val sub = table[l.component]?.let { intent(it.name) }
            sub ?: l.component.removePrefix("zx_")
        }
        return parts.joinToString("+")
    }

    /**
     * 展开：把组合 DSL 摊平成逐层的 (组件, DSL)。
     * 渲染器按这个顺序画就行——它不需要知道"组合"这回事。
     */
    fun expand(name: String, root: JSONObject): List<Pair<String, JSONObject>> {
        val r = table[name] ?: return emptyList()
        val layersA = root.optJSONArray("layers") ?: return emptyList()
        val out = ArrayList<Pair<String, JSONObject>>()
        r.layers.forEachIndexed { k, def ->
            val o = layersA.optJSONObject(k) ?: JSONObject(def.dsl.toString())
            val comp = o.optString("type", def.component)
            if (table.containsKey(comp)) out.addAll(expand(comp, o))
            else out.add(comp to o)
        }
        return out
    }

    /* ── 内置示例组合 ────────────────────────────────────────── */
    fun bootstrap() {
        reg(Recipe("zx_forecast_actual", "预测与实际",
            "预测卡上面叠一层实际值，闭环最直接的一种拼法",
            listOf(
                Layer("zx_forecast", JSONObject("""{"type":"zx_forecast"}"""), Pos.BASE),
                Layer("zx_feedback", JSONObject("""{"type":"zx_feedback"}"""), Pos.OVERLAY)
            )))
        reg(Recipe("zx_decision_review", "决策复盘",
            "决策矩阵下面接一条遗憾分析，做之前和做完之后一起看",
            listOf(
                Layer("zx_dmatrix", JSONObject("""{"type":"zx_dmatrix"}"""), Pos.BASE),
                Layer("zx_regret", JSONObject("""{"type":"zx_regret"}"""), Pos.FOOTER)
            )))
        reg(Recipe("zx_drift_alert", "漂移告警",
            "漂移卡下面接处置动作——只看 PSI 不知道该干嘛",
            listOf(
                Layer("zx_drift", JSONObject("""{"type":"zx_drift"}"""), Pos.BASE),
                Layer("zx_alert", JSONObject("""{"type":"zx_alert"}"""), Pos.FOOTER)
            )))
        reg(Recipe("zx_rollout_canary", "灰度与金丝雀",
            "放量进度 + 逐指标对比，决定能不能继续放",
            listOf(
                Layer("zx_rollout", JSONObject("""{"type":"zx_rollout"}"""), Pos.BASE),
                Layer("zx_canary", JSONObject("""{"type":"zx_canary"}"""), Pos.OVERLAY)
            )))
    }

    fun validate2(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("reg", "get", "list", "check", "expand", "intent", "demo", "report", "guide"))
            return false to "zx_compose 的 op=\"$op\" 只支持 reg / get / list / check / expand / intent / demo / report / guide"
        if (op == "reg") {
            if (o.optString("name").isBlank()) return false to "reg 需要 name"
            val layers = o.optJSONArray("layers")
                ?: return false to "reg 需要 layers 数组"
            if (layers.length() == 0) return false to "layers 为空"
            if (layers.length() > 4) return false to "layers 最多 4 层"
            var bases = 0
            val built = ArrayList<Layer>()
            for (k in 0 until layers.length()) {
                val l = layers.optJSONObject(k)
                    ?: return false to "layers[$k] 不是对象"
                val c = l.optString("c")
                if (c.isBlank()) return false to "layers[$k] 缺少 c（组件名）"
                if (!ZxRegistry.has(c) && !table.containsKey(c))
                    return false to "layers[$k].c=\"$c\" 不是已注册组件或组合——" +
                                    "**组合只能排列已有能力**"
                val at = when (l.optString("at", "overlay")) {
                    "base" -> Pos.BASE; "footer" -> Pos.FOOTER; else -> Pos.OVERLAY
                }
                if (at == Pos.BASE) bases++
                built.add(Layer(c, l.optJSONObject("dsl") ?: JSONObject(), at))
            }
            if (bases != 1)
                return false to "必须有且只有 1 个 base 层，实际 $bases 个"
        }
        if (op == "check" && o.optString("name").isBlank()) return false to "check 需要 name"
        return true to ""
    }

    fun from(o: JSONObject): Recipe {
        val layers = o.optJSONArray("layers") ?: return Recipe("", "", "", emptyList())
        val built = ArrayList<Layer>()
        for (k in 0 until layers.length()) {
            val l = layers.optJSONObject(k) ?: continue
            built.add(Layer(l.optString("c"),
                l.optJSONObject("dsl") ?: JSONObject(),
                when (l.optString("at", "overlay")) {
                    "base" -> Pos.BASE; "footer" -> Pos.FOOTER; else -> Pos.OVERLAY
                }))
        }
        return Recipe(o.optString("name"), o.optString("label", o.optString("name")),
            o.optString("desc", ""), built)
    }

    fun audit(): String = buildString {
        appendLine("组合自检")
        appendLine()
        appendLine("已注册组合 ${table.size} 个")
        appendLine()
        if (conflicts.isEmpty()) appendLine("无覆盖冲突。")
        else { appendLine("覆盖 ${conflicts.size} 处："); conflicts.forEach { appendLine("  · $it") } }
        appendLine()
        table.values.forEach { r ->
            appendLine("  ${r.name}　${r.label}　（深度 ${r.depth()}）")
            r.layers.forEach { l ->
                appendLine("      ${l.at.label}　${l.component}")
            }
            appendLine("      反编译意图：${intent(r.name)}")
        }
    }

    fun render(): String = buildString {
        appendLine("组件自组合协议（按语义把已有组件拼成新组件）")
        appendLine()
        appendLine("从第一批到第二十八批，每一次都是**手工加**。")
        appendLine("手工加有个绕不过去的天花板：**组合爆炸**。")
        appendLine()
        appendLine("  已有 zx_forecast（预测）")
        appendLine("  已有 zx_feedback（结果回收）→ 它其实是 forecast 的特例")
        appendLine()
        appendLine("继续这么加，第 500 个组件会是第 499 个的微调版，")
        appendLine("而模型要在 500 个名字里挑，**挑错的概率比挑对高**。")
        appendLine()
        appendLine("五条约束：")
        appendLine("  一、**只能引用已注册组件**，不能凭空造")
        appendLine("  二、**不能覆盖内置组件名**（否则历史卡片全变样）")
        appendLine("  三、**深度上限 3 层**（组合套组合会指数级膨胀）")
        appendLine("  四、**每层各自校验，单层非法整个拒绝**（不许装一半）")
        appendLine("  五、**拼出来的要能被反编译回意图**（不做黑箱）")
        appendLine()
        appendLine("与 ⑮ 热更新的区别：热更新的「组合」是把几张卡摆进容器")
        appendLine("（排版）；这里是同一气泡位里多层叠加（分层）。")
    }

    fun demo(): String = buildString {
        bootstrap()
        appendLine("══ 手工加的天花板 ══")
        appendLine()
        appendLine("  已有 zx_forecast（预测）")
        appendLine("  想要「预测 vs 实际」→ 于是加了 zx_feedback")
        appendLine("  可 zx_feedback 其实就是 forecast 的特例")
        appendLine()
        appendLine("══ 换成拼 ══")
        appendLine()
        val r = table["zx_forecast_actual"]!!
        appendLine("  compose(forecast_actual)")
        appendLine("    主轴　zx_forecast")
        appendLine("    叠加　zx_feedback")
        appendLine()
        appendLine("  拼出来的**不是一个新组件**，而是一份声明。")
        appendLine("  渲染时按层序画，校验时对每层各跑一遍。")
        appendLine()
        appendLine("══ 三条硬约束 ══")
        appendLine()
        val bad1 = reg(Recipe("zx_table", "覆盖内置", "", listOf(
            Layer("zx_table", JSONObject(), Pos.BASE))))
        appendLine("  ① 覆盖内置名 zx_table →")
        appendLine("     ${bad1.second.take(70)}…")
        appendLine()
        val deep = Recipe("zx_deep", "超深", "", listOf(
            Layer("zx_forecast_actual", JSONObject(), Pos.BASE)))
        reg(deep)
        val deeper = Recipe("zx_deeper", "更深", "", listOf(
            Layer("zx_deep", JSONObject(), Pos.BASE)))
        val r3 = reg(deeper)
        appendLine("  ② 组合套组合 → 深度 ${deeper.depth()}")
        appendLine("     ${if (!r3.first) r3.second.take(80) else "通过了"}")
        appendLine()
        val bad3 = reg(Recipe("zx_nobase", "没主轴", "", listOf(
            Layer("zx_forecast", JSONObject(), Pos.OVERLAY))))
        appendLine("  ③ 没有主轴 →")
        appendLine("     ${bad3.second.take(60)}…")
        appendLine()
        appendLine("══ 反编译：不做黑箱 ══")
        appendLine()
        appendLine("  intent(forecast_actual) = ${intent("zx_forecast_actual")}")
        appendLine("  intent(decision_review)  = ${intent("zx_decision_review")}")
        appendLine()
        appendLine("  模型能看懂组合是什么，不用试。")
    }

    fun report(): String = render()

    fun guide(): String = """
        zx_compose(op=reg|get|list|check|expand|intent|demo|report|guide, ...)

        按语义把已有组件拼成新组件，不再手工加。

        从第一批到第二十八批每一次都是**手工加**，
        但手工加有天花板：**组合爆炸**——
        第 500 个组件会是第 499 个的微调版，
        而模型要在 500 个名字里挑，**挑错的概率比挑对高**。

        拼的不是新组件，是"由什么拼成"的声明：
          compose(forecast_actual) = 主轴 zx_forecast + 叠加 zx_feedback
        渲染按层序画，校验对每层各跑一遍。

        五条约束：
          一、**只能引用已注册组件**，不能凭空造
          二、**不能覆盖内置组件名**（否则历史卡片全变样）
          三、**深度上限 3 层**（组合套组合会指数级膨胀）
          四、**每层各自校验，单层非法整个拒绝**（不许装一半）
          五、**拼出来的要能被反编译回意图**（不做黑箱）

        与 ⑮ 热更新的区别：
          热更新的「组合」= 把几张卡摆进容器（排版）
          这里的「组合」  = 同一气泡位多层叠加（分层）
    """.trimIndent()
}
