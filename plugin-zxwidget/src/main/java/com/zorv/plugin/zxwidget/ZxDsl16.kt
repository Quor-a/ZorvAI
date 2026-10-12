package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 第十六批组件（时间 / 对比 / 状态机）的校验 + 示例 + 规格。
 *
 * 这批查的是**「别让用户自己算」这一条有没有落实**：
 *
 *   · 打分组没给 scores，算不出总分 → 用户要在脑子里加权求和
 *   · 打分组只有 1 个方案 → 没有可比对象，"胜出"是假的
 *   · 状态机 at 越界 → 当前状态不存在，出边全空
 *   · 状态机有不可达状态 → 那个节点永远是灰的
 *   · A/B 只有一个变体 → "提升"算不出来
 *   · 检查清单 after 指向自己或越界 → 前置永远满足不了，那步永远勾不上
 *
 * 与前面几批的差别：这批的约束大多不是"画不出来"，
 * 而是"画得出来但结论是错的/没有的"。
 * 打分组缺 scores 照样能画出表格，只是没有总分——
 * 用户会以为那个数字不存在，而不是以为数据没给。
 */
object ZxDsl16 {

    val ALL = listOf(
        "zx_weekgrid", "zx_pulse", "zx_scoreboard", "zx_abtest",
        "zx_statemachine", "zx_checkflow", "zx_branch", "zx_sla",
        "zx_repost", "zx_thread", "zx_splitflap", "zx_runbook"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_weekgrid"    -> weekgrid(root)
        "zx_pulse"       -> pulse(root)
        "zx_scoreboard"  -> scoreboard(root)
        "zx_abtest"      -> abtest(root)
        "zx_statemachine"-> statemachine(root)
        "zx_checkflow"   -> checkflow(root)
        "zx_branch"      -> branch(root)
        "zx_sla"         -> sla(root)
        "zx_repost"      -> repost(root)
        "zx_thread"      -> thread(root)
        "zx_splitflap"   -> splitflap(root)
        "zx_runbook"     -> runbook(root)
        else -> false to "未注册组件：$component"
    }

    private fun weekgrid(o: JSONObject): Pair<Boolean, String> {
        val days = o.optJSONArray("days")
            ?: return false to "zx_weekgrid 缺少 days"
        val slots = o.optJSONArray("slots")
            ?: return false to "zx_weekgrid 缺少 slots"
        if (days.length() < 2) return false to "周网格至少 2 天，实际 ${days.length()} 天"
        if (days.length() > 7) return false to "周网格最多 7 天，实际 ${days.length()} 天"
        if (slots.length() < 2) return false to "周网格至少 2 个时段，实际 ${slots.length()} 个"
        if (slots.length() > 6) return false to "周网格最多 6 个时段，实际 ${slots.length()} 个"
        for (i in 0 until days.length())
            if (days.optString(i).isBlank()) return false to "days[$i] 是空字符串"
        for (i in 0 until slots.length())
            if (slots.optString(i).isBlank()) return false to "slots[$i] 是空字符串"
        val nowD = o.optInt("nowD", -1)
        val nowS = o.optInt("nowS", -1)
        if (nowD >= days.length() || nowS >= slots.length())
            return false to "nowD=$nowD / nowS=$nowS 越界（共 ${days.length()} 天 ${slots.length()} 时段）——当前时段标记会画到表外"
        val cells = o.optJSONArray("cells")
        if (cells != null) {
            if (cells.length() > days.length() * slots.length())
                return false to "cells 有 ${cells.length()} 个，但格子只有 ${days.length() * slots.length()} 个"
            for (k in 0 until cells.length()) {
                val c = cells.optJSONObject(k)
                    ?: return false to "cells[$k] 不是对象，应为 {\"d\":,\"s\":,\"t\":}"
                val d = c.optInt("d", -1); val s = c.optInt("s", -1)
                if (d < 0 || s < 0) return false to "cells[$k] 缺少 d 或 s"
                if (d >= days.length() || s >= slots.length())
                    return false to "cells[$k] 的 d=$d s=$s 越界"
            }
        }
        return true to ""
    }

    private fun pulse(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_pulse 缺少 items"
        if (items.length() < 3) return false to "脉冲图至少 3 个时段，实际 ${items.length()} 个"
        if (items.length() > 30) return false to "脉冲图最多 30 个时段，实际 ${items.length()} 个"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":时段名,\"v\":强度}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（时段名）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v（强度）"
            if (v < 0) return false to "items[$i].v=$v 为负，柱高算不出来"
        }
        if ((0 until items.length()).all { items.optJSONObject(it)?.optDouble("v", 0.0) == 0.0 })
            return false to "所有 v 都是 0——没有强度数据，均值线也画不出来"
        return true to ""
    }

    private fun scoreboard(o: JSONObject): Pair<Boolean, String> {
        val opts = o.optJSONArray("opts")
            ?: return false to "zx_scoreboard 缺少 opts（方案名列表）"
        val crit = o.optJSONArray("crit")
            ?: return false to "zx_scoreboard 缺少 crit（评分维度）"
        if (opts.length() < 2)
            return false to "打分组至少 2 个方案，实际 ${opts.length()} 个——1 个方案没有可比对象，「胜出」是假的"
        if (opts.length() > 4) return false to "打分组最多 4 个方案，实际 ${opts.length()} 个"
        if (crit.length() == 0) return false to "zx_scoreboard 的 crit 为空"
        if (crit.length() > 5) return false to "评分维度最多 5 个，实际 ${crit.length()} 个"
        var wSum = 0.0
        for (i in 0 until crit.length()) {
            val c = crit.optJSONObject(i)
                ?: return false to "crit[$i] 不是对象，应为 {\"n\":维度名,\"w\":权重}"
            if (c.optString("n").isBlank()) return false to "crit[$i] 缺少 n"
            val w = c.optDouble("w", 1.0)
            if (w < 0) return false to "crit[$i].w=$w 为负"
            wSum += w
        }
        // 权重全 0 会除零，总分算不出来
        if (wSum <= 0) return false to "所有维度权重都是 0——加权总分除零"
        for (i in 0 until opts.length())
            if (opts.optString(i).isBlank()) return false to "opts[$i] 是空字符串"
        val scores = o.optJSONArray("scores")
            ?: return false to "zx_scoreboard 缺少 scores——没有分数就算不出总分，用户要在脑子里加权求和"
        if (scores.length() != opts.length())
            return false to "scores 有 ${scores.length()} 组，但 opts 有 ${opts.length()} 个——对不上"
        for (i in 0 until scores.length()) {
            val row = scores.optJSONArray(i)
                ?: return false to "scores[$i] 不是数组，应为每个维度一个分值"
            if (row.length() != crit.length())
                return false to "scores[$i] 有 ${row.length()} 个分，但 crit 有 ${crit.length()} 个维度"
            for (k in 0 until row.length()) {
                val v = row.optDouble(k, Double.NaN)
                if (v.isNaN()) return false to "scores[$i][$k] 不是数值"
                if (v < 0 || v > 10)
                    return false to "scores[$i][$k]=$v 超出 0-10（分值按 10 分制）"
            }
        }
        return true to ""
    }

    private fun abtest(o: JSONObject): Pair<Boolean, String> {
        val vs = o.optJSONArray("variants")
            ?: return false to "zx_abtest 缺少 variants"
        if (vs.length() < 2)
            return false to "A/B 至少 2 个变体，实际 ${vs.length()} 个——1 个变体算不出「提升」，提升是相对第 1 个算的"
        if (vs.length() > 4) return false to "A/B 最多 4 个变体，实际 ${vs.length()} 个"
        for (i in 0 until vs.length()) {
            val v = vs.optJSONObject(i)
                ?: return false to "variants[$i] 不是对象，应为 {\"n\":名,\"n_\":样本量,\"cr\":转化率}"
            if (v.optString("n").isBlank()) return false to "variants[$i] 缺少 n"
            if (v.optInt("n_", 0) <= 0)
                return false to "variants[$i] 的 n_（样本量）必须为正——否则转化率没有意义"
            val cr = v.optDouble("cr", Double.NaN)
            if (cr.isNaN()) return false to "variants[$i] 缺少 cr（转化率，百分数）"
            if (cr < 0 || cr > 100) return false to "variants[$i].cr=$cr 超出 0-100"
        }
        val base = vs.optJSONObject(0)?.optDouble("cr", 0.0) ?: 0.0
        if (base <= 0)
            return false to "第 1 个变体的 cr 为 0——它是基准，基准为 0 算不出提升"
        return true to ""
    }

    private fun statemachine(o: JSONObject): Pair<Boolean, String> {
        val states = o.optJSONArray("states")
            ?: return false to "zx_statemachine 缺少 states"
        if (states.length() < 2) return false to "状态机至少 2 个状态，实际 ${states.length()} 个"
        if (states.length() > 8) return false to "状态机最多 8 个状态，实际 ${states.length()} 个"
        val n = states.length()
        for (i in 0 until n) {
            val s = states.optJSONObject(i)
                ?: return false to "states[$i] 不是对象，应为 {\"k\":键,\"n\":显示名}"
            if (s.optString("k").isBlank()) return false to "states[$i] 缺少 k"
            if (s.optString("n").isBlank()) return false to "states[$i] 缺少 n"
        }
        val trans = o.optJSONArray("trans")
        if (trans == null || trans.length() == 0)
            return false to "zx_statemachine 缺少 trans——没有迁移就走不动"
        if (trans.length() > 24) return false to "迁移最多 24 条，实际 ${trans.length()} 条"
        for (k in 0 until trans.length()) {
            val t = trans.optJSONObject(k)
                ?: return false to "trans[$k] 不是对象，应为 {\"f\":从,\"t\":到,\"a\":动作}"
            val f = t.optInt("f", -1); val to = t.optInt("t", -1)
            if (f < 0 || to < 0) return false to "trans[$k] 缺少 f 或 t"
            if (f >= n || to >= n)
                return false to "trans[$k] 的 f=$f t=$to 越界（共 $n 个状态）"
            if (f == to) return false to "trans[$k] 是自迁移（f=t=$f）"
        }
        val at = o.optInt("at", 0)
        if (at < 0 || at >= n)
            return false to "at=$at 越界（共 $n 个状态，下标 0-${n - 1}）——当前状态不存在，出边全空"
        // 不可达状态：那个节点永远是灰的
        val reach = mutableSetOf(at)
        repeat(n) {
            for (k in 0 until trans.length()) {
                val t = trans.optJSONObject(k) ?: continue
                if (t.optInt("f") in reach) reach.add(t.optInt("t"))
            }
        }
        val dead = (0 until n).count { it !in reach }
        if (dead > 0)
            return false to "有 $dead 个状态从当前状态走不到——它们永远是灰的，用户会以为能点"
        return true to ""
    }

    private fun checkflow(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_checkflow 缺少 items"
        if (items.length() == 0) return false to "zx_checkflow 的 items 为空"
        if (items.length() > 8) return false to "检查清单最多 8 项，实际 ${items.length()} 项"
        val n = items.length()
        for (i in 0 until n) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":,\"req\":,\"after\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val after = it.optInt("after", -1)
            // after 指向自己或后面：那一步永远满足不了前置，永远勾不上
            if (after >= i)
                return false to "items[$i].after=$after 必须指向前面的项（当前是第 $i 项）——指向自己或后面，前置永远满足不了"
            if (after >= n)
                return false to "items[$i].after=$after 越界（共 $n 项）"
        }
        // 依赖成环：after 严格小于 i，天然无环，这里只查链长
        return true to ""
    }

    private fun branch(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_branch 缺少 rows"
        if (rows.length() == 0) return false to "zx_branch 的 rows 为空"
        if (rows.length() > 4) return false to "分支图最多 4 行，实际 ${rows.length()} 行"
        val cols = o.optInt("cols", 6)
        if (cols !in 2..12) return false to "zx_branch 的 cols=$cols 超出 2-12"
        val head = o.optInt("head", -1)
        if (head >= cols)
            return false to "head=$head 越界（共 $cols 列）——HEAD 标记画到表外"
        for (i in 0 until rows.length())
            if (rows.optJSONObject(i)?.optString("n").isNullOrBlank())
                return false to "rows[$i] 缺少 n（分支名）"
        val cells = o.optJSONArray("cells")
        if (cells != null) {
            if (cells.length() > rows.length() * cols)
                return false to "cells 有 ${cells.length()} 个，但格子只有 ${rows.length() * cols} 个"
            for (k in 0 until cells.length()) {
                val c = cells.optJSONObject(k)
                    ?: return false to "cells[$k] 不是对象，应为 {\"r\":,\"c\":,\"k\":\"commit|branch|merge\",\"t\":}"
                val r = c.optInt("r", -1); val cc = c.optInt("c", -1)
                if (r < 0 || cc < 0) return false to "cells[$k] 缺少 r 或 c"
                if (r >= rows.length() || cc >= cols)
                    return false to "cells[$k] 的 r=$r c=$cc 越界"
                val kind = c.optString("k", "commit")
                if (kind !in setOf("commit", "branch", "merge"))
                    return false to "cells[$k].k=\"$kind\" 只能是 commit / branch / merge"
            }
        }
        return true to ""
    }

    private fun sla(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_sla 缺少 items"
        if (items.length() == 0) return false to "zx_sla 的 items 为空"
        if (items.length() > 6) return false to "SLA 最多 6 项，实际 ${items.length()} 项"
        var breach = false
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":,\"used\":,\"budget\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val budget = it.optDouble("budget", Double.NaN)
            if (budget.isNaN()) return false to "items[$i] 缺少 budget"
            if (budget <= 0)
                return false to "items[$i].budget=$budget 必须为正——否则算不出消耗比例"
            val used = it.optDouble("used", Double.NaN)
            if (used.isNaN()) return false to "items[$i] 缺少 used"
            if (used < 0) return false to "items[$i].used=$used 为负"
            if (used >= budget) breach = true
        }
        // 全都没超标也合法，只是说明当前没风险——不拦
        // breach 只用于说明当前有无风险，不拦截
        return true to ""
    }

    private fun repost(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("text").isBlank() && !o.has("quote"))
            return false to "zx_repost 既没 text 也没 quote——没有任何可显示的内容"
        if (o.optString("by").isBlank())
            return false to "zx_repost 缺少 by（作者）——转发卡上会是一片空白"
        if (o.optInt("likes", 0) < 0 || o.optInt("reposts", 0) < 0 ||
            o.optInt("replies", 0) < 0)
            return false to "zx_repost 的计数不能为负"
        val q = o.optJSONObject("quote")
        if (q != null && q.optString("text").isBlank() && q.optString("by").isBlank())
            return false to "zx_repost 的 quote 既没 text 也没 by——引用块会是空的"
        return true to ""
    }

    private fun thread(o: JSONObject): Pair<Boolean, String> {
        val posts = o.optJSONArray("posts")
            ?: return false to "zx_thread 缺少 posts"
        if (posts.length() == 0) return false to "zx_thread 的 posts 为空"
        if (posts.length() > 8) return false to "话题串最多 8 条，实际 ${posts.length()} 条"
        for (i in 0 until posts.length()) {
            val p = posts.optJSONObject(i)
                ?: return false to "posts[$i] 不是对象，应为 {\"by\":,\"at\":,\"t\":正文,\"d\":层级}"
            if (p.optString("by").isBlank()) return false to "posts[$i] 缺少 by"
            if (p.optString("t").isBlank()) return false to "posts[$i] 缺少 t（正文）"
            val d = p.optInt("d", 0)
            if (d < 0 || d > 3) return false to "posts[$i].d=$d 超出 0-3（最多 4 层缩进）"
            // 首条必须是最外层，否则缩进线画在第 0 层左边，看不出是谁回复谁
            if (i == 0 && d != 0)
                return false to "首条的 d=$d，必须是 0——否则看不出谁回复谁"
            if (i > 0) {
                val prev = posts.optJSONObject(i - 1)?.optInt("d", 0) ?: 0
                if (d > prev + 1)
                    return false to "posts[$i].d=$d 比上一条深了不止 1 层（上一条 $prev）——层级跳级，缩进线会断"
            }
        }
        return true to ""
    }

    private fun splitflap(o: JSONObject): Pair<Boolean, String> {
        val digits = o.optJSONArray("digits")
            ?: return false to "zx_splitflap 缺少 digits"
        if (digits.length() == 0) return false to "zx_splitflap 的 digits 为空"
        if (digits.length() > 8) return false to "翻页牌最多 8 位，实际 ${digits.length()} 位"
        for (i in 0 until digits.length()) {
            val d = digits.optJSONObject(i)
                ?: return false to "digits[$i] 不是对象，应为 {\"v\":\"0\"-\"9\",\"n\":标签}"
            val v = d.optString("v")
            if (v.length != 1)
                return false to "digits[$i].v=\"$v\" 必须是单个字符（一位一牌）"
        }
        return true to ""
    }

    private fun runbook(o: JSONObject): Pair<Boolean, String> {
        val steps = o.optJSONArray("steps")
            ?: return false to "zx_runbook 缺少 steps"
        if (steps.length() == 0) return false to "zx_runbook 的 steps 为空"
        if (steps.length() > 8) return false to "操作手册最多 8 步，实际 ${steps.length()} 步"
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i)
                ?: return false to "steps[$i] 不是对象，应为 {\"n\":步骤名,\"cmd\":命令,\"note\":,\"risk\":}"
            if (s.optString("n").isBlank()) return false to "steps[$i] 缺少 n（步骤名）"
            if (s.optString("cmd").isBlank())
                return false to "steps[$i] 缺少 cmd（要执行的内容）——用户不知道这一步要做什么"
        }
        val at = o.optInt("at", 0)
        if (at < 0 || at >= steps.length())
            return false to "at=$at 越界（共 ${steps.length()} 步）——当前步骤不存在"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_weekgrid" -> """
            {"type":"zx_weekgrid","nowD":2,"nowS":1,
             "days":["一","二","三","四","五","六","日"],
             "slots":["早","午","晚"],
             "cells":[{"d":0,"s":0,"t":"张"}",{"d":2,"s":1,"t":"李"}",
                      {"d":5,"s":0,"t":"王"}",{"d":5,"s":1,"t":"王"}"]}""".trimIndent()
        "zx_pulse" -> """
            {"type":"zx_pulse","items":[
              {"n":"00","v":12},{"n":"04","v":5},{"n":"08","v":31},
              {"n":"12","v":48},{"n":"16","v":39},{"n":"20","v":22}]}""".trimIndent()
        "zx_scoreboard" -> """
            {"type":"zx_scoreboard","opts":["A","B","C"],
             "crit":[{"n":"成本","w":2},{"n":"性能","w":1},{"n":"可维护","w":1.5}],
             "scores":[[8,7,6],[6,9,7],[7,6,9]]}""".trimIndent()
        "zx_abtest" -> """
            {"type":"zx_abtest","sig":"p=0.03",
             "variants":[{"n":"对照组","n_":12000,"cr":2.4},
                         {"n":"新版","n_":11800,"cr":3.1}]}""".trimIndent()
        "zx_statemachine" -> """
            {"type":"zx_statemachine","at":0,"states":[
              {"k":"idle","n":"空闲"}",{"k":"run","n":"执行中"}",
              {"k":"pause","n":"已暂停"}",{"k":"done","n":"完成"}"],
             "trans":[{"f":0,"t":1,"a":"start"},{"f":1,"t":2,"a":"pause"},
                      {"f":2,"t":1,"a":"resume"},{"f":1,"t":3,"a":"finish"}]}""".trimIndent()
        "zx_checkflow" -> """
            {"type":"zx_checkflow","n":"发布前检查","items":[
              {"n":"跑测试","req":true},
              {"n":"打标签","req":true,"after":0},
              {"n":"更新文档","req":false,"after":1}]}""".trimIndent()
        "zx_branch" -> """
            {"type":"zx_branch","cols":6,"head":4,"rows":[
              {"n":"main"},{"n":"feature"}],
             "cells":[{"r":0,"c":0,"k":"commit"},{"r":1,"c":1,"k":"branch"},
                      {"r":1,"c":3,"k":"commit"},{"r":0,"c":4,"k":"merge"}]}""".trimIndent()
        "zx_sla" -> """
            {"type":"zx_sla","items":[
              {"n":"可用性","used":99.2,"budget":100},
              {"n":"错误预算","used":118,"budget":100}]}""".trimIndent()
        "zx_repost" -> """
            {"type":"zx_repost","by":"同事","at":"2 小时前",
             "text":"这版把重试改成 5 次了，感觉偏多。","likes":12,"reposts":3,
             "quote":{"by":"我","text":"超时放宽到 30 秒。"}}""".trimIndent()
        "zx_thread" -> """
            {"type":"zx_thread","posts":[
              {"by":"我","at":"09:00","t":"这个方案行吗","d":0},
              {"by":"同事","at":"09:12","t":"成本偏高","d":1},
              {"by":"我","at":"09:30","t":"那换成 B","d":2}]}""".trimIndent()
        "zx_splitflap" -> """
            {"type":"zx_splitflap","digits":[
              {"v":"1","n":"千"}",{"v":"6","n":"百"}",
              {"v":"7","n":"十"}",{"v":"3","n":"个"}"]}""".trimIndent()
        "zx_runbook" -> """
            {"type":"zx_runbook","n":"回滚流程","at":0,"steps":[
              {"n":"确认当前版本","cmd":"cat VERSION"},
              {"n":"切到上一版","cmd":"git checkout HEAD~1","risk":true,
               "note":"会丢掉未提交的改动"}"]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_weekgrid"    -> "days 2-7 + slots 2-6 + cells?[{d,s,t}] · nowD/nowS 可选"
        "zx_pulse"       -> "items[{n,v}]  3-30 · v ≥ 0 且不可全 0"
        "zx_scoreboard"  -> "opts 2-4 + crit ≤5 + scores[opts][crit] 0-10"
        "zx_abtest"      -> "variants[{n,n_>0,cr}]  2-4 · 首个 cr 须 >0"
        "zx_statemachine"-> "states 2-8 + trans ≤24 + at · 无不可达状态"
        "zx_checkflow"   -> "items[{n,req?,after?}]  ≤8 · after 须指向前面"
        "zx_branch"      -> "rows ≤4 + cols 2-12 + cells?[{r,c,k}]"
        "zx_sla"         -> "items[{n,used,budget>0}]  ≤6"
        "zx_repost"      -> "by + (text | quote) · 计数不可为负"
        "zx_thread"      -> "posts[{by,t,d 0-3}]  ≤8 · 首条 d=0，不可跳级"
        "zx_splitflap"   -> "digits[{v,n?}]  ≤8 · v 是单个字符"
        "zx_runbook"     -> "steps[{n,cmd,note?,risk?}]  ≤8 · at 不越界"
        else -> "未注册组件：$component"
    }
}
