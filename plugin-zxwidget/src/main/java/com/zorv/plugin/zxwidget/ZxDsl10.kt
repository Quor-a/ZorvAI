package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第十批组件（高信息密度 / 专用输入 / 装饰性呈现）的校验 + 示例 + 规格。
 *
 * 这批校验的重心是**密度上限**：
 * 密度型组件很容易被模型塞爆——给它 365 天的热力图，
 * 每个格子只剩 1px，那就只剩色块没有信息了。
 *
 * 所以每个密度型组件都定死上限，超了就拦，
 * 而不是"渲染出来但看不清"。看不清比渲染失败更糟，
 * 因为模型会以为成功了。
 *
 * 另一类是**量程与取值前提**：
 * 评分的 max 影响星星数量、刻度的 min/max 影响指针位置、
 * 标签的 max 影响能否继续添加——这些值越界不会崩，
 * 但会得到"看起来能用其实是错的"界面。
 */
object ZxDsl10 {

    val ALL = listOf(
        "zx_calheat", "zx_matrixheat", "zx_polar", "zx_voice",
        "zx_stars", "zx_react", "zx_scale", "zx_tags",
        "zx_flipnum", "zx_skeleton", "zx_badges"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_calheat"   -> calheat(root)
        "zx_matrixheat"-> matrixheat(root)
        "zx_polar"     -> polar(root)
        "zx_voice"     -> voice(root)
        "zx_stars"     -> stars(root)
        "zx_react"     -> react(root)
        "zx_scale"     -> scale(root)
        "zx_tags"      -> tags(root)
        "zx_flipnum"   -> flipnum(root)
        "zx_skeleton"  -> skeleton(root)
        "zx_badges"    -> badges(root)
        else -> false to "未注册组件：$component"
    }

    private fun calheat(o: JSONObject): Pair<Boolean, String> {
        val cells = o.optJSONArray("cells")
            ?: return false to "zx_calheat 缺少 cells"
        if (cells.length() == 0) return false to "zx_calheat 的 cells 为空"
        // 91 天 = 一季度 13 周；再多格子就只剩色块没有信息
        if (cells.length() > 91)
            return false to "日历热力最多 91 格（一季度），实际 ${cells.length()} 格——再多格子只剩 1px，只有色块没有信息"
        for (i in 0 until cells.length()) {
            val v = cells.optDouble(i, Double.NaN)
            if (v.isNaN()) return false to "cells[$i] 不是数值"
            if (v < 0) return false to "cells[$i]=$v 为负，热力值不能为负"
        }
        // 全 0 就没有可看的东西
        val allZero = (0 until cells.length()).all { cells.optDouble(it, 0.0) == 0.0 }
        if (allZero) return false to "所有 cells 都是 0——整张图全是底色，没有信息"
        val labels = o.optJSONArray("labels")
        if (labels != null && labels.length() > 13)
            return false to "labels 最多 13 个（对应周数），实际 ${labels.length()} 个"
        return true to ""
    }

    private fun matrixheat(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_matrixheat 缺少 rows"
        val cols = o.optJSONArray("cols")
            ?: return false to "zx_matrixheat 缺少 cols"
        if (cols.length() < 2) return false to "矩阵至少 2 列，实际 ${cols.length()} 列"
        if (cols.length() > 7) return false to "矩阵最多 7 列，实际 ${cols.length()} 列（每列 30dp）"
        if (rows.length() == 0) return false to "zx_matrixheat 的 rows 为空"
        if (rows.length() > 8) return false to "矩阵最多 8 行，实际 ${rows.length()} 行"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"n\":行名,\"v\":[...]}"
            if (r.optString("n").isBlank()) return false to "rows[$i] 缺少 n（行名）"
            val v = r.optJSONArray("v")
                ?: return false to "rows[$i] 缺少 v（该行数值数组）"
            if (v.length() != cols.length())
                return false to "rows[$i].v 有 ${v.length()} 个值，但 cols 有 ${cols.length()} 列——单元格会错位"
            for (c in 0 until v.length()) {
                val x = v.optDouble(c, Double.NaN)
                if (x.isNaN()) return false to "rows[$i].v[$c] 不是数值"
                if (x < 0) return false to "rows[$i].v[$c]=$x 为负，热力值不能为负"
            }
        }
        return true to ""
    }

    private fun polar(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_polar 缺少 items"
        if (items.length() < 3) return false to "极坐标至少 3 项，实际 ${items.length()} 项"
        if (items.length() > 12) return false to "极坐标最多 12 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":值,\"r\":半径}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（名称）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v（数值）"
            val r = it.optDouble("r", Double.NaN)
            if (r.isNaN()) return false to "items[$i] 缺少 r（半径，决定离圆心多远）"
            if (r <= 0) return false to "items[$i].r=$r 必须为正，否则点会落在圆心"
        }
        // 半径全相同就退化成饼图了
        val rs = (0 until items.length()).map { items.optJSONObject(it)?.optDouble("r", 0.0) ?: 0.0 }
        if (rs.maxOrNull()!! - rs.minOrNull()!! < 1e-9)
            return false to "所有 items 的 r 都一样——半径没有变化量，退化成饼图了，用 zx_chart 更合适"
        return true to ""
    }

    private fun voice(o: JSONObject): Pair<Boolean, String> {
        val maxSecs = o.optInt("maxSecs", 60)
        if (maxSecs !in 5..600)
            return false to "zx_voice 的 maxSecs=$maxSecs 超出 5-600"
        val v = o.optString("v")
        if (v.length > 500)
            return false to "zx_voice 的 v 超过 500 字（实际 ${v.length}），语音转写不该这么长"
        return true to ""
    }

    private fun stars(o: JSONObject): Pair<Boolean, String> {
        val max = o.optInt("max", 5)
        if (max !in 3..10)
            return false to "zx_stars 的 max=$max 超出 3-10（少于 3 没有区分度，多于 10 一排放不下）"
        val v = o.optInt("v", 0)
        if (v !in 0..max)
            return false to "zx_stars 的 v=$v 超出 0-$max"
        val labels = o.optJSONArray("labels")
        // labels 必须和 max 对齐，否则点第 4 颗星读到的标签是错的
        if (labels != null && labels.length() != max)
            return false to "zx_stars 的 labels 有 ${labels.length()} 个，但 max=$max——点第 N 颗星会读到错的标签"
        return true to ""
    }

    private fun react(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_react 缺少 items"
        if (items.length() < 2) return false to "表情反馈至少 2 项，实际 ${items.length()} 项"
        if (items.length() > 6) return false to "表情反馈最多 6 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"k\":键,\"e\":表情,\"n\":说明}"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（回传键）——点了这个表情模型也不知道是哪个"
            if (it.optString("e").isBlank())
                return false to "items[$i] 缺少 e（表情字符）——会显示成占位点"
        }
        return true to ""
    }

    private fun scale(o: JSONObject): Pair<Boolean, String> {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", Double.NaN)
        if (max.isNaN()) return false to "zx_scale 缺少 max（量程上限）"
        if (max <= min) return false to "zx_scale 的 max($max) 必须大于 min($min)，指针位置算不出来"
        val v = o.optDouble("v", Double.NaN)
        if (!v.isNaN() && (v < min || v > max))
            return false to "zx_scale 的 v=$v 超出 [$min,$max]，指针会停在量程外"
        val stops = o.optJSONArray("stops")
            ?: return false to "zx_scale 缺少 stops（色阶）——没有色阶就只是个滑条，用 zx_range 更合适"
        if (stops.length() < 2) return false to "stops 至少 2 个色标，实际 ${stops.length()} 个"
        if (stops.length() > 6) return false to "stops 最多 6 个色标，实际 ${stops.length()} 个"
        for (i in 0 until stops.length()) {
            val s = stops.optJSONObject(i)
                ?: return false to "stops[$i] 不是对象，应为 {\"n\":名,\"hex\":\"#RRGGBB\"}"
            if (s.optString("n").isBlank()) return false to "stops[$i] 缺少 n（色标名）"
            val hex = s.optString("hex")
            if (hex.isBlank()) return false to "stops[$i] 缺少 hex（颜色）"
            if (!hex.matches(Regex("#[0-9A-Fa-f]{6}")))
                return false to "stops[$i].hex=\"$hex\" 不是合法的 #RRGGBB"
        }
        return true to ""
    }

    private fun tags(o: JSONObject): Pair<Boolean, String> {
        val max = o.optInt("max", 8)
        if (max !in 1..12) return false to "zx_tags 的 max=$max 超出 1-12"
        val tags = o.optJSONArray("tags") ?: return true to ""
        if (tags.length() > max)
            return false to "已有 ${tags.length()} 个标签，超过 max=$max——一进来就是满的，加不了"
        val seen = mutableSetOf<String>()
        for (i in 0 until tags.length()) {
            val t = tags.optString(i)
            if (t.isBlank()) return false to "tags[$i] 是空字符串"
            if (!seen.add(t.trim()))
                return false to "tags[$i]=\"$t\" 重复——删除时不知道删的是哪个"
        }
        return true to ""
    }

    private fun flipnum(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_flipnum 缺少 items"
        if (items.length() == 0) return false to "zx_flipnum 的 items 为空"
        if (items.length() > 4) return false to "翻页数字最多 4 个，实际 ${items.length()} 个"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"v\":\"123\",\"n\":\"名称\",\"d\":\"+5\"}"
            val v = it.optString("v")
            if (v.isBlank()) return false to "items[$i] 缺少 v（显示的数字）"
            // 每位一个格子，超过 4 位就放不下
            if (v.length > 4)
                return false to "items[$i].v=\"$v\" 有 ${v.length} 位，但每位一个格子最多放 4 位"
            if (!v.matches(Regex("-?[0-9.]+")))
                return false to "items[$i].v=\"$v\" 不是数值——翻页数字只能显示数字"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（这个数字是什么）"
        }
        return true to ""
    }

    private fun skeleton(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optInt("rows", 3)
        if (rows !in 1..6) return false to "zx_skeleton 的 rows=$rows 超出 1-6"
        return true to ""
    }

    private fun badges(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_badges 缺少 items"
        if (items.length() == 0) return false to "zx_badges 的 items 为空"
        if (items.length() > 8) return false to "徽章最多 8 个，实际 ${items.length()} 个"
        var got = 0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"k\":键,\"e\":图标,\"n\":名,\"got\":bool,\"d\":描述}"
            if (it.optString("k").isBlank()) return false to "items[$i] 缺少 k（回传键）"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（徽章名）"
            if (it.optString("e").isBlank())
                return false to "items[$i] 缺少 e（图标字符）——会显示成占位块"
            if (it.optBoolean("got", false)) got++
        }
        if (got == 0)
            return false to "没有任何 got=true 的徽章——整组都是灰的，看不出进度"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_calheat" -> """
            {"type":"zx_calheat","labels":["W1","W2","W3"],
             "cells":[3,0,7,12,5,0,2,9,14,6,1,0,8]}""".trimIndent()
        "zx_matrixheat" -> """
            {"type":"zx_matrixheat","cols":["一","二","三"],
             "rows":[{"n":"登录","v":[120,80,45]},
                     {"n":"支付","v":[30,25,18]}]}""".trimIndent()
        "zx_polar" -> """
            {"type":"zx_polar","items":[
              {"n":"A","v":320,"r":0.8},{"n":"B","v":180,"r":0.4},
              {"n":"C","v":95,"r":0.25}]}""".trimIndent()
        "zx_voice" -> """
            {"type":"zx_voice","maxSecs":60,"v":""}""".trimIndent()
        "zx_stars" -> """
            {"type":"zx_stars","max":5,"v":0,
             "labels":["很差","较差","一般","好","非常好"]}""".trimIndent()
        "zx_react" -> """
            {"type":"zx_react","items":[
              {"k":"up","e":"👍","n":"有用"}",{"k":"down","e":"👎","n":"没用"}"]}""".trimIndent()
        "zx_scale" -> """
            {"type":"zx_scale","label":"延迟","min":0,"max":200,"v":45,"unit":"ms",
             "stops":[{"n":"快","hex":"#3FBF7F"},{"n":"慢","hex":"#E5544B"}]}""".trimIndent()
        "zx_tags" -> """
            {"type":"zx_tags","max":8,"tags":["Kotlin","Compose"],
             "sugg":["Jetpack","MNN","插件"]}""".trimIndent()
        "zx_flipnum" -> """
            {"type":"zx_flipnum","items":[
              {"v":"128","n":"会话","d":"+12"},{"v":"3.4","n":"评分"}"]}""".trimIndent()
        "zx_skeleton" -> """
            {"type":"zx_skeleton","rows":3,"hint":"正在渲染卡片…"}""".trimIndent()
        "zx_badges" -> """
            {"type":"zx_badges","items":[
              {"k":"b1","e":"★","n":"首次运行","got":true},
              {"k":"b2","e":"◆","n":"插件作者","got":false}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_calheat"   -> "cells[](≤91) + labels?  值非负 · 全 0 会被拦"
        "zx_matrixheat"-> "cols[] + rows[{n,v[]}]  v 长度须等于列数 · ≤8×7"
        "zx_polar"     -> "items[{n,v,r}]  3-12 项 · r>0 且必须有变化量"
        "zx_voice"     -> "maxSecs?(5-600) + v?  波形条数固定 28"
        "zx_stars"     -> "max(3-10) + v + labels?(须等于 max)"
        "zx_react"     -> "items[{k,e,n?}]  2-6 个 · k/e 必填"
        "zx_scale"     -> "min,max,v? + stops[{n,hex}]  2-6 色标 · 指针随值取色"
        "zx_tags"      -> "max(1-12) + tags?[] + sugg?[]  不可重复"
        "zx_flipnum"   -> "items[{v≤4位,n,d?}]  最多 4 个 · v 须是数值"
        "zx_skeleton"  -> "rows(1-6) + avatar? + hint?"
        "zx_badges"    -> "items[{k,e,n,got?,d?}]  ≤8 · 至少一个 got=true"
        else -> "未注册组件：$component"
    }
}
