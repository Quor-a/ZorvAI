package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第四批组件（radar/rank/org/sankey/gauge/range/color/breadcrumb/pager/countdown/sign/board）
 * 的校验规则 + 目录 + 示例 DSL + 字段规格。
 *
 * 这批组件第一次引入了「连续手势」，所以校验除了结构正确性，
 * 还要检查"手势有没有落点"——比如签名板没有回传通道、
 * 画布元素没有 k、范围滑块 min>=max，都属于"用户操作了但什么都不会发生"。
 */
object ZxDsl4 {

    val ALL = listOf(
        "zx_radar", "zx_rank", "zx_org", "zx_sankey",
        "zx_gauge", "zx_range", "zx_color", "zx_breadcrumb",
        "zx_pager", "zx_countdown", "zx_sign", "zx_board"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_radar"      -> radar(root)
        "zx_rank"       -> rank(root)
        "zx_org"        -> org(root)
        "zx_sankey"     -> sankey(root)
        "zx_gauge"      -> gauge(root)
        "zx_range"      -> range(root)
        "zx_color"      -> color(root)
        "zx_breadcrumb" -> breadcrumb(root)
        "zx_pager"      -> pager(root)
        "zx_countdown"  -> countdown(root)
        "zx_sign"       -> sign(root)
        "zx_board"      -> board(root)
        else -> false to "未注册组件：$component"
    }

    /** 雷达：至少 3 维，值必须 0-1 */
    private fun radar(o: JSONObject): Pair<Boolean, String> {
        val dims = o.optJSONArray("dims")
            ?: return false to "zx_radar 缺少 dims（维度名数组）"
        if (dims.length() < 3)
            return false to "雷达图至少需要 3 个维度，实际 ${dims.length()} 个（2 维退化成直线，1 维是个点）"
        if (dims.length() > 8)
            return false to "雷达图最多 8 个维度，实际 ${dims.length()} 个"
        for (i in 0 until dims.length()) {
            if (dims.optString(i).isBlank())
                return false to "dims[$i] 是空字符串，坐标轴会没有名字"
        }

        fun checkVals(arr: JSONArray?, tag: String): Pair<Boolean, String> {
            if (arr == null) return false to "$tag 缺少 v（数值数组）"
            if (arr.length() != dims.length())
                return false to "$tag 的 v 有 ${arr.length()} 个值，但 dims 有 ${dims.length()} 个维度，多边形画不出来"
            for (i in 0 until arr.length()) {
                val v = arr.optDouble(i, Double.NaN)
                if (v.isNaN()) return false to "$tag.v[$i] 不是数值：${arr.opt(i)}"
                if (v < 0 || v > 1)
                    return false to "$tag.v[$i]=$v 超出 0-1（雷达图按归一化半径画），请先归一化"
            }
            return true to ""
        }

        val series = o.optJSONArray("series")
        return if (series != null) {
            if (series.length() > 3) return false to "最多 3 条序列，实际 ${series.length()} 条（再多颜色分不开）"
            for (s in 0 until series.length()) {
                val sd = series.optJSONObject(s)
                    ?: return false to "series[$s] 不是对象"
                if (sd.optString("n").isBlank())
                    return false to "series[$s] 缺少 n（序列名），图例里无法区分"
                val r = checkVals(sd.optJSONArray("v"), "series[$s]")
                if (!r.first) return r
            }
            true to ""
        } else checkVals(o.optJSONArray("v"), "zx_radar")
    }

    /** 排行：v 必须非负，否则条长算不出来 */
    private fun rank(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_rank 缺少 items"
        if (items.length() == 0) return false to "zx_rank 的 items 为空"
        if (items.length() > 12) return false to "排行榜最多 12 条，实际 ${items.length()} 条"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象"
            if (it.optString("n").isBlank())
                return false to "items[$i] 缺少 n（名称）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v（数值），条长算不出来"
            if (v < 0) return false to "items[$i].v=$v 是负数，条长会是负的"
        }
        return true to ""
    }

    /** 组织：depth 必须连续，否则会出现空层 */
    private fun org(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_org 缺少 nodes"
        if (nodes.length() == 0) return false to "zx_org 的 nodes 为空"
        if (nodes.length() > 24) return false to "组织节点最多 24 个，实际 ${nodes.length()} 个"
        val depths = sortedSetOf<Int>()
        for (i in 0 until nodes.length()) {
            val nd = nodes.optJSONObject(i)
                ?: return false to "nodes[$i] 不是对象"
            if (nd.optString("n").isBlank())
                return false to "nodes[$i] 缺少 n（姓名/部门名）"
            if (nd.optString("k").isBlank())
                return false to "nodes[$i] 缺少 k（回传键）"
            val d = nd.optInt("d", 0)
            if (d < 0 || d > 5) return false to "nodes[$i].d=$d 超出 0-5"
            depths.add(d)
        }
        val sorted = depths.toList()
        for (i in 1 until sorted.size) {
            if (sorted[i] != sorted[i - 1] + 1)
                return false to "层级不连续：有 ${sorted[i - 1]} 层和 ${sorted[i]} 层，中间缺了，会渲染出空白层"
        }
        return true to ""
    }

    /** 桑基：from/to 必填，v 正数 */
    private fun sankey(o: JSONObject): Pair<Boolean, String> {
        val flows = o.optJSONArray("flows")
            ?: return false to "zx_sankey 缺少 flows"
        if (flows.length() == 0) return false to "zx_sankey 的 flows 为空"
        if (flows.length() > 10) return false to "桑基图最多 10 条流，实际 ${flows.length()} 条"
        for (i in 0 until flows.length()) {
            val f = flows.optJSONObject(i)
                ?: return false to "flows[$i] 不是对象"
            if (f.optString("from").isBlank()) return false to "flows[$i] 缺少 from（来源名）"
            if (f.optString("to").isBlank()) return false to "flows[$i] 缺少 to（去向名）"
            val v = f.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "flows[$i] 缺少 v（流量值）"
            if (v <= 0) return false to "flows[$i].v=$v 必须为正数，否则带宽为 0 看不见"
        }
        return true to ""
    }

    /** 仪表：v 必须 0-1 */
    private fun gauge(o: JSONObject): Pair<Boolean, String> {
        if (!o.has("v")) return false to "zx_gauge 缺少 v（当前值）"
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return false to "zx_gauge 的 v 不是数值：${o.opt("v")}"
        if (v < 0 || v > 1)
            return false to "zx_gauge 的 v=$v 超出 0-1，弧长会画到表盘外"
        if (o.has("target")) {
            val t = o.optDouble("target", Double.NaN)
            if (t.isNaN()) return false to "zx_gauge 的 target 不是数值"
            if (t < 0 || t > 1) return false to "zx_gauge 的 target=$t 超出 0-1，刻线画不出来"
        }
        return true to ""
    }

    /** 范围滑块：min < max，lo <= hi */
    private fun range(o: JSONObject): Pair<Boolean, String> {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0)
        if (max <= min)
            return false to "zx_range 的 max($max) 必须大于 min($min)，否则整个轨道长度为 0"
        val lo = o.optDouble("lo", min)
        val hi = o.optDouble("hi", max)
        if (lo > hi)
            return false to "zx_range 的 lo($lo) 大于 hi($hi)，选区是反的（已为你交换，但建议检查数据）"
        if (lo < min || hi > max)
            return false to "zx_range 的 lo/hi 超出 [min,max]=[$min,$max] 范围"
        return true to ""
    }

    /** 取色：色值必须能解析 */
    private fun color(o: JSONObject): Pair<Boolean, String> {
        val sw = o.optJSONArray("swatches") ?: return true to ""
        if (sw.length() > 12) return false to "色板最多 12 个，实际 ${sw.length()} 个"
        for (i in 0 until sw.length()) {
            val hex = sw.optString(i)
            if (!hex.matches(Regex("#[0-9A-Fa-f]{6}")))
                return false to "swatches[$i]=\"$hex\" 不是合法的 #RRGGBB 色值"
        }
        return true to ""
    }

    /** 面包屑：path 元素可以是字符串或 {n} */
    private fun breadcrumb(o: JSONObject): Pair<Boolean, String> {
        val path = o.optJSONArray("path")
            ?: return false to "zx_breadcrumb 缺少 path"
        if (path.length() == 0) return false to "zx_breadcrumb 的 path 为空"
        if (path.length() > 8) return false to "面包屑最多 8 级，实际 ${path.length()} 级（再多级手机上要横滚很久）"
        for (i in 0 until path.length()) {
            val v = path.opt(i)
            val ok = when (v) {
                is JSONObject -> v.optString("n").isNotBlank()
                is String -> v.isNotBlank()
                else -> false
            }
            if (!ok) return false to "path[$i] 既不是非空字符串，也不是含 n 字段的对象"
        }
        return true to ""
    }

    /** 分页：total/cur 合法性 */
    private fun pager(o: JSONObject): Pair<Boolean, String> {
        val total = o.optInt("total", -1)
        if (total < 1) return false to "zx_pager 缺少 total（总页数），或 total < 1"
        if (total > 9999) return false to "总页数 $total 过大"
        val cur = o.optInt("cur", 1)
        if (cur < 1 || cur > total)
            return false to "zx_pager 的 cur=$cur 超出 1-$total，会显示一个不存在的页"
        val per = o.optInt("per", 10)
        if (per < 1) return false to "zx_pager 的 per=$per 必须为正（每页条数）"
        return true to ""
    }

    /** 倒计时：sec 正数 */
    private fun countdown(o: JSONObject): Pair<Boolean, String> {
        val sec = o.optInt("sec", -1)
        if (sec < 1) return false to "zx_countdown 缺少 sec（秒数），或 sec < 1"
        if (sec > 86400) return false to "倒计时 $sec 秒超过 24 小时，用日历更合适"
        return true to ""
    }

    /** 签名：必须能回传 */
    private fun sign(o: JSONObject): Pair<Boolean, String> {
        // 签名板本身没有必填结构，但要提醒模型：不接回传的话用户签了也白签
        val sink = o.optString("onDone")
        if (sink.isNotBlank() && sink !in setOf("zx_form_submit", "zx_select", "callback"))
            return false to "zx_sign 的 onDone 只能是 zx_form_submit / zx_select / callback，实际 \"$sink\""
        return true to ""
    }

    /** 画布：每个元素要有 k，坐标 0-1 */
    private fun board(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_board 缺少 items"
        if (items.length() == 0) return false to "zx_board 的 items 为空"
        if (items.length() > 12) return false to "画布最多 12 个元素，实际 ${items.length()} 个"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象"
            if (it.optString("t").isBlank())
                return false to "items[$i] 缺少 t（显示文本）"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（回传键），拖动后无法告诉模型动的是哪个"
            val x = it.optDouble("x", -1.0); val y = it.optDouble("y", -1.0)
            if (x < 0 || x > 1 || y < 0 || y > 1)
                return false to "items[$i] 的 x=$x y=$y 超出 0-1（相对坐标），元素会被夹到边界"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_radar" -> """
            {"type":"zx_radar","dims":["性能","续航","拍照","价格","系统"],
             "series":[{"n":"本机","v":[0.9,0.7,0.8,0.6,0.85]},
                       {"n":"竞品","v":[0.75,0.85,0.7,0.8,0.7]}]}""".trimIndent()
        "zx_rank" -> """
            {"type":"zx_rank","items":[
              {"n":"首页","v":12400,"label":"12.4k"},
              {"n":"搜索","v":9800,"label":"9.8k"},
              {"n":"详情","v":7600,"label":"7.6k"},
              {"n":"结算","v":3100,"label":"3.1k"}]}""".trimIndent()
        "zx_org" -> """
            {"type":"zx_org","nodes":[
              {"k":"ceo","n":"张总","r":"CEO","d":0},
              {"k":"tech","n":"李工","r":"技术","d":1},
              {"k":"ops","n":"王经理","r":"运营","d":1},
              {"k":"fe","n":"小陈","r":"前端","d":2},
              {"k":"be","n":"小刘","r":"后端","d":2}]}""".trimIndent()
        "zx_sankey" -> """
            {"type":"zx_sankey","flows":[
              {"from":"自然量","to":"注册","v":5200},
              {"from":"投放","to":"注册","v":3100},
              {"from":"注册","to":"付费","v":1400},
              {"from":"注册","to":"流失","v":6900}]}""".trimIndent()
        "zx_gauge" -> """
            {"type":"zx_gauge","v":0.78,"target":0.85,"label":"测试覆盖率"}"""".trimIndent()
        "zx_range" -> """
            {"type":"zx_range","min":0,"max":5000,"lo":1200,"hi":3400}""".trimIndent()
        "zx_color" -> """
            {"type":"zx_color","swatches":["#5046E4","#3FBF7F","#E8A33D","#E5544B","#4A9BE8"]}""".trimIndent()
        "zx_breadcrumb" -> """
            {"type":"zx_breadcrumb","path":["工程","app","ui","Main.kt"]}""".trimIndent()
        "zx_pager" -> """
            {"type":"zx_pager","total":12,"cur":3,"per":20}""".trimIndent()
        "zx_countdown" -> """
            {"type":"zx_countdown","sec":300,"label":"验证码有效期","auto":true}""".trimIndent()
        "zx_sign" -> """
            {"type":"zx_sign","onDone":"zx_form_submit"}""".trimIndent()
        "zx_board" -> """
            {"type":"zx_board","items":[
              {"k":"a","t":"想法 A","x":0.15,"y":0.2},
              {"k":"b","t":"想法 B","x":0.55,"y":0.45},
              {"k":"c","t":"想法 C","x":0.3,"y":0.7}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_radar"      -> "dims[3-8] + series[{n,v[0-1]}] 或 v[0-1]  最多 3 条序列"
        "zx_rank"       -> "items[{n,v≥0,label?}]  最多 12 条 · 前三名特殊配色"
        "zx_org"        -> "nodes[{k,n,r?,d}]  d 层级必须连续 · 最多 24 节点"
        "zx_sankey"     -> "flows[{from,to,v>0}]  最多 10 条 · 带宽按占比"
        "zx_gauge"      -> "v(0-1),target?(0-1),label?  75%+ 绿 · 40%+ 蓝 · 其余红"
        "zx_range"      -> "min,max,lo?,hi?  双端拖拽 · 松手回传 {lo,hi}"
        "zx_color"      -> "swatches[#RRGGBB]  最多 12 个 · HSV 面板固定 S=0.85"
        "zx_breadcrumb" -> "path[字符串或{n}]  最多 8 级 · 点击回传层级"
        "zx_pager"      -> "total,cur?,per?  窄屏只显示当前与两端"
        "zx_countdown"  -> "sec(1-86400),label?,auto?  归零回传 done"
        "zx_sign"       -> "onDone?(zx_form_submit/zx_select/callback)  笔迹存点集不存位图"
        "zx_board"      -> "items[{k,t,x,y}]  x/y 为 0-1 相对坐标 · 最多 12 个"
        else -> "未注册组件：$component"
    }
}
