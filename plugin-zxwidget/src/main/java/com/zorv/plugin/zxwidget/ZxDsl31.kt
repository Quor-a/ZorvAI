package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs

/**
 * 第三十一批组件（模拟与推演）的校验 + 示例 + 规格。
 *
 * 这批查的是**「推演出来了，但推演本身不可信」**。
 *
 * 前八档查的都还是"已经发生的事"这一族：
 *
 *   画不出来 → 统计前提不成立 → 区间前提不成立 →
 *   因果前提无从判断 → 看起来确定了其实没那么确定 →
 *   状态摆出来了但推不出该做什么 → 记下来了但下次用不上 →
 *   数字都在但落不到谁该做什么
 *
 * 到了模拟与推演，多了一种新的错法，而且是**最危险的一种**：
 *
 *   **推演出来的东西，用户没法用事实校验。**
 *
 *   因为它本来就是"还没发生的事"。前面的组件画错了，
 *   用户拿真实数据一对就知道；推演错了，要等到未来发生才知道。
 *
 *   所以**错的推演比没有推演更危险**——它看起来一样可信，
 *   但会让人按一个错误的预期去做准备。
 *
 * 具体要拦的（与 ZxRender31 注释一一对应）：
 *   montecarlo    试验次数 <200（分位数不稳）/ sd<0（分布非法）
 *   scenario      情景序列长度不齐（画不出带）/ 悲观值优于乐观值（上下界反了）
 *   tornado       影响值全为 0（排不出序）/ 因素名重复
 *   breakeven     单价 ≤ 变动成本（**永远盈亏平衡不了**）
 *   whatif        链路缺节点
 *   systemdyn     流出率导致存量变负（物理上不可能）
 *   markov        非方阵 / 每行和不为 1（不是合法转移矩阵）
 *   payoff        非 2x2 / 支付数不齐
 *   optpath       起点终点不存在 / 图不连通（无路径）
 *   reversesolve  目标不在值域内（**必须明说无解**）
 *   stress        全部存活（**压得不够，没测出边界**）
 *   realoption    σ 非正（期权价值算不出来）
 */
object ZxDsl31 {

    val ALL = listOf(
        "zx_scenario", "zx_montecarlo", "zx_stress",
        "zx_tornado", "zx_whatif", "zx_breakeven",
        "zx_systemdyn", "zx_markov", "zx_optpath",
        "zx_payoff", "zx_reversesolve", "zx_realoption"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_scenario"    -> scenario(root)
        "zx_montecarlo"  -> montecarlo(root)
        "zx_stress"      -> stress(root)
        "zx_tornado"     -> tornado(root)
        "zx_whatif"      -> whatif(root)
        "zx_breakeven"   -> breakeven(root)
        "zx_systemdyn"   -> systemdyn(root)
        "zx_markov"      -> markov(root)
        "zx_optpath"     -> optpath(root)
        "zx_payoff"      -> payoff(root)
        "zx_reversesolve" -> reversesolve(root)
        "zx_realoption"  -> realoption(root)
        else -> false to "未注册组件：$component"
    }

    private fun scenario(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("scenarios") ?: return false to "zx_scenario 缺少 scenarios"
        val n = a.length()
        if (n < 2) return false to "zx_scenario 至少 2 个情景，实际 $n 个——" +
                                   "只有 1 个情景那叫预测，不叫情景推演"
        if (n > 5) return false to "zx_scenario 最多 5 个情景，实际 $n 个"
        var len = -1
        val names = HashSet<String>()
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "scenarios[$k] 不是对象，应为 {\"n\":情景名,\"v\":[各期数值]}"
            val nm = s.optString("n")
            if (nm.isBlank()) return false to "scenarios[$k] 缺少 n（情景名）"
            if (!names.add(nm)) return false to "scenarios[$k] 的情景名「$nm」重复"
            val v = s.optJSONArray("v") ?: return false to "scenarios[$k] 缺少 v（各期数值）"
            val l = v.length()
            if (l < 2) return false to "scenarios[$k].v 至少 2 期，实际 $l 期"
            // 序列长度不齐：画不出带
            if (len < 0) len = l
            else if (l != len)
                return false to "scenarios[$k].v 有 $l 期，但第一个情景有 $len 期——" +
                                "**长度不齐画不出色带**，各情景必须覆盖同样的时间轴"
            for (i in 0 until l)
                if (v.optDouble(i, Double.NaN).isNaN())
                    return false to "scenarios[$k].v[$i] 不是数值"
        }
        // 悲观值优于乐观值：上下界反了
        fun arr(k: Int) = a.optJSONObject(k)!!.optJSONArray("v")!!
        for (k in 0 until n) for (j in k + 1 until n) {
            val nk = a.optJSONObject(k)!!.optString("n")
            val nj = a.optJSONObject(j)!!.optString("n")
            val pessim = if (nk.contains("悲观")) k else if (nj.contains("悲观")) j else -1
            val optim = if (nk.contains("乐观")) k else if (nj.contains("乐观")) j else -1
            if (pessim >= 0 && optim >= 0) {
                val lp = arr(pessim).optDouble(arr(pessim).length() - 1, 0.0)
                val lo = arr(optim).optDouble(arr(optim).length() - 1, 0.0)
                if (lp > lo)
                    return false to "**悲观情景（$lp）优于乐观情景（$lo）**——" +
                                    "上下界反了，画出来的色带是错的"
            }
        }
        return true to ""
    }

    private fun montecarlo(o: JSONObject): Pair<Boolean, String> {
        val n = o.optInt("n", 2000)
        // 试验次数太少：分位数不稳
        if (n < 200)
            return false to "zx_montecarlo 的 n=$n 少于 200——**分位数不稳**，" +
                            "P95 会随每次运行跳动很大，而这个组件的全部价值就在分位数上"
        if (n > 20000) return false to "zx_montecarlo 最多 20000 次，实际 $n 次（气泡里算不完）"
        val sd = o.optDouble("sd", Double.NaN)
        if (sd.isNaN()) return false to "zx_montecarlo 缺少 sd（标准差）"
        if (sd < 0) return false to "zx_montecarlo 的 sd=$sd 不能为负——**分布非法**"
        if (sd == 0.0) return false to "zx_montecarlo 的 sd=0——**没有不确定性还叫蒙特卡洛吗**，" +
                                       "退化成一个确定的数，用 zx_terminal 就行"
        val mean = o.optDouble("mean", Double.NaN)
        if (mean.isNaN()) return false to "zx_montecarlo 缺少 mean（均值）"
        // seed 缺失要提示（不拦，但记下来）
        return true to if (o.has("seed")) "" else
            "提示：没给 seed，再跑一次结果会变。存不下种子 = 推演无法复核（见 ZxScen）"
    }

    private fun stress(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items") ?: return false to "zx_stress 缺少 items"
        val n = a.length()
        if (n == 0) return false to "zx_stress 的 items 为空"
        if (n > 8) return false to "zx_stress 最多 8 个情景，实际 $n 个"
        var fail = 0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":情景,\"load\":倍数,\"s\":结果}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（情景名）"
            val load = it.optDouble("load", Double.NaN)
            if (load.isNaN()) return false to "items[$k] 缺少 load（压力倍数）"
            if (load <= 0) return false to "items[$k].load=$load 必须为正"
            val s = it.optString("s", "pass")
            if (s !in setOf("pass", "warn", "fail"))
                return false to "items[$k].s=\"$s\" 只支持 pass / warn / fail"
            if (s == "fail") fail++
        }
        // 全部存活：压得不够
        if (fail == 0)
            return false to "zx_stress 全部 $n 个情景都通过——**压得不够**。" +
                            "压力测试的目的是**找到边界在哪**，全部通过等于没测出边界，" +
                            "不知道系统到底能扛多少。至少要有 1 个失败情景，或者说明这是上限"
        return true to ""
    }

    private fun tornado(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items") ?: return false to "zx_tornado 缺少 items"
        val n = a.length()
        if (n < 2) return false to "zx_tornado 至少 2 个因素，实际 $n 个——" +
                                   "只有 1 个因素排不出序，而排序正是这个组件的价值"
        if (n > 10) return false to "zx_tornado 最多 10 个因素，实际 $n 个"
        val names = HashSet<String>()
        var allZero = true
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":因素,\"lo\":下界,\"hi\":上界}"
            val nm = it.optString("n")
            if (nm.isBlank()) return false to "items[$k] 缺少 n（因素名）"
            if (!names.add(nm)) return false to "items[$k] 的因素名「$nm」重复"
            val lo = it.optDouble("lo", Double.NaN)
            val hi = it.optDouble("hi", Double.NaN)
            if (lo.isNaN()) return false to "items[$k] 缺少 lo（下界影响）"
            if (hi.isNaN()) return false to "items[$k] 缺少 hi（上界影响）"
            if (abs(hi - lo) > 1e-9) allZero = false
        }
        // 影响值全为 0：排不出序
        if (allZero)
            return false to "zx_tornado 全部 $n 个因素的摆动都是 0——**排不出序**，" +
                            "也就看不出该先去核实哪个数。每个因素要给 lo/hi 两个值"
        return true to ""
    }

    private fun whatif(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("nodes") ?: return false to "zx_whatif 缺少 nodes"
        val n = a.length()
        if (n < 2) return false to "zx_whatif 至少 2 个节点，实际 $n 个——" +
                                   "只有 1 个节点就不存在「链路」，也谈不上放大"
        if (n > 8) return false to "zx_whatif 最多 8 个节点，实际 $n 个"
        for (k in 0 until n) {
            val nd = a.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"n\":节点名,\"v\":基准值,\"k\":放大系数}"
            if (nd.optString("n").isBlank()) return false to "nodes[$k] 缺少 n（节点名）"
            val v = nd.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "nodes[$k] 缺少 v（基准值）"
            if (v <= 0) return false to "nodes[$k].v=$v 必须为正——**百分比变化在 0 上没有意义**"
            // 传播系数未定义：默认 1，但要显式检查不是 NaN
            val kk = nd.optDouble("k", Double.NaN)
            if (kk.isNaN()) return false to "nodes[$k].k 不是数值——**传播系数未定义**，" +
                                            "链路怎么传导就说不清了"
            if (kk <= 0) return false to "nodes[$k].k=$kk 必须为正（系数为负会让上下游方向翻转）"
        }
        return true to ""
    }

    private fun breakeven(o: JSONObject): Pair<Boolean, String> {
        val fixed = o.optDouble("fixed", Double.NaN)
        if (fixed.isNaN()) return false to "zx_breakeven 缺少 fixed（固定成本）"
        if (fixed <= 0) return false to "zx_breakeven 的 fixed=$fixed 必须为正"
        val price = o.optDouble("price", Double.NaN)
        if (price.isNaN()) return false to "zx_breakeven 缺少 price（单价）"
        if (price <= 0) return false to "zx_breakeven 的 price=$price 必须为正"
        val vc = o.optDouble("vc", Double.NaN)
        if (vc.isNaN()) return false to "zx_breakeven 缺少 vc（单位变动成本）"
        if (vc < 0) return false to "zx_breakeven 的 vc=$vc 不能为负"
        // 单价 ≤ 变动成本：永远盈亏平衡不了
        if (price <= vc)
            return false to "zx_breakeven 的 price=$price ≤ vc=$vc——**永远盈亏平衡不了**。" +
                            "单位贡献为负，卖一件亏一件，卖得越多亏得越多。" +
                            "这时临界点是无穷大，显示一个空值会让人以为是自己没调好"
        return true to ""
    }

    private fun systemdyn(o: JSONObject): Pair<Boolean, String> {
        val s0 = o.optDouble("s0", Double.NaN)
        if (s0.isNaN()) return false to "zx_systemdyn 缺少 s0（初始存量）"
        if (s0 < 0) return false to "zx_systemdyn 的 s0=$s0 不能为负——**存量不能是负数**"
        val inf = o.optDouble("in", Double.NaN)
        if (inf.isNaN()) return false to "zx_systemdyn 缺少 in（流入速率）"
        if (inf < 0) return false to "zx_systemdyn 的 in=$inf 不能为负"
        val out = o.optDouble("out", Double.NaN)
        if (out.isNaN()) return false to "zx_systemdyn 缺少 out（流出比例）"
        if (out < 0) return false to "zx_systemdyn 的 out=$out 不能为负"
        // 流出率 > 1：存量会变负，物理上不可能
        if (out > 1)
            return false to "zx_systemdyn 的 out=$out 大于 1——**下一期存量会变负**，" +
                            "物理上不可能。流出是「按当前存量的比例流出」，不能超过 100%"
        val steps = o.optInt("steps", 40)
        if (steps < 2) return false to "zx_systemdyn 的 steps=$steps 至少 2 步——" +
                                       "看的是长期行为，1 步看不出趋势"
        if (steps > 200) return false to "zx_systemdyn 最多 200 步，实际 $steps 步"
        val cap = o.optDouble("cap", Double.NaN)
        if (!cap.isNaN() && cap <= 0)
            return false to "zx_systemdyn 的 cap=$cap 必须为正（给了上限就不能是非正数）"
        return true to ""
    }

    private fun markov(o: JSONObject): Pair<Boolean, String> {
        val st = o.optJSONArray("states") ?: return false to "zx_markov 缺少 states"
        val n = st.length()
        if (n < 2) return false to "zx_markov 至少 2 个状态，实际 $n 个"
        if (n > 6) return false to "zx_markov 最多 6 个状态，实际 $n 个（气泡里矩阵画不下）"
        for (k in 0 until n)
            if (st.optString(k).isBlank()) return false to "states[$k] 为空"
        val a = o.optJSONArray("p") ?: return false to "zx_markov 缺少 p（转移矩阵）"
        val m = a.length()
        // 非方阵
        if (m != n)
            return false to "zx_markov 的转移矩阵有 $m 行，但状态有 $n 个——**不是方阵**，" +
                            "转移矩阵必须是 n×n"
        for (k in 0 until m) {
            val row = a.optJSONObject(k)
                ?: return false to "p[$k] 不是对象，应为 {\"r\":[该行各列的转移概率]}"
            val r = row.optJSONArray("r") ?: return false to "p[$k] 缺少 r（该行概率）"
            if (r.length() != n)
                return false to "p[$k].r 有 ${r.length()} 个值，但状态有 $n 个——**不是方阵**"
            var sum = 0.0
            for (i in 0 until n) {
                val v = r.optDouble(i, Double.NaN)
                if (v.isNaN()) return false to "p[$k].r[$i] 不是数值"
                if (v < 0 || v > 1) return false to "p[$k].r[$i]=$v 超出 0-1"
                sum += v
            }
            // 每行和不为 1：不是合法转移矩阵
            if (abs(sum - 1.0) > 0.01)
                return false to "p[$k] 的行和是 ${"%.3f".format(sum)} ≠ 1——" +
                                "**不是合法转移矩阵**，从任一状态出去的概率必须加起来等于 1。" +
                                "差的那部分意味着「有概率凭空消失了」"
        }
        return true to ""
    }

    private fun optpath(o: JSONObject): Pair<Boolean, String> {
        val nd = o.optJSONArray("nodes") ?: return false to "zx_optpath 缺少 nodes"
        val n = nd.length()
        if (n < 2) return false to "zx_optpath 至少 2 个节点，实际 $n 个"
        if (n > 12) return false to "zx_optpath 最多 12 个节点，实际 $n 个（网格布局放不下）"
        for (k in 0 until n)
            if (nd.optString(k).isBlank()) return false to "nodes[$k] 为空"
        val a = o.optJSONArray("edges") ?: return false to "zx_optpath 缺少 edges"
        val m = a.length()
        if (m == 0) return false to "zx_optpath 的 edges 为空——没有边必然不可达"
        if (m > 30) return false to "zx_optpath 最多 30 条边，实际 $m 条"
        val adj = Array(n) { ArrayList<Pair<Int, Double>>() }
        for (k in 0 until m) {
            val e = a.optJSONObject(k)
                ?: return false to "edges[$k] 不是对象，应为 {\"f\":起点,\"t\":终点,\"w\":代价}"
            val f = e.optInt("f", -1)
            val t = e.optInt("t", -1)
            if (f < 0 || f >= n) return false to "edges[$k].f=$f 超出 0-${n - 1}"
            if (t < 0 || t >= n) return false to "edges[$k].t=$t 超出 0-${n - 1}"
            if (f == t) return false to "edges[$k] 是自环（f=t=$f）"
            val w = e.optDouble("w", Double.NaN)
            if (w.isNaN()) return false to "edges[$k] 缺少 w（代价）"
            if (w <= 0) return false to "edges[$k].w=$w 必须为正——**代价为 0 的边会让最短路失去意义**"
            adj[f].add(t to w)
        }
        val src = o.optInt("src", 0)
        val dst = o.optInt("dst", n - 1)
        if (src !in 0 until n) return false to "src=$src 超出 0-${n - 1}——**起点不存在**"
        if (dst !in 0 until n) return false to "dst=$dst 超出 0-${n - 1}——**终点不存在**"
        if (src == dst) return false to "src 和 dst 都是 $src——起点等于终点，没有路径可求"
        // 不连通：无路径
        val seen = BooleanArray(n); val q = ArrayList<Int>()
        q.add(src); seen[src] = true
        while (q.isNotEmpty()) {
            val u = q.removeAt(0)
            adj[u].forEach { (v, _) -> if (!seen[v]) { seen[v] = true; q.add(v) } }
        }
        if (!seen[dst])
            return false to "从节点 $src 到节点 $dst **不连通**——" +
                            "这不等于「代价很高」，而是**根本走不通**。" +
                            "再怎么优化都没用，得先补一条边"
        return true to ""
    }

    private fun payoff(o: JSONObject): Pair<Boolean, String> {
        val rs = o.optJSONArray("rows") ?: return false to "zx_payoff 缺少 rows"
        val cs = o.optJSONArray("cols") ?: return false to "zx_payoff 缺少 cols"
        val rn = rs.length(); val cn = cs.length()
        // 非 2x2
        if (rn != 2 || cn != 2)
            return false to "zx_payoff 目前只支持 2×2（实际 ${rn}×${cn}）——" +
                            "**超过 2×2 的博弈在气泡里画不下，而且纳什均衡要枚举的格子指数级增长**。" +
                            "更多策略请拆成多个 2×2 或用 zx_matrix"
        for (k in 0 until rn) if (rs.optString(k).isBlank()) return false to "rows[$k] 为空"
        for (k in 0 until cn) if (cs.optString(k).isBlank()) return false to "cols[$k] 为空"
        val a = o.optJSONArray("cells") ?: return false to "zx_payoff 缺少 cells"
        // 支付数不齐
        if (a.length() != rn * cn)
            return false to "zx_payoff 的 cells 有 ${a.length()} 个，但 ${rn}×${cn} 需要 ${rn * cn} 个——" +
                            "**支付数对不上**，矩阵会有空格"
        for (k in 0 until a.length()) {
            val c = a.optJSONObject(k)
                ?: return false to "cells[$k] 不是对象，应为 {\"a\":行方收益,\"b\":列方收益}"
            if (c.optDouble("a", Double.NaN).isNaN()) return false to "cells[$k] 缺少 a（行方收益）"
            if (c.optDouble("b", Double.NaN).isNaN()) return false to "cells[$k] 缺少 b（列方收益）"
        }
        return true to ""
    }

    private fun reversesolve(o: JSONObject): Pair<Boolean, String> {
        val target = o.optDouble("target", Double.NaN)
        if (target.isNaN()) return false to "zx_reversesolve 缺少 target（目标值）"
        val a = o.optDouble("a", Double.NaN)
        if (a.isNaN()) return false to "zx_reversesolve 缺少 a（转换斜率）"
        if (a == 0.0) return false to "zx_reversesolve 的 a=0——**斜率为 0 无法反解**，" +
                                      "投入多少都是同一个结果，目标要么必然达到要么必然达不到"
        val b = o.optDouble("b", 0.0)
        val lo = o.optDouble("lo", 0.0)
        val hi = o.optDouble("hi", Double.NaN)
        if (hi.isNaN()) return false to "zx_reversesolve 缺少 hi（投入上限）"
        if (hi <= lo) return false to "zx_reversesolve 的 hi=$hi 必须大于 lo=$lo——**可行域为空**"
        // 目标不在值域内：必须明说无解
        val yLo = a * lo + b
        val yHi = a * hi + b
        val mn = minOf(yLo, yHi); val mx = maxOf(yLo, yHi)
        if (target < mn || target > mx)
            return false to "zx_reversesolve 的目标 $target 不在可行值域 [${"%.1f".format(mn)}, " +
                            "${"%.1f".format(mx)}] 内——**无解**。" +
                            "反向求解最容易犯的错就是不说明无解：给一个够不着的输入让人白努力，" +
                            "比直接说做不到更糟。请改目标或放宽 hi"
        return true to ""
    }

    private fun realoption(o: JSONObject): Pair<Boolean, String> {
        val now = o.optDouble("now", Double.NaN)
        if (now.isNaN()) return false to "zx_realoption 缺少 now（现在做的净现值）"
        val later = o.optDouble("later", Double.NaN)
        if (later.isNaN()) return false to "zx_realoption 缺少 later（等一等再做的价值）"
        val sigma = o.optDouble("sigma", Double.NaN)
        if (sigma.isNaN()) return false to "zx_realoption 缺少 sigma（不确定性）"
        // σ 非正：期权价值算不出来
        if (sigma <= 0)
            return false to "zx_realoption 的 sigma=$sigma 必须为正——**不确定为 0 时期权价值就是 0**，" +
                            "那「等一等」没有任何额外价值，直接用 zx_decision 比较 now/later 就够了"
        if (sigma > 1.5) return false to "zx_realoption 的 sigma=$sigma 过大（>1.5），期权价值会虚高"
        val cost = o.optDouble("cost", 0.0)
        if (cost < 0) return false to "zx_realoption 的 cost=$cost 不能为负"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_scenario" -> """
            {"type":"zx_scenario","n":"明年营收推演","scenarios":[
               {"n":"乐观","v":[100,128,160,198,242]},
               {"n":"中性","v":[100,118,138,160,182]},
               {"n":"悲观","v":[100,105,108,110,112]}]}""".trimIndent()
        "zx_montecarlo" -> """
            {"type":"zx_montecarlo","n":"季度利润模拟","n":2000,"mean":120,"sd":38,
             "target":100,"seed":20250101}""".trimIndent()
        "zx_stress" -> """
            {"type":"zx_stress","n":"极端情景","items":[
               {"n":"常规流量","load":1.0,"s":"pass"},
               {"n":"大促 2 倍","load":2.0,"s":"pass"},
               {"n":"大促 3 倍","load":3.0,"s":"warn"},
               {"n":"依赖全挂","load":1.0,"s":"pass"},
               {"n":"机房断电","load":0.0,"s":"fail"}]}""".trimIndent()
        "zx_tornado" -> """
            {"type":"zx_tornado","n":"利润敏感性","items":[
               {"n":"客单价","lo":-42,"hi":38},
               {"n":"转化率","lo":-28,"hi":31},
               {"n":"获客成本","lo":-19,"hi":16},
               {"n":"留存率","lo":-11,"hi":13}]}""".trimIndent()
        "zx_whatif" -> """
            {"type":"zx_whatif","n":"曝光到营收","nodes":[
               {"n":"曝光","v":1000,"k":1.0},
               {"n":"点击","v":120,"k":1.15},
               {"n":"加购","v":38,"k":1.30},
               {"n":"成交","v":12,"k":1.05}]}""".trimIndent()
        "zx_breakeven" -> """
            {"type":"zx_breakeven","n":"新品盈亏平衡","fixed":48000,"price":39,
             "vc":14,"maxq":4000,"q":1500}""".trimIndent()
        "zx_systemdyn" -> """
            {"type":"zx_systemdyn","n":"用户池演化","s0":1200,"in":180,"out":0.09,"steps":40}""".trimIndent()
        "zx_markov" -> """
            {"type":"zx_markov","n":"用户状态迁移","at":6,
             "states":["活跃","沉默","流失"],
             "p":[{"r":[0.82,0.15,0.03]},
                  {"r":[0.22,0.55,0.23]},
                  {"r":[0.04,0.06,0.90]}]}""".trimIndent()
        "zx_optpath" -> """
            {"type":"zx_optpath","n":"数据同步最优链路","src":0,"dst":7,
             "nodes":["源库","清洗","校验","缓存A","缓存B","队列","转换","目标"],
             "edges":[{"f":0,"t":1,"w":3},{"f":1,"t":2,"w":5},{"f":2,"t":5,"w":4},
                      {"f":0,"t":3,"w":8},{"f":3,"t":6,"w":5},{"f":5,"t":6,"w":2},
                      {"f":6,"t":7,"w":1},{"f":4,"t":6,"w":7},{"f":1,"t":4,"w":2}]}""".trimIndent()
        "zx_payoff" -> """
            {"type":"zx_payoff","n":"定价博弈","rows":["我方降价","我方维持"],
             "cols":["对手降价","对手维持"],
             "cells":[{"a":2,"b":2},{"a":8,"b":1},
                      {"a":1,"b":8},{"a":5,"b":5}]}""".trimIndent()
        "zx_reversesolve" -> """
            {"type":"zx_reversesolve","n":"要 5000 单需要多少曝光","target":5000,
             "a":0.42,"b":-180,"lo":0,"hi":30000}""".trimIndent()
        "zx_realoption" -> """
            {"type":"zx_realoption","n":"是否立刻上线","now":180,"later":205,
             "sigma":0.55,"cost":32}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_scenario" -> "scenarios[{n,v[]}] 2-5；**长度不齐会被拦**；悲观优于乐观会被拦"
        "zx_montecarlo" -> "n∈[200,20000] + mean + sd>0 + target? + seed?；**n<200 会被拦**"
        "zx_stress" -> "items[{n,load>0,s∈pass|warn|fail}] ≤8；**全通过会被拦**（压得不够）"
        "zx_tornado" -> "items[{n,lo,hi}] 2-10；**摆动全为 0 会被拦**（排不出序）"
        "zx_whatif" -> "nodes[{n,v>0,k>0}] 2-8；**k 未定义会被拦**"
        "zx_breakeven" -> "fixed>0 + price>0 + vc≥0 + maxq? + q?；**price≤vc 会被拦**"
        "zx_systemdyn" -> "s0≥0 + in≥0 + out∈[0,1] + steps∈[2,200] + cap?>0；**out>1 会被拦**"
        "zx_markov" -> "states[2-6] + p 方阵每行和=1 + at?；**非方阵/行和≠1 会被拦**"
        "zx_optpath" -> "nodes[2-12] + edges[{f,t,w>0}] ≤30 + src/dst；**不连通会被拦**"
        "zx_payoff" -> "rows[2] + cols[2] + cells[4]{a,b}；**非 2×2 会被拦**"
        "zx_reversesolve" -> "target + a≠0 + b + lo + hi>lo；**目标不在值域会被拦**（必须明说无解）"
        "zx_realoption" -> "now + later + sigma∈(0,1.5] + cost≥0；**sigma≤0 会被拦**"
        else -> "未注册组件：$component"
    }
}
