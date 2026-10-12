package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第十八批组件（编辑 / 映射 / 度量）的校验 + 示例 + 规格。
 *
 * 这批查的是**「用户改的东西能不能看出改了」**：
 *
 *   · 流程图 edges 指向不存在的节点 → 画一条连到空白的线
 *   · 裁剪框 w/h 为 0 → 选区不可见，用户以为没选中
 *   · 裁剪框 x+w 超出 1 → 选区跑到图外
 *   · 映射 from/to 为空 → 没有可配对的东西
 *   · 比率 a、b 都为 0 → 比率算不出来
 *   · 双轨时间轴 e < s → 条长为负
 *   · 天平 l、r 都为 0 → 倾角算不出来
 *   · 刻度盘 max ≤ min → 指针角度除零
 *   · 时间段 s+len 超出日程范围 → 块排到界外
 *
 * 与前面几批的差别：这批有一半是**编辑器**。
 * 编辑器的校验重点是"初始状态就是可用的"：
 * 选区不可见、映射源为空、指针角度除零——
 * 这些都不崩，只是让用户面对一个**看起来能用、
 * 实际操作不出结果**的界面。比崩溃更糟，
 * 因为模型会以为下发成功了。
 */
object ZxDsl18 {

    val ALL = listOf(
        "zx_flowedit", "zx_crop", "zx_mapping", "zx_ratio",
        "zx_deviation", "zx_timeline2", "zx_scale2", "zx_mirror",
        "zx_dial2", "zx_matrix2", "zx_ladder", "zx_slot"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_flowedit"  -> flowedit(root)
        "zx_crop"      -> crop(root)
        "zx_mapping"   -> mapping(root)
        "zx_ratio"     -> ratio(root)
        "zx_deviation" -> deviation(root)
        "zx_timeline2" -> timeline2(root)
        "zx_scale2"    -> scale2(root)
        "zx_mirror"    -> mirror(root)
        "zx_dial2"     -> dial2(root)
        "zx_matrix2"   -> matrix2(root)
        "zx_ladder"    -> ladder(root)
        "zx_slot"      -> slot(root)
        else -> false to "未注册组件：$component"
    }

    private fun flowedit(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_flowedit 缺少 nodes"
        if (nodes.length() < 2) return false to "流程图至少 2 个节点，实际 ${nodes.length()} 个"
        if (nodes.length() > 10) return false to "流程图最多 10 个节点，实际 ${nodes.length()} 个"
        val n = nodes.length()
        for (i in 0 until n) {
            val nd = nodes.optJSONObject(i)
                ?: return false to "nodes[$i] 不是对象，应为 {\"n\":名,\"x\":0-1,\"y\":0-1}"
            if (nd.optString("n").isBlank()) return false to "nodes[$i] 缺少 n"
            val x = nd.optDouble("x", Double.NaN)
            if (!x.isNaN() && (x < 0 || x > 1))
                return false to "nodes[$i].x=$x 超出 0-1"
            val y = nd.optDouble("y", Double.NaN)
            if (!y.isNaN() && (y < 0 || y > 1))
                return false to "nodes[$i].y=$y 超出 0-1"
        }
        val edges = o.optJSONArray("edges")
        if (edges != null) {
            if (edges.length() > 24) return false to "连线最多 24 条，实际 ${edges.length()} 条"
            for (k in 0 until edges.length()) {
                val e = edges.optJSONObject(k)
                    ?: return false to "edges[$k] 不是对象，应为 {\"f\":从,\"t\":到}"
                val f = e.optInt("f", -1); val t = e.optInt("t", -1)
                if (f < 0 || t < 0) return false to "edges[$k] 缺少 f 或 t"
                // 指向不存在的节点：会画一条连到空白的线
                if (f >= n || t >= n)
                    return false to "edges[$k] 的 f=$f t=$t 越界（共 $n 个节点）——会连到空白"
                if (f == t) return false to "edges[$k] 是自环（f=t=$f）"
            }
        }
        return true to ""
    }

    private fun crop(o: JSONObject): Pair<Boolean, String> {
        val x = o.optDouble("x", .15)
        val y = o.optDouble("y", .15)
        val w = o.optDouble("w", .6)
        val h = o.optDouble("h", .6)
        for ((k, v) in listOf("x" to x, "y" to y, "w" to w, "h" to h))
            if (v.isNaN()) return false to "zx_crop 的 $k 不是数值"
        // w/h 为 0：选区不可见，用户以为没选中
        if (w <= 0) return false to "zx_crop 的 w=$w 必须为正——选区不可见，用户会以为没选中"
        if (h <= 0) return false to "zx_crop 的 h=$h 必须为正——选区不可见"
        if (x < 0 || y < 0) return false to "zx_crop 的 x=$x y=$y 不能为负"
        if (x + w > 1.0001)
            return false to "zx_crop 的 x+w=${x + w} 超出 1——选区会跑到图外"
        if (y + h > 1.0001)
            return false to "zx_crop 的 y+h=${y + h} 超出 1——选区会跑到图外"
        val ratio = o.optDouble("ratio", 0.0)
        if (!ratio.isNaN() && ratio < 0)
            return false to "zx_crop 的 ratio=$ratio 不能为负（0 表示自由比例）"
        return true to ""
    }

    private fun mapping(o: JSONObject): Pair<Boolean, String> {
        val from = o.optJSONArray("from")
            ?: return false to "zx_mapping 缺少 from（源字段）"
        val to = o.optJSONArray("to")
            ?: return false to "zx_mapping 缺少 to（目标字段）"
        if (from.length() == 0) return false to "zx_mapping 的 from 为空——没有可配对的源"
        if (to.length() == 0) return false to "zx_mapping 的 to 为空——没有可配对的目标"
        if (from.length() > 8) return false to "源字段最多 8 个，实际 ${from.length()} 个"
        if (to.length() > 8) return false to "目标字段最多 8 个，实际 ${to.length()} 个"
        for (i in 0 until from.length()) {
            val f = from.optJSONObject(i)
                ?: return false to "from[$i] 不是对象，应为 {\"k\":键,\"n\":显示名,\"to\":初始映射?}"
            if (f.optString("k").isBlank()) return false to "from[$i] 缺少 k"
            if (f.optString("n").isBlank()) return false to "from[$i] 缺少 n"
            if (f.has("to")) {
                val t = f.optInt("to", -1)
                // 初始映射越界：界面上会显示一个已配对但配到不存在的字段
                if (t >= to.length())
                    return false to "from[$i].to=$t 越界（目标只有 ${to.length()} 个）——会配到不存在的字段"
            }
        }
        for (j in 0 until to.length()) {
            val t = to.optJSONObject(j)
                ?: return false to "to[$j] 不是对象，应为 {\"k\":键,\"n\":显示名}"
            if (t.optString("k").isBlank()) return false to "to[$j] 缺少 k"
            if (t.optString("n").isBlank()) return false to "to[$j] 缺少 n"
        }
        // 目标比源少：必然有配不上的，提醒而不是拦——用户可能就是要部分映射
        return true to ""
    }

    private fun ratio(o: JSONObject): Pair<Boolean, String> {
        val a = o.optDouble("a", Double.NaN)
        val b = o.optDouble("b", Double.NaN)
        if (a.isNaN()) return false to "zx_ratio 缺少 a"
        if (b.isNaN()) return false to "zx_ratio 缺少 b"
        if (a < 0 || b < 0) return false to "zx_ratio 的 a=$a b=$b 不能为负"
        // 都为 0：比率算不出来
        if (a == 0.0 && b == 0.0)
            return false to "zx_ratio 的 a 和 b 都是 0——比率算不出来"
        if (b == 0.0)
            return false to "zx_ratio 的 b=0——除零，比率算不出来"
        val target = o.optDouble("target", 0.0)
        if (!target.isNaN() && target < 0)
            return false to "zx_ratio 的 target=$target 不能为负（0 表示不显示目标线）"
        return true to ""
    }

    private fun deviation(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_deviation 缺少 items"
        if (items.length() == 0) return false to "zx_deviation 的 items 为空"
        if (items.length() > 20) return false to "偏差最多 20 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"act\":实际,\"tgt\":目标}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            if (!it.has("act")) return false to "items[$i] 缺少 act（实际值）"
            // tgt 为 0：偏差百分比除零
            if (!it.has("tgt") || it.optDouble("tgt", 0.0) == 0.0)
                return false to "items[$i] 缺少 tgt 或 tgt=0——偏差百分比除零"
        }
        return true to ""
    }

    private fun timeline2(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_timeline2 缺少 rows"
        if (rows.length() == 0) return false to "zx_timeline2 的 rows 为空"
        if (rows.length() > 6) return false to "双轨时间轴最多 6 行，实际 ${rows.length()} 行"
        val t0 = o.optDouble("from", 0.0)
        val t1 = o.optDouble("to", 100.0)
        val span = t1 - t0
        if (span <= 0)
            return false to "zx_timeline2 的 from=$t0 to=$t1，区间必须为正——否则条长算不出来"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"n\":名,\"s\":开始,\"e\":结束,\"ps\":,\"pe\":}"
            if (r.optString("n").isBlank()) return false to "rows[$i] 缺少 n"
            val s = r.optDouble("s", Double.NaN)
            val e = r.optDouble("e", Double.NaN)
            if (s.isNaN()) return false to "rows[$i] 缺少 s（实际开始）"
            if (e.isNaN()) return false to "rows[$i] 缺少 e（实际结束）"
            // e < s：条长为负
            if (e < s)
                return false to "rows[$i] 的 e=$e < s=$s——条长为负"
            if (r.has("ps") && r.has("pe")) {
                val ps = r.optDouble("ps", s); val pe = r.optDouble("pe", e)
                if (pe < ps)
                    return false to "rows[$i] 的 pe=$pe < ps=$ps——计划条长为负"
            }
            if (s < t0 - 1e-6 || e > t1 + 1e-6)
                return false to "rows[$i] 的 s=$s e=$e 超出 from-to ($t0-$t1)——会画到区间外"
        }
        return true to ""
    }

    private fun scale2(o: JSONObject): Pair<Boolean, String> {
        val l = o.optDouble("l", Double.NaN)
        val r = o.optDouble("r", Double.NaN)
        if (l.isNaN()) return false to "zx_scale2 缺少 l（左盘）"
        if (r.isNaN()) return false to "zx_scale2 缺少 r（右盘）"
        if (l < 0 || r < 0) return false to "zx_scale2 的 l=$l r=$r 不能为负"
        // 都为 0：倾角算不出来
        if (l == 0.0 && r == 0.0)
            return false to "zx_scale2 的 l 和 r 都是 0——倾角算不出来，两边都是空的"
        return true to ""
    }

    private fun mirror(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_mirror 缺少 items"
        if (items.length() == 0) return false to "zx_mirror 的 items 为空"
        if (items.length() > 8) return false to "镜像对比最多 8 项，实际 ${items.length()} 项"
        var allZero = true
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"a\":,\"b\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            if (!it.has("a") || !it.has("b"))
                return false to "items[$i] 缺少 a 或 b——两侧都要有值才能对比"
            val a = it.optDouble("a", 0.0); val b = it.optDouble("b", 0.0)
            if (a < 0 || b < 0) return false to "items[$i] 的 a=$a b=$b 不能为负"
            if (a > 0 || b > 0) allZero = false
        }
        // 全 0：两侧都是空的，看不出任何对比
        if (allZero) return false to "所有项的 a 和 b 都是 0——两侧都是空的，看不出对比"
        return true to ""
    }

    private fun dial2(o: JSONObject): Pair<Boolean, String> {
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return false to "zx_dial2 缺少 v（当前值）——指针不知道指哪"
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0)
        // max ≤ min：指针角度除零
        if (max <= min)
            return false to "zx_dial2 的 max=$max 必须大于 min=$min——否则指针角度除零"
        if (v < min || v > max)
            return false to "zx_dial2 的 v=$v 超出 [$min, $max]——指针会停到刻度外"
        val zones = o.optJSONArray("zones")
        if (zones != null) {
            if (zones.length() > 4) return false to "区间最多 4 段，实际 ${zones.length()} 段"
            for (k in 0 until zones.length()) {
                val z = zones.optJSONObject(k)
                    ?: return false to "zones[$k] 不是对象，应为 {\"f\":,\"t\":,\"k\":\"ok|warn|err\"}"
                val f = z.optDouble("f", Double.NaN)
                val t = z.optDouble("t", Double.NaN)
                if (f.isNaN() || t.isNaN()) return false to "zones[$k] 缺少 f 或 t"
                if (t <= f) return false to "zones[$k] 的 t=$t 必须大于 f=$f"
                if (f < min || t > max)
                    return false to "zones[$k] 的 f=$f t=$t 超出 [$min, $max]——区间带画到刻度外"
                val kind = z.optString("k", "")
                if (kind !in setOf("ok", "warn", "err"))
                    return false to "zones[$k].k=\"$kind\" 只能是 ok / warn / err"
            }
        }
        return true to ""
    }

    private fun matrix2(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_matrix2 缺少 rows"
        val cols = o.optJSONArray("cols")
            ?: return false to "zx_matrix2 缺少 cols"
        if (rows.length() == 0) return false to "zx_matrix2 的 rows 为空"
        if (cols.length() < 2)
            return false to "矩阵至少 2 列，实际 ${cols.length()} 列——1 列就是个普通列表，用 zx_choice 更合适"
        if (rows.length() > 6) return false to "矩阵最多 6 行，实际 ${rows.length()} 行"
        if (cols.length() > 5) return false to "矩阵最多 5 列，实际 ${cols.length()} 列"
        for (i in 0 until rows.length())
            if (rows.optString(i).isBlank()) return false to "rows[$i] 是空字符串"
        for (j in 0 until cols.length())
            if (cols.optString(j).isBlank()) return false to "cols[$j] 是空字符串"
        val mode = o.optString("mode", "one")
        if (mode !in setOf("one", "row"))
            return false to "zx_matrix2 的 mode=\"$mode\" 只能是 one（单选）或 row（每行一个）"
        return true to ""
    }

    private fun ladder(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_ladder 缺少 items"
        if (items.length() < 2)
            return false to "阶梯至少 2 项，实际 ${items.length()} 项——1 项没有排序可言"
        if (items.length() > 8) return false to "阶梯最多 8 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":数值}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            if (!it.has("v")) return false to "items[$i] 缺少 v"
        }
        return true to ""
    }

    private fun slot(o: JSONObject): Pair<Boolean, String> {
        val blocks = o.optJSONArray("blocks")
            ?: return false to "zx_slot 缺少 blocks"
        if (blocks.length() == 0) return false to "zx_slot 的 blocks 为空"
        if (blocks.length() > 8) return false to "时间段最多 8 块，实际 ${blocks.length()} 块"
        val from = o.optDouble("from", 8.0)
        val to = o.optDouble("to", 20.0)
        val span = to - from
        if (span <= 0)
            return false to "zx_slot 的 from=$from to=$to，区间必须为正——否则位置算不出来"
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i)
                ?: return false to "blocks[$i] 不是对象，应为 {\"n\":名,\"s\":开始,\"len\":时长}"
            if (b.optString("n").isBlank()) return false to "blocks[$i] 缺少 n"
            val s = b.optDouble("s", Double.NaN)
            val len = b.optDouble("len", Double.NaN)
            if (s.isNaN()) return false to "blocks[$i] 缺少 s（开始）"
            if (len.isNaN()) return false to "blocks[$i] 缺少 len（时长）"
            if (len <= 0) return false to "blocks[$i].len=$len 必须为正——看不见的块"
            // s+len 超出：块排到界外
            if (s < from - 1e-6)
                return false to "blocks[$i].s=$s 早于 from=$from——会排到界外"
            if (s + len > to + 1e-6)
                return false to "blocks[$i] 的 s+len=${s + len} 超出 to=$to——会排到界外"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_flowedit" -> """
            {"type":"zx_flowedit","nodes":[
              {"n":"开始","x":0.15,"y":0.5},{"n":"校验","x":0.45,"y":0.3},
              {"n":"结束","x":0.8,"y":0.5}],
             "edges":[{"f":0,"t":1},{"f":1,"t":2}]}""".trimIndent()
        "zx_crop" -> """
            {"type":"zx_crop","src":"photo.jpg","x":0.15,"y":0.15,"w":0.6,"h":0.6,
             "ratio":0}""".trimIndent()
        "zx_mapping" -> """
            {"type":"zx_mapping","lname":"源表","rname":"目标表",
             "from":[{"k":"name","n":"姓名","to":0},{"k":"age","n":"年龄"}"],
             "to":[{"k":"fullname","n":"全名"}",{"k":"years","n":"岁数"}"]}""".trimIndent()
        "zx_ratio" -> """
            {"type":"zx_ratio","n":"投入产出","a":320,"b":80,"target":3,
             "an":"收入","bn":"成本"}"""".trimIndent()
        "zx_deviation" -> """
            {"type":"zx_deviation","n":"月度偏差","items":[
              {"n":"1月","act":110,"tgt":100},{"n":"2月","act":92,"tgt":100},
              {"n":"3月","act":118,"tgt":100}]}""".trimIndent()
        "zx_timeline2" -> """
            {"type":"zx_timeline2","from":0,"to":30,"rows":[
              {"n":"设计","s":0,"e":6,"ps":0,"pe":5},
              {"n":"开发","s":5,"e":18,"ps":5,"pe":14}]}""".trimIndent()
        "zx_scale2" -> """
            {"type":"zx_scale2","l":320,"r":180,"ln":"收入","rn":"成本"}"""".trimIndent()
        "zx_mirror" -> """
            {"type":"zx_mirror","an":"去年","bn":"今年","items":[
              {"n":"Q1","a":120,"b":150},{"n":"Q2","a":140,"b":132}]}""".trimIndent()
        "zx_dial2" -> """
            {"type":"zx_dial2","v":72,"min":0,"max":100,"u":"%",
             "zones":[{"f":0,"t":40,"k":"err"},{"f":40,"t":75,"k":"warn"},
                      {"f":75,"t":100,"k":"ok"}]}""".trimIndent()
        "zx_matrix2" -> """
            {"type":"zx_matrix2","mode":"row","rows":["成本","性能","可维护"],
             "cols":["低","中","高"]}""".trimIndent()
        "zx_ladder" -> """
            {"type":"zx_ladder","items":[
              {"n":"A","v":92},{"n":"B","v":78},{"n":"C","v":65}]}""".trimIndent()
        "zx_slot" -> """
            {"type":"zx_slot","from":8,"to":20,"blocks":[
              {"n":"晨会","s":9,"len":0.5},{"n":"开发","s":10,"len":4},
              {"n":"评审","s":15,"len":1}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_flowedit"  -> "nodes[{n,x?,y?}]  2-10 + edges?[{f,t}]  ≤24 · 无自环无越界"
        "zx_crop"      -> "x,y,w,h（w/h>0，x+w≤1，y+h≤1）+ ratio?（0=自由）+ src?"
        "zx_mapping"   -> "from[{k,n,to?}] + to[{k,n}]  各 ≤8 · to 索引不越界"
        "zx_ratio"     -> "a + b（b>0）+ target?（0=不显示）· 不可全 0"
        "zx_deviation" -> "items[{n,act,tgt≠0}]  ≤20"
        "zx_timeline2" -> "rows[{n,s,e,ps?,pe?}]  ≤6 + from<to · e≥s 且不越界"
        "zx_scale2"    -> "l + r（非负，不可全 0）· 倾角 ±12° 上限"
        "zx_mirror"    -> "items[{n,a,b}]  2-8 · 不可全 0"
        "zx_dial2"     -> "v + min<max + zones?[{f,t,k}]  ≤4 · v 不越界"
        "zx_matrix2"   -> "rows ≤6 + cols 2-5 · mode ∈ one/row"
        "zx_ladder"    -> "items[{n,v}]  2-8 · 可界面内重排"
        "zx_slot"      -> "blocks[{n,s,len>0}]  ≤8 + from<to · s+len 不越界"
        else -> "未注册组件：$component"
    }
}
