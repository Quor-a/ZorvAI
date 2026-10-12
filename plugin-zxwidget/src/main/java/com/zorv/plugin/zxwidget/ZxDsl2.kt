package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第二批组件（table/chart/kanban/diff/terminal/approval/media/stepper）的
 * 校验规则 + 组件目录 + 示例 DSL 生成。
 *
 * 设计原则跟第一批一致：只卡"会导致渲染错位或回传落点丢失"的错误，
 * 样式问题不拦。校验失败时把可执行修正建议回喂给模型。
 */
object ZxDsl2 {

    /** 全部 12 个组件，供 command / aciCapability / ragSource 列目录 */
    val ALL = listOf(
        "zx_compare", "zx_form", "zx_timeline", "zx_choice",
        "zx_table", "zx_chart", "zx_kanban", "zx_diff",
        "zx_terminal", "zx_approval", "zx_media", "zx_stepper"
    )

    private val CHART_STYLES = setOf("line", "bar", "donut", "area")
    private val LEVELS = setOf("L1", "L2", "L3", "L4", "L5")

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> {
        return when (component) {
            "zx_table"    -> table(root)
            "zx_chart"    -> chart(root)
            "zx_kanban"   -> kanban(root)
            "zx_diff"     -> diff(root)
            "zx_terminal" -> terminal(root)
            "zx_approval" -> approval(root)
            "zx_media"    -> media(root)
            "zx_stepper"  -> stepper(root)
            else -> false to "未注册组件：$component"
        }
    }

    // 表格：每行长度必须与列数一致，否则单元格会串行
    private fun table(o: JSONObject): Pair<Boolean, String> {
        val cols = o.optJSONArray("columns")
            ?: return false to "缺少 columns（表头数组）"
        val n = cols.length()
        val rows = o.optJSONArray("rows") ?: return false to "缺少 rows"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i)
                ?: return false to "rows[$i] 必须是数组（一行数据）"
            if (r.length() != n)
                return false to "rows[$i] 有 ${r.length()} 个单元格，但表头是 $n 列，单元格会串行"
        }
        return true to ""
    }

    // 图表：points 是渲染的唯一数据源，labels 缺了就退化成无名点，不拦
    private fun chart(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("points")
            ?: return false to "缺少 points（数值数组）"
        if (pts.length() < 2) return false to "points 至少 2 个点，否则画不出趋势"
        for (i in 0 until pts.length()) {
            val v = pts.opt(i)
            if (v !is Number)
                return false to "points[$i] 不是数值：$v（图表无法定位纵坐标）"
        }
        val st = o.optString("style", "line")
        if (st !in CHART_STYLES)
            return false to "style 非法：$st，可选 ${CHART_STYLES.joinToString()}"
        val labels = o.optJSONArray("labels")
        if (labels != null && labels.length() != pts.length())
            return false to "labels ${labels.length()} 个与 points ${pts.length()} 个对不上，悬浮提示会错位"
        return true to ""
    }

    private fun kanban(o: JSONObject): Pair<Boolean, String> {
        val cols = o.optJSONArray("columns")
            ?: return false to "缺少 columns"
        if (cols.length() < 2) return false to "columns 至少 2 列，否则不构成看板"
        for (i in 0 until cols.length()) {
            val c = cols.optJSONObject(i)
                ?: return false to "columns[$i] 不是对象"
            if (c.optString("name").isBlank())
                return false to "columns[$i] 缺少 name（列名）"
        }
        return true to ""
    }

    // diff：hunk 里每行必须有 t（+/-/空格），否则增删着色会全丢
    private fun diff(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("path").isBlank())
            return false to "缺少 path（改动文件路径），回传时不知道写回哪里"
        val hs = o.optJSONArray("hunks") ?: return false to "缺少 hunks"
        if (hs.length() == 0) return false to "hunks 为空，没有可展示的改动"
        for (i in 0 until hs.length()) {
            val h = hs.optJSONObject(i) ?: return false to "hunks[$i] 不是对象"
            if (h.optString("header").isBlank())
                return false to "hunks[$i] 缺少 header（如 @@ -12,4 +12,6 @@）"
            val lines = h.optJSONArray("lines")
                ?: return false to "hunks[$i] 缺少 lines"
            for (j in 0 until lines.length()) {
                val l = lines.optJSONObject(j)
                    ?: return false to "hunks[$i].lines[$j] 不是对象"
                val t = l.optString("t")
                if (t !in setOf("+", "-", " "))
                    return false to "hunks[$i].lines[$j].t 只能是 + / - / 空格，实际 '$t'"
            }
        }
        return true to ""
    }

    private fun terminal(o: JSONObject): Pair<Boolean, String> {
        val lines = o.optJSONArray("lines")
            ?: return false to "缺少 lines"
        for (i in 0 until lines.length()) {
            val l = lines.optJSONObject(i)
                ?: return false to "lines[$i] 不是对象（应为 {c, lv}）"
            if (!l.has("c")) return false to "lines[$i] 缺少 c（输出内容）"
            val lv = l.optString("lv", "out")
            if (lv !in setOf("out", "err", "warn", "ok", "cmd"))
                return false to "lines[$i].lv 非法：$lv"
        }
        return true to ""
    }

    // 审批：L3 以上必须有 detail，否则用户是在给看不懂的操作授权
    private fun approval(o: JSONObject): Pair<Boolean, String> {
        val lv = o.optString("level")
        if (lv !in LEVELS) return false to "level 非法：$lv，可选 ${LEVELS.joinToString()}"
        if (o.optString("op").isBlank()) return false to "缺少 op（操作标识）"
        if (lv in setOf("L3", "L4", "L5") && o.optString("detail").isBlank())
            return false to "$lv 级操作必须给 detail，用户需要知道自己在授权什么"
        return true to ""
    }

    private fun media(o: JSONObject): Pair<Boolean, String> {
        val k = o.optString("kind", "image")
        if (k !in setOf("image", "web", "video", "file"))
            return false to "kind 非法：$k，可选 image/web/video/file"
        if (o.optString("src").isBlank())
            return false to "缺少 src（资源地址）"
        return true to ""
    }

    private fun stepper(o: JSONObject): Pair<Boolean, String> {
        val s = o.optJSONArray("steps") ?: return false to "缺少 steps"
        if (s.length() < 2) return false to "steps 至少 2 步，否则不需要向导"
        for (i in 0 until s.length()) {
            val it = s.optJSONObject(i) ?: return false to "steps[$i] 不是对象"
            if (it.optString("t").isBlank()) return false to "steps[$i] 缺少 t（步骤标题）"
        }
        return true to ""
    }

    /* ── 示例 DSL：/zx 指令与 codeRuntime 的兜底数据 ─────────── */
    fun sample(component: String): String = when (component) {
        "zx_table" -> """{"type":"zx_table","title":"预算表",
          "columns":["项目","金额","备注"],
          "rows":[["服务器","1200","按月"],["域名","80","年付"]]}"""
        "zx_chart" -> """{"type":"zx_chart","style":"line","title":"周活跃",
          "points":[120,180,150,220,260,240,300],
          "labels":["一","二","三","四","五","六","日"]}"""
        "zx_kanban" -> """{"type":"zx_kanban","title":"迭代",
          "columns":[{"name":"待办","items":["登录页","埋点"]},
                     {"name":"进行中","items":["支付"]},
                     {"name":"完成","items":["灰度"]}]}"""
        "zx_diff" -> """{"type":"zx_diff","path":"app/src/Main.kt",
          "hunks":[{"header":"@@ -12,4 +12,6 @@",
            "lines":[{"t":" ","c":"fun main() {"},
                     {"t":"-","c":"  println(1)"},
                     {"t":"+","c":"  println(2)"}]}]}"""
        "zx_terminal" -> """{"type":"zx_terminal","cmd":"./gradlew build","exit":0,
          "lines":[{"lv":"cmd","c":"$ ./gradlew build"},
                   {"lv":"ok","c":"BUILD SUCCESSFUL in 12s"}]}"""
        "zx_approval" -> """{"type":"zx_approval","level":"L3","op":"冻结该应用",
          "detail":"将通过设备管理员冻结 com.xxx.app，期间无法启动。"}"""
        "zx_media" -> """{"type":"zx_media","kind":"image","src":"/sdcard/a.png",
          "caption":"截屏 2026-10-10"}"""
        "zx_stepper" -> """{"type":"zx_stepper","title":"接入指南",
          "steps":[{"t":"引入依赖","d":"在 build.gradle.kts 加一行"}",
                   {"t":"初始化","d":"在 Application 里 attach"},
                   {"t":"注册组件","d":"用 plugin DSL 声明"}"]}"""
        "zx_compare" -> """{"type":"zx_compare","title":"方案对比",
          "columns":["A","B"],"rows":[{"k":"成本","v":["低","高"]}]}"""
        "zx_form" -> """{"type":"zx_form","title":"补参数",
          "fields":[{"id":"usage","kind":"segment","options":["游戏","办公"]}],
          "submit":{"action":"callback","name":"zx_form_submit"}}"""
        "zx_timeline" -> """{"type":"zx_timeline","bind":"agent.run_state",
          "steps":[{"n":"检索","st":"3 个来源"}"]}"""
        "zx_choice" -> """{"type":"zx_choice","title":"建议",
          "options":[{"key":"A","t":"方案 A","rec":true},{"key":"B","t":"方案 B"}]}"""
        else -> "{}"
    }

    /* ── /zxspec：把字段规格塞进终端卡，避免另开界面 ─────────── */
    fun specAsTerminal(component: String?): String {
        val list = component?.let { listOf(it) } ?: ALL
        val lines = list.map { c ->
            """{"lv":"cmd","c":"$c"},{"lv":"out","c":"  ${specOf(c)}"}"""
        }.joinToString(",")
        return """{"type":"zx_terminal","cmd":"zxspec","exit":0,"lines":[$lines]}"""
    }

    /** 单组件字段规格，供注册表跨批查询（ZxDsl.spec 的落地） */
    fun spec(component: String): String = specLine(component)

    /** /zxspec 终端卡单行规格：未登记的也要给一句话，不能出空行 */
    fun specOf(c: String): String = specLine(c).ifBlank { "（字段规格未登记，仍可渲染）" }

    /** 单行字段规格，供注册表与 /zxspec 使用 */
    fun specLine(c: String): String = when (c) {
        "zx_table"    -> "columns[] · rows[[]]，每行长度必须等于列数"
        "zx_chart"    -> "style(line|bar|donut|area) · points[](Number) · labels[]，labels 数量须对齐"
        "zx_kanban"   -> "columns[{name,items[]}]，至少 2 列"
        "zx_diff"     -> "path · hunks[{header,lines[{t(+|-| ),c}]}]"
        "zx_terminal" -> "cmd · exit · lines[{c,lv(out|err|warn|ok|cmd)}]"
        "zx_approval" -> "op · level(L1-L5) · detail（L3+ 必填）"
        "zx_media"    -> "kind(image|web|video|file) · src · caption?"
        "zx_stepper"  -> "steps[{t,d}]，至少 2 步"
        "zx_compare"  -> "columns[] · rows[{k,v[]}]，v 长度须等于列数"
        "zx_form"     -> "fields[{id,kind(segment|slider|text|check|date)}] · submit{action,name}"
        "zx_timeline" -> "steps[{n,st}] · bind=agent.run_state"
        "zx_choice"   -> "options[{key,t,d,rec}]，rec=true 最多 1 个"
        else -> ""
    }

    /* ── 定时任务：把 payload 转成图表卡 / 差异卡 ─────────────── */
    fun buildChartFrom(payload: String): String {
        val o = runCatching { JSONObject(payload) }.getOrElse { JSONObject() }
        val pts = o.optJSONArray("points") ?: JSONArray()
        val labels = o.optJSONArray("labels") ?: JSONArray()
        return JSONObject()
            .put("type", "zx_chart")
            .put("title", o.optString("title", "每日摘要"))
            .put("style", o.optString("style", "line"))
            .put("points", pts)
            .put("labels", labels)
            .toString()
    }

    fun buildDiffFrom(payload: String): String {
        val o = runCatching { JSONObject(payload) }.getOrElse { JSONObject() }
        return JSONObject()
            .put("type", "zx_diff")
            .put("path", o.optString("path", "unknown"))
            .put("hunks", o.optJSONArray("hunks") ?: JSONArray())
            .toString()
    }
}
