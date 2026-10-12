package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第三十二批组件（议价、竞标与序贯决策）的校验 + 示例 + 规格。
 *
 * 这批查的是**「决策做出来了，但它的前提在过程中会变」**。
 *
 * 前九档查的还是"给定信息下算得对不对"这个族：
 *
 *   画不出来 → 统计前提不成立 → 区间前提不成立 →
 *   因果前提无从判断 → 看起来确定了其实没那么确定 →
 *   状态摆出来了但推不出该做什么 → 记下来了但下次用不上 →
 *   数字都在但落不到谁该做什么 → 推演本身不可信
 *
 * 第三十一批把"没有数据"这个口子撕开了（模拟与推演），
 * 但它留了一条残留假设：**推演是一次性的**——我算出结果，然后照做。
 *
 * 真实决策不是这样。真实决策是**在过程中还能改**：
 *   谈判可以还价、拍卖可以加价、可以等下一个信息再定、
 *   可以先做一半看反应再决定剩下那一半。
 *
 * 于是出现一种新的错法：**把灵活性当成免费的**。
 *
 *   保留选择权 → 要放弃确定性
 *   放弃确定性 → 要承受别人先动的风险
 *   等到的信息 → 可能不值等待的代价
 *
 * 具体要拦的（与 ZxRender32 注释一一对应）：
 *   bargain       保留价格**不重叠**（无 ZOPA，必然谈不成，必须明说）
 *   auction       出价 < 保留价 / 提示赢者诅咒
 *   multiissue    双方权重**完全相同**（没有交换空间，退化成零和）
 *   pareto        只有一个目标（不是多目标）/ 点全在前沿（没有可剔除的）
 *   portfolio     权重和不为 1
 *   sequential    信息成本 > 信息价值（**不该等**）
 *   stoprule      n < 3（37% 规则不成立）
 *   hedge         对冲成本 ≥ 敞口（**对冲比不对冲还亏**）
 *   contingent    触发条件重叠 / 概率和 >1
 *   escalate      升级成本 > 问题全部损失（**永远不该升级**）
 *   commit        承诺无代价（**不可信**）
 *   bidcurve      供需无交点（**无均衡价**）
 */
object ZxDsl32 {

    val ALL = listOf(
        "zx_bargain", "zx_auction", "zx_multiissue",
        "zx_pfront", "zx_portfolio", "zx_bidcurve",
        "zx_sequential", "zx_stoprule", "zx_hedge",
        "zx_contingent", "zx_escalate", "zx_commit"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_bargain"     -> bargain(root)
        "zx_auction"     -> auction(root)
        "zx_multiissue"  -> multiissue(root)
        "zx_pfront"      -> pareto(root)
        "zx_portfolio"   -> portfolio(root)
        "zx_bidcurve"    -> bidcurve(root)
        "zx_sequential"  -> sequential(root)
        "zx_stoprule"    -> stoprule(root)
        "zx_hedge"       -> hedge(root)
        "zx_contingent"  -> contingent(root)
        "zx_escalate"    -> escalate(root)
        "zx_commit"      -> commit(root)
        else -> false to "未注册组件：$component"
    }

    private fun bargain(o: JSONObject): Pair<Boolean, String> {
        val myMax = o.optDouble("mymax", Double.NaN)
        if (myMax.isNaN()) return false to "zx_bargain 缺少 mymax（我方最高可接受）"
        val hisMin = o.optDouble("hismin", Double.NaN)
        if (hisMin.isNaN()) return false to "zx_bargain 缺少 hismin（对方最低可接受）"
        if (myMax <= 0 || hisMin <= 0)
            return false to "zx_bargain 的 mymax / hismin 必须为正"
        // 保留价格不重叠：无 ZOPA
        if (hisMin > myMax)
            return false to "zx_bargain 的 hismin=$hisMin **大于** mymax=$myMax——" +
                            "**没有成交区间（ZOPA 为空）**。再怎么谈也不会成，" +
                            "要么改预算要么换一家。只画一个空区间会让人以为还能谈"
        val lo = o.optDouble("lo", 0.0)
        val hi = o.optDouble("hi", max(myMax, hisMin) * 1.4)
        if (hi <= lo) return false to "zx_bargain 的 hi=$hi 必须大于 lo=$lo"
        val offer = o.optDouble("offer", (myMax + hisMin) / 2)
        if (offer < lo || offer > hi)
            return false to "zx_bargain 的 offer=$offer 超出显示量程 [$lo, $hi]"
        return true to ""
    }

    private fun auction(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("bids") ?: return false to "zx_auction 缺少 bids"
        val n = a.length()
        if (n < 2) return false to "zx_auction 至少 2 个竞标方，实际 $n 个——" +
                                   "只有 1 个那叫议价，用 zx_bargain"
        if (n > 8) return false to "zx_auction 最多 8 个竞标方，实际 $n 个"
        val names = HashSet<String>()
        for (k in 0 until n) {
            val b = a.optJSONObject(k)
                ?: return false to "bids[$k] 不是对象，应为 {\"n\":竞标方,\"v\":出价,\"e\":估值}"
            val nm = b.optString("n")
            if (nm.isBlank()) return false to "bids[$k] 缺少 n（竞标方名）"
            if (!names.add(nm)) return false to "bids[$k] 的竞标方名「$nm」重复"
            val v = b.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "bids[$k] 缺少 v（出价）"
            if (v < 0) return false to "bids[$k].v=$v 不能为负"
            val e = b.optDouble("e", Double.NaN)
            if (e.isNaN()) return false to "bids[$k] 缺少 e（该方对标的的真实估值）——" +
                                           "**没有估值就判不出赢者诅咒**，而那正是这个组件的价值"
        }
        val reserve = o.optDouble("reserve", 0.0)
        if (reserve < 0) return false to "zx_auction 的 reserve=$reserve 不能为负"
        val top = (0 until n).mapNotNull { a.optJSONObject(it)?.optDouble("v", 0.0) }.maxOrNull() ?: 0.0
        // 出价低于保留价：流标（不拦，但要提示）
        if (top < reserve)
            return false to "zx_auction 的最高出价 $top 低于保留价 $reserve——会**流标**。" +
                            "这不是错误，但请确认是不是想要这个效果；否则调低 reserve"
        return true to ""
    }

    private fun multiissue(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("issues") ?: return false to "zx_multiissue 缺少 issues"
        val n = a.length()
        if (n < 2) return false to "zx_multiissue 至少 2 个议题，实际 $n 个——" +
                                   "**只有 1 个议题就是零和的**，交换空间为零，用 zx_bargain"
        if (n > 8) return false to "zx_multiissue 最多 8 个议题，实际 $n 个"
        val names = HashSet<String>()
        var allSame = true
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "issues[$k] 不是对象，应为 {\"n\":议题,\"a\":A方权重,\"b\":B方权重}"
            val nm = it.optString("n")
            if (nm.isBlank()) return false to "issues[$k] 缺少 n（议题名）"
            if (!names.add(nm)) return false to "issues[$k] 的议题名「$nm」重复"
            val wa = it.optDouble("a", Double.NaN)
            val wb = it.optDouble("b", Double.NaN)
            if (wa.isNaN()) return false to "issues[$k] 缺少 a（A 方权重）"
            if (wb.isNaN()) return false to "issues[$k] 缺少 b（B 方权重）"
            if (wa <= 0 || wb <= 0)
                return false to "issues[$k] 的权重必须为正（a=$wa, b=$wb）——" +
                                "权重为 0 意味着这一项对某方完全无所谓，那它就不该出现在谈判里"
            if (abs(wa - wb) > 0.01) allSame = false
        }
        // 双方权重完全相同：没有交换空间
        if (allSame)
            return false to "zx_multiissue 所有议题上双方权重**完全相同**——" +
                            "**没有交换空间**，退化成零和博弈。" +
                            "多议题谈判的全部价值就在于双方权重不同，" +
                            "权重相同还硬要谈，只会让双方都觉得在让步"
        return true to ""
    }

    private fun pareto(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("pts") ?: return false to "zx_pareto 缺少 pts"
        val n = a.length()
        if (n < 3) return false to "zx_pareto 至少 3 个点，实际 $n 个——" +
                                   "2 个点谈不出支配关系"
        if (n > 20) return false to "zx_pareto 最多 20 个点，实际 $n 个"
        val names = HashSet<String>()
        val pts = ArrayList<Pair<Double, Double>>()
        for (k in 0 until n) {
            val p = a.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"n\":方案,\"x\":维度一,\"y\":维度二}"
            val nm = p.optString("n")
            if (nm.isBlank()) return false to "pts[$k] 缺少 n（方案名）"
            if (!names.add(nm)) return false to "pts[$k] 的方案名「$nm」重复"
            val x = p.optDouble("x", Double.NaN)
            val y = p.optDouble("y", Double.NaN)
            if (x.isNaN()) return false to "pts[$k] 缺少 x"
            if (y.isNaN()) return false to "pts[$k] 缺少 y"
            pts.add(x to y)
        }
        // 只有一个目标：不是多目标
        val allX = pts.all { abs(it.first - pts[0].first) < 1e-9 }
        val allY = pts.all { abs(it.second - pts[0].second) < 1e-9 }
        if (allX || allY)
            return false to "zx_pareto 所有点在一个维度上完全相同——**那不是多目标**，" +
                            "退化成单目标排序了。用 zx_decision 更合适"
        fun dominated(i: Int): Boolean = pts.indices.any { j ->
            j != i && pts[j].first >= pts[i].first && pts[j].second >= pts[i].second &&
            (pts[j].first > pts[i].first || pts[j].second > pts[i].second) }
        // 点全在前沿：没有可剔除的
        if (pts.indices.none { dominated(it) })
            return false to "zx_pareto 的 $n 个点**没有任何一个被支配**——" +
                            "前沿就是全部，这个组件帮不上忙。" +
                            "它的价值恰恰在于剔除坏选项，一个都剔不掉就退化成散点图了"
        return true to ""
    }

    private fun portfolio(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items") ?: return false to "zx_portfolio 缺少 items"
        val n = a.length()
        if (n < 2) return false to "zx_portfolio 至少 2 个资产，实际 $n 个——" +
                                   "只有 1 个就谈不上分散化，而分散化正是这个组件的全部意义"
        if (n > 8) return false to "zx_portfolio 最多 8 个资产，实际 $n 个"
        val names = HashSet<String>()
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":资产,\"r\":预期收益%,\"s\":波动率%}"
            val nm = it.optString("n")
            if (nm.isBlank()) return false to "items[$k] 缺少 n（资产名）"
            if (!names.add(nm)) return false to "items[$k] 的资产名「$nm」重复"
            val r = it.optDouble("r", Double.NaN)
            if (r.isNaN()) return false to "items[$k] 缺少 r（预期收益%）"
            val s = it.optDouble("s", Double.NaN)
            if (s.isNaN()) return false to "items[$k] 缺少 s（波动率%）"
            if (s < 0) return false to "items[$k].s=$s 不能为负"
            if (s == 0.0)
                return false to "items[$k].s=0——**无风险资产会吃掉整个组合的风险项**，" +
                                "组合优化的前提是每个资产都有不确定性"
        }
        val rho = o.optDouble("rho", 0.3)
        if (rho < -1 || rho > 1) return false to "zx_portfolio 的 rho=$rho 超出 [-1, 1]"
        // 权重和不为 1
        val w = o.optJSONArray("w")
        if (w != null) {
            if (w.length() != n)
                return false to "zx_portfolio 的 w 有 ${w.length()} 个，但资产有 $n 个"
            var sum = 0.0
            for (k in 0 until n) {
                val v = w.optDouble(k, Double.NaN)
                if (v.isNaN()) return false to "w[$k] 不是数值"
                if (v < 0) return false to "w[$k]=$v 不能为负——**不允许做空**（会改变组合语义）"
                sum += v
            }
            if (abs(sum - 1.0) > 0.01)
                return false to "zx_portfolio 的权重和是 ${"%.3f".format(sum)} ≠ 1——" +
                                "**权重和不为 1，组合就没有意义**。" +
                                "差的部分意味着有一笔钱不知道去哪了"
        }
        return true to ""
    }

    private fun bidcurve(o: JSONObject): Pair<Boolean, String> {
        val d = o.optJSONArray("demand") ?: return false to "zx_bidcurve 缺少 demand"
        val s = o.optJSONArray("supply") ?: return false to "zx_bidcurve 缺少 supply"
        val p = o.optJSONArray("prices") ?: return false to "zx_bidcurve 缺少 prices"
        val n = minOf(d.length(), s.length(), p.length())
        if (n < 3) return false to "zx_bidcurve 至少 3 个价位，实际 $n 个——" +
                                   "2 个点画不出曲线"
        if (d.length() != s.length() || d.length() != p.length())
            return false to "zx_bidcurve 的 demand(${d.length()}) / supply(${s.length()}) / " +
                            "prices(${p.length()}) **长度必须一致**"
        var prevP = Double.NEGATIVE_INFINITY
        for (k in 0 until n) {
            val pk = p.optDouble(k, Double.NaN)
            if (pk.isNaN()) return false to "prices[$k] 不是数值"
            if (pk <= prevP)
                return false to "prices[$k]=$pk 必须**严格递增**（前一个 $prevP）——" +
                                "价格轴乱序会让曲线回头"
            prevP = pk
            val dk = d.optDouble(k, Double.NaN)
            val sk = s.optDouble(k, Double.NaN)
            if (dk.isNaN()) return false to "demand[$k] 不是数值"
            if (sk.isNaN()) return false to "supply[$k] 不是数值"
            if (dk < 0 || sk < 0) return false to "demand/supply[$k] 不能为负"
        }
        // 需求随价格递减、供给随价格递增（经济常识）
        var monoD = true; var monoS = true
        for (k in 1 until n) {
            if (d.optDouble(k, 0.0) > d.optDouble(k - 1, 0.0) + 1e-9) monoD = false
            if (s.optDouble(k, 0.0) < s.optDouble(k - 1, 0.0) - 1e-9) monoS = false
        }
        if (!monoD) return false to "zx_bidcurve 的 demand **没有随价格递减**——" +
                                    "价格越高需求越多，这不是需求曲线"
        if (!monoS) return false to "zx_bidcurve 的 supply **没有随价格递增**——" +
                                    "价格越高供给越少，这不是供给曲线"
        // 供需无交点：无均衡价
        var cross = false
        for (k in 0 until n) if (s.optDouble(k, 0.0) >= d.optDouble(k, 0.0)) { cross = true; break }
        if (!cross)
            return false to "zx_bidcurve 在**任何价位供给都小于需求**——**没有均衡价**。" +
                            "这不是「价格不够高」的问题，是这个市场在当前条件下不成立。" +
                            "画一条不相交的曲线会让人以为调价就能解决"
        return true to ""
    }

    private fun sequential(o: JSONObject): Pair<Boolean, String> {
        val now = o.optDouble("now", Double.NaN)
        if (now.isNaN()) return false to "zx_sequential 缺少 now（现在就定的期望价值）"
        val cost = o.optDouble("cost", Double.NaN)
        if (cost.isNaN()) return false to "zx_sequential 缺少 cost（等待/获取信息的成本）"
        if (cost < 0) return false to "zx_sequential 的 cost=$cost 不能为负"
        val gain = o.optDouble("gain", Double.NaN)
        if (gain.isNaN()) return false to "zx_sequential 缺少 gain（信息带来的价值增量）"
        if (gain <= 0)
            return false to "zx_sequential 的 gain=$gain 必须为正——" +
                            "**信息不带来任何增量，那等它就纯是浪费**"
        val p = o.optDouble("p", 0.5)
        if (p < 0 || p > 1) return false to "zx_sequential 的 p=$p 超出 0-1"
        // 信息成本 > 信息价值：不该等
        val value = p * gain
        if (cost > value)
            return false to "zx_sequential 的信息成本 $cost **大于**期望信息价值 " +
                            "${"%.2f".format(value)}（$p × $gain）——**不该等**。" +
                            "「有信息总比没有好」是错的：如果不管信息说什么你都做同一件事，" +
                            "那信息一文不值。请把 cost 降到 ${"%.2f".format(value)} 以下"
        return true to ""
    }

    private fun stoprule(o: JSONObject): Pair<Boolean, String> {
        val n = o.optInt("n", 20)
        // n < 3：37% 规则不成立
        if (n < 3)
            return false to "zx_stoprule 的 n=$n 少于 3——**37% 规则不成立**。" +
                            "候选太少时「看前 1/e 建立标准」这个策略没有意义，" +
                            "直接全看完再选就行"
        if (n > 200) return false to "zx_stoprule 最多 200 个候选，实际 $n 个"
        val at = o.optInt("at", 0)
        if (at < 0 || at > n) return false to "zx_stoprule 的 at=$at 超出 0-$n"
        val best = o.optDouble("best", 0.0)
        if (best < 0) return false to "zx_stoprule 的 best=$best 不能为负"
        return true to ""
    }

    private fun hedge(o: JSONObject): Pair<Boolean, String> {
        val exposure = o.optDouble("exposure", Double.NaN)
        if (exposure.isNaN()) return false to "zx_hedge 缺少 exposure（敞口）"
        if (exposure <= 0) return false to "zx_hedge 的 exposure=$exposure 必须为正"
        val p = o.optDouble("p", Double.NaN)
        if (p.isNaN()) return false to "zx_hedge 缺少 p（不利发生的概率）"
        if (p <= 0 || p > 1) return false to "zx_hedge 的 p=$p 必须在 (0, 1]"
        val loss = o.optDouble("loss", Double.NaN)
        if (loss.isNaN()) return false to "zx_hedge 缺少 loss（发生时的损失）"
        if (loss <= 0) return false to "zx_hedge 的 loss=$loss 必须为正"
        val cost = o.optDouble("cost", Double.NaN)
        if (cost.isNaN()) return false to "zx_hedge 缺少 cost（对冲成本）"
        if (cost < 0) return false to "zx_hedge 的 cost=$cost 不能为负"
        // 对冲成本 ≥ 期望损失：比不对冲还亏
        val expected = p * loss
        if (cost >= expected)
            return false to "zx_hedge 的对冲成本 $cost **≥ 期望损失 " +
                            "${"%.2f".format(expected)}**（$p × $loss）——" +
                            "**对冲比不对冲还亏**。「买个安心」不是理由，那是有价格的。" +
                            "请把 cost 降到 ${"%.2f".format(expected)} 以下"
        val ratio = o.optDouble("ratio", 1.0)
        if (ratio < 0 || ratio > 1) return false to "zx_hedge 的 ratio=$ratio 超出 0-1"
        return true to ""
    }

    private fun contingent(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("plans") ?: return false to "zx_contingent 缺少 plans"
        val n = a.length()
        if (n == 0) return false to "zx_contingent 的 plans 为空"
        if (n > 8) return false to "zx_contingent 最多 8 条预案，实际 $n 条"
        val conds = HashSet<String>()
        var sum = 0.0
        for (k in 0 until n) {
            val p = a.optJSONObject(k)
                ?: return false to "plans[$k] 不是对象，应为 {\"if\":条件,\"then\":动作,\"p\":概率}"
            val c = p.optString("if")
            if (c.isBlank()) return false to "plans[$k] 缺少 if（触发条件）"
            // 条件重叠：两个预案同时触发，不知道执行哪个
            if (!conds.add(c))
                return false to "plans[$k] 的条件「$c」与其他条**重复**——" +
                                "两个预案触发条件相同，真发生时不知道该执行哪个"
            val t = p.optString("then")
            if (t.isBlank())
                return false to "plans[$k] 缺少 then（触发后的动作）——**只有条件没有动作，" +
                                "那不叫预案，那叫担忧**"
            val pr = p.optDouble("p", Double.NaN)
            if (pr.isNaN()) return false to "plans[$k] 缺少 p（该情景概率）"
            if (pr <= 0 || pr > 1) return false to "plans[$k].p=$pr 超出 (0, 1]"
            sum += pr
        }
        // 概率和 > 1
        if (sum > 1.001)
            return false to "zx_contingent 的概率和是 ${"%.3f".format(sum)} > 1——" +
                            "**情景之间不是互斥的**。互斥情景的概率必须加起来不超过 1，" +
                            "超了说明有的情景被算了两次"
        return true to ""
    }

    private fun escalate(o: JSONObject): Pair<Boolean, String> {
        val loss = o.optDouble("loss", Double.NaN)
        if (loss.isNaN()) return false to "zx_escalate 缺少 loss（不升级时每小时损失）"
        if (loss <= 0) return false to "zx_escalate 的 loss=$loss 必须为正"
        val cost = o.optDouble("cost", Double.NaN)
        if (cost.isNaN()) return false to "zx_escalate 缺少 cost（升级的固定成本）"
        if (cost < 0) return false to "zx_escalate 的 cost=$cost 不能为负"
        val pSelf = o.optDouble("p", Double.NaN)
        if (pSelf.isNaN()) return false to "zx_escalate 缺少 p（自己能解决的概率）"
        if (pSelf < 0 || pSelf > 1) return false to "zx_escalate 的 p=$pSelf 超出 0-1"
        val horizon = o.optDouble("horizon", Double.NaN)
        if (horizon.isNaN()) return false to "zx_escalate 缺少 horizon（最长可拖时间）"
        if (horizon <= 0) return false to "zx_escalate 的 horizon=$horizon 必须为正"
        val at = o.optDouble("at", 0.0)
        if (at < 0 || at > horizon) return false to "zx_escalate 的 at=$at 超出 0-$horizon"
        // 升级成本 > 全部损失：永远不该升级
        if (cost > horizon * loss)
            return false to "zx_escalate 的升级成本 $cost **超过**问题本身全部损失 " +
                            "${"%.2f".format(horizon * loss)}（$horizon × $loss）——" +
                            "**这种事永远不该升级**，忍过去更划算。" +
                            "这个结论应该明确给出，而不是画两条线让人自己看"
        return true to ""
    }

    private fun commit(o: JSONObject): Pair<Boolean, String> {
        val claim = o.optString("claim")
        if (claim.isBlank())
            return false to "zx_commit 缺少 claim（承诺的内容）——**没有内容的承诺不用评估可信度**"
        val benefit = o.optDouble("benefit", Double.NaN)
        if (benefit.isNaN()) return false to "zx_commit 缺少 benefit（违约能省下的）"
        if (benefit <= 0)
            return false to "zx_commit 的 benefit=$benefit 必须为正——" +
                            "违约没有任何好处，那承诺自然可信，不需要这个组件"
        val cost = o.optDouble("cost", Double.NaN)
        if (cost.isNaN()) return false to "zx_commit 缺少 cost（坚持承诺要付的代价）"
        if (cost < 0) return false to "zx_commit 的 cost=$cost 不能为负"
        // 承诺无代价：不可信
        if (cost <= 0)
            return false to "zx_commit 的 cost=0——**零代价的承诺不可信**。" +
                            "违约没有任何成本，对方凭什么信？" +
                            "烧掉退路 / 公开表态 / 付定金，都是在人为制造违约成本"
        val hisCost = o.optDouble("hiscost", 0.0)
        if (hisCost < 0) return false to "zx_commit 的 hiscost=$hisCost 不能为负"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_bargain" -> """
            {"type":"zx_bargain","n":"供应商报价","mymax":85,"hismin":62,
             "lo":50,"hi":110,"offer":74}""".trimIndent()
        "zx_auction" -> """
            {"type":"zx_auction","n":"广告位竞标","reserve":40,
             "bids":[{"n":"甲方","v":92,"e":78},{"n":"乙方","v":85,"e":88},
                     {"n":"丙方","v":71,"e":74}]}""".trimIndent()
        "zx_multiissue" -> """
            {"type":"zx_multiissue","n":"合同条款","issues":[
               {"n":"价格","a":3,"b":1},{"n":"交付期","a":1,"b":3},
               {"n":"质保","a":2,"b":1},{"n":"付款方式","a":1,"b":2}]}""".trimIndent()
        "zx_pfront" -> """
            {"type":"zx_pfront","n":"方案前沿","xname":"成本(万)","yname":"效果",
             "pts":[{"n":"A","x":30,"y":55},{"n":"B","x":45,"y":78},
                    {"n":"C","x":60,"y":72},{"n":"D","x":38,"y":48},
                    {"n":"E","x":52,"y":85}]}""".trimIndent()
        "zx_portfolio" -> """
            {"type":"zx_portfolio","n":"渠道预算组合","rho":0.25,
             "items":[{"n":"搜索","r":12,"s":18},{"n":"信息流","r":20,"s":32},
                      {"n":"私域","r":8,"s":9}]}""".trimIndent()
        "zx_bidcurve" -> """
            {"type":"zx_bidcurve","n":"算力竞价",
             "prices":[10,20,30,40,50],
             "demand":[180,140,105,70,40],
             "supply":[30,75,120,165,205]}""".trimIndent()
        "zx_sequential" -> """
            {"type":"zx_sequential","n":"要不要等灰度数据","now":180,"cost":22,
             "gain":95,"p":0.55}""".trimIndent()
        "zx_stoprule" -> """
            {"type":"zx_stoprule","n":"候选人筛选","n":20,"at":9,"best":7.5,"explore":8.2}""".trimIndent()
        "zx_hedge" -> """
            {"type":"zx_hedge","n":"汇率对冲","exposure":480,"p":0.25,
             "loss":95,"cost":14,"ratio":0.8}""".trimIndent()
        "zx_contingent" -> """
            {"type":"zx_contingent","n":"大促预案","plans":[
               {"if":"流量超预期 2 倍","then":"切只读+限流","p":0.18},
               {"if":"支付通道故障","then":"切备用通道","p":0.12},
               {"if":"库存扣减异常","then":"关秒杀入口","p":0.08}]}""".trimIndent()
        "zx_escalate" -> """
            {"type":"zx_escalate","n":"要不要叫架构师","loss":38,"cost":45,
             "p":0.45,"horizon":8,"at":3}""".trimIndent()
        "zx_commit" -> """
            {"type":"zx_commit","n":"最低价承诺","claim":"我们不会低于这个价",
             "benefit":120,"cost":85,"hiscost":60}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_bargain" -> "mymax>0 + hismin>0（**hismin>mymax 会被拦**：无 ZOPA）+ lo/hi? + offer?"
        "zx_auction" -> "bids[{n,v≥0,e}] 2-8 + reserve?；**缺 e 判不出赢者诅咒**；**最高出价<reserve 会被拦**"
        "zx_multiissue" -> "issues[{n,a>0,b>0}] 2-8；**双方权重全同会被拦**（退化零和）"
        "zx_pfront" -> "pts[{n,x,y}] 3-20 + xname?/yname?；**单维度会被拦**；**无被支配点会被拦**"
        "zx_portfolio" -> "items[{n,r,s>0}] 2-8 + rho∈[-1,1] + w?；**权重和≠1 会被拦**"
        "zx_bidcurve" -> "prices/demand/supply 等长≥3，价格递增、需求递减、供给递增；**无交点会被拦**"
        "zx_sequential" -> "now + cost≥0 + gain>0 + p∈[0,1]；**cost>p×gain 会被拦**（不该等）"
        "zx_stoprule" -> "n∈[3,200] + at? + best?；**n<3 会被拦**（37% 规则不成立）"
        "zx_hedge" -> "exposure>0 + p∈(0,1] + loss>0 + cost≥0 + ratio∈[0,1]；**cost≥p×loss 会被拦**"
        "zx_contingent" -> "plans[{if,then,p∈(0,1]}] ≤8；**条件重复会被拦**；**概率和>1 会被拦**"
        "zx_escalate" -> "loss>0 + cost≥0 + p∈[0,1] + horizon>0 + at?；**cost>horizon×loss 会被拦**"
        "zx_commit" -> "claim 非空 + benefit>0 + cost>0 + hiscost?；**cost=0 会被拦**（不可信）"
        else -> "未注册组件：$component"
    }
}
