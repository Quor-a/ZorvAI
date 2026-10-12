package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第七批组件（引导 / 识别 / 编排）的校验 + 示例 + 规格。
 *
 * 这批的校验重心是"能不能定位"和"会不会有歧义"：
 *   · 转盘扇区数决定吸附角，扇区数非法就吸不住
 *   · 交叉表行列长度不齐会导致汇总错行
 *   · 状态流的 at 越界会没有"当前"节点
 *   · 引导步骤为空，用户就卡在没有下一步的状态
 */
object ZxDsl7 {

    val ALL = listOf(
        "zx_tour", "zx_swipe", "zx_matrix", "zx_autocomplete",
        "zx_spin", "zx_barcode", "zx_dualist", "zx_scrubber",
        "zx_bento", "zx_minmap", "zx_statelist", "zx_legend"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_tour"         -> tour(root)
        "zx_swipe"        -> swipe(root)
        "zx_matrix"       -> matrix(root)
        "zx_autocomplete" -> autocomplete(root)
        "zx_spin"         -> spin(root)
        "zx_barcode"      -> barcode(root)
        "zx_dualist"      -> dualist(root)
        "zx_scrubber"     -> scrubber(root)
        "zx_bento"        -> bento(root)
        "zx_minmap"       -> minmap(root)
        "zx_statelist"    -> statelist(root)
        "zx_legend"       -> legend(root)
        else -> false to "未注册组件：$component"
    }

    private fun tour(o: JSONObject): Pair<Boolean, String> {
        val steps = o.optJSONArray("steps")
            ?: return false to "zx_tour 缺少 steps（引导步骤）"
        if (steps.length() == 0) return false to "zx_tour 的 steps 为空——用户会卡在一个没有下一步的引导里"
        if (steps.length() > 8) return false to "引导最多 8 步，实际 ${steps.length()} 步（再长没人看完）"
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: return false to "steps[$i] 不是对象"
            if (s.optString("t").isBlank())
                return false to "steps[$i] 缺少 t（标题）——引导没有标题就不知道在讲哪一步"
        }
        val at = o.optInt("at", 0)
        if (at !in 0 until steps.length())
            return false to "zx_tour 的 at=$at 超出 0-${steps.length() - 1}，没有当前步"
        return true to ""
    }

    private fun swipe(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_swipe 缺少 items"
        if (items.length() == 0) return false to "zx_swipe 的 items 为空"
        if (items.length() > 8) return false to "滑动列表最多 8 行，实际 ${items.length()} 行"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: return false to "items[$i] 不是对象"
            if (it.optString("t").isBlank()) return false to "items[$i] 缺少 t（行文本）"
            val acts = it.optJSONArray("acts")
                ?: return false to "items[$i] 缺少 acts（滑出动作）——没有动作就不叫滑动操作"
            if (acts.length() == 0) return false to "items[$i] 的 acts 为空"
            if (acts.length() > 3) return false to "items[$i] 的 acts 最多 3 个，实际 ${acts.length()} 个（手指点不中）"
            for (k in 0 until acts.length()) {
                val a = acts.optJSONObject(k) ?: return false to "items[$i].acts[$k] 不是对象"
                if (a.optString("t").isBlank()) return false to "items[$i].acts[$k] 缺少 t（动作名）"
                if (a.optString("k").isBlank())
                    return false to "items[$i].acts[$k] 缺少 k（回传键），点了不知道执行了什么"
            }
        }
        return true to ""
    }

    private fun matrix(o: JSONObject): Pair<Boolean, String> {
        val cols = o.optJSONArray("cols")
            ?: return false to "zx_matrix 缺少 cols（列表头）"
        if (cols.length() < 2) return false to "交叉表至少 2 列，实际 ${cols.length()} 列"
        if (cols.length() > 5) return false to "交叉表最多 5 列，实际 ${cols.length()} 列"
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_matrix 缺少 rows（行数据）"
        if (rows.length() == 0) return false to "zx_matrix 的 rows 为空"
        if (rows.length() > 7) return false to "交叉表最多 7 行，实际 ${rows.length()} 行"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: return false to "rows[$i] 不是对象"
            if (r.optString("n").isBlank()) return false to "rows[$i] 缺少 n（行名）"
            if (r.optString("k").isBlank()) return false to "rows[$i] 缺少 k（回传键）"
            val v = r.optJSONArray("v")
                ?: return false to "rows[$i] 缺少 v（数值数组）"
            // 长度不齐会让汇总错行——这是交叉表最容易出的静默错误
            if (v.length() != cols.length())
                return false to "rows[$i].v 有 ${v.length()} 个值，但 cols 有 ${cols.length()} 列——汇总会错行"
            for (c in 0 until v.length()) {
                val d = v.optDouble(c, Double.NaN)
                if (d.isNaN()) return false to "rows[$i].v[$c] 不是数值：${v.opt(c)}"
            }
        }
        return true to ""
    }

    private fun autocomplete(o: JSONObject): Pair<Boolean, String> {
        val opts = o.optJSONArray("options")
            ?: return false to "zx_autocomplete 缺少 options"
        if (opts.length() == 0) return false to "zx_autocomplete 的 options 为空，输什么都没候选"
        if (opts.length() > 30) return false to "候选最多 30 个，实际 ${opts.length()} 个（列表只显示前 6 个）"
        val seen = mutableSetOf<String>()
        for (i in 0 until opts.length()) {
            val op = opts.optJSONObject(i)
            val t = op?.optString("t") ?: opts.optString(i)
            if (t.isBlank()) return false to "options[$i] 既不是非空字符串，也不是含 t 的对象"
            if (!seen.add(t))
                return false to "options[$i]=\"$t\" 重复——候选里两项一样，用户不知道该选哪个"
            if (op != null && op.optString("k").isBlank())
                return false to "options[$i] 是对象但缺 k（回传键）"
        }
        return true to ""
    }

    private fun spin(o: JSONObject): Pair<Boolean, String> {
        val sectors = o.optJSONArray("sectors")
            ?: return false to "zx_spin 缺少 sectors（扇区）"
        // 扇区数决定吸附角：2 个扇区吸附后只有两种结果，不如直接用 zx_choice
        if (sectors.length() < 3)
            return false to "转盘至少 3 个扇区，实际 ${sectors.length()} 个（少于 3 个不如用 zx_choice 直接选）"
        if (sectors.length() > 8) return false to "转盘最多 8 个扇区，实际 ${sectors.length()} 个"
        for (i in 0 until sectors.length()) {
            if (sectors.optString(i).isBlank()) return false to "sectors[$i] 是空字符串"
        }
        return true to ""
    }

    private fun barcode(o: JSONObject): Pair<Boolean, String> {
        val code = o.optString("code")
        if (code.isBlank()) return false to "zx_barcode 缺少 code（编码内容）"
        if (code.length < 4)
            return false to "zx_barcode 的 code=\"$code\" 只有 ${code.length} 位，条数太少扫不出来（至少 4 位）"
        if (code.length > 24) return false to "zx_barcode 的 code 最多 24 位，实际 ${code.length} 位"
        if (!code.matches(Regex("[A-Za-z0-9\\-.]+")))
            return false to "zx_barcode 的 code=\"$code\" 含非法字符——Code128 只支持字母数字与 . -"
        return true to ""
    }

    private fun dualist(o: JSONObject): Pair<Boolean, String> {
        val pairs = o.optJSONArray("pairs")
            ?: return false to "zx_dualist 缺少 pairs（对比对）"
        if (pairs.length() == 0) return false to "zx_dualist 的 pairs 为空"
        if (pairs.length() > 10) return false to "对比最多 10 行，实际 ${pairs.length()} 行"
        var diff = 0
        for (i in 0 until pairs.length()) {
            val p = pairs.optJSONObject(i) ?: return false to "pairs[$i] 不是对象，应为 {\"a\":\"原\",\"b\":\"新\"}"
            if (!p.has("a") || !p.has("b"))
                return false to "pairs[$i] 需要同时有 a 和 b——只有一边没法比"
            if (p.optString("a") != p.optString("b")) diff++
        }
        // 全部相同还用对比组件，说明数据没给对
        if (diff == 0)
            return false to "所有行的 a 和 b 都一样，没有差异可标——确认数据是否给错"
        return true to ""
    }

    private fun scrubber(o: JSONObject): Pair<Boolean, String> {
        val min = o.optDouble("min", 0.0); val max = o.optDouble("max", 100.0)
        if (max <= min) return false to "zx_scrubber 的 max($max) 必须大于 min($min)，没有可微调的区间"
        if (o.has("v")) {
            val v = o.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "zx_scrubber 的 v 不是数值"
            if (v < min || v > max) return false to "zx_scrubber 的 v=$v 超出 [$min,$max]"
        }
        return true to ""
    }

    private fun bento(o: JSONObject): Pair<Boolean, String> {
        val cells = o.optJSONArray("cells")
            ?: return false to "zx_bento 缺少 cells"
        if (cells.length() < 2) return false to "Bento 至少 2 块，实际 ${cells.length()} 块"
        if (cells.length() > 6) return false to "Bento 最多 6 块，实际 ${cells.length()} 块（再多大块优势就没了）"
        var big = 0
        for (i in 0 until cells.length()) {
            val c = cells.optJSONObject(i) ?: return false to "cells[$i] 不是对象"
            if (c.optString("t").isBlank()) return false to "cells[$i] 缺少 t（标题）"
            if (c.optString("k").isBlank()) return false to "cells[$i] 缺少 k（回传键）"
            val span = c.optInt("span", 1)
            if (span !in 1..2) return false to "cells[$i].span=$span 只能是 1 或 2"
            if (span == 2) big++
        }
        // span=2 的块必须是偶数个位置才能排满，奇数会留一个洞
        if (big * 2 > cells.length())
            return false to "有 $big 个 span=2 的大块，但总共只有 ${cells.length()} 块——排布会留空洞（大块要成对出现）"
        return true to ""
    }

    private fun minmap(o: JSONObject): Pair<Boolean, String> {
        val blocks = o.optJSONArray("blocks")
            ?: return false to "zx_minmap 缺少 blocks"
        if (blocks.length() == 0) return false to "zx_minmap 的 blocks 为空"
        if (blocks.length() > 24) return false to "缩略图最多 24 块，实际 ${blocks.length()} 块"
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: return false to "blocks[$i] 不是对象"
            if (b.optString("t").isBlank())
                return false to "blocks[$i] 缺少 t（块说明）——缩略图点上去不知道跳到哪"
            val k = b.optString("k", "body")
            if (k !in setOf("h1", "code", "img", "body"))
                return false to "blocks[$i].k=\"$k\" 只能是 h1 / code / img / body"
        }
        val at = o.optInt("at", 0)
        if (at !in 0 until blocks.length())
            return false to "zx_minmap 的 at=$at 超出 0-${blocks.length() - 1}，视口框画在图外"
        return true to ""
    }

    private fun statelist(o: JSONObject): Pair<Boolean, String> {
        val states = o.optJSONArray("states")
            ?: return false to "zx_statelist 缺少 states"
        if (states.length() < 2) return false to "状态流至少 2 个状态，实际 ${states.length()} 个"
        if (states.length() > 8) return false to "状态流最多 8 个，实际 ${states.length()} 个"
        for (i in 0 until states.length()) {
            val s = states.optJSONObject(i) ?: return false to "states[$i] 不是对象"
            if (s.optString("n").isBlank()) return false to "states[$i] 缺少 n（状态名）"
        }
        val at = o.optInt("at", 0)
        if (at !in 0 until states.length())
            return false to "zx_statelist 的 at=$at 超出 0-${states.length() - 1}，会没有「当前」节点"
        return true to ""
    }

    private fun legend(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_legend 缺少 items"
        if (items.length() < 2) return false to "图例至少 2 项，实际 ${items.length()} 项（1 项不需要开关）"
        if (items.length() > 8) return false to "图例最多 8 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: return false to "items[$i] 不是对象"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（图例名）"
            if (it.optString("k").isBlank()) return false to "items[$i] 缺少 k（回传键）"
        }
        // 全部 off 会让图表变空——渲染时兜底，但下发时就该提醒
        val onCount = (0 until items.length()).count {
            items.optJSONObject(it)?.optBoolean("on", true) ?: true }
        if (onCount == 0)
            return false to "所有图例都是 on=false，图表会变空。至少留一项为 true（渲染时会强制保一个）。"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_tour" -> """
            {"type":"zx_tour","at":0,"steps":[
              {"t":"先建依赖","d":"compileOnly 引契约包"}",
              {"t":"再登记工具","d":"在 entry 里注册 11 个扩展点"}",
              {"t":"最后签名","d":"对齐宿主 SHA-256"}]}""".trimIndent()
        "zx_swipe" -> """
            {"type":"zx_swipe","items":[
              {"t":"修复首页崩溃","acts":[{"k":"fix","t":"处理"}",{"k":"del","t":"删除","danger":true}]}]}""".trimIndent()
        "zx_matrix" -> """
            {"type":"zx_matrix","cols":["iOS","Android","Web"],
             "rows":[{"k":"crash","n":"崩溃","v":[12,31,4]},{"k":"slow","n":"卡顿","v":[8,15,22]}]}""".trimIndent()
        "zx_autocomplete" -> """
            {"type":"zx_autocomplete","hint":"搜索组件",
             "options":[{"k":"zx_chart","t":"图表","d":"数据"}",{"k":"zx_poll","t":"投票","d":"输入"}"]}""".trimIndent()
        "zx_spin" -> """
            {"type":"zx_spin","sectors":["一等奖","二等奖","三等奖","谢谢参与"]}""".trimIndent()
        "zx_barcode" -> """
            {"type":"zx_barcode","code":"ZORV-2026-0417"}""".trimIndent()
        "zx_dualist" -> """
            {"type":"zx_dualist","leftTitle":"修改前","rightTitle":"修改后",
             "pairs":[{"a":"minSdk 23","b":"minSdk 26"},{"a":"Kotlin 1.9","b":"Kotlin 1.9"}]}""".trimIndent()
        "zx_scrubber" -> """
            {"type":"zx_scrubber","label":"间距","min":0,"max":48,"v":16}""".trimIndent()
        "zx_bento" -> """
            {"type":"zx_bento","cells":[
              {"k":"dau","t":"日活","v":"12.4k","span":2},
              {"k":"crash","t":"崩溃率","v":"0.42%"},{"k":"lat","t":"延迟","v":"86ms"}]}""".trimIndent()
        "zx_minmap" -> """
            {"type":"zx_minmap","at":2,
             "blocks":[{"k":"h1","t":"概述"}",{"k":"body","t":"背景"}",
                       {"k":"code","t":"实现"}",{"k":"img","t":"架构图"}"]}""".trimIndent()
        "zx_statelist" -> """
            {"type":"zx_statelist","at":1,"states":[
              {"n":"待审核","d":"提交后等待"}",{"n":"审核中","d":"人工复核"}",
              {"n":"已通过","d":"可发布"}"]}""".trimIndent()
        "zx_legend" -> """
            {"type":"zx_legend","items":[
              {"k":"ios","n":"iOS","on":true},{"k":"and","n":"Android","on":true},
              {"k":"web","n":"Web","on":false}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_tour"         -> "steps[{t,d?,tag?}] + at  2-8 步 · at 必须在范围内"
        "zx_swipe"        -> "items[{t,acts[{k,t,danger?}]}]  最多 8 行 · 每行 1-3 动作"
        "zx_matrix"       -> "cols[] 2-5 + rows[{k,n,v[]}]  v 长度必须等于 cols"
        "zx_autocomplete" -> "options[{k,t,d?}]  最多 30 · 不可重复 · 选中才回传"
        "zx_spin"         -> "sectors[]  3-8 个 · 松手吸附到扇区中心"
        "zx_barcode"      -> "code  4-24 位 · 仅字母数字与 . -"
        "zx_dualist"      -> "pairs[{a,b}]  最多 10 行 · 至少一行有差异"
        "zx_scrubber"     -> "min,max,v?  增量式拖动，松手回传"
        "zx_bento"        -> "cells[{k,t,v?,span?(1-2)}]  2-6 块 · 大块须成对"
        "zx_minmap"       -> "blocks[{k,t}] + at  k ∈ h1/code/img/body"
        "zx_statelist"    -> "states[{n,d?}] + at  2-8 个 · 连接线表达顺序"
        "zx_legend"       -> "items[{k,n,on?}]  2-8 项 · 至少一项 on=true"
        else -> "未注册组件：$component"
    }
}
