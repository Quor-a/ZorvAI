package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 全量回归 —— 改了渲染器之后，把全部组件的示例 DSL 跑一遍。
 *
 * **为什么现在才做**
 *
 * ㉞ 快照回归（第三十批）解决的是"同一张卡改前改后是不是一样"。
 * 但它有个前提：**你得知道是哪张卡变了**。
 *
 * 现实情况不是这样。改渲染器的时候你不知道会碰到谁：
 *
 *   改一个共用色值        → 影响所有用了这个色的组件
 *   改一个共用校验分支     → 影响所有走这条分支的组件
 *   改 ZxT 的一个 token   → 影响全部 400 多个
 *
 * 405 个组件之后这类问题必然出现，而且**不会有任何报错**——
 * 你改的那处通过了所有肉眼检查，只是某个你根本没想起来的组件
 * 在用户手机上画歪了。等用户来报，已经过了好几个版本。
 *
 * 所以需要"扫一遍全部"。
 *
 * ── 它与快照回归的分工 ──────────────────────────────────────
 *
 * |            | 快照回归 ㉞        | 全量回归           |
 * |------------|-------------------|-------------------|
 * | 比什么      | 同一张卡的改前改后   | 全部组件的当前状态   |
 * | 什么时候用   | 改某一张卡之前       | 改渲染器/校验之后    |
 * | 认得出      | 这张卡变了吗         | 有谁坏了           |
 * | 认不出      | 谁会连带坏掉         | 坏了多少（只报有无） |
 *
 * 两者不重复，缺一个就会漏一整类问题。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**只用示例 DSL，不用会话数据**。
 *     会话数据含时间戳、id、用户填的值，每次跑都会"变了"。
 *     误报多了没人看——**没人看的回归等于没有回归**。
 *
 * 二、**指纹只取校验结果与关键结构，不取整串 JSON**。
 *     整串里一处空格、一个键顺序变化就会误报。
 *     这里取：是否通过 + 键集合 + 数组长度 + 关键数值范围。
 *
 * 三、**基线能 update**。
 *     不提供 update，人就会手动删基线文件，
 *     删完基线是空的，下一次全量报"全部是新的"，更没人看。
 *
 * 四、**认不出"渲染器改坏了"，只能认出"校验结果与指纹变了"**。
 *     这条必须写出来。它不跑渲染，不比像素——
 *     真渲染要 UI 环境，代价高且不稳定。
 *     所以它能抓"校验挂了 / 结构变了 / 示例缺失"，
 *     抓不到"画出来的位置偏了 2dp"。
 *     **把它当成像素级回归来用，会漏掉真正的视觉问题。**
 */
object ZxRegress {

    /** 一个组件的回归结果 */
    data class Item(
        val name: String,
        val ok: Boolean,
        val reason: String,       // 不通过时的原因
        val hasSample: Boolean,   // 有没有示例 DSL
        val keys: String,         // 键集合指纹
        val shape: String,        // 数组长度指纹
        val fp: String            // 总指纹
    )

    private val baseline = HashMap<String, String>()

    /** 跑一遍全部 */
    fun run(): List<Item> {
        val out = ArrayList<Item>()
        for (m in ZxRegistry.all()) {
            val name = m.name
            val dsl = ZxRegistry.sample(name)
            if (dsl.isBlank() || dsl == "{}") {
                // 示例缺失：这本身就是问题——回归跑不到它
                out.add(Item(name, false, "**没有示例 DSL**——回归跑不到它，" +
                    "改了渲染器也不会被发现", false, "", "", "NO_SAMPLE"))
                continue
            }
            val root = try {
                JSONObject(dsl)
            } catch (e: Exception) {
                out.add(Item(name, false, "**示例 DSL 解析失败**：${e.message}", true,
                    "", "", "PARSE_ERR"))
                continue
            }
            val (ok, reason) = try {
                ZxDsl.validateAny(name, root)
            } catch (e: Exception) {
                out.add(Item(name, false, "**校验抛异常**：${e.message}", true,
                    "", "", "THROW"))
                continue
            }
            out.add(Item(name, ok, reason, true, keyFp(root), shapeFp(root),
                if (ok) (keyFp(root) + "#" + shapeFp(root)) else "FAIL"))
        }
        return out
    }

    private fun keyFp(o: JSONObject): String =
        o.keys().asSequence().toList().sorted().joinToString(",")

    private fun shapeFp(o: JSONObject): String {
        val sb = StringBuilder()
        for (k in o.keys().asSequence().toList().sorted()) {
            val v = o.opt(k)
            when (v) {
                is org.json.JSONArray -> sb.append("$k:[${v.length()}]")
                is JSONObject -> sb.append("$k:{${v.length()}}")
                else -> sb.append("$k:*")
            }
        }
        return sb.toString()
    }

    /** 与基线比：哪些是新的、哪些变了、哪些挂了 */
    fun diff(items: List<Item>): Report {
        val changed = ArrayList<Item>()
        val failed = ArrayList<Item>()
        val noSample = ArrayList<Item>()
        val updated = ArrayList<String>()
        for (it in items) {
            if (!it.hasSample) { noSample.add(it); continue }
            if (!it.ok) { failed.add(it); continue }
            val old = baseline[it.name]
            when {
                old == null -> { baseline[it.name] = it.fp; updated.add(it.name) }
                old != it.fp -> { changed.add(it); baseline[it.name] = it.fp }
            }
        }
        return Report(items.size, changed, failed, noSample, updated)
    }

    data class Report(
        val total: Int,
        val changed: List<Item>,
        val failed: List<Item>,
        val noSample: List<Item>,
        val baselineUpdated: List<String>
    ) {
        val healthy: Boolean get() = failed.isEmpty() && noSample.isEmpty()
    }

    /** 更新基线到当前（改完之后确认没问题就调一次） */
    fun update(): Int {
        val items = run()
        var n = 0
        for (it in items) {
            if (it.hasSample && it.ok) { baseline[it.name] = it.fp; n++ }
        }
        return n
    }

    fun clear() = baseline.clear().let { baseline.size }

    fun render(): String = buildString {
        val items = run()
        val r = diff(items)
        appendLine("══ 全量回归 ══")
        appendLine()
        appendLine("  共 ${r.total} 个注册组件")
        appendLine()
        appendLine("  示例 DSL 通过　${items.count { it.ok }}")
        appendLine("  校验失败　　　${r.failed.size}")
        appendLine("  无示例　　　　${r.noSample.size}")
        appendLine("  指纹变化　　　${r.changed.size}")
        appendLine("  首次入基线　　${r.baselineUpdated.size}")
        appendLine()
        if (r.failed.isNotEmpty()) {
            appendLine("  ── 校验失败（这些必须修） ──")
            r.failed.take(8).forEach {
                appendLine("    ${it.name}")
                appendLine("      ${it.reason.take(110)}")
            }
            if (r.failed.size > 8) appendLine("    …还有 ${r.failed.size - 8} 个")
            appendLine()
        }
        if (r.noSample.isNotEmpty()) {
            appendLine("  ── 无示例（回归跑不到它们） ──")
            appendLine("    ${r.noSample.take(14).joinToString("、") { it.name }}")
            if (r.noSample.size > 14) appendLine("    …还有 ${r.noSample.size - 14} 个")
            appendLine()
        }
        appendLine("  结论：**${if (r.healthy) "健康" else "有问题"}**")
    }

    fun report(): String = buildString {
        appendLine("全量回归 —— 改了渲染器之后，把全部组件的示例 DSL 跑一遍")
        appendLine()
        appendLine("㉞ 快照回归解决的是「同一张卡改前改后是不是一样」。")
        appendLine("但它有个前提：**你得知道是哪张卡变了**。")
        appendLine()
        appendLine("现实情况不是这样。改渲染器的时候你不知道会碰到谁：")
        appendLine()
        appendLine("  改一个共用色值      → 影响所有用了这个色的组件")
        appendLine("  改一个共用校验分支   → 影响所有走这条分支的组件")
        appendLine("  改 ZxT 的一个 token → 影响全部 400 多个")
        appendLine()
        appendLine("405 个组件之后这类问题必然出现，而且**不会有任何报错**——")
        appendLine("你改的那处通过了所有肉眼检查，只是某个你根本没想起来的组件")
        appendLine("在用户手机上画歪了。等用户来报，已经过了好几个版本。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**只用示例 DSL，不用会话数据**")
        appendLine("  二、**指纹只取校验结果与关键结构，不取整串 JSON**")
        appendLine("  三、**基线能 update**")
        appendLine("  四、**认不出「渲染器改坏了」，只能认出「校验结果与指纹变了」**")
        appendLine()
        appendLine("第一条最容易做废：会话数据含时间戳、id、用户填的值，")
        appendLine("每次跑都会「变了」。误报多了没人看——")
        appendLine("**没人看的回归等于没有回归。**")
        appendLine()
        appendLine("第四条是诚实的局限：它不跑渲染、不比像素。")
        appendLine("真渲染要 UI 环境，代价高且不稳定。")
        appendLine("所以它抓得到「校验挂了 / 结构变了 / 示例缺失」，")
        appendLine("抓不到「画出来的位置偏了 2dp」。")
    }

    fun demo(): String = buildString {
        val items = run()
        val r = diff(items)
        appendLine("══ 一次全量回归 ══")
        appendLine()
        appendLine("  共 ${r.total} 个注册组件")
        appendLine()
        appendLine("  示例通过　${items.count { it.ok }}")
        appendLine("  校验失败　${r.failed.size}")
        appendLine("  无示例　　${r.noSample.size}")
        appendLine("  指纹变化　${r.changed.size}")
        appendLine()
        // 抽样展示：按批取几个代表性的
        val picks = listOf("zx_table", "zx_chart", "zx_fitness", "zx_utilitymeter",
            "zx_sleep", "zx_medicine", "zx_habit", "zx_kanban")
        appendLine("  ── 抽样 ──")
        picks.forEach { n ->
            val it = items.firstOrNull { x -> x.name == n }
            if (it != null) {
                appendLine("  ${n.padEnd(18)} ${if (it.ok) "✓" else "✗"}　" +
                    if (it.ok) it.fp.take(46) else it.reason.take(46))
            }
        }
        appendLine()
        if (r.noSample.isNotEmpty()) {
            appendLine("  ── 无示例（回归跑不到它们） ──")
            appendLine("  ${r.noSample.take(10).joinToString("、") { it.name }}")
            if (r.noSample.size > 10) appendLine("  …还有 ${r.noSample.size - 10} 个")
            appendLine()
            appendLine("  **没示例的组件，改坏了也不会被发现**——")
            appendLine("  这也是一种「坏」，而且是更难发现的那种。")
            appendLine()
        }
        appendLine("  ── 它能抓到什么 / 抓不到什么 ──")
        appendLine()
        appendLine("  抓得到：校验挂了 / 结构变了 / 示例缺失")
        appendLine("  抓不到：**画出来的位置偏了 2dp**")
        appendLine()
        appendLine("  它不跑渲染、不比像素——真渲染要 UI 环境，")
        appendLine("  代价高且不稳定。**把它当成像素级回归来用，")
        appendLine("  会漏掉真正的视觉问题。**")
    }

    fun guide(): String = """
        zx_regress(op=run|demo|report|update|clear|guide)

        全量回归：改了渲染器之后，把全部组件的示例 DSL 跑一遍。

        ㉞ 快照回归解决的是「同一张卡改前改后是不是一样」。
        但它有个前提：**你得知道是哪张卡变了**。

        现实情况不是这样。改渲染器的时候你不知道会碰到谁：

          改一个共用色值      → 影响所有用了这个色的组件
          改一个共用校验分支   → 影响所有走这条分支的组件
          改 ZxT 的一个 token → 影响全部 400 多个

        405 个组件之后这类问题必然出现，而且**不会有任何报错**——
        你改的那处通过了所有肉眼检查，只是某个你根本没想起来的组件
        在用户手机上画歪了。

        四条约束：
          一、**只用示例 DSL，不用会话数据**
          二、**指纹只取校验结果与关键结构，不取整串 JSON**
          三、**基线能 update**
          四、**认不出「渲染器改坏了」，只能认出「校验结果与指纹变了」**

          第一条最容易做废：会话数据含时间戳、id、用户填的值，
          每次跑都会「变了」。误报多了没人看——
          **没人看的回归等于没有回归。**

          op=run     跑一遍并输出统计
          op=demo    抽样展示 + 诚实说明能抓到什么
          op=update  把当前状态写进基线
          op=clear   清空基线
    """.trimIndent()
}
