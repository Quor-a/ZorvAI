package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第十七批组件（空间 / 符号 / 动效）的校验 + 示例 + 规格。
 *
 * 这批查的是**「动效能被关掉」和「前提是成立的」**：
 *
 *   · 地理点阵坐标全相同 → 所有点叠在一个位置
 *   · 公式没有 expr → 没有可渲染的表达式
 *   · 六边形网格 coords 相同 → 格子重叠成一坨
 *   · 螺旋少于 4 个点 → 螺旋不起来，就是折线
 *   · 跑马灯只有 1 条 → 不需要滚动，滚动反而读不完
 *   · 频谱全 0 → 柱子全空
 *   · 曲线编辑器 x 超出 0-1 → 不是函数（一个 x 对应多个 y）
 *
 * 与前面几批的差别：这批有一半是**动效组件**。
 * 动效组件的校验重点是"能不能关"和"关了之后还成不成立"——
 * 冻结后回传的必须是静态值（频谱），
 * 跳到终值后显示的必须是目标值（数字滚动）。
 * 动效不能关就是噪音，而"关了之后显示错的值"比不关更糟。
 */
object ZxDsl17 {

    val ALL = listOf(
        "zx_geo", "zx_formula", "zx_tokens", "zx_ast",
        "zx_hexgrid", "zx_spiral", "zx_marquee", "zx_odometer",
        "zx_radial", "zx_isometric", "zx_spectrum", "zx_bezier"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_geo"       -> geo(root)
        "zx_formula"   -> formula(root)
        "zx_tokens"    -> tokens(root)
        "zx_ast"       -> ast(root)
        "zx_hexgrid"   -> hexgrid(root)
        "zx_spiral"    -> spiral(root)
        "zx_marquee"   -> marquee(root)
        "zx_odometer"  -> odometer(root)
        "zx_radial"    -> radial(root)
        "zx_isometric" -> isometric(root)
        "zx_spectrum"  -> spectrum(root)
        "zx_bezier"    -> bezier(root)
        else -> false to "未注册组件：$component"
    }

    private fun geo(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts")
            ?: return false to "zx_geo 缺少 pts"
        if (pts.length() < 2) return false to "地理点阵至少 2 个点，实际 ${pts.length()} 个"
        if (pts.length() > 24) return false to "地理点阵最多 24 个点，实际 ${pts.length()} 个"
        var hasLa = false; var hasLo = false
        for (i in 0 until pts.length()) {
            val p = pts.optJSONObject(i)
                ?: return false to "pts[$i] 不是对象，应为 {\"n\":名,\"la\":纬度,\"lo\":经度,\"v\":}"
            if (p.optString("n").isBlank()) return false to "pts[$i] 缺少 n"
            if (p.has("la")) hasLa = true
            if (p.has("lo")) hasLo = true
            val la = p.optDouble("la", Double.NaN)
            if (!la.isNaN() && (la < -90 || la > 90))
                return false to "pts[$i].la=$la 超出 -90~90（纬度范围）"
            val lo = p.optDouble("lo", Double.NaN)
            if (!lo.isNaN() && (lo < -180 || lo > 180))
                return false to "pts[$i].lo=$lo 超出 -180~180（经度范围）"
            if (p.has("v") && p.optDouble("v") < 0)
                return false to "pts[$i].v 为负，半径算不出来"
        }
        if (!hasLa || !hasLo)
            return false to "缺少 la 或 lo——没有坐标就只是散点图，不是地理图"
        // 坐标全相同：所有点叠在一个位置
        val las = (0 until pts.length()).map { pts.optJSONObject(it)?.optDouble("la", 0.0) ?: 0.0 }
        val los = (0 until pts.length()).map { pts.optJSONObject(it)?.optDouble("lo", 0.0) ?: 0.0 }
        if (las.maxOrNull()!! - las.minOrNull()!! < 1e-9 &&
            los.maxOrNull()!! - los.minOrNull()!! < 1e-9)
            return false to "所有点坐标相同——会叠在一个位置，投影区间为 0"
        return true to ""
    }

    private fun formula(o: JSONObject): Pair<Boolean, String> {
        val e = o.optJSONObject("expr")
            ?: return false to "zx_formula 缺少 expr（结构化表达式）"
        var count = 0
        val ops = setOf("frac", "sqrt", "pow", "add", "sub", "mul", "div")
        fun walk(n: JSONObject, depth: Int): String? {
            count++
            if (count > 40) return "表达式节点超过 40 个，气泡里放不下"
            if (depth > 4) return "表达式嵌套超过 4 层"
            val op = n.optString("op")
            if (op.isBlank()) {
                if (n.optString("v").isBlank())
                    return "叶子节点既没 op 也没 v——会渲染成空白"
                return null
            }
            if (op !in ops)
                return "op=\"$op\" 不支持（可选：${ops.joinToString("/")}）"
            val kids = n.optJSONArray("c")
            val need = when (op) { "sqrt" -> 1; else -> 2 }
            if (kids == null || kids.length() != need)
                return "$op 需要 $need 个子节点，实际 ${kids?.length() ?: 0} 个"
            for (i in 0 until kids.length()) {
                val k = kids.optJSONObject(i) ?: return "c[$i] 不是对象"
                walk(k, depth + 1)?.let { return it }
            }
            return null
        }
        walk(e, 0)?.let { return false to it }
        return true to ""
    }

    private fun tokens(o: JSONObject): Pair<Boolean, String> {
        val t = o.optJSONArray("tokens")
            ?: return false to "zx_tokens 缺少 tokens"
        if (t.length() == 0) return false to "zx_tokens 的 tokens 为空"
        if (t.length() > 40) return false to "token 最多 40 个，实际 ${t.length()} 个"
        val kinds = setOf("kw", "str", "num", "cmt", "fn", "id", "op", "ty")
        for (i in 0 until t.length()) {
            val x = t.optJSONObject(i)
                ?: return false to "tokens[$i] 不是对象，应为 {\"v\":文本,\"k\":种类,\"nl\":换行?}"
            if (x.optString("v").isBlank()) return false to "tokens[$i] 缺少 v（token 文本）"
            val k = x.optString("k", "id")
            if (k !in kinds)
                return false to "tokens[$i].k=\"$k\" 不支持（可选：${kinds.joinToString("/")}）"
        }
        return true to ""
    }

    private fun ast(o: JSONObject): Pair<Boolean, String> {
        val root = o.optJSONObject("root")
            ?: return false to "zx_ast 缺少 root"
        if (root.optString("n").isBlank()) return false to "root 缺少 n（节点名）"
        val kinds = setOf("fn", "call", "lit", "op", "id", "decl")
        var count = 0
        fun walk(n: JSONObject, depth: Int): String? {
            count++
            if (count > 40) return "AST 节点超过 40 个，气泡里放不下"
            if (depth > 4) return "AST 嵌套超过 4 层"
            if (n.optString("n").isBlank()) return "有节点缺少 n——会渲染成空行"
            val k = n.optString("k", "id")
            if (k !in kinds) return "k=\"$k\" 不支持（可选：${kinds.joinToString("/")}）"
            val kids = n.optJSONArray("c") ?: return null
            for (i in 0 until kids.length()) {
                val c = kids.optJSONObject(i) ?: return "c[$i] 不是对象"
                walk(c, depth + 1)?.let { return it }
            }
            return null
        }
        walk(root, 0)?.let { return false to it }
        return true to ""
    }

    private fun hexgrid(o: JSONObject): Pair<Boolean, String> {
        val cells = o.optJSONArray("cells")
            ?: return false to "zx_hexgrid 缺少 cells"
        if (cells.length() == 0) return false to "zx_hexgrid 的 cells 为空"
        if (cells.length() > 18) return false to "六边形网格最多 18 格，实际 ${cells.length()} 格"
        val cols = o.optInt("cols", 4)
        if (cols !in 2..6) return false to "zx_hexgrid 的 cols=$cols 超出 2-6"
        for (i in 0 until cells.length()) {
            val c = cells.optJSONObject(i)
                ?: return false to "cells[$i] 不是对象，应为 {\"n\":名,\"v\":0-1}"
            if (c.optString("n").isBlank()) return false to "cells[$i] 缺少 n"
            val v = c.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "cells[$i] 缺少 v"
            if (v < 0 || v > 1) return false to "cells[$i].v=$v 超出 0-1（用于取色阶）"
        }
        return true to ""
    }

    private fun spiral(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_spiral 缺少 items"
        if (items.length() < 4)
            return false to "螺旋至少 4 个点，实际 ${items.length()} 个——少于 4 个螺旋不起来，就是折线"
        if (items.length() > 24) return false to "螺旋最多 24 个点，实际 ${items.length()} 个"
        val turns = o.optDouble("turns", 2.0)
        if (turns < 1.0 || turns > 4.0)
            return false to "zx_spiral 的 turns=$turns 超出 1-4"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":0-1}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v"
            if (v < 0 || v > 1) return false to "items[$i].v=$v 超出 0-1"
        }
        return true to ""
    }

    private fun marquee(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_marquee 缺少 items"
        if (items.length() < 2)
            return false to "跑马灯至少 2 条，实际 ${items.length()} 条——1 条不需要滚动，滚动反而读不完"
        if (items.length() > 8) return false to "跑马灯最多 8 条，实际 ${items.length()} 条"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"t\":文本}"
            if (it.optString("t").isBlank())
                return false to "items[$i] 缺少 t——会滚过去一段空白"
        }
        val speed = o.optInt("speed", 3)
        if (speed !in 1..5) return false to "zx_marquee 的 speed=$speed 超出 1-5"
        return true to ""
    }

    private fun odometer(o: JSONObject): Pair<Boolean, String> {
        if (!o.has("v"))
            return false to "zx_odometer 缺少 v（目标值）——没有目标就不知道滚到哪"
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return false to "zx_odometer 的 v 不是数值"
        val dur = o.optInt("dur", 1200)
        // dur=0 表示直接显示终值，这是合法的
        if (dur < 0 || dur > 5000)
            return false to "zx_odometer 的 dur=$dur 超出 0-5000（0 = 直接跳到终值）"
        val dec = o.optInt("dec", 0)
        if (dec !in 0..3) return false to "zx_odometer 的 dec=$dec 超出 0-3"
        return true to ""
    }

    private fun radial(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_radial 缺少 items"
        if (items.length() < 2)
            return false to "径向菜单至少 2 个扇区，实际 ${items.length()} 个——1 个就是整圆，看不出是菜单"
        if (items.length() > 8) return false to "径向菜单最多 8 个扇区，实际 ${items.length()} 个"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"k\":键,\"n\":名,\"d\":描述}"
            if (it.optString("k").isBlank()) return false to "items[$i] 缺少 k"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
        }
        val inner = o.optDouble("inner", .32)
        if (inner < .15 || inner > .6)
            return false to "zx_radial 的 inner=$inner 超出 0.15-0.6——太小放不下中心文字，太大扇区就没了"
        return true to ""
    }

    private fun isometric(o: JSONObject): Pair<Boolean, String> {
        val blocks = o.optJSONArray("blocks")
            ?: return false to "zx_isometric 缺少 blocks"
        if (blocks.length() == 0) return false to "zx_isometric 的 blocks 为空"
        if (blocks.length() > 14) return false to "等距视图最多 14 块，实际 ${blocks.length()} 块"
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i)
                ?: return false to "blocks[$i] 不是对象，应为 {\"n\":名,\"x\":,\"y\":,\"h\":高度}"
            if (b.optString("n").isBlank()) return false to "blocks[$i] 缺少 n"
            val h = b.optDouble("h", Double.NaN)
            if (h.isNaN()) return false to "blocks[$i] 缺少 h（高度）"
            if (h <= 0) return false to "blocks[$i].h=$h 必须为正，否则画不出方块"
        }
        // 位置完全重复：两块重叠，看不出有两块
        val seen = mutableSetOf<String>()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val key = "${b.optDouble("x", 0.0)},${b.optDouble("y", 0.0)}"
            if (!seen.add(key))
                return false to "blocks[$i] 与前面的块位置相同（x,y=$key）——两块会完全重叠"
        }
        return true to ""
    }

    private fun spectrum(o: JSONObject): Pair<Boolean, String> {
        val bands = o.optJSONArray("bands")
            ?: return false to "zx_spectrum 缺少 bands"
        if (bands.length() < 4)
            return false to "频谱至少 4 个频段，实际 ${bands.length()} 个——太少看不出频谱"
        if (bands.length() > 32) return false to "频谱最多 32 个频段，实际 ${bands.length()} 个"
        var allZero = true
        for (i in 0 until bands.length()) {
            val v = bands.optDouble(i, Double.NaN)
            if (v.isNaN()) return false to "bands[$i] 不是数值"
            if (v < 0 || v > 1) return false to "bands[$i]=$v 超出 0-1"
            if (v > 0) allZero = false
        }
        // 全 0：柱子全空，而且冻结/继续没有区别
        if (allZero) return false to "所有频段都是 0——柱子全空，冻结也没意义"
        return true to ""
    }

    private fun bezier(o: JSONObject): Pair<Boolean, String> {
        val ps = listOf("p1x", "p2x")
        for (p in ps) {
            val v = o.optDouble(p, Double.NaN)
            if (v.isNaN()) return false to "zx_bezier 缺少 $p"
            // x 必须夹在 0-1：超出就不是一个 x 对应一个 y，不是函数
            if (v < 0 || v > 1)
                return false to "$p=$v 超出 0-1——控制点的 x 超出就不是函数了（一个 x 对应多个 y）"
        }
        // y 允许超出（回弹效果），但不能是 NaN
        for (p in listOf("p1y", "p2y")) {
            val v = o.optDouble(p, Double.NaN)
            if (v.isNaN()) return false to "zx_bezier 缺少 $p"
            if (v < -0.5 || v > 1.5)
                return false to "$p=$v 超出 -0.5~1.5（y 允许回弹，但不能离谱）"
        }
        val presets = o.optJSONArray("presets")
        if (presets != null) {
            if (presets.length() > 6) return false to "预设最多 6 个，实际 ${presets.length()} 个"
            for (i in 0 until presets.length()) {
                val p = presets.optJSONObject(i)
                    ?: return false to "presets[$i] 不是对象"
                if (p.optString("n").isBlank()) return false to "presets[$i] 缺少 n"
                listOf("p1x", "p2x").forEach { k ->
                    val v = p.optDouble(k, Double.NaN)
                    if (v.isNaN() || v < 0 || v > 1)
                        return false to "presets[$i].$k 缺失或超出 0-1"
                }
            }
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_geo" -> """
            {"type":"zx_geo","pts":[
              {"n":"北京","la":39.9,"lo":116.4,"v":8},
              {"n":"上海","la":31.2,"lo":121.5,"v":9},
              {"n":"广州","la":23.1,"lo":113.3,"v":6}]}""".trimIndent()
        "zx_formula" -> """
            {"type":"zx_formula","lhs":"E",
             "expr":{"op":"frac","c":[{"v":"m"},{"op":"pow","c":
               [{"v":"c"},{"v":"2"}]}]},
             "subs":[{"k":"m","d":"质量","v":"2.0 kg"}]}""".trimIndent()
        "zx_tokens" -> """
            {"type":"zx_tokens","tokens":[
              {"v":"fun","k":"kw"},{"v":"calc","k":"fn"},{"v":"(","k":"op"},
              {"v":"42","k":"num","nl":true}]}""".trimIndent()
        "zx_ast" -> """
            {"type":"zx_ast","root":{"n":"Call","k":"call","c":[
              {"n":"calc","k":"id"},{"n":"42","k":"lit"}]}}""".trimIndent()
        "zx_hexgrid" -> """
            {"type":"zx_hexgrid","cols":4,"cells":[
              {"n":"A","v":0.2},{"n":"B","v":0.6},{"n":"C","v":0.9}]}""".trimIndent()
        "zx_spiral" -> """
            {"type":"zx_spiral","turns":2,"items":[
              {"n":"1 月","v":0.3},{"n":"2 月","v":0.5},
              {"n":"3 月","v":0.8},{"n":"4 月","v":0.6}]}""".trimIndent()
        "zx_marquee" -> """
            {"type":"zx_marquee","n":"系统公告","speed":3,"items":[
              {"t":"今晚 02:00 维护"}",{"t":"新版本已发布"}"]}""".trimIndent()
        "zx_odometer" -> """
            {"type":"zx_odometer","n":"今日访问","v":15600,"dur":1200,"dec":0}""".trimIndent()
        "zx_radial" -> """
            {"type":"zx_radial","inner":0.32,"items":[
              {"k":"a","n":"复制","d":"复制到剪贴板"}",
              {"k":"b","n":"分享"}",{"k":"c","n":"删除"}"]}""".trimIndent()
        "zx_isometric" -> """
            {"type":"zx_isometric","blocks":[
              {"n":"服务A","x":0,"y":0,"h":3},
              {"n":"服务B","x":1,"y":0,"h":5}]}""".trimIndent()
        "zx_spectrum" -> """
            {"type":"zx_spectrum","n":"音频频谱",
             "bands":[0.2,0.5,0.8,0.6,0.3,0.7,0.9,0.4]}""".trimIndent()
        "zx_bezier" -> """
            {"type":"zx_bezier","p1x":0.25,"p1y":0.1,"p2x":0.25,"p2y":1,
             "presets":[{"n":"ease","p1x":0.25,"p1y":0.1,"p2x":0.25,"p2y":1}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_geo"       -> "pts[{n,la -90~90,lo -180~180,v?}]  2-24 · 坐标不可全同"
        "zx_formula"   -> "lhs? + expr{op|c[]}  ≤4 层 · op ∈ frac/sqrt/pow/add/sub/mul/div"
        "zx_tokens"    -> "tokens[{v,k,nl?}]  ≤40 · k ∈ kw/str/num/cmnt→cmt/fn/id/op/ty"
        "zx_ast"       -> "root{n,k,c?[]}  ≤4 层 · k ∈ fn/call/lit/op/id/decl"
        "zx_hexgrid"   -> "cells[{n,v 0-1}]  ≤18 · cols 2-6"
        "zx_spiral"    -> "items[{n,v 0-1}]  4-24 · turns 1-4"
        "zx_marquee"   -> "items[{t}]  2-8 · speed 1-5 · 可 paused"
        "zx_odometer"  -> "v + dur? 0-5000（0=直接终值）· dec? 0-3"
        "zx_radial"    -> "items[{k,n,d?}]  2-8 · inner 0.15-0.6"
        "zx_isometric" -> "blocks[{n,x,y,h>0}]  ≤14 · 位置不可重复"
        "zx_spectrum"  -> "bands[0-1]  4-32 · 不可全 0 · 可 frozen"
        "zx_bezier"    -> "p1x/p2x ∈ 0-1 · p1y/p2y ∈ -0.5~1.5 · presets? ≤6"
        else -> "未注册组件：$component"
    }
}
