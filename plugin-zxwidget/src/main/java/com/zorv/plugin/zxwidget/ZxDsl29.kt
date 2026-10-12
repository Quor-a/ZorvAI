package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第二十九批组件（知识与判断的留存）的校验 + 示例 + 规格。
 *
 * 这批查的是**「记是记下来了，但下次用不上」**。
 *
 * 前六档查的都还是"这一刻对不对"这一族：
 *
 *   画不出来 → 统计前提不成立 → 区间前提不成立 →
 *   因果前提无从判断 → 看起来确定了其实没那么确定 →
 *   状态摆出来了但推不出该做什么
 *
 * 到了"留存"，多了一种新的错法：
 *
 *   **东西全都记下来了，但下次拿它没办法。**
 *
 *   容量卡写了「现在很闲」              → 下次大促还是拍脑袋
 *   拓扑卡只列了各服务健康度            → 上游挂了才知道影响面
 *   剧本只有「怎么做」没有「怎么退」     → 真要回滚时临时想
 *   复盘只有时间线没有行动项            → 下次照样挂
 *   知识没标时效                        → 过期知识比没有更糟：它是错的且看起来可信
 *   升级路径每级没有超时                → 一级级传上去可能半天没人看
 *   技术债不标利息                      → 清单永远排不上优先级
 *   决策只记结论不记依据                → 半年后没人知道当初为什么这么选
 *
 * 这些全都不崩、不报错，只是让卡片**只是记录了**。
 *
 *   而留存类组件，存在的意义就是**下次能用上**。
 *   只记录的留存等于没留存。
 *
 * 具体要拦的（与 ZxRender29 注释一一对应）：
 *   capacity    μ ≤ λ（ρ≥1 系统不稳定，队列无限长）
 *   queue       λ 或 μ 非正（Little's Law 无从算起）
 *   loadtest    吞吐未升序（画不出拐点）
 *   topology    有环（传播无限循环）/ 边指向不存在的节点
 *   changerisk  高风险却有因素没写依据（评分变拍脑袋）
 *   playbook    缺回滚步骤
 *   escalation  没有终点 / 超时未升序
 *   postmortem  缺行动项
 *   knowledge   缺出处或时间（无法判断是否过期）
 *   decisionlog 缺依据
 *   debt        利息为 0（那不叫债，那叫待办）
 *   checkpoint  进度未升序
 */
object ZxDsl29 {

    val ALL = listOf(
        "zx_capacity", "zx_queue", "zx_loadtest",
        "zx_topology", "zx_changerisk",
        "zx_playbook", "zx_escalation",
        "zx_postmortem", "zx_knowledge", "zx_decisionlog", "zx_debt", "zx_checkpoint"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_capacity"    -> capacity(root)
        "zx_queue"       -> queue(root)
        "zx_loadtest"    -> loadtest(root)
        "zx_topology"    -> topology(root)
        "zx_changerisk"  -> changerisk(root)
        "zx_playbook"    -> playbook(root)
        "zx_escalation"  -> escalation(root)
        "zx_postmortem"  -> postmortem(root)
        "zx_knowledge"   -> knowledge(root)
        "zx_decisionlog" -> decisionlog(root)
        "zx_debt"        -> debt(root)
        "zx_checkpoint"  -> checkpoint(root)
        else -> false to "未注册组件：$component"
    }

    private fun capacity(o: JSONObject): Pair<Boolean, String> {
        val lam = o.optDouble("lambda", Double.NaN)
        if (lam.isNaN()) return false to "zx_capacity 缺少 lambda（到达率）"
        if (lam <= 0) return false to "zx_capacity 的 lambda=$lam 必须为正"
        val mu = o.optDouble("mu", Double.NaN)
        if (mu.isNaN()) return false to "zx_capacity 缺少 mu（服务率）"
        if (mu <= 0) return false to "zx_capacity 的 mu=$mu 必须为正"
        val n = o.optInt("servers", 1)
        if (n <= 0) return false to "zx_capacity 的 servers=$n 必须为正"
        // μ ≤ λ：系统不稳定，队列无限长
        if (mu * n <= lam)
            return false to "zx_capacity 的 μ×servers=${mu * n} ≤ λ=$lam——" +
                            "**ρ≥1，队列会无限增长**。这时算出来的等待时间是假的，" +
                            "真正的结论是「这个配置撑不住」"
        return true to ""
    }

    private fun queue(o: JSONObject): Pair<Boolean, String> {
        val lam = o.optDouble("lambda", Double.NaN)
        if (lam.isNaN()) return false to "zx_queue 缺少 lambda（到达率）"
        if (lam <= 0) return false to "zx_queue 的 lambda=$lam 必须为正——**没有到达率，" +
                                      "Little's Law 里的每个量都无从算起**"
        val mu = o.optDouble("mu", Double.NaN)
        if (mu.isNaN()) return false to "zx_queue 缺少 mu（服务率）"
        if (mu <= 0) return false to "zx_queue 的 mu=$mu 必须为正"
        val items = o.optJSONArray("items") ?: return false to "zx_queue 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_queue 的 items 为空"
        if (n > 12) return false to "zx_queue 最多 12 项，实际 $n 项"
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":名,\"at\":到达时刻,\"svc\":耗时}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（任务名）"
            if (it.optDouble("at", Double.NaN).isNaN()) return false to "items[$k] 缺少 at（到达时刻）"
            val s = it.optDouble("svc", Double.NaN)
            if (s.isNaN()) return false to "items[$k] 缺少 svc（服务耗时）"
            if (s < 0) return false to "items[$k].svc=$s 不能为负"
        }
        return true to ""
    }

    private fun loadtest(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_loadtest 缺少 pts"
        val n = pts.length()
        if (n < 4) return false to "zx_loadtest 至少 4 个点，实际 $n 个——少于 4 点算不出拐点"
        if (n > 24) return false to "zx_loadtest 最多 24 个点，实际 $n 个"
        var prevQ = -1.0
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"q\":吞吐,\"lat\":延迟}"
            val q = p.optDouble("q", Double.NaN)
            if (q.isNaN()) return false to "pts[$k] 缺少 q（吞吐量）"
            if (q <= prevQ)
                return false to "pts[$k].q=$q 未升序（上一个 $prevQ）——" +
                                "**吞吐乱序画不出拐点**，压测本来就是一路加压上去的"
            prevQ = q
            val lat = p.optDouble("lat", Double.NaN)
            if (lat.isNaN()) return false to "pts[$k] 缺少 lat（延迟）"
            if (lat < 0) return false to "pts[$k].lat=$lat 不能为负"
        }
        val sla = o.optDouble("sla", Double.NaN)
        if (!sla.isNaN() && sla <= 0)
            return false to "zx_loadtest 的 sla=$sla 必须为正——SLA 为 0 会让所有点都判成超标"
        return true to ""
    }

    private fun topology(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes") ?: return false to "zx_topology 缺少 nodes"
        val n = nodes.length()
        if (n < 2) return false to "zx_topology 至少 2 个节点，实际 $n 个"
        if (n > 10) return false to "zx_topology 最多 10 个节点，实际 $n 个——再多就画糊了"
        for (k in 0 until n) {
            val nd = nodes.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"n\":名,\"s\":状态,\"d\":详情}"
            if (nd.optString("n").isBlank()) return false to "nodes[$k] 缺少 n（服务名）"
            val s = nd.optString("s", "ok")
            if (s !in setOf("ok", "warn", "err", "crit"))
                return false to "nodes[$k].s=\"$s\" 只支持 ok / warn / err / crit"
        }
        val edges = o.optJSONArray("edges") ?: return false to "zx_topology 缺少 edges"
        val m = edges.length()
        if (m == 0) return false to "zx_topology 的 edges 为空——没有依赖边就没有传播可言"
        if (m > 16) return false to "zx_topology 最多 16 条边，实际 $m 条"
        val adj = Array(n) { ArrayList<Int>() }
        for (k in 0 until m) {
            val e = edges.optJSONObject(k)
                ?: return false to "edges[$k] 不是对象，应为 {\"f\":起点,\"t\":终点}"
            val f = e.optInt("f", -1)
            val t = e.optInt("t", -1)
            if (f < 0 || f >= n)
                return false to "edges[$k].f=$f 超出 0-${n - 1}——**指向不存在的节点会画出一条连到空白的线**"
            if (t < 0 || t >= n)
                return false to "edges[$k].t=$t 超出 0-${n - 1}"
            if (f == t) return false to "edges[$k] 是自环（f=t=$f）"
            adj[f].add(t)
        }
        // 环检测：传播会无限循环
        val color = IntArray(n)
        fun dfs(u: Int): Boolean {
            color[u] = 1
            adj[u].forEach { v ->
                if (color[v] == 1) return true
                if (color[v] == 0 && dfs(v)) return true
            }
            color[u] = 2
            return false
        }
        for (k in 0 until n) if (color[k] == 0 && dfs(k))
            return false to "zx_topology 的依赖图有环（涉及节点 ${k}）——" +
                            "**健康传播会无限循环**，必须先理清依赖方向"
        return true to ""
    }

    private fun changerisk(o: JSONObject): Pair<Boolean, String> {
        val fs = o.optJSONArray("factors") ?: return false to "zx_changerisk 缺少 factors"
        val n = fs.length()
        if (n == 0) return false to "zx_changerisk 的 factors 为空"
        if (n > 6) return false to "zx_changerisk 最多 6 个因素，实际 $n 个"
        var total = 0.0
        var noWhy = 0
        for (k in 0 until n) {
            val f = fs.optJSONObject(k)
                ?: return false to "factors[$k] 不是对象，应为 {\"n\":名,\"w\":分,\"why\":依据}"
            if (f.optString("n").isBlank()) return false to "factors[$k] 缺少 n（因素名）"
            val w = f.optDouble("w", Double.NaN)
            if (w.isNaN()) return false to "factors[$k] 缺少 w（风险分）"
            if (w < 0 || w > 10) return false to "factors[$k].w=$w 超出 0-10"
            total += w
            if (f.optString("why").isBlank()) noWhy++
        }
        val score = total / n
        // 高风险却没写依据：评分变成拍脑袋
        if (score >= 7 && noWhy > 0)
            return false to "zx_changerisk 总分 ${"%.1f".format(score)} 属于高风险，" +
                            "但有 $noWhy 项没写依据——**没有依据的评分就是拍脑袋**，" +
                            "下次不会再有人信它。至少给高风险项补上 why"
        return true to ""
    }

    private fun playbook(o: JSONObject): Pair<Boolean, String> {
        val steps = o.optJSONArray("steps") ?: return false to "zx_playbook 缺少 steps"
        val n = steps.length()
        if (n < 2) return false to "zx_playbook 至少 2 步，实际 $n 步"
        if (n > 8) return false to "zx_playbook 最多 8 步，实际 $n 步——再多就不是剧本了"
        var hasRollback = false
        var prev = -1
        for (k in 0 until n) {
            val s = steps.optJSONObject(k)
                ?: return false to "steps[$k] 不是对象，应为 {\"n\":步骤名,\"d\":说明,\"sec\":耗时}"
            val nm = s.optString("n")
            if (nm.isBlank()) return false to "steps[$k] 缺少 n（步骤名）"
            if (nm.contains("回滚") || nm.contains("撤销") || nm.contains("退回")) hasRollback = true
            val sec = s.optInt("sec", 0)
            if (sec < 0) return false to "steps[$k].sec=$sec 不能为负"
            if (s.optString("d").isBlank() && sec == 0)
                return false to "steps[$k] 既没有说明也没有耗时——**剧本的意义是出事时不用想**，" +
                                "空步骤等于让人临场发挥"
        }
        // 缺回滚：真要回滚时只能临时想
        if (!hasRollback)
            return false to "zx_playbook 没有回滚步骤——**只有「怎么做」没有「做错了怎么退**，" +
                            "一旦中间某步出问题就卡住了。加一步「回滚：…」"
        return true to ""
    }

    private fun escalation(o: JSONObject): Pair<Boolean, String> {
        val lv = o.optJSONArray("levels") ?: return false to "zx_escalation 缺少 levels"
        val n = lv.length()
        if (n < 2) return false to "zx_escalation 至少 2 级，实际 $n 级——只有 1 级就没有升级可言"
        if (n > 5) return false to "zx_escalation 最多 5 级，实际 $n 级——再多半夜也找不到人"
        var prev = -1
        for (k in 0 until n) {
            val l = lv.optJSONObject(k)
                ?: return false to "levels[$k] 不是对象，应为 {\"who\":谁,\"min\":几分钟后升级,\"how\":怎么联系}"
            if (l.optString("who").isBlank()) return false to "levels[$k] 缺少 who（这一级是谁）"
            val min = l.optInt("min", 0)
            if (min < 0) return false to "levels[$k].min=$min 不能为负"
            if (min < prev)
                return false to "levels[$k].min=$min 未升序（上一级 $prev）——" +
                                "**超时倒着走会出现「升到第 2 级反而等更久」**"
            prev = min
        }
        // 最高级没有超时：那是终点，允许 min=0
        val last = lv.optJSONObject(n - 1)
        if (last != null && last.optInt("min", 0) > 0)
            return false to "zx_escalation 的最高级不用再写超时——**它已经是终点**，" +
                            "写了超时等于承诺「还能再往上升」，而实际没地方升了"
        return true to ""
    }

    private fun postmortem(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("summary").isBlank() && o.optString("impact").isBlank())
            return false to "zx_postmortem 缺少 summary 或 impact——**只有时间线的复盘等于没复盘**"
        val acts = o.optJSONArray("actions") ?: return false to "zx_postmortem 缺少 actions"
        val n = acts.length()
        // 缺行动项：等于没复盘
        if (n == 0)
            return false to "zx_postmortem 一条行动项都没有——**只有时间线的复盘等于没复盘**，" +
                            "下次照样挂。至少写一条，且要有负责人"
        if (n > 6) return false to "zx_postmortem 最多 6 条行动项，实际 $n 条"
        var noOwner = 0
        for (k in 0 until n) {
            val a = acts.optJSONObject(k)
                ?: return false to "actions[$k] 不是对象，应为 {\"n\":行动,\"who\":负责人,\"by\":期限}"
            val nm = a.optString("n")
            if (nm.isBlank()) return false to "actions[$k] 缺少 n（行动内容）"
            if (a.optString("who").isBlank()) noOwner++
        }
        // 全都没有负责人：没人的行动项不会被执行
        if (noOwner == n)
            return false to "zx_postmortem 全部 $n 条行动项都没写负责人——" +
                            "**没人的行动项不会被执行**。至少给最关键的那条指定 who"
        return true to ""
    }

    private fun knowledge(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items") ?: return false to "zx_knowledge 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_knowledge 的 items 为空"
        if (n > 6) return false to "zx_knowledge 最多 6 条，实际 $n 条"
        var noSrc = 0; var noAt = 0
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":知识,\"src\":出处,\"at\":多少天前}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（知识内容）"
            if (it.optString("src").isBlank()) noSrc++
            val at = it.optString("at")
            if (at.isBlank()) noAt++
            else if (at.toIntOrNull() == null)
                return false to "items[$k].at=\"$at\" 不是天数（整数）"
            else if ((at.toIntOrNull() ?: 0) < 0)
                return false to "items[$k].at=$at 不能为负"
        }
        // 缺出处或时间：无法判断是否过期
        if (noSrc > 0)
            return false to "zx_knowledge 有 $noSrc 条没写出处——**没有出处就无法复核**，" +
                            "下次要么重查要么盲信"
        if (noAt > 0)
            return false to "zx_knowledge 有 $noAt 条没写时间——**没有时间就无法判断是否过期**。" +
                            "**过期的知识比没有知识更糟**：它是错的，而且看起来可信"
        val stale = o.optInt("stale", 90)
        if (stale <= 0) return false to "zx_knowledge 的 stale=$stale 必须为正"
        return true to ""
    }

    private fun decisionlog(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items") ?: return false to "zx_decisionlog 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_decisionlog 的 items 为空"
        if (n > 6) return false to "zx_decisionlog 最多 6 条，实际 $n 条"
        var noWhy = 0
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":决策,\"why\":依据,\"who\":谁定的}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（决策内容）"
            if (it.optString("why").isBlank()) noWhy++
        }
        // 缺依据：记了等于没记
        if (noWhy == n)
            return false to "zx_decisionlog 全部 $n 条都只记了结论没记依据——" +
                            "**半年后没人知道当初为什么这么选**，也就没法判断现在该不该改。" +
                            "至少给一条补上 why"
        return true to ""
    }

    private fun debt(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items") ?: return false to "zx_debt 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_debt 的 items 为空"
        if (n > 8) return false to "zx_debt 最多 8 条，实际 $n 条"
        var zero = 0
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":债务,\"int\":月利息,\"cost\":偿还成本}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（债务名）"
            val int = it.optDouble("int", Double.NaN)
            if (int.isNaN()) return false to "items[$k] 缺少 int（每月利息）"
            if (int < 0) return false to "items[$k].int=$int 不能为负"
            if (int <= 0) zero++
            val cost = it.optDouble("cost", Double.NaN)
            if (cost.isNaN()) return false to "items[$k] 缺少 cost（偿还成本）"
            if (cost <= 0) return false to "items[$k].cost=$cost 必须为正——" +
                                           "**零成本的债早就被还了**，写 0 会让性价比除零"
        }
        // 利息为 0：那不叫债
        if (zero == n)
            return false to "zx_debt 全部 $n 条利息都是 0——**那不叫债，那叫待办**。" +
                            "混在债清单里会挤掉真正该还的；要么标上利息，要么放进 backlog"
        return true to ""
    }

    private fun checkpoint(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts") ?: return false to "zx_checkpoint 缺少 pts"
        val n = pts.length()
        if (n < 2) return false to "zx_checkpoint 至少 2 个点，实际 $n 个——只有 1 个就谈不上间隔"
        if (n > 8) return false to "zx_checkpoint 最多 8 个点，实际 $n 个"
        var prev = -1.0
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"p\":进度,\"n\":检查点名}"
            val v = p.optDouble("p", Double.NaN)
            if (v.isNaN()) return false to "pts[$k] 缺少 p（进度百分比）"
            if (v < 0 || v > 100) return false to "pts[$k].p=$v 超出 0-100"
            if (v <= prev)
                return false to "pts[$k].p=$v 未升序（上一个 $prev）——" +
                                "**进度乱序算不出「崩了会丢多少」**，而那正是看这张卡的原因"
            prev = v
            if (p.optString("n").isBlank()) return false to "pts[$k] 缺少 n（检查点名）"
        }
        val total = o.optDouble("total", 100.0)
        if (total <= 0) return false to "zx_checkpoint 的 total=$total 必须为正"
        if (prev > total)
            return false to "zx_checkpoint 的最后一个进度 $prev 超过 total=$total"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_capacity" -> """
            {"type":"zx_capacity","n":"订单服务容量","lambda":820,"mu":1000,"servers":1}""".trimIndent()
        "zx_queue" -> """
            {"type":"zx_queue","n":"消息队列","lambda":8,"mu":10,"items":[
               {"n":"A","at":0,"svc":0.1},{"n":"B","at":0.2,"svc":0.1},
               {"n":"C","at":0.4,"svc":0.1},{"n":"D","at":0.6,"svc":0.1}]}""".trimIndent()
        "zx_loadtest" -> """
            {"type":"zx_loadtest","n":"压测","sla":400,"pts":[
               {"q":100,"lat":85},{"q":200,"lat":92},{"q":300,"lat":101},
               {"q":400,"lat":118},{"q":500,"lat":147},{"q":600,"lat":205},
               {"q":700,"lat":318},{"q":800,"lat":520}]}""".trimIndent()
        "zx_topology" -> """
            {"type":"zx_topology","n":"服务依赖","nodes":[
               {"n":"网关","s":"ok"},{"n":"订单","s":"err","d":"连接池满"}",
               {"n":"支付","s":"ok"},{"n":"库存","s":"ok"},
               {"n":"通知","s":"ok"}],
             "edges":[{"f":0,"t":1},{"f":1,"t":2},{"f":1,"t":3},{"f":2,"t":4}]}""".trimIndent()
        "zx_changerisk" -> """
            {"type":"zx_changerisk","n":"v2.3 发布风险","factors":[
               {"n":"数据库迁移","w":8,"why":"改了 3 张表结构，不可回滚"}",
               {"n":"流量规模","w":6,"why":"大促期间，峰值是平时 4 倍"}",
               {"n":"改动范围","w":4,"why":"只动了订单服务"}",
               {"n":"可观测性","w":3,"why":"新链路已埋点"}"]}""".trimIndent()
        "zx_playbook" -> """
            {"type":"zx_playbook","n":"支付故障处置","steps":[
               {"n":"确认影响面","d":"看拓扑，哪些下游受影响","sec":30},
               {"n":"切备用网关","d":"改 DNS 权重到备用","sec":60},
               {"n":"观察 5 分钟","d":"盯错误率和延迟","sec":300},
               {"n":"回滚：切回主网关","d":"指标没恢复就切回来","sec":60}]}""".trimIndent()
        "zx_escalation" -> """
            {"type":"zx_escalation","n":"支付告警升级","levels":[
               {"who":"值班工程师","min":0,"how":"电话"}",
               {"who":"订单组组长","min":15,"how":"电话+短信"}",
               {"who":"技术负责人","min":30,"how":"电话"}",
               {"who":"CTO","min":0,"how":"电话"}"]}""".trimIndent()
        "zx_postmortem" -> """
            {"type":"zx_postmortem","n":"3/12 支付故障复盘",
             "summary":"大促流量超去年峰值，连接池未提前扩容",
             "impact":"支付失败 12 分钟，影响约 3400 单",
             "root":"容量评估用的还是去年峰值",
             "actions":[
               {"n":"容量复盘进发布流程","who":"李四","by":"本季度"}",
               {"n":"连接池改为动态扩容","who":"王五","by":"下月"}"]}""".trimIndent()
        "zx_knowledge" -> """
            {"type":"zx_knowledge","n":"运维经验","stale":90,"items":[
               {"n":"Redis 连接池默认 200 够用","src":"2024-08 压测报告","at":15},
               {"n":"大促前必须做容量评估","src":"3/12 复盘","at":20},
               {"n":"网关超时设 3s","src":"2023 年某次调整","at":410}]}""".trimIndent()
        "zx_decisionlog" -> """
            {"type":"zx_decisionlog","n":"架构决策","items":[
               {"n":"消息队列选 Kafka","why":"吞吐要求 10w/s，RabbitMQ 达不到","who":"架构组"}",
               {"n":"订单表按月分区","why":"单表超 2 亿行，查询变慢","who":"DBA"}]}""".trimIndent()
        "zx_debt" -> """
            {"type":"zx_debt","n":"技术债","items":[
               {"n":"订单表没分区","int":12,"cost":3},
               {"n":"老定时任务没下线","int":2,"cost":1},
               {"n":"缺集成测试","int":8,"cost":10},
               {"n":"硬编码配置","int":1,"cost":0.5}]}""".trimIndent()
        "zx_checkpoint" -> """
            {"type":"zx_checkpoint","n":"数据迁移进度","total":100,"pts":[
               {"p":10,"n":"首批 1-10万"}",{"p":30,"n":"第二批"}",
               {"p":55,"n":"第三批"}",{"p":80,"n":"第四批"}"]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_capacity"    -> "lambda>0 + mu>0 + servers>0；**μ×servers > λ**（否则 ρ≥1 会被拦）"
        "zx_queue"       -> "lambda>0 + mu>0 + items[{n,at,svc≥0}] ≤12"
        "zx_loadtest"    -> "pts[{q 严格升序,lat≥0}] 4-24 + sla>0（可选）"
        "zx_topology"    -> "nodes[{n,s∈ok|warn|err|crit,d}] 2-10 + edges[{f,t}] ≤16；**有环会被拦**"
        "zx_changerisk"  -> "factors[{n,w∈0-10,why}] ≤6；**高风险却有因素没写 why 会被拦**"
        "zx_playbook"    -> "steps[{n,d,sec≥0}] 2-8；**必须含回滚步骤**"
        "zx_escalation"  -> "levels[{who,min 升序,how}] 2-5；**最高级 min 必须为 0**（它是终点）"
        "zx_postmortem"  -> "summary|impact 必居其一 + actions[{n,who,by}] 1-6；不能全无 who"
        "zx_knowledge"   -> "items[{n,src,at 天数}] ≤6 + stale>0；**缺 src 或 at 会被拦**"
        "zx_decisionlog" -> "items[{n,why,who}] ≤6；不能全部缺 why"
        "zx_debt"        -> "items[{n,int≥0,cost>0}] ≤8；**不能全部 int=0**（那是待办不是债）"
        "zx_checkpoint"  -> "pts[{p 0-100 严格升序,n}] 2-8 + total>0"
        else -> "未注册组件：$component"
    }
}
