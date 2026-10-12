package com.zorv.plugin.zxwidget
import kotlin.math.abs

import org.json.JSONObject

/**
 * 第二十六批组件（因果与可信度）的校验 + 示例 + 规格。
 *
 * 这批查的重点是**「前提无从判断」**。
 *
 * 前二十五批全都在回答"有没有关系"——
 * corr 给相关系数、scatter 画散点、regress 给回归线、
 * zx_test 给 p 值。没有一个回答"**是不是它导致的**"。
 *
 * 而 AI 在对话里最常干的恰恰是后者：
 *
 *   "留存下降了，是不是因为改了首页？"
 *
 * 如果只有相关性的工具，模型只能给出一个相关系数，
 * 然后**用文字去暗示因果**——而文字暗示没有前提、
 * 不可检验、也没法被用户反驳。
 *
 * 所以这批的每个组件都把**前提变成界面上的东西**，
 * 校验也因此要拦"前提无从判断"的情况：
 *
 *   · 因果图有环                    → 不是 DAG，后门路径无定义
 *   · 因果图边指向不存在的节点      → 画出一条连到空白的箭头
 *   · DID 干预时点不在数据内        → 算不出干预前差值
 *   · DID 两组期数不等              → 同一期表示的不是同一时刻
 *   · RDD 断点落在数据范围外        → 跳跃无从观测
 *   · RDD 断点两侧都不足 2 点       → 拟合不出两条线
 *   · IV 缺 z                       → 一阶段无从估计，IV 也就不成立
 *   · PSM 缺协变量 / 两组有一组为空 → 配不了对
 *   · 平衡图某协变量缺两组值        → SMD 算不出来
 *   · 缺失值矩阵行宽不齐            → 矩阵画错列
 *   · 序贯检验次数为 0 或函数非法   → 边界无从计算
 *   · 贝叶斯先验不在 0-1            → 赔率无定义
 *   · 异常检测全常量                → 任何判据都判不出异常
 *   · 功效分析效应量为 0            → 样本量无穷大
 *
 * 共同点：这些都是**不会崩**的。
 *
 * 前几批拦"渲染错位"，第二十四批拦"统计前提不成立"，
 * 第二十五批拦"区间/效应量的前提不成立"，
 * 这批拦的是"**因果结论的前提无从判断**"——
 * 前提判断不了时图照样画得出来、看起来也完整，
 * 但那个因果结论是**站不住的**。
 *
 * **不能验证的前提，等于没有前提。**
 * 以前"平行趋势假设"是一句没人验证的话，
 * 现在它是两条画出来的线，用户能直接看平行不平行。
 */
object ZxDsl26 {

    val ALL = listOf(
        "zx_causal", "zx_did", "zx_rdd", "zx_iv", "zx_sense",
        "zx_psm", "zx_balance", "zx_missing", "zx_seqtest",
        "zx_bayes", "zx_anomaly", "zx_power"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_causal"  -> causal(root)
        "zx_did"     -> did(root)
        "zx_rdd"     -> rdd(root)
        "zx_iv"      -> iv(root)
        "zx_sense"   -> sense(root)
        "zx_psm"     -> psm(root)
        "zx_balance" -> balance(root)
        "zx_missing" -> missing(root)
        "zx_seqtest" -> seqtest(root)
        "zx_bayes"   -> bayes(root)
        "zx_anomaly" -> anomaly(root)
        "zx_power"   -> power(root)
        else -> false to "未注册组件：$component"
    }

    private fun nums(root: JSONObject, key: String, min: Int, max: Int): Pair<List<Double>?, String> {
        val arr = root.optJSONArray(key) ?: return null to "缺少 $key"
        val n = arr.length()
        if (n < min) return null to "$key 至少 $min 个，实际 $n 个"
        if (n > max) return null to "$key 最多 $max 个，实际 $n 个"
        val out = ArrayList<Double>()
        for (k in 0 until n) {
            val v = arr.optDouble(k, Double.NaN)
            if (v.isNaN()) return null to "$key[$k] 不是数值"
            out.add(v)
        }
        return out to ""
    }

    private fun hasCycle(n: Int, edges: List<Pair<Int, Int>>): String? {
        val adj = (0 until n).map { i -> edges.filter { it.first == i }.map { it.second } }
        val color = IntArray(n)   // 0 未访问 1 在栈上 2 已完成
        val path = ArrayList<Int>()
        fun dfs(u: Int): String? {
            color[u] = 1; path.add(u)
            adj[u].forEach { v ->
                if (color[v] == 1) return (path.drop(path.indexOf(v)) + v).joinToString(" → ")
                if (color[v] == 0) dfs(v)?.let { return it }
            }
            path.removeAt(path.size - 1); color[u] = 2
            return null
        }
        (0 until n).forEach { if (color[it] == 0) dfs(it)?.let { return it } }
        return null
    }

    private fun causal(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes") ?: return false to "zx_causal 缺少 nodes"
        val n = nodes.length()
        if (n < 2) return false to "zx_causal 至少 2 个节点，实际 $n 个——单节点没有因果可谈"
        if (n > 8) return false to "zx_causal 最多 8 个节点，实际 $n 个"
        for (k in 0 until n) {
            val p = nodes.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"n\":名,\"role\":}" +
                    "（role 可选 confounder / collider / mediator）"
            if (p.optString("n").isBlank()) return false to "nodes[$k] 缺少 n（节点名）"
            val r = p.optString("role", "")
            if (r.isNotEmpty() && r !in setOf("confounder", "collider", "mediator"))
                return false to "nodes[$k].role=\"$r\" 只支持 confounder / collider / mediator"
        }
        val edges = o.optJSONArray("edges") ?: return false to "zx_causal 缺少 edges"
        val m = edges.length()
        if (m == 0) return false to "zx_causal 的 edges 为空——没有箭头就没有因果方向"
        if (m > 14) return false to "zx_causal 最多 14 条边，实际 $m 条"
        val es = ArrayList<Pair<Int, Int>>()
        for (k in 0 until m) {
            val e = edges.optJSONObject(k)
                ?: return false to "edges[$k] 不是对象，应为 {\"f\":起点,\"t\":终点}"
            val f = e.optInt("f", -1); val t = e.optInt("t", -1)
            // 指向不存在的节点：画一条连到空白的箭头
            if (f < 0 || f >= n) return false to "edges[$k].f=$f 超出 0-${n - 1}——会画一条连到空白的箭头"
            if (t < 0 || t >= n) return false to "edges[$k].t=$t 超出 0-${n - 1}——会画一条连到空白的箭头"
            if (f == t) return false to "edges[$k] 是自环（f=t=$f）"
            es.add(f to t)
        }
        // 有环：不是 DAG，后门路径无定义
        hasCycle(n, es)?.let {
            return false to "zx_causal 的图有环：$it——不是 DAG，后门路径无定义"
        }
        val t = o.optInt("t", -1); val y = o.optInt("y", -1)
        if (t !in 0 until n)
            return false to "zx_causal 的 t=$t 不是有效节点下标——没指定处理变量，后门无从计算"
        if (y !in 0 until n)
            return false to "zx_causal 的 y=$y 不是有效节点下标——没指定结果变量"
        if (t == y) return false to "zx_causal 的处理变量和结果变量是同一个（$t）"
        return true to ""
    }

    private fun did(o: JSONObject): Pair<Boolean, String> {
        val (a, ea) = nums(o, "treat", 3, 12)
        if (a == null) return false to "zx_did $ea"
        val (b, eb) = nums(o, "ctrl", 3, 12)
        if (b == null) return false to "zx_did $eb"
        // 两组期数不等：同一期表示的不是同一时刻
        if (a.size != b.size)
            return false to "zx_did 的 treat 有 ${a.size} 期、ctrl 有 ${b.size} 期——" +
                            "期数必须相等，否则同一列表示的不是同一时刻"
        val n = a.size
        val at = o.optInt("at", n / 2)
        // 干预时点不在数据内：算不出干预前差值
        if (at < 1 || at > n - 1)
            return false to "zx_did 的 at=$at 超出 [1,${n - 1}]——" +
                            "干预前至少要 1 期（否则算不出平行趋势），干预后也要至少 1 期"
        return true to ""
    }

    private fun rdd(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_rdd 缺少 pts"
        val n = pts.length()
        if (n < 6) return false to "zx_rdd 至少 6 个点，实际 $n 个——两侧各拟合一条线，点数不够"
        if (n > 40) return false to "zx_rdd 最多 40 个点，实际 $n 个"
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"x\":运行变量,\"y\":结果}"
            val x = p.optDouble("x", Double.NaN); val y = p.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) return false to "pts[$k] 缺少 x 或 y"
            xs.add(x); ys.add(y)
        }
        val cut = o.optDouble("cut", Double.NaN)
        if (cut.isNaN()) return false to "zx_rdd 缺少 cut（断点）——没有断点就不是断点回归"
        val xLo = xs.minOrNull()!!; val xHi = xs.maxOrNull()!!
        // 断点落在数据范围外：跳跃无从观测
        if (cut <= xLo || cut >= xHi)
            return false to "zx_rdd 的 cut=$cut 落在数据范围 ($xLo, $xHi) 之外——" +
                            "断点两侧至少得各有数据，跳跃才观测得到"
        val bw = o.optDouble("bw", 0.0)
        if (bw <= 0) return false to "zx_rdd 的 bw=$bw 必须为正——带宽为 0 则拟合不到任何点"
        val nL = xs.count { it < cut && abs(it - cut) <= bw }
        val nR = xs.count { it >= cut && abs(it - cut) <= bw }
        // 断点两侧都不足 2 点：拟合不出两条线
        if (nL < 2 || nR < 2)
            return false to "zx_rdd 带宽 ±$bw 内左侧 $nL 点、右侧 $nR 点——" +
                            "两侧至少各 2 点才能各拟合一条线（现在有一侧拟合不出来）"
        return true to ""
    }

    private fun iv(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_iv 缺少 pts"
        val n = pts.length()
        if (n < 5) return false to "zx_iv 至少 5 个样本，实际 $n 个——两阶段回归需要一定样本量"
        if (n > 30) return false to "zx_iv 最多 30 个样本，实际 $n 个"
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"z\":工具变量,\"x\":内生变量,\"y\":结果}"
            val z = p.optDouble("z", Double.NaN)
            // 缺 z：一阶段无从估计，IV 也就不成立
            if (z.isNaN()) return false to "pts[$k] 缺少 z（工具变量）——" +
                            "没有工具变量就不叫 IV，一阶段无从估计"
            if (p.optDouble("x", Double.NaN).isNaN()) return false to "pts[$k] 缺少 x"
            if (p.optDouble("y", Double.NaN).isNaN()) return false to "pts[$k] 缺少 y"
        }
        val zs = (0 until n).map { pts.optJSONObject(it)!!.optDouble("z") }
        if (zs.maxOrNull()!! - zs.minOrNull()!! < 1e-9)
            return false to "zx_iv 的 z 全相同——工具变量没有变化，一阶段完全解释不了 x"
        return true to ""
    }

    private fun sense(o: JSONObject): Pair<Boolean, String> {
        val est = o.optDouble("est", Double.NaN)
        if (est.isNaN()) return false to "zx_sense 缺少 est（原估计值）"
        val se = o.optDouble("se", Double.NaN)
        if (se.isNaN()) return false to "zx_sense 缺少 se（标准误）——没有标准误算不出区间"
        if (se <= 0) return false to "zx_sense 的 se=$se 必须为正"
        val g = o.optDouble("g", 1.0)
        if (g < 1.0) return false to "zx_sense 的 g=$g 不能小于 1——Γ=1 表示没有未测混杂"
        if (g > 12.0) return false to "zx_sense 的 g=$g 超出上限 12"
        return true to ""
    }

    private fun psm(o: JSONObject): Pair<Boolean, String> {
        val units = o.optJSONArray("units") ?: return false to "zx_psm 缺少 units"
        val n = units.length()
        if (n < 4) return false to "zx_psm 至少 4 个个体，实际 $n 个"
        if (n > 24) return false to "zx_psm 最多 24 个个体，实际 $n 个"
        var nt = 0; var nc = 0; var width = -1
        for (k in 0 until n) {
            val u = units.optJSONObject(k)
                ?: return false to "units[$k] 不是对象，应为 {\"t\":0或1,\"p\":倾向得分,\"cs\":[协变量]}"
            val t = u.optInt("t", -1)
            if (t != 0 && t != 1) return false to "units[$k].t=$t 必须是 0 或 1（0=对照，1=处理）"
            if (t == 1) nt++ else nc++
            val p = u.optDouble("p", Double.NaN)
            if (p.isNaN()) return false to "units[$k] 缺少 p（倾向得分）"
            if (p < 0 || p > 1) return false to "units[$k].p=$p 超出 0-1——倾向得分是概率"
            val cs = u.optJSONArray("cs")
                // 缺协变量：配不了对
                ?: return false to "units[$k] 缺少 cs（协变量）——没有协变量算不出倾向得分的依据"
            if (cs.length() == 0) return false to "units[$k].cs 为空"
            if (width < 0) width = cs.length()
            // 协变量个数不齐：SMD 逐列算，列数不对会串
            else if (cs.length() != width)
                return false to "units[$k].cs 有 ${cs.length()} 个，但 units[0].cs 有 $width 个——" +
                                "协变量个数必须一致"
            for (j in 0 until cs.length())
                if (cs.optDouble(j, Double.NaN).isNaN()) return false to "units[$k].cs[$j] 不是数值"
        }
        // 两组有一组为空：配不了对
        if (nt == 0 || nc == 0)
            return false to "zx_psm 只有一类个体（处理 $nt / 对照 $nc）——没有异组对象可配"
        val covs = o.optJSONArray("covs")
        if (covs == null || covs.length() != width)
            return false to "zx_psm 的 covs 需要 $width 个（与 cs 长度一致），" +
                            "实际 ${covs?.length() ?: 0} 个"
        return true to ""
    }

    private fun balance(o: JSONObject): Pair<Boolean, String> {
        val covs = o.optJSONArray("covs") ?: return false to "zx_balance 缺少 covs"
        val n = covs.length()
        if (n == 0) return false to "zx_balance 的 covs 为空"
        if (n > 8) return false to "zx_balance 最多 8 个协变量，实际 $n 个"
        for (k in 0 until n) {
            val c = covs.optJSONObject(k)
                ?: return false to "covs[$k] 不是对象，应为 {\"n\":名,\"a\":[处理组],\"b\":[对照组]}"
            if (c.optString("n").isBlank()) return false to "covs[$k] 缺少 n（协变量名）"
            val a = c.optJSONArray("a") ?: return false to "covs[$k] 缺少 a（处理组值）"
            val b = c.optJSONArray("b") ?: return false to "covs[$k] 缺少 b（对照组值）"
            // 缺两组值：SMD 算不出来
            if (a.length() == 0 || b.length() == 0)
                return false to "covs[$k] 的 a / b 不能为空——SMD 需要两组各自的方差"
            if (a.length() < 2 || b.length() < 2)
                return false to "covs[$k] 的 a / b 至少各 2 个，实际 ${a.length()} / ${b.length()}——" +
                                "样本太少算不出标准差"
            if (a.length() > 40 || b.length() > 40)
                return false to "covs[$k] 的 a / b 最多各 40 个"
            for (j in 0 until a.length())
                if (a.optDouble(j, Double.NaN).isNaN()) return false to "covs[$k].a[$j] 不是数值"
            for (j in 0 until b.length())
                if (b.optDouble(j, Double.NaN).isNaN()) return false to "covs[$k].b[$j] 不是数值"
        }
        return true to ""
    }

    private fun missing(o: JSONObject): Pair<Boolean, String> {
        val cols = o.optJSONArray("cols") ?: return false to "zx_missing 缺少 cols"
        val nc = cols.length()
        if (nc == 0) return false to "zx_missing 的 cols 为空"
        if (nc > 8) return false to "zx_missing 最多 8 列，实际 $nc 列"
        for (k in 0 until nc)
            if (cols.optString(k).isBlank()) return false to "cols[$k] 为空列名"
        val rows = o.optJSONArray("rows") ?: return false to "zx_missing 缺少 rows"
        val nr = rows.length()
        if (nr == 0) return false to "zx_missing 的 rows 为空"
        if (nr > 14) return false to "zx_missing 最多 14 行，实际 $nr 行"
        for (i in 0 until nr) {
            val r = rows.optJSONArray(i)
                ?: return false to "rows[$i] 不是数组，应为一行 0/1（1=缺失）"
            // 行宽不齐：矩阵会画错列
            if (r.length() != nc)
                return false to "rows[$i] 有 ${r.length()} 个，但 cols 有 $nc 列——" +
                                "行宽必须等于列数，否则矩阵会画错列"
            for (j in 0 until nc) {
                val v = r.optInt(j, -1)
                if (v != 0 && v != 1)
                    return false to "rows[$i][$j]=$v 必须是 0 或 1（1 表示缺失）"
            }
        }
        // 全不缺：那这个组件没有信息
        var miss = 0
        for (i in 0 until nr) for (j in 0 until nc) if (rows.optJSONArray(i)!!.optInt(j) == 1) miss++
        if (miss == 0)
            return false to "zx_missing 一个缺失都没有——没有缺失就没有模式可看"
        return true to ""
    }

    private fun seqtest(o: JSONObject): Pair<Boolean, String> {
        val looks = o.optJSONArray("looks") ?: return false to "zx_seqtest 缺少 looks"
        val k = looks.length()
        // 次数为 0：边界无从计算
        if (k == 0) return false to "zx_seqtest 的 looks 为空——没有看就没有边界可言"
        if (k < 2) return false to "zx_seqtest 至少 2 次看，实际 $k 次——只看一次就是普通检验"
        if (k > 10) return false to "zx_seqtest 最多 10 次看，实际 $k 次"
        for (i in 0 until k) {
            val l = looks.optJSONObject(i)
                ?: return false to "looks[$i] 不是对象，应为 {\"z\":统计量,\"n\":这次的名字}"
            if (l.optDouble("z", Double.NaN).isNaN()) return false to "looks[$i] 缺少 z（累计统计量）"
            if (l.optString("n", "").isBlank()) return false to "looks[$i] 缺少 n（这次的名字）"
        }
        val fn = o.optString("fn", "obrien")
        if (fn !in setOf("obrien", "pocock", "none"))
            return false to "zx_seqtest 的 fn=\"$fn\" 只支持 obrien / pocock / none"
        val a = o.optDouble("alpha", 0.05)
        if (a <= 0 || a >= 1) return false to "zx_seqtest 的 alpha=$a 超出 0-1"
        return true to ""
    }

    private fun bayes(o: JSONObject): Pair<Boolean, String> {
        val prior = o.optDouble("prior", Double.NaN)
        if (prior.isNaN()) return false to "zx_bayes 缺少 prior（先验概率）"
        // 先验不在 0-1：赔率无定义
        if (prior <= 0 || prior >= 1)
            return false to "zx_bayes 的 prior=$prior 必须在 0 到 1 之间（不含端点）——" +
                            "等于 0 或 1 时赔率无定义，也就不可能再被证据更新"
        val lp = o.optDouble("lp", Double.NaN)
        if (lp.isNaN()) return false to "zx_bayes 缺少 lp（正向证据的似然比）"
        if (lp <= 0) return false to "zx_bayes 的 lp=$lp 必须为正——似然比是比值"
        val ln = o.optDouble("ln", Double.NaN)
        if (ln.isNaN()) return false to "zx_bayes 缺少 ln（反向证据的似然比）"
        if (ln <= 0) return false to "zx_bayes 的 ln=$ln 必须为正"
        // 两个似然比都等于 1：证据不更新信念，图永远是平的
        if (abs(lp - 1.0) < 1e-9 && abs(ln - 1.0) < 1e-9)
            return false to "zx_bayes 的两个似然比都是 1——证据不更新信念，曲线永远是平的"
        val np = o.optInt("np", 0)
        if (np < 0 || np > 10) return false to "zx_bayes 的 np=$np 超出 0-10"
        val nn = o.optInt("nn", 0)
        if (nn < 0 || nn > 10) return false to "zx_bayes 的 nn=$nn 超出 0-10"
        return true to ""
    }

    private fun anomaly(o: JSONObject): Pair<Boolean, String> {
        val (v, e) = nums(o, "data", 5, 32)
        if (v == null) return false to "zx_anomaly $e——至少 5 个点才有分布可言"
        // 全常量：任何判据都判不出异常
        if (v.maxOrNull()!! - v.minOrNull()!! < 1e-9)
            return false to "zx_anomaly 的 data 全相同——方差为 0，" +
                            "3σ / IQR / MAD 三种判据全都判不出异常"
        val m = o.optString("method", "z")
        if (m !in setOf("z", "iqr", "mad"))
            return false to "zx_anomaly 的 method=\"$m\" 只支持 z / iqr / mad"
        return true to ""
    }

    private fun power(o: JSONObject): Pair<Boolean, String> {
        val d = o.optDouble("d", Double.NaN)
        if (d.isNaN()) return false to "zx_power 缺少 d（效应量 Cohen's d）"
        // 效应量为 0：样本量无穷大
        if (abs(d) < 1e-6)
            return false to "zx_power 的 d=$d 为 0——效应量为 0 时需要无穷多样本才能检出"
        if (abs(d) > 5) return false to "zx_power 的 d=$d 超出 ±5"
        val a = o.optDouble("alpha", 0.05)
        if (a <= 0 || a >= 1) return false to "zx_power 的 alpha=$a 超出 0-1"
        val n = o.optInt("n", 30)
        if (n < 4 || n > 250) return false to "zx_power 的 n=$n 超出 [4,250]"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_causal" -> """
            {"type":"zx_causal","n":"首页改版 → 留存","t":0,"y":2,
             "nodes":[{"n":"改版","role":""},{"n":"活跃度","role":"confounder"},
                      {"n":"留存","role":""},{"n":"不同触达","role":"mediator"}],
             "edges":[{"f":0,"t":2},{"f":1,"t":0},{"f":1,"t":2},{"f":0,"t":3},{"f":3,"t":2}]}""".trimIndent()
        "zx_did" -> """
            {"type":"zx_did","n":"改版对留存的 DID","at":5,
             "treat":[0.52,0.51,0.53,0.52,0.51,0.58,0.61,0.63,0.64,0.66],
             "ctrl":[0.50,0.49,0.51,0.50,0.50,0.51,0.52,0.53,0.52,0.54]}""".trimIndent()
        "zx_rdd" -> """
            {"type":"zx_rdd","n":"满减门槛效应","cut":100,"bw":0.5,
             "pts":[{"x":96.2,"y":41},{"x":97.5,"y":43},{"x":98.4,"y":44},
                    {"x":99.1,"y":46},{"x":99.7,"y":47},{"x":100.3,"y":62},
                    {"x":100.9,"y":63},{"x":101.6,"y":65},{"x":102.4,"y":66},
                    {"x":103.1,"y":68}]}""".trimIndent()
        "zx_iv" -> """
            {"type":"zx_iv","n":"推送对付费的 IV","pts":[
               {"z":1,"x":3,"y":12},{"z":0,"x":1,"y":8},{"z":1,"x":4,"y":15},
               {"z":0,"x":2,"y":9},{"z":1,"x":5,"y":18},{"z":0,"x":1,"y":7}]}""".trimIndent()
        "zx_sense" -> """
            {"type":"zx_sense","n":"改版效应有多稳","est":0.42,"se":0.13,"g":1.0}""".trimIndent()
        "zx_psm" -> """
            {"type":"zx_psm","n":"用没用新功能","covs":["活跃度","设备档"],
             "units":[{"t":1,"p":0.72,"cs":[42,3]},{"t":1,"p":0.68,"cs":[38,3]},
                      {"t":0,"p":0.31,"cs":[15,1]},{"t":0,"p":0.35,"cs":[18,1]},
                      {"t":1,"p":0.75,"cs":[45,3]},{"t":0,"p":0.29,"cs":[12,1]}]}""".trimIndent()
        "zx_balance" -> """
            {"type":"zx_balance","n":"两组可不可比","covs":[
               {"n":"活跃度","a":[42,38,45,40,44],"b":[15,18,12,16,20]},
               {"n":"注册天数","a":[310,280,340,300,320],"b":[95,120,88,110,130]},
               {"n":"设备档","a":[3,3,3,2,3],"b":[1,1,1,2,1]}]}""".trimIndent()
        "zx_missing" -> """
            {"type":"zx_missing","n":"问卷缺失模式","cols":["年龄","收入","城市","评分"],
             "rows":[[0,0,0,0],[0,1,0,0],[1,1,0,1],[0,0,0,0],
                     [0,1,1,0],[0,0,0,0],[1,1,0,1],[0,0,0,0]]}""".trimIndent()
        "zx_seqtest" -> """
            {"type":"zx_seqtest","n":"边看边下结论","fn":"obrien","alpha":0.05,
             "looks":[{"z":1.1,"n":"第1周"}",{"z":1.8,"n":"第2周"}",{"z":2.4,"n":"第3周"}",
                      {"z":3.1,"n":"第4周"}",{"z":3.6,"n":"第5周"}"]}""".trimIndent()
        "zx_bayes" -> """
            {"type":"zx_bayes","n":"故障是否由发布引起","prior":0.15,"lp":4.0,"ln":0.35,
             "np":1,"nn":0}""".trimIndent()
        "zx_anomaly" -> """
            {"type":"zx_anomaly","n":"响应耗时异常","method":"z",
             "data":[120,131,128,140,135,126,138,132,129,145,
                     133,127,139,136,410,130,134,128,137,131]}""".trimIndent()
        "zx_power" -> """
            {"type":"zx_power","n":"这个实验够不够","d":0.35,"n":60,"alpha":0.05}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_causal"  -> "nodes[{n,role}]2-8 + edges[{f,t}]，必须无环 + 指定 t 与 y"
        "zx_did"     -> "treat[]/ctrl[] 等长 3-12 + at∈[1,n-1]"
        "zx_rdd"     -> "pts[{x,y}] 6-40 + cut 在范围内 + bw>0，两侧各≥2 点"
        "zx_iv"      -> "pts[{z,x,y}] 5-30，z 必须有变化"
        "zx_sense"   -> "est + se>0 + g∈[1,12]"
        "zx_psm"     -> "units[{t,p,cs[]}] 4-24，两组都要有 + covs 与 cs 等长"
        "zx_balance" -> "covs[{n,a[],b[]}] ≤8，每组各 2-40"
        "zx_missing" -> "cols[] ≤8 + rows[][] 0/1，行宽=列数，至少一处缺失"
        "zx_seqtest" -> "looks[{z,n}] 2-10 + fn = obrien/pocock/none"
        "zx_bayes"   -> "prior∈(0,1) + lp/ln>0，且不能都为 1"
        "zx_anomaly" -> "data[] 5-32 且非常量 + method = z/iqr/mad"
        "zx_power"   -> "d≠0（|d|≤5）+ n∈[4,250] + alpha"
        else -> "未注册组件：$component"
    }
}
