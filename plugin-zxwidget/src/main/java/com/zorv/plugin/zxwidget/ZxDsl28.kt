package com.zorv.plugin.zxwidget
import kotlin.math.abs

import org.json.JSONObject

/**
 * 第二十八批组件（行动之后）的校验 + 示例 + 规格。
 *
 * 这批查的是**「状态摆出来了，但推不出该做什么」**。
 *
 * 前二十七批查的都还是"结论可不可信"这一族：
 *
 *   画不出来 → 统计前提不成立 → 区间前提不成立 →
 *   因果前提无从判断 → 看起来确定了其实没那么确定
 *
 * 到了"行动之后"，多了一种新的错法：
 *
 *   **状态完全正常地摆出来了，但没人知道下一步该干嘛。**
 *
 *   灰度卡给了百分比，没说能不能继续放量   → 用户自己看数字猜
 *   错误预算给了成功率，没说还剩多少       → 不知道该不该停手
 *   漂移卡给了 PSI，没给阈值               → 不知道这算严重还是正常
 *   告警给了「CPU 85%」，没写处置动作       → 收到的人只会关掉它
 *   事故时间线缺了「发现」节点              → 算不出 MTTR，无从改进
 *   成本卡给了已花金额，没外推             → 第 10 天花 60 和第 25 天花 60 是两回事
 *   回测没给基准                          → 赚了也可能不如什么都不做
 *   RCA 只问了 1 层                        → 停在表象的修复会复发
 *
 * 这些全都不崩、不报错，只是让界面**只是显示了状态**。
 *
 *   而执行与监控类组件，存在的意义就是**触发下一步动作**。
 *   只显示状态的监控，等于让人盯着屏幕。
 *
 * 具体要拦的（与 ZxRender28 注释一一对应）：
 *   rollout      批次比例未升序 / 比例不在 0-100
 *   canary       两列长度不等 / 容差为负
 *   slo          target 不在 0-1 / 已用超 100% 却标健康
 *   healthcheck  状态值非法（会被静默当通过）/ 检查项为空
 *   incident     缺「发现」或「恢复」节点 / 时间未升序
 *   rca          有效层数 <3（停在表象）/ 链断了
 *   regret       反事实结果缺失 / 概率和不为 1
 *   backtest     无基准（说明不了问题）/ 点数不足
 *   drift        没给阈值 / PSI 为负
 *   alert        没有动作（会被标出来）/ 级别非法
 *   costtrack    预算 ≤0 / 天数未升序
 *   feedback     预测数和实际数数量不等（对不上没法比）
 */
object ZxDsl28 {

    val ALL = listOf(
        "zx_rollout", "zx_canary", "zx_slo", "zx_healthcheck",
        "zx_incident", "zx_rca", "zx_regret", "zx_backtest",
        "zx_drift", "zx_alert", "zx_costtrack", "zx_feedback"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_rollout"     -> rollout(root)
        "zx_canary"      -> canary(root)
        "zx_slo"         -> slo(root)
        "zx_healthcheck" -> healthcheck(root)
        "zx_incident"    -> incident(root)
        "zx_rca"         -> rca(root)
        "zx_regret"      -> regret(root)
        "zx_backtest"    -> backtest(root)
        "zx_drift"       -> drift(root)
        "zx_alert"       -> alert(root)
        "zx_costtrack"   -> costtrack(root)
        "zx_feedback"    -> feedback(root)
        else -> false to "未注册组件：$component"
    }

    private fun rollout(o: JSONObject): Pair<Boolean, String> {
        val st = o.optJSONArray("stages") ?: return false to "zx_rollout 缺少 stages"
        val n = st.length()
        if (n < 2) return false to "zx_rollout 至少 2 个批次，实际 $n 个——1 批那叫全量发布"
        if (n > 6) return false to "zx_rollout 最多 6 个批次，实际 $n 个"
        var prev = -1
        var seenRun = false
        for (k in 0 until n) {
            val s = st.optJSONObject(k)
                ?: return false to "stages[$k] 不是对象，应为 {\"p\":百分比,\"s\":状态}"
            val p = s.optInt("p", -1)
            if (p < 0 || p > 100) return false to "stages[$k].p=$p 超出 0-100"
            // 比例未升序：那不是灰度，是来回横跳
            if (p <= prev)
                return false to "stages[$k].p=$p 未升序（上一批 $prev）——**灰度必须一路往上放**，" +
                                "倒着走不是灰度"
            prev = p
            val ss = s.optString("s", "idle")
            if (ss !in setOf("ok", "warn", "err", "run", "idle"))
                return false to "stages[$k].s=\"$ss\" 只支持 ok / warn / err / run / idle"
            if (ss == "run" && seenRun)
                return false to "stages[$k] 又标了一个 run——**同一时刻只能有一个批次在进行**，" +
                                "两个都标 run 说不清现在到哪了"
            if (ss == "run") seenRun = true
        }
        return true to ""
    }

    private fun canary(o: JSONObject): Pair<Boolean, String> {
        val ms = o.optJSONArray("metrics") ?: return false to "zx_canary 缺少 metrics"
        val n = ms.length()
        if (n == 0) return false to "zx_canary 的 metrics 为空"
        if (n > 8) return false to "zx_canary 最多 8 个指标，实际 $n 个"
        for (k in 0 until n) {
            val m = ms.optJSONObject(k)
                ?: return false to "metrics[$k] 不是对象，应为 {\"n\":名,\"a\":基线,\"b\":新版本}"
            if (m.optString("n").isBlank()) return false to "metrics[$k] 缺少 n（指标名）"
            if (m.optDouble("a", Double.NaN).isNaN()) return false to "metrics[$k] 缺少 a（基线值）"
            if (m.optDouble("b", Double.NaN).isNaN()) return false to "metrics[$k] 缺少 b（新版本值）"
        }
        val tol = o.optDouble("tol", 0.02)
        if (tol < 0) return false to "zx_canary 的 tol=$tol 不能为负——容差为负时任何差异都算恶化"
        return true to ""
    }

    private fun slo(o: JSONObject): Pair<Boolean, String> {
        val t = o.optDouble("target", Double.NaN)
        if (t.isNaN()) return false to "zx_slo 缺少 target（SLO 目标）"
        if (t <= 0 || t >= 1)
            return false to "zx_slo 的 target=$t 必须落在 (0,1)——它是成功率，1.0 意味着不允许任何失败"
        val u = o.optDouble("used", Double.NaN)
        if (u.isNaN()) return false to "zx_slo 缺少 used（已消耗的错误预算比例）"
        if (u < 0) return false to "zx_slo 的 used=$u 不能为负"
        // 已用超过 100% 必须标耗尽，否则界面会显示"还剩负值"
        if (u > 1.0)
            return false to "zx_slo 的 used=$u 超过 100%——**预算已经耗尽**，" +
                            "这时界面必须标「停止发布」，而不是显示还剩多少"
        val days = o.optJSONArray("days") ?: return false to "zx_slo 缺少 days（每日消耗）"
        val n = days.length()
        if (n < 3) return false to "zx_slo 至少 3 天，实际 $n 天"
        if (n > 30) return false to "zx_slo 最多 30 天，实际 $n 天"
        for (k in 0 until n) {
            val v = days.optDouble(k, Double.NaN)
            if (v.isNaN()) return false to "days[$k] 不是数值"
            if (v < 0) return false to "days[$k]=$v 不能为负——消耗量不会是负的"
        }
        return true to ""
    }

    private fun healthcheck(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items") ?: return false to "zx_healthcheck 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_healthcheck 的 items 为空"
        if (n > 16) return false to "zx_healthcheck 最多 16 项，实际 $n 项——再多就压不进一屏了"
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":名,\"s\":状态,\"d\":详情}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（检查项名）"
            val s = it.optString("s", "")
            // 状态值非法：会被静默当通过，这是最糟的错法
            if (s !in setOf("ok", "warn", "err", "idle"))
                return false to "items[$k].s=\"$s\" 只支持 ok / warn / err / idle——" +
                                "拼错会被静默当成「未开始」甚至「通过」，" +
                                "一个拼错的 err 就这样混过去了"
        }
        return true to ""
    }

    private fun incident(o: JSONObject): Pair<Boolean, String> {
        val ev = o.optJSONArray("ev") ?: return false to "zx_incident 缺少 ev（事件序列）"
        val n = ev.length()
        if (n < 2) return false to "zx_incident 至少 2 个事件，实际 $n 个"
        if (n > 10) return false to "zx_incident 最多 10 个事件，实际 $n 个"
        var prev = -1
        var hasFind = false; var hasRec = false
        for (k in 0 until n) {
            val e = ev.optJSONObject(k)
                ?: return false to "ev[$k] 不是对象，应为 {\"t\":分钟,\"k\":类型,\"d\":描述}"
            val t = e.optInt("t", -1)
            if (t < 0) return false to "ev[$k].t=$t 不能为负"
            if (t < prev)
                return false to "ev[$k].t=$t 未升序（上一个 $prev）——事故时间线乱序，" +
                                "段间时长就全是负的"
            prev = t
            val kk = e.optString("k", "other")
            if (kk !in setOf("find", "diag", "fix", "rec", "other"))
                return false to "ev[$k].k=\"$kk\" 只支持 find / diag / fix / rec / other"
            if (kk == "find") hasFind = true
            if (kk == "rec") hasRec = true
        }
        // 缺发现或恢复：算不出 MTTR
        if (!hasFind || !hasRec)
            return false to "zx_incident 缺少${if (!hasFind) "「发现」" else ""}" +
                            "${if (!hasFind && !hasRec) "和" else ""}${if (!hasRec) "「恢复」" else ""}" +
                            "节点——**算不出 MTTR**，也就无从知道该改进哪一段"
        return true to ""
    }

    private fun rca(o: JSONObject): Pair<Boolean, String> {
        val why = o.optJSONArray("why") ?: return false to "zx_rca 缺少 why（追问链）"
        val n = why.length()
        if (n < 3) return false to "zx_rca 至少 3 层，实际 $n 层——**5Why 少于 3 层通常停在表象**"
        if (n > 6) return false to "zx_rca 最多 6 层，实际 $n 层"
        var answered = 0
        var broken = false
        for (k in 0 until n) {
            val w = why.optJSONObject(k)
                ?: return false to "why[$k] 不是对象，应为 {\"q\":为什么,\"a\":因为}"
            if (w.optString("q").isBlank()) return false to "why[$k] 缺少 q（这一层问什么）"
            val a = w.optString("a", "")
            if (a.isBlank()) { broken = true; continue }
            answered++
        }
        // 链断了：中间空了一层还往下答，那不是追问链
        if (broken && answered < n)
            return false to "zx_rca 的追问链断了——第 ${answered + 1} 层没回答却有更深的层，" +
                            "中间跳过的那层往往是根因真正所在"
        if (answered < 3)
            return false to "zx_rca 只答了 $answered 层——**停在表象的修复会复发**，" +
                            "再往下问两层，通常到第三层才是流程问题"
        return true to ""
    }

    private fun regret(o: JSONObject): Pair<Boolean, String> {
        val alts = o.optJSONArray("alts") ?: return false to "zx_regret 缺少 alts（各方案的反事实结果）"
        val n = alts.length()
        if (n < 2) return false to "zx_regret 至少 2 个方案，实际 $n 个——只有 1 个就没有「选错」可言"
        if (n > 5) return false to "zx_regret 最多 5 个方案，实际 $n 个"
        if (o.optString("chosen").isBlank())
            return false to "zx_regret 缺少 chosen（当时选了哪个）——不知道选了谁就无从比较"
        var sum = 0.0
        var hasP = false
        for (k in 0 until n) {
            val a = alts.optJSONObject(k)
                ?: return false to "alts[$k] 不是对象，应为 {\"n\":名,\"v\":反事实结果,\"p\":当时概率}"
            if (a.optString("n").isBlank()) return false to "alts[$k] 缺少 n（方案名）"
            if (a.optDouble("v", Double.NaN).isNaN())
                return false to "alts[$k] 缺少 v（反事实结果）——**不填这个就无从判断选得对不对**"
            val p = a.optDouble("p", Double.NaN)
            if (!p.isNaN()) { hasP = true; sum += p }
        }
        val chosen = o.optString("chosen")
        if (alts.length().let { (0 until it).none { alts.optJSONObject(it)?.optString("n") == chosen } })
            return false to "zx_regret 的 chosen=\"$chosen\" 不在 alts 里——选的方案对不上任何一条"
        // 实际值缺失：这是闭环断掉最常见的地方
        if (o.optDouble("actual", Double.NaN).isNaN())
            return false to "zx_regret 缺少 actual（实际结果）——**没有回填就没有遗憾可言**，" +
                            "这个分析是不完整的"
        if (hasP && abs(sum - 1.0) > 0.01)
            return false to "zx_regret 的当时概率之和为 ${"%.3f".format(sum)}，不是 1——" +
                            "事前期望值会算错"
        return true to ""
    }

    private fun backtest(o: JSONObject): Pair<Boolean, String> {
        val eq = o.optJSONArray("eq") ?: return false to "zx_backtest 缺少 eq（权益曲线）"
        val n = eq.length()
        if (n < 5) return false to "zx_backtest 至少 5 个点，实际 $n 个——算不出有意义的回撤"
        if (n > 60) return false to "zx_backtest 最多 60 个点，实际 $n 个"
        for (k in 0 until n)
            if (eq.optDouble(k, Double.NaN).isNaN()) return false to "eq[$k] 不是数值"
        // 无基准：说明不了问题
        val bm = o.optJSONArray("bench")
        if (bm == null)
            return false to "zx_backtest 缺少 bench（基准）——**赚了也可能不如什么都不做**，" +
                            "没有基准的回测说明不了策略有没有用"
        if (bm.length() != n)
            return false to "zx_backtest 的 bench 有 ${bm.length()} 个点，但 eq 有 $n 个——对不上，逐点比较会错位"
        for (k in 0 until n)
            if (bm.optDouble(k, Double.NaN).isNaN()) return false to "bench[$k] 不是数值"
        return true to ""
    }

    private fun drift(o: JSONObject): Pair<Boolean, String> {
        val cols = o.optJSONArray("cols") ?: return false to "zx_drift 缺少 cols"
        val n = cols.length()
        if (n == 0) return false to "zx_drift 的 cols 为空"
        if (n > 8) return false to "zx_drift 最多 8 个特征，实际 $n 个"
        for (k in 0 until n) {
            val c = cols.optJSONObject(k)
                ?: return false to "cols[$k] 不是对象，应为 {\"n\":名,\"psi\":漂移量,\"act\":建议动作}"
            if (c.optString("n").isBlank()) return false to "cols[$k] 缺少 n（特征名）"
            val psi = c.optDouble("psi", Double.NaN)
            if (psi.isNaN()) return false to "cols[$k] 缺少 psi（漂移量）"
            if (psi < 0) return false to "cols[$k].psi=$psi 不能为负——PSI 是距离，没有负的"
        }
        val w = o.optDouble("warn", Double.NaN)
        val r = o.optDouble("retrain", Double.NaN)
        // 没给阈值：判据无从判断
        if (w.isNaN() || r.isNaN())
            return false to "zx_drift 缺少 warn / retrain 阈值——**只说 PSI=0.31，" +
                            "用户不知道这算严重还是正常**。没有判据的监控等于让人盯着屏幕"
        if (r <= w)
            return false to "zx_drift 的 retrain=$r 不大于 warn=$w——「观察」区间是空的，" +
                            "要么正常要么重训，中间没有过渡"
        return true to ""
    }

    private fun alert(o: JSONObject): Pair<Boolean, String> {
        val al = o.optJSONArray("alerts") ?: return false to "zx_alert 缺少 alerts"
        val n = al.length()
        if (n == 0) return false to "zx_alert 的 alerts 为空"
        if (n > 5) return false to "zx_alert 最多 5 条，实际 $n 条——再多就没人看了"
        var noAct = 0
        for (k in 0 until n) {
            val a = al.optJSONObject(k)
                ?: return false to "alerts[$k] 不是对象，应为 {\"n\":名,\"lv\":级别,\"act\":处置动作}"
            if (a.optString("n").isBlank()) return false to "alerts[$k] 缺少 n（告警名）"
            val lv = a.optString("lv", "warn")
            if (lv !in setOf("crit", "warn", "info"))
                return false to "alerts[$k].lv=\"$lv\" 只支持 crit / warn / info"
            if (a.optString("act").isBlank()) noAct++
        }
        // 没动作：会被标出来而不是拦掉——告警本身还是该显示
        if (noAct == n)
            return false to "zx_alert 全部 $n 条都没写处置动作——**告警的意义是触发行动**，" +
                            "一条没动作的告警只会让人学会忽略它"
        return true to ""
    }

    private fun costtrack(o: JSONObject): Pair<Boolean, String> {
        val b = o.optDouble("budget", Double.NaN)
        if (b.isNaN()) return false to "zx_costtrack 缺少 budget（预算）"
        if (b <= 0) return false to "zx_costtrack 的 budget=$b 必须为正"
        val days = o.optInt("days", 30)
        if (days <= 0) return false to "zx_costtrack 的 days=$days 必须为正"
        val pts = o.optJSONArray("pts") ?: return false to "zx_costtrack 缺少 pts（每日花费）"
        val n = pts.length()
        if (n < 2) return false to "zx_costtrack 至少 2 个点，实际 $n 个——外推需要至少两个点"
        if (n > 30) return false to "zx_costtrack 最多 30 个点，实际 $n 个"
        var prev = -1
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"d\":第几天,\"v\":累计花费}"
            val d = p.optInt("d", -1)
            if (d < 0) return false to "pts[$k].d=$d 不能为负"
            if (d <= prev)
                return false to "pts[$k].d=$d 未升序（上一个 $prev）——时间乱序，外推就没意义了"
            prev = d
            val v = p.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "pts[$k] 缺少 v（累计花费）"
            if (v < 0) return false to "pts[$k].v=$v 不能为负"
        }
        return true to ""
    }

    private fun feedback(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items") ?: return false to "zx_feedback 缺少 items"
        val n = items.length()
        if (n < 2) return false to "zx_feedback 至少 2 项，实际 $n 个"
        if (n > 8) return false to "zx_feedback 最多 8 项，实际 $n 个"
        var filled = 0
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":名,\"p\":预测值,\"a\":实际值}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（项目名）"
            if (it.optDouble("p", Double.NaN).isNaN())
                return false to "items[$k] 缺少 p（当初的预测值）"
            // a 允许缺失（待回填），但用 has() 判断而不是 optDouble 默认 0
            if (it.has("a") && !it.isNull("a")) {
                if (it.optDouble("a", Double.NaN).isNaN())
                    return false to "items[$k].a 不是数值——**要留空就整个字段不写**，" +
                                    "写个空字符串会被当成 0，偏差就全错了"
                filled++
            }
        }
        if (filled == 0)
            return false to "zx_feedback 一项实际值都没回填——**预测发出去就没人再看了**，" +
                            "这是闭环断掉最常见的地方"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_rollout" -> """
            {"type":"zx_rollout","n":"v2.3 灰度","stages":[
               {"p":1,"s":"ok"},{"p":5,"s":"ok"},{"p":20,"s":"run"},
               {"p":50,"s":"idle"},{"p":100,"s":"idle"}]}""".trimIndent()
        "zx_canary" -> """
            {"type":"zx_canary","n":"v2.3 金丝雀","tol":0.02,"metrics":[
               {"n":"转化率","a":0.124,"b":0.126},
               {"n":"P95 延迟","a":240,"b":336},
               {"n":"错误率","a":0.0031,"b":0.0033}]}""".trimIndent()
        "zx_slo" -> """
            {"type":"zx_slo","n":"可用性预算","target":0.999,"used":0.62,
             "days":[0.01,0.02,0.01,0.05,0.12,0.03,0.02,0.01,0.02,0.18,
                     0.04,0.02,0.01,0.03,0.02,0.01,0.02,0.01,0.02,0.01]}}""".trimIndent()
        "zx_healthcheck" -> """
            {"type":"zx_healthcheck","n":"上线前体检","items":[
               {"n":"数据库","s":"ok","d":"延迟 8ms"},
               {"n":"缓存","s":"warn","d":"命中率 78%"},
               {"n":"队列","s":"err","d":"积压 12k"},
               {"n":"CDN","s":"ok","d":"正常"}",
               {"n":"鉴权","s":"ok","d":"正常"}",
               {"n":"存储","s":"ok","d":"余量 62%"}]}""".trimIndent()
        "zx_incident" -> """
            {"type":"zx_incident","n":"3/12 支付故障","ev":[
               {"t":0,"k":"other","d":"订单成功率开始下降"}",
               {"t":8,"k":"find","d":"监控告警触发"}",
               {"t":26,"k":"diag","d":"定位到支付网关连接池"}",
               {"t":31,"k":"fix","d":"扩容连接池并重启"}",
               {"t":44,"k":"rec","d":"成功率恢复正常"}"]}""".trimIndent()
        "zx_rca" -> """
            {"type":"zx_rca","n":"为什么支付挂了","why":[
               {"q":"为什么支付失败率上升？","a":"连接池耗尽"}",
               {"q":"为什么连接池会耗尽？","a":"大促流量是平时的 4 倍"}",
               {"q":"为什么没有提前扩容？","a":"容量评估用的还是去年峰值"}",
               {"q":"为什么用的是去年峰值？","a":"容量复盘没有进发布流程"}"]}""".trimIndent()
        "zx_regret" -> """
            {"type":"zx_regret","n":"选 A 还是选 B","chosen":"方案A","actual":8.2,
             "alts":[{"n":"方案A","v":8.2,"p":0.6},
                     {"n":"方案B","v":11.5,"p":0.4}]}""".trimIndent()
        "zx_backtest" -> """
            {"type":"zx_backtest","n":"动量策略回测","eq":[100,103,101,107,112,109,115,121,118],
             "bench":[100,101,102,103,104,105,106,107,108]}""".trimIndent()
        "zx_drift" -> """
            {"type":"zx_drift","n":"线上 vs 训练","warn":0.1,"retrain":0.25,"cols":[
               {"n":"用户年龄","psi":0.31,"act":"重训"}",
               {"n":"客单价","psi":0.14,"act":"观察"}",
               {"n":"活跃度","psi":0.06,"act":""},
               {"n":"地域","psi":0.02,"act":""}]}""".trimIndent()
        "zx_alert" -> """
            {"type":"zx_alert","n":"当前告警","alerts":[
               {"n":"支付错误率 3.2%","lv":"crit","act":"切备用网关并 @值班"}",
               {"n":"CPU 85%","lv":"warn","act":"扩容 2 台"}",
               {"n":"磁盘 78%","lv":"info","act":"本周内清理日志"}"]}""".trimIndent()
        "zx_costtrack" -> """
            {"type":"zx_costtrack","n":"本月云成本","budget":100,"days":30,
             "pts":[{"d":2,"v":6},{"d":4,"v":14},{"d":7,"v":26},{"d":9,"v":35},
                    {"d":12,"v":49},{"d":14,"v":58}]}""".trimIndent()
        "zx_feedback" -> """
            {"type":"zx_feedback","n":"上季度预测 vs 实际","items":[
               {"n":"1月","p":210,"a":228},{"n":"2月","p":235,"a":241},
               {"n":"3月","p":260,"a":271},{"n":"4月","p":288,"a":297},
               {"n":"5月","p":312,"a":330},{"n":"6月","p":340}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_rollout"     -> "stages[{p∈0-100 严格升序,s∈ok|warn|err|run|idle}] 2-6；run 最多一个"
        "zx_canary"      -> "metrics[{n,a,b}] ≤8 + tol≥0"
        "zx_slo"         -> "target∈(0,1) + used∈[0,1] + days[] 3-30 非负"
        "zx_healthcheck" -> "items[{n,s∈ok|warn|err|idle,d}] ≤16；状态拼错会被拦"
        "zx_incident"    -> "ev[{t 升序,k∈find|diag|fix|rec|other,d}] 2-10；必须有 find 和 rec"
        "zx_rca"         -> "why[{q,a}] 3-6；至少答满 3 层，链不能断"
        "zx_regret"      -> "chosen 在 alts 内 + actual + alts[{n,v,p}] 2-5；Σp=1"
        "zx_backtest"    -> "eq[] 5-60 + bench[] 等长（**没有基准会被拦**）"
        "zx_drift"       -> "cols[{n,psi≥0,act}] ≤8 + warn < retrain（**没给阈值会被拦**）"
        "zx_alert"       -> "alerts[{n,lv∈crit|warn|info,act}] ≤5；不能全都没动作"
        "zx_costtrack"   -> "budget>0 + days>0 + pts[{d 升序,v≥0}] 2-30"
        "zx_feedback"    -> "items[{n,p,a?}] 2-8；至少回填 1 项实际值（留空别写字段）"
        else -> "未注册组件：$component"
    }
}
