package com.zorv.plugin.zxwidget
import kotlin.math.abs

import org.json.JSONObject

/**
 * 第二十二批组件（追溯 / 关系强度 / 定位）的校验 + 示例 + 规格。
 *
 * 这批查的重点是**「能不能定位」**。
 *
 * 前二十一批大多查"画不出来"或"画出来但形状/结论是错的"。
 * 这批要拦的是**"信息摆出来了，但追溯不到具体时刻和位置"**：
 *
 *   · 命令历史缺 `t`（时间戳）→ 只知道"跑过"，不知道什么时候
 *   · 命令历史缺 `rc`（退出码）→ 成功和失败看起来一样
 *   · 调用栈缺 `at`（行号）→ 只知道"到了这层"，不知道卡在第几行
 *   · 可用性条 slots 与 at 长度不符 → 点出来的时刻是错的
 *   · 回放 marks 的 `f` 越界 → 标记画到条外，等于没标
 *   · 流水线各段 rate 全为 0 → 算不出瓶颈
 *   · 相关性矩阵 m 非方阵 → 对应关系错位
 *   · 相关性矩阵对角线非 1 → 数据本身有问题
 *   · 多对象雷达 series 的 v 长度 ≠ 轴数 → 某个维度会静默缺失
 *   · 区域着色 cells 全同值 → 整张图一个颜色
 *   · 目录缺 `cur` → 没有当前项，那只是列表不是导航
 *   · 术语表缺 `d`（定义）→ 点了展开是空的
 *   · 问卷 scale 的 max < 2 → 只有一个刻度，等于没有选择
 *   · 问卷 opts 为空 → 没得选
 *   · 打点卡 d/h 越界 → 点被丢掉，用户看到的总数对不上
 *
 * 共同点：这些都是**不会崩**的。
 * 校验要拦的不是错误，是**"看起来能用、实际追溯不到"**——
 * 追溯类组件的价值全在"定位到具体时刻/位置"，
 * 定位不到，它就退化成了一份普通列表，
 * 而用户会以为它能回答"什么时候发生的"。
 */
object ZxDsl22 {

    val ALL = listOf(
        "zx_cmdhist", "zx_callstack", "zx_uptime", "zx_playback",
        "zx_pipe", "zx_corr", "zx_radar2", "zx_choro",
        "zx_toc", "zx_glossary", "zx_survey", "zx_punch"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_cmdhist"  -> cmdhist(root)
        "zx_callstack" -> callstack(root)
        "zx_uptime"   -> uptime(root)
        "zx_playback" -> playback(root)
        "zx_pipe"     -> pipe(root)
        "zx_corr"     -> corr(root)
        "zx_radar2"   -> radar2(root)
        "zx_choro"    -> choro(root)
        "zx_toc"      -> toc(root)
        "zx_glossary" -> glossary(root)
        "zx_survey"   -> survey(root)
        "zx_punch"    -> punch(root)
        else -> false to "未注册组件：$component"
    }

    private fun cmdhist(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_cmdhist 缺少 items"
        if (items.length() == 0) return false to "zx_cmdhist 的 items 为空"
        if (items.length() > 12) return false to "命令历史最多 12 条，实际 ${items.length()} 条"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"c\":命令,\"t\":时间,\"d\":耗时,\"rc\":退出码,\"fav\":}"
            if (it.optString("c").isBlank())
                return false to "items[$i] 缺少 c（命令本身）"
            // 时间戳：没有它就只知道"跑过"，不知道什么时候
            if (it.optString("t").isBlank())
                return false to "items[$i] 缺少 t（时间戳）——只有「跑过」没有「什么时候」的追溯没用"
            // 退出码：没有它就分不清成功和失败
            if (!it.has("rc"))
                return false to "items[$i] 缺少 rc（退出码）——成功和失败会看起来一样"
        }
        return true to ""
    }

    private fun callstack(o: JSONObject): Pair<Boolean, String> {
        val frames = o.optJSONArray("frames")
            ?: return false to "zx_callstack 缺少 frames"
        if (frames.length() == 0) return false to "zx_callstack 的 frames 为空"
        if (frames.length() > 10) return false to "调用栈最多 10 层，实际 ${frames.length()} 层"
        for (i in 0 until frames.length()) {
            val f = frames.optJSONObject(i)
                ?: return false to "frames[$i] 不是对象，应为 {\"fn\":函数,\"at\":\"文件:行号\",\"ext\":}"
            if (f.optString("fn").isBlank())
                return false to "frames[$i] 缺少 fn（函数名）"
            // 行号：没有它就只知道"到了这层"，不知道卡在第几行
            if (f.optString("at").isBlank())
                return false to "frames[$i] 缺少 at（文件:行号）——" +
                                "只知道「到了这层」，不知道卡在第几行"
        }
        // 有 err 却不给栈帧：说了出错但没说在哪
        if (o.optString("err").isNotBlank() && frames.length() == 0)
            return false to "给了 err 但 frames 为空——说了出错却没说在哪"
        return true to ""
    }

    private fun uptime(o: JSONObject): Pair<Boolean, String> {
        val slots = o.optJSONArray("slots")
            ?: return false to "zx_uptime 缺少 slots"
        if (slots.length() < 2) return false to "可用性条至少 2 格，实际 ${slots.length()} 格"
        if (slots.length() > 48) return false to "可用性条最多 48 格，实际 ${slots.length()} 格"
        val at = o.optJSONArray("at")
        // 长度不符：点出来的时刻是错的
        if (at != null && at.length() != slots.length())
            return false to "at 有 ${at.length()} 个，但 slots 有 ${slots.length()} 个——" +
                            "点出来的时刻会是错的"
        for (k in 0 until slots.length()) {
            val st = slots.optInt(k, -1)
            if (st < 0 || st > 3)
                return false to "slots[$k]=$st 超出 0-3（0正常/1降级/2中断/3无数据）" +
                                "——越界会被当成正常，中断段就看不出来了"
        }
        return true to ""
    }

    private fun playback(o: JSONObject): Pair<Boolean, String> {
        val total = o.optInt("frames", -1)
        if (total < 2) return false to "zx_playback 的 frames=$total 至少为 2——1 帧没有可回放的"
        if (total > 600) return false to "回放最多 600 帧，实际 $total 帧"
        val cur = o.optInt("cur", 0)
        if (cur < 0 || cur >= total)
            return false to "zx_playback 的 cur=$cur 越界（共 $total 帧）"
        val speed = o.optDouble("speed", 1.0)
        if (speed <= 0) return false to "zx_playback 的 speed=$speed 必须为正"
        if (speed > 8) return false to "zx_playback 的 speed=$speed 过快（上限 8）"
        val marks = o.optJSONArray("marks")
        for (k in 0 until (marks?.length() ?: 0)) {
            val m = marks!!.optJSONObject(k)
                ?: return false to "marks[$k] 不是对象，应为 {\"f\":帧号,\"n\":说明,\"err\":}"
            val f = m.optInt("f", -1)
            if (f < 0) return false to "marks[$k] 缺少 f（帧号）"
            // 越界：标记画到条外，等于没标
            if (f >= total)
                return false to "marks[$k].f=$f 超出帧数 $total——标记会画到条外，等于没标"
            if (m.optString("n").isBlank())
                return false to "marks[$k] 缺少 n（说明）——不知道这一帧发生了什么"
        }
        return true to ""
    }

    private fun pipe(o: JSONObject): Pair<Boolean, String> {
        val stages = o.optJSONArray("stages")
            ?: return false to "zx_pipe 缺少 stages"
        if (stages.length() < 2)
            return false to "流水线至少 2 段，实际 ${stages.length()} 段——1 段看不出流转"
        if (stages.length() > 6) return false to "流水线最多 6 段，实际 ${stages.length()} 段"
        var anyPos = false
        for (i in 0 until stages.length()) {
            val st = stages.optJSONObject(i)
                ?: return false to "stages[$i] 不是对象，应为 {\"n\":名,\"rate\":吞吐,\"u\":单位}"
            if (st.optString("n").isBlank()) return false to "stages[$i] 缺少 n"
            val r = st.optDouble("rate", Double.NaN)
            if (r.isNaN()) return false to "stages[$i] 缺少 rate（吞吐）"
            if (r < 0) return false to "stages[$i].rate=$r 不能为负"
            if (r > 0) anyPos = true
        }
        // 全为 0：算不出瓶颈
        if (!anyPos)
            return false to "所有段的吞吐都是 0——算不出瓶颈"
        return true to ""
    }

    private fun corr(o: JSONObject): Pair<Boolean, String> {
        val names = o.optJSONArray("names")
            ?: return false to "zx_corr 缺少 names"
        val m = names.length()
        if (m < 2) return false to "相关性矩阵至少 2 个变量，实际 $m 个"
        if (m > 8) return false to "相关性矩阵最多 8 个变量，实际 $m 个"
        for (i in 0 until m)
            if (names.optString(i).isBlank())
                return false to "names[$i] 是空的——相关性不标变量名就不知道在比什么"
        val mat = o.optJSONArray("m")
            ?: return false to "zx_corr 缺少 m（矩阵）"
        // 非方阵：对应关系错位
        if (mat.length() != m)
            return false to "m 有 ${mat.length()} 行，但 names 有 $m 个——对应关系会错位"
        for (r in 0 until m) {
            val row = mat.optJSONArray(r)
                ?: return false to "m[$r] 不是数组"
            if (row.length() != m)
                return false to "m[$r] 有 ${row.length()} 列，应为 $m 列——不是方阵"
            for (c in 0 until m) {
                val v = row.optDouble(c, Double.NaN)
                if (v.isNaN()) return false to "m[$r][$c] 不是数值"
                if (v < -1.0001 || v > 1.0001)
                    return false to "m[$r][$c]=$v 超出 -1~1——相关系数不可能大于 1"
                // 对角线必须约等于 1，否则数据本身有问题
                if (r == c && abs(v - 1.0) > 0.01)
                    return false to "m[$r][$c]=$v，对角线应约为 1（自己和自己必然完全相关）" +
                                    "——不是 1 说明数据本身有问题"
            }
            // 对称性：不检查会导致上三角和下三角不一致
            for (c in 0 until r) {
                val a = mat.optJSONArray(r)?.optDouble(c, 0.0) ?: 0.0
                val b = mat.optJSONArray(c)?.optDouble(r, 0.0) ?: 0.0
                if (abs(a - b) > 0.01)
                    return false to "矩阵不对称：m[$r][$c]=$a 但 m[$c][$r]=$b——相关性必然是对称的"
            }
        }
        return true to ""
    }

    private fun radar2(o: JSONObject): Pair<Boolean, String> {
        val axes = o.optJSONArray("axes")
            ?: return false to "zx_radar2 缺少 axes"
        val na = axes.length()
        if (na < 3) return false to "雷达至少 3 个轴，实际 $na 个——2 个轴画不出面"
        if (na > 8) return false to "雷达最多 8 个轴，实际 $na 个"
        for (i in 0 until na)
            if (axes.optString(i).isBlank())
                return false to "axes[$i] 是空的——雷达不标轴名就不知道每个方向是什么"
        val series = o.optJSONArray("series")
            ?: return false to "zx_radar2 缺少 series"
        val ns = series.length()
        if (ns == 0) return false to "zx_radar2 的 series 为空"
        if (ns > 4) return false to "雷达最多叠 4 条，实际 $ns 条——再多必然看不清"
        val max = o.optDouble("max", Double.NaN)
        if (max.isNaN()) return false to "zx_radar2 缺少 max（量程）"
        if (max <= 0) return false to "zx_radar2 的 max=$max 必须为正"
        for (s in 0 until ns) {
            val se = series.optJSONObject(s)
                ?: return false to "series[$s] 不是对象，应为 {\"n\":名,\"v\":[每轴的值]}"
            if (se.optString("n").isBlank()) return false to "series[$s] 缺少 n"
            val arr = se.optJSONArray("v")
                ?: return false to "series[$s] 缺少 v"
            // 长度不等于轴数：某个维度会静默缺失
            if (arr.length() != na)
                return false to "series[$s].v 有 ${arr.length()} 个值，但 axes 有 $na 个轴" +
                                "——某个维度会静默缺失"
            for (a in 0 until na) {
                val v = arr.optDouble(a, Double.NaN)
                if (v.isNaN()) return false to "series[$s].v[$a] 不是数值"
                if (v < 0) return false to "series[$s].v[$a]=$v 不能为负"
                if (v > max + 1e-9)
                    return false to "series[$s].v[$a]=$v 超出 max=$max——会画到网格外"
            }
        }
        return true to ""
    }

    private fun choro(o: JSONObject): Pair<Boolean, String> {
        val cells = o.optJSONArray("cells")
            ?: return false to "zx_choro 缺少 cells"
        val n = cells.length()
        if (n == 0) return false to "zx_choro 的 cells 为空"
        if (n > 24) return false to "区域着色最多 24 个区域，实际 $n 个"
        val cols = o.optInt("cols", 6)
        if (cols < 2 || cols > 8) return false to "zx_choro 的 cols=$cols 超出 2-8"
        var mn: Double? = null; var mx: Double? = null
        for (i in 0 until n) {
            val c = cells.optJSONObject(i)
                ?: return false to "cells[$i] 不是对象，应为 {\"n\":区域名,\"v\":数值}"
            if (c.optString("n").isBlank()) return false to "cells[$i] 缺少 n（区域名）"
            val v = c.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "cells[$i] 缺少 v"
            mn = if (mn == null) v else minOf(mn!!, v)
            mx = if (mx == null) v else maxOf(mx!!, v)
        }
        // 全同值：整张图一个颜色，看不出差异
        if (mn != null && mx != null && abs(mx!! - mn!!) < 1e-9)
            return false to "所有区域的 v 都是 $mn——整张图一个颜色，看不出分布"
        return true to ""
    }

    private fun toc(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_toc 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_toc 的 items 为空"
        if (n > 14) return false to "目录最多 14 节，实际 $n 节"
        for (i in 0 until n) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":标题,\"lv\":层级,\"kids\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（标题）"
            val lv = it.optInt("lv", 1)
            if (lv < 1 || lv > 3)
                return false to "items[$i].lv=$lv 超出 1-3（缩进会失控）"
        }
        // 缺 cur：没有当前项，那只是列表不是导航
        if (!o.has("cur"))
            return false to "zx_toc 缺少 cur（当前节）——没有当前项标记就只是个列表，不是导航"
        val cur = o.optInt("cur", 0)
        if (cur < 0 || cur >= n)
            return false to "zx_toc 的 cur=$cur 越界（共 $n 节）"
        return true to ""
    }

    private fun glossary(o: JSONObject): Pair<Boolean, String> {
        val terms = o.optJSONArray("terms")
            ?: return false to "zx_glossary 缺少 terms"
        val n = terms.length()
        if (n == 0) return false to "zx_glossary 的 terms 为空"
        if (n > 12) return false to "术语表最多 12 条，实际 $n 条"
        for (i in 0 until n) {
            val t = terms.optJSONObject(i)
                ?: return false to "terms[$i] 不是对象，应为 {\"t\":术语,\"d\":定义,\"abbr\":,\"see\":}"
            if (t.optString("t").isBlank()) return false to "terms[$i] 缺少 t（术语名）"
            // 定义：点了展开是空的，术语表就失去意义
            if (t.optString("d").isBlank())
                return false to "terms[$i] 缺少 d（定义）——点开是空的，术语表就失去意义"
        }
        return true to ""
    }

    private fun survey(o: JSONObject): Pair<Boolean, String> {
        val qs = o.optJSONArray("qs")
            ?: return false to "zx_survey 缺少 qs"
        val n = qs.length()
        if (n == 0) return false to "zx_survey 的 qs 为空"
        if (n > 6) return false to "问卷最多 6 题，实际 $n 题——一屏一题，再多用户会失去耐心"
        val kinds = setOf("single", "multi", "scale")
        for (i in 0 until n) {
            val q = qs.optJSONObject(i)
                ?: return false to "qs[$i] 不是对象，应为 {\"id\":,\"q\":题干,\"k\":类型,\"opts\":,\"max\":,\"req\":}"
            if (q.optString("q").isBlank()) return false to "qs[$i] 缺少 q（题干）"
            val k = q.optString("k", "single")
            if (k !in kinds)
                return false to "qs[$i].k=\"$k\" 不支持（可选：${kinds.joinToString("/")}）"
            if (k == "scale") {
                val mx = q.optInt("max", -1)
                if (mx < 2)
                    return false to "qs[$i].max=$mx 至少为 2——只有一个刻度等于没有选择"
                if (mx > 10) return false to "qs[$i].max=$mx 过大（上限 10）"
            } else {
                val opts = q.optJSONArray("opts")
                // 没得选：这题无法作答
                if (opts == null || opts.length() == 0)
                    return false to "qs[$i] 是 $k 但 opts 为空——没得选，这题无法作答"
                if (opts.length() > 6) return false to "qs[$i] 最多 6 个选项，实际 ${opts.length()} 个"
                for (c in 0 until opts.length())
                    if (opts.optString(c).isBlank())
                        return false to "qs[$i].opts[$c] 是空的"
            }
        }
        // id 重复：答案会互相覆盖
        val ids = (0 until n).map { qs.optJSONObject(it)?.optString("id", "$it") ?: "$it" }
        if (ids.distinct().size != n)
            return false to "有重复的 id（${ids.joinToString("/")}）——答案会互相覆盖"
        return true to ""
    }

    private fun punch(o: JSONObject): Pair<Boolean, String> {
        val days = o.optInt("days", 7)
        if (days < 2 || days > 7) return false to "zx_punch 的 days=$days 超出 2-7"
        val hours = o.optInt("hours", 24)
        if (hours < 2 || hours > 24) return false to "zx_punch 的 hours=$hours 超出 2-24"
        val pts = o.optJSONArray("pts")
            ?: return false to "zx_punch 缺少 pts"
        val n = pts.length()
        if (n == 0) return false to "zx_punch 的 pts 为空"
        if (n > 60) return false to "打点卡最多 60 个点，实际 $n 个"
        for (k in 0 until n) {
            val p = pts.optJSONObject(k)
                ?: return false to "pts[$k] 不是对象，应为 {\"d\":星期0-6,\"h\":小时}"
            val d = p.optInt("d", -1)
            val h = p.optInt("h", -1)
            if (d < 0) return false to "pts[$k] 缺少 d（星期，0=周一）"
            if (h < 0) return false to "pts[$k] 缺少 h（小时）"
            // 越界：点被丢掉，用户看到的总数对不上
            if (d >= days)
                return false to "pts[$k].d=$d 超出天数 $days——这个点会被丢掉，总数对不上"
            if (h >= hours)
                return false to "pts[$k].h=$h 超出小时数 $hours——这个点会被丢掉，总数对不上"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_cmdhist" -> """
            {"type":"zx_cmdhist","n":"命令历史","items":[
              {"c":"gradle build","t":"14:02","d":"1m20s","rc":0},
              {"c":"adb install app.apk","t":"14:05","d":"8s","rc":1}]}""".trimIndent()
        "zx_callstack" -> """
            {"type":"zx_callstack","n":"崩溃栈","err":"NullPointerException",
             "frames":[{"fn":"loadUser","at":"UserRepo.kt:142"},
                       {"fn":"onResume","at":"Main.kt:88","ext":true}]}""".trimIndent()
        "zx_uptime" -> """
            {"type":"zx_uptime","n":"近 24 小时","total":99.2,
             "at":["00","01"],"slots":[0,2]}""".trimIndent()
        "zx_playback" -> """
            {"type":"zx_playback","n":"操作回放","frames":60,"cur":12,"speed":1,
             "marks":[{"f":10,"n":"点击提交"}",{"f":30,"n":"报错","err":true}]}""".trimIndent()
        "zx_pipe" -> """
            {"type":"zx_pipe","n":"构建流水线","queue":3,"stages":[
              {"n":"拉取","rate":120,"u":"MB/s"},
              {"n":"编译","rate":18,"u":"文件/s"}]}""".trimIndent()
        "zx_corr" -> """
            {"type":"zx_corr","n":"指标相关性","names":["价格","销量","评分"],
             "m":[[1,-0.62,0.31],[-0.62,1,-0.08],[0.31,-0.08,1]]}""".trimIndent()
        "zx_radar2" -> """
            {"type":"zx_radar2","n":"方案对比","max":100,
             "axes":["性能","成本","易用","稳定"],
             "series":[{"n":"A","v":[90,60,75,80]},{"n":"B","v":[70,85,90,65]}]}""".trimIndent()
        "zx_choro" -> """
            {"type":"zx_choro","n":"区域销量","cols":4,"cells":[
              {"n":"华东","v":320},{"n":"华南","v":180},
              {"n":"华北","v":240},{"n":"西部","v":90}]}""".trimIndent()
        "zx_toc" -> """
            {"type":"zx_toc","n":"文档目录","cur":1,"items":[
              {"n":"一、概述","lv":1},{"n":"1.1 背景","lv":2,"kids":true},
              {"n":"二、方案","lv":1}]}""".trimIndent()
        "zx_glossary" -> """
            {"type":"zx_glossary","n":"术语表","terms":[
              {"t":"QPS","d":"每秒查询数","abbr":"Queries/sec"},
              {"t":"P99","d":"99% 请求的耗时上限","see":"延迟"}"]}""".trimIndent()
        "zx_survey" -> """
            {"type":"zx_survey","n":"满意度","qs":[
              {"id":"q1","q":"整体满意度？","k":"scale","max":5,"req":true},
              {"id":"q2","q":"常用功能","k":"multi","opts":["搜索","对比","导出"]}]}""".trimIndent()
        "zx_punch" -> """
            {"type":"zx_punch","n":"活跃时段","days":7,"hours":24,
             "pts":[{"d":0,"h":9},{"d":0,"h":9},{"d":2,"h":14}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_cmdhist"  -> "items[{c,t,rc,d?,fav?}]  ≤12 · 缺 t/rc 就追溯不到何时与成败"
        "zx_callstack" -> "frames[{fn,at,ext?}]  ≤10 · at 是文件:行号，缺了不知道卡在第几行"
        "zx_uptime"   -> "slots[]  2-48 · 状态 0-3，at 长度须等于 slots"
        "zx_playback" -> "frames 2-600 + cur + marks[{f<n,n}] · 帧号越界等于没标"
        "zx_pipe"     -> "stages[{n,rate>0,u?}]  2-6 · 全为 0 算不出瓶颈"
        "zx_corr"     -> "names[] + m[][] 必须方阵对称，对角线≈1，值 -1~1"
        "zx_radar2"   -> "axes 3-8 + series[{n,v[=轴数]}]  ≤4 · 长度不符会静默缺维度"
        "zx_choro"    -> "cells[{n,v}]  ≤24 · 全同值则整图一色"
        "zx_toc"      -> "items[{n,lv1-3,kids?}]  ≤14 · 缺 cur 只是列表不是导航"
        "zx_glossary" -> "terms[{t,d,abbr?,see?}]  ≤12 · 缺 d 点开是空的"
        "zx_survey"   -> "qs[{id,q,k,opts|max,req?}]  ≤6 · id 不可重复，scale 的 max≥2"
        "zx_punch"    -> "pts[{d<days,h<hours}]  ≤60 · 越界会被丢掉，总数对不上"
        else -> "未注册组件：$component"
    }
}
