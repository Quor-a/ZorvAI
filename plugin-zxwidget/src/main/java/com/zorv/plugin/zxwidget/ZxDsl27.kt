package com.zorv.plugin.zxwidget
import kotlin.math.abs

import org.json.JSONObject

/**
 * 第二十七批组件（预测与决策）的校验 + 示例 + 规格。
 *
 * 这批查的重点是**「看起来确定了，其实没那么确定」**。
 *
 * 前二十六批查的都还是"画不画得出来"这一族：
 *   渲染错位 → 统计前提不成立 → 区间/效应量前提不成立 →
 *   因果结论前提无从判断。
 *
 * 到了预测与决策，多了一种新的错法：
 *
 *   **图画得完全正常，读起来却比实际更确定。**
 *
 *   预测区间随 horizon 收窄 —— 画反了，越远看起来越准
 *   校准只给曲线不给 ECE  —— 看不出整体偏了多少
 *   决策只给排名不给权重  —— 看不出换个权重会不会翻
 *   效用只给一个数字      —— 偏好根本不是一个数字能表达的
 *   老虎机只给当前均值    —— 没试过的臂被饿死
 *   归因不自洽            —— 那个解释是假的
 *   反事实改了但结果没变  —— 这个建议无效
 *   异质效应全同          —— 没有异质性，画这个是浪费
 *   A/B 分流和不为 1      —— SRM 无从判断，后面全白看
 *
 * 这些全都不崩、不报错，只是让界面**比它实际所知的更确定**。
 *
 * 而预测与决策恰好是"知道了就要动手"的地方——
 * 过度的确定会直接变成错误的行动。
 *
 * **看不见的不确定，会被当成确定。**
 *
 * 具体要拦的（与 ZxRender27 注释一一对应）：
 *   forecast   历史 <4 点 / 给的区间随 horizon 收窄
 *   calib      预测概率不在 0-1 / 分箱数 > 样本数
 *   optline    候选 <4 个
 *   decision   权重和为 0 / 选项或准则为空 / 评分矩阵尺寸不符
 *   riskreturn 两列长度不等 / 点数 <3
 *   utility    点数 <2 / x 未升序
 *   bandit     臂数为 0 / k=0 却给了非 0 均值
 *   explain    基线 + 归因 ≠ 预测（归因不自洽）
 *   cfactual   两组行数不等 / 改了但结果没变
 *   cforest    子组 <2 / 效应全相同 / se ≤0
 *   alloc      预算 ≤0 / a 或 b ≤0（那就不是递减场景）
 *   abmon      分流和不为 1 / 某组预期比例 ≤0
 */
object ZxDsl27 {

    val ALL = listOf(
        "zx_forecast", "zx_calib", "zx_optline", "zx_dmatrix",
        "zx_riskreturn", "zx_utility", "zx_bandit", "zx_explain",
        "zx_cfactual", "zx_cforest", "zx_alloc", "zx_abmon"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_forecast"   -> forecast(root)
        "zx_calib"      -> calib(root)
        "zx_optline"    -> optline(root)
        "zx_dmatrix"   -> decision(root)
        "zx_riskreturn" -> riskreturn(root)
        "zx_utility"    -> utility(root)
        "zx_bandit"     -> bandit(root)
        "zx_explain"    -> explain(root)
        "zx_cfactual"   -> cfactual(root)
        "zx_cforest"    -> cforest(root)
        "zx_alloc"      -> alloc(root)
        "zx_abmon"      -> abmon(root)
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

    private fun forecast(o: JSONObject): Pair<Boolean, String> {
        val (h, e) = nums(o, "hist", 4, 24)
        if (h == null) return false to "zx_forecast $e——少于 4 个点拟不出趋势，外推没有依据"
        val hh = o.optInt("h", 6)
        if (hh < 1 || hh > 12) return false to "zx_forecast 的 h=$hh 超出 [1,12]"
        // 区间收窄：越远越准，这是画反了
        val lA = o.optJSONArray("lo"); val hA = o.optJSONArray("hi")
        if (lA != null && hA != null && lA.length() == hA.length() && lA.length() >= 2) {
            val w0 = hA.optDouble(0, 0.0) - lA.optDouble(0, 0.0)
            val wN = hA.optDouble(lA.length() - 1, 0.0) - lA.optDouble(lA.length() - 1, 0.0)
            if (wN < w0)
                return false to "zx_forecast 的区间随 horizon 收窄了（第 1 期宽 ${"%.3f".format(w0)}，" +
                                "最后一期只有 ${"%.3f".format(wN)}）——**越远应该越不确定**，这是画反了"
        }
        return true to ""
    }

    private fun calib(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_calib 缺少 pts"
        val n = pts.length()
        if (n < 8) return false to "zx_calib 至少 8 个样本，实际 $n 个——样本太少分不出箱"
        if (n > 30) return false to "zx_calib 最多 30 个样本，实际 $n 个"
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"p\":预测概率,\"y\":0或1}"
            val v = p.optDouble("p", Double.NaN)
            if (v.isNaN()) return false to "pts[$k] 缺少 p（预测概率）"
            if (v < 0 || v > 1) return false to "pts[$k].p=$v 超出 0-1——概率不能是这个值"
            val y = p.optInt("y", -1)
            if (y != 0 && y != 1) return false to "pts[$k].y=$y 必须是 0 或 1"
        }
        val b = o.optInt("bins", 5)
        if (b < 2 || b > 8) return false to "zx_calib 的 bins=$b 超出 [2,8]"
        // 分箱数超过样本数：每箱只有 1 个，频率非 0 即 1
        if (b > n) return false to "zx_calib 的 bins=$b 超过样本数 $n——每箱不到 1 个，" +
                                   "算出来的频率非 0 即 1，校准曲线没有意义"
        return true to ""
    }

    private fun optline(o: JSONObject): Pair<Boolean, String> {
        val (c, e) = nums(o, "cand", 4, 16)
        if (c == null) return false to "zx_optline $e——少于 4 个候选直接全看完就行，" +
                                       "用不到停止规则"
        return true to ""
    }

    private fun decision(o: JSONObject): Pair<Boolean, String> {
        val opts = o.optJSONArray("opts") ?: return false to "zx_decision 缺少 opts"
        val m = opts.length()
        if (m == 0) return false to "zx_decision 的 opts 为空"
        if (m > 6) return false to "zx_decision 最多 6 个选项，实际 $m 个"
        for (k in 0 until m)
            if (opts.optString(k).isBlank()) return false to "opts[$k] 为空选项名"
        val crit = o.optJSONArray("crit") ?: return false to "zx_decision 缺少 crit"
        val kk = crit.length()
        if (kk == 0) return false to "zx_decision 的 crit 为空——没有准则就没法加权"
        if (kk > 5) return false to "zx_decision 最多 5 个准则，实际 $kk 个"
        var sum = 0.0
        for (k in 0 until kk) {
            val c = crit.optJSONObject(k)
                ?: return false to "crit[$k] 不是对象，应为 {\"n\":准则名,\"w\":权重}"
            if (c.optString("n").isBlank()) return false to "crit[$k] 缺少 n（准则名）"
            val w = c.optDouble("w", Double.NaN)
            if (w.isNaN()) return false to "crit[$k] 缺少 w（权重）"
            if (w < 0) return false to "crit[$k].w=$w 不能为负"
            sum += w
        }
        // 权重全 0：归一化除零
        if (sum <= 1e-9)
            return false to "zx_decision 的权重全是 0——归一化会除零，得不出任何排名"
        val sc = o.optJSONArray("scores") ?: return false to "zx_decision 缺少 scores"
        if (sc.length() != m)
            return false to "zx_decision 的 scores 有 ${sc.length()} 行，但 opts 有 $m 个——对不上"
        for (i in 0 until m) {
            val r = sc.optJSONArray(i)
                ?: return false to "scores[$i] 不是数组"
            if (r.length() != kk)
                return false to "scores[$i] 有 ${r.length()} 个，但 crit 有 $kk 个——" +
                                "每个选项必须对每个准则都有分"
            for (j in 0 until kk)
                if (r.optDouble(j, Double.NaN).isNaN())
                    return false to "scores[$i][$j] 不是数值"
        }
        return true to ""
    }

    private fun riskreturn(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_riskreturn 缺少 pts"
        val n = pts.length()
        if (n < 3) return false to "zx_riskreturn 至少 3 个方案，实际 $n 个——少于 3 个画不出前沿"
        if (n > 12) return false to "zx_riskreturn 最多 12 个方案，实际 $n 个"
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"n\":名,\"r\":收益,\"s\":风险}"
            if (p.optString("n").isBlank()) return false to "pts[$k] 缺少 n（方案名）"
            if (p.optDouble("r", Double.NaN).isNaN()) return false to "pts[$k] 缺少 r（收益）"
            val s = p.optDouble("s", Double.NaN)
            if (s.isNaN()) return false to "pts[$k] 缺少 s（风险）"
            if (s < 0) return false to "pts[$k].s=$s 不能为负——风险是离散度"
        }
        return true to ""
    }

    private fun utility(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_utility 缺少 pts"
        val n = pts.length()
        if (n < 2) return false to "zx_utility 至少 2 个点，实际 $n 个——1 个点画不出曲线形态，" +
                                   "而偏好恰恰体现在**形状**上"
        if (n > 10) return false to "zx_utility 最多 10 个点，实际 $n 个"
        var prev = -Double.MAX_VALUE
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"x\":取值,\"u\":效用}"
            val x = p.optDouble("x", Double.NaN)
            if (x.isNaN()) return false to "pts[$k] 缺少 x"
            if (p.optDouble("u", Double.NaN).isNaN()) return false to "pts[$k] 缺少 u（效用）"
            // x 未升序：连线会回头，曲率判读全错
            if (x <= prev) return false to "pts[$k].x=$x 未升序（前一个 ${"%.3f".format(prev)}）——" +
                                           "连线会回头，凹凸性判读就错了"
            prev = x
        }
        return true to ""
    }

    private fun bandit(o: JSONObject): Pair<Boolean, String> {
        val arms = o.optJSONArray("arms") ?: return false to "zx_bandit 缺少 arms"
        val n = arms.length()
        if (n == 0) return false to "zx_bandit 的 arms 为空"
        if (n > 6) return false to "zx_bandit 最多 6 个臂，实际 $n 个"
        for (k in 0 until n) {
            val a = arms.optJSONObject(k)
                ?: return false to "arms[$k] 不是对象，应为 {\"n\":臂名,\"mu\":均值,\"k\":试验次数}"
            if (a.optString("n").isBlank()) return false to "arms[$k] 缺少 n（臂名）"
            if (a.optDouble("mu", Double.NaN).isNaN()) return false to "arms[$k] 缺少 mu（均值）"
            val kk = a.optInt("k", -1)
            if (kk < 0) return false to "arms[$k].k=$kk 不能为负"
            // k=0 却给了非 0 均值：那是假数据，且会误导 UCB
            if (kk == 0 && abs(a.optDouble("mu", 0.0)) > 1e-9)
                return false to "arms[$k] 试验次数 k=0 却给了 mu=${a.optDouble("mu", 0.0)}——" +
                                "没试过就没有均值，这个值要么是编的，要么会误导 UCB 不去探索它"
        }
        return true to ""
    }

    private fun explain(o: JSONObject): Pair<Boolean, String> {
        val base = o.optDouble("base", Double.NaN)
        if (base.isNaN()) return false to "zx_explain 缺少 base（基线值）"
        val pred = o.optDouble("pred", Double.NaN)
        if (pred.isNaN()) return false to "zx_explain 缺少 pred（预测值）"
        val attrs = o.optJSONArray("attrs") ?: return false to "zx_explain 缺少 attrs"
        val n = attrs.length()
        if (n == 0) return false to "zx_explain 的 attrs 为空——没有归因就没有解释"
        if (n > 8) return false to "zx_explain 最多 8 个特征，实际 $n 个"
        var sum = 0.0
        for (k in 0 until n) {
            val a = attrs.optJSONObject(k)
                ?: return false to "attrs[$k] 不是对象，应为 {\"n\":特征名,\"v\":归因值,\"x\":特征值}"
            if (a.optString("n").isBlank()) return false to "attrs[$k] 缺少 n（特征名）"
            val v = a.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "attrs[$k] 缺少 v（归因值）"
            sum += v
        }
        // 归因不自洽：这个解释是假的
        val tol = 0.02 * abs(pred).coerceAtLeast(1.0)
        if (abs(base + sum - pred) > tol)
            return false to "zx_explain 不自洽：基线 ${"%.3f".format(base)} + 归因合计 " +
                            "${"%.3f".format(sum)} = ${"%.3f".format(base + sum)}，" +
                            "但预测是 ${"%.3f".format(pred)}（差 ${"%.3f".format(abs(base + sum - pred))}，" +
                            "容差 ${"%.3f".format(tol)}）——不自洽的归因不能拿来解释"
        return true to ""
    }

    private fun cfactual(o: JSONObject): Pair<Boolean, String> {
        val cur = o.optJSONArray("cur") ?: return false to "zx_cfactual 缺少 cur（现状）"
        val cf = o.optJSONArray("cf") ?: return false to "zx_cfactual 缺少 cf（反事实）"
        val n = cur.length()
        if (n == 0) return false to "zx_cfactual 的 cur 为空"
        if (n > 6) return false to "zx_cfactual 最多 6 个特征，实际 $n 个"
        // 两组行数不等：对比会错位
        if (cf.length() != n)
            return false to "zx_cfactual 的 cf 有 ${cf.length()} 项，但 cur 有 $n 项——对不上，对比会错位"
        var changed = 0
        for (k in 0 until n) {
            val c = cur.optJSONObject(k)
                ?: return false to "cur[$k] 不是对象，应为 {\"n\":特征名,\"v\":值}"
            val f = cf.optJSONObject(k)
                ?: return false to "cf[$k] 不是对象，应为 {\"n\":特征名,\"v\":值}"
            if (c.optString("n").isBlank()) return false to "cur[$k] 缺少 n"
            if (f.optString("n").isBlank()) return false to "cf[$k] 缺少 n"
            if (c.optString("n") != f.optString("n"))
                return false to "cur[$k].n=\"${c.optString("n")}\" 与 cf[$k].n=" +
                                "\"${f.optString("n")}\" 不一致——两边必须按同一次序列特征"
            if (c.optString("v") != f.optString("v")) changed++
        }
        if (changed == 0)
            return false to "zx_cfactual 一个特征都没改——那不是反事实"
        val y0 = o.optDouble("y0", Double.NaN)
        if (y0.isNaN()) return false to "zx_cfactual 缺少 y0（现状的结果）"
        val y1 = o.optDouble("y1", Double.NaN)
        if (y1.isNaN()) return false to "zx_cfactual 缺少 y1（反事实的结果）"
        // 改了但结果没变：这个反事实无效
        if (abs(y1 - y0) < 1e-9)
            return false to "zx_cfactual 改了 $changed 项，但结果没变（y0=$y0，y1=$y1）——" +
                            "这个反事实不成立，不能拿它当建议"
        return true to ""
    }

    private fun cforest(o: JSONObject): Pair<Boolean, String> {
        val subs = o.optJSONArray("subs") ?: return false to "zx_cforest 缺少 subs"
        val n = subs.length()
        if (n < 2) return false to "zx_cforest 至少 2 个子组，实际 $n 个——" +
                                   "只有 1 个子组就没有异质性可看"
        if (n > 8) return false to "zx_cforest 最多 8 个子组，实际 $n 个"
        val es = ArrayList<Double>()
        for (k in 0 until n) {
            val s = subs.optJSONObject(k)
                ?: return false to "subs[$k] 不是对象，应为 {\"n\":子组名,\"e\":效应,\"se\":标准误}"
            if (s.optString("n").isBlank()) return false to "subs[$k] 缺少 n（子组名）"
            val e = s.optDouble("e", Double.NaN)
            if (e.isNaN()) return false to "subs[$k] 缺少 e（效应）"
            es.add(e)
            val se = s.optDouble("se", Double.NaN)
            if (se.isNaN()) return false to "subs[$k] 缺少 se（标准误）"
            // se ≤0：区间退化成一个点，会显得"极显著"
            if (se <= 0) return false to "subs[$k].se=$se 必须为正——为 0 时区间退化成一个点，" +
                                         "每个子组都会显得极显著"
        }
        val spread = es.maxOrNull()!! - es.minOrNull()!!
        if (spread < 1e-9)
            return false to "zx_cforest 所有子组效应完全相同——没有异质性，" +
                            "用平均效应就够了，画这个是浪费"
        return true to ""
    }

    private fun alloc(o: JSONObject): Pair<Boolean, String> {
        val ch = o.optJSONArray("ch") ?: return false to "zx_alloc 缺少 ch（渠道）"
        val n = ch.length()
        if (n == 0) return false to "zx_alloc 的 ch 为空"
        if (n > 5) return false to "zx_alloc 最多 5 个渠道，实际 $n 个"
        for (k in 0 until n) {
            val c = ch.optJSONObject(k)
                ?: return false to "ch[$k] 不是对象，应为 {\"n\":渠道名,\"a\":规模系数,\"b\":效率}"
            if (c.optString("n").isBlank()) return false to "ch[$k] 缺少 n（渠道名）"
            val a = c.optDouble("a", Double.NaN)
            if (a.isNaN()) return false to "ch[$k] 缺少 a（规模系数）"
            if (a <= 0) return false to "ch[$k].a=$a 必须为正"
            val b = c.optDouble("b", Double.NaN)
            if (b.isNaN()) return false to "ch[$k] 缺少 b（效率）"
            if (b <= 0) return false to "ch[$k].b=$b 必须为正"
        }
        val bg = o.optDouble("budget", Double.NaN)
        if (bg.isNaN()) return false to "zx_alloc 缺少 budget（预算）"
        if (bg <= 0) return false to "zx_alloc 的 budget=$bg 必须为正"
        if (bg > 100000) return false to "zx_alloc 的 budget=$bg 超出上限 100000"
        return true to ""
    }

    private fun abmon(o: JSONObject): Pair<Boolean, String> {
        val arms = o.optJSONArray("arms") ?: return false to "zx_abmon 缺少 arms"
        val n = arms.length()
        if (n < 2) return false to "zx_abmon 至少 2 个组，实际 $n 个"
        if (n > 5) return false to "zx_abmon 最多 5 个组，实际 $n 个"
        var sum = 0.0
        for (k in 0 until n) {
            val a = arms.optJSONObject(k)
                ?: return false to "arms[$k] 不是对象，应为 {\"n\":组名,\"p\":预期比例,\"k\":实际样本数}"
            if (a.optString("n").isBlank()) return false to "arms[$k] 缺少 n（组名）"
            val p = a.optDouble("p", Double.NaN)
            if (p.isNaN()) return false to "arms[$k] 缺少 p（预期分流比例）"
            if (p <= 0 || p > 1) return false to "arms[$k].p=$p 超出 (0,1]"
            sum += p
            val kk = a.optInt("k", -1)
            if (kk < 0) return false to "arms[$k].k=$kk 不能为负"
        }
        // 分流和不为 1：SRM 无从判断
        if (abs(sum - 1.0) > 0.01)
            return false to "zx_abmon 的预期分流比例之和为 ${"%.3f".format(sum)}，不是 1——" +
                            "SRM（分流比例不匹配）就无从判断，而 SRM 不成立时后面所有指标都不可信"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_forecast" -> """
            {"type":"zx_forecast","n":"下周单量预测","h":6,
             "hist":[212,228,235,241,238,250,259,266,271,283,290,297]}""".trimIndent()
        "zx_calib" -> """
            {"type":"zx_calib","n":"流失预测校准","bins":5,
             "pts":[{"p":0.12,"y":0},{"p":0.18,"y":1},{"p":0.25,"y":0},{"p":0.31,"y":1},
                    {"p":0.44,"y":0},{"p":0.47,"y":1},{"p":0.55,"y":1},{"p":0.62,"y":1},
                    {"p":0.71,"y":1},{"p":0.78,"y":1},{"p":0.83,"y":0},{"p":0.91,"y":1}]}""".trimIndent()
        "zx_optline" -> """
            {"type":"zx_optline","n":"面试该在第几个停",
             "cand":[62,71,58,80,74,69,88,77,72,85,79,91]}""".trimIndent()
        "zx_dmatrix" -> """
            {"type":"zx_dmatrix","n":"选哪家供应商",
             "opts":["A 厂","B 厂","C 厂"],
             "crit":[{"n":"价格","w":2},{"n":"交期","w":1},{"n":"质量","w":2}],
             "scores":[[8,6,7],[6,9,8],[9,5,6]]}""".trimIndent()
        "zx_riskreturn" -> """
            {"type":"zx_riskreturn","n":"三个投放方案","pts":[
               {"n":"激进","r":0.18,"s":0.32},{"n":"平衡","r":0.12,"s":0.15},
               {"n":"保守","r":0.06,"s":0.05},{"n":"次优","r":0.09,"s":0.16}]}""".trimIndent()
        "zx_utility" -> """
            {"type":"zx_utility","n":"收益的效用曲线","pts":[
               {"x":0,"u":0},{"x":10,"u":0.42},{"x":20,"u":0.70},
               {"x":40,"u":0.92},{"x":80,"u":1.0}]}""".trimIndent()
        "zx_bandit" -> """
            {"type":"zx_bandit","n":"三个文案哪个更好","arms":[
               {"n":"文案A","mu":0.31,"k":42},{"n":"文案B","mu":0.28,"k":38},
               {"n":"文案C","mu":0.0,"k":0}]}""".trimIndent()
        "zx_explain" -> """
            {"type":"zx_explain","n":"为什么判定高风险","base":0.20,"pred":0.78,
             "attrs":[{"n":"逾期次数","v":0.31,"x":"3 次"}",
                      {"n":"负债比","v":0.19,"x":"0.62"},
                      {"n":"账龄","v":0.08,"x":"8 年"}"]}""".trimIndent()
        "zx_cfactual" -> """
            {"type":"zx_cfactual","n":"怎么才能通过","y0":0.32,"y1":0.71,
             "cur":[{"n":"逾期次数","v":"3 次"}",{"n":"负债比","v":"0.62"},
                    {"n":"账龄","v":"8 年"}"],
             "cf":[{"n":"逾期次数","v":"0 次"}",{"n":"负债比","v":"0.62"},
                   {"n":"账龄","v":"8 年"}"]}""".trimIndent()
        "zx_cforest" -> """
            {"type":"zx_cforest","n":"新功能对谁有效","subs":[
               {"n":"新客","e":0.22,"se":0.06},{"n":"老客","e":-0.08,"se":0.05},
               {"n":"高频","e":0.31,"se":0.09},{"n":"低频","e":0.02,"se":0.07}]}""".trimIndent()
        "zx_alloc" -> """
            {"type":"zx_alloc","n":"季度预算怎么投","budget":100,
             "ch":[{"n":"搜索","a":1.0,"b":0.9},{"n":"信息流","a":0.8,"b":0.6},
                   {"n":"私域","a":0.5,"b":0.4}]}""".trimIndent()
        "zx_abmon" -> """
            {"type":"zx_abmon","n":"分流体检","arms":[
               {"n":"对照","p":0.5,"k":5120},{"n":"实验","p":0.5,"k":4980}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_forecast"   -> "hist[] 4-24 + h∈[1,12]；区间必须随 horizon 变宽"
        "zx_calib"      -> "pts[{p∈0-1,y:0|1}] 8-30 + bins∈[2,8] 且 ≤样本数"
        "zx_optline"    -> "cand[] 4-16"
        "zx_dmatrix"   -> "opts[] ≤6 + crit[{n,w}] ≤5（权重和>0）+ scores[opts][crit]"
        "zx_riskreturn" -> "pts[{n,r,s≥0}] 3-12"
        "zx_utility"    -> "pts[{x,u}] 2-10，x 必须严格升序"
        "zx_bandit"     -> "arms[{n,mu,k}] ≤6；k=0 时 mu 必须为 0"
        "zx_explain"    -> "base + pred + attrs[{n,v,x}] ≤8；base+Σv 必须≈pred"
        "zx_cfactual"   -> "cur/cf 等长 ≤6 且同名；至少改 1 项；y0≠y1"
        "zx_cforest"    -> "subs[{n,e,se>0}] 2-8；效应不能全同"
        "zx_alloc"      -> "ch[{n,a>0,b>0}] ≤5 + budget>0"
        "zx_abmon"      -> "arms[{n,p∈(0,1],k}] 2-5；Σp 必须=1"
        else -> "未注册组件：$component"
    }
}
