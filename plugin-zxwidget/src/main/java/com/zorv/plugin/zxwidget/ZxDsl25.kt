package com.zorv.plugin.zxwidget
import kotlin.math.abs

import org.json.JSONObject

/**
 * 第二十五批组件（稳健性 / 敏感性 / 效应量）的校验 + 示例 + 规格。
 *
 * 这批查的重点是**「算不出区间或效应量」**。
 *
 * 第二十四批给了判据（QQ 给偏离带、森林图给合并菱形、
 * 公差带给 Cp/Cpk），但判据是**单点结论**。
 * 这批改成了"在多大范围内成立、有多稳"——
 * 而区间和效应量一样建立在前提之上：
 *
 *   · Bootstrap 有非数值样本       → 抽样无从谈起
 *   · 置换检验两组都为空           → 没法置换
 *   · 参数扫描格点为空             → 扫不出曲线
 *   · 参数扫描各格点指标数不一致   → 画出来的线会串行
 *   · ROC 只有一类样本             → AUC 恒为 0.5，没有信息
 *   · ROC 有 y 不是 0/1            → 正负类判定不明
 *   · 方差分解组数 < 2             → 组间方差无从定义
 *   · 方差分解某组为空             → 该组均值无定义
 *   · MDS 距离矩阵非方阵 / 不对称  → 没法分解
 *   · MDS 对角线非 0               → 自己到自己的距离不是 0
 *   · 地理流图边指向不存在的节点   → 画出一条连到空白的线
 *   · 分解周期 > 序列长度一半      → 季节项不可识别
 *   · 小提琴对比某组为空           → 没东西并排
 *   · 生存分组对比两组都为空       → 算不出 log-rank
 *   · 提升图基线为 0 或某箱总量 0  → 除零，提升值算错
 *   · 假设检验两组都为空           → 什么都算不出
 *
 * 共同点：这些都是**不会崩**的。
 *
 * 前几批拦的是"渲染错位"，第二十四批拦"统计前提不成立"，
 * 这批拦的是"**区间/效应量的前提不成立**"——
 * 前提不成立时图照样画得出来、看起来也正常，
 * 但那个区间或效应量是**编的**。
 *
 * **点估计 + 区间 + 敏感性，三样齐了才算能用的结论。**
 * 前提错了，区间就是假的，而假的区间比没有区间更危险——
 * 它会让人对一个不成立的结果抱有信心。
 */
object ZxDsl25 {

    val ALL = listOf(
        "zx_decompose", "zx_boot", "zx_perm", "zx_test",
        "zx_roc", "zx_lift", "zx_pscan", "zx_vardec",
        "zx_mds", "zx_geoflow", "zx_violincmp", "zx_survcmp"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_decompose" -> decompose(root)
        "zx_boot"      -> boot(root)
        "zx_perm"      -> perm(root)
        "zx_test"      -> test(root)
        "zx_roc"       -> roc(root)
        "zx_lift"      -> lift(root)
        "zx_pscan"     -> pscan(root)
        "zx_vardec"    -> vardec(root)
        "zx_mds"       -> mds(root)
        "zx_geoflow"   -> geoflow(root)
        "zx_violincmp" -> violincmp(root)
        "zx_survcmp"   -> survcmp(root)
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

    private fun decompose(o: JSONObject): Pair<Boolean, String> {
        val (v, e) = nums(o, "data", 4, 48)
        if (v == null) return false to "zx_decompose $e"
        val period = o.optInt("period", 7)
        // 周期 > 序列长度一半：季节项不可识别
        if (period < 2 || period > v.size / 2)
            return false to "zx_decompose 的 period=$period 超出 [2, ${v.size / 2}]——" +
                            "周期超过序列长度的一半，季节项不可识别（每个相位只有一个样本）"
        if (v.all { it == v[0] })
            return false to "zx_decompose 的 data 全相同——三项分解全是常数，没有信息"
        return true to ""
    }

    private fun boot(o: JSONObject): Pair<Boolean, String> {
        val (v, e) = nums(o, "data", 3, 40)
        // 有非数值样本：抽样无从谈起
        if (v == null) return false to "zx_boot $e（Bootstrap 靠重采样，样本必须是数值）"
        val b = o.optInt("b", 2000)
        if (b < 100 || b > 20000)
            return false to "zx_boot 的 b=$b 超出 [100,20000]——" +
                            "重采样次数太少区间不稳、太多浪费算力"
        val st = o.optString("stat", "mean")
        if (st !in setOf("mean", "median"))
            return false to "zx_boot 的 stat=\"$st\" 只支持 mean / median"
        return true to ""
    }

    private fun perm(o: JSONObject): Pair<Boolean, String> {
        val (a, ea) = nums(o, "a", 2, 30)
        if (a == null) return false to "zx_perm $ea"
        val (b, eb) = nums(o, "b", 2, 30)
        if (b == null) return false to "zx_perm $eb"
        // 两组都为空：没法置换（各自非空已由上面保证，这里防的是极端配置）
        if (a.size + b.size < 4)
            return false to "zx_perm 两组加起来只有 ${a.size + b.size} 个——" +
                            "置换组合太少，p 值只能是 1/N 的整数倍，没有分辨力"
        val n = o.optInt("n", 2000)
        if (n < 100 || n > 50000)
            return false to "zx_perm 的 n=$n 超出 [100,50000]"
        return true to ""
    }

    private fun test(o: JSONObject): Pair<Boolean, String> {
        val (a, ea) = nums(o, "a", 2, 40)
        if (a == null) return false to "zx_test $ea"
        val (b, eb) = nums(o, "b", 2, 40)
        if (b == null) return false to "zx_test $eb"
        val m = o.optString("method", "t")
        if (m !in setOf("t", "rank", "perm"))
            return false to "zx_test 的 method=\"$m\" 只支持 t / rank / perm"
        if (a.size != b.size)
            return false to "zx_test 的 a 有 ${a.size} 个、b 有 ${b.size} 个——" +
                            "长度不等时样本量滑块无意义（拖到哪一端都不对）"
        return true to ""
    }

    private fun roc(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_roc 缺少 pts"
        val n = pts.length()
        if (n < 4) return false to "zx_roc 至少 4 个点，实际 $n 个——太少画不出有意义的曲线"
        if (n > 40) return false to "zx_roc 最多 40 个点，实际 $n 个"
        var pos = 0; var neg = 0
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"s\":得分,\"y\":0 或 1}"
            val s = p.optDouble("s", Double.NaN)
            if (s.isNaN()) return false to "pts[$k] 缺少 s（模型得分）"
            val y = p.optInt("y", -1)
            // y 不是 0/1：正负类判定不明
            if (y != 0 && y != 1)
                return false to "pts[$k].y=$y 不是 0 或 1——正负类判定不明，AUC 无从计算"
            if (y == 1) pos++ else neg++
        }
        // 只有一类样本：AUC 恒为 0.5
        if (pos == 0 || neg == 0)
            return false to "zx_roc 只有一类样本（正 $pos / 负 $neg）——" +
                            "AUC 恒为 0.5，这个对比没有信息"
        return true to ""
    }

    private fun lift(o: JSONObject): Pair<Boolean, String> {
        val bins = o.optJSONArray("bins") ?: return false to "zx_lift 缺少 bins"
        val n = bins.length()
        if (n < 2) return false to "zx_lift 至少 2 个箱，实际 $n 个——单箱算不出提升"
        if (n > 10) return false to "zx_lift 最多 10 个箱，实际 $n 个"
        var hit = 0.0
        for (k in 0 until n) {
            val b = bins.optJSONObject(k)
                ?: return false to "bins[$k] 不是对象，应为 {\"n\":箱名,\"hit\":命中数,\"tot\":总数}"
            if (b.optString("n").isBlank()) return false to "bins[$k] 缺少 n（箱名）"
            val h = b.optDouble("hit", Double.NaN)
            val t = b.optDouble("tot", Double.NaN)
            if (h.isNaN() || t.isNaN()) return false to "bins[$k] 缺少 hit 或 tot"
            // 某箱总量为 0：除零
            if (t <= 0)
                return false to "bins[$k].tot=$t 必须为正——除零，提升值算错"
            if (h < 0 || h > t)
                return false to "bins[$k] 的 hit=$h 超出 [0,$t]——命中数不能大于总数"
            hit += h
        }
        // 基线为 0：除零
        if (hit <= 0)
            return false to "zx_lift 所有箱命中数都是 0——基线为 0，提升值是 0/0"
        return true to ""
    }

    private fun pscan(o: JSONObject): Pair<Boolean, String> {
        val grid = o.optJSONArray("grid") ?: return false to "zx_pscan 缺少 grid"
        val n = grid.length()
        // 格点为空：扫不出曲线
        if (n == 0) return false to "zx_pscan 的 grid 为空——扫不出曲线"
        if (n > 24) return false to "zx_pscan 最多 24 个格点，实际 $n 个"
        var width = -1
        for (k in 0 until n) {
            val g = grid.optJSONObject(k)
                ?: return false to "grid[$k] 不是对象，应为 {\"x\":参数值,\"ys\":[各指标值]}"
            if (g.optDouble("x", Double.NaN).isNaN()) return false to "grid[$k] 缺少 x（参数值）"
            val ys = g.optJSONArray("ys") ?: return false to "grid[$k] 缺少 ys"
            if (ys.length() == 0) return false to "grid[$k].ys 为空"
            // 各格点指标数不一致：画出来的线会串行
            if (width < 0) width = ys.length()
            else if (ys.length() != width)
                return false to "grid[$k].ys 有 ${ys.length()} 个，但 grid[0].ys 有 $width 个——" +
                                "各格点指标数必须一致，否则画出来的线会串行"
            for (j in 0 until ys.length())
                if (ys.optDouble(j, Double.NaN).isNaN()) return false to "grid[$k].ys[$j] 不是数值"
        }
        val metrics = o.optJSONArray("metrics")
        if (metrics == null || metrics.length() != width)
            return false to "zx_pscan 的 metrics 需要 $width 个（与 ys 长度一致），" +
                            "实际 ${metrics?.length() ?: 0} 个"
        return true to ""
    }

    private fun vardec(o: JSONObject): Pair<Boolean, String> {
        val groups = o.optJSONArray("groups") ?: return false to "zx_vardec 缺少 groups"
        val n = groups.length()
        // 组数 < 2：组间方差无从定义
        if (n < 2) return false to "zx_vardec 至少 2 组，实际 $n 组——组间方差无从定义"
        if (n > 8) return false to "zx_vardec 最多 8 组，实际 $n 组"
        var total = 0
        for (k in 0 until n) {
            val g = groups.optJSONObject(k)
                ?: return false to "groups[$k] 不是对象，应为 {\"n\":组名,\"vs\":[数值]}"
            if (g.optString("n").isBlank()) return false to "groups[$k] 缺少 n（组名）"
            val vs = g.optJSONArray("vs") ?: return false to "groups[$k] 缺少 vs"
            // 某组为空：该组均值无定义
            if (vs.length() == 0)
                return false to "groups[$k].vs 为空——该组均值无定义，组间方差算不出来"
            for (j in 0 until vs.length())
                if (vs.optDouble(j, Double.NaN).isNaN()) return false to "groups[$k].vs[$j] 不是数值"
            total += vs.length()
        }
        if (total < 4)
            return false to "zx_vardec 总共只有 $total 个样本——太少，方差分解没有意义"
        return true to ""
    }

    private fun mds(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_mds 缺少 pts"
        val n = pts.length()
        if (n < 3) return false to "zx_mds 至少 3 个点，实际 $n 个——两点摊到平面没有意义"
        if (n > 12) return false to "zx_mds 最多 12 个点，实际 $n 个"
        for (k in 0 until n)
            if (pts.optJSONObject(k)?.optString("n").isNullOrBlank())
                return false to "pts[$k] 缺少 n（点名）"
        val d = o.optJSONArray("d") ?: return false to "zx_mds 缺少 d（距离矩阵）"
        // 非方阵：没法分解
        if (d.length() != n)
            return false to "zx_mds 的 d 有 ${d.length()} 行，但 pts 有 $n 个——" +
                            "距离矩阵必须是 n×n 方阵"
        var maxD = 0.0
        for (i in 0 until n) {
            val row = d.optJSONArray(i)
                ?: return false to "d[$i] 不是数组，应为距离矩阵的一行"
            if (row.length() != n)
                return false to "d[$i] 有 ${row.length()} 列，但应为 $n 列——距离矩阵必须是方阵"
            for (j in 0 until n) {
                val v = row.optDouble(j, Double.NaN)
                if (v.isNaN()) return false to "d[$i][$j] 不是数值"
                if (v < 0) return false to "d[$i][$j]=$v 不能为负——距离不能是负的"
                // 对角线非 0
                if (i == j && abs(v) > 1e-9)
                    return false to "d[$i][$j]=$v 不为 0——自己到自己的距离必须是 0"
                // 不对称
                if (i < j) {
                    val other = d.optJSONArray(j)?.optDouble(i, Double.NaN) ?: Double.NaN
                    if (!other.isNaN() && abs(other - v) > 1e-6)
                        return false to "d[$i][$j]=$v 与 d[$j][$i]=$other 不相等——距离矩阵必须对称"
                }
                if (v > maxD) maxD = v
            }
        }
        if (maxD <= 0)
            return false to "zx_mds 所有距离都是 0——所有点重合，摊不开"
        return true to ""
    }

    private fun geoflow(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes") ?: return false to "zx_geoflow 缺少 nodes"
        val n = nodes.length()
        if (n < 2) return false to "zx_geoflow 至少 2 个节点，实际 $n 个"
        if (n > 10) return false to "zx_geoflow 最多 10 个节点，实际 $n 个"
        for (k in 0 until n) {
            val p = nodes.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"n\":名,\"x\":0-1,\"y\":0-1}"
            if (p.optString("n").isBlank()) return false to "nodes[$k] 缺少 n（节点名）"
            val x = p.optDouble("x", Double.NaN)
            val y = p.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) return false to "nodes[$k] 缺少 x 或 y"
            if (x < 0 || x > 1 || y < 0 || y > 1)
                return false to "nodes[$k] 的 x/y 超出 0-1——坐标是相对位置，应在 0 到 1 之间"
        }
        val edges = o.optJSONArray("edges") ?: return false to "zx_geoflow 缺少 edges"
        val m = edges.length()
        if (m == 0) return false to "zx_geoflow 的 edges 为空——没有流可画"
        if (m > 16) return false to "zx_geoflow 最多 16 条流，实际 $m 条"
        for (k in 0 until m) {
            val e = edges.optJSONObject(k)
                ?: return false to "edges[$k] 不是对象，应为 {\"f\":起点,\"t\":终点,\"w\":流量}"
            val f = e.optInt("f", -1); val t = e.optInt("t", -1)
            // 指向不存在的节点：画出一条连到空白的线
            if (f < 0 || f >= n)
                return false to "edges[$k].f=$f 超出 0-${n - 1}——会画出一条连到空白的线"
            if (t < 0 || t >= n)
                return false to "edges[$k].t=$t 超出 0-${n - 1}——会画出一条连到空白的线"
            if (f == t) return false to "edges[$k] 是自环（f=t=$f）——流向自己没有意义"
            val w = e.optDouble("w", Double.NaN)
            if (w.isNaN()) return false to "edges[$k] 缺少 w（流量）"
            if (w <= 0) return false to "edges[$k].w=$w 必须为正——流量为 0 的流不该画"
        }
        return true to ""
    }

    private fun violincmp(o: JSONObject): Pair<Boolean, String> {
        val groups = o.optJSONArray("groups") ?: return false to "zx_violincmp 缺少 groups"
        val n = groups.length()
        if (n < 2) return false to "zx_violincmp 至少 2 组，实际 $n 组——单组不需要并排"
        if (n > 6) return false to "zx_violincmp 最多 6 组，实际 $n 组"
        for (k in 0 until n) {
            val g = groups.optJSONObject(k)
                ?: return false to "groups[$k] 不是对象，应为 {\"n\":组名,\"vs\":[数值]}"
            if (g.optString("n").isBlank()) return false to "groups[$k] 缺少 n（组名）"
            val vs = g.optJSONArray("vs") ?: return false to "groups[$k] 缺少 vs"
            if (vs.length() < 2)
                return false to "groups[$k].vs 只有 ${vs.length()} 个——至少 2 个才能画出形状"
            if (vs.length() > 24)
                return false to "groups[$k].vs 最多 24 个，实际 ${vs.length()} 个"
            for (j in 0 until vs.length())
                if (vs.optDouble(j, Double.NaN).isNaN()) return false to "groups[$k].vs[$j] 不是数值"
        }
        return true to ""
    }

    private fun survcmp(o: JSONObject): Pair<Boolean, String> {
        val groups = o.optJSONArray("groups") ?: return false to "zx_survcmp 缺少 groups"
        val n = groups.length()
        if (n < 2) return false to "zx_survcmp 至少 2 组，实际 $n 组——单组不需要对比"
        if (n > 4) return false to "zx_survcmp 最多 4 组，实际 $n 组"
        val n0 = o.optInt("n0", -1)
        if (n0 < 1) return false to "zx_survcmp 的 n0=$n0 至少为 1（每组起始风险人数）"
        var anyEvent = false
        for (k in 0 until n) {
            val g = groups.optJSONObject(k)
                ?: return false to "groups[$k] 不是对象，应为 {\"n\":组名,\"events\":[{\"t\":,\"d\":,\"c\":}]}"
            if (g.optString("n").isBlank()) return false to "groups[$k] 缺少 n（组名）"
            val evs = g.optJSONArray("events") ?: return false to "groups[$k] 缺少 events"
            val m = evs.length()
            if (m > 14) return false to "groups[$k].events 最多 14 个，实际 $m 个"
            var prevT = -1.0
            var risk = n0
            for (i in 0 until m) {
                val e = evs.optJSONObject(i)
                    ?: return false to "groups[$k].events[$i] 不是对象，应为 {\"t\":,\"d\":,\"c\":}"
                val t = e.optDouble("t", Double.NaN)
                if (t.isNaN()) return false to "groups[$k].events[$i] 缺少 t"
                if (t <= prevT)
                    return false to "groups[$k].events[$i].t=$t 不比上一个（$prevT）大——" +
                                    "KM 要求时间递增"
                prevT = t
                val d = e.optInt("d", -1); val c = e.optInt("c", -1)
                if (d < 0 || c < 0) return false to "groups[$k].events[$i] 的 d / c 不能为负"
                if (d > 0) anyEvent = true
                if (d + c > risk)
                    return false to "groups[$k].events[$i] 的 d+c=${d + c} 超过剩余风险人数 $risk"
                risk -= (d + c)
            }
        }
        // 两组都为空：算不出 log-rank
        if (!anyEvent)
            return false to "zx_survcmp 所有组都没有事件（d 全为 0）——" +
                            "log-rank 靠比较观测与期望事件数，没有事件就无从计算"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_decompose" -> """
            {"type":"zx_decompose","n":"日活量","period":7,
             "data":[120,132,141,138,150,163,158,130,142,152,148,
                     161,172,166,138,150,160,156,170,182,176]}""".trimIndent()
        "zx_boot" -> """
            {"type":"zx_boot","n":"响应耗时中位数","stat":"median","b":2000,
             "data":[820,910,1050,1120,980,1240,760,1310,1090,940,
                     1150,1010,880,1260,990,1070]}""".trimIndent()
        "zx_perm" -> """
            {"type":"zx_perm","n":"A/B 耗时","n":2000,
             "a":[820,910,1050,980,1240,760,1090,940],
             "b":[1150,1010,880,1260,990,1070,1300,1180]}""".trimIndent()
        "zx_test" -> """
            {"type":"zx_test","n":"A/B 转化","method":"t",
             "a":[22,38,55,68,91,102,88,74],"b":[30,42,51,60,79,88,71,66]}""".trimIndent()
        "zx_roc" -> """
            {"type":"zx_roc","n":"风控模型","pts":[
               {"s":0.92,"y":1},{"s":0.85,"y":1},{"s":0.72,"y":1},
               {"s":0.61,"y":0},{"s":0.55,"y":1},{"s":0.43,"y":0},
               {"s":0.31,"y":0},{"s":0.22,"y":0}]}""".trimIndent()
        "zx_lift" -> """
            {"type":"zx_lift","n":"分箱提升","bins":[
               {"n":"第1箱","hit":42,"tot":100},{"n":"第2箱","hit":28,"tot":100},
               {"n":"第3箱","hit":16,"tot":100},{"n":"第4箱","hit":8,"tot":100}]}""".trimIndent()
        "zx_pscan" -> """
            {"type":"zx_pscan","n":"阈值扫描","metrics":["精确率","召回率"],"grid":[
               {"x":0.1,"ys":[0.42,0.95]},{"x":0.3,"ys":[0.61,0.82]},
               {"x":0.5,"ys":[0.78,0.63]},{"x":0.7,"ys":[0.89,0.41]}]}""".trimIndent()
        "zx_vardec" -> """
            {"type":"zx_vardec","n":"机型耗电","groups":[
               {"n":"旗舰","vs":[31,33,30,32,34]},
               {"n":"中端","vs":[22,24,21,23,25]},
               {"n":"入门","vs":[16,18,15,17,19]}]}""".trimIndent()
        "zx_mds" -> """
            {"type":"zx_mds","n":"机型相似度","iter":60,
             "pts":[{"n":"A"},{"n":"B"},{"n":"C"},{"n":"D"}],
             "d":[[0,1.2,2.4,3.1],[1.2,0,1.5,2.8],
                  [2.4,1.5,0,1.1],[3.1,2.8,1.1,0]]}""".trimIndent()
        "zx_geoflow" -> """
            {"type":"zx_geoflow","n":"专线流量",
             "nodes":[{"n":"北京","x":0.7,"y":0.2},{"n":"上海","x":0.8,"y":0.5},
                      {"n":"广州","x":0.7,"y":0.85},{"n":"成都","x":0.4,"y":0.55}],
             "edges":[{"f":0,"t":1,"w":8},{"f":0,"t":3,"w":5},
                      {"f":1,"t":2,"w":6},{"f":3,"t":2,"w":3}]}""".trimIndent()
        "zx_violincmp" -> """
            {"type":"zx_violincmp","n":"三版本耗时","groups":[
               {"n":"v1.0","vs":[820,910,1050,980,1240,760]},
               {"n":"v1.1","vs":[690,720,880,760,910,700]},
               {"n":"v1.2","vs":[520,610,480,700,560,530]}]}""".trimIndent()
        "zx_survcmp" -> """
            {"type":"zx_survcmp","n":"两组留存","n0":100,"groups":[
               {"n":"对照组","events":[{"t":1,"d":8,"c":2},{"t":3,"d":12,"c":5}]},
               {"n":"实验组","events":[{"t":1,"d":4,"c":1},{"t":3,"d":6,"c":3}]}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_decompose" -> "data[] 4-48 + period∈[2,n/2] · 周期过大则季节项不可识别"
        "zx_boot"      -> "data[] 3-40 + b∈[100,20000] · stat = mean/median"
        "zx_perm"      -> "a[]/b[] 各 2-30 且合计≥4 · 组合太少则 p 无分辨力"
        "zx_test"      -> "a[]/b[] 等长 2-40 · method = t/rank/perm"
        "zx_roc"       -> "pts[{s,y}]  4-40 · y 必须 0/1，且正负都要有"
        "zx_lift"      -> "bins[{n,hit,tot}]  2-10 · tot>0，且至少一箱 hit>0"
        "zx_pscan"     -> "grid[{x,ys[]}] ≤24 + metrics[] 与 ys 等长"
        "zx_vardec"    -> "groups[{n,vs[]}]  2-8 · 组非空，合计样本 ≥4"
        "zx_mds"       -> "pts[{n}] 3-12 + d[][] n×n 方阵、对称、对角为 0"
        "zx_geoflow"   -> "nodes[{n,x,y}] 2-10（x/y 为 0-1）+ edges[{f,t,w}]，不可自环"
        "zx_violincmp" -> "groups[{n,vs[]}]  2-6 · 每组 2-24 个"
        "zx_survcmp"   -> "groups[{n,events[{t,d,c}]}]  2-4 + n0 · 至少有一个事件"
        else -> "未注册组件：$component"
    }
}
