package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 第二十批组件（密度 / 分布 / 编码）的校验 + 示例 + 规格。
 *
 * 这批查的重点是**「形状前提」**。
 *
 * 前十九批大多查"会不会画不出来"（数值越界、数组长度不齐）。
 * 这批有一半是**画得出来、但形状是错的**：
 *
 *   · 等高线所有值相同 → 一条等值线都画不出，只剩空白
 *   · 流图所有点总量为 0 → 带宽全 0，只剩一条中线
 *   · 地平线图有负值 → 折叠逻辑失效，负段画不出来
 *   · 平行坐标只有 1 个轴 → 没有"平行"可言，退化成散点
 *   · 小提琴图所有值相同 → 宽度为 0，画不出轮廓
 *   · 字形网格只有 2 维 → 退化成线段，不是形状
 *   · 弧形图 f、t 相同 → 弧半径为 0，退化成一个点
 *   · 马赛克某行总和为 0 → 该行高度 0，后续行全被顶掉
 *   · 轨迹少于 3 点 → 只能画一条线，没有"轨迹"可言
 *   · 象形图 per 为 0 → 除零
 *
 * 这些都不崩、不报错——**只是画出一个看起来正常、
 * 实际是错的图形**。用户会基于错误形状做判断，
 * 这比画不出来危险得多，所以必须在校验阶段拦掉。
 */
object ZxDsl20 {

    val ALL = listOf(
        "zx_contour", "zx_stream", "zx_horizon", "zx_parallel",
        "zx_beeswarm", "zx_violin", "zx_glyph", "zx_arc",
        "zx_mosaic", "zx_trail", "zx_pictogram", "zx_dotstrip"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_contour"   -> contour(root)
        "zx_stream"    -> stream(root)
        "zx_horizon"   -> horizon(root)
        "zx_parallel"  -> parallel(root)
        "zx_beeswarm"  -> beeswarm(root)
        "zx_violin"    -> violin(root)
        "zx_glyph"     -> glyph(root)
        "zx_arc"       -> arc(root)
        "zx_mosaic"    -> mosaic(root)
        "zx_trail"     -> trail(root)
        "zx_pictogram" -> pictogram(root)
        "zx_dotstrip"  -> dotstrip(root)
        else -> false to "未注册组件：$component"
    }

    private fun contour(o: JSONObject): Pair<Boolean, String> {
        val grid = o.optJSONArray("grid")
            ?: return false to "zx_contour 缺少 grid"
        if (grid.length() < 2) return false to "等高线至少 2 行，实际 ${grid.length()} 行"
        if (grid.length() > 12) return false to "等高线最多 12 行，实际 ${grid.length()} 行"
        var cols = 0
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
        var count = 0
        for (r in 0 until grid.length()) {
            val arr = grid.optJSONArray(r)
                ?: return false to "grid[$r] 不是数组"
            if (arr.length() > 12) return false to "grid[$r] 有 ${arr.length()} 列，最多 12 列"
            cols = maxOf(cols, arr.length())
            for (c in 0 until arr.length()) {
                val v = arr.optDouble(c, Double.NaN)
                if (v.isNaN()) return false to "grid[$r][$c] 不是数值"
                mn = min(mn, v); mx = max(mx, v); count++
            }
        }
        if (count == 0) return false to "zx_contour 的 grid 是空的"
        // 所有值相同：一条等值线都画不出，只剩空白
        if (mx - mn < 1e-9)
            return false to "所有格点的值都是 $mn——画不出任何等值线，只剩空白"
        val levels = o.optJSONArray("levels")
        if (levels != null) {
            if (levels.length() > 7) return false to "等值线最多 7 条，实际 ${levels.length()} 条"
            for (i in 0 until levels.length()) {
                val lv = levels.optDouble(i, Double.NaN)
                if (lv.isNaN()) return false to "levels[$i] 不是数值"
                // 等值线在取值范围之外：画不出任何线段
                if (lv <= mn || lv >= mx)
                    return false to "levels[$i]=$lv 超出取值范围 [$mn, $mx]" +
                                   "——这条等值线画不出任何线段"
            }
        }
        return true to ""
    }

    private fun stream(o: JSONObject): Pair<Boolean, String> {
        val series = o.optJSONArray("series")
            ?: return false to "zx_stream 缺少 series"
        if (series.length() < 2) return false to "流图至少 2 条序列，实际 ${series.length()} 条"
        if (series.length() > 6) return false to "流图最多 6 条序列，实际 ${series.length()} 条"
        val n = series.length()
        var len = 0
        for (i in 0 until n) {
            val s = series.optJSONObject(i)
                ?: return false to "series[$i] 不是对象，应为 {\"n\":名,\"v\":[数值]}"
            if (s.optString("n").isBlank()) return false to "series[$i] 缺少 n"
            val arr = s.optJSONArray("v")
                ?: return false to "series[$i] 缺少 v"
            if (arr.length() > 24) return false to "每条序列最多 24 个点，series[$i] 有 ${arr.length()} 个"
            len = maxOf(len, arr.length())
            for (k in 0 until arr.length()) {
                val v = arr.optDouble(k, Double.NaN)
                if (v.isNaN()) return false to "series[$i].v[$k] 不是数值"
                if (v < 0) return false to "series[$i].v[$k]=$v 不能为负——流图无法表达负值"
            }
        }
        if (len < 2) return false to "每条序列至少 2 个点，实际 $len 个"
        // 所有点总量为 0：带宽全 0，只剩一条中线
        var allZero = true
        for (k in 0 until len) {
            var tot = 0.0
            for (i in 0 until n) tot += series.optJSONObject(i)?.optJSONArray("v")
                ?.optDouble(k, 0.0) ?: 0.0
            if (tot > 1e-9) { allZero = false; break }
        }
        if (allZero) return false to "每个点的总量都是 0——带宽全为 0，只剩一条中线"
        return true to ""
    }

    private fun horizon(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts")
            ?: return false to "zx_horizon 缺少 pts"
        if (pts.length() < 4) return false to "地平线图至少 4 个点，实际 ${pts.length()} 个"
        if (pts.length() > 120) return false to "地平线图最多 120 个点，实际 ${pts.length()} 个"
        var mx = 0.0
        for (k in 0 until pts.length()) {
            val v = pts.optDouble(k, Double.NaN)
            if (v.isNaN()) return false to "pts[$k] 不是数值"
            // 负值：折叠逻辑失效，负段画不出来
            if (v < 0) return false to "pts[$k]=$v 是负值——地平线图无法表达负值（折叠逻辑会失效）"
            mx = max(mx, v)
        }
        if (mx <= 0) return false to "所有点都是 0——折叠后没有任何高度"
        val bands = o.optInt("bands", 3)
        if (bands < 2 || bands > 5)
            return false to "zx_horizon 的 bands=$bands 超出 2-5"
        return true to ""
    }

    private fun parallel(o: JSONObject): Pair<Boolean, String> {
        val axes = o.optJSONArray("axes")
            ?: return false to "zx_parallel 缺少 axes"
        // 只有 1 个轴：没有"平行"可言
        if (axes.length() < 2)
            return false to "平行坐标至少 2 个轴，实际 ${axes.length()} 个——1 个就没有「平行」可言，退化成散点"
        if (axes.length() > 6) return false to "平行坐标最多 6 个轴，实际 ${axes.length()} 个"
        val na = axes.length()
        for (i in 0 until na) {
            val ax = axes.optJSONObject(i)
                ?: return false to "axes[$i] 不是对象，应为 {\"n\":名,\"min\":,\"max\":}"
            if (ax.optString("n").isBlank()) return false to "axes[$i] 缺少 n"
            if (ax.has("min") && ax.has("max") && ax.optDouble("max", 0.0) <= ax.optDouble("min", 0.0))
                return false to "axes[$i] 的 max 必须大于 min——否则所有点都叠在一处"
        }
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_parallel 缺少 rows"
        if (rows.length() == 0) return false to "zx_parallel 的 rows 为空"
        if (rows.length() > 14) return false to "平行坐标最多 14 条，实际 ${rows.length()} 条"
        for (r in 0 until rows.length()) {
            val arr = rows.optJSONArray(r)
                ?: return false to "rows[$r] 不是数组"
            // 长度与轴数不符：后面的维度会被截断，用户看到的线是断的
            if (arr.length() != na)
                return false to "rows[$r] 有 ${arr.length()} 个值，但 axes 有 $na 个——折线会断"
            for (i in 0 until na) {
                val v = arr.optDouble(i, Double.NaN)
                if (v.isNaN()) return false to "rows[$r][$i] 不是数值"
            }
        }
        return true to ""
    }

    private fun beeswarm(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_beeswarm 缺少 items"
        if (items.length() < 2) return false to "蜂群图至少 2 个个体，实际 ${items.length()} 个"
        if (items.length() > 60) return false to "蜂群图最多 60 个个体，实际 ${items.length()} 个"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":数值}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            if (!it.has("v")) return false to "items[$i] 缺少 v"
        }
        // 全同值：所有点挤在一处，看不出分布
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
        for (i in 0 until items.length()) {
            val v = items.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0
            mn = min(mn, v); mx = max(mx, v)
        }
        if (mx - mn < 1e-9)
            return false to "所有个体的 v 都是 $mn——所有点挤在一处，看不出分布"
        return true to ""
    }

    private fun violin(o: JSONObject): Pair<Boolean, String> {
        val groups = o.optJSONArray("groups")
            ?: return false to "zx_violin 缺少 groups"
        if (groups.length() == 0) return false to "zx_violin 的 groups 为空"
        if (groups.length() > 5) return false to "小提琴图最多 5 组，实际 ${groups.length()} 组"
        var allFlat = true
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i)
                ?: return false to "groups[$i] 不是对象，应为 {\"n\":名,\"pts\":[数值],\"min\":,\"max\":,\"med\":}"
            if (g.optString("n").isBlank()) return false to "groups[$i] 缺少 n"
            val pts = g.optJSONArray("pts")
                ?: return false to "groups[$i] 缺少 pts"
            if (pts.length() < 3)
                return false to "groups[$i] 至少需要 3 个点才能估计密度，实际 ${pts.length()} 个"
            if (pts.length() > 40) return false to "每组最多 40 个点，groups[$i] 有 ${pts.length()} 个"
            var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
            for (k in 0 until pts.length()) {
                val v = pts.optDouble(k, Double.NaN)
                if (v.isNaN()) return false to "groups[$i].pts[$k] 不是数值"
                mn = min(mn, v); mx = max(mx, v)
            }
            // 所有值相同：宽度为 0，画不出轮廓
            if (mx - mn > 1e-9) allFlat = false
            if (g.has("med")) {
                val med = g.optDouble("med", Double.NaN)
                if (med.isNaN()) return false to "groups[$i].med 不是数值"
                if (med < mn - 1e-9 || med > mx + 1e-9)
                    return false to "groups[$i].med=$med 超出数据范围 [$mn, $mx]——中位数线会画到轮廓外"
            }
        }
        if (allFlat) return false to "每组的 pts 值都各自相同——轮廓宽度为 0，画不出小提琴"
        return true to ""
    }

    private fun glyph(o: JSONObject): Pair<Boolean, String> {
        val dims = o.optJSONArray("dims")
            ?: return false to "zx_glyph 缺少 dims"
        // 少于 3 维：退化成线段，不是形状
        if (dims.length() < 3)
            return false to "字形网格至少 3 个维度，实际 ${dims.length()} 个——2 维退化成线段，看不出形状差异"
        if (dims.length() > 5) return false to "字形网格最多 5 个维度，实际 ${dims.length()} 个"
        for (i in 0 until dims.length())
            if (dims.optString(i).isBlank()) return false to "dims[$i] 是空字符串"
        val items = o.optJSONArray("items")
            ?: return false to "zx_glyph 缺少 items"
        if (items.length() == 0) return false to "zx_glyph 的 items 为空"
        if (items.length() > 24) return false to "字形网格最多 24 个，实际 ${items.length()} 个"
        val nd = dims.length()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":[0-1 数值]}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val arr = it.optJSONArray("v")
                ?: return false to "items[$i] 缺少 v"
            if (arr.length() != nd)
                return false to "items[$i].v 有 ${arr.length()} 个值，但 dims 有 $nd 个——形状会缺角"
            for (d in 0 until nd) {
                val v = arr.optDouble(d, Double.NaN)
                if (v.isNaN()) return false to "items[$i].v[$d] 不是数值"
                if (v < 0 || v > 1) return false to "items[$i].v[$d]=$v 超出 0-1"
            }
        }
        return true to ""
    }

    private fun arc(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_arc 缺少 nodes"
        if (nodes.length() < 2) return false to "弧形图至少 2 个节点，实际 ${nodes.length()} 个"
        if (nodes.length() > 24) return false to "弧形图最多 24 个节点，实际 ${nodes.length()} 个"
        val n = nodes.length()
        for (i in 0 until n)
            if (nodes.optString(i).isBlank()) return false to "nodes[$i] 是空字符串"
        val edges = o.optJSONArray("edges")
            ?: return false to "zx_arc 缺少 edges"
        if (edges.length() == 0) return false to "zx_arc 的 edges 为空"
        if (edges.length() > 30) return false to "连线最多 30 条，实际 ${edges.length()} 条"
        for (k in 0 until edges.length()) {
            val e = edges.optJSONObject(k)
                ?: return false to "edges[$k] 不是对象，应为 {\"f\":,\"t\":}"
            val f = e.optInt("f", -1); val t = e.optInt("t", -1)
            if (f < 0 || t < 0) return false to "edges[$k] 缺少 f 或 t"
            if (f >= n || t >= n) return false to "edges[$k] 的 f=$f t=$t 越界（共 $n 个节点）"
            // f、t 相同：弧半径为 0，退化成一个点
            if (f == t) return false to "edges[$k] 的 f=t=$f——弧半径为 0，退化成一个点"
        }
        return true to ""
    }

    private fun mosaic(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_mosaic 缺少 rows"
        if (rows.length() == 0) return false to "zx_mosaic 的 rows 为空"
        if (rows.length() > 5) return false to "马赛克图最多 5 行，实际 ${rows.length()} 行"
        val cols = o.optJSONArray("cols")
            ?: return false to "zx_mosaic 缺少 cols"
        if (cols.length() < 2) return false to "马赛克图至少 2 列，实际 ${cols.length()} 列"
        if (cols.length() > 5) return false to "马赛克图最多 5 列，实际 ${cols.length()} 列"
        val nc = cols.length()
        for (j in 0 until nc)
            if (cols.optString(j).isBlank()) return false to "cols[$j] 是空字符串"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"n\":名,\"v\":[数值]}"
            if (r.optString("n").isBlank()) return false to "rows[$i] 缺少 n"
            val arr = r.optJSONArray("v")
                ?: return false to "rows[$i] 缺少 v"
            if (arr.length() != nc)
                return false to "rows[$i].v 有 ${arr.length()} 个值，但 cols 有 $nc 个——格子会错位"
            var tot = 0.0
            for (c in 0 until nc) {
                val v = arr.optDouble(c, Double.NaN)
                if (v.isNaN()) return false to "rows[$i].v[$c] 不是数值"
                if (v < 0) return false to "rows[$i].v[$c]=$v 不能为负——负面积画不出来"
                tot += v
            }
            // 该行总和为 0：行高 0，后续行全被顶掉
            if (tot <= 1e-9)
                return false to "rows[$i] 的 v 总和为 0——这一行高度为 0，后面的行会被顶掉"
        }
        return true to ""
    }

    private fun trail(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts")
            ?: return false to "zx_trail 缺少 pts"
        // 少于 3 点：只能画一条线，没有"轨迹"可言
        if (pts.length() < 3)
            return false to "轨迹至少 3 个点，实际 ${pts.length()} 个——2 个点只能画一条线，没有「轨迹」可言"
        if (pts.length() > 60) return false to "轨迹最多 60 个点，实际 ${pts.length()} 个点"
        for (k in 0 until pts.length()) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"x\":0-1,\"y\":0-1,\"t\":}"
            val x = p.optDouble("x", Double.NaN)
            val y = p.optDouble("y", Double.NaN)
            if (x.isNaN()) return false to "pts[$k] 缺少 x（0-1）"
            if (y.isNaN()) return false to "pts[$k] 缺少 y（0-1）"
            if (x < 0 || x > 1) return false to "pts[$k].x=$x 超出 0-1"
            if (y < 0 || y > 1) return false to "pts[$k].y=$y 超出 0-1"
        }
        return true to ""
    }

    private fun pictogram(o: JSONObject): Pair<Boolean, String> {
        val groups = o.optJSONArray("groups")
            ?: return false to "zx_pictogram 缺少 groups"
        if (groups.length() == 0) return false to "zx_pictogram 的 groups 为空"
        if (groups.length() > 5) return false to "象形图最多 5 组，实际 ${groups.length()} 组"
        val per = o.optInt("per", 1)
        // per 为 0：除零
        if (per <= 0) return false to "zx_pictogram 的 per=$per 必须为正——每格代表的数量，为 0 会除零"
        var any = false
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i)
                ?: return false to "groups[$i] 不是对象，应为 {\"n\":名,\"n\":数量}"
            if (g.optString("n").isBlank()) return false to "groups[$i] 缺少 n"
            val c = g.optInt("cnt", -1)
            if (c < 0) return false to "groups[$i] 缺少 cnt（数量）"
            if (c < 0) return false to "groups[$i].cnt=$c 不能为负"
            if (c > 0) any = true
        }
        if (!any) return false to "所有组的 cnt 都是 0——一个格子都不会画"
        return true to ""
    }

    private fun dotstrip(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_dotstrip 缺少 items"
        if (items.length() == 0) return false to "zx_dotstrip 的 items 为空"
        if (items.length() > 8) return false to "单位点图最多 8 项，实际 ${items.length()} 项"
        var any = false
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"n\":数量}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val c = it.optInt("cnt", -1)
            if (c < 0) return false to "items[$i] 缺少 cnt（数量）"
            if (c < 0) return false to "items[$i].cnt=$c 不能为负"
            if (c > 100) return false to "items[$i].cnt=$c 超过 100——点阵会画 10 行以上，用 zx_bar 更合适"
            if (c > 0) any = true
        }
        if (!any) return false to "所有项的 cnt 都是 0——一个点都不会画"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_contour" -> """
            {"type":"zx_contour","n":"地形","grid":[
              [0,1,2,1],[1,3,5,3],[2,5,8,5],[1,3,5,3]],
             "levels":[2,4,6]}""".trimIndent()
        "zx_stream" -> """
            {"type":"zx_stream","n":"流量构成","series":[
              {"n":"Web","v":[30,28,25,20,18]},
              {"n":"App","v":[10,16,22,30,38]}]}""".trimIndent()
        "zx_horizon" -> """
            {"type":"zx_horizon","n":"QPS","bands":3,
             "pts":[12,18,40,95,60,30,22,15,44,110,70,35]}""".trimIndent()
        "zx_parallel" -> """
            {"type":"zx_parallel","axes":[
              {"n":"价格","min":0,"max":100},
              {"n":"性能","min":0,"max":100},
              {"n":"续航","min":0,"max":100}],
             "rows":[[20,90,40],[60,70,85],[95,50,60]]}""".trimIndent()
        "zx_beeswarm" -> """
            {"type":"zx_beeswarm","n":"响应耗时","items":[
              {"n":"a","v":120},{"n":"b","v":135},{"n":"c","v":280}]}""".trimIndent()
        "zx_violin" -> """
            {"type":"zx_violin","n":"分组分布","groups":[
              {"n":"A","pts":[10,12,11,40,42,38],"med":26},
              {"n":"B","pts":[20,22,21,19,20,23],"med":21}]}""".trimIndent()
        "zx_glyph" -> """
            {"type":"zx_glyph","n":"机型对比","dims":["性能","续航","拍照","屏幕"],
             "items":[{"n":"A","v":[.9,.6,.8,.7]},{"n":"B","v":[.5,.9,.6,.8]}]}""".trimIndent()
        "zx_arc" -> """
            {"type":"zx_arc","n":"跨文件引用",
             "nodes":["a.kt","b.kt","c.kt","d.kt","e.kt"],
             "edges":[{"f":0,"t":3},{"f":1,"t":4}]}""".trimIndent()
        "zx_mosaic" -> """
            {"type":"zx_mosaic","n":"渠道×转化","cols":["新客","老客"],
             "rows":[{"n":"A","v":[40,10]},{"n":"B","v":[15,55]}]}""".trimIndent()
        "zx_trail" -> """
            {"type":"zx_trail","n":"用户轨迹","pts":[
              {"x":.1,"y":.8,"t":0},{"x":.4,"y":.3,"t":1},
              {"x":.7,"y":.5,"t":2},{"x":.9,"y":.2,"t":3}]}""".trimIndent()
        "zx_pictogram" -> """
            {"type":"zx_pictogram","n":"用户构成","per":10,"unit":"人","total":1000,
             "groups":[{"n":"付费","cnt":240},{"n":"免费","cnt":760}]}""".trimIndent()
        "zx_dotstrip" -> """
            {"type":"zx_dotstrip","n":"缺陷数","items":[
              {"n":"v1.0","cnt":7},{"n":"v1.1","cnt":3},{"n":"v1.2","cnt":1}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_contour"   -> "grid 2-12×≤12 + levels? ≤7 · 值不可全同，levels 不可超范围"
        "zx_stream"    -> "series[{n,v[]}]  2-6 × ≤24 · 非负，总量不可全 0"
        "zx_horizon"   -> "pts 4-120 非负 + bands 2-5 · 折叠后装下 3-5 倍分辨率"
        "zx_parallel"  -> "axes 2-6 + rows[{...}] ≤14 · 每行长度必须等于轴数"
        "zx_beeswarm"  -> "items[{n,v}]  2-60 · 值不可全同"
        "zx_violin"    -> "groups[{n,pts≥3,med?}]  ≤5 · 每组值不可全同"
        "zx_glyph"     -> "dims 3-5 + items[{n,v[]}]  ≤24 · v 在 0-1"
        "zx_arc"       -> "nodes 2-24 + edges[{f,t}]  ≤30 · f≠t"
        "zx_mosaic"    -> "cols 2-5 + rows[{n,v[]}]  ≤5 · 每行总和必须 >0"
        "zx_trail"     -> "pts[{x,y,t?}]  3-60 · x/y 在 0-1"
        "zx_pictogram" -> "groups[{n,cnt}]  ≤5 + per>0 · 1 格 = per 个"
        "zx_dotstrip"  -> "items[{n,cnt}]  ≤8 · cnt ≤100，超了用 zx_bar"
        else -> "未注册组件：$component"
    }
}
