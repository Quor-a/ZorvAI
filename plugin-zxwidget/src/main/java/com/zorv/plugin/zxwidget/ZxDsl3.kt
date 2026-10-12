package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三批组件（metrics/gantt/tree/flow/calendar/rating/editor/audio/heatmap/toggle/qr/stack）
 * 的校验规则 + 目录 + 示例 DSL。
 *
 * 延续同一条判定标准：只卡「会导致渲染错位、回传落点丢失、或者用户点了没反应」的错误。
 * 样式层面的自由度一律放开——模型把颜色写丑不是 bug，写错结构才是。
 */
object ZxDsl3 {

    /** 第三批 12 个组件 */
    val ALL = listOf(
        "zx_metrics", "zx_gantt", "zx_tree", "zx_flow",
        "zx_calendar", "zx_rating", "zx_editor", "zx_audio",
        "zx_heatmap", "zx_toggle", "zx_qr", "zx_stack"
    )

    /** 第三批容器 3 个 */
    val CONTAINERS = listOf("zx_grid", "zx_master", "zx_stagger")

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_metrics"  -> metrics(root)
        "zx_gantt"    -> gantt(root)
        "zx_tree"     -> tree(root)
        "zx_flow"     -> flow(root)
        "zx_calendar" -> calendar(root)
        "zx_rating"   -> rating(root)
        "zx_editor"   -> editor(root)
        "zx_audio"    -> audio(root)
        "zx_heatmap"  -> heatmap(root)
        "zx_toggle"   -> toggle(root)
        "zx_qr"       -> qr(root)
        "zx_stack"    -> stack(root)
        in CONTAINERS -> container(component, root)
        else -> false to "未注册组件：$component"
    }

    /* ── metrics：必须有 items，每项至少 k + v ────────────────── */
    private fun metrics(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_metrics 缺少 items（指标数组）"
        if (items.length() == 0) return false to "zx_metrics 的 items 为空"
        if (items.length() > 6) return false to "指标最多 6 个（再多手机上会挤成一团），实际 ${items.length()} 个"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（指标名），回传时无法定位用户点了哪个"
            if (it.optString("v").isBlank())
                return false to "items[$i] 缺少 v（指标值）"
        }
        return true to ""
    }

    /* ── gantt：e 必须 >= s，否则条算出来是负宽度 ──────────────── */
    private fun gantt(o: JSONObject): Pair<Boolean, String> {
        val tasks = o.optJSONArray("tasks")
            ?: return false to "zx_gantt 缺少 tasks"
        if (tasks.length() == 0) return false to "zx_gantt 的 tasks 为空"
        if (tasks.length() > 12) return false to "任务最多 12 条，实际 ${tasks.length()} 条"
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i)
                ?: return false to "tasks[$i] 不是对象"
            if (t.optString("n").isBlank())
                return false to "tasks[$i] 缺少 n（任务名）"
            // 允许缺省（用下标兜底），但给了就必须合法
            if (t.has("s") && t.has("e")) {
                val s = t.optDouble("s", Double.NaN); val e = t.optDouble("e", Double.NaN)
                if (s.isNaN() || e.isNaN())
                    return false to "tasks[$i] 的 s/e 不是数值：s=${t.opt("s")} e=${t.opt("e")}"
                if (e < s)
                    return false to "tasks[$i] 的 e($e) 小于 s($s)，条宽会是负数"
            }
        }
        return true to ""
    }

    /* ── tree：目录靠 children 判定，叶子必须有 path 才能回传 ───── */
    private fun tree(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_tree 缺少 nodes"
        if (nodes.length() == 0) return false to "zx_tree 的 nodes 为空"

        fun walk(arr: JSONArray, depth: Int): Pair<Boolean, String> {
            if (depth > 5) return false to "文件树超过 5 层，气泡里放不下"
            for (i in 0 until arr.length()) {
                val nd = arr.optJSONObject(i)
                    ?: return false to "层级 $depth 的第 $i 个节点不是对象"
                if (nd.optString("n").isBlank())
                    return false to "层级 $depth 的第 $i 个节点缺少 n（显示名）"
                // 叶子节点：回传时用它定位，必须给
                val kids = nd.optJSONArray("children")
                if (kids == null && nd.optString("path").isBlank())
                    return false to "叶子节点「${nd.optString("n")}」缺少 path，点击后无法回传具体文件"
                if (kids != null) {
                    val r = walk(kids, depth + 1)
                    if (!r.first) return r
                }
            }
            return true to ""
        }
        return walk(nodes, 0)
    }

    /* ── flow：edges 的下标必须落在节点范围内 ──────────────────── */
    private fun flow(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_flow 缺少 nodes"
        if (nodes.length() == 0) return false to "zx_flow 的 nodes 为空"
        if (nodes.length() > 10) return false to "流程节点最多 10 个，实际 ${nodes.length()} 个"
        for (i in 0 until nodes.length()) {
            val nd = nodes.optJSONObject(i)
                ?: return false to "nodes[$i] 不是对象"
            if (nd.optString("n").isBlank())
                return false to "nodes[$i] 缺少 n（节点名）"
        }
        val edges = o.optJSONArray("edges") ?: return true to ""
        for (e in 0 until edges.length()) {
            val ed = edges.optJSONObject(e)
                ?: return false to "edges[$e] 不是对象，应为 {\"from\":0,\"to\":1}"
            val from = ed.optInt("from", -1); val to = ed.optInt("to", -1)
            if (from < 0 || to < 0)
                return false to "edges[$e] 缺少 from 或 to（从 0 开始的下标）"
            if (from >= nodes.length() || to >= nodes.length())
                return false to "edges[$e] 的 from=$from to=$to 越界，节点只有 ${nodes.length()} 个"
            if (from == to)
                return false to "edges[$e] 的 from 与 to 相同（$from），自环没有意义"
        }
        return true to ""
    }

    /* ── calendar：total 与 days 下标必须对得上 ────────────────── */
    private fun calendar(o: JSONObject): Pair<Boolean, String> {
        val total = o.optInt("total", 30)
        if (total < 28 || total > 31)
            return false to "total（当月天数）应在 28-31 之间，实际 $total"
        val off = o.optInt("firstDow", 0)
        if (off < 0 || off > 6)
            return false to "firstDow（1号是周几）应在 0-6 之间，实际 $off"
        val days = o.optJSONArray("days") ?: return true to ""
        for (i in 0 until days.length()) {
            val d = days.optJSONObject(i)
                ?: return false to "days[$i] 不是对象，应为 {\"d\":5,\"mark\":\"done\"}"
            val dd = d.optInt("d", -1)
            if (dd < 1 || dd > total)
                return false to "days[$i].d=$dd 超出 1-$total 的范围，标记不会显示"
            val mk = d.optString("mark")
            if (mk.isNotBlank() && mk !in setOf("done", "warn", "err", "info"))
                return false to "days[$i].mark 只能是 done/warn/err/info，实际 \"$mk\""
        }
        return true to ""
    }

    /* ── rating：max 过大时星星会换行 ─────────────────────────── */
    private fun rating(o: JSONObject): Pair<Boolean, String> {
        val dims = o.optJSONArray("dims")
            ?: return false to "zx_rating 缺少 dims（评分维度数组）"
        if (dims.length() == 0) return false to "zx_rating 的 dims 为空"
        if (dims.length() > 8) return false to "评分维度最多 8 个，实际 ${dims.length()} 个"
        for (i in 0 until dims.length()) {
            val d = dims.optJSONObject(i)
                ?: return false to "dims[$i] 不是对象"
            if (d.optString("n").isBlank())
                return false to "dims[$i] 缺少 n（维度名），提交时无法区分各项分数"
            val max = d.optInt("max", 5)
            if (max !in 2..10)
                return false to "dims[$i].max 应在 2-10 之间（少于 2 无意义，多于 10 会换行），实际 $max"
            val v = d.optInt("v", 0)
            if (v > max)
                return false to "dims[$i].v=$v 超过 max=$max，渲染出的星数会越界"
        }
        return true to ""
    }

    /* ── editor：code 与 path ─────────────────────────────────── */
    private fun editor(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("code").isBlank())
            return false to "zx_editor 缺少 code（初始代码内容）"
        if (o.optString("path").isBlank())
            return false to "zx_editor 缺少 path（文件路径），保存时不知道写回哪里"
        val lines = o.optString("code").lineSequence().count()
        if (lines > 200)
            return false to "代码 $lines 行，超过 200 行上限（气泡里编辑长文件体验很差），建议改用 zx_diff"
        return true to ""
    }

    /* ── audio：peaks 必须是 0-1 的数值 ───────────────────────── */
    private fun audio(o: JSONObject): Pair<Boolean, String> {
        if (o.optInt("dur", 0) <= 0 && !o.has("dur"))
            return false to "zx_audio 缺少 dur（总时长秒数），进度条无法换算"
        val peaks = o.optJSONArray("peaks") ?: return true to ""
        if (peaks.length() > 64)
            return false to "peaks 最多 64 个（再多单个波形条宽度不足 1dp），实际 ${peaks.length()} 个"
        for (i in 0 until peaks.length()) {
            val v = peaks.optDouble(i, Double.NaN)
            if (v.isNaN())
                return false to "peaks[$i] 不是数值：${peaks.opt(i)}"
            if (v < 0 || v > 1)
                return false to "peaks[$i]=$v 超出 0-1 范围，波形会画出边界"
        }
        return true to ""
    }

    /* ── heatmap：每行长度不必一致，但都不能空 ─────────────────── */
    private fun heatmap(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_heatmap 缺少 rows（二维数组）"
        if (rows.length() == 0) return false to "zx_heatmap 的 rows 为空"
        if (rows.length() > 14) return false to "热力图最多 14 行，实际 ${rows.length()} 行"
        for (i in 0 until rows.length()) {
            val arr = rows.optJSONArray(i)
                ?: return false to "rows[$i] 不是数组，应为 [0.2, 0.8, ...]"
            if (arr.length() == 0) return false to "rows[$i] 是空数组"
            if (arr.length() > 24) return false to "rows[$i] 有 ${arr.length()} 列，最多 24 列"
            for (j in 0 until arr.length()) {
                val v = arr.optDouble(j, Double.NaN)
                if (v.isNaN())
                    return false to "rows[$i][$j] 不是数值：${arr.opt(j)}"
            }
        }
        return true to ""
    }

    /* ── toggle：k 是回传键，必须给 ────────────────────────────── */
    private fun toggle(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_toggle 缺少 items"
        if (items.length() == 0) return false to "zx_toggle 的 items 为空"
        if (items.length() > 10) return false to "开关项最多 10 个，实际 ${items.length()} 个"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象"
            if (it.optString("n").isBlank())
                return false to "items[$i] 缺少 n（显示名）"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（回传键），切换后无法告诉模型改的是哪一项"
        }
        return true to ""
    }

    /* ── qr：text 不能空 ──────────────────────────────────────── */
    private fun qr(o: JSONObject): Pair<Boolean, String> {
        val t = o.optString("text")
        if (t.isBlank()) return false to "zx_qr 缺少 text（要编码的内容）"
        if (t.length > 500)
            return false to "二维码内容 ${t.length} 字符，超过 500 上限（会密到扫不出来）"
        return true to ""
    }

    /* ── stack：每张卡要有 k ──────────────────────────────────── */
    private fun stack(o: JSONObject): Pair<Boolean, String> {
        val cards = o.optJSONArray("cards")
            ?: return false to "zx_stack 缺少 cards"
        if (cards.length() == 0) return false to "zx_stack 的 cards 为空"
        if (cards.length() > 8) return false to "堆栈最多 8 张，实际 ${cards.length()} 张"
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i)
                ?: return false to "cards[$i] 不是对象"
            if (c.optString("t").isBlank())
                return false to "cards[$i] 缺少 t（标题）"
            if (c.optString("k").isBlank())
                return false to "cards[$i] 缺少 k（回传键）"
        }
        return true to ""
    }

    /* ── 第三批容器 ───────────────────────────────────────────── */
    private fun container(type: String, o: JSONObject): Pair<Boolean, String> {
        val kids = o.optJSONArray("children")
            ?: return false to "$type 缺少 children"
        if (kids.length() == 0) return false to "$type 的 children 为空"
        when (type) {
            "zx_grid" -> {
                val cols = o.optInt("cols", 2)
                if (cols !in 2..4)
                    return false to "zx_grid 的 cols 应在 2-4 之间（cols=1 用 Column，>4 手机上每格不足 80dp），实际 $cols"
                if (kids.length() > 12)
                    return false to "zx_grid 最多 12 个子节点，实际 ${kids.length()} 个"
            }
            "zx_master" -> {
                if (kids.length() != 2)
                    return false to "zx_master 必须且只能有 2 个子节点（主列表 + 详情），实际 ${kids.length()} 个"
                val ratio = o.optDouble("ratio", 0.4)
                if (ratio < 0.25 || ratio > 0.6)
                    return false to "zx_master 的 ratio 应在 0.25-0.6 之间，实际 $ratio"
            }
            "zx_stagger" -> {
                if (kids.length() > 16)
                    return false to "zx_stagger 最多 16 个子节点，实际 ${kids.length()} 个"
            }
        }
        for (i in 0 until kids.length()) {
            val c = kids.optJSONObject(i)
                ?: return false to "$type.children[$i] 不是对象"
            val sub = c.optString("type")
            if (sub !in ZxDsl.ALL_COMPONENTS && sub !in ZxDsl2.ALL && sub !in ALL)
                return false to "$type.children[$i] 的 type 不是已注册组件：$sub"
            val (ok, why) = ZxDsl.validateAny(sub, c)
            if (!ok) return false to "$type.children[$i] → $why"
        }
        return true to ""
    }

    /* ── 示例 DSL，供 /zx <组件名> 与 ACI 目录使用 ─────────────── */
    fun sample(component: String): String = when (component) {
        "zx_metrics" -> """
            {"type":"zx_metrics","items":[
              {"k":"日活","v":"12.4万","delta":"+8.2%"},
              {"k":"留存","v":"43.1%","delta":"-1.4%"},
              {"k":"崩溃率","v":"0.07%","delta":"+0.01%"},
              {"k":"ARPU","v":"¥8.6","delta":"+0.3"}]}""".trimIndent()
        "zx_gantt" -> """
            {"type":"zx_gantt","tasks":[
              {"n":"需求评审","s":0,"e":2,"st":"done"},
              {"n":"接口开发","s":2,"e":6,"st":"done"},
              {"n":"联调","s":6,"e":8,"st":"run"},
              {"n":"灰度","s":8,"e":10,"st":"todo"},
              {"n":"全量","s":10,"e":11,"st":"todo"}]}""".trimIndent()
        "zx_tree" -> """
            {"type":"zx_tree","nodes":[
              {"n":"app","children":[
                {"n":"ui","children":[{"n":"Main.kt","path":"app/ui/Main.kt","tag":"改"}"]},
                {"n":"data","children":[{"n":"Repo.kt","path":"app/data/Repo.kt"}]}]},
              {"n":"build.gradle.kts","path":"build.gradle.kts"}]}""".trimIndent()
        "zx_flow" -> """
            {"type":"zx_flow","nodes":[
              {"n":"拉取代码","st":"done"},{"n":"编译","st":"done"},
              {"n":"测试","st":"run"},{"n":"打包","st":"todo"}],
             "edges":[{"from":0,"to":1},{"from":1,"to":2},{"from":2,"to":3}]}""".trimIndent()
        "zx_calendar" -> """
            {"type":"zx_calendar","month":"2026年10月","total":31,"firstDow":4,
             "days":[{"d":3,"mark":"done"},{"d":10,"mark":"warn"},{"d":17,"mark":"err"}]}""".trimIndent()
        "zx_rating" -> """
            {"type":"zx_rating","dims":[
              {"n":"响应速度","max":5,"v":4},
              {"n":"准确性","max":5,"v":5},
              {"n":"表达清晰","max":5,"v":3}]}""".trimIndent()
        "zx_editor" -> """
            {"type":"zx_editor","lang":"kotlin","path":"app/ui/Main.kt",
             "code":"fun main() {\n    println(\"hi\")\n}"}""".trimIndent()
        "zx_audio" -> """
            {"type":"zx_audio","title":"会议录音 01","dur":184,"at":0.35,
             "peaks":[0.2,0.5,0.9,0.4,0.7,1.0,0.3,0.6]}""".trimIndent()
        "zx_heatmap" -> """
            {"type":"zx_heatmap","rows":[
              [0.1,0.4,0.9,0.3],[0.7,0.2,0.5,1.0],[0.3,0.8,0.1,0.6]]}""".trimIndent()
        "zx_toggle" -> """
            {"type":"zx_toggle","items":[
              {"k":"notify","n":"消息通知","d":"新消息时推送","on":true},
              {"k":"autoplay","n":"自动播放","on":false},
              {"k":"beta","n":"Beta 功能","d":"可能不稳定","on":false,"locked":true}]}""".trimIndent()
        "zx_qr" -> """
            {"type":"zx_qr","text":"https://example.com/invite?c=zx","caption":"扫码加入"}"""".trimIndent()
        "zx_stack" -> """
            {"type":"zx_stack","cards":[
              {"k":"a","t":"方案 A","d":"成本低，周期长"}",
              {"k":"b","t":"方案 B","d":"成本中，周期中"}",
              {"k":"c","t":"方案 C","d":"成本高，周期短"}"]}""".trimIndent()
        "zx_grid" -> """
            {"type":"zx_grid","cols":2,"children":[
              {"type":"zx_metrics","items":[{"k":"日活","v":"12万"}"]},
              {"type":"zx_chart","style":"bar","points":[1,3,2],"labels":["a","b","c"]}]}""".trimIndent()
        "zx_master" -> """
            {"type":"zx_master","ratio":0.4,"children":[
              {"type":"zx_tree","nodes":[{"n":"a","path":"a"},{"n":"b","path":"b"}]},
              {"type":"zx_terminal","cmd":"cat a","lines":[]}]}""".trimIndent()
        "zx_stagger" -> """
            {"type":"zx_stagger","children":[
              {"type":"zx_media","kind":"image","src":"/a.png"},
              {"type":"zx_terminal","cmd":"ls","lines":[]}]}""".trimIndent()
        else -> "{}"
    }

    /** 规格说明，供 /zxspec 输出 */
    fun spec(component: String): String = when (component) {
        "zx_metrics"  -> "items[{k,v,delta?}]  最多 6 项 · 点击回传 zx_select"
        "zx_gantt"    -> "tasks[{n,s,e,st?}]  最多 12 条 · st: done/run/block/todo"
        "zx_tree"     -> "nodes[{n,path?,children?,tag?}]  最多 5 层 · 叶子必须给 path"
        "zx_flow"     -> "nodes[{n,st?}] + edges[{from,to}]  最多 10 节点 · 下标从 0"
        "zx_calendar" -> "month,total,firstDow,days[{d,mark?}]  mark: done/warn/err/info"
        "zx_rating"   -> "dims[{n,max?,v?}]  最多 8 维 · max 2-10 · 提交走 zx_form_submit"
        "zx_editor"   -> "lang,path,code  最多 200 行 · 保存走 zx_table_commit"
        "zx_audio"    -> "title,dur,at?,peaks[{0-1}]  最多 64 个采样点"
        "zx_heatmap"  -> "rows[[数值]]  最多 14 行 × 24 列"
        "zx_toggle"   -> "items[{k,n,d?,on?,locked?}]  最多 10 项 · 切换回传 zx_toggle"
        "zx_qr"       -> "text(≤500字符),caption?"
        "zx_stack"    -> "cards[{k,t,d?}]  最多 8 张 · 点顶层回传 zx_select"
        "zx_grid"     -> "cols(2-4),children[≤12]  等宽网格"
        "zx_master"   -> "ratio(0.25-0.6),children[必须 2 个]  左列表右详情"
        "zx_stagger"  -> "children[≤16]  高度自适应瀑布流"
        else -> "未注册组件：$component"
    }
}
