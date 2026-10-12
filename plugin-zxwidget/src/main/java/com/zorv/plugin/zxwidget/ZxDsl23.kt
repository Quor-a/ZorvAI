package com.zorv.plugin.zxwidget
import kotlin.math.abs

import org.json.JSONObject

/**
 * 第二十三批组件（推演与规则操纵）的校验 + 示例 + 规格。
 *
 * 这批查的重点是**「推不出结论」**。
 *
 * 前二十二批大多查"画不出来"（错误）或"画出来但形状/结论是错的"（误导）。
 * 这批要拦的是**"界面看起来完整，但推不出结论"**：
 *
 *   · 决策树有叶子没 `r` → 走到头却不知道答案
 *   · 决策树有节点无出口 → 输入会卡在中间节点，永远到不了叶子
 *   · 决策树条件无分支 → 那个条件选不了，路径断了
 *   · 权衡两个目标同向 → 那不是权衡，是并列
 *   · 降级链只有 1 级 → 没有备胎，谈不上"降级"
 *   · 降级链最后一级标了"还有下一级" → 用户以为还能再降
 *   · 重试 factor < 1 → 越试越快，退避失效
 *   · 重试 cap < base → 第一次就被截断，曲线是平的
 *   · 刷选 lo > hi → 区间为空，均值算出来是 0 或 NaN
 *   · 波及图 edges 指向不存在的节点 → 画出一条连到空白的线
 *   · 批量动作为空 → 没得执行
 *   · 批量无可选项 → 执行按钮点了没反应
 *   · 象限 thr 超出取值范围 → 所有项挤进一个象限
 *   · 探针无端点 → 探测不到东西
 *   · 检查器字段全空 → 没得查
 *   · 重映射成环 → a→b→a 永远替换不完
 *   · 汇总树父值 ≠ 子和 → 汇总不可信
 *
 * 共同点：这些都是**不会崩**的。
 *
 * 校验要拦的不是错误，是**"看起来能用、实际推不出结论"**——
 * 推演类组件的价值全在"如果这样会怎样"，
 * 推不出结论，它就退化成一张普通图，
 * 而用户会以为它已经帮自己算过了。
 *
 * **展示已经发生的事 ≠ 能预测将发生什么。**
 */
object ZxDsl23 {

    val ALL = listOf(
        "zx_decision", "zx_tradeoff", "zx_fallback", "zx_retry",
        "zx_brush", "zx_blast", "zx_batch", "zx_priority",
        "zx_probe", "zx_inspect", "zx_remap", "zx_rollup"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_decision"  -> decision(root)
        "zx_tradeoff"  -> tradeoff(root)
        "zx_fallback"  -> fallback(root)
        "zx_retry"     -> retry(root)
        "zx_brush"     -> brush(root)
        "zx_blast"     -> blast(root)
        "zx_batch"     -> batch(root)
        "zx_priority"  -> priority(root)
        "zx_probe"     -> probe(root)
        "zx_inspect"   -> inspect(root)
        "zx_remap"     -> remap(root)
        "zx_rollup"    -> rollup(root)
        else -> false to "未注册组件：$component"
    }

    private fun decision(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_decision 缺少 nodes"
        val n = nodes.length()
        if (n == 0) return false to "zx_decision 的 nodes 为空"
        if (n > 12) return false to "决策树最多 12 个节点，实际 $n 个"
        for (i in 0 until n) {
            val nd = nodes.optJSONObject(i)
                ?: return false to "nodes[$i] 不是对象，应为 {\"c\":条件,\"b\":[分支],\"y1\":目标,\"leaf\":,\"r\":结果}"
            val isLeaf = nd.optBoolean("leaf", false)
            if (isLeaf) {
                // 叶子必须有结果：走到头却不知道答案
                if (nd.optString("r").isBlank())
                    return false to "nodes[$i] 是叶子但缺少 r（结果）——走到头却不知道答案"
                continue
            }
            if (nd.optString("c").isBlank())
                return false to "nodes[$i] 缺少 c（判断条件）"
            val branches = nd.optJSONArray("b")
            if (branches == null || branches.length() == 0)
                return false to "nodes[$i] 缺少 b（分支）——这个条件选不了，路径会断"
            // 每个分支都得有出口，否则输入会卡在这一层
            for (k in 0 until branches.length()) {
                val y = nd.optInt("y${k + 1}", -1)
                if (y < 0)
                    return false to "nodes[$i] 的分支「${branches.optString(k, "")}」" +
                                    "没有对应出口 y${k + 1}——走到这里就卡住了"
                if (y >= n)
                    return false to "nodes[$i].y${k + 1}=$y 越界（共 $n 个节点）——指向不存在的节点"
                if (y == i)
                    return false to "nodes[$i].y${k + 1} 指向自己——会永远绕不出来"
            }
        }
        return true to ""
    }

    private fun tradeoff(o: JSONObject): Pair<Boolean, String> {
        val a = o.optString("a", "")
        val b = o.optString("b", "")
        if (a.isBlank() || b.isBlank())
            return false to "zx_tradeoff 要给两个目标名（a / b）"
        // 同向：那不是权衡，是并列
        if (o.optBoolean("same", false))
            return false to "两个目标同向——那不是权衡，是并列，用普通滑块即可"
        val t = o.optDouble("t", 0.5)
        if (t < 0 || t > 1) return false to "zx_tradeoff 的 t=$t 超出 0-1"
        val front = o.optJSONArray("front")
        if (front != null) {
            if (front.length() < 2)
                return false to "front 至少要 2 个点才能画出前沿曲线"
            for (k in 0 until front.length()) {
                val p = front.optJSONObject(k)
                    ?: return false to "front[$k] 不是对象，应为 {\"a\":0-1,\"b\":0-1}"
                val pa = p.optDouble("a", Double.NaN)
                val pb = p.optDouble("b", Double.NaN)
                if (pa.isNaN() || pb.isNaN())
                    return false to "front[$k] 缺少 a 或 b"
                if (pa < 0 || pa > 1 || pb < 0 || pb > 1)
                    return false to "front[$k]=($pa,$pb) 超出 0-1"
            }
        }
        return true to ""
    }

    private fun fallback(o: JSONObject): Pair<Boolean, String> {
        val links = o.optJSONArray("links")
            ?: return false to "zx_fallback 缺少 links"
        val n = links.length()
        // 只有 1 级：没有备胎，谈不上"降级"
        if (n < 2) return false to "降级链至少 2 级（主 + 备），实际 $n 级——没有备胎谈不上降级"
        if (n > 6) return false to "降级链最多 6 级，实际 $n 级"
        for (i in 0 until n) {
            val lk = links.optJSONObject(i)
                ?: return false to "links[$i] 不是对象，应为 {\"n\":名,\"cost\":代价,\"st\":0-2}"
            if (lk.optString("n").isBlank()) return false to "links[$i] 缺少 n"
            val st = lk.optInt("st", 0)
            if (st < 0 || st > 2)
                return false to "links[$i].st=$st 超出 0-2（0正常/1降级中/2已挂）"
        }
        val cur = o.optInt("cur", 0)
        if (cur < 0 || cur >= n)
            return false to "zx_fallback 的 cur=$cur 越界（共 $n 级）"
        // 全部已挂：无处可去，界面上会显示"当前"在一个死掉的级别上
        if ((0 until n).all { links.optJSONObject(it)?.optInt("st", 0) == 2 })
            return false to "所有级别都已挂——无处可去，降级链失去意义"
        return true to ""
    }

    private fun retry(o: JSONObject): Pair<Boolean, String> {
        val max = o.optInt("max", -1)
        if (max < 1) return false to "zx_retry 的 max=$max 至少为 1——不允许重试就不是重试策略"
        if (max > 10) return false to "zx_retry 的 max=$max 过多（上限 10）"
        val used = o.optInt("used", 0)
        if (used < 0 || used > max)
            return false to "zx_retry 的 used=$used 超出 0-$max"
        val base = o.optDouble("base", Double.NaN)
        if (base.isNaN()) return false to "zx_retry 缺少 base（首次等待秒数）"
        if (base <= 0) return false to "zx_retry 的 base=$base 必须为正"
        val factor = o.optDouble("factor", 2.0)
        // factor < 1：越试越快，退避失效
        if (factor < 1.0)
            return false to "zx_retry 的 factor=$factor 小于 1——越试越快，退避失效"
        if (factor > 10) return false to "zx_retry 的 factor=$factor 过大（上限 10）"
        val cap = o.optDouble("cap", 60.0)
        // cap < base：第一次就被截断，曲线是平的
        if (cap < base)
            return false to "zx_retry 的 cap=$cap 小于 base=$base——第一次就被截断，曲线是平的"
        return true to ""
    }

    private fun brush(o: JSONObject): Pair<Boolean, String> {
        val data = o.optJSONArray("data")
            ?: return false to "zx_brush 缺少 data"
        val n = data.length()
        if (n < 2) return false to "zx_brush 至少 2 个数据点，实际 $n 个"
        if (n > 48) return false to "zx_brush 最多 48 个数据点，实际 $n 个"
        for (k in 0 until n) {
            val v = data.optDouble(k, Double.NaN)
            if (v.isNaN()) return false to "data[$k] 不是数值"
        }
        val lo = o.optInt("lo", 0)
        val hi = o.optInt("hi", n - 1)
        if (lo < 0 || hi >= n)
            return false to "刷选区间 [$lo,$hi] 越界（共 $n 点）"
        // 区间为空：均值算出来是 0 或 NaN，用户看到的是一个假结果
        if (lo > hi)
            return false to "zx_brush 的 lo=$lo 大于 hi=$hi——区间为空，算出来的均值是假的"
        return true to ""
    }

    private fun blast(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_blast 缺少 nodes"
        val n = nodes.length()
        if (n == 0) return false to "zx_blast 的 nodes 为空"
        if (n > 10) return false to "zx_blast 最多 10 个节点，实际 $n 个"
        for (i in 0 until n) {
            val nd = nodes.optJSONObject(i)
                ?: return false to "nodes[$i] 不是对象，应为 {\"n\":名称}"
            if (nd.optString("n").isBlank()) return false to "nodes[$i] 缺少 n"
        }
        val root = o.optInt("root", 0)
        if (root < 0 || root >= n)
            return false to "zx_blast 的 root=$root 越界（共 $n 个节点）——起点不存在"
        val edges = o.optJSONArray("edges")
            ?: return false to "zx_blast 缺少 edges——没有边就推不出波及谁"
        for (k in 0 until edges.length()) {
            val e = edges.optJSONObject(k)
                ?: return false to "edges[$k] 不是对象，应为 {\"f\":从,\"t\":到}"
            val f = e.optInt("f", -1)
            val t = e.optInt("t", -1)
            if (f < 0 || t < 0) return false to "edges[$k] 缺少 f 或 t"
            // 指向不存在的节点：连线连到空白
            if (f >= n || t >= n)
                return false to "edges[$k]=($f→$t) 越界（共 $n 个节点）——会画出一条连到空白的线"
            if (f == t)
                return false to "edges[$k] 是自环（f=t=$f）——自己波及自己没有意义"
        }
        return true to ""
    }

    private fun batch(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_batch 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_batch 的 items 为空——没有可操作的对象"
        if (n > 10) return false to "zx_batch 最多 10 项，实际 $n 项"
        for (i in 0 until n) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名称}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
        }
        val acts = o.optJSONArray("acts")
        // 没得执行：按钮点了没反应
        if (acts == null || acts.length() == 0)
            return false to "zx_batch 缺少 acts（可执行的动作）——没得执行，按钮点了没反应"
        if (acts.length() > 3) return false to "zx_batch 最多 3 个动作，实际 ${acts.length()} 个"
        for (k in 0 until acts.length())
            if (acts.optString(k).isBlank()) return false to "acts[$k] 是空的"
        return true to ""
    }

    private fun priority(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_priority 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_priority 的 items 为空"
        if (n > 8) return false to "zx_priority 最多 8 项，实际 $n 项"
        val thr = o.optDouble("thr", Double.NaN)
        if (thr.isNaN()) return false to "zx_priority 缺少 thr（分象限的阈值）"
        for (i in 0 until n) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"x\":价值0-100,\"y\":成本0-100}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val x = it.optDouble("x", Double.NaN)
            val y = it.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN())
                return false to "items[$i] 缺少 x（价值）或 y（成本）——不知道放哪个象限"
            if (x < 0 || x > 100 || y < 0 || y > 100)
                return false to "items[$i]=($x,$y) 超出 0-100"
        }
        // 阈值超出取值范围：所有项挤进一个象限
        if (thr <= 0 || thr >= 100)
            return false to "zx_priority 的 thr=$thr 超出 0-100——所有项会挤进同一个象限"
        return true to ""
    }

    private fun probe(o: JSONObject): Pair<Boolean, String> {
        val eps = o.optJSONArray("eps")
            ?: return false to "zx_probe 缺少 eps"
        val n = eps.length()
        // 无端点：探测不到东西
        if (n == 0) return false to "zx_probe 的 eps 为空——没有端点可探"
        if (n > 8) return false to "zx_probe 最多 8 个端点，实际 $n 个"
        for (i in 0 until n) {
            val e = eps.optJSONObject(i)
                ?: return false to "eps[$i] 不是对象，应为 {\"n\":端点,\"ms\":耗时,\"rc\":状态码}"
            if (e.optString("n").isBlank()) return false to "eps[$i] 缺少 n（端点名）"
            val ms = e.optDouble("ms", Double.NaN)
            if (ms.isNaN()) return false to "eps[$i] 缺少 ms（耗时）——不知道谁慢"
            if (ms < 0) return false to "eps[$i].ms=$ms 不能为负"
        }
        val slow = o.optDouble("slow", 300.0)
        if (slow <= 0) return false to "zx_probe 的 slow=$slow 必须为正"
        return true to ""
    }

    private fun inspect(o: JSONObject): Pair<Boolean, String> {
        val fields = o.optJSONArray("fields")
            ?: return false to "zx_inspect 缺少 fields"
        val n = fields.length()
        if (n == 0) return false to "zx_inspect 的 fields 为空——没有字段可查"
        if (n > 10) return false to "zx_inspect 最多 10 个字段，实际 $n 个"
        val okSrc = setOf("user", "ai", "ref", "default", "derived")
        for (i in 0 until n) {
            val f = fields.optJSONObject(i)
                ?: return false to "fields[$i] 不是对象，应为 {\"k\":字段名,\"v\":值,\"src\":来源,\"ok\":,\"at\":,\"why\":,\"from\":}"
            if (f.optString("k").isBlank()) return false to "fields[$i] 缺少 k（字段名）"
            val src = f.optString("src", "")
            // 来源必须标：来源不明的值不该被当真
            if (src.isBlank())
                return false to "fields[$i] 缺少 src（来源）——来源不明的值不该被当真"
            if (src !in okSrc)
                return false to "fields[$i].src=\"$src\" 不支持（可选：${okSrc.joinToString("/")}）"
        }
        return true to ""
    }

    private fun remap(o: JSONObject): Pair<Boolean, String> {
        val rules = o.optJSONArray("rules")
            ?: return false to "zx_remap 缺少 rules"
        val n = rules.length()
        if (n == 0) return false to "zx_remap 的 rules 为空"
        if (n > 10) return false to "zx_remap 最多 10 条规则，实际 $n 条"
        val map = HashMap<String, String>()
        val dupF = ArrayList<String>()
        for (i in 0 until n) {
            val r = rules.optJSONObject(i)
                ?: return false to "rules[$i] 不是对象，应为 {\"f\":原值,\"t\":新值,\"n\":说明}"
            val f = r.optString("f", "")
            val t = r.optString("t", "")
            if (f.isBlank()) return false to "rules[$i] 缺少 f（原值）"
            if (t.isBlank()) return false to "rules[$i] 缺少 t（新值）"
            // 自己换自己：无意义的规则
            if (f == t) return false to "rules[$i] 的 f 和 t 都是「$f」——自己换自己没有意义"
            if (map.containsKey(f)) dupF.add(f)
            map[f] = t
        }
        // 同一个原值有两条规则：用哪条不确定
        if (dupF.isNotEmpty())
            return false to "有重复的原值（${dupF.joinToString("、")}）——同一个值有两条规则，用哪条不确定"
        // 成环：a→b→a 永远替换不完
        for (k in map.keys) {
            val seen = LinkedHashSet<String>()
            var cur = k
            while (cur.isNotBlank() && seen.add(cur)) cur = map[cur] ?: ""
            if (map[cur] != null && cur.isNotBlank())
                return false to "规则成环：${seen.joinToString("→")}→$cur——会永远替换不完"
        }
        return true to ""
    }

    private fun rollup(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_rollup 缺少 rows"
        val n = rows.length()
        if (n == 0) return false to "zx_rollup 的 rows 为空"
        if (n > 14) return false to "zx_rollup 最多 14 行，实际 $n 行"
        for (i in 0 until n) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"n\":名称,\"v\":数值,\"lv\":层级}"
            if (r.optString("n").isBlank()) return false to "rows[$i] 缺少 n"
            val v = r.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "rows[$i] 缺少 v"
            val lv = r.optInt("lv", 1)
            if (lv < 1 || lv > 3) return false to "rows[$i].lv=$lv 超出 1-3"
            // 首行必须是顶层：否则不知道从哪汇总
            if (i == 0 && lv != 1)
                return false to "rows[0].lv=$lv 应为 1——第一行必须是顶层"
            // 子层不能跳过层级：lv 从 1 直接到 3 中间没有 2
            if (i > 0) {
                val prev = rows.optJSONObject(i - 1)?.optInt("lv", 1) ?: 1
                if (lv > prev + 1)
                    return false to "rows[$i].lv=$lv 比上一行（$prev）跳了不止一级——中间会缺一层"
            }
        }
        // 父值 ≠ 子和：汇总不可信
        for (i in 0 until n) {
            val lv = rows.optJSONObject(i)?.optInt("lv", 1) ?: 1
            if (lv != 1) continue
            var s = 0.0
            var hasKid = false
            for (j in i + 1 until n) {
                val lj = rows.optJSONObject(j)?.optInt("lv", 1) ?: 1
                if (lj <= lv) break
                if (lj == lv + 1) { s += rows.optJSONObject(j)?.optDouble("v", 0.0) ?: 0.0; hasKid = true }
            }
            if (hasKid && abs(s - (rows.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0)) > 0.01)
                return false to "rows[$i]（${rows.optJSONObject(i)?.optString("n", "")}）" +
                                "的值 %.2f ≠ 子项之和 %.2f——汇总不可信".format(
                                    rows.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0, s)
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_decision" -> """
            {"type":"zx_decision","n":"退款审批","nodes":[
              {"c":"金额 > 500?","b":["是","否"],"y1":1,"y2":2},
              {"c":"有凭证?","b":["有","无"],"y1":3,"y2":4},
              {"leaf":true,"r":"自动通过"}",
              {"leaf":true,"r":"人工复核"}",
              {"leaf":true,"r":"驳回"}"]}""".trimIndent()
        "zx_tradeoff" -> """
            {"type":"zx_tradeoff","n":"速度与质量","a":"速度","b":"质量","t":0.5}""".trimIndent()
        "zx_fallback" -> """
            {"type":"zx_fallback","n":"支付链路","cur":1,"links":[
              {"n":"主支付","cost":"全量","st":2},
              {"n":"备用通道","cost":"手续费 +0.6%","st":0},
              {"n":"离线记账","cost":"延后对账","st":0}]}""".trimIndent()
        "zx_retry" -> """
            {"type":"zx_retry","n":"下单重试","max":5,"used":2,"base":1,"factor":2,"cap":30}""".trimIndent()
        "zx_brush" -> """
            {"type":"zx_brush","n":"QPS 区间","lo":10,"hi":30,
             "data":[120,130,125,140,180,210,240,230,200,170,
                     150,140,145,160,190,220,260,250,210,180,
                     160,150,155,170,200,240,280,270,230,200,
                     180,170,175,190,220,260,300,290,250,220]}""".trimIndent()
        "zx_blast" -> """
            {"type":"zx_blast","n":"改动波及","root":0,
             "nodes":[{"n":"UserRepo"},{"n":"ProfileVM"},{"n":"MainAct"},{"n":"SettingUI"}],
             "edges":[{"f":0,"t":1},{"f":1,"t":2},{"f":1,"t":3}]}""".trimIndent()
        "zx_batch" -> """
            {"type":"zx_batch","n":"批量发布","acts":["发布","回滚","下线"],
             "items":[{"n":"服务A"},{"n":"服务B"},{"n":"服务C"}]}""".trimIndent()
        "zx_priority" -> """
            {"type":"zx_priority","n":"需求排序","thr":50,"items":[
              {"n":"搜索优化","x":80,"y":25},
              {"n":"换肤","x":20,"y":70},
              {"n":"导出","x":60,"y":30}]}""".trimIndent()
        "zx_probe" -> """
            {"type":"zx_probe","n":"健康检查","slow":300,"eps":[
              {"n":"api-1","ms":120,"rc":0},
              {"n":"api-2","ms":520,"rc":0},
              {"n":"db-1","ms":80,"rc":0}]}""".trimIndent()
        "zx_inspect" -> """
            {"type":"zx_inspect","n":"报价字段","fields":[
              {"k":"price","v":"1200","src":"user","ok":true},
              {"k":"tax","v":"96","src":"derived","from":"price*0.08"},
              {"k":"ship","v":"0","src":"default","ok":false}]}""".trimIndent()
        "zx_remap" -> """
            {"type":"zx_remap","n":"状态码归一","rules":[
              {"f":"OK","t":"0"},{"f":"SUCCESS","t":"0"},
              {"f":"FAIL","t":"1","n":"失败统一为 1"}]}""".trimIndent()
        "zx_rollup" -> """
            {"type":"zx_rollup","n":"成本汇总","rows":[
              {"n":"总成本","v":300,"lv":1},
              {"n":"人力","v":200,"lv":2},
              {"n":"开发","v":120,"lv":3},
              {"n":"测试","v":80,"lv":3},
              {"n":"基建","v":100,"lv":2}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_decision"  -> "nodes[{c,b[],y1..yn | leaf:true,r}]  ≤12 · 叶子必须有 r，每个分支必须有出口"
        "zx_tradeoff"  -> "a / b 两个目标名 + t 0-1 · 同向不是权衡；front 可选 ≥2 点"
        "zx_fallback"  -> "links[{n,cost?,st0-2}]  2-6 · 至少 2 级，不可全挂，末级标终点"
        "zx_retry"     -> "max 1-10 + base>0 + factor≥1 + cap≥base · used∈0..max"
        "zx_brush"     -> "data[]  2-48 + lo≤hi · 区间为空则均值是假的"
        "zx_blast"     -> "nodes  ≤10 + edges[{f,t}] · 起点须存在，边不可越界或自环"
        "zx_batch"     -> "items[{n}]  ≤10 + acts[]  ≤3 · 缺 acts 则按钮点了没反应"
        "zx_priority"  -> "items[{n,x,y}]  ≤8 + thr∈0-100 · 阈值越界会挤进一个象限"
        "zx_probe"     -> "eps[{n,ms,rc?}]  ≤8 · 缺 ms 不知道谁慢"
        "zx_inspect"   -> "fields[{k,v,src,ok?,at?,why?,from?}]  ≤10 · src 必填"
        "zx_remap"     -> "rules[{f,t,n?}]  ≤10 · 不可自换、不可重复、不可成环"
        "zx_rollup"    -> "rows[{n,v,lv1-3}]  ≤14 · 首行 lv=1，父值须等于子和"
        else -> "未注册组件：$component"
    }
}
