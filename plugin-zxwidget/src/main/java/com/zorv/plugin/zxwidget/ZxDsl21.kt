package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第二十一批组件（对齐 / 差异 / 编排）的校验 + 示例 + 规格。
 *
 * 这批查的重点是**「比得对不对」**。
 *
 * 前二十批大多查"画不出来"或"画出来但形状是错的"。
 * 这批要拦的是**"画得出来、形状也对、但结论是错的"**：
 *
 *   · 序列对齐只有 1 条序列 → 没有"对齐"的对象
 *   · 结构化差异 rows 全 same → 那不叫差异
 *   · 三方比对缺 base → 没有基准就不是三方比对，
 *                      退化成两方，判不出"谁改了"
 *   · 三方比对三列全同 → 那一行没有任何信息
 *   · 能量桑基 flows 指向跨层 → 能量会"倒流"
 *   · 分割面板 min ≥ max → 拖柄可拖区间为负
 *   · 条件容器没有 default → 条件都不满足时显示空白
 *   · 泳道格子状态值越界 → 静默当空闲，冲突格看不出来
 *   · 子弹图 max 小于 act → 条会超出量程被截断
 *   · 同心环某环 segs 总和为 0 → 该环整圈消失
 *   · 分步流程没有进行中或失败步 → 看不出卡在哪
 *   · 标注坐标越界 → 画到框外，用户找不到
 *   · 多表盘 max 为 0 → 除零，指针角度算不出来
 *
 * 共同点：这些都是**不会崩**的。
 * 校验要拦的不是错误，是**误导**——
 * 用户会基于这些图做判断，错的图比没有图更糟。
 */
object ZxDsl21 {

    val ALL = listOf(
        "zx_align", "zx_jsondiff", "zx_threeway", "zx_sankey2",
        "zx_split", "zx_switch", "zx_lane", "zx_bullet2",
        "zx_ring2", "zx_stepflow", "zx_annotate", "zx_dial3"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_align"     -> align(root)
        "zx_jsondiff"  -> jsondiff(root)
        "zx_threeway"  -> threeway(root)
        "zx_sankey2"   -> sankey2(root)
        "zx_split"     -> split(root)
        "zx_switch"    -> switch(root)
        "zx_lane"      -> lane(root)
        "zx_bullet2"   -> bullet2(root)
        "zx_ring2"     -> ring2(root)
        "zx_stepflow"  -> stepflow(root)
        "zx_annotate"  -> annotate(root)
        "zx_dial3"     -> dial3(root)
        else -> false to "未注册组件：$component"
    }

    private fun align(o: JSONObject): Pair<Boolean, String> {
        val series = o.optJSONArray("series")
            ?: return false to "zx_align 缺少 series"
        // 只有 1 条：没有"对齐"的对象
        if (series.length() < 2)
            return false to "序列对齐至少 2 条，实际 ${series.length()} 条——1 条没有对齐的对象"
        if (series.length() > 4) return false to "序列对齐最多 4 条，实际 ${series.length()} 条"
        var any = false
        for (i in 0 until series.length()) {
            val s = series.optJSONObject(i)
                ?: return false to "series[$i] 不是对象，应为 {\"n\":名,\"v\":[数值]}"
            if (s.optString("n").isBlank()) return false to "series[$i] 缺少 n"
            val arr = s.optJSONArray("v")
                ?: return false to "series[$i] 缺少 v"
            if (arr.length() < 2)
                return false to "series[$i] 至少 2 个点，实际 ${arr.length()} 个——1 个点画不出线"
            if (arr.length() > 60) return false to "每条序列最多 60 个点，series[$i] 有 ${arr.length()} 个"
            for (k in 0 until arr.length())
                if (arr.optDouble(k, Double.NaN).isNaN())
                    return false to "series[$i].v[$k] 不是数值"
            any = true
        }
        if (!any) return false to "zx_align 的 series 是空的"
        val mode = o.optString("mode", "stretch")
        if (mode !in setOf("stretch", "raw"))
            return false to "zx_align 的 mode=\"$mode\" 只能是 stretch / raw"
        return true to ""
    }

    private fun jsondiff(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_jsondiff 缺少 rows"
        if (rows.length() == 0) return false to "zx_jsondiff 的 rows 为空"
        if (rows.length() > 14) return false to "差异最多 14 行，实际 ${rows.length()} 行"
        val kinds = setOf("add", "del", "mod", "same")
        var hasChange = false
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"p\":路径,\"k\":类型,\"a\":原值,\"b\":新值}"
            if (r.optString("p").isBlank()) return false to "rows[$i] 缺少 p（字段路径）"
            val k = r.optString("k", "same")
            if (k !in kinds)
                return false to "rows[$i].k=\"$k\" 不支持（可选：${kinds.joinToString("/")}）"
            if (k != "same") hasChange = true
            // mod 必须给前后两个值，否则"改成了什么"说不清
            if (k == "mod" && r.optString("a").isBlank() && r.optString("b").isBlank())
                return false to "rows[$i] 是 mod 但 a 和 b 都为空——说不清从什么改到什么"
        }
        // 全 same：那不叫差异
        if (!hasChange)
            return false to "所有行都是 same——那不叫差异，不需要对比"
        val only = o.optString("only", "all")
        if (only !in setOf("all", "add", "del", "mod"))
            return false to "zx_jsondiff 的 only=\"$only\" 只能是 all / add / del / mod"
        return true to ""
    }

    private fun threeway(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_threeway 缺少 rows"
        if (rows.length() == 0) return false to "zx_threeway 的 rows 为空"
        if (rows.length() > 12) return false to "三方比对最多 12 行，实际 ${rows.length()} 行"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"f\":字段名,\"base\":,\"a\":,\"b\":}"
            if (r.optString("f").isBlank()) return false to "rows[$i] 缺少 f（字段名）"
            // 缺 base：退化成两方比对，判不出"谁改了"
            if (!r.has("base"))
                return false to "rows[$i] 缺少 base——没有基准就不是三方比对，判不出谁改了"
            if (!r.has("a") || !r.has("b"))
                return false to "rows[$i] 缺少 a 或 b（两边的值）"
            val base = r.optString("base"); val a = r.optString("a"); val b = r.optString("b")
            // 三列全同：这一行没有任何信息
            if (base == a && a == b)
                return false to "rows[$i] 的 base / a / b 完全相同——这一行没有任何信息"
        }
        return true to ""
    }

    private fun sankey2(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_sankey2 缺少 nodes"
        if (nodes.length() < 2) return false to "能量桑基至少 2 个节点，实际 ${nodes.length()} 个"
        if (nodes.length() > 6) return false to "能量桑基最多 6 个节点，实际 ${nodes.length()} 个"
        val n = nodes.length()
        val layers = (0 until n).map { nodes.optJSONObject(it)?.optInt("l", 0) ?: 0 }
        for (i in 0 until n) {
            if (nodes.optJSONObject(i)?.optString("n").isNullOrBlank())
                return false to "nodes[$i] 缺少 n"
            if (nodes.optJSONObject(i)?.optInt("l", 0) ?: 0 < 0)
                return false to "nodes[$i].l 不能为负"
        }
        val flows = o.optJSONArray("flows")
            ?: return false to "zx_sankey2 缺少 flows"
        if (flows.length() == 0) return false to "zx_sankey2 的 flows 为空"
        if (flows.length() > 8) return false to "流量最多 8 条，实际 ${flows.length()} 条"
        var anyPos = false
        for (k in 0 until flows.length()) {
            val f = flows.optJSONObject(k)
                ?: return false to "flows[$k] 不是对象，应为 {\"f\":,\"t\":,\"v\":,\"loss\":}"
            val s = f.optInt("f", -1); val t = f.optInt("t", -1)
            if (s < 0 || t < 0) return false to "flows[$k] 缺少 f 或 t"
            if (s >= n || t >= n) return false to "flows[$k] 的 f=$s t=$t 越界（共 $n 个节点）"
            // 跨层：能量会"倒流"，图看着像能量凭空增加
            if (layers[t] <= layers[s])
                return false to "flows[$k] 从层 ${layers[s]} 流向层 ${layers[t]}" +
                               "——不能往回或同层流，能量会看起来「倒流」"
            val v = f.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "flows[$k] 缺少 v"
            if (v <= 0) return false to "flows[$k].v=$v 必须为正——带宽为 0 看不见，为负画不出"
            anyPos = true
        }
        if (!anyPos) return false to "所有流量都是 0——什么也画不出来"
        if (o.optDouble("loss", 0.0) < 0)
            return false to "zx_sankey2 的 loss 不能为负"
        return true to ""
    }

    private fun split(o: JSONObject): Pair<Boolean, String> {
        if (!o.has("a") || !o.has("b"))
            return false to "zx_split 缺少 a 或 b（两半的内容）"
        val dir = o.optString("dir", "v")
        if (dir !in setOf("v", "h"))
            return false to "zx_split 的 dir=\"$dir\" 只能是 v（上下）/ h（左右）"
        val min = o.optDouble("min", .2)
        val max = o.optDouble("max", .8)
        // min ≥ max：可拖区间为负，拖柄会跳
        if (min >= max) return false to "zx_split 的 min=$min 必须小于 max=$max——可拖区间为负"
        if (min < 0 || max > 1) return false to "zx_split 的 min/max 必须在 0-1 内"
        val ratio = o.optDouble("ratio", .5)
        if (ratio < min - 1e-9 || ratio > max + 1e-9)
            return false to "zx_split 的 ratio=$ratio 超出 [$min, $max]——初始位置会立刻被夹回去"
        return true to ""
    }

    private fun switch(o: JSONObject): Pair<Boolean, String> {
        val cases = o.optJSONArray("cases")
            ?: return false to "zx_switch 缺少 cases"
        if (cases.length() == 0) return false to "zx_switch 的 cases 为空"
        if (cases.length() > 5) return false to "条件容器最多 5 个分支，实际 ${cases.length()} 个"
        var hasDefault = false
        for (i in 0 until cases.length()) {
            val c = cases.optJSONObject(i)
                ?: return false to "cases[$i] 不是对象，应为 {\"when\":条件,\"label\":,\"body\":{...}}"
            val w = c.optString("when", "")
            if (w.isBlank() || w == "default") hasDefault = true
            if (!c.has("body"))
                return false to "cases[$i] 缺少 body——该分支没有内容可显示"
        }
        // 没有 default：条件都不满足时显示空白
        if (!hasDefault)
            return false to "没有一个 default 分支（when 为空或 \"default\"）" +
                           "——条件都不满足时会显示空白"
        val cur = o.optInt("cur", 0)
        if (cur < 0 || cur >= cases.length())
            return false to "zx_switch 的 cur=$cur 越界（共 ${cases.length()} 个分支）"
        return true to ""
    }

    private fun lane(o: JSONObject): Pair<Boolean, String> {
        val lanes = o.optJSONArray("lanes")
            ?: return false to "zx_lane 缺少 lanes"
        if (lanes.length() == 0) return false to "zx_lane 的 lanes 为空"
        if (lanes.length() > 5) return false to "泳道最多 5 条，实际 ${lanes.length()} 条"
        val slots = o.optInt("slots", 12)
        if (slots < 2 || slots > 24) return false to "zx_lane 的 slots=$slots 超出 2-24"
        for (i in 0 until lanes.length()) {
            val l = lanes.optJSONObject(i)
                ?: return false to "lanes[$i] 不是对象，应为 {\"n\":名,\"v\":[状态数组]}"
            if (l.optString("n").isBlank()) return false to "lanes[$i] 缺少 n"
            val arr = l.optJSONArray("v")
                ?: return false to "lanes[$i] 缺少 v"
            if (arr.length() != slots)
                return false to "lanes[$i].v 有 ${arr.length()} 格，但 slots=$slots——各道格数不齐就不是同一个时间轴"
            for (k in 0 until arr.length()) {
                val st = arr.optInt(k, -1)
                // 越界会静默当空闲，冲突格就看不出来了
                if (st < 0 || st > 3)
                    return false to "lanes[$i].v[$k]=$st 超出 0-3（0空闲/1占用/2冲突/3不可用）" +
                                   "——越界会被当成空闲，冲突格就看不出来了"
            }
        }
        return true to ""
    }

    private fun bullet2(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_bullet2 缺少 items"
        if (items.length() == 0) return false to "zx_bullet2 的 items 为空"
        if (items.length() > 8) return false to "子弹图组最多 8 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"act\":实际,\"tgt\":目标,\"max\":,\"bands\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val act = it.optDouble("act", Double.NaN)
            val tgt = it.optDouble("tgt", Double.NaN)
            if (act.isNaN()) return false to "items[$i] 缺少 act（实际值）"
            if (tgt.isNaN()) return false to "items[$i] 缺少 tgt（目标值）"
            val max = it.optDouble("max", maxOf(act, tgt, 1.0))
            // max 小于实际值：条会超出量程被截断，用户看到的达成度是假的
            if (max < act - 1e-9)
                return false to "items[$i].max=$max 小于 act=$act——条会超出量程被截断，达成度看着是假的"
            if (max < tgt - 1e-9)
                return false to "items[$i].max=$max 小于 tgt=$tgt——目标线会画到条外"
            if (max <= 0) return false to "items[$i].max=$max 必须为正"
            val bands = it.optJSONArray("bands")
            if (bands != null) {
                var prev = -1.0
                for (b in 0 until bands.length()) {
                    val v = bands.optDouble(b, Double.NaN)
                    if (v.isNaN()) return false to "items[$i].bands[$b] 不是数值"
                    if (v <= prev) return false to "items[$i].bands 必须递增，bands[$b]=$v 不大于前一个 $prev"
                    prev = v
                }
            }
        }
        return true to ""
    }

    private fun ring2(o: JSONObject): Pair<Boolean, String> {
        val rings = o.optJSONArray("rings")
            ?: return false to "zx_ring2 缺少 rings"
        if (rings.length() == 0) return false to "zx_ring2 的 rings 为空"
        if (rings.length() > 4) return false to "同心环最多 4 环，实际 ${rings.length()} 环"
        for (i in 0 until rings.length()) {
            val r = rings.optJSONObject(i)
                ?: return false to "rings[$i] 不是对象，应为 {\"n\":名,\"segs\":[{\"n\":,\"v\":}]}"
            if (r.optString("n").isBlank()) return false to "rings[$i] 缺少 n"
            val segs = r.optJSONArray("segs")
                ?: return false to "rings[$i] 缺少 segs"
            if (segs.length() < 2)
                return false to "rings[$i] 至少 2 段，实际 ${segs.length()} 段——1 段就是整圈，看不出构成"
            if (segs.length() > 8) return false to "每环最多 8 段，rings[$i] 有 ${segs.length()} 段"
            var tot = 0.0
            for (k in 0 until segs.length()) {
                val s = segs.optJSONObject(k)
                    ?: return false to "rings[$i].segs[$k] 不是对象"
                if (s.optString("n").isBlank()) return false to "rings[$i].segs[$k] 缺少 n"
                val v = s.optDouble("v", Double.NaN)
                if (v.isNaN()) return false to "rings[$i].segs[$k] 缺少 v"
                if (v < 0) return false to "rings[$i].segs[$k].v=$v 不能为负"
                tot += v
            }
            // 总和为 0：该环整圈消失
            if (tot <= 1e-9)
                return false to "rings[$i] 的各段总和为 0——这一环会整圈消失"
        }
        return true to ""
    }

    private fun stepflow(o: JSONObject): Pair<Boolean, String> {
        val steps = o.optJSONArray("steps")
            ?: return false to "zx_stepflow 缺少 steps"
        if (steps.length() < 2)
            return false to "分步流程至少 2 步，实际 ${steps.length()} 步——1 步看不出流程"
        if (steps.length() > 8) return false to "分步流程最多 8 步，实际 ${steps.length()} 步"
        var running = 0
        var failed = 0
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i)
                ?: return false to "steps[$i] 不是对象，应为 {\"n\":名,\"s\":状态,\"d\":说明}"
            if (s.optString("n").isBlank()) return false to "steps[$i] 缺少 n"
            val st = s.optInt("s", 0)
            if (st < 0 || st > 4)
                return false to "steps[$i].s=$st 超出 0-4（0待办/1进行中/2完成/3失败/4跳过）"
            if (st == 1) running++
            if (st == 3) failed++
        }
        // 没有进行中也没有失败：看不出卡在哪
        if (running == 0 && failed == 0)
            return false to "没有「进行中」也没有「失败」的步骤——看不出当前卡在哪一步"
        if (running > 1)
            return false to "有 $running 个「进行中」的步骤——同时只能有一个"
        return true to ""
    }

    private fun annotate(o: JSONObject): Pair<Boolean, String> {
        val marks = o.optJSONArray("marks")
            ?: return false to "zx_annotate 缺少 marks"
        if (marks.length() == 0) return false to "zx_annotate 的 marks 为空"
        if (marks.length() > 10) return false to "标注最多 10 处，实际 ${marks.length()} 处"
        val types = setOf("dot", "box", "arrow", "line")
        for (i in 0 until marks.length()) {
            val m = marks.optJSONObject(i)
                ?: return false to "marks[$i] 不是对象，应为 {\"t\":类型,\"x\":,\"y\":,\"n\":说明}"
            val t = m.optString("t", "dot")
            if (t !in types)
                return false to "marks[$i].t=\"$t\" 不支持（可选：${types.joinToString("/")}）"
            val x = m.optDouble("x", Double.NaN)
            val y = m.optDouble("y", Double.NaN)
            if (x.isNaN()) return false to "marks[$i] 缺少 x（0-1）"
            if (y.isNaN()) return false to "marks[$i] 缺少 y（0-1）"
            // 越界：画到框外，用户找不到
            if (x < 0 || x > 1) return false to "marks[$i].x=$x 超出 0-1——会画到框外，用户找不到"
            if (y < 0 || y > 1) return false to "marks[$i].y=$y 超出 0-1——会画到框外，用户找不到"
            if (m.optString("n").isBlank())
                return false to "marks[$i] 缺少 n（说明）——没有说明的标注不知道在指什么"
            if (t == "box") {
                val w = m.optDouble("w", Double.NaN); val h = m.optDouble("h", Double.NaN)
                if (w.isNaN() || h.isNaN()) return false to "marks[$i] 是 box 但缺少 w 或 h"
                if (x + w > 1.001) return false to "marks[$i] 的框右边超出画布（x+w=${x + w}）"
                if (y + h > 1.001) return false to "marks[$i] 的框下边超出画布（y+h=${y + h}）"
            }
        }
        return true to ""
    }

    private fun dial3(o: JSONObject): Pair<Boolean, String> {
        val dials = o.optJSONArray("dials")
            ?: return false to "zx_dial3 缺少 dials"
        if (dials.length() == 0) return false to "zx_dial3 的 dials 为空"
        if (dials.length() > 4) return false to "表盘组最多 4 个，实际 ${dials.length()} 个"
        for (i in 0 until dials.length()) {
            val d = dials.optJSONObject(i)
                ?: return false to "dials[$i] 不是对象，应为 {\"n\":名,\"v\":,\"max\":,\"warn\":,\"bad\":,\"u\":单位}"
            if (d.optString("n").isBlank()) return false to "dials[$i] 缺少 n"
            val v = d.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "dials[$i] 缺少 v（当前值）"
            val max = d.optDouble("max", Double.NaN)
            if (max.isNaN()) return false to "dials[$i] 缺少 max（量程）"
            // max 为 0：除零，指针角度算不出来
            if (max <= 0) return false to "dials[$i].max=$max 必须为正——为 0 会除零，指针角度算不出来"
            if (v < 0) return false to "dials[$i].v=$v 不能为负"
            val warn = d.optDouble("warn", max * .75)
            val bad = d.optDouble("bad", max * .9)
            if (warn >= bad)
                return false to "dials[$i] 的 warn=$warn 必须小于 bad=$bad——否则「警戒」和「危险」分不出"
            if (warn < 0 || bad > max * 1.5)
                return false to "dials[$i] 的 warn/bad 超出合理范围（0 ~ ${max * 1.5}）"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_align" -> """
            {"type":"zx_align","n":"三轮压测","mode":"stretch","series":[
              {"n":"v1.0","v":[10,30,60,90,70,40]},
              {"n":"v1.1","v":[12,45,85,60]}]}""".trimIndent()
        "zx_jsondiff" -> """
            {"type":"zx_jsondiff","n":"配置对比","rows":[
              {"p":"server.port","k":"mod","a":"8080","b":"9090"},
              {"p":"cache.ttl","k":"add","b":"600"},
              {"p":"debug","k":"del","a":"true"}]}""".trimIndent()
        "zx_threeway" -> """
            {"type":"zx_threeway","n":"合并预览","a":"你的","b":"远端","base":"基准",
             "rows":[{"f":"timeout","base":"30","a":"60","b":"45"},
                     {"f":"retries","base":"3","a":"3","b":"5"}]}""".trimIndent()
        "zx_sankey2" -> """
            {"type":"zx_sankey2","n":"能量流","loss":15,
             "nodes":[{"n":"输入","l":0},{"n":"转换","l":1},
                      {"n":"输出","l":2},{"n":"散热","l":2}],
             "flows":[{"f":0,"t":1,"v":100},{"f":1,"t":2,"v":85},
                      {"f":1,"t":3,"v":15,"loss":true}]}""".trimIndent()
        "zx_split" -> """
            {"type":"zx_split","dir":"v","ratio":0.5,"min":0.2,"max":0.8,
             "a":{"type":"zx_terminal","text":"上半"}",
             "b":{"type":"zx_terminal","text":"下半"}"}""".trimIndent()
        "zx_switch" -> """
            {"type":"zx_switch","n":"状态分支","cur":0,"cases":[
              {"when":"state=ok","label":"正常","body":{"type":"zx_terminal","text":""}},
              {"when":"default","label":"其他","body":{"type":"zx_terminal","text":""}}]}""".trimIndent()
        "zx_lane" -> """
            {"type":"zx_lane","n":"排期","slots":6,"lanes":[
              {"n":"甲","v":[0,1,1,0,2,3]},
              {"n":"乙","v":[1,0,0,1,1,0]}]}""".trimIndent()
        "zx_bullet2" -> """
            {"type":"zx_bullet2","n":"Q4 达成","items":[
              {"n":"营收","act":92,"tgt":100,"max":120,"bands":[40,70,120]},
              {"n":"利润","act":28,"tgt":25,"max":40,"bands":[15,25,40]}]}""".trimIndent()
        "zx_ring2" -> """
            {"type":"zx_ring2","n":"三年构成","rings":[
              {"n":"2023","segs":[{"n":"A","v":60},{"n":"B","v":40}]},
              {"n":"2024","segs":[{"n":"A","v":45},{"n":"B","v":55}]}]}""".trimIndent()
        "zx_stepflow" -> """
            {"type":"zx_stepflow","n":"发布流程","steps":[
              {"n":"构建","s":2},{"n":"测试","s":2},
              {"n":"部署","s":1},{"n":"验收","s":0}]}""".trimIndent()
        "zx_annotate" -> """
            {"type":"zx_annotate","n":"截图标注","marks":[
              {"t":"box","x":.1,"y":.2,"w":.3,"h":.2,"n":"这里错位"}",
              {"t":"arrow","x":.7,"y":.6,"n":"看这个"}"]}""".trimIndent()
        "zx_dial3" -> """
            {"type":"zx_dial3","n":"系统监控","dials":[
              {"n":"CPU","v":72,"max":100,"warn":75,"bad":90,"u":"%"},
              {"n":"内存","v":88,"max":100,"warn":75,"bad":90,"u":"%"}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_align"     -> "series[{n,v≥2}]  2-4 · mode ∈ stretch/raw · 长度可不一"
        "zx_jsondiff"  -> "rows[{p,k∈add/del/mod/same,a?,b?}]  ≤14 · 不可全 same"
        "zx_threeway"  -> "rows[{f,base,a,b}]  ≤12 · 缺 base 就不是三方比对"
        "zx_sankey2"   -> "nodes[{n,l}] ≤6 + flows[{f,t,v>0,loss?}] ≤8 · 只能往更深层流"
        "zx_split"     -> "a + b + dir ∈ v/h · min<max，ratio 在范围内"
        "zx_switch"    -> "cases[{when,label,body}] ≤5 · 必须有一个 default"
        "zx_lane"      -> "lanes[{n,v[]}] ≤5 · 每道格数必须等于 slots，状态 0-3"
        "zx_bullet2"   -> "items[{n,act,tgt,max,bands?}] ≤8 · max 不得小于 act/tgt"
        "zx_ring2"     -> "rings[{n,segs≥2}] ≤4 · 每环总和必须 >0"
        "zx_stepflow"  -> "steps[{n,s}]  2-8 · 必须有且仅有一个「进行中」或存在「失败」"
        "zx_annotate"  -> "marks[{t,x,y,n}]  ≤10 · 坐标 0-1，越界会画到框外"
        "zx_dial3"     -> "dials[{n,v,max>0,warn<bad}]  ≤4 · max 为 0 会除零"
        else -> "未注册组件：$component"
    }
}
