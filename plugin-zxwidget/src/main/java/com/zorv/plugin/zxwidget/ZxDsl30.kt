package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs

/**
 * 第三十批组件（组织与治理）的校验 + 示例 + 规格。
 *
 * 这批查的是**「数字都在，但落不到谁该做什么」**。
 *
 * 前七档查的都还是"这一刻 / 这一张卡"这一族：
 *
 *   画不出来 → 统计前提不成立 → 区间前提不成立 →
 *   因果前提无从判断 → 看起来确定了其实没那么确定 →
 *   状态摆出来了但推不出该做什么 → 记下来了但下次用不上
 *
 * 到了治理，多了一种新的错法：
 *
 *   **数字全对，但没人知道下一步该由谁做什么。**
 *
 *   单客经济给 LTV/CAC=4.2，不给回本周期  → 不知道现金流扛不扛得住
 *   成本归因拆完加不回总额                → 那这个拆分是编的
 *   血缘图画了箭头不算深度                → 不知道改上游要跳几层
 *   负载只标「谁忙」不看「谁闲」          → 会得出「需要加人」的错误结论
 *   权限列表不标「多久没审」              → 离职的人可能还开着
 *   实验只说 p<0.05 不给提升幅度          → 把「显著」当成「值得上线」
 *   合规标了不合格不给整改人              → 下次检查还是不合格
 *
 * 这些全都不崩、不报错，只是让卡片**只有数字**。
 *
 *   而治理类组件，存在的意义就是**落到人、落到动作**。
 *   只有数字、落不到动作的治理，等于没治理。
 *
 * 具体要拦的（与 ZxRender30 注释一一对应）：
 *   unitecon     CAC 非正（除零）/ churn 不在 0-1（LTV 算错）
 *   costattrib   分项之和 ≠ 总额（拆分是编的）
 *   lineage      有环（血缘无限）/ 边指向不存在的节点
 *   impact       影响项缺目标
 *   dataclass    有列未分级（那列按最高敏感处理）
 *   retention    保留天数非正（到期判断失效）
 *   workload     capacity 非正（负载率除零）
 *   bottleneck   吞吐非正（约束无从判断）
 *   expresult    给了 p 却没给提升（显著 ≠ 该上）
 *   compliance   有不合格项却没整改人
 *   audittrail   有条目缺操作者
 *   accessreview 全部没审过时间（判断不了过期）
 */
object ZxDsl30 {

    val ALL = listOf(
        "zx_unitecon", "zx_costattrib",
        "zx_lineage", "zx_impact", "zx_dataclass", "zx_retention",
        "zx_workload", "zx_bottleneck", "zx_accessreview",
        "zx_expresult", "zx_compliance", "zx_audittrail"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_unitecon"    -> unitecon(root)
        "zx_costattrib"  -> costattrib(root)
        "zx_lineage"     -> lineage(root)
        "zx_impact"      -> impact(root)
        "zx_dataclass"   -> dataclass(root)
        "zx_retention"   -> retention(root)
        "zx_workload"    -> workload(root)
        "zx_bottleneck"  -> bottleneck(root)
        "zx_expresult"   -> expresult(root)
        "zx_compliance"  -> compliance(root)
        "zx_audittrail"  -> audittrail(root)
        "zx_accessreview" -> accessreview(root)
        else -> false to "未注册组件：$component"
    }

    private fun unitecon(o: JSONObject): Pair<Boolean, String> {
        val cac = o.optDouble("cac", Double.NaN)
        if (cac.isNaN()) return false to "zx_unitecon 缺少 cac（获客成本）"
        if (cac <= 0) return false to "zx_unitecon 的 cac=$cac 必须为正——**除零**，LTV/CAC 算不出来"
        val arpu = o.optDouble("arpu", Double.NaN)
        if (arpu.isNaN()) return false to "zx_unitecon 缺少 arpu（月均收入）"
        if (arpu <= 0) return false to "zx_unitecon 的 arpu=$arpu 必须为正——**回本周期算不出来**，" +
                                       "而那正是看这张卡的原因"
        val churn = o.optDouble("churn", Double.NaN)
        if (churn.isNaN()) return false to "zx_unitecon 缺少 churn（月流失率）"
        if (churn <= 0 || churn >= 1)
            return false to "zx_unitecon 的 churn=$churn 必须在 0-1 之间——**LTV = ARPU/churn**，" +
                            "churn 为 0 会让 LTV 变无穷大，≥1 会让 LTV 小于一个月的收入"
        val horizon = o.optInt("horizon", 24)
        if (horizon <= 0) return false to "zx_unitecon 的 horizon=$horizon 必须为正"
        return true to ""
    }

    private fun costattrib(o: JSONObject): Pair<Boolean, String> {
        val total = o.optDouble("total", Double.NaN)
        if (total.isNaN()) return false to "zx_costattrib 缺少 total（成本总额）——" +
                                           "**没有总额就无法验证拆分是不是完整**"
        if (total <= 0) return false to "zx_costattrib 的 total=$total 必须为正"
        val items = o.optJSONArray("items") ?: return false to "zx_costattrib 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_costattrib 的 items 为空"
        if (n > 8) return false to "zx_costattrib 最多 8 项，实际 $n 项"
        var sum = 0.0
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":名,\"v\":金额,\"d\":环比}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（维度名）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$k] 缺少 v（金额）"
            if (v < 0) return false to "items[$k].v=$v 不能为负"
            sum += v
        }
        // 分项之和 ≠ 总额：拆分是编的
        val tol = total * 0.005
        if (abs(total - sum) > tol)
            return false to "zx_costattrib 分项之和 ${"%.2f".format(sum)} ≠ 总额 ${"%.2f".format(total)}" +
                            "（差 ${"%.2f".format(total - sum)}）——**这个拆分漏了东西，按它做决策会错**。" +
                            "最常见的漏法是「其他」那一项忘了加"
        return true to ""
    }

    private fun lineage(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes") ?: return false to "zx_lineage 缺少 nodes"
        val n = nodes.length()
        if (n < 2) return false to "zx_lineage 至少 2 个节点，实际 $n 个"
        if (n > 10) return false to "zx_lineage 最多 10 个节点，实际 $n 个"
        for (k in 0 until n) {
            val nd = nodes.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"n\":表名,\"t\":类型}"
            if (nd.optString("n").isBlank()) return false to "nodes[$k] 缺少 n（表名）"
        }
        val edges = o.optJSONArray("edges") ?: return false to "zx_lineage 缺少 edges"
        val m = edges.length()
        if (m == 0) return false to "zx_lineage 的 edges 为空——没有边就没有血缘可言"
        if (m > 16) return false to "zx_lineage 最多 16 条边，实际 $m 条"
        val adj = Array(n) { ArrayList<Int>() }
        for (k in 0 until m) {
            val e = edges.optJSONObject(k)
                ?: return false to "edges[$k] 不是对象，应为 {\"f\":上游,\"t\":下游}"
            val f = e.optInt("f", -1)
            val t = e.optInt("t", -1)
            if (f < 0 || f >= n)
                return false to "edges[$k].f=$f 超出 0-${n - 1}——**指向不存在的表**"
            if (t < 0 || t >= n) return false to "edges[$k].t=$t 超出 0-${n - 1}"
            if (f == t) return false to "edges[$k] 是自环（f=t=$f）"
            adj[f].add(t)
        }
        // 环检测：血缘无限，深度算不出来
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
            return false to "zx_lineage 的血缘图有环（涉及节点 ${k}）——" +
                            "**深度算不出来**，改上游会无限循环。数据表血缘应该是 DAG"
        return true to ""
    }

    private fun impact(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("change").isBlank() && o.optString("n").isBlank())
            return false to "zx_impact 缺少 change（改了什么）——**不知道改的是什么，影响面无从谈起**"
        val items = o.optJSONArray("items") ?: return false to "zx_impact 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_impact 的 items 为空"
        if (n > 8) return false to "zx_impact 最多 8 项，实际 $n 项"
        var noFix = 0
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"t\":目标,\"sev\":级别,\"fix\":怎么修}"
            // 影响项缺目标：说不清波及了谁
            if (it.optString("t").isBlank())
                return false to "items[$k] 缺少 t（受影响的目标）——**说不清波及了谁**"
            val sev = it.optString("sev", "med")
            if (sev !in setOf("crit", "high", "med", "low"))
                return false to "items[$k].sev=\"$sev\" 只支持 crit / high / med / low"
            if (it.optString("fix").isBlank()) noFix++
        }
        // 全都没写怎么修：等于把问题丢回去
        if (noFix == n)
            return false to "zx_impact 全部 $n 项都没写 fix——**只报告影响不给办法**，" +
                            "等于把问题丢回去。至少给高危项写上怎么修"
        return true to ""
    }

    private fun dataclass(o: JSONObject): Pair<Boolean, String> {
        val cols = o.optJSONArray("cols") ?: return false to "zx_dataclass 缺少 cols"
        val n = cols.length()
        if (n == 0) return false to "zx_dataclass 的 cols 为空"
        if (n > 10) return false to "zx_dataclass 最多 10 列，实际 $n 列"
        var unclassified = 0
        for (k in 0 until n) {
            val c = cols.optJSONObject(k)
                ?: return false to "cols[$k] 不是对象，应为 {\"n\":列名,\"lv\":级别,\"note\":说明}"
            if (c.optString("n").isBlank()) return false to "cols[$k] 缺少 n（列名）"
            val lv = c.optString("lv")
            if (lv.isBlank()) { unclassified++; continue }
            if (lv !in setOf("public", "internal", "confidential", "secret"))
                return false to "cols[$k].lv=\"$lv\" 只支持 public / internal / confidential / secret"
        }
        // 有列未分级：那列按最高敏感处理，整张表都被拉高
        if (unclassified > 0)
            return false to "zx_dataclass 有 $unclassified 列没标级别——**没分级的列默认按最高敏感处理**，" +
                            "否则一旦流出，没人知道它本来该受什么保护"
        return true to ""
    }

    private fun retention(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items") ?: return false to "zx_retention 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_retention 的 items 为空"
        if (n > 8) return false to "zx_retention 最多 8 项，实际 $n 项"
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":数据名,\"keep\":保留天数,\"age\":已存天数}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（数据名）"
            val keep = it.optInt("keep", 0)
            // 保留天数非正：到期判断失效
            if (keep <= 0)
                return false to "items[$k].keep=$keep 必须为正——**到期判断会失效**，" +
                                "而「哪些该删了」正是看这张卡的原因"
            val age = it.optInt("age", -1)
            if (age < 0) return false to "items[$k] 缺少 age（已存天数）"
        }
        return true to ""
    }

    private fun workload(o: JSONObject): Pair<Boolean, String> {
        val people = o.optJSONArray("people") ?: return false to "zx_workload 缺少 people"
        val n = people.length()
        if (n == 0) return false to "zx_workload 的 people 为空"
        if (n > 8) return false to "zx_workload 最多 8 人，实际 $n 人"
        for (k in 0 until n) {
            val p = people.optJSONObject(k)
                ?: return false to "people[$k] 不是对象，应为 {\"n\":姓名,\"todo\":手头数,\"cap\":容量}"
            if (p.optString("n").isBlank()) return false to "people[$k] 缺少 n（姓名）"
            val todo = p.optInt("todo", -1)
            if (todo < 0) return false to "people[$k] 缺少 todo（手头任务数）"
            val cap = p.optInt("cap", 10)
            // capacity 非正：负载率除零
            if (cap <= 0)
                return false to "people[$k].cap=$cap 必须为正——**负载率除零**，" +
                                "而且「容量为 0」的人不该出现在团队里"
        }
        return true to ""
    }

    private fun bottleneck(o: JSONObject): Pair<Boolean, String> {
        val st = o.optJSONArray("stages") ?: return false to "zx_bottleneck 缺少 stages"
        val n = st.length()
        if (n < 2) return false to "zx_bottleneck 至少 2 个环节，实际 $n 个——" +
                                   "只有 1 个环节就没有「哪一环是约束」可言"
        if (n > 8) return false to "zx_bottleneck 最多 8 个环节，实际 $n 个"
        for (k in 0 until n) {
            val s = st.optJSONObject(k)
                ?: return false to "stages[$k] 不是对象，应为 {\"n\":环节名,\"thr\":吞吐,\"wip\":在制}"
            if (s.optString("n").isBlank()) return false to "stages[$k] 缺少 n（环节名）"
            val thr = s.optDouble("thr", Double.NaN)
            if (thr.isNaN()) return false to "stages[$k] 缺少 thr（吞吐）"
            // 吞吐非正：约束无从判断
            if (thr <= 0)
                return false to "stages[$k].thr=$thr 必须为正——**吞吐为 0 的环节必然是约束**，" +
                                "那样识别出来的「瓶颈」没有意义"
            val wip = s.optDouble("wip", Double.NaN)
            if (wip.isNaN()) return false to "stages[$k] 缺少 wip（在制数量）"
            if (wip < 0) return false to "stages[$k].wip=$wip 不能为负"
        }
        return true to ""
    }

    private fun expresult(o: JSONObject): Pair<Boolean, String> {
        val ms = o.optJSONArray("metrics") ?: return false to "zx_expresult 缺少 metrics"
        val n = ms.length()
        if (n == 0) return false to "zx_expresult 的 metrics 为空"
        if (n > 5) return false to "zx_expresult 最多 5 个指标，实际 $n 个"
        var hasP = 0; var hasLift = 0
        for (k in 0 until n) {
            val m = ms.optJSONObject(k)
                ?: return false to "metrics[$k] 不是对象，应为 {\"n\":指标名,\"lift\":提升,\"p\":p值}"
            if (m.optString("n").isBlank()) return false to "metrics[$k] 缺少 n（指标名）"
            val p = m.optDouble("p", Double.NaN)
            if (!p.isNaN()) {
                if (p < 0 || p > 1) return false to "metrics[$k].p=$p 超出 0-1"
                hasP++
            }
            val lift = m.optDouble("lift", Double.NaN)
            if (lift.isNaN()) {
                // 给了 p 却没给提升 → 拦
                if (hasP > 0)
                    return false to "metrics[$k] 给了 p 值却没给 lift——**显著 ≠ 该上**。" +
                                    "p 值回答「有没有」，提升幅度回答「值不值」，" +
                                    "只有 p 会让人把「统计上显著」当成「值得上线」"
            } else hasLift++
        }
        if (hasLift == 0)
            return false to "zx_expresult 一个提升幅度都没给——**只看 p 值做不了上线决策**"
        return true to ""
    }

    private fun compliance(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items") ?: return false to "zx_compliance 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_compliance 的 items 为空"
        if (n > 8) return false to "zx_compliance 最多 8 项，实际 $n 项"
        var fail = 0; var failNoOwner = 0
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":检查项,\"s\":状态,\"who\":整改人}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（检查项）"
            val s = it.optString("s", "pass")
            if (s !in setOf("pass", "warn", "fail"))
                return false to "items[$k].s=\"$s\" 只支持 pass / warn / fail"
            if (s == "fail") { fail++; if (it.optString("who").isBlank()) failNoOwner++ }
        }
        // 有不合格项却没整改人：下次检查还是不合格
        if (failNoOwner > 0)
            return false to "zx_compliance 有 $failNoOwner 项不合格且没写整改人——" +
                            "**没人改就永远不合格**，检查就变成了走过场"
        return true to ""
    }

    private fun audittrail(o: JSONObject): Pair<Boolean, String> {
        val es = o.optJSONArray("entries") ?: return false to "zx_audittrail 缺少 entries"
        val n = es.length()
        if (n == 0) return false to "zx_audittrail 的 entries 为空"
        if (n > 10) return false to "zx_audittrail 最多 10 条，实际 $n 条"
        var noWho = 0
        for (k in 0 until n) {
            val e = es.optJSONObject(k)
                ?: return false to "entries[$k] 不是对象，" +
                                   "应为 {\"who\":谁,\"what\":做了什么,\"when\":何时,\"kind\":类型}"
            if (e.optString("what").isBlank()) return false to "entries[$k] 缺少 what（做了什么）"
            val kind = e.optString("kind", "read")
            if (kind !in setOf("read", "write", "delete", "grant", "export"))
                return false to "entries[$k].kind=\"$kind\" 只支持 read / write / delete / grant / export"
            if (e.optString("who").isBlank()) noWho++
        }
        // 有条目缺操作者：追不到人
        if (noWho > 0)
            return false to "zx_audittrail 有 $noWho 条没有操作者——**审计追踪的意义就是追到人**，" +
                            "追不到人的日志等于没记"
        return true to ""
    }

    private fun accessreview(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items") ?: return false to "zx_accessreview 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_accessreview 的 items 为空"
        if (n > 8) return false to "zx_accessreview 最多 8 项，实际 $n 项"
        var noAge = 0
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"who\":谁,\"perm\":权限,\"age\":多久没审}"
            if (it.optString("who").isBlank()) return false to "items[$k] 缺少 who（谁）"
            if (it.optString("perm").isBlank()) return false to "items[$k] 缺少 perm（权限级别）"
            val age = it.optInt("age", -1)
            if (age < 0) noAge++
        }
        // 全部没审过时间：判断不了过期
        if (noAge == n)
            return false to "zx_accessreview 全部 $n 项都没写 age（多久没审）——" +
                            "**判断不了过期**。权限评审看的不是「有什么权限」，" +
                            "是「多久没确认还该不该有」"
        val stale = o.optInt("stale", 90)
        if (stale <= 0) return false to "zx_accessreview 的 stale=$stale 必须为正"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_unitecon" -> """
            {"type":"zx_unitecon","n":"付费版单客经济","cac":420,"arpu":68,"churn":0.055,"horizon":24}""".trimIndent()
        "zx_costattrib" -> """
            {"type":"zx_costattrib","n":"云成本归因","total":100,
             "items":[{"n":"计算","v":42,"d":6},{"n":"存储","v":23,"d":2},
                      {"n":"网络","v":18,"d":-3},{"n":"数据库","v":12,"d":1},
                      {"n":"其他","v":5,"d":0}]}""".trimIndent()
        "zx_lineage" -> """
            {"type":"zx_lineage","n":"订单域血缘","nodes":[
               {"n":"ods_order"},{"n":"dwd_order"},{"n":"dws_user_1d"},
               {"n":"ads_gmv"},{"n":"bi_dashboard"}],
             "edges":[{"f":0,"t":1},{"f":1,"t":2},{"f":2,"t":3},{"f":3,"t":4}]}""".trimIndent()
        "zx_impact" -> """
            {"type":"zx_impact","n":"影响分析","change":"order.amount 精度 decimal(10,2) → decimal(16,4)",
             "items":[
               {"t":"GMV 日报","sev":"crit","fix":"改聚合口径后再算"}",
               {"t":"财务对账","sev":"high","fix":"加四舍五入兼容"}",
               {"t":"用户账单页","sev":"med","fix":"前端格式化"}"]}""".trimIndent()
        "zx_dataclass" -> """
            {"type":"zx_dataclass","n":"user_profile 分级","cols":[
               {"n":"user_id","lv":"internal"},{"n":"nickname","lv":"public"},
               {"n":"phone","lv":"confidential","note":"脱敏展示"}",
               {"n":"id_card","lv":"secret","note":"禁止出域"}",
               {"n":"city","lv":"public"}]}""".trimIndent()
        "zx_retention" -> """
            {"type":"zx_retention","n":"数据保留","items":[
               {"n":"访问日志","keep":90,"age":142},
               {"n":"订单快照","keep":365,"age":210},
               {"n":"埋点原始数据","keep":30,"age":28},
               {"n":"用户画像","keep":180,"age":95}]}""".trimIndent()
        "zx_workload" -> """
            {"type":"zx_workload","n":"迭代 24 负载","people":[
               {"n":"张三","todo":14,"cap":10},{"n":"李四","todo":9,"cap":10},
               {"n":"王五","todo":3,"cap":10},{"n":"赵六","todo":11,"cap":10}]}""".trimIndent()
        "zx_bottleneck" -> """
            {"type":"zx_bottleneck","n":"发布流水线瓶颈","stages":[
               {"n":"构建","thr":12,"wip":8},{"n":"测试","thr":5,"wip":31},
               {"n":"评审","thr":18,"wip":6},{"n":"部署","thr":20,"wip":3}]}""".trimIndent()
        "zx_expresult" -> """
            {"type":"zx_expresult","n":"首页改版实验","decision":"建议上线，但只取转化提升，跳过停留时长",
             "metrics":[{"n":"转化率","lift":0.032,"p":0.004},
                        {"n":"客单价","lift":-0.008,"p":0.21},
                        {"n":"停留时长","lift":0.004,"p":0.031}]}""".trimIndent()
        "zx_compliance" -> """
            {"type":"zx_compliance","n":"等保三级自查","items":[
               {"n":"访问日志留存 6 个月","s":"pass"},
               {"n":"敏感数据加密存储","s":"fail","who":"王五"}",
               {"n":"双因素认证","s":"warn"},
               {"n":"离职账号回收","s":"fail","who":"李四"}"]}""".trimIndent()
        "zx_audittrail" -> """
            {"type":"zx_audittrail","n":"配置变更审计","entries":[
               {"who":"李四","what":"改超时 3s→8s","when":"今天 14:02","kind":"write"},
               {"who":"王五","what":"导出用户表 2 万行","when":"今天 11:37","kind":"export"},
               {"who":"张三","what":"查看密钥","when":"昨天 19:20","kind":"read"}]}""".trimIndent()
        "zx_accessreview" -> """
            {"type":"zx_accessreview","n":"生产库权限评审","stale":90,"items":[
               {"who":"张三","perm":"admin","age":420},
               {"who":"李四","perm":"read","age":30},
               {"who":"离职账号","perm":"owner","age":610},
               {"who":"王五","perm":"write","age":15}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_unitecon"  -> "cac>0 + arpu>0 + churn∈(0,1) + horizon>0；**cac/arpu 非正会被拦**（除零）"
        "zx_costattrib" -> "total>0 + items[{n,v≥0,d}] ≤8；**分项之和 ≠ 总额会被拦**（拆分是编的）"
        "zx_lineage"   -> "nodes[{n,t}] 2-10 + edges[{f,t}] ≤16；**有环会被拦**（深度算不出来）"
        "zx_impact"    -> "change 必填 + items[{t,sev∈crit|high|med|low,fix}] ≤8；不能全部缺 fix"
        "zx_dataclass" -> "cols[{n,lv∈public|internal|confidential|secret,note}] ≤10；**有列未分级会被拦**"
        "zx_retention" -> "items[{n,keep>0,age≥0}] ≤8；**keep 非正会被拦**（到期判断失效）"
        "zx_workload"  -> "people[{n,todo≥0,cap>0}] ≤8；**cap 非正会被拦**（负载率除零）"
        "zx_bottleneck" -> "stages[{n,thr>0,wip≥0}] 2-8；**thr 非正会被拦**（约束无从判断）"
        "zx_expresult" -> "metrics[{n,lift,p∈0-1}] ≤5 + decision；**给了 p 却没给 lift 会被拦**"
        "zx_compliance" -> "items[{n,s∈pass|warn|fail,who}] ≤8；**不合格项缺整改人会被拦**"
        "zx_audittrail" -> "entries[{who,what,when,kind∈read|write|delete|grant|export}] ≤10；**缺操作者会被拦**"
        "zx_accessreview" -> "items[{who,perm,age≥0}] ≤8 + stale>0；**全部缺 age 会被拦**"
        else -> "未注册组件：$component"
    }
}
