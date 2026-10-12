package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 第十二批组件（组织 / 流向 / 导航 / 采集）的校验 + 示例 + 规格。
 *
 * 这批查的是**导航能不能真的导航、采集能不能真的采集到值**：
 *
 *   · 面包屑没有 cur 或 cur 越界，就没有"当前项"，
 *     它退化成一排文字而不是导航
 *   · 分页 cur 超出 total，会显示一个不存在的页码
 *   · 桑基图 v 全为 0，带子宽度为 0，整张图是空的
 *   · 步进器初始值不在步长刻度上，第一次点 ± 会跳值
 *   · 芯片组全不选且不是多选，用户没法取消
 *
 * 这些同样不崩。它们只是让组件"能点但点不出预期结果"——
 * 用户会归因为"这个 AI 不好用"，而不是"DSL 错了"。
 */
object ZxDsl12 {

    val ALL = listOf(
        "zx_orgchart", "zx_sankey", "zx_gauge", "zx_sparkgrid",
        "zx_breadcrumb", "zx_pagination", "zx_tabbar", "zx_searchbar",
        "zx_filterchips", "zx_colorpick", "zx_stepper", "zx_treeview"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_orgchart"  -> orgchart(root)
        "zx_sankey"    -> sankey(root)
        "zx_gauge"     -> gauge(root)
        "zx_sparkgrid" -> sparkgrid(root)
        "zx_breadcrumb"-> breadcrumb(root)
        "zx_pagination"-> pagination(root)
        "zx_tabbar"    -> tabbar(root)
        "zx_searchbar" -> searchbar(root)
        "zx_filterchips"     -> chips(root)
        "zx_colorpick" -> colorpick(root)
        "zx_stepper"   -> stepper(root)
        "zx_treeview"  -> treeview(root)
        else -> false to "未注册组件：$component"
    }

    /** 递归统计节点数，超过上限就拦——气泡里放不下整棵树 */
    private fun countNodes(n: JSONObject?, cap: Int, cur: Int = 0, depth: Int = 0): Int {
        if (n == null || cur >= cap || depth > 4) return cur
        var c = cur + 1
        val kids = n.optJSONArray("kids") ?: return c
        for (i in 0 until kids.length()) c = countNodes(kids.optJSONObject(i), cap, c, depth + 1)
        return c
    }

    private fun orgchart(o: JSONObject): Pair<Boolean, String> {
        val root = o.optJSONObject("root")
            ?: return false to "zx_orgchart 缺少 root"
        if (root.optString("n").isBlank())
            return false to "root 缺少 n（节点名）"
        val d = o.optInt("depth", 3)
        if (d !in 2..4) return false to "zx_orgchart 的 depth=$d 超出 2-4"
        val n = countNodes(root, 24)
        if (n > 24) return false to "组织架构最多 24 个节点，实际 $n 个——气泡里放不下整棵树"
        return true to ""
    }

    private fun sankey(o: JSONObject): Pair<Boolean, String> {
        val flows = o.optJSONArray("flows")
            ?: return false to "zx_sankey 缺少 flows"
        if (flows.length() == 0) return false to "zx_sankey 的 flows 为空"
        if (flows.length() > 7) return false to "桑基图最多 7 条流，实际 ${flows.length()} 条"
        var total = 0.0
        for (i in 0 until flows.length()) {
            val f = flows.optJSONObject(i)
                ?: return false to "flows[$i] 不是对象，应为 {\"s\":来源,\"d\":去向,\"v\":流量}"
            if (f.optString("s").isBlank()) return false to "flows[$i] 缺少 s（来源名）"
            if (f.optString("d").isBlank()) return false to "flows[$i] 缺少 d（去向名）"
            val v = f.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "flows[$i] 缺少 v（流量）"
            if (v <= 0) return false to "flows[$i].v=$v 必须为正，否则带子宽度为 0，看不见"
            total += v
        }
        // 只有一条流就不是"流向图"了
        if (flows.length() == 1)
            return false to "只有 1 条流——看不出流向，用 zx_barchart 更合适"
        return true to ""
    }

    private fun gauge(o: JSONObject): Pair<Boolean, String> {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", Double.NaN)
        if (max.isNaN()) return false to "zx_gauge 缺少 max（量程上限）"
        if (max <= min) return false to "zx_gauge 的 max($max) 必须大于 min($min)"
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return false to "zx_gauge 缺少 v（当前值）——指针不知道停在哪"
        if (v < min || v > max)
            return false to "zx_gauge 的 v=$v 超出 [$min,$max]，指针会停在量程外"
        val zones = o.optJSONArray("zones")
        if (zones != null) {
            if (zones.length() !in 2..5)
                return false to "zones 需 2-5 段，实际 ${zones.length()} 段"
            var prevFrom = min
            for (i in 0 until zones.length()) {
                val z = zones.optJSONObject(i)
                    ?: return false to "zones[$i] 不是对象，应为 {\"from\":,\"to\":,\"hex\":\"#RRGGBB\"}"
                val from = z.optDouble("from", Double.NaN)
                val to = z.optDouble("to", Double.NaN)
                if (from.isNaN()) return false to "zones[$i] 缺少 from"
                if (to.isNaN()) return false to "zones[$i] 缺少 to"
                if (to <= from) return false to "zones[$i] 的 to($to) 必须大于 from($from)"
                if (from < min || to > max)
                    return false to "zones[$i] 的范围 [$from,$to] 超出量程 [$min,$max]"
                val hex = z.optString("hex")
                if (!hex.matches(Regex("#[0-9A-Fa-f]{6}")))
                    return false to "zones[$i].hex=\"$hex\" 不是合法的 #RRGGBB"
                prevFrom = to
            }
        }
        return true to ""
    }

    private fun sparkgrid(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_sparkgrid 缺少 items"
        if (items.length() == 0) return false to "zx_sparkgrid 的 items 为空"
        // 2 列布局：3 列会让每个图只剩 90dp
        if (items.length() > 6) return false to "迷你图网格最多 6 个（2 列），实际 ${items.length()} 个"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"pts\":[...]}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（图名）"
            val pts = it.optJSONArray("pts")
                ?: return false to "items[$i] 缺少 pts（数据点数组）"
            if (pts.length() < 2) return false to "items[$i].pts 至少 2 个点，实际 ${pts.length()} 个"
            if (pts.length() > 40)
                return false to "items[$i].pts 最多 40 个点，实际 ${pts.length()} 个（迷你图不需要这么多）"
            for (k in 0 until pts.length()) {
                val d = pts.optDouble(k, Double.NaN)
                if (d.isNaN()) return false to "items[$i].pts[$k] 不是数值"
            }
        }
        return true to ""
    }

    private fun breadcrumb(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_breadcrumb 缺少 items"
        if (items.length() < 2) return false to "面包屑至少 2 项，实际 ${items.length()} 项"
        if (items.length() > 6) return false to "面包屑最多 6 项，实际 ${items.length()} 项"
        for (i in 0 until items.length())
            if (items.optString(i).isBlank()) return false to "items[$i] 是空字符串"
        val cur = o.optInt("cur", items.length() - 1)
        // 没有合法 cur 就没有"当前项"，它退化成一排文字而不是导航
        if (cur !in 0 until items.length())
            return false to "cur=$cur 超出 0-${items.length() - 1}——没有当前项高亮，它退化成一排文字而不是导航"
        return true to ""
    }

    private fun pagination(o: JSONObject): Pair<Boolean, String> {
        val total = o.optInt("total", 5)
        if (total !in 1..999) return false to "zx_pagination 的 total=$total 超出 1-999"
        val cur = o.optInt("cur", 1)
        // cur 超出 total 会显示一个不存在的页码
        if (cur !in 1..total)
            return false to "cur=$cur 超出 1-$total——会显示一个不存在的页码"
        return true to ""
    }

    private fun tabbar(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_tabbar 缺少 items"
        if (items.length() < 2) return false to "标签栏至少 2 项，实际 ${items.length()} 项"
        if (items.length() > 5) return false to "标签栏最多 5 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"k\":键,\"n\":名,\"e\":图标}"
            if (it.optString("k").isBlank()) return false to "items[$i] 缺少 k（回传键）"
            if (it.optString("n").isBlank())
                return false to "items[$i] 缺少 n（标签名）——只有图标看不出是什么"
        }
        val cur = o.optInt("cur", 0)
        if (cur !in 0 until items.length())
            return false to "cur=$cur 超出 0-${items.length() - 1}"
        return true to ""
    }

    private fun searchbar(o: JSONObject): Pair<Boolean, String> {
        val v = o.optString("v")
        if (v.length > 200)
            return false to "zx_searchbar 的 v 超过 200 字（实际 ${v.length}）——搜索词不该这么长"
        val sugg = o.optJSONArray("sugg")
        if (sugg != null && sugg.length() > 6)
            return false to "sugg 最多 6 个，实际 ${sugg.length()} 个（气泡里放不下）"
        return true to ""
    }

    private fun chips(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_chips 缺少 items"
        if (items.length() < 2) return false to "芯片组至少 2 个，实际 ${items.length()} 个"
        if (items.length() > 10) return false to "芯片组最多 10 个，实际 ${items.length()} 个"
        val seen = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            val t = items.optString(i)
            if (t.isBlank()) return false to "items[$i] 是空字符串"
            if (!seen.add(t.trim()))
                return false to "items[$i]=\"$t\" 重复——回传时无法区分选的是哪个"
        }
        return true to ""
    }

    private fun colorpick(o: JSONObject): Pair<Boolean, String> {
        val sw = o.optJSONArray("swatches")
            ?: return true to ""      // 允许用默认色板
        if (sw.length() == 0) return false to "zx_colorpick 的 swatches 为空"
        if (sw.length() > 12) return false to "取色板最多 12 个色块，实际 ${sw.length()} 个"
        for (i in 0 until sw.length()) {
            val s = sw.optString(i)
            if (!s.matches(Regex("#[0-9A-Fa-f]{6}")))
                return false to "swatches[$i]=\"$s\" 不是合法的 #RRGGBB"
        }
        return true to ""
    }

    private fun stepper(o: JSONObject): Pair<Boolean, String> {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", Double.NaN)
        if (max.isNaN()) return false to "zx_stepper 缺少 max"
        if (max < min) return false to "zx_stepper 的 max($max) 小于 min($min)"
        val step = o.optDouble("step", 1.0)
        if (step <= 0) return false to "zx_stepper 的 step=$step 必须为正"
        if (step > (max - min) && max > min)
            return false to "zx_stepper 的 step=$step 超过量程 ${max - min}，一加就越界"
        val v = o.optDouble("v", Double.NaN)
        if (!v.isNaN()) {
            if (v < min || v > max)
                return false to "zx_stepper 的 v=$v 超出 [$min,$max]"
            // 初始值不在刻度上，第一次点 ± 会跳值
            val off = ((v - min) / step)
            if (kotlin.math.abs(off - kotlin.math.round(off)) > 1e-6)
                return false to "zx_stepper 的 v=$v 不在步长刻度上（min=$min step=$step）——第一次点 ± 会跳值"
        }
        return true to ""
    }

    private fun treeview(o: JSONObject): Pair<Boolean, String> {
        val root = o.optJSONObject("root")
            ?: return false to "zx_treeview 缺少 root"
        if (root.optString("n").isBlank()) return false to "root 缺少 n（节点名）"
        if (root.optString("k").isBlank())
            return false to "root 缺少 k（节点键）——勾选后无法知道选的是哪个"
        val n = countNodes(root, 30)
        if (n > 30) return false to "层级树最多 30 个节点，实际 $n 个"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_orgchart" -> """
            {"type":"zx_orgchart","depth":3,"root":
              {"k":"ceo","n":"张三","r":"CEO","kids":[
                {"k":"tech","n":"李四","r":"技术","kids":[{"k":"a1","n":"王五"}"]},
                {"k":"ops","n":"赵六","r":"运营"}"]}}""".trimIndent()
        "zx_sankey" -> """
            {"type":"zx_sankey","flows":[
              {"s":"首页","d":"列表","v":1000},{"s":"列表","d":"详情","v":620},
              {"s":"首页","d":"搜索","v":380}]}""".trimIndent()
        "zx_gauge" -> """
            {"type":"zx_gauge","n":"CPU","min":0,"max":100,"v":72,"unit":"%",
             "zones":[{"from":0,"to":60,"hex":"#3FBF7F"},
                      {"from":60,"to":100,"hex":"#E5544B"}]}""".trimIndent()
        "zx_sparkgrid" -> """
            {"type":"zx_sparkgrid","items":[
              {"n":"日活","pts":[12,18,15,22,28,25]},
              {"n":"留存","pts":[40,38,35,33,30,29]}]}""".trimIndent()
        "zx_breadcrumb" -> """
            {"type":"zx_breadcrumb","cur":2,
             "items":["项目","组件库","zx_gauge"]}""".trimIndent()
        "zx_pagination" -> """
            {"type":"zx_pagination","total":12,"cur":3}""".trimIndent()
        "zx_tabbar" -> """
            {"type":"zx_tabbar","cur":0,"items":[
              {"k":"chat","n":"对话","e":"◉"},{"k":"card","n":"卡片","e":"◆"}]}""".trimIndent()
        "zx_searchbar" -> """
            {"type":"zx_searchbar","hint":"搜索组件…","v":"",
             "sugg":["zx_gauge","zx_sankey","zx_orgchart"]}""".trimIndent()
        "zx_filterchips" -> """
            {"type":"zx_filterchips","multi":true,
             "items":["全部","数据","导航","输入"]}""".trimIndent()
        "zx_colorpick" -> """
            {"type":"zx_colorpick",
             "swatches":["#5046E4","#3FBF7F","#E8A33D","#E5544B"]}""".trimIndent()
        "zx_stepper" -> """
            {"type":"zx_stepper","n":"并发数","min":1,"max":16,"step":1,"v":4}""".trimIndent()
        "zx_treeview" -> """
            {"type":"zx_treeview","root":
              {"k":"r","n":"全部","kids":[
                {"k":"a","n":"渲染层","kids":[{"k":"a1","n":"ZxRender"}]},
                {"k":"b","n":"校验层"}"]}}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_orgchart"  -> "root{k,n,r?,kids[]} + depth(2-4) · ≤24 节点"
        "zx_sankey"    -> "flows[{s,d,v>0}]  2-7 条 · 单一流会被拦"
        "zx_gauge"     -> "min,max,v + n? + unit? + zones?[{from,to,hex}] 2-5 段"
        "zx_sparkgrid" -> "items[{n,pts[]}]  ≤6 个 · pts 2-40 点"
        "zx_breadcrumb"-> "items[] 2-6 + cur(须在范围内)"
        "zx_pagination"-> "total(1-999) + cur(1..total)"
        "zx_tabbar"    -> "items[{k,n,e?}]  2-5 · cur 须在范围内"
        "zx_searchbar" -> "v? + hint? + sugg?(≤6)  回车才回传"
        "zx_filterchips"     -> "items[] 2-10 + multi?  不可重复"
        "zx_colorpick" -> "swatches?[]  ≤12 · 合法 #RRGGBB"
        "zx_stepper"   -> "min,max,step,v  v 须落在步长刻度上"
        "zx_treeview"  -> "root{k,n,kids?}  ≤30 节点 · 父级半选显示 ─"
        else -> "未注册组件：$component"
    }
}
