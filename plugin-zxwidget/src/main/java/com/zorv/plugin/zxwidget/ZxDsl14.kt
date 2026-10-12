package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 第十四批组件（分布 / 富媒体 / 产出）的校验 + 示例 + 规格。
 *
 * 这批查的是**分布的前提成立不成立**：
 *
 *   · 箱线图 q1 > q3，箱体高度为负
 *   · 箱线图 lo > q1 或 hi < q3，须线画反
 *   · 控制图 lcl >= ucl，控制带翻转，
 *     所有点都会被判成超界
 *   · 帕累托 items 未按降序，累计线会回头
 *   · 直方图 bins 区间重叠，同一个值被算两次
 *
 * 分布类的共同点是**它们都在描述数据的形状**。
 * 形状前提不成立，画出来的图会"看起来正常但实际是错的"——
 * 用户会基于错误形状做判断，这比画不出来危险得多。
 */
object ZxDsl14 {

    val ALL = listOf(
        "zx_histogram", "zx_boxplot", "zx_pareto", "zx_control",
        "zx_sunburst", "zx_video", "zx_docview", "zx_model3d",
        "zx_schedule", "zx_nps", "zx_export", "zx_bargauge"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_histogram" -> histogram(root)
        "zx_boxplot"   -> boxplot(root)
        "zx_pareto"    -> pareto(root)
        "zx_control"   -> control(root)
        "zx_sunburst"  -> sunburst(root)
        "zx_video"     -> video(root)
        "zx_docview"   -> docview(root)
        "zx_model3d"   -> model3d(root)
        "zx_schedule"  -> schedule(root)
        "zx_nps"       -> nps(root)
        "zx_export"    -> export(root)
        "zx_bargauge"  -> bargauge(root)
        else -> false to "未注册组件：$component"
    }

    private fun histogram(o: JSONObject): Pair<Boolean, String> {
        val bins = o.optJSONArray("bins")
            ?: return false to "zx_histogram 缺少 bins"
        if (bins.length() < 3) return false to "直方图至少 3 个区间，实际 ${bins.length()} 个"
        if (bins.length() > 14) return false to "直方图最多 14 个区间，实际 ${bins.length()} 个"
        var prevTo = Double.NEGATIVE_INFINITY
        for (i in 0 until bins.length()) {
            val b = bins.optJSONObject(i)
                ?: return false to "bins[$i] 不是对象，应为 {\"n\":区间名,\"v\":频数,\"to\":区间上界}"
            if (b.optString("n").isBlank()) return false to "bins[$i] 缺少 n（区间名）"
            val v = b.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "bins[$i] 缺少 v（频数）"
            if (v < 0) return false to "bins[$i].v=$v 为负，频数不能为负"
            val to = b.optDouble("to", Double.NaN)
            if (!to.isNaN()) {
                // 区间重叠：同一个值会被算进两个桶
                if (to <= prevTo)
                    return false to "bins[$i].to=$to 不大于上一个上界 $prevTo——区间重叠，同一个值会被算两次"
                prevTo = to
            }
        }
        if ((0 until bins.length()).all { bins.optJSONObject(it)?.optDouble("v", 0.0) == 0.0 })
            return false to "所有 bins 的 v 都是 0——直方图全空"
        return true to ""
    }

    private fun boxplot(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_boxplot 缺少 items"
        if (items.length() == 0) return false to "zx_boxplot 的 items 为空"
        if (items.length() > 6) return false to "箱线图最多 6 组，实际 ${items.length()} 组"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":,\"lo\":,\"q1\":,\"q2\":,\"q3\":,\"hi\":,\"out\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（组名）"
            val l = double(it, "lo") ?: return false to "items[$i] 缺少 lo（下须）"
            val q1 = double(it, "q1") ?: return false to "items[$i] 缺少 q1"
            val q2 = double(it, "q2") ?: return false to "items[$i] 缺少 q2（中位数）"
            val q3 = double(it, "q3") ?: return false to "items[$i] 缺少 q3"
            val h = double(it, "hi") ?: return false to "items[$i] 缺少 hi（上须）"
            // 形状前提：任何一条不成立，画出来的图会「看起来正常但实际是错的」
            if (q1 > q3) return false to "items[$i] 的 q1=$q1 大于 q3=$q3——箱体高度为负"
            if (q2 < q1 || q2 > q3) return false to "items[$i] 的中位数 $q2 不在 [$q1,$q3] 内"
            if (l > q1) return false to "items[$i] 的 lo=$l 大于 q1=$q1，下须画反了"
            if (h < q3) return false to "items[$i] 的 hi=$h 小于 q3=$q3，上须画反了"
            if (l > h) return false to "items[$i] 的 lo=$l 大于 hi=$h"
        }
        return true to ""
    }

    private fun double(o: JSONObject, k: String): Double? =
        if (o.has(k)) o.optDouble(k, Double.NaN).takeIf { !it.isNaN() } else null

    private fun pareto(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_pareto 缺少 items"
        if (items.length() < 3) return false to "帕累托图至少 3 项，实际 ${items.length()} 项"
        if (items.length() > 8) return false to "帕累托图最多 8 项，实际 ${items.length()} 项"
        var prev = Double.POSITIVE_INFINITY
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":数值}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v"
            if (v < 0) return false to "items[$i].v=$v 为负"
            // 未降序：累计线会回头，80% 分界也算错
            if (v > prev + 1e-9)
                return false to "items[$i].v=$v 大于上一项 $prev——帕累托必须按降序，否则累计线会回头、80% 分界也算错"
            prev = v
        }
        if (prev <= 0) return false to "所有 v 都是 0——帕累托图算不出占比"
        return true to ""
    }

    private fun control(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("pts")
            ?: return false to "zx_control 缺少 pts"
        if (pts.length() < 5) return false to "控制图至少 5 个点，实际 ${pts.length()} 个"
        if (pts.length() > 30) return false to "控制图最多 30 个点，实际 ${pts.length()} 个"
        for (i in 0 until pts.length()) {
            val v = pts.optDouble(i, Double.NaN)
            if (v.isNaN()) return false to "pts[$i] 不是数值"
        }
        if (o.has("ucl") && o.has("lcl")) {
            val u = o.optDouble("ucl"); val l = o.optDouble("lcl")
            // lcl >= ucl 会让控制带翻转，所有点都被判成超界
            if (l >= u)
                return false to "lcl=$l 不小于 ucl=$u——控制带翻转，所有点都会被判成超界"
            if (o.has("cl") && (o.optDouble("cl") < l || o.optDouble("cl") > u))
                return false to "cl=${o.optDouble("cl")} 不在 [$l,$u] 内，中心线跑到控制带外面了"
        }
        return true to ""
    }

    private fun sunburst(o: JSONObject): Pair<Boolean, String> {
        val rings = o.optJSONArray("rings")
            ?: return false to "zx_sunburst 缺少 rings"
        if (rings.length() == 0) return false to "zx_sunburst 的 rings 为空"
        if (rings.length() > 3) return false to "旭日图最多 3 层，实际 ${rings.length()} 层——再多层内圈会挤成一条线"
        var outer = 0.0
        for (r in 0 until rings.length()) {
            val ring = rings.optJSONObject(r)
                ?: return false to "rings[$r] 不是对象，应为 {\"items\":[{\"n\":,\"v\":}]}"
            val items = ring.optJSONArray("items")
                ?: return false to "rings[$r] 缺少 items"
            if (items.length() < 2)
                return false to "rings[$r] 至少 2 项，实际 ${items.length()} 项——1 项就是一个整圆，没有分层意义"
            if (items.length() > 8) return false to "rings[$r] 最多 8 项，实际 ${items.length()} 项"
            var sum = 0.0
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i)
                    ?: return false to "rings[$r].items[$i] 不是对象，应为 {\"n\":名,\"v\":数值}"
                if (it.optString("n").isBlank()) return false to "rings[$r].items[$i] 缺少 n"
                val v = it.optDouble("v", Double.NaN)
                if (v.isNaN()) return false to "rings[$r].items[$i] 缺少 v"
                if (v <= 0) return false to "rings[$r].items[$i].v=$v 必须为正，否则扇区角度为 0"
                sum += v
            }
            // 内层总量必须涵盖外层，否则外圈会出现无来源的扇区
            if (r == 0) outer = sum
            else if (sum > outer + 1e-6)
                return false to "rings[$r] 合计 $sum 大于内层 $outer——外圈出现了无来源的扇区"
        }
        return true to ""
    }

    private fun video(o: JSONObject): Pair<Boolean, String> {
        val dur = o.optInt("dur", 0)
        if (dur <= 0) return false to "zx_video 的 dur 必须为正（秒）"
        if (dur > 7200) return false to "zx_video 的 dur=$dur 秒超出上限 7200"
        val at = o.optDouble("at", 0.0)
        if (at.isNaN() || at < 0 || at > 1)
            return false to "zx_video 的 at=$at 超出 0-1（应为进度比例）"
        val rate = o.optDouble("rate", 1.0)
        if (rate <= 0 || rate > 4)
            return false to "zx_video 的 rate=$rate 超出 (0,4]"
        if (o.optString("n").isBlank())
            return false to "zx_video 缺少 n（标题）——播放器上会是一片空白"
        val marks = o.optJSONArray("marks")
        if (marks != null) for (i in 0 until minOf(marks.length(), 6)) {
            val m = marks.optJSONObject(i) ?: continue
            val t = m.optDouble("at", Double.NaN)
            if (t.isNaN() || t < 0 || t > 1)
                return false to "marks[$i].at=$t 超出 0-1，标记点会画到轨道外"
        }
        return true to ""
    }

    private fun docview(o: JSONObject): Pair<Boolean, String> {
        val total = o.optInt("total", 0)
        if (total <= 0) return false to "zx_docview 的 total 必须为正"
        if (total > 999) return false to "zx_docview 的 total=$total 超出上限 999"
        val at = o.optInt("at", 1)
        if (at !in 1..total)
            return false to "zx_docview 的 at=$at 超出 1-$total——会显示一个不存在的页码"
        val zoom = o.optDouble("zoom", 1.0)
        if (zoom < .5 || zoom > 2)
            return false to "zx_docview 的 zoom=$zoom 超出 0.5-2"
        return true to ""
    }

    private fun model3d(o: JSONObject): Pair<Boolean, String> {
        val yaw = o.optDouble("yaw", 0.0)
        val pitch = o.optDouble("pitch", 0.0)
        if (yaw.isNaN() || pitch.isNaN()) return false to "zx_model3d 的 yaw/pitch 不是数值"
        // 俯仰超出 ±90° 会让模型上下翻转，看起来像渲染坏了
        if (pitch < -1.5708 || pitch > 1.5708)
            return false to "zx_model3d 的 pitch=$pitch 超出 ±90°（±1.5708 弧度）——模型会上下翻转，看起来像渲染坏了"
        if (o.optString("n").isBlank() && !o.has("verts"))
            return false to "zx_model3d 缺 n 也缺 verts——没有任何可显示的内容"
        return true to ""
    }

    private fun schedule(o: JSONObject): Pair<Boolean, String> {
        val days = o.optJSONArray("days")
            ?: return false to "zx_schedule 缺少 days"
        val slots = o.optJSONArray("slots")
            ?: return false to "zx_schedule 缺少 slots"
        if (days.length() < 2) return false to "排班表至少 2 天，实际 ${days.length()} 天"
        if (days.length() > 7) return false to "排班表最多 7 天，实际 ${days.length()} 天"
        if (slots.length() < 2) return false to "排班表至少 2 个时段，实际 ${slots.length()} 个"
        if (slots.length() > 5) return false to "排班表最多 5 个时段，实际 ${slots.length()} 个"
        for (i in 0 until days.length())
            if (days.optString(i).isBlank()) return false to "days[$i] 是空字符串"
        for (i in 0 until slots.length())
            if (slots.optString(i).isBlank()) return false to "slots[$i] 是空字符串"
        val cells = o.optJSONArray("cells")
        if (cells != null) {
            if (cells.length() > days.length() * slots.length())
                return false to "cells 有 ${cells.length()} 个，但格子只有 ${days.length() * slots.length()} 个"
            for (k in 0 until cells.length()) {
                val c = cells.optJSONObject(k)
                    ?: return false to "cells[$k] 不是对象，应为 {\"d\":天下标,\"s\":时段下标,\"who\":}"
                val d = c.optInt("d", -1); val s = c.optInt("s", -1)
                if (d < 0 || s < 0) return false to "cells[$k] 缺少 d 或 s"
                if (d >= days.length() || s >= slots.length())
                    return false to "cells[$k] 的 d=$d s=$s 越界（共 ${days.length()} 天 ${slots.length()} 时段）"
            }
        }
        return true to ""
    }

    private fun nps(o: JSONObject): Pair<Boolean, String> {
        val d = o.optInt("detractors", -1)
        val p = o.optInt("passives", -1)
        val m = o.optInt("promoters", -1)
        if (d < 0 || p < 0 || m < 0)
            return false to "zx_nps 需要 detractors / passives / promoters，且都不能为负"
        val total = o.optInt("total", d + p + m)
        // 分段之和必须等于样本量，否则百分比分母错了
        if (total != d + p + m)
            return false to "total=$total 不等于三段之和 ${d + p + m}——百分比的分母错了，NPS 会算歪"
        if (total == 0) return false to "样本量为 0，NPS 除零"
        return true to ""
    }

    private fun export(o: JSONObject): Pair<Boolean, String> {
        val fmts = o.optJSONArray("fmts")
            ?: return false to "zx_export 缺少 fmts"
        if (fmts.length() == 0) return false to "zx_export 的 fmts 为空——没有格式可选"
        if (fmts.length() > 5) return false to "导出卡最多 5 个格式，实际 ${fmts.length()} 个"
        val seen = mutableSetOf<String>()
        for (i in 0 until fmts.length()) {
            val f = fmts.optJSONObject(i)
                ?: return false to "fmts[$i] 不是对象，应为 {\"k\":格式键,\"n\":显示名,\"e\":图标}"
            if (f.optString("k").isBlank())
                return false to "fmts[$i] 缺少 k（格式键）——导出时不知道要导出成什么"
            if (!seen.add(f.optString("k")))
                return false to "fmts[$i].k 重复"
            if (f.optString("n").isBlank()) return false to "fmts[$i] 缺少 n（显示名）"
        }
        return true to ""
    }

    private fun bargauge(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_bargauge 缺少 items"
        if (items.length() == 0) return false to "zx_bargauge 的 items 为空"
        if (items.length() > 6) return false to "条形仪表最多 6 条，实际 ${items.length()} 条"
        val maxV = o.optDouble("max", Double.NaN)
        if (!maxV.isNaN() && maxV <= 0)
            return false to "zx_bargauge 的 max=$maxV 必须为正"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":值,\"tgt\":,\"hex\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v"
            if (v < 0) return false to "items[$i].v=$v 为负，条长为负"
            if (!maxV.isNaN() && v > maxV)
                return false to "items[$i].v=$v 超出量程 $maxV——条会被截断，用户看到的是错的"
            val tgt = it.optDouble("tgt", Double.NaN)
            if (!tgt.isNaN() && !maxV.isNaN() && tgt > maxV)
                return false to "items[$i].tgt=$tgt 超出量程 $maxV——目标刻线画到条外面"
            val hex = it.optString("hex")
            if (hex.isNotBlank() && !hex.matches(Regex("#[0-9A-Fa-f]{6}")))
                return false to "items[$i].hex=\"$hex\" 不是合法的 #RRGGBB"
        }
        if (maxV.isNaN() && (0 until items.length())
                .all { items.optJSONObject(it)?.optDouble("v", 0.0) == 0.0 })
            return false to "没给 max 且所有 v 都是 0——量程算不出来，条长全为 0"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_histogram" -> """
            {"type":"zx_histogram","mean":4.2,"bins":[
              {"n":"0-2","v":12,"to":2},{"n":"2-4","v":31,"to":4},
              {"n":"4-6","v":45,"to":6},{"n":"6-8","v":18,"to":8}]}""".trimIndent()
        "zx_boxplot" -> """
            {"type":"zx_boxplot","items":[
              {"n":"A","lo":1.2,"q1":2.4,"q2":3.1,"q3":4.0,"hi":5.6,
               "out":[7.2,0.4]},
              {"n":"B","lo":2.0,"q1":3.0,"q2":3.8,"q3":4.6,"hi":6.1}]}""".trimIndent()
        "zx_pareto" -> """
            {"type":"zx_pareto","items":[
              {"n":"渲染失败","v":42},{"n":"校验不过","v":28},
              {"n":"超时","v":15},{"n":"其他","v":8}]}""".trimIndent()
        "zx_control" -> """
            {"type":"zx_control","cl":100,"ucl":112,"lcl":88,
             "pts":[98,101,103,99,114,97,102,100,86,101,104,99]}""".trimIndent()
        "zx_sunburst" -> """
            {"type":"zx_sunburst","n":"总计","rings":[
              {"items":[{"n":"渲染","v":60},{"n":"校验","v":40}]},
              {"items":[{"n":"Compose","v":35},{"n":"Canvas","v":25},
                        {"n":"DSL","v":25},{"n":"Ref","v":15}]}]}""".trimIndent()
        "zx_video" -> """
            {"type":"zx_video","n":"演示录屏","dur":180,"at":0.3,"rate":1,
             "marks":[{"at":0.2},{"at":0.65}]}""".trimIndent()
        "zx_docview" -> """
            {"type":"zx_docview","n":"方案.pdf","total":12,"at":3,"zoom":1}""".trimIndent()
        "zx_model3d" -> """
            {"type":"zx_model3d","n":"外壳","yaw":0,"pitch":0}""".trimIndent()
        "zx_schedule" -> """
            {"type":"zx_schedule","days":["一","二","三","四","五"],
             "slots":["早","中","晚"],
             "cells":[{"d":0,"s":0,"who":"张三"}",{"d":2,"s":1,"who":"李四"}"]}""".trimIndent()
        "zx_nps" -> """
            {"type":"zx_nps","n":"本季 NPS","detractors":18,
             "passives":42,"promoters":90,"total":150}""".trimIndent()
        "zx_export" -> """
            {"type":"zx_export","n":"导出这张表","size":"12.4 KB","target":"budget",
             "fmts":[{"k":"csv","n":"CSV","e":"▤"},
                     {"k":"xlsx","n":"Excel","e":"▦"},
                     {"k":"md","n":"Markdown","e":"≡"}]}""".trimIndent()
        "zx_bargauge" -> """
            {"type":"zx_bargauge","max":100,"items":[
              {"n":"完成度","v":72,"tgt":80,"hex":"#5046E4"},
              {"n":"覆盖率","v":45,"tgt":60,"hex":"#3FBF7F"}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_histogram" -> "bins[{n,v,to?}]  3-14 · mean? · to 须递增不重叠"
        "zx_boxplot"   -> "items[{n,lo,q1,q2,q3,hi,out?[]}]  ≤6 · q1≤q3，中位数须在箱内"
        "zx_pareto"    -> "items[{n,v}]  3-8 · 必须降序"
        "zx_control"   -> "pts[]  5-30 · cl/ucl/lcl? · lcl<ucl"
        "zx_sunburst"  -> "rings[{items[{n,v}]}]  ≤3 层 · 外层合计不可超内层"
        "zx_video"     -> "n + dur(秒) · at 0-1 · rate (0,4] · marks?"
        "zx_docview"   -> "n + total(1-999) · at 1..total · zoom .5-2"
        "zx_model3d"   -> "n + yaw · pitch ±90°"
        "zx_schedule"  -> "days 2-7 + slots 2-5 + cells?[{d,s,who}]"
        "zx_nps"       -> "detractors/passives/promoters · total 须等于三者之和"
        "zx_export"    -> "fmts[{k,n,e?}]  ≤5 · k 不可重复"
        "zx_bargauge"  -> "items[{n,v,tgt?,hex?}]  ≤6 · max? · v 不可超量程"
        else -> "未注册组件：$component"
    }
}
