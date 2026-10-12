package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 第九批组件（复合图表 / 结构图 / 复合容器）的校验 + 示例 + 规格。
 *
 * 这批校验的重心是**形状前提**——这些组件的形状本身就是信息，
 * 数据不满足前提时图会画出来但读不出意思：
 *
 *   · 金字塔层级必须递减，乱序就退化成普通条形图
 *   · 韦恩最多 3 组，4 组画不出可读的图
 *   · K线每根必须 低 ≤ min(开,收) 且 max(开,收) ≤ 高，
 *     否则蜡烛会画反或画成负高度
 *   · DAG 的边下标必须合法且不能自环，自环节点永远排不进层
 *   · 气泡的 x/y 必须是数值，r 必须为正
 */
object ZxDsl9 {

    val ALL = listOf(
        "zx_waterfall", "zx_pyramid", "zx_bubble", "zx_venn",
        "zx_candle", "zx_dag", "zx_swimlane", "zx_mindmap",
        "zx_gallery", "zx_wizard", "zx_spreadsheet", "zx_conversation"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_waterfall"   -> waterfall(root)
        "zx_pyramid"     -> pyramid(root)
        "zx_bubble"      -> bubble(root)
        "zx_venn"        -> venn(root)
        "zx_candle"      -> candle(root)
        "zx_dag"         -> dag(root)
        "zx_swimlane"    -> swimlane(root)
        "zx_mindmap"     -> mindmap(root)
        "zx_gallery"     -> gallery(root)
        "zx_wizard"      -> wizard(root)
        "zx_spreadsheet" -> spreadsheet(root)
        "zx_conversation"-> conversation(root)
        else -> false to "未注册组件：$component"
    }

    private fun waterfall(o: JSONObject): Pair<Boolean, String> {
        val steps = o.optJSONArray("steps")
            ?: return false to "zx_waterfall 缺少 steps"
        if (steps.length() < 2) return false to "瀑布图至少 2 步，实际 ${steps.length()} 步"
        if (steps.length() > 8) return false to "瀑布图最多 8 步，实际 ${steps.length()} 步"
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i)
                ?: return false to "steps[$i] 不是对象，应为 {\"n\":名,\"v\":增量}"
            if (s.optString("n").isBlank()) return false to "steps[$i] 缺少 n（步骤名）"
            val v = s.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "steps[$i] 缺少 v（增量，可正可负）"
            if (kotlin.math.abs(v) > 1e12)
                return false to "steps[$i].v=$v 绝对值过大，柱子算不出可见高度"
        }
        // 全为正就不是瀑布图了，用柱状图更合适
        val allPos = (0 until steps.length()).all { steps.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 >= 0 }
        if (allPos) return false to "所有 steps 的 v 都是非负——那是柱状图不是瀑布图，用 zx_chart 更合适"
        return true to ""
    }

    private fun pyramid(o: JSONObject): Pair<Boolean, String> {
        val levels = o.optJSONArray("levels")
            ?: return false to "zx_pyramid 缺少 levels"
        if (levels.length() < 2) return false to "金字塔至少 2 层，实际 ${levels.length()} 层"
        if (levels.length() > 7) return false to "金字塔最多 7 层，实际 ${levels.length()} 层"
        var prev = Double.POSITIVE_INFINITY
        for (i in 0 until levels.length()) {
            val l = levels.optJSONObject(i)
                ?: return false to "levels[$i] 不是对象，应为 {\"n\":名,\"v\":值}"
            if (l.optString("n").isBlank()) return false to "levels[$i] 缺少 n（层名）"
            val v = l.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "levels[$i] 缺少 v（数值）"
            if (v < 0) return false to "levels[$i].v=$v 为负，金字塔宽度算不出来"
            // 形状本身就是信息：乱序就退化成条形图
            if (v > prev)
                return false to "levels[$i].v=$v 大于上一层 $prev——金字塔层级必须递减，否则形状不再是金字塔"
            prev = v
        }
        return true to ""
    }

    private fun bubble(o: JSONObject): Pair<Boolean, String> {
        val points = o.optJSONArray("points")
            ?: return false to "zx_bubble 缺少 points"
        if (points.length() < 2) return false to "气泡图至少 2 个点，实际 ${points.length()} 个"
        if (points.length() > 14) return false to "气泡图最多 14 个点，实际 ${points.length()} 个"
        for (i in 0 until points.length()) {
            val p = points.optJSONObject(i)
                ?: return false to "points[$i] 不是对象，应为 {\"x\":,\"y\":,\"r\":,\"n\":}"
            val x = p.optDouble("x", Double.NaN)
            val y = p.optDouble("y", Double.NaN)
            if (x.isNaN()) return false to "points[$i] 缺少 x（横轴值，必须是数值）"
            if (y.isNaN()) return false to "points[$i] 缺少 y（纵轴值，必须是数值）"
            val r = p.optDouble("r", Double.NaN)
            if (r.isNaN()) return false to "points[$i] 缺少 r（气泡半径）"
            if (r <= 0) return false to "points[$i].r=$r 必须为正，否则气泡画不出来"
            if (p.optString("n").isBlank())
                return false to "points[$i] 缺少 n（名称）——气泡重叠时只能靠下方图例点选"
        }
        // 所有点 x/y 全相同会缩成一个点
        val xs = (0 until points.length()).map { points.optJSONObject(it)?.optDouble("x", 0.0) ?: 0.0 }
        if (xs.maxOrNull()!! - xs.minOrNull()!! < 1e-9)
            return false to "所有点的 x 都一样，气泡会叠成一列——横轴没有变化量"
        return true to ""
    }

    private fun venn(o: JSONObject): Pair<Boolean, String> {
        val groups = o.optJSONArray("groups")
            ?: return false to "zx_venn 缺少 groups"
        if (groups.length() < 2) return false to "韦恩图至少 2 组，实际 ${groups.length()} 组"
        // 4 组以上画不出可读的韦恩图
        if (groups.length() > 3) return false to "韦恩图最多 3 组，实际 ${groups.length()} 组——4 组画不出可读的图"
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i)
                ?: return false to "groups[$i] 不是对象，应为 {\"n\":名,\"v\":值,\"hex\":色}"
            if (g.optString("n").isBlank()) return false to "groups[$i] 缺少 n（组名）"
            val v = g.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "groups[$i] 缺少 v（数值）"
            val hex = g.optString("hex")
            if (hex.isNotBlank() && !hex.matches(Regex("#[0-9A-Fa-f]{6}")))
                return false to "groups[$i].hex=\"$hex\" 不是合法的 #RRGGBB"
        }
        return true to ""
    }

    private fun candle(o: JSONObject): Pair<Boolean, String> {
        val bars = o.optJSONArray("bars")
            ?: return false to "zx_candle 缺少 bars"
        if (bars.length() == 0) return false to "zx_candle 的 bars 为空"
        if (bars.length() > 24) return false to "K线最多 24 根，实际 ${bars.length()} 根"
        for (i in 0 until bars.length()) {
            val b = bars.optJSONObject(i)
                ?: return false to "bars[$i] 不是对象，应为 {\"h\":高,\"l\":低,\"o\":开,\"c\":收}"
            val hi = b.optDouble("h", Double.NaN); val lo = b.optDouble("l", Double.NaN)
            val op = b.optDouble("o", Double.NaN); val cl = b.optDouble("c", Double.NaN)
            if (hi.isNaN()) return false to "bars[$i] 缺少 h（最高价）"
            if (lo.isNaN()) return false to "bars[$i] 缺少 l（最低价）"
            if (op.isNaN()) return false to "bars[$i] 缺少 o（开盘价）"
            if (cl.isNaN()) return false to "bars[$i] 缺少 c（收盘价）"
            // 蜡烛体必须落在影线内，否则实体会画到影线外面去
            if (lo > min(op, cl) + 1e-9)
                return false to "bars[$i] 的 l=$lo 高于 min(o,c)=${min(op, cl)}——实体会画到影线外"
            if (hi < max(op, cl) - 1e-9)
                return false to "bars[$i] 的 h=$hi 低于 max(o,c)=${max(op, cl)}——实体会画到影线外"
            if (hi < lo) return false to "bars[$i] 的 h=$hi 低于 l=$lo，影线是负的"
        }
        return true to ""
    }

    private fun dag(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_dag 缺少 nodes"
        if (nodes.length() < 2) return false to "DAG 至少 2 个节点，实际 ${nodes.length()} 个"
        if (nodes.length() > 10) return false to "DAG 最多 10 个节点，实际 ${nodes.length()} 个"
        for (i in 0 until nodes.length()) {
            if (nodes.optString(i).isBlank()) return false to "nodes[$i] 是空字符串"
        }
        val edges = o.optJSONArray("edges")
            ?: return false to "zx_dag 缺少 edges（依赖边），没有边就只是个列表"
        if (edges.length() == 0) return false to "zx_dag 的 edges 为空"
        if (edges.length() > 20) return false to "DAG 最多 20 条边，实际 ${edges.length()} 条"
        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i)
                ?: return false to "edges[$i] 不是对象，应为 {\"f\":起点下标,\"t\":终点下标}"
            val f = e.optInt("f", -1); val t = e.optInt("t", -1)
            if (f !in 0 until nodes.length())
                return false to "edges[$i].f=$f 超出 0-${nodes.length() - 1}"
            if (t !in 0 until nodes.length())
                return false to "edges[$i].t=$t 超出 0-${nodes.length() - 1}"
            // 自环节点永远排不进任何一层
            if (f == t) return false to "edges[$i] 是自环（f=t=$f）——该节点永远排不进任何一层"
        }
        return true to ""
    }

    private fun swimlane(o: JSONObject): Pair<Boolean, String> {
        val lanes = o.optJSONArray("lanes")
            ?: return false to "zx_swimlane 缺少 lanes"
        if (lanes.length() < 2) return false to "泳道至少 2 条，实际 ${lanes.length()} 条"
        if (lanes.length() > 5) return false to "泳道最多 5 条，实际 ${lanes.length()} 条"
        var size: Int? = null
        for (i in 0 until lanes.length()) {
            val l = lanes.optJSONObject(i)
                ?: return false to "lanes[$i] 不是对象，应为 {\"n\":泳道名,\"cells\":[...]}"
            if (l.optString("n").isBlank()) return false to "lanes[$i] 缺少 n（泳道名）"
            val cells = l.optJSONArray("cells")
                ?: return false to "lanes[$i] 缺少 cells"
            // 各泳道格数必须一致——不齐的话同一列表示的不是同一时间点
            if (size == null) size = cells.length()
            else if (cells.length() != size)
                return false to "lanes[$i] 有 ${cells.length()} 格，但第一条泳道有 $size 格——同一列表示的不是同一时间点"
            if (cells.length() > 6) return false to "每条泳道最多 6 格，实际 ${cells.length()} 格"
            for (c in 0 until cells.length()) {
                val cc = cells.optJSONObject(c) ?: continue
                val lv = cc.optString("lv", "todo")
                if (lv !in setOf("todo", "doing", "done", "block"))
                    return false to "lanes[$i].cells[$c].lv=\"$lv\" 只能是 todo/doing/done/block"
            }
        }
        return true to ""
    }

    private fun mindmap(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("root").isBlank())
            return false to "zx_mindmap 缺少 root（中心主题）"
        val arms = o.optJSONArray("arms")
            ?: return false to "zx_mindmap 缺少 arms（主枝）"
        if (arms.length() == 0) return false to "zx_mindmap 的 arms 为空"
        if (arms.length() > 6) return false to "主枝最多 6 条，实际 ${arms.length()} 条"
        for (i in 0 until arms.length()) {
            val a = arms.optJSONObject(i)
                ?: return false to "arms[$i] 不是对象，应为 {\"n\":名,\"kids\":[...]}"
            if (a.optString("n").isBlank()) return false to "arms[$i] 缺少 n（主枝名）"
            val kids = a.optJSONArray("kids")
            if (kids != null && kids.length() > 5)
                return false to "arms[$i] 的子节点最多 5 个，实际 ${kids.length()} 个"
        }
        return true to ""
    }

    private fun gallery(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_gallery 缺少 items"
        if (items.length() == 0) return false to "zx_gallery 的 items 为空"
        if (items.length() > 9) return false to "画廊最多 9 张，实际 ${items.length()} 张"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"k\":键,\"n\":说明,\"hex\":占位色}"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（回传键），点中了不知道是第几张"
            val hex = it.optString("hex")
            if (hex.isNotBlank() && !hex.matches(Regex("#[0-9A-Fa-f]{6}")))
                return false to "items[$i].hex=\"$hex\" 不是合法的 #RRGGBB"
        }
        return true to ""
    }

    private fun wizard(o: JSONObject): Pair<Boolean, String> {
        val steps = o.optJSONArray("steps")
            ?: return false to "zx_wizard 缺少 steps"
        if (steps.length() < 2) return false to "向导至少 2 步，实际 ${steps.length()} 步"
        if (steps.length() > 5) return false to "向导最多 5 步，实际 ${steps.length()} 步"
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i)
                ?: return false to "steps[$i] 不是对象，应为 {\"k\":键,\"q\":问题,\"options\":[...]}"
            if (s.optString("q").isBlank())
                return false to "steps[$i] 缺少 q（这一步问什么）——没有问题就没法收集"
            if (s.optString("k").isBlank())
                return false to "steps[$i] 缺少 k（回传键），最后组装 payload 时这一项会没名字"
            val opts = s.optJSONArray("options")
                ?: return false to "steps[$i] 缺少 options（选项）"
            if (opts.length() < 2) return false to "steps[$i].options 至少 2 项，实际 ${opts.length()} 项"
            if (opts.length() > 5) return false to "steps[$i].options 最多 5 项，实际 ${opts.length()} 项"
            for (k in 0 until opts.length()) {
                val op = opts.optJSONObject(k)
                val t = op?.optString("t") ?: opts.optString(k)
                if (t.isBlank()) return false to "steps[$i].options[$k] 既不是非空字符串，也不是含 t 的对象"
                if (op != null && op.optString("k").isBlank())
                    return false to "steps[$i].options[$k] 是对象但缺 k（回传键）"
            }
        }
        return true to ""
    }

    private fun spreadsheet(o: JSONObject): Pair<Boolean, String> {
        val cols = o.optJSONArray("cols")
            ?: return false to "zx_spreadsheet 缺少 cols（列头）"
        if (cols.length() < 2) return false to "网格至少 2 列，实际 ${cols.length()} 列"
        if (cols.length() > 5) return false to "网格最多 5 列，实际 ${cols.length()} 列"
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_spreadsheet 缺少 rows"
        if (rows.length() == 0) return false to "zx_spreadsheet 的 rows 为空"
        if (rows.length() > 7) return false to "网格最多 7 行，实际 ${rows.length()} 行"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"k\":行键,\"n\":显示名,\"v\":[...]}"
            if (r.optString("k").isBlank())
                return false to "rows[$i] 缺少 k（行键）——提交变更时要拿它组 \"行键.列名\""
            val v = r.optJSONArray("v")
                ?: return false to "rows[$i] 缺少 v（单元格值数组）"
            if (v.length() != cols.length())
                return false to "rows[$i].v 有 ${v.length()} 个值，但 cols 有 ${cols.length()} 列——单元格会错位"
        }
        return true to ""
    }

    private fun conversation(o: JSONObject): Pair<Boolean, String> {
        val turns = o.optJSONArray("turns")
            ?: return false to "zx_conversation 缺少 turns"
        if (turns.length() == 0) return false to "zx_conversation 的 turns 为空"
        if (turns.length() > 8) return false to "对话最多 8 轮，实际 ${turns.length()} 轮"
        for (i in 0 until turns.length()) {
            val t = turns.optJSONObject(i)
                ?: return false to "turns[$i] 不是对象，应为 {\"who\":\"me|them\",\"n\":名,\"t\":内容}"
            if (t.optString("t").isBlank()) return false to "turns[$i] 缺少 t（说话内容）"
            val who = t.optString("who", "them")
            if (who !in setOf("me", "them"))
                return false to "turns[$i].who=\"$who\" 只能是 me 或 them（决定气泡靠左还是靠右）"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_waterfall" -> """
            {"type":"zx_waterfall","steps":[
              {"n":"期初","v":1200},{"n":"新增","v":380},
              {"n":"流失","v":-150},{"n":"期末","v":1430}]}""".trimIndent()
        "zx_pyramid" -> """
            {"type":"zx_pyramid","levels":[
              {"n":"全部访问","v":10000},{"n":"注册","v":3200},
              {"n":"付费","v":480}]}""".trimIndent()
        "zx_bubble" -> """
            {"type":"zx_bubble","points":[
              {"x":12,"y":80,"r":40,"n":"A"},{"x":40,"y":35,"r":22,"n":"B"},
              {"x":70,"y":60,"r":30,"n":"C"}]}""".trimIndent()
        "zx_venn" -> """
            {"type":"zx_venn","groups":[
              {"n":"用过 A","v":820,"hex":"#5046E4"},
              {"n":"用过 B","v":640,"hex":"#3FBF7F"}]}""".trimIndent()
        "zx_candle" -> """
            {"type":"zx_candle","bars":[
              {"h":42,"l":38,"o":39,"c":41},{"h":44,"l":40,"o":41,"c":40}]}""".trimIndent()
        "zx_dag" -> """
            {"type":"zx_dag","nodes":["取数据","清洗","聚合","出图"],
             "edges":[{"f":0,"t":1},{"f":1,"t":2},{"f":2,"t":3}]}""".trimIndent()
        "zx_swimlane" -> """
            {"type":"zx_swimlane","lanes":[
              {"n":"前端","cells":[{"t":"搭骨架","lv":"done"},{"t":"接接口","lv":"doing"}]},
              {"n":"后端","cells":[{"t":"建表","lv":"done"},{"t":"写接口","lv":"block"}]}]}""".trimIndent()
        "zx_mindmap" -> """
            {"type":"zx_mindmap","root":"插件架构","arms":[
              {"n":"渲染层","kids":[{"n":"组件"}",{"n":"容器"}"]},
              {"n":"契约层","kids":[{"n":"扩展点"}"]}]}""".trimIndent()
        "zx_gallery" -> """
            {"type":"zx_gallery","items":[
              {"k":"s1","n":"首屏","hex":"#5046E4"},
              {"k":"s2","n":"对话","hex":"#3FBF7F"}]}""".trimIndent()
        "zx_wizard" -> """
            {"type":"zx_wizard","steps":[
              {"k":"os","q":"目标系统？","options":[{"k":"a","t":"Android 11+"}]},
              {"k":"arch","q":"架构？","options":[{"k":"mvvm","t":"MVVM"}]}]}""".trimIndent()
        "zx_spreadsheet" -> """
            {"type":"zx_spreadsheet","cols":["预算","实际"],
             "rows":[{"k":"dev","n":"研发","v":["12000","9800"]},
                     {"k":"ops","n":"运维","v":["4000","4300"]}]}""".trimIndent()
        "zx_conversation" -> """
            {"type":"zx_conversation","turns":[
              {"who":"them","n":"用户","t":"这个能离线用吗"}",
              {"who":"me","t":"可以，本地模型已编进 APK"}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_waterfall"   -> "steps[{n,v}]  2-8 步 · 必须有增有减，全正用 zx_chart"
        "zx_pyramid"     -> "levels[{n,v,d?}]  2-7 层 · 值必须递减"
        "zx_bubble"      -> "points[{x,y,r,n}]  2-14 个 · r>0 · x 必须有变化量"
        "zx_venn"        -> "groups[{n,v,hex?}]  2-3 组 · hex 为 #RRGGBB"
        "zx_candle"      -> "bars[{h,l,o,c}]  最多 24 · 实体须落在影线内"
        "zx_dag"         -> "nodes[] + edges[{f,t}]  2-10 节点 · 边不可自环"
        "zx_swimlane"    -> "lanes[{n,cells[{t,lv}]}]  2-5 道 · 各道格数须一致"
        "zx_mindmap"     -> "root + arms[{n,kids?}]  主枝 ≤6 · 子 ≤5 · 可折叠"
        "zx_gallery"     -> "items[{k,n?,hex?}]  最多 9 · 3 列固定"
        "zx_wizard"      -> "steps[{k,q,options[{k,t}]}]  2-5 步 · 每步 2-5 选项"
        "zx_spreadsheet" -> "cols[] + rows[{k,n,v[]}]  v 长度须等于列数 · 可改"
        "zx_conversation"-> "turns[{who(me|them),n?,t,d?}]  最多 8 轮"
        else -> "未注册组件：$component"
    }
}
