package com.zorv.plugin.zxwidget
import kotlin.math.abs

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 第十五批组件（关系 / 文本 / 小众图表）的校验 + 示例 + 规格。
 *
 * 这批查的是**替代方案是否真的成立**：
 *
 *   · 路径图少于 2 站，画不出"从哪到哪"
 *   · 批注的 s/e 越界或 s >= e，高亮区间为空
 *   · 子弹图 v 超出量程，条会被截断
 *   · 华夫饼各部分和为 0，格子全灰
 *   · 斜率图只有 1 项——那就是一根线，看不出对比
 *
 * 这批组件都是"替代某个已有方案"而存在的。
 * 如果替代的前提不成立（数据不足以支撑这种画法），
 * 用它反而比用柱状图更糟——
 * 所以这类前提必须在渲染前拦掉。
 */
object ZxDsl15 {

    val ALL = listOf(
        "zx_route", "zx_network", "zx_annotate", "zx_revision",
        "zx_bullet", "zx_icicle", "zx_cohort", "zx_slope",
        "zx_dumbbell", "zx_waffle", "zx_ridgeline", "zx_steparea"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_route"     -> route(root)
        "zx_network"   -> network(root)
        "zx_annotate"  -> annotate(root)
        "zx_revision"  -> revision(root)
        "zx_bullet"    -> bullet(root)
        "zx_icicle"    -> icicle(root)
        "zx_cohort"    -> cohort(root)
        "zx_slope"     -> slope(root)
        "zx_dumbbell"  -> dumbbell(root)
        "zx_waffle"    -> waffle(root)
        "zx_ridgeline" -> ridgeline(root)
        "zx_steparea"  -> steparea(root)
        else -> false to "未注册组件：$component"
    }

    private fun route(o: JSONObject): Pair<Boolean, String> {
        val stops = o.optJSONArray("stops")
            ?: return false to "zx_route 缺少 stops"
        if (stops.length() < 2)
            return false to "路径图至少 2 站，实际 ${stops.length()} 站——1 站画不出「从哪到哪」"
        if (stops.length() > 8) return false to "路径图最多 8 站，实际 ${stops.length()} 站"
        for (i in 0 until stops.length()) {
            val s = stops.optJSONObject(i)
                ?: return false to "stops[$i] 不是对象，应为 {\"n\":站名,\"k\":\"stop|change\",\"t\":,\"d\":}"
            if (s.optString("n").isBlank()) return false to "stops[$i] 缺少 n（站名）"
            val k = s.optString("k", "stop")
            if (k !in setOf("stop", "change"))
                return false to "stops[$i].k=\"$k\" 只能是 stop / change"
        }
        val changes = (0 until stops.length()).count {
            stops.optJSONObject(it)?.optString("k", "stop") == "change" }
        if (changes == stops.length())
            return false to "所有站都是换乘站——换乘标记就没有区分度了"
        return true to ""
    }

    private fun network(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_network 缺少 nodes"
        if (nodes.length() < 3) return false to "关系网络至少 3 个节点，实际 ${nodes.length()} 个"
        if (nodes.length() > 12) return false to "关系网络最多 12 个节点，实际 ${nodes.length()} 个"
        for (i in 0 until nodes.length()) {
            val nd = nodes.optJSONObject(i)
                ?: return false to "nodes[$i] 不是对象，应为 {\"k\":键,\"n\":名,\"v\":权重}"
            if (nd.optString("k").isBlank()) return false to "nodes[$i] 缺少 k"
            if (nd.optString("n").isBlank()) return false to "nodes[$i] 缺少 n"
            if (nd.has("v") && nd.optDouble("v") < 0)
                return false to "nodes[$i].v 为负，半径算不出来"
        }
        val links = o.optJSONArray("links")
        if (links == null || links.length() == 0)
            return false to "zx_network 缺少 links——没有边就只是一堆散点"
        if (links.length() > 24) return false to "关系边最多 24 条，实际 ${links.length()} 条"
        for (k in 0 until links.length()) {
            val l = links.optJSONObject(k)
                ?: return false to "links[$k] 不是对象，应为 {\"a\":起点,\"b\":终点,\"w\":权重}"
            val a = l.optInt("a", -1); val b = l.optInt("b", -1)
            if (a < 0 || b < 0) return false to "links[$k] 缺少 a 或 b"
            if (a >= nodes.length() || b >= nodes.length())
                return false to "links[$k] 的 a=$a b=$b 越界（共 ${nodes.length()} 个节点）"
            if (a == b) return false to "links[$k] 是自环（a=b=$a）"
            val w = l.optDouble("w", 1.0)
            if (w <= 0) return false to "links[$k].w=$w 必须为正，线宽算不出来"
        }
        // 孤立节点：力导向后它会飘到边上，看不出属于这张图
        val deg = IntArray(nodes.length())
        for (k in 0 until links.length()) {
            val l = links.optJSONObject(k) ?: continue
            deg[l.optInt("a")]++; deg[l.optInt("b")]++
        }
        val iso = deg.count { it == 0 }
        if (iso > 0) return false to "有 $iso 个孤立节点（没有任何边）——力导向后它会飘到边上，看不出属于这张图"
        return true to ""
    }

    private fun annotate(o: JSONObject): Pair<Boolean, String> {
        val text = o.optString("text")
        if (text.isBlank()) return false to "zx_annotate 缺少 text（被批注的原文）"
        if (text.length > 1200) return false to "zx_annotate 的 text 超过 1200 字，实际 ${text.length} 字"
        val marks = o.optJSONArray("marks")
            ?: return false to "zx_annotate 缺少 marks——没有批注就只是普通文本"
        if (marks.length() == 0) return false to "zx_annotate 的 marks 为空"
        if (marks.length() > 8) return false to "批注最多 8 条，实际 ${marks.length()} 条"
        var lastEnd = -1
        for (i in 0 until marks.length()) {
            val m = marks.optJSONObject(i)
                ?: return false to "marks[$i] 不是对象，应为 {\"s\":起,\"e\":止,\"c\":批注,\"by\":,\"q\":}"
            val s = m.optInt("s", -1); val e = m.optInt("e", -1)
            if (s < 0 || e < 0) return false to "marks[$i] 缺少 s 或 e"
            // 区间为空或越界：高亮画不出来
            if (s >= e)
                return false to "marks[$i] 的 s=$s 不小于 e=$e——高亮区间为空"
            if (e > text.length)
                return false to "marks[$i] 的 e=$e 超出原文长度 ${text.length}"
            if (m.optString("c").isBlank())
                return false to "marks[$i] 缺少 c（批注内容）——会渲染出一条空的批注"
            if (s < lastEnd)
                return false to "marks[$i] 的 s=$s 落在上一条批注内（上一条到 $lastEnd）——区间重叠，高亮会串"
            lastEnd = e
        }
        return true to ""
    }

    private fun revision(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_revision 缺少 items"
        if (items.length() == 0) return false to "zx_revision 的 items 为空"
        if (items.length() > 8) return false to "修订最多 8 条，实际 ${items.length()} 条"
        val kinds = setOf("add", "del", "mod")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"k\":\"add|del|mod\",\"old\":,\"new\":}"
            val k = it.optString("k", "mod")
            if (k !in kinds) return false to "items[$i].k=\"$k\" 只能是 ${kinds.joinToString("/")}"
            if (k == "add" && it.optString("new").isBlank())
                return false to "items[$i] 是 add 但 new 为空——没有内容可加"
            if (k == "del" && it.optString("old").isBlank())
                return false to "items[$i] 是 del 但 old 为空——没有内容可删"
            if (k == "mod" && (it.optString("old").isBlank() || it.optString("new").isBlank()))
                return false to "items[$i] 是 mod 但 old 或 new 为空——只显示一半"
            if (k == "mod" && it.optString("old") == it.optString("new"))
                return false to "items[$i] 是 mod 但 old 和 new 相同——那不是修订"
        }
        return true to ""
    }

    private fun bullet(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_bullet 缺少 items"
        if (items.length() == 0) return false to "zx_bullet 的 items 为空"
        if (items.length() > 6) return false to "子弹图最多 6 条，实际 ${items.length()} 条"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":,\"tgt\":,\"max\":,\"bad\":,\"ok\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val max = it.optDouble("max", 100.0)
            if (max <= 0) return false to "items[$i].max=$max 必须为正"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v"
            if (v < 0) return false to "items[$i].v=$v 为负"
            // 超出量程：条会被截断，用户看到的是错的
            if (v > max) return false to "items[$i].v=$v 超出量程 $max——条会被截断，用户看到的是错的"
            val tgt = it.optDouble("tgt", Double.NaN)
            if (!tgt.isNaN() && (tgt < 0 || tgt > max))
                return false to "items[$i].tgt=$tgt 超出 0-$max，目标刻线画到条外面"
            val bad = it.optDouble("bad", max * .5)
            val ok = it.optDouble("ok", max * .75)
            // bad >= ok 会让"差"区间比"良"区间还宽，语义是反的
            if (bad >= ok)
                return false to "items[$i].bad=$bad 不小于 ok=$ok——「差」区间比「良」区间还宽，语义是反的"
            if (ok > max) return false to "items[$i].ok=$ok 超出量程 $max"
        }
        return true to ""
    }

    private fun icicle(root: JSONObject): Pair<Boolean, String> {
        val r = root.optJSONObject("root")
            ?: return false to "zx_icicle 缺少 root"
        if (r.optString("n").isBlank()) return false to "root 缺少 n"
        var count = 0
        fun walk(n: JSONObject, depth: Int): String? {
            count++
            if (count > 40) return "层级节点超过 40 个，冰柱图会太长"
            if (depth > 3) return "层级超过 4 层（0-3），再深缩进就没意义了"
            val kids = n.optJSONArray("c") ?: return null
            var kv = 0.0
            for (i in 0 until kids.length()) {
                val k = kids.optJSONObject(i) ?: return "c[$i] 不是对象"
                if (k.optString("n").isBlank()) return "c[$i] 缺少 n"
                val v = k.optDouble("v", Double.NaN)
                if (v.isNaN()) return "c[$i] 缺少 v"
                if (v < 0) return "c[$i].v 为负"
                kv += v
                walk(k, depth + 1)?.let { return it }
            }
            val pv = n.optDouble("v", Double.NaN)
            // 子节点之和超过父节点：宽度加起来超过 100%
            if (!pv.isNaN() && pv > 0 && kv > pv + 1e-6)
                return "「${n.optString("n")}」子节点合计 $kv 大于自身 $pv——宽度加起来会超过 100%"
            return null
        }
        walk(r, 0)?.let { return false to it }
        return true to ""
    }

    private fun cohort(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_cohort 缺少 rows"
        if (rows.length() < 2) return false to "队列分析至少 2 行，实际 ${rows.length()} 行"
        if (rows.length() > 8) return false to "队列分析最多 8 行，实际 ${rows.length()} 行"
        val periods = o.optInt("periods", 5)
        if (periods !in 2..12) return false to "zx_cohort 的 periods=$periods 超出 2-12"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"n\":队列名,\"size\":样本量,\"v\":[留存%]}"
            if (r.optString("n").isBlank()) return false to "rows[$i] 缺少 n"
            if (r.optInt("size", 0) <= 0) return false to "rows[$i] 的 size 必须为正"
            val v = r.optJSONArray("v")
                ?: return false to "rows[$i] 缺少 v（各期留存率）"
            if (v.length() > periods)
                return false to "rows[$i] 的 v 有 ${v.length()} 个，但 periods=$periods——多出的列画不出来"
            for (k in 0 until v.length()) {
                val p = v.optDouble(k, Double.NaN)
                if (p.isNaN()) continue     // 空缺是允许的（该期还没到）
                if (p < 0 || p > 100)
                    return false to "rows[$i].v[$k]=$p 超出 0-100（留存率是百分数）"
            }
        }
        return true to ""
    }

    private fun slope(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_slope 缺少 items"
        if (items.length() < 2)
            return false to "斜率图至少 2 项，实际 ${items.length()} 项——1 项就是一根线，看不出对比"
        if (items.length() > 8) return false to "斜率图最多 8 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"a\":起点值,\"b\":终点值}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val a = it.optDouble("a", Double.NaN)
            val b = it.optDouble("b", Double.NaN)
            if (a.isNaN()) return false to "items[$i] 缺少 a（起点值）"
            if (b.isNaN()) return false to "items[$i] 缺少 b（终点值）"
            if (a == b) return false to "items[$i] 的 a 和 b 相同——斜率为 0，用不上斜率图"
        }
        // 全部相同斜率 = 全部平行，看不出差异
        val first = items.optJSONObject(0)!!
        val d0 = first.optDouble("b") - first.optDouble("a")
        if ((1 until items.length()).all {
                val it = items.optJSONObject(it)
                abs((it!!.optDouble("b") - it.optDouble("a")) - d0) < 1e-9 })
            return false to "所有项的变化量都相同——画出来是一组平行线，看不出差异"
        return true to ""
    }

    private fun dumbbell(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_dumbbell 缺少 items"
        if (items.length() == 0) return false to "zx_dumbbell 的 items 为空"
        if (items.length() > 8) return false to "哑铃图最多 8 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"a\":前,\"b\":后}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val a = it.optDouble("a", Double.NaN)
            val b = it.optDouble("b", Double.NaN)
            if (a.isNaN()) return false to "items[$i] 缺少 a（前）"
            if (b.isNaN()) return false to "items[$i] 缺少 b（后）"
        }
        return true to ""
    }

    private fun waffle(o: JSONObject): Pair<Boolean, String> {
        val parts = o.optJSONArray("parts")
            ?: return false to "zx_waffle 缺少 parts"
        if (parts.length() < 2)
            return false to "华夫饼至少 2 部分，实际 ${parts.length()} 部分——1 部分就是一整块"
        if (parts.length() > 5) return false to "华夫饼最多 5 部分，实际 ${parts.length()} 部分"
        var sum = 0
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i)
                ?: return false to "parts[$i] 不是对象，应为 {\"n\":名,\"v\":数量}"
            if (p.optString("n").isBlank()) return false to "parts[$i] 缺少 n"
            val v = p.optInt("v", 0)
            if (v <= 0) return false to "parts[$i].v=$v 必须为正，否则一格都分不到"
            sum += v
        }
        // 全 0 会让格子全灰
        if (sum == 0) return false to "所有 part 的 v 都是 0——格子全灰"
        val cols = o.optInt("cols", 10)
        if (cols !in 2..12) return false to "zx_waffle 的 cols=$cols 超出 2-12"
        val per = o.optDouble("per", Double.NaN)
        if (!per.isNaN() && per <= 0)
            return false to "zx_waffle 的 per=$per 必须为正（每格代表的数量）"
        return true to ""
    }

    private fun ridgeline(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_ridgeline 缺少 rows"
        if (rows.length() < 2)
            return false to "山脊线至少 2 行，实际 ${rows.length()} 行——1 行就是普通面积图"
        if (rows.length() > 6) return false to "山脊线最多 6 行，实际 ${rows.length()} 行"
        var len = -1
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"n\":名,\"v\":[数值]}"
            if (r.optString("n").isBlank()) return false to "rows[$i] 缺少 n"
            val v = r.optJSONArray("v")
                ?: return false to "rows[$i] 缺少 v（分布序列）"
            if (v.length() < 3) return false to "rows[$i] 的 v 至少 3 个点，实际 ${v.length()} 个"
            if (v.length() > 40) return false to "rows[$i] 的 v 最多 40 个点，实际 ${v.length()} 个"
            // 各行点数必须一致，否则 x 轴对齐不上
            if (len < 0) len = v.length()
            else if (v.length() != len)
                return false to "rows[$i] 的 v 有 ${v.length()} 个点，第一行有 $len 个——x 轴对齐不上，对比就错了"
            for (k in 0 until v.length())
                if (v.optDouble(k, Double.NaN).isNaN())
                    return false to "rows[$i].v[$k] 不是数值"
        }
        return true to ""
    }

    private fun steparea(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts")
            ?: return false to "zx_steparea 缺少 pts"
        if (pts.length() < 3)
            return false to "阶梯面积至少 3 个点，实际 ${pts.length()} 个——少于 3 个画不出阶梯"
        if (pts.length() > 24) return false to "阶梯面积最多 24 个点，实际 ${pts.length()} 个"
        for (i in 0 until pts.length()) {
            val v = pts.optDouble(i, Double.NaN)
            if (v.isNaN()) return false to "pts[$i] 不是数值"
            if (v < 0) return false to "pts[$i]=$v 为负，面积画到基线下面"
        }
        if ((0 until pts.length()).all { pts.optDouble(it, 0.0) == 0.0 })
            return false to "所有点都是 0——面积为零"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_route" -> """
            {"type":"zx_route","stops":[
              {"n":"起点","k":"stop","t":"08:00","d":"步行 200m"},
              {"n":"换乘站","k":"change","t":"08:12","d":"换 3 号线"}",
              {"n":"终点","k":"stop","t":"08:40","d":"出 B 口"}"]}""".trimIndent()
        "zx_network" -> """
            {"type":"zx_network","nodes":[
              {"k":"a","n":"渲染","v":3},{"k":"b","n":"校验","v":2},
              {"k":"c","n":"缓存","v":1},{"k":"d","n":"事务","v":2}],
             "links":[{"a":0,"b":1,"w":2},{"a":1,"b":2,"w":1},
                      {"a":1,"b":3,"w":1.5},{"a":2,"b":3,"w":1}]}""".trimIndent()
        "zx_annotate" -> """
            {"type":"zx_annotate",
             "text":"这一版把重试次数从 3 次改成 5 次，超时也放宽到 30 秒。",
             "marks":[{"s":9,"e":16,"c":"5 次是不是太多了？","by":"同事","q":"重试次数从 3 次改成 5 次"}"]}""".trimIndent()
        "zx_revision" -> """
            {"type":"zx_revision","path":"Config.kt","items":[
              {"k":"mod","old":"timeout = 10","new":"timeout = 30"},
              {"k":"add","old":"","new":"retry = 5"}]}""".trimIndent()
        "zx_bullet" -> """
            {"type":"zx_bullet","items":[
              {"n":"收入","v":72,"tgt":80,"max":100,"bad":50,"ok":75},
              {"n":"毛利","v":38,"tgt":35,"max":100,"bad":50,"ok":75}]}""".trimIndent()
        "zx_icicle" -> """
            {"type":"zx_icicle","root":{"n":"总计","v":100,"c":[
              {"n":"渲染","v":60,"c":[{"n":"Compose","v":35},{"n":"Canvas","v":25}]},
              {"n":"校验","v":40}]}}""".trimIndent()
        "zx_cohort" -> """
            {"type":"zx_cohort","unit":"周","periods":4,"rows":[
              {"n":"第 1 周","size":1200,"v":[100,62,45,33]},
              {"n":"第 2 周","size":1450,"v":[100,58,41]}]}""".trimIndent()
        "zx_slope" -> """
            {"type":"zx_slope","a":"Q1","b":"Q2","items":[
              {"n":"A 方案","a":32,"b":48},{"n":"B 方案","a":55,"b":41}]}""".trimIndent()
        "zx_dumbbell" -> """
            {"type":"zx_dumbbell","a":"改前","b":"改后","items":[
              {"n":"启动","a":820,"b":640},{"n":"内存","a":310,"b":290}]}""".trimIndent()
        "zx_waffle" -> """
            {"type":"zx_waffle","cols":10,"per":10,"parts":[
              {"n":"已覆盖","v":72},{"n":"未覆盖","v":28}]}""".trimIndent()
        "zx_ridgeline" -> """
            {"type":"zx_ridgeline","rows":[
              {"n":"1 月","v":[3,5,9,6,2,1]},
              {"n":"2 月","v":[2,4,8,9,5,2]}]}""".trimIndent()
        "zx_steparea" -> """
            {"type":"zx_steparea",
             "pts":[10,10,25,25,18,18,30,30,22]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_route"     -> "stops[{n,k,t?,d?}]  2-8 · k ∈ stop/change · 不可全是 change"
        "zx_network"   -> "nodes[{k,n,v?}] 3-12 + links[{a,b,w?}]  ≤24 · 无孤立节点"
        "zx_annotate"  -> "text + marks[{s,e,c,by?,q?}]  ≤8 · s<e 且不可重叠"
        "zx_revision"  -> "items[{k,old,new}]  ≤8 · mod 的 old/new 不可相同"
        "zx_bullet"    -> "items[{n,v,tgt,max,bad,ok}]  ≤6 · bad<ok≤max"
        "zx_icicle"    -> "root{n,v?,c?[]}  ≤4 层 · 子节点和不可超父"
        "zx_cohort"    -> "rows[{n,size,v[0-100]}]  2-8 · periods 2-12"
        "zx_slope"     -> "items[{n,a,b}]  2-8 · a≠b 且变化量不可全同"
        "zx_dumbbell"  -> "items[{n,a,b}]  ≤8"
        "zx_waffle"    -> "parts[{n,v>0}]  2-5 · cols 2-12 · per?"
        "zx_ridgeline" -> "rows[{n,v[]}]  2-6 · 各行点数必须一致"
        "zx_steparea"  -> "pts[]  3-24 · 值非负"
        else -> "未注册组件：$component"
    }
}
