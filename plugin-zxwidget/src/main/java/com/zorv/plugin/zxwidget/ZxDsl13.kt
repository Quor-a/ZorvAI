package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 第十三批组件（对照 / 诊断 / 协同）的校验 + 示例 + 规格。
 *
 * 这批查的是**能不能定位到问题**：
 *
 *   · diff 缺行号，改在哪一行说不清
 *   · 日志缺 lv，级别着色和过滤全部失效，
 *     整块退化成等宽文本
 *   · 依赖图 edges 指向不存在的节点，
 *     会画出一条连到空白的线
 *   · 评分卡权重全为 0，加权总分除零
 *   · 时间区间 a >= b，选区宽度为负
 *
 * 这批组件本身都是"出问题才用"的——
 * 如果它们自己也读不出信息，
 * 用户就处在"出了问题但看不到问题"的状态，
 * 那是最糟的一种界面。
 */
object ZxDsl13 {

    val ALL = listOf(
        "zx_diffview", "zx_baseline", "zx_logview", "zx_console",
        "zx_depgraph", "zx_datamap", "zx_conflict", "zx_activity",
        "zx_timerange", "zx_gridform", "zx_scorecard", "zx_waveedit"
    )

    private val LV = setOf("err", "warn", "info", "ok", "dbg")
    private val DIFF_T = setOf("add", "del", "mod", "same")

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_diffview" -> diffview(root)
        "zx_baseline" -> baseline(root)
        "zx_logview"  -> logview(root)
        "zx_console"  -> console(root)
        "zx_depgraph" -> depgraph(root)
        "zx_datamap"  -> datamap(root)
        "zx_conflict" -> conflict(root)
        "zx_activity" -> activity(root)
        "zx_timerange"-> timerange(root)
        "zx_gridform" -> gridform(root)
        "zx_scorecard"-> scorecard(root)
        "zx_waveedit" -> waveedit(root)
        else -> false to "未注册组件：$component"
    }

    private fun diffview(o: JSONObject): Pair<Boolean, String> {
        val lines = o.optJSONArray("lines")
            ?: return false to "zx_diffview 缺少 lines"
        if (lines.length() == 0) return false to "zx_diffview 的 lines 为空"
        if (lines.length() > 14) return false to "并排 diff 最多 14 行，实际 ${lines.length()} 行"
        var changed = 0
        for (i in 0 until lines.length()) {
            val l = lines.optJSONObject(i)
                ?: return false to "lines[$i] 不是对象，应为 {\"t\":\"add/del/mod/same\",\"a\":,\"b\":,\"no\":}"
            val t = l.optString("t", "same")
            if (t !in DIFF_T)
                return false to "lines[$i].t=\"$t\" 只能是 ${DIFF_T.joinToString("/")}"
            if (t != "same") changed++
            if (t == "add" && l.optString("b").isBlank())
                return false to "lines[$i] 是 add 但 b 为空——右侧会显示空白"
            if (t == "del" && l.optString("a").isBlank())
                return false to "lines[$i] 是 del 但 a 为空——左侧会显示空白"
            if (t == "mod" && (l.optString("a").isBlank() || l.optString("b").isBlank()))
                return false to "lines[$i] 是 mod 但 a 或 b 为空——并排对比缺一半"
        }
        if (changed == 0)
            return false to "没有任何改动行——整块都是 same，那就不是 diff"
        return true to ""
    }

    private fun baseline(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_baseline 缺少 items"
        if (items.length() == 0) return false to "zx_baseline 的 items 为空"
        if (items.length() > 6) return false to "基线对比最多 6 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":,\"base\":,\"cur\":,\"tgt\":,\"lower\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（指标名）"
            if (it.isNull("base") || !it.has("base"))
                return false to "items[$i] 缺少 base（基线值）——没有基线就不叫基线对比"
            if (it.isNull("cur") || !it.has("cur"))
                return false to "items[$i] 缺少 cur（当前值）"
            val tgt = if (it.has("tgt")) it.optDouble("tgt") else it.optDouble("base")
            if (tgt.isNaN()) return false to "items[$i] 的 tgt 不是数值"
        }
        return true to ""
    }

    private fun logview(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_logview 缺少 rows"
        if (rows.length() == 0) return false to "zx_logview 的 rows 为空"
        if (rows.length() > 30) return false to "日志最多 30 条，实际 ${rows.length()} 条"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"lv\":,\"v\":,\"t\":}"
            val lv = r.optString("lv")
            // 缺 lv 会让级别着色和过滤全部失效，整块退化成等宽文本
            if (lv.isBlank())
                return false to "rows[$i] 缺少 lv（级别）——级别着色和过滤会全部失效，整块退化成等宽文本"
            if (lv !in LV)
                return false to "rows[$i].lv=\"$lv\" 只能是 ${LV.joinToString("/")}"
            if (r.optString("v").isBlank())
                return false to "rows[$i] 缺少 v（日志内容）——会渲染出一条空行"
        }
        return true to ""
    }

    private fun console(o: JSONObject): Pair<Boolean, String> {
        val hist = o.optJSONArray("hist") ?: return true to ""
        if (hist.length() > 8) return false to "控制台历史最多 8 条，实际 ${hist.length()} 条"
        for (i in 0 until hist.length())
            if (hist.optString(i).isBlank())
                return false to "hist[$i] 是空字符串——会渲染出一个空提示符"
        return true to ""
    }

    private fun depgraph(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_depgraph 缺少 nodes"
        if (nodes.length() < 2) return false to "依赖图至少 2 个节点，实际 ${nodes.length()} 个"
        if (nodes.length() > 10) return false to "依赖图最多 10 个节点，实际 ${nodes.length()} 个"
        for (i in 0 until nodes.length()) {
            val nd = nodes.optJSONObject(i)
                ?: return false to "nodes[$i] 不是对象，应为 {\"k\":键,\"n\":名}"
            if (nd.optString("k").isBlank()) return false to "nodes[$i] 缺少 k（节点键）"
            if (nd.optString("n").isBlank()) return false to "nodes[$i] 缺少 n（节点名）"
        }
        val edges = o.optJSONArray("edges")
        if (edges != null) {
            if (edges.length() > 20) return false to "依赖边最多 20 条，实际 ${edges.length()} 条"
            for (e in 0 until edges.length()) {
                val ed = edges.optJSONObject(e)
                    ?: return false to "edges[$e] 不是对象，应为 {\"f\":起点下标,\"t\":终点下标}"
                val f = ed.optInt("f", -1); val t = ed.optInt("t", -1)
                if (f < 0 || t < 0) return false to "edges[$e] 缺少 f 或 t"
                // 指向不存在的节点会画出一条连到空白的线
                if (f >= nodes.length() || t >= nodes.length())
                    return false to "edges[$e] 指向 f=$f t=$t，但只有 ${nodes.length()} 个节点——会画出一条连到空白的线"
                if (f == t) return false to "edges[$e] 是自环（f=t=$f），这个节点永远排不进拓扑"
            }
        }
        return true to ""
    }

    private fun datamap(o: JSONObject): Pair<Boolean, String> {
        val regions = o.optJSONArray("regions")
            ?: return false to "zx_datamap 缺少 regions"
        if (regions.length() == 0) return false to "zx_datamap 的 regions 为空"
        if (regions.length() > 12) return false to "数据地图最多 12 个区域，实际 ${regions.length()} 个"
        for (i in 0 until regions.length()) {
            val rg = regions.optJSONObject(i)
                ?: return false to "regions[$i] 不是对象，应为 {\"n\":区域名,\"v\":数值}"
            if (rg.optString("n").isBlank()) return false to "regions[$i] 缺少 n（区域名）"
            val v = rg.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "regions[$i] 缺少 v（数值）——色阶算不出来"
            if (v < 0) return false to "regions[$i].v=$v 为负，色阶不能为负"
        }
        val allZero = (0 until regions.length()).all { regions.optJSONObject(it)?.optDouble("v", 0.0) == 0.0 }
        if (allZero) return false to "所有 region 的 v 都是 0——整张图一个色，看不出分布"
        return true to ""
    }

    private fun conflict(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_conflict 缺少 items"
        if (items.length() == 0) return false to "zx_conflict 的 items 为空——没有冲突就不该出这张卡"
        if (items.length() > 6) return false to "冲突卡最多 6 处，实际 ${items.length()} 处（再多要分页）"
        val sevs = setOf("SOFT", "DELETE_VS_EDIT", "SHAPE")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"path\":,\"label\":,\"local\":,\"remote\":,\"sev\":}"
            if (it.optString("path").isBlank())
                return false to "items[$i] 缺少 path——解决完不知道改的是哪个字段"
            if (it.optString("local").isBlank() && it.optString("remote").isBlank())
                return false to "items[$i] 的 local 和 remote 都为空——没有可比的内容"
            val sev = it.optString("sev", "SOFT")
            if (sev !in sevs)
                return false to "items[$i].sev=\"$sev\" 只能是 ${sevs.joinToString("/")}"
        }
        return true to ""
    }

    private fun activity(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_activity 缺少 items"
        if (items.length() == 0) return false to "zx_activity 的 items 为空"
        if (items.length() > 10) return false to "活动流最多 10 条，实际 ${items.length()} 条"
        val kinds = setOf("add", "del", "edit", "comment")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"k\":,\"who\":,\"v\":,\"t\":}"
            val k = it.optString("k", "edit")
            if (k !in kinds)
                return false to "items[$i].k=\"$k\" 只能是 ${kinds.joinToString("/")}"
            if (it.optString("who").isBlank())
                return false to "items[$i] 缺少 who——活动流的核心就是「谁动了什么」，没有 who 就没有意义"
            if (it.optString("v").isBlank())
                return false to "items[$i] 缺少 v（做了什么）"
        }
        return true to ""
    }

    private fun timerange(o: JSONObject): Pair<Boolean, String> {
        val total = o.optInt("total", 24)
        if (total !in 2..168) return false to "zx_timerange 的 total=$total 超出 2-168"
        val a = o.optDouble("a", 0.0)
        val b = o.optDouble("b", 1.0)
        if (a.isNaN() || b.isNaN()) return false to "zx_timerange 的 a/b 不是数值"
        // a >= b 会让选区宽度为负
        if (a >= b)
            return false to "zx_timerange 的 a=$a 必须小于 b=$b，否则选区宽度为负"
        if (a < 0 || b > 1)
            return false to "zx_timerange 的 a/b 必须在 0-1，实际 [$a,$b]"
        return true to ""
    }

    private fun gridform(o: JSONObject): Pair<Boolean, String> {
        val fields = o.optJSONArray("fields")
            ?: return false to "zx_gridform 缺少 fields"
        if (fields.length() == 0) return false to "zx_gridform 的 fields 为空"
        if (fields.length() > 8) return false to "栅格表单最多 8 个字段，实际 ${fields.length()} 个"
        val cols = o.optInt("cols", 2)
        if (cols !in 1..3) return false to "zx_gridform 的 cols=$cols 超出 1-3"
        val seen = mutableSetOf<String>()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i)
                ?: return false to "fields[$i] 不是对象，应为 {\"k\":键,\"n\":名,\"v\":,\"hint\":}"
            if (f.optString("k").isBlank())
                return false to "fields[$i] 缺少 k（提交键）——提交后不知道哪个值对应哪个字段"
            if (!seen.add(f.optString("k")))
                return false to "fields[$i].k 重复——提交时后一个会覆盖前一个"
            if (f.optString("n").isBlank()) return false to "fields[$i] 缺少 n（显示名）"
        }
        return true to ""
    }

    private fun scorecard(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_scorecard 缺少 items"
        if (items.length() == 0) return false to "zx_scorecard 的 items 为空"
        if (items.length() > 6) return false to "评分卡最多 6 项，实际 ${items.length()} 项"
        var wsum = 0.0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"k\":,\"n\":,\"v\":,\"max\":,\"w\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（维度名）"
            val max = it.optInt("max", 5)
            if (max !in 2..10) return false to "items[$i].max=$max 超出 2-10"
            val v = it.optInt("v", 0)
            if (v !in 0..max) return false to "items[$i].v=$v 超出 0-$max"
            val w = it.optDouble("w", 1.0)
            if (w < 0) return false to "items[$i].w=$w 不能为负"
            wsum += w
        }
        // 权重全 0 会让加权总分除零
        if (wsum <= 0)
            return false to "所有权重加起来是 0——加权总分会除零"
        return true to ""
    }

    private fun waveedit(o: JSONObject): Pair<Boolean, String> {
        val peaks = o.optJSONArray("peaks") ?: return true to ""
        if (peaks.length() == 0) return false to "zx_waveedit 的 peaks 为空"
        if (peaks.length() > 60) return false to "波形最多 60 帧，实际 ${peaks.length()} 帧"
        for (i in 0 until peaks.length()) {
            val v = peaks.optDouble(i, Double.NaN)
            if (v.isNaN()) return false to "peaks[$i] 不是数值"
            if (v < 0 || v > 1)
                return false to "peaks[$i]=$v 超出 0-1，波形会画出界"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_diffview" -> """
            {"type":"zx_diffview","lines":[
              {"no":"12","t":"mod","a":"val a = 1","b":"val a = 2"},
              {"no":"13","t":"add","a":"","b":"val b = 3"}]}""".trimIndent()
        "zx_baseline" -> """
            {"type":"zx_baseline","items":[
              {"n":"启动耗时","base":820,"cur":640,"tgt":600,"lower":true},
              {"n":"崩溃率","base":1.2,"cur":0.4,"tgt":0.5,"lower":true}]}""".trimIndent()
        "zx_logview" -> """
            {"type":"zx_logview","rows":[
              {"lv":"err","t":"10:22:01","v":"渲染失败：rows 长度不齐"}",
              {"lv":"ok","t":"10:22:03","v":"已回退到上一版"}"]}""".trimIndent()
        "zx_console" -> """
            {"type":"zx_console","hist":["zorv> 就绪","$ zx_discover(op=list)"]}""".trimIndent()
        "zx_depgraph" -> """
            {"type":"zx_depgraph","nodes":[{"k":"a","n":"渲染"}",{"k":"b","n":"校验"}"],
             "edges":[{"f":1,"t":0}]}""".trimIndent()
        "zx_datamap" -> """
            {"type":"zx_datamap","regions":[
              {"n":"华东","v":3200},{"n":"华南","v":1800}]}""".trimIndent()
        "zx_conflict" -> """
            {"type":"zx_conflict","items":[
              {"path":"rows[0].v[1]","label":"研发预算",
               "local":"2400","remote":"1800","sev":"SOFT"}]}""".trimIndent()
        "zx_activity" -> """
            {"type":"zx_activity","items":[
              {"k":"edit","who":"我","v":"把预算改成 2400","t":"10:24"},
              {"k":"comment","who":"同事","v":"这个数不对吧","t":"10:31"}]}""".trimIndent()
        "zx_timerange" -> """
            {"type":"zx_timerange","total":24,"a":0.25,"b":0.6}""".trimIndent()
        "zx_gridform" -> """
            {"type":"zx_gridform","cols":2,"btn":"保存","fields":[
              {"k":"name","n":"名称","v":"zorv","hint":"显示名"}",
              {"k":"port","n":"端口","v":"8080"}]}""".trimIndent()
        "zx_scorecard" -> """
            {"type":"zx_scorecard","n":"方案评估","items":[
              {"k":"cost","n":"成本","v":4,"max":5,"w":2.0},
              {"k":"risk","n":"风险","v":3,"max":5,"w":1.0}]}""".trimIndent()
        "zx_waveedit" -> """
            {"type":"zx_waveedit",
             "peaks":[0.2,0.5,0.8,0.4,0.9,0.3,0.6]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_diffview" -> "lines[{no,t,a,b}]  t ∈ add/del/mod/same · ≤14 行 · 至少 1 处改动"
        "zx_baseline" -> "items[{n,base,cur,tgt?,lower?}]  ≤6 · 不可缺 base"
        "zx_logview"  -> "rows[{lv,v,t?}]  ≤30 · lv 必填否则过滤失效"
        "zx_console"  -> "hist?[]  ≤8 · 支持 ↑ 取历史"
        "zx_depgraph" -> "nodes[{k,n}] + edges?[{f,t}]  ≤10/20 · 不可自环或越界"
        "zx_datamap"  -> "regions[{n,v≥0}]  ≤12 · 不可全 0"
        "zx_conflict" -> "items[{path,label,local,remote,sev?}]  ≤6"
        "zx_activity" -> "items[{k,who,v,t?}]  ≤10 · who 必填"
        "zx_timerange"-> "total(2-168) + a<b（0-1）"
        "zx_gridform" -> "fields[{k,n,v?,hint?}]  ≤8 · cols 1-3 · k 不可重复"
        "zx_scorecard"-> "items[{k,n,v,max?,w?}]  ≤6 · 权重和不可为 0"
        "zx_waveedit" -> "peaks?[]  ≤60 · 值 0-1"
        else -> "未注册组件：$component"
    }
}
