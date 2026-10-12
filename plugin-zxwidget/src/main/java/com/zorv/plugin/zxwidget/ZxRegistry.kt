package com.zorv.plugin.zxwidget

import androidx.compose.runtime.Composable
import org.json.JSONObject

/**
 * 组件自注册 —— 解决"每个组件要在 6 个地方登记"的漂移问题。
 *
 * 之前加一个组件要动这些地方：
 *   ZxDsl.ALL_COMPONENTS（总表）
 *   ZxDsl.validate 的 when 分支（校验分发）
 *   ZxNest.render 的 when 分支（嵌套渲染）
 *   ZxSurface.renderOne 的 when 分支（全屏渲染）
 *   ZxWidgetEntry.chatCard（宿主注册）
 *   ZxDsl*.spec / sample（规格与示例）
 *
 * 六处。漏一处不报错，只是"这个组件在某些路径下不可用"——
 * 这种 bug 最难查，因为大部分路径都是好的。
 *
 * 所以改成：组件自己声明一次，其余全部从注册表派生。
 *
 * 顺带把「能力协商」做进来：模型问"你能画什么"，
 * 宿主按当前屏宽和权限回一份可用清单，而不是给一张永远完整的表。
 * 360dp 的屏不该拿到"需要 480dp 的看板"这个选项。
 */
object ZxRegistry {

    enum class Category(val label: String) {
        DATA("数据可视化"),
        INPUT("输入"),
        GESTURE("手势"),
        LAYOUT("容器"),
        TEXT("文本"),
        STATUS("状态")
    }

    data class Meta(
        val name: String,
        val label: String,
        val category: Category,
        /** 一句话说明，给模型看的——不是给人看的 UI 文案 */
        val summary: String,
        val spec: String,
        val sample: String,
        val validate: (JSONObject) -> Pair<Boolean, String>,
        val render: @Composable (JSONObject, RenderContext) -> Unit,
        /** 低于此宽度不可用，discover 时会过滤掉 */
        val minWidthDp: Int = 0,
        /** 是否建议展开为全屏 */
        val prefersSurface: (JSONObject) -> Boolean = { false },
        /** 需要的卡片能力（配合 ZxSandbox） */
        val needsCaps: Set<String> = emptySet(),
        val tags: Set<String> = emptySet()
    )

    private val table = linkedMapOf<String, Meta>()

    /**
     * 重名登记的记录。
     *
     * 原来是 `if (containsKey) return` 静默丢弃——
     * 这正是 bug 的根源：后登记的组件看起来"注册成功"了
     * （代码写了、校验也接了），但实际渲染走的是先登记的那个。
     * 不崩、不报错，只是"我写的实现没生效"，这种最难查。
     *
     * 现在改成后者覆盖（演进是合理的：新一批往往更严格），
     * 但必须把冲突记下来，让 audit() 能报出来。
     */
    private val conflicts = mutableListOf<Pair<String, String>>()

    fun register(m: Meta) {
        val old = table[m.name]
        if (old != null) conflicts.add(m.name to old.label)
        table[m.name] = m
    }

    /** 重名冲突列表，audit 会带上 */
    fun conflicts(): List<Pair<String, String>> = conflicts.toList()

    fun all(): List<Meta> = table.values.toList()
    fun names(): List<String> = table.keys.toList()
    fun get(name: String): Meta? = table[name]

    /** 是否存在该组件（内置或已注册） */
    fun has(name: String): Boolean = table.containsKey(name)

    fun validate(name: String, o: JSONObject): Pair<Boolean, String> =
        table[name]?.validate?.invoke(o)
            ?: (false to "未注册组件：$name。用 zx_discover(op=list) 看当前可用清单。")

    @Composable
    fun render(name: String, o: JSONObject, ctx: RenderContext) {
        table[name]?.render?.invoke(o, ctx)
    }

    fun spec(name: String): String = table[name]?.spec ?: ""
    fun sample(name: String): String = table[name]?.sample ?: "{}"

    /* ══════════ 能力协商 ══════════ */

    data class Env(
        /** 当前可用宽度 */
        val widthDp: Int,
        /** 当前主题变体 */
        val variant: String,
        /** 卡片已声明的能力集合 */
        val caps: Set<String>,
        /** 是否离线 */
        val online: Boolean
    )

    /**
     * 按环境返回可用组件清单。
     *
     * 过滤掉装不下和没权限的——给模型一张它实际能用的表，
     * 比给一张完整表再让它试错便宜得多（每次试错都是一次推理）。
     */
    fun discover(env: Env, category: Category? = null, keyword: String = ""): List<Meta> =
        table.values.filter { m ->
            (category == null || m.category == category) &&
            (keyword.isBlank() || m.name.contains(keyword, true) ||
             m.label.contains(keyword, true) || m.tags.any { it.contains(keyword, true) }) &&
            m.minWidthDp <= env.widthDp &&
            (m.needsCaps.isEmpty() || m.needsCaps.all { it in env.caps })
        }

    /** 给模型的清单文本：一行一个，带规格 */
    fun catalog(env: Env, category: Category? = null, keyword: String = ""): String {
        val list = discover(env, category, keyword)
        return buildString {
            appendLine("当前环境：${env.widthDp}dp · ${env.variant} · ${if (env.online) "在线" else "离线"}")
            appendLine("可用组件 ${list.size} 个（已按环境过滤）：")
            list.groupBy { it.category }.forEach { (cat, items) ->
                appendLine()
                appendLine("【${cat.label}】")
                items.forEach { m ->
                    appendLine("  ${m.name}")
                    appendLine("      ${m.summary}")
                    appendLine("      ${m.spec}")
                }
            }
            val hidden = table.size - list.size
            if (hidden > 0) appendLine()
            if (hidden > 0) appendLine("另有 $hidden 个因屏宽或能力限制不可用。")
        }
    }

    /* ══════════ 能力协商 2.0：按剩余上下文定价 ══════════ */

    /**
     * 一个组件在"上下文预算"上的成本。
     *
     * 为什么加入这个维度：1.0 版只按屏宽和权限过滤，
     * 但那两个都是**能不能画**的问题，
     * 没回答**值不值得画**。
     *
     * 一个 100 行的 zx_table 在 360dp 屏上完全装得下、
     * 也不需要任何权限——但它要吃掉 2000 token。
     * 如果这一轮只剩 1500 token 预算，
     * 告诉模型"它可用"就是把它往沟里带。
     *
     * 所以成本不按"组件类型"估，
     * 而按**这份 DSL 实际有多长**估——
     * 同一个 zx_table，3 行和 100 行是两个价。
     */
    data class Budget(
        /** 剩余可用 token */
        val left: Int,
        /** 本次下发最多愿意花的 token */
        val max: Int = 4000
    ) {
        fun enough(cost: Int) = cost <= minOf(left, max)
    }

    /** 估算一份 DSL 的 token 成本：中文按 1 字 1.5 token，其余按 4 字符 1 token */
    fun costOf(dsl: String): Int {
        val cn = dsl.count { it.code in 0x4E00..0x9FFF }
        val other = dsl.length - cn
        return (cn * 1.5 + other / 4.0).toInt().coerceAtLeast(1)
    }

    /** 组件本身的固定开销（注册名 + 结构骨架），不含数据 */
    private val FIXED = mapOf(
        "zx_rich" to 90, "zx_table" to 60, "zx_spreadsheet" to 110,
        "zx_orgchart" to 100, "zx_sankey" to 80, "zx_treeview" to 90,
        "zx_chart" to 45, "zx_empty" to 18, "zx_divider" to 14,
        "zx_callout" to 30, "zx_banner" to 35
    )

    /**
     * 按预算过滤：只回"画得起"的组件。
     *
     * 关键点：**超预算要给出替代建议**，
     * 而不是只说"不可用"。
     * 模型拿到"zx_table 要 2100 token，超出预算；\n* 建议改 zx_chart（约 320）或分页 zx_table（rows ≤ 20）"
     * 它下一轮就能自己做对；
     * 拿到一句"不可用"它只能瞎猜。
     */
    fun affordable(env: Env, budget: Budget, dslSample: String = ""): List<Meta> =
        discover(env).filter { m ->
            val c = (FIXED[m.name] ?: 40) +
                    (if (dslSample.isNotBlank()) costOf(dslSample) else 0)
            budget.enough(c)
        }

    /** 预算不足时的降级建议 */
    fun suggest(env: Env, budget: Budget, want: String, dsl: String): String {
        val m = table[want] ?: return "未注册组件：$want"
        val cost = (FIXED[want] ?: 40) + (if (dsl.isNotBlank()) costOf(dsl) else 0)
        if (budget.enough(cost)) return "$want 约 $cost token，预算内可用。"
        val alts = affordable(env, Budget(budget.left, budget.max))
            .filter { it.category == m.category || it.name in CHEAP_FALLBACK }
            .sortedBy { FIXED[it.name] ?: 40 }
            .take(3)
        return buildString {
            appendLine("$want 约 $cost token，超出预算（剩 ${budget.left}）。")
            if (alts.isEmpty()) {
                appendLine("没有更便宜的替代——建议只输出纯文本，或用 zx_skeleton 占位。")
            } else {
                appendLine("建议改：")
                alts.forEach { a ->
                    appendLine("  ${a.name}（约 ${FIXED[a.name] ?: 40} token）— ${a.summary}")
                }
                if (want == "zx_table")
                    appendLine("  或分页 zx_table：rows ≤ 20，分多次下发。")
            }
        }
    }

    private val CHEAP_FALLBACK = setOf(
        "zx_chart", "zx_terminal", "zx_callout", "zx_stat", "zx_empty",
        "zx_divider", "zx_flipnum", "zx_gauge", "zx_sparkgrid"
    )

    /** 单组件详情 */
    fun detail(name: String): String {
        val m = table[name] ?: return "未注册组件：$name"
        return buildString {
            appendLine("${m.name} · ${m.label}（${m.category.label}）")
            appendLine("  ${m.summary}")
            appendLine("  规格：${m.spec}")
            if (m.minWidthDp > 0) appendLine("  最小宽度：${m.minWidthDp}dp")
            if (m.needsCaps.isNotEmpty()) appendLine("  需要能力：${m.needsCaps.joinToString()}")
            appendLine()
            appendLine("示例：")
            appendLine(m.sample)
        }
    }

    /** 自检：漏登记 + 重名覆盖。空列表 = 健康 */
    fun audit(): List<String> {
        val out = mutableListOf<String>()
        conflicts.forEach { (name, oldLabel) ->
            out.add("$name 被重复登记（覆盖旧「$oldLabel」）— 确认是有意演进，还是造重了")
        }
        table.values.forEach { m ->
            when {
                m.spec.isBlank() -> out.add("${m.name} 缺 spec")
                m.sample.isBlank() -> out.add("${m.name} 缺 sample")
                m.summary.isBlank() -> out.add("${m.name} 缺 summary")
            }
        }
        return out
    }

    /**
     * 从已有的分批定义里批量登记。
     * 分批文件（ZxDsl2..6）保留各自的分批校验，这里只做汇总——
     * 不把五批的规则全搬进一个巨型 when，那样文件会有几千行。
     */
    internal fun bootstrap() {
        // 由 ZxRegistryInit 调用，见该文件
    }
}
