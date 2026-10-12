package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第二十四批组件（统计推断与一致性）的校验 + 示例 + 规格。
 *
 * 这批查的重点是**「算不出判据」**。
 *
 * 前二十三批大多查"画不出来"（错误）、
 * "画出来但形状/结论是错的"（误导）、
 * "看起来完整但推不出结论"（无解）。
 *
 * 这批要拦的是**"画得出来，但给不出判据"**：
 *
 *   · QQ 图样本 < 3 → 分位数无从谈起，偏离带算不出来
 *   · 森林图有研究缺 ci → 权重无从计算，合并效应量是假的
 *   · Bland-Altman 两组长度不等 → 没法逐对做差
 *   · 生存曲线 t 递减 → 风险集只增不减，KM 递推失效
 *   · 生存曲线某时刻存活数超过起点 → 不可能
 *   · 洛伦兹份额未升序 → 曲线会回头，基尼算错
 *   · 洛伦兹全为 0 → 基尼是 0/0
 *   · 回归 x 全相同 → 斜率无定义
 *   · 公差带 usl ≤ lsl → 规格区间为空
 *   · 公差带数据为空 → σ 算不出来
 *   · 序列片段对比两段都为空 → 无从对比
 *   · 命令面板无命令 → 面板是空的
 *   · 区间标注 s ≥ e → 长度为负
 *   · 区间标注区间重叠 → 同一段被标两次，归属不明
 *   · 散点少于 2 点 → 趋势无从谈起
 *   · ECDF 数据为空 → 分位读不出来
 *
 * 共同点：这些都是**不会崩**的。
 *
 * 前几批拦的是"渲染错位"，这批拦的是"**统计前提不成立**"——
 * 前提不成立时，图照样画得出来、看起来也正常，
 * 但基于它得出的结论是错的。
 *
 * **图只能"看起来像"，判据才能"下结论"。**
 * 判据建立在前提之上，前提错了判据就是编的。
 */
object ZxDsl24 {

    val ALL = listOf(
        "zx_qq", "zx_forest", "zx_bland", "zx_survival",
        "zx_ecdf", "zx_lorenz", "zx_regress", "zx_scatter",
        "zx_seqdiff", "zx_cmdpal", "zx_span", "zx_tolerance"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_qq"        -> qq(root)
        "zx_forest"    -> forest(root)
        "zx_bland"     -> bland(root)
        "zx_survival"  -> survival(root)
        "zx_ecdf"      -> ecdf(root)
        "zx_lorenz"    -> lorenz(root)
        "zx_regress"   -> regress(root)
        "zx_scatter"   -> scatter(root)
        "zx_seqdiff"   -> seqdiff(root)
        "zx_cmdpal"    -> cmdpal(root)
        "zx_span"      -> span(root)
        "zx_tolerance" -> tolerance(root)
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

    private fun qq(o: JSONObject): Pair<Boolean, String> {
        val (v, e) = nums(o, "data", 3, 40)
        // 样本 < 3：分位数无从谈起，偏离带算不出来
        if (v == null) return false to "zx_qq $e（分位数至少需要 3 个样本，偏离带才成立）"
        if (v.all { it == v[0] }) return false to "zx_qq 的 data 全相同——σ=0，QQ 图没有意义"
        return true to ""
    }

    private fun forest(o: JSONObject): Pair<Boolean, String> {
        val studies = o.optJSONArray("studies")
            ?: return false to "zx_forest 缺少 studies"
        val n = studies.length()
        if (n < 2) return false to "zx_forest 至少 2 个研究，实际 $n 个——单个研究不需要合并"
        if (n > 8) return false to "zx_forest 最多 8 个研究，实际 $n 个"
        for (i in 0 until n) {
            val s = studies.optJSONObject(i)
                ?: return false to "studies[$i] 不是对象，应为 {\"n\":研究名,\"e\":效应量,\"lo\":下限,\"hi\":上限}"
            if (s.optString("n").isBlank()) return false to "studies[$i] 缺少 n（研究名）"
            val e = s.optDouble("e", Double.NaN)
            if (e.isNaN()) return false to "studies[$i] 缺少 e（效应量）"
            val lo = s.optDouble("lo", Double.NaN)
            val hi = s.optDouble("hi", Double.NaN)
            // 缺 ci：逆方差权重无从计算，合并效应量是假的
            if (lo.isNaN() || hi.isNaN())
                return false to "studies[$i] 缺少 lo/hi（置信区间）——" +
                                "权重按 1/se² 算，没有 ci 就无从计算，合并效应量会是假的"
            if (lo > hi)
                return false to "studies[$i] 的 lo=$lo 大于 hi=$hi——置信区间是反的"
            if (e < lo || e > hi)
                return false to "studies[$i].e=$e 落在置信区间 [$lo,$hi] 之外——效应量应在区间内"
        }
        return true to ""
    }

    private fun bland(o: JSONObject): Pair<Boolean, String> {
        val (a, ea) = nums(o, "a", 3, 30)
        if (a == null) return false to "zx_bland $ea"
        val (b, eb) = nums(o, "b", 3, 30)
        if (b == null) return false to "zx_bland $eb"
        // 长度不等：没法逐对做差
        if (a.size != b.size)
            return false to "zx_bland 的 a 有 ${a.size} 个、b 有 ${b.size} 个——" +
                            "长度不等没法逐对做差，一致性界限是算不出来的"
        if (a.indices.all { a[it] == b[it] })
            return false to "两组完全相同——差恒为 0，一致性界限宽度为 0，这个对比没有信息"
        return true to ""
    }

    private fun survival(o: JSONObject): Pair<Boolean, String> {
        val events = o.optJSONArray("events")
            ?: return false to "zx_survival 缺少 events"
        val n = events.length()
        if (n == 0) return false to "zx_survival 的 events 为空"
        if (n > 14) return false to "zx_survival 最多 14 个时间点，实际 $n 个"
        val n0 = o.optInt("n0", -1)
        if (n0 < 1) return false to "zx_survival 的 n0=$n0 至少为 1（起始风险人数）"
        var prevT = -1.0
        var risk = n0
        for (i in 0 until n) {
            val e = events.optJSONObject(i)
                ?: return false to "events[$i] 不是对象，应为 {\"t\":时间,\"d\":事件数,\"c\":截尾数}"
            val t = e.optDouble("t", Double.NaN)
            if (t.isNaN()) return false to "events[$i] 缺少 t（时间点）"
            if (t < 0) return false to "events[$i].t=$t 不能为负"
            // t 递减：KM 递推依赖时间有序
            if (t <= prevT)
                return false to "events[$i].t=$t 不比上一个时间点（$prevT）大——" +
                                "KM 要求时间递增，风险集只增不减，顺序错了递推就失效"
            prevT = t
            val d = e.optInt("d", -1)
            val c = e.optInt("c", -1)
            if (d < 0 || c < 0) return false to "events[$i] 的 d / c 不能为负"
            if (d + c == 0) return false to "events[$i] 的 d 和 c 都是 0——这个时间点没有信息"
            // 存活数超过起点：不可能
            if (d + c > risk)
                return false to "events[$i] 的 d+c=${d + c} 超过剩余风险人数 $risk——不可能"
            risk -= (d + c)
        }
        return true to ""
    }

    private fun ecdf(o: JSONObject): Pair<Boolean, String> {
        val (v, e) = nums(o, "data", 2, 40)
        if (v == null) return false to "zx_ecdf $e（至少 2 个点才能读出累计比例）"
        val marks = o.optJSONArray("marks")
        for (k in 0 until (marks?.length() ?: 0)) {
            val q = marks!!.optDouble(k, Double.NaN)
            if (q.isNaN()) return false to "marks[$k] 不是数值"
            if (q <= 0 || q >= 1)
                return false to "marks[$k]=$q 超出 0-1（分位数必须是 0 到 1 之间的比例）"
        }
        return true to ""
    }

    private fun lorenz(o: JSONObject): Pair<Boolean, String> {
        val (v, e) = nums(o, "shares", 2, 12)
        if (v == null) return false to "zx_lorenz $e"
        if (v.all { it == 0.0 })
            return false to "zx_lorenz 的 shares 全为 0——总量为 0，基尼是 0/0"
        if (v.any { it < 0 })
            return false to "zx_lorenz 的 shares 有负值——份额不能为负"
        // 未升序：曲线会回头，基尼算错
        for (k in 1 until v.size)
            if (v[k] < v[k - 1])
                return false to "shares[$k]=${v[k]} 小于 shares[${k - 1}]=${v[k - 1]}——" +
                                "份额必须升序，否则曲线会回头，基尼算出来是错的"
        return true to ""
    }

    private fun regress(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_regress 缺少 pts"
        val n = pts.length()
        if (n < 3) return false to "zx_regress 至少 3 个点，实际 $n 个——2 点拟合没有残差可言"
        if (n > 24) return false to "zx_regress 最多 24 个点，实际 $n 个"
        val xs = HashSet<Double>()
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"x\":,\"y\":}"
            val x = p.optDouble("x", Double.NaN)
            val y = p.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) return false to "pts[$k] 缺少 x 或 y"
            xs.add(x)
        }
        // x 全相同：斜率无定义
        if (xs.size == 1)
            return false to "所有点的 x 都相同——斜率无定义（分母为 0），退不出回归线"
        return true to ""
    }

    private fun scatter(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_scatter 缺少 pts"
        val n = pts.length()
        if (n < 2) return false to "zx_scatter 至少 2 个点，实际 $n 个——1 个点看不出趋势与离群"
        if (n > 40) return false to "zx_scatter 最多 40 个点，实际 $n 个"
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"x\":,\"y\":,\"n\":组名?}"
            if (p.optDouble("x", Double.NaN).isNaN() ||
                p.optDouble("y", Double.NaN).isNaN())
                return false to "pts[$k] 缺少 x 或 y"
        }
        return true to ""
    }

    private fun seqdiff(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("a") ?: return false to "zx_seqdiff 缺少 a"
        val b = o.optJSONArray("b") ?: return false to "zx_seqdiff 缺少 b"
        // 两段都为空：无从对比
        if (a.length() == 0 && b.length() == 0)
            return false to "zx_seqdiff 的 a 和 b 都为空——没有可对比的内容"
        val n = maxOf(a.length(), b.length())
        if (n > 40) return false to "zx_seqdiff 最多 40 项，实际 $n 项"
        for (k in 0 until a.length())
            if (a.optString(k, null) == null) return false to "a[$k] 不是字符串"
        for (k in 0 until b.length())
            if (b.optString(k, null) == null) return false to "b[$k] 不是字符串"
        return true to ""
    }

    private fun cmdpal(o: JSONObject): Pair<Boolean, String> {
        val cmds = o.optJSONArray("cmds")
            ?: return false to "zx_cmdpal 缺少 cmds"
        val n = cmds.length()
        // 无命令：面板是空的
        if (n == 0) return false to "zx_cmdpal 的 cmds 为空——面板是空的，什么也选不了"
        if (n > 12) return false to "zx_cmdpal 最多 12 条命令，实际 $n 条"
        val seen = HashSet<String>()
        for (k in 0 until n) {
            val c = cmds.optJSONObject(k)
                ?: return false to "cmds[$k] 不是对象，应为 {\"n\":命令名,\"k\":快捷键?,\"used\":次数?}"
            val nm = c.optString("n", "")
            if (nm.isBlank()) return false to "cmds[$k] 缺少 n（命令名）"
            // 重名：选中了不知道执行的是哪个
            if (!seen.add(nm))
                return false to "命令名「$nm」重复——选中了不知道执行的是哪一个"
            if (c.optInt("used", 0) < 0) return false to "cmds[$k].used 不能为负"
        }
        return true to ""
    }

    private fun span(o: JSONObject): Pair<Boolean, String> {
        val total = o.optDouble("total", Double.NaN)
        if (total.isNaN()) return false to "zx_span 缺少 total（轴的总长）"
        if (total <= 0) return false to "zx_span 的 total=$total 必须为正"
        val spans = o.optJSONArray("spans") ?: return false to "zx_span 缺少 spans"
        val n = spans.length()
        if (n == 0) return false to "zx_span 的 spans 为空"
        if (n > 8) return false to "zx_span 最多 8 段，实际 $n 段"
        val items = ArrayList<Triple<String, Double, Double>>()
        for (k in 0 until n) {
            val s = spans.optJSONObject(k)
                ?: return false to "spans[$k] 不是对象，应为 {\"n\":段名,\"s\":起,\"e\":止}"
            val nm = s.optString("n", "")
            if (nm.isBlank()) return false to "spans[$k] 缺少 n（段名）"
            val a = s.optDouble("s", Double.NaN)
            val b = s.optDouble("e", Double.NaN)
            if (a.isNaN() || b.isNaN()) return false to "spans[$k] 缺少 s 或 e"
            // s ≥ e：长度为负，条会画反
            if (a >= b)
                return false to "spans[$k] 的 s=$a 不小于 e=$b——长度为负，条会画反"
            if (a < 0 || b > total)
                return false to "spans[$k]=[$a,$b] 超出轴的范围 [0,$total]"
            items.add(Triple(nm, a, b))
        }
        // 重叠：同一段被标两次，归属不明
        val sorted = items.sortedBy { it.second }
        for (k in 1 until sorted.size)
            if (sorted[k].second < sorted[k - 1].third)
                return false to "「${sorted[k - 1].first}」和「${sorted[k].first}」重叠——" +
                                "同一段被标了两次，点上去不知道选中哪一个"
        return true to ""
    }

    private fun tolerance(o: JSONObject): Pair<Boolean, String> {
        val lsl = o.optDouble("lsl", Double.NaN)
        val usl = o.optDouble("usl", Double.NaN)
        if (lsl.isNaN() || usl.isNaN())
            return false to "zx_tolerance 需要 lsl（规格下限）和 usl（规格上限）"
        // usl ≤ lsl：规格区间为空，Cp 是负的
        if (usl <= lsl)
            return false to "zx_tolerance 的 usl=$usl 不大于 lsl=$lsl——" +
                            "规格区间为空，Cp 会是负的"
        val (v, e) = nums(o, "data", 3, 36)
        if (v == null) return false to "zx_tolerance $e（σ 至少需要 3 个样本才站得住）"
        if (v.all { it == v[0] })
            return false to "zx_tolerance 的 data 全相同——σ=0，Cp 会是无穷大"
        val target = o.optDouble("target", (lsl + usl) / 2)
        if (target < lsl || target > usl)
            return false to "zx_tolerance 的 target=$target 落在规格区间 [$lsl,$usl] 之外——" +
                            "目标值应在规格内"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_qq" -> """
            {"type":"zx_qq","n":"响应耗时正态性",
             "data":[98,102,105,110,97,101,99,103,108,96,
                     104,100,107,95,109,102,98,106,101,103,
                     99,105,100,102,97,104,108,96,101,103]}""".trimIndent()
        "zx_forest" -> """
            {"type":"zx_forest","title":"三个研究的效应量","null":0,
             "studies":[
               {"n":"研究A","e":0.42,"lo":0.18,"hi":0.66},
               {"n":"研究B","e":0.31,"lo":0.05,"hi":0.57},
               {"n":"研究C","e":0.55,"lo":0.30,"hi":0.80}]}""".trimIndent()
        "zx_bland" -> """
            {"type":"zx_bland","n":"两种测压法一致性","a":[120,130,140,150,160,170],
             "b":[118,127,141,146,163,166]}""".trimIndent()
        "zx_survival" -> """
            {"type":"zx_survival","title":"留存","n0":100,"events":[
               {"t":1,"d":8,"c":2},{"t":3,"d":12,"c":5},
               {"t":6,"d":9,"c":1},{"t":12,"d":6,"c":8}]}""".trimIndent()
        "zx_ecdf" -> """
            {"type":"zx_ecdf","n":"首屏耗时分布","marks":[0.5,0.9],
             "data":[820,910,1050,1120,980,1240,760,1310,1090,940]}""".trimIndent()
        "zx_lorenz" -> """
            {"type":"zx_lorenz","n":"订单集中度",
             "shares":[5,9,14,22,38,64,110]}""".trimIndent()
        "zx_regress" -> """
            {"type":"zx_regress","n":"投放与转化","pts":[
               {"x":10,"y":22},{"x":20,"y":38},{"x":30,"y":55},
               {"x":40,"y":68},{"x":50,"y":91},{"x":60,"y":102}]}""".trimIndent()
        "zx_scatter" -> """
            {"type":"zx_scatter","title":"机型与耗电","pts":[
               {"x":2.1,"y":31,"n":"A"},{"x":3.4,"y":48,"n":"A"},
               {"x":2.8,"y":39,"n":"B"},{"x":4.2,"y":62,"n":"B"}]}""".trimIndent()
        "zx_seqdiff" -> """
            {"type":"zx_seqdiff","n":"两版 token 序列","an":"v1","bn":"v2",
             "a":["GET","user","id","name"],"b":["GET","user","id","email"]}""".trimIndent()
        "zx_cmdpal" -> """
            {"type":"zx_cmdpal","n":"命令","cmds":[
               {"n":"发布到预发","k":"⌘P","used":12},
               {"n":"回滚上一版","k":"⌘Z","used":5},
               {"n":"查看日志","used":3}]}""".trimIndent()
        "zx_span" -> """
            {"type":"zx_span","title":"请求耗时分解","total":1200,
             "spans":[{"n":"DNS","s":0,"e":80},{"n":"连接","s":80,"e":210},
                      {"n":"等待","s":210,"e":900},{"n":"下载","s":900,"e":1180}]}""".trimIndent()
        "zx_tolerance" -> """
            {"type":"zx_tolerance","n":"孔径","lsl":9.8,"usl":10.2,"target":10,
             "data":[9.94,10.02,9.98,10.05,9.91,10.08,9.97,10.01,
                     9.95,10.03,9.99,10.06]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_qq"        -> "data[]  3-40 · 少于 3 算不出分位数与偏离带"
        "zx_forest"    -> "studies[{n,e,lo,hi}]  2-8 · 缺 ci 则权重无从计算，合并是假的"
        "zx_bland"     -> "a[] / b[]  3-30 且等长 · 长度不等没法逐对做差"
        "zx_survival"  -> "events[{t,d,c}]  ≤14 + n0 · t 须递增，d+c 不可超剩余风险人数"
        "zx_ecdf"      -> "data[]  2-40 · marks 为 0-1 的分位数"
        "zx_lorenz"    -> "shares[]  2-12 且升序 · 未升序曲线会回头，基尼算错"
        "zx_regress"   -> "pts[{x,y}]  3-24 · x 全相同则斜率无定义"
        "zx_scatter"   -> "pts[{x,y,n?}]  2-40 · n 为分组名，用于着色"
        "zx_seqdiff"   -> "a[] / b[]  ≤40 · 长度不等时缺的一侧显示「—」"
        "zx_cmdpal"    -> "cmds[{n,k?,used?}]  ≤12 · 命令名不可重复"
        "zx_span"      -> "total>0 + spans[{n,s,e}]  ≤8 · s<e，区间不可重叠"
        "zx_tolerance" -> "lsl<usl + target∈[lsl,usl] + data[]  3-36"
        else -> "未注册组件：$component"
    }
}
