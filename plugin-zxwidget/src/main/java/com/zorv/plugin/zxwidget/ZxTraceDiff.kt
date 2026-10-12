package com.zorv.plugin.zxwidget

/**
 * 交互轨迹差异对比 —— 两条轨迹并排，看在哪一步分叉。
 *
 * ㉟（交互导出）能录"用户按了什么"，解决了
 * "到底点了什么"的问题。但它只能回答**单条**轨迹：
 *
 *   这次的轨迹是：点 A → 改 B → 提交
 *
 * 遇到"复现不了"的 bug 时，真正有用的不是单条轨迹，
 * 而是**两条轨迹的对比**：
 *
 *   上次能跑通：  点 A → 改 B → 改 C → 提交
 *   这次跑不通：  点 A → 改 B →      提交
 *
 *   差在第 3 步 —— 这次没改 C。
 *
 * 单看这次的轨迹，只能看到"做了三步然后失败了"，
 * 看不出**少了什么**。而"少了什么"才是原因。
 *
 * 所以要做的是对齐两条轨迹并标出分叉点。
 *
 * 四条约束：
 *
 *   一、**不能简单逐位比对**。
 *       两条轨迹长度不同、可能中间插了无关步骤，
 *       逐位比对会把第 3 步之后的每一步都判成"不同"，
 *       真正的差异（一个缺失的步骤）反而被淹没了。
 *       所以要用**最长公共子序列**对齐，
 *       缺失的才显示为"缺一步"，而不是"后面全变了"。
 *
 *   二、**只比"有因果关系的步骤"**。
 *       纯粹的展开/折叠、选中查看这类不改变状态的步骤
 *       也算差异的话，十条里有九条是噪音。
 *       所以只比会写值的步骤（set / toggle / submit / delete）。
 *
 *   三、**差异要能定位到具体字段**。
 *       说"第 3 步不同"不够，要说
 *       "第 3 步：base 上改了 rows[3][1]，target 上没有这一步"。
 *
 *   四、**只读**。
 *       对比不修改任何一条轨迹——
 *       改了就不是"对比"了，下次再对比基准已经脏了。
 */
object ZxTraceDiff {

    /** 一条可比对的步骤（只保留会写值的） */
    data class Step(
        val act: String,
        val path: String,
        val to: String
    ) {
        fun key() = "$act|$path|$to"
        fun line() = "$act ${path.ifBlank { "整卡" }} → ${to.ifBlank { "—" }}"
    }

    /** 比对结果中的一行 */
    data class Row(
        /** same / added（target 多）/ missing（base 有 target 没有） */
        val kind: String,
        val base: Step? = null,
        val target: Step? = null
    ) {
        fun text(): String = when (kind) {
            "same"    -> "　" + (base?.line() ?: "")
            "missing" -> "－ " + (base?.line() ?: "") + "　（这条轨迹上没有这一步）"
            else      -> "＋ " + (target?.line() ?: "") + "　（多出来的一步）"
        }
    }

    /** 只保留会写值的步骤 */
    private val CAUSAL = setOf("set", "toggle", "submit", "delete", "move")

    fun causal(steps: List<ZxTrace.Step>): List<Step> =
        steps.filter { it.act in CAUSAL }.map { Step(it.act, it.path, it.to) }

    /**
     * LCS 对齐。
     *
     * 不用逐位比对：两条轨迹中间插一步之后，
     * 逐位比对会把后面每一步都判成"不同"，
     * 真正的差异（一个缺失的步骤）反而被淹没。
     */
    fun diff(base: List<Step>, target: List<Step>): List<Row> {
        val n = base.size; val m = target.size
        if (n == 0 && m == 0) return emptyList()
        // dp[i][j] = base 前 i 个与 target 前 j 个的 LCS 长度
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) for (j in 1..m)
            dp[i][j] = if (base[i - 1].key() == target[j - 1].key())
                dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])

        val out = ArrayList<Row>()
        var i = n; var j = m
        val tmp = ArrayList<Row>()
        while (i > 0 && j > 0) {
            when {
                base[i - 1].key() == target[j - 1].key() -> {
                    tmp.add(Row("same", base[i - 1], target[j - 1])); i--; j--
                }
                dp[i - 1][j] >= dp[i][j - 1] -> { tmp.add(Row("missing", base[i - 1], null)); i-- }
                else -> { tmp.add(Row("added", null, target[j - 1])); j-- }
            }
        }
        while (i > 0) { tmp.add(Row("missing", base[i - 1], null)); i-- }
        while (j > 0) { tmp.add(Row("added", null, target[j - 1])); j-- }
        out.addAll(tmp.reversed())
        return out
    }

    /** 第一个分叉点 —— 报错时最有用的是"从哪一步开始不一样" */
    fun firstDivergence(rows: List<Row>): Int = rows.indexOfFirst { it.kind != "same" }

    fun render(rows: List<Row>, baseName: String = "基准", targetName: String = "对比"): String =
        buildString {
            if (rows.isEmpty()) { appendLine("两条轨迹都没有会写值的步骤"); return@buildString }
            appendLine("$baseName ←→ $targetName")
            appendLine()
            rows.forEachIndexed { k, r -> appendLine("${(k + 1).toString().padStart(2)}. ${r.text()}") }
            val miss = rows.count { it.kind == "missing" }
            val add = rows.count { it.kind == "added" }
            appendLine()
            appendLine("相同 ${rows.count { it.kind == "same" }} 步，缺失 $miss 步，多出 $add 步")
            val fd = firstDivergence(rows)
            if (fd >= 0) appendLine("第一次分叉在第 ${fd + 1} 步")
            else appendLine("两条轨迹完全一致")
        }

    /**
     * 从两条轨迹里摘出"缺少的步骤"——这才是原因。
     *
     * 单看一条轨迹只能看到"做了三步然后失败了"，
     * 看不出**少了什么**。而"少了什么"才是原因。
     */
    fun missing(rows: List<Row>): List<Step> = rows.mapNotNull { if (it.kind == "missing") it.base else null }

    fun demo(): String {
        val base = listOf(
            Step("set", "rows[2][1]", "2400"),
            Step("set", "rows[3][1]", "90"),
            Step("toggle", "opt.fast", "on"),
            Step("submit", "", "")
        )
        val target = listOf(
            Step("set", "rows[2][1]", "2400"),
            Step("set", "rows[3][1]", "90"),
            Step("submit", "", "")
        )
        val rows = diff(base, target)
        return buildString {
            appendLine("示例：上次能跑通 vs 这次跑不通")
            appendLine()
            appendLine(render(rows, "上次", "这次").trimEnd())
            appendLine()
            appendLine("缺少的步骤（这才是原因）：")
            missing(rows).forEach { appendLine("  · ${it.line()}") }
        }
    }

    fun report(): String = buildString {
        appendLine("交互轨迹差异对比（两条轨迹并排，看在哪一步分叉）")
        appendLine()
        appendLine("㉟ 能录「用户按了什么」，解决了「到底点了什么」。")
        appendLine("但它只能回答**单条**轨迹：")
        appendLine()
        appendLine("  这次的轨迹是：点 A → 改 B → 提交")
        appendLine()
        appendLine("遇到「复现不了」的 bug 时，真正有用的不是单条轨迹，")
        appendLine("而是**两条轨迹的对比**：")
        appendLine()
        appendLine("  上次能跑通：  点 A → 改 B → 改 C → 提交")
        appendLine("  这次跑不通：  点 A → 改 B →      提交")
        appendLine()
        appendLine("  差在第 3 步 —— 这次没改 C。")
        appendLine()
        appendLine("单看这次的轨迹，只能看到「做了三步然后失败了」，")
        appendLine("看不出**少了什么**。" +
            "而「少了什么」才是原因。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、不能简单逐位比对（用 LCS 对齐，否则后面每一步都判成不同）")
        appendLine("  二、只比有因果关系的步骤（展开/选中这类是噪音）")
        appendLine("  三、差异要能定位到具体字段")
        appendLine("  四、只读（改了就不是对比了，下次基准已经脏了）")
    }

    fun guide(): String = """
        zx_tracediff(op=diff|missing|demo|report|guide)

        两条轨迹并排，看在哪一步分叉。

        ㉟ 能录「用户按了什么」，但只能回答单条轨迹。
        遇到「复现不了」的 bug 时，真正有用的是对比：

          上次能跑通：  点 A → 改 B → 改 C → 提交
          这次跑不通：  点 A → 改 B →      提交

          差在第 3 步 —— 这次没改 C

        单看这次只能看到「做了三步然后失败了」，
        看不出**少了什么**。而「少了什么」才是原因。

        四条约束：
          一、用 LCS 对齐，不能逐位比对
             （逐位会把后面每一步都判成不同，缺失的那一步反被淹没）
          二、只比会写值的步骤（展开/选中是噪音）
          三、差异要定位到具体字段
          四、只读
    """.trimIndent()
}
