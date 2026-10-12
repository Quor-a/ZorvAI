package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第六批组件（数据密集 + 直接操纵）的校验 + 示例 + 规格。
 *
 * 这批的校验重点跟前五批都不一样，主要是三类：
 *   一、编码前提：面积/字号/弧长都是相对编码，必须有一个非零基准
 *       （树图 total=0、漏斗首级 v<=0，都会让所有块塌成 0 宽）
 *   二、极端值：单个值独大或独小会把其他元素压到看不见
 *   三、命中可行性：看板列数 × 卡片数超屏宽后，卡片会窄到点不中
 */
object ZxDsl6 {

    val ALL = listOf(
        "zx_wordcloud", "zx_funnel", "zx_treemap", "zx_chord",
        "zx_sparkline", "zx_kanban", "zx_hotspot", "zx_stepper",
        "zx_segmented", "zx_mention", "zx_dropzone", "zx_resizable"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_wordcloud" -> wordcloud(root)
        "zx_funnel"    -> funnel(root)
        "zx_treemap"   -> treemap(root)
        "zx_chord"     -> chord(root)
        "zx_sparkline" -> sparkline(root)
        "zx_kanban"    -> kanban(root)
        "zx_hotspot"   -> hotspot(root)
        "zx_stepper"   -> stepper(root)
        "zx_segmented" -> segmented(root)
        "zx_mention"   -> mention(root)
        "zx_dropzone"  -> dropzone(root)
        "zx_resizable" -> resizable(root)
        else -> false to "未注册组件：$component"
    }

    private fun wordcloud(o: JSONObject): Pair<Boolean, String> {
        val words = o.optJSONArray("words")
            ?: return false to "zx_wordcloud 缺少 words"
        if (words.length() < 3) return false to "词云至少 3 个词，实际 ${words.length()} 个（少于 3 个不如直接列出来）"
        if (words.length() > 24) return false to "词云最多 24 个词，实际 ${words.length()} 个"
        var max = 0.0
        for (i in 0 until words.length()) {
            val w = words.optJSONObject(i)
                ?: return false to "words[$i] 不是对象，应为 {\"t\":\"词\",\"w\":权重}"
            val t = w.optString("t")
            if (t.isBlank()) return false to "words[$i] 缺少 t（词文本）"
            if (t.length > 10) return false to "words[$i]=\"$t\" 长度 ${t.length} 超过 10，换行后会散成一堆"
            val v = w.optDouble("w", Double.NaN)
            if (v.isNaN()) return false to "words[$i] 缺少 w（权重），字号算不出来"
            if (v < 0) return false to "words[$i].w=$v 是负数"
            max = maxOf(max, v)
        }
        if (max <= 0) return false to "所有词的权重都是 0，字号全塌成最小值，看不出差异"
        return true to ""
    }

    private fun funnel(o: JSONObject): Pair<Boolean, String> {
        val stages = o.optJSONArray("stages")
            ?: return false to "zx_funnel 缺少 stages"
        if (stages.length() < 2) return false to "漏斗至少 2 级，实际 ${stages.length()} 级"
        if (stages.length() > 8) return false to "漏斗最多 8 级，实际 ${stages.length()} 级"
        val first = stages.optJSONObject(0)
            ?: return false to "stages[0] 不是对象"
        val top = first.optDouble("v", Double.NaN)
        if (top.isNaN()) return false to "stages[0] 缺少 v（数值）"
        if (top <= 0)
            return false to "stages[0].v=$top 必须为正——它是所有层宽的基准，为 0 会导致除零、所有层塌成 0 宽"
        for (i in 0 until stages.length()) {
            val s = stages.optJSONObject(i) ?: return false to "stages[$i] 不是对象"
            if (s.optString("n").isBlank()) return false to "stages[$i] 缺少 n（阶段名）"
            val v = s.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "stages[$i] 缺少 v"
            if (v < 0) return false to "stages[$i].v=$v 是负数"
            if (i > 0) {
                val prev = stages.optJSONObject(i - 1)?.optDouble("v", 0.0) ?: 0.0
                if (v > prev * 1.001)
                    return false to "stages[$i].v=$v 大于上一级 $prev，漏斗会中间变宽——这不是漏斗（检查是否数据顺序错了）"
            }
        }
        return true to ""
    }

    private fun treemap(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_treemap 缺少 items"
        if (items.length() < 2) return false to "树图至少 2 项，实际 ${items.length()} 项"
        if (items.length() > 10) return false to "树图最多 10 项，实际 ${items.length()} 项（再多格子会小到点不中）"
        var total = 0.0
        var maxV = 0.0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: return false to "items[$i] 不是对象"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（名称）"
            if (it.optString("k").isBlank()) return false to "items[$i] 缺少 k（回传键）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v（数值）"
            if (v < 0) return false to "items[$i].v=$v 是负数，面积算不出来"
            total += v; maxV = maxOf(maxV, v)
        }
        if (total <= 0) return false to "所有 v 都是 0，总面积为 0，所有块会塌成最小宽度"
        // 独大检测：这一项占 90% 以上，其他块小于 2% 就看不见了
        if (maxV / total > 0.92)
            return false to "有一项占了 ${(maxV / total * 100).toInt()}%，其余项会被压到看不见。考虑把小项合并成「其他」。"
        return true to ""
    }

    private fun chord(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_chord 缺少 nodes"
        if (nodes.length() < 2) return false to "和弦图至少 2 个节点，实际 ${nodes.length()} 个"
        if (nodes.length() > 8) return false to "和弦图最多 8 个节点，实际 ${nodes.length()} 个"
        for (i in 0 until nodes.length()) {
            if (nodes.optString(i).isBlank()) return false to "nodes[$i] 是空字符串"
        }
        val links = o.optJSONArray("links")
            ?: return false to "zx_chord 缺少 links（关系数组）"
        if (links.length() == 0) return false to "zx_chord 的 links 为空，没有弦可画"
        if (links.length() > 20) return false to "关系最多 20 条，实际 ${links.length()} 条（再多会糊成一团）"
        var total = 0.0
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i)
                ?: return false to "links[$i] 不是对象，应为 {\"from\":0,\"to\":1,\"v\":10}"
            val f = l.optInt("from", -1); val t = l.optInt("to", -1)
            if (f !in 0 until nodes.length())
                return false to "links[$i].from=$f 超出 0-${nodes.length() - 1}"
            if (t !in 0 until nodes.length())
                return false to "links[$i].to=$t 超出 0-${nodes.length() - 1}"
            if (f == t) return false to "links[$i] 的 from 和 to 都是 $f（自环没有意义）"
            val v = l.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "links[$i] 缺少 v（流量）"
            if (v <= 0) return false to "links[$i].v=$v 必须为正，否则这条弦宽度为 0"
            total += v
        }
        if (total <= 0) return false to "所有流量都是 0，弧长全为 0"
        return true to ""
    }

    private fun sparkline(o: JSONObject): Pair<Boolean, String> {
        val pts = o.optJSONArray("points")
            ?: return false to "zx_sparkline 缺少 points"
        if (pts.length() < 2) return false to "迷你趋势至少 2 个点，实际 ${pts.length()} 个"
        if (pts.length() > 40) return false to "迷你趋势最多 40 个点，实际 ${pts.length()} 个（30dp 高里画不下）"
        for (i in 0 until pts.length()) {
            val v = pts.optDouble(i, Double.NaN)
            if (v.isNaN()) return false to "points[$i] 不是数值：${pts.opt(i)}"
        }
        // 全等值会让 span 为 0，画出来是一条直线——不是错误但通常不是想要的
        val first = pts.optDouble(0, 0.0)
        if ((1 until pts.length()).all { pts.optDouble(it, 0.0) == first })
            return false to "所有点都是 $first，画出来是一条直线，看不出趋势"
        return true to ""
    }

    private fun kanban(o: JSONObject): Pair<Boolean, String> {
        val cols = o.optJSONArray("cols")
            ?: return false to "zx_kanban 缺少 cols（列定义）"
        if (cols.length() < 2) return false to "看板至少 2 列，实际 ${cols.length()} 列"
        if (cols.length() > 4) return false to "看板最多 4 列，实际 ${cols.length()} 列（每列 96dp，4 列已需横滚）"
        for (i in 0 until cols.length()) {
            val c = cols.optJSONObject(i) ?: return false to "cols[$i] 不是对象"
            if (c.optString("n").isBlank()) return false to "cols[$i] 缺少 n（列名）"
            if (c.optString("k").isBlank()) return false to "cols[$i] 缺少 k（回传键）"
        }
        val cards = o.optJSONArray("cards")
            ?: return false to "zx_kanban 缺少 cards"
        if (cards.length() == 0) return false to "zx_kanban 的 cards 为空"
        if (cards.length() > 20) return false to "看板最多 20 张卡，实际 ${cards.length()} 张"
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i) ?: return false to "cards[$i] 不是对象"
            if (c.optString("t").isBlank()) return false to "cards[$i] 缺少 t（卡片文字）"
            if (c.optString("k").isBlank())
                return false to "cards[$i] 缺少 k（回传键），拖完无法告诉模型动的是哪张"
            val col = c.optInt("col", 0)
            if (col !in 0 until cols.length())
                return false to "cards[$i].col=$col 超出 0-${cols.length() - 1}，这张卡不会显示在任何列里"
        }
        return true to ""
    }

    private fun hotspot(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("src").isBlank() && !o.optBoolean("placeholder", false))
            return false to "zx_hotspot 缺少 src（图片），或显式声明 \"placeholder\":true 用占位图"
        val spots = o.optJSONArray("spots") ?: return true to ""   // 允许空标记，用户自己点
        if (spots.length() > 12) return false to "热点标记最多 12 个，实际 ${spots.length()} 个"
        for (i in 0 until spots.length()) {
            val s = spots.optJSONObject(i)
                ?: return false to "spots[$i] 不是对象，应为 {\"x\":0.3,\"y\":0.5,\"t\":\"说明\"}"
            val x = s.optDouble("x", -1.0); val y = s.optDouble("y", -1.0)
            if (x < 0 || x > 1 || y < 0 || y > 1)
                return false to "spots[$i] 的 x=$x y=$y 超出 0-1（相对坐标），标记会画到图外"
            if (s.optString("t").isBlank())
                return false to "spots[$i] 缺少 t（标记说明），点了不知道是什么"
        }
        return true to ""
    }

    private fun stepper(o: JSONObject): Pair<Boolean, String> {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 99.0)
        if (max <= min) return false to "zx_stepper 的 max($max) 必须大于 min($min)，没有可步进的区间"
        val step = o.optDouble("step", 1.0)
        if (step <= 0) return false to "zx_stepper 的 step=$step 必须为正，否则点击后值不变或反向"
        if (step > (max - min))
            return false to "zx_stepper 的 step=$step 大于整个区间 ${max - min}，一步就到底了"
        if (o.has("v")) {
            val v = o.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "zx_stepper 的 v 不是数值"
            if (v < min || v > max)
                return false to "zx_stepper 的 v=$v 超出 [$min,$max]"
            // v 必须落在步长刻度上，否则点一次加会跳到非刻度值
            val off = (v - min) / step
            if (kotlin.math.abs(off - kotlin.math.round(off)) > 1e-6)
                return false to "zx_stepper 的 v=$v 不在步长 $step 的刻度上（从 min=$min 起算），点加减会跳到非刻度值"
        }
        return true to ""
    }

    private fun segmented(o: JSONObject): Pair<Boolean, String> {
        val segs = o.optJSONArray("segs")
            ?: return false to "zx_segmented 缺少 segs"
        if (segs.length() < 2) return false to "分段控件至少 2 段，实际 ${segs.length()} 段"
        if (segs.length() > 5) return false to "分段控件最多 5 段，实际 ${segs.length()} 段（再多每段不到 60dp 点不中）"
        var rec = 0
        for (i in 0 until segs.length()) {
            val v = segs.opt(i)
            val s = v as? JSONObject
            val label = s?.optString("t") ?: (v as? String) ?: ""
            if (label.isBlank())
                return false to "segs[$i] 既不是非空字符串，也不是含 t 字段的对象"
            if (s != null && s.optBoolean("rec")) rec++
        }
        if (rec > 1) return false to "segs 中有 $rec 个 rec=true，推荐项只能有一个"
        val sel = o.optInt("sel", 0)
        if (sel !in 0 until segs.length())
            return false to "zx_segmented 的 sel=$sel 超出 0-${segs.length() - 1}"
        return true to ""
    }

    private fun mention(o: JSONObject): Pair<Boolean, String> {
        val people = o.optJSONArray("people")
            ?: return false to "zx_mention 缺少 people（可提及的人）"
        if (people.length() == 0) return false to "zx_mention 的 people 为空，@ 了也没候选"
        if (people.length() > 10) return false to "候选最多 10 人，实际 ${people.length()} 人（列表只显示前 5 个）"
        for (i in 0 until people.length()) {
            val p = people.optJSONObject(i)
                ?: return false to "people[$i] 不是对象，应为 {\"k\":\"id\",\"n\":\"姓名\",\"r\":\"角色\"}"
            if (p.optString("n").isBlank())
                return false to "people[$i] 缺少 n（显示名），候选列表里会是空白"
            if (p.optString("k").isBlank())
                return false to "people[$i] 缺少 k（回传键），提及了模型也不知道是谁"
        }
        return true to ""
    }

    private fun dropzone(o: JSONObject): Pair<Boolean, String> {
        val acc = o.optJSONArray("accept") ?: return true to ""   // 不限制类型
        if (acc.length() > 8) return false to "接受类型最多 8 个，实际 ${acc.length()} 个"
        for (i in 0 until acc.length()) {
            val ext = acc.optString(i)
            if (ext.isBlank()) return false to "accept[$i] 是空字符串"
            if (ext.startsWith("."))
                return false to "accept[$i]=\"$ext\" 不要带点，写 \"png\" 而不是 \".png\""
            if (!ext.matches(Regex("[a-zA-Z0-9]+")))
                return false to "accept[$i]=\"$ext\" 含非法字符，扩展名只能是字母数字"
        }
        val maxSize = o.optInt("maxSizeMb", 0)
        if (maxSize < 0) return false to "zx_dropzone 的 maxSizeMb 不能为负"
        return true to ""
    }

    private fun resizable(o: JSONObject): Pair<Boolean, String> {
        if (!o.has("left")) return false to "zx_resizable 缺少 left（左栏内容数组）"
        if (!o.has("right")) return false to "zx_resizable 缺少 right（右栏内容数组）"
        val l = o.optJSONArray("left"); val r = o.optJSONArray("right")
        if (l == null || l.length() == 0) return false to "zx_resizable 的 left 为空"
        if (r == null || r.length() == 0) return false to "zx_resizable 的 right 为空"
        if (l.length() > 5) return false to "左栏最多 5 行，实际 ${l.length()} 行"
        if (r.length() > 5) return false to "右栏最多 5 行，实际 ${r.length()} 行"
        val ratio = o.optDouble("ratio", 0.5)
        if (ratio < 0.2 || ratio > 0.8)
            return false to "zx_resizable 的 ratio=$ratio 超出 0.2-0.8（两端各留 20% 最小宽度，否则一边会被拖没）"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_wordcloud" -> """
            {"type":"zx_wordcloud","words":[
              {"t":"性能","w":42},{"t":"内存","w":31},{"t":"启动","w":27},
              {"t":"崩溃","w":18},{"t":"UI","w":14},{"t":"网络","w":11}]}""".trimIndent()
        "zx_funnel" -> """
            {"type":"zx_funnel","stages":[
              {"n":"访问","v":12400},{"n":"注册","v":4200},
              {"n":"激活","v":1800},{"n":"付费","v":620}]}""".trimIndent()
        "zx_treemap" -> """
            {"type":"zx_treemap","items":[
              {"k":"img","n":"图片","v":4200},{"k":"js","n":"JS","v":2100},
              {"k":"css","n":"CSS","v":800},{"k":"font","n":"字体","v":640}]}""".trimIndent()
        "zx_chord" -> """
            {"type":"zx_chord","nodes":["首页","搜索","详情","结算"],
             "links":[{"from":0,"to":1,"v":52},{"from":1,"to":2,"v":38},
                      {"from":2,"to":3,"v":21},{"from":0,"to":2,"v":14}]}""".trimIndent()
        "zx_sparkline" -> """
            {"type":"zx_sparkline","label":"日活","points":[88,92,85,97,103,99,112]}""".trimIndent()
        "zx_kanban" -> """
            {"type":"zx_kanban",
             "cols":[{"k":"todo","n":"待办"}",{"k":"doing","n":"进行中"}",{"k":"done","n":"已完成"}"],
             "cards":[{"k":"t1","t":"修崩溃","col":0},{"k":"t2","t":"加埋点","col":1}]}""".trimIndent()
        "zx_hotspot" -> """
            {"type":"zx_hotspot","src":"","placeholder":true,
             "spots":[{"x":0.3,"y":0.4,"t":"按钮偏移"}",{"x":0.7,"y":0.6,"t":"文字截断"}"]}""".trimIndent()
        "zx_stepper" -> """
            {"type":"zx_stepper","label":"数量","min":1,"max":20,"step":1,"v":3}""".trimIndent()
        "zx_segmented" -> """
            {"type":"zx_segmented","sel":0,
             "segs":[{"k":"day","t":"日","rec":true},{"k":"week","t":"周"}",{"k":"month","t":"月"}"]}""".trimIndent()
        "zx_mention" -> """
            {"type":"zx_mention","hint":"输入 @ 指派给谁",
             "people":[{"k":"u1","n":"李工","r":"前端"}",{"k":"u2","n":"小陈","r":"后端"}"]}""".trimIndent()
        "zx_dropzone" -> """
            {"type":"zx_dropzone","accept":["png","jpg","pdf"],"maxSizeMb":10,
             "hint":"拖入截图或日志文件"}"""".trimIndent()
        "zx_resizable" -> """
            {"type":"zx_resizable","ratio":0.5,
             "left":["改动前","旧逻辑"],"right":["改动后","新逻辑"]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_wordcloud" -> "words[{t≤10字,w>0}]  3-24 个 · 字号编码权重"
        "zx_funnel"    -> "stages[{n,v}]  2-8 级 · 逐级递减，首级 >0"
        "zx_treemap"   -> "items[{k,n,v≥0}]  2-10 项 · 单项不超 92%"
        "zx_chord"     -> "nodes[名] + links[{from,to,v>0}]  2-8 节点 · 无自环"
        "zx_sparkline" -> "points[数值] + label?  2-40 点 · 不可全等值"
        "zx_kanban"    -> "cols[{k,n}] 2-4 + cards[{k,t,col}]  跨列拖拽"
        "zx_hotspot"   -> "src + spots?[{x,y,t}] 0-1 相对坐标 · 最多 12"
        "zx_stepper"   -> "min,max,step>0,v?  v 须落在步长刻度上"
        "zx_segmented" -> "segs[字符串或{k,t}] 2-5 段 · rec 最多 1 个"
        "zx_mention"   -> "people[{k,n,r?}]  最多 10 · 回传结构化 mentions"
        "zx_dropzone"  -> "accept?[扩展名不带点],maxSizeMb?  校验后才回传"
        "zx_resizable" -> "left[],right[],ratio?(0.2-0.8)  两端留 20%"
        else -> "未注册组件：$component"
    }
}
