package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs

/**
 * 情景快照与复现 —— 让"推演"这件本来不可复核的事变得可复核。
 *
 * **为什么需要**
 *
 * 第三十一批做了 12 个模拟与推演组件，然后立刻撞上一个问题：
 *
 *   用户对着一张蒙特卡洛卡说"再跑一次看看"，结果不一样。
 *   他把两个不同的结果截图给我，问我哪个是对的。
 *
 * 两个都对——因为 seed 没存，每次都是新的随机序列。
 *
 * 这件事的严重性超过"结果不一致"本身：
 *
 *   **推演出来的结论本来就难验证**（它描述的是还没发生的事）。
 *   如果连"同样的输入能不能得到同样的输出"都做不到，
 *   那推演就彻底失去可信度了——它连可重复都谈不上，
 *   更不用说正确。
 *
 * 所以要有情景快照。存四样：
 *
 *   一、**参数**（推演的输入）
 *   二、**seed**（随机种子）
 *   三、**结果摘要**（不用存全部样本，存分位数就够）
 *   四、**时间**（什么时候推演的）
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**随机源必须自己实现，不用 java.util.Random**。
 *     `Random(seed)` 的序列在不同 JDK 版本/实现上可能不同。
 *     存了 seed 也复现不了，那存 seed 就是自欺欺人。
 *     这里统一用 ZxRender31.Lcg（线性同余，序列完全由公式决定）。
 *
 * 二、**不存全部样本，只存分位数**。
 *     2000 个 double 存进快照，几十条快照就把内存吃掉了。
 *     而复核需要的是"结果对不对"，分位数足够回答这个。
 *
 * 三、**复现失败要能说清差在哪**。
 *     只说"不一致"没用——用户需要知道是参数变了还是 seed 变了
 *     还是组件实现变了（第三种最麻烦，说明有回归）。
 *
 * 四、**不自动覆盖同名情景**。
 *     同名的两次推演，后一次会悄悄盖掉前一次，
 *     用户以为自己对比的是"改前改后"，其实改前的已经没了。
 */
object ZxScen {

    data class Snapshot(
        val id: String,
        val name: String,
        val comp: String,          // zx_montecarlo / zx_scenario / ...
        val params: String,        // 参数摘要（不含 seed）
        val seed: Long,
        /** 结果分位数：P05 / P50 / P95 / 达标率 */
        val q: Map<String, Double>,
        val ts: Long = System.currentTimeMillis()
    )

    private val snaps = LinkedHashMap<String, Snapshot>()
    private var seq = 0

    /** 参数指纹：把影响结果的字段抽出来，不含 seed / 时间 */
    fun fingerprint(comp: String, o: JSONObject): String {
        val keys = when (comp) {
            "zx_montecarlo" -> listOf("n", "mean", "sd", "target")
            "zx_scenario"   -> listOf("scenarios")
            "zx_systemdyn"  -> listOf("s0", "in", "out", "steps", "cap")
            "zx_markov"     -> listOf("states", "p", "at")
            "zx_breakeven"  -> listOf("fixed", "price", "vc", "maxq", "q")
            "zx_whatif"     -> listOf("nodes")
            else -> o.keys().asSequence().toList()
        }
        return comp + "#" + keys.joinToString("|") { "$it=${o.opt(it)}" }
    }

    /**
     * 存快照。同名不覆盖——要显式传 overwrite 或者换名字。
     */
    fun save(name: String, comp: String, o: JSONObject, q: Map<String, Double>): Pair<Snapshot?, String> {
        val fp = fingerprint(comp, o)
        val old = snaps.values.firstOrNull { it.name == name }
        if (old != null)
            return null to "已存在同名情景「$name」（${ZxUtil.ago(old.ts)}），" +
                "**不自动覆盖**——同名的两次推演被悄悄盖掉，" +
                "用户以为对比的是改前改后，其实改前的已经没了。换个名字或先删掉"
        val s = Snapshot("s${++seq}", name, comp, fp, o.optLong("seed", -1L), q)
        snaps[s.id] = s
        return s to "已存情景「$name」：${comp} · seed=${if (s.seed < 0) "（无）" else s.seed}"
    }

    fun force(name: String, comp: String, o: JSONObject, q: Map<String, Double>): Snapshot {
        snaps.values.firstOrNull { it.name == name }?.let { snaps.remove(it.id) }
        return save(name, comp, o, q).first!!
    }

    fun del(idOrName: String): Boolean {
        val k = snaps.keys.firstOrNull { it == idOrName }
            ?: snaps.values.firstOrNull { it.name == idOrName }?.id ?: return false
        return snaps.remove(k) != null
    }

    fun get(idOrName: String): Snapshot? =
        snaps[idOrName] ?: snaps.values.firstOrNull { it.name == idOrName }

    fun all(): List<Snapshot> = snaps.values.toList()

    /**
     * 复现：用快照的 seed 重跑，比对分位数。
     *
     * 关键是不只说"一致/不一致"，要说清**差在哪**——
     * 参数变了、seed 变了、还是组件实现变了（第三种说明有回归）。
     */
    fun replay(idOrName: String, o: JSONObject, q: Map<String, Double>): String {
        val s = get(idOrName) ?: return "找不到情景「$idOrName」"
        val sb = StringBuilder()
        sb.appendLine("复现情景「${s.name}」（${s.comp}）")
        sb.appendLine()

        val fp = fingerprint(s.comp, o)
        if (fp != s.params) {
            sb.appendLine("参数：**变了**")
            sb.appendLine("  当时：${if (s.params.length > 90) s.params.take(90) + "…" else s.params}")
            sb.appendLine("  现在：${if (fp.length > 90) fp.take(90) + "…" else fp}")
            sb.appendLine("  → 参数不同，结果不同是正常的。改回去再比。")
            return sb.toString()
        }
        sb.appendLine("参数：一致")

        val seedNow = o.optLong("seed", -1L)
        if (s.seed < 0) {
            sb.appendLine("seed：当时**没存种子** → 无法复现（这正是要存 seed 的原因）")
            return sb.toString()
        }
        if (seedNow != s.seed) {
            sb.appendLine("seed：**变了**（当时 ${s.seed} / 现在 $seedNow）")
            sb.appendLine("  → 用 ${s.seed} 重跑即可复现")
            return sb.toString()
        }
        sb.appendLine("seed：一致（${s.seed}）")

        // 参数和 seed 都对，比结果
        var diff = 0
        val ks = s.q.keys + q.keys
        ks.distinct().forEach { k ->
            val a = s.q[k]; val b = q[k]
            when {
                a == null -> { sb.appendLine("  $k：当时没记，现在 ${"%.3f".format(b!!)}"); diff++ }
                b == null -> { sb.appendLine("  $k：现在没有，当时 ${"%.3f".format(a)}"); diff++ }
                abs(a - b) > 0.01 -> {
                    sb.appendLine("  $k：**${"%.3f".format(a)} → ${"%.3f".format(b)}**")
                    diff++
                }
                else -> sb.appendLine("  $k：${"%.3f".format(a)} ✓")
            }
        }
        sb.appendLine()
        if (diff == 0) sb.appendLine("结论：**完全复现**。推演是可重复的。")
        else sb.appendLine("结论：**$diff 项对不上**。参数和 seed 都一样却结果不同，" +
            "那说明**组件实现变了**——这是回归，不是随机性。")
        return sb.toString()
    }

    /**
     * 对比两条情景。和 replay 的区别：
     * replay 是"同一套参数能不能重跑出同样结果"（查可重复性），
     * compare 是"两套不同参数的结果差多少"（查参数影响）。
     */
    fun compare(aId: String, bId: String): String {
        val a = get(aId) ?: return "找不到情景「$aId」"
        val b = get(bId) ?: return "找不到情景「$bId」"
        val sb = StringBuilder()
        sb.appendLine("对比「${a.name}」 vs 「${b.name}」")
        sb.appendLine()
        if (a.comp != b.comp) {
            sb.appendLine("组件不同（${a.comp} / ${b.comp}）——**不同组件的指标没有可比性**")
            return sb.toString()
        }
        sb.appendLine("参数：${if (a.params == b.params) "一致" else "**不同**"}")
        if (a.params != b.params) {
            sb.appendLine("  A：${if (a.params.length > 80) a.params.take(80) + "…" else a.params}")
            sb.appendLine("  B：${if (b.params.length > 80) b.params.take(80) + "…" else b.params}")
        }
        sb.appendLine("seed：${a.seed} / ${b.seed}")
        sb.appendLine()
        (a.q.keys + b.q.keys).distinct().forEach { k ->
            val x = a.q[k]; val y = b.q[k]
            if (x == null || y == null) { sb.appendLine("  $k：一边缺数据"); return@forEach }
            val d = y - x
            sb.appendLine("  $k：${"%.3f".format(x)} → ${"%.3f".format(y)}" +
                "　(${if (d >= 0) "+" else ""}${"%.3f".format(d)})")
        }
        return sb.toString()
    }

    fun render(): String = buildString {
        appendLine("情景快照与复现（${snaps.size} 条）")
        appendLine()
        if (snaps.isEmpty()) { appendLine("（还没有存过任何情景）"); return@buildString }
        snaps.values.forEach { s ->
            appendLine("  ${s.id}　${s.name}　${s.comp}")
            appendLine("     seed=${if (s.seed < 0) "无" else s.seed}　${ZxUtil.ago(s.ts)}")
            appendLine("     ${s.q.entries.joinToString(" ")}")
        }
    }

    fun report(): String = buildString {
        appendLine("情景快照与复现 —— 让推演这件本来不可复核的事变得可复核")
        appendLine()
        appendLine("第三十一批做了 12 个模拟组件，立刻撞上一个问题：")
        appendLine()
        appendLine("  用户说「再跑一次看看」，结果不一样。")
        appendLine("  他把两个不同的结果截图给我，问我哪个是对的。")
        appendLine()
        appendLine("两个都对——因为 seed 没存。")
        appendLine()
        appendLine("这件事的严重性超过「结果不一致」本身：")
        appendLine("  **推演出来的结论本来就难验证**（它描述的是还没发生的事）。")
        appendLine("  如果连「同样的输入能不能得到同样的输出」都做不到，")
        appendLine("  那推演就彻底失去可信度了——连可重复都谈不上，更不用说正确。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**随机源自己实现，不用 java.util.Random**（序列可能因实现而变）")
        appendLine("  二、**不存全部样本，只存分位数**")
        appendLine("  三、**复现失败要说清差在哪**（参数 / seed / 组件实现回归）")
        appendLine("  四、**不自动覆盖同名情景**")
        appendLine()
        appendLine("第一条最容易被忽略：以为存了 seed 就万事大吉，")
        appendLine("结果 JDK 换个版本 `Random(seed)` 的序列就变了，存的 seed 毫无意义。")
    }

    fun demo(): String = buildString {
        appendLine("══ 为什么必须存 seed ══")
        appendLine()
        appendLine("同一个 seed 20250101，跑两次：")
        val r1 = ZxRender31.Lcg(20250101L)
        val r2 = ZxRender31.Lcg(20250101L)
        val a1 = (0..4).map { "%.6f".format(r1.next()) }
        val a2 = (0..4).map { "%.6f".format(r2.next()) }
        a1.forEachIndexed { i, v -> appendLine("  第${i + 1}次　$v　${if (v == a2[i]) "✓" else "✗"}") }
        appendLine()
        appendLine("序列完全由公式决定，不依赖任何运行时实现 —— 存了 seed 就一定能复现。")
        appendLine()
        appendLine("══ 复现失败时怎么定位 ══")
        appendLine()
        appendLine("  参数不同 → 结果不同是正常的，改回去再比")
        appendLine("  seed 不同 → 用当时的 seed 重跑")
        appendLine("  **参数和 seed 都一样却结果不同 → 组件实现变了，这是回归**")
        appendLine()
        appendLine("第三种最麻烦，也最有价值——它能抓出「改了渲染器把推演改坏了」这类问题。")
    }

    fun guide(): String = """
        zx_scen(op=save|force|del|list|replay|compare|demo|report|guide, ...)

        让"推演"这件本来不可复核的事变得可复核。

        第三十一批做了 12 个模拟组件，立刻撞上一个问题：

          用户说「再跑一次看看」，结果不一样。
          他把两个不同的结果截图给我，问我哪个是对的。

        两个都对——因为 seed 没存。

        这件事的严重性超过「结果不一致」本身：
          **推演出来的结论本来就难验证**（它描述的是还没发生的事）。
          如果连「同样的输入能不能得到同样的输出」都做不到，
          那推演就彻底失去可信度了。

        四条约束：
          一、**随机源自己实现，不用 java.util.Random**
              序列可能因 JDK 版本而变，存了 seed 也复现不了
          二、**不存全部样本，只存分位数**
              2000 个 double × 几十条快照会把内存吃掉
          三、**复现失败要说清差在哪**
              参数变 / seed 变 / 组件实现回归（第三种最麻烦也最有价值）
          四、**不自动覆盖同名情景**
              悄悄盖掉会让「改前 vs 改后」的对比变成假的

          op=replay   同参数重跑，查可重复性
          op=compare  两套不同参数，查参数影响
    """.trimIndent()

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("save", "force", "del", "list", "replay", "compare", "demo", "report", "guide"))
            return false to "zx_scen 的 op=\"$op\" 只支持 save / force / del / list / replay / compare / demo / report / guide"
        if (op in setOf("save", "force") && o.optString("name").isBlank())
            return false to "$op 需要 name——**没名字的情景等于没法引用**，存了也取不出来"
        if (op in setOf("save", "force") && !ZxRegistry.has(o.optString("comp")))
            return false to "$op 的 comp=\"${o.optString("comp")}\" 不是已注册组件"
        if (op == "compare" && (o.optString("a").isBlank() || o.optString("b").isBlank()))
            return false to "compare 需要 a 和 b 两个情景"
        if (op in setOf("replay", "compare")) {
            val id = o.optString(if (op == "replay") "id" else "a")
            if (get(id) == null) return false to "找不到情景「$id」"
        }
        return true to ""
    }
}
