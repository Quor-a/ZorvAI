package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第五批组件（输入型）的校验规则 + 目录 + 示例 DSL + 字段规格。
 *
 * 输入型组件的校验重点跟展示型不同——除了结构正确性，
 * 更要看"用户填完能不能交出去"：
 *   · 投票没有选项 → 没得选
 *   · 口令 len 不在 3-8 → 格子放不下或太散
 *   · 滑块组 budget <= 0 → 分配比例算不出来
 *   · 时间 step 不能整除 60 → 滚到末尾跳不到 59
 * 这些都不是渲染错误，是"用户操作了但得不到结果"。
 */
object ZxDsl5 {

    val ALL = listOf(
        "zx_poll", "zx_transfer", "zx_chips", "zx_pin",
        "zx_dial", "zx_joystick", "zx_timepicker", "zx_slidergroup",
        "zx_sortable", "zx_cardflip", "zx_comment", "zx_seek"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_poll"        -> poll(root)
        "zx_transfer"    -> transfer(root)
        "zx_chips"       -> chips(root)
        "zx_pin"         -> pin(root)
        "zx_dial"        -> dial(root)
        "zx_joystick"    -> joystick(root)
        "zx_timepicker"  -> timepicker(root)
        "zx_slidergroup" -> slidergroup(root)
        "zx_sortable"    -> sortable(root)
        "zx_cardflip"    -> cardflip(root)
        "zx_comment"     -> comment(root)
        "zx_seek"        -> seek(root)
        else -> false to "未注册组件：$component"
    }

    private fun poll(o: JSONObject): Pair<Boolean, String> {
        val opts = o.optJSONArray("options")
            ?: return false to "zx_poll 缺少 options（选项数组）"
        if (opts.length() < 2) return false to "投票至少 2 个选项，实际 ${opts.length()} 个"
        if (opts.length() > 6) return false to "投票最多 6 个选项，实际 ${opts.length()} 个"
        var hasVote = false
        for (i in 0 until opts.length()) {
            val op = opts.optJSONObject(i)
                ?: return false to "options[$i] 不是对象"
            if (op.optString("t").isBlank())
                return false to "options[$i] 缺少 t（选项文字）"
            if (op.optString("k").isBlank())
                return false to "options[$i] 缺少 k（回传键）"
            if (op.has("v")) hasVote = true
        }
        // 给了票数就必须全给，否则分布算出来是错的
        if (hasVote) {
            for (i in 0 until opts.length()) {
                val op = opts.optJSONObject(i) ?: continue
                val v = op.optDouble("v", Double.NaN)
                if (v.isNaN())
                    return false to "options[$i] 没给 v，但其他选项给了——票数分布会算错（要么都给，要么都不给）"
                if (v < 0) return false to "options[$i].v=$v 是负数"
            }
        }
        return true to ""
    }

    private fun transfer(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_transfer 缺少 items"
        if (items.length() == 0) return false to "zx_transfer 的 items 为空"
        if (items.length() > 24) return false to "穿梭框最多 24 项，实际 ${items.length()} 项"
        var right = 0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象"
            if (it.optString("n").isBlank())
                return false to "items[$i] 缺少 n（显示名）"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（回传键）"
            val side = it.optString("side", "left")
            if (side !in setOf("left", "right"))
                return false to "items[$i].side 只能是 left 或 right，实际 \"$side\""
            if (side == "right") right++
        }
        if (right == items.length())
            return false to "所有项都在右侧，左侧为空——用户没有可移过去的了（不是错误，但通常不是你想要的）"
        return true to ""
    }

    private fun chips(o: JSONObject): Pair<Boolean, String> {
        val tags = o.optJSONArray("tags")
            ?: return false to "zx_chips 缺少 tags"
        if (tags.length() > 16) return false to "标签最多 16 个，实际 ${tags.length()} 个"
        val seen = mutableSetOf<String>()
        for (i in 0 until tags.length()) {
            val t = tags.optString(i)
            if (t.isBlank()) return false to "tags[$i] 是空字符串"
            if (!seen.add(t)) return false to "tags[$i]=\"$t\" 重复，选中态会互相覆盖"
            if (t.length > 12) return false to "tags[$i]=\"$t\" 长度 ${t.length} 超过 12，气泡里会换行"
        }
        return true to ""
    }

    private fun pin(o: JSONObject): Pair<Boolean, String> {
        val len = o.optInt("len", 6)
        if (len < 3 || len > 8)
            return false to "zx_pin 的 len=$len 超出 3-8（少于 3 不安全，多于 8 格子放不下）"
        val mask = o.optString("mask")
        if (mask.isNotBlank() && mask.length != 1)
            return false to "zx_pin 的 mask 只能是单个字符，实际 \"$mask\"（长度 ${mask.length}）"
        return true to ""
    }

    private fun dial(o: JSONObject): Pair<Boolean, String> {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0)
        if (max <= min)
            return false to "zx_dial 的 max($max) 必须大于 min($min)，否则旋转没有量程"
        if (o.has("v")) {
            val v = o.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "zx_dial 的 v 不是数值"
            if (v < min || v > max)
                return false to "zx_dial 的 v=$v 超出 [$min,$max]，指针会停在量程外"
        }
        return true to ""
    }

    private fun joystick(o: JSONObject): Pair<Boolean, String> {
        val dead = o.optDouble("dead", 0.08)
        if (dead < 0 || dead > 0.5)
            return false to "zx_joystick 的 dead=$dead 超出 0-0.5（死区超过一半就没法操作了）"
        return true to ""
    }

    private fun timepicker(o: JSONObject): Pair<Boolean, String> {
        val h = o.optInt("h", 9)
        if (h < 0 || h > 23) return false to "zx_timepicker 的 h=$h 超出 0-23"
        val m = o.optInt("m", 0)
        if (m < 0 || m > 59) return false to "zx_timepicker 的 m=$m 超出 0-59"
        val step = o.optInt("step", 5)
        if (step < 1 || step > 30)
            return false to "zx_timepicker 的 step=$step 超出 1-30"
        // step 不能整除 60 的话，滚到末尾跳不到 59，末尾会缺一块
        if (60 % step != 0)
            return false to "zx_timepicker 的 step=$step 不能整除 60，分钟列表末尾会缺一块（用 1/2/3/4/5/6/10/12/15/20/30）"
        if (m % step != 0)
            return false to "zx_timepicker 的 m=$m 不在 step=$step 的刻度上，初始选中项不存在"
        return true to ""
    }

    private fun slidergroup(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_slidergroup 缺少 items"
        if (items.length() < 2) return false to "滑块组至少 2 项，实际 ${items.length()} 项（1 项不需要分配）"
        if (items.length() > 5) return false to "滑块组最多 5 项，实际 ${items.length()} 项"
        val budget = o.optDouble("budget", 100.0)
        if (budget <= 0)
            return false to "zx_slidergroup 的 budget=$budget 必须为正，否则百分比算不出来"
        var sum = 0.0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象"
            if (it.optString("n").isBlank())
                return false to "items[$i] 缺少 n（名称）"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（回传键）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$i] 缺少 v（初始值）"
            if (v < 0) return false to "items[$i].v=$v 是负数"
            sum += v
        }
        // 超预算不拦（会自动 rebalance），但要告知——模型可能以为自己给对了
        if (sum > budget * 1.01)
            return false to "初始分配合计 $sum 超过预算 $budget，会被按比例摊回。要么调小，要么调大 budget。"
        return true to ""
    }

    private fun sortable(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_sortable 缺少 items"
        if (items.length() < 2) return false to "排序列表至少 2 项，实际 ${items.length()} 项（1 项不用排）"
        if (items.length() > 10) return false to "排序列表最多 10 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象"
            if (it.optString("n").isBlank())
                return false to "items[$i] 缺少 n（显示名）"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（回传键），排完序无法告诉模型顺序变了"
        }
        return true to ""
    }

    private fun cardflip(o: JSONObject): Pair<Boolean, String> {
        val cards = o.optJSONArray("cards")
            ?: return false to "zx_cardflip 缺少 cards"
        if (cards.length() == 0) return false to "zx_cardflip 的 cards 为空"
        if (cards.length() > 12) return false to "翻转卡最多 12 张，实际 ${cards.length()} 张"
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i)
                ?: return false to "cards[$i] 不是对象"
            if (c.optString("q").isBlank()) return false to "cards[$i] 缺少 q（正面问题）"
            if (c.optString("a").isBlank()) return false to "cards[$i] 缺少 a（背面答案）"
        }
        return true to ""
    }

    private fun comment(o: JSONObject): Pair<Boolean, String> {
        val cs = o.optJSONArray("comments") ?: return true to ""   // 允许空评论列表
        if (cs.length() > 8) return false to "批注最多显示 8 条，实际 ${cs.length()} 条"
        for (i in 0 until cs.length()) {
            val c = cs.optJSONObject(i)
                ?: return false to "comments[$i] 不是对象"
            if (c.optString("text").isBlank())
                return false to "comments[$i] 缺少 text（批注内容）"
            if (c.optString("who").isBlank())
                return false to "comments[$i] 缺少 who（发言人），头像和署名都没得显示"
        }
        return true to ""
    }

    private fun seek(o: JSONObject): Pair<Boolean, String> {
        val dur = o.optInt("dur", -1)
        if (dur <= 0) return false to "zx_seek 缺少 dur（总时长），或 dur <= 0"
        if (o.has("at")) {
            val at = o.optDouble("at", Double.NaN)
            if (at.isNaN()) return false to "zx_seek 的 at 不是数值"
            if (at < 0 || at > dur)
                return false to "zx_seek 的 at=$at 超出 0-$dur，滑块会停在轨道外"
        }
        val marks = o.optJSONArray("marks") ?: return true to ""
        if (marks.length() > 12) return false to "章节标记最多 12 个，实际 ${marks.length()} 个"
        for (i in 0 until marks.length()) {
            val m = marks.optJSONObject(i)
                ?: return false to "marks[$i] 不是对象，应为 {\"at\":30,\"t\":\"引言\"}"
            val at = m.optDouble("at", Double.NaN)
            if (at.isNaN()) return false to "marks[$i] 缺少 at（时间点）"
            if (at < 0 || at > dur)
                return false to "marks[$i].at=$at 超出 0-$dur，标记画在轨道外"
            if (m.optString("t").isBlank())
                return false to "marks[$i] 缺少 t（章节名），点了不知道跳到哪"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_poll" -> """
            {"type":"zx_poll","q":"这次发布选哪个时间？","multi":false,
             "options":[{"k":"fri","t":"周五晚","v":12},
                        {"k":"sat","t":"周六早","v":27},
                        {"k":"sun","t":"周日晚","v":9}]}""".trimIndent()
        "zx_transfer" -> """
            {"type":"zx_transfer","leftTitle":"可选成员","rightTitle":"已加入",
             "items":[{"k":"u1","n":"陈明"}",{"k":"u2","n":"李婷","side":"right"},
                      {"k":"u3","n":"王凯"}",{"k":"u4","n":"赵雪"}"]}""".trimIndent()
        "zx_chips" -> """
            {"type":"zx_chips","tags":["性能","内存","启动速度","崩溃","UI","网络"]}""".trimIndent()
        "zx_pin" -> """
            {"type":"zx_pin","len":6,"mask":"●"}""".trimIndent()
        "zx_dial" -> """
            {"type":"zx_dial","min":0,"max":100,"v":62,"label":"音量"}"""".trimIndent()
        "zx_joystick" -> """
            {"type":"zx_joystick","dead":0.08}""".trimIndent()
        "zx_timepicker" -> """
            {"type":"zx_timepicker","h":9,"m":30,"step":5}""".trimIndent()
        "zx_slidergroup" -> """
            {"type":"zx_slidergroup","budget":100,
             "items":[{"k":"cpu","n":"CPU","v":40},{"k":"mem","n":"内存","v":30},
                      {"k":"io","n":"IO","v":30}]}""".trimIndent()
        "zx_sortable" -> """
            {"type":"zx_sortable","items":[
              {"k":"a","n":"需求评审"}",{"k":"b","n":"设计"}",
              {"k":"c","n":"开发"}",{"k":"d","n":"测试"}"]}""".trimIndent()
        "zx_cardflip" -> """
            {"type":"zx_cardflip","cards":[
              {"q":"Compose 中 remember 的作用？","a":"在重组间保持值，配置变化会重置"}",
              {"q":"LaunchedEffect 何时取消？","a":"key 变化或离开组合时"}"]}""".trimIndent()
        "zx_comment" -> """
            {"type":"zx_comment","comments":[
              {"who":"李工","at":"10:24","text":"这里换成 rememberSaveable 更好"}"]}""".trimIndent()
        "zx_seek" -> """
            {"type":"zx_seek","dur":600,"at":120,
             "marks":[{"at":0,"t":"开场"}",{"at":180,"t":"demo"},{"at":420,"t":"QA"}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_poll"        -> "q,options[{k,t,v?}],multi?  2-6 项 · v 要么都给要么都不给"
        "zx_transfer"    -> "items[{k,n,side?}],leftTitle?,rightTitle?  最多 24 项"
        "zx_chips"       -> "tags[字符串 ≤12 字]  最多 16 个 · 不可重复"
        "zx_pin"         -> "len(3-8),mask?(单字符)  自动跳焦 · 满位回传"
        "zx_dial"        -> "min,max,v?,label?  旋转手势 · 135°~405° 量程"
        "zx_joystick"    -> "dead?(0-0.5)  输出 x/y ∈ [-1,1] · 夹到单位圆"
        "zx_timepicker"  -> "h(0-23),m,step(能整除60)  双列滚轮"
        "zx_slidergroup" -> "budget>0,items[{k,n,v}]  2-5 项 · 超预算自动摊回"
        "zx_sortable"    -> "items[{k,n}]  2-10 项 · 松手回传新顺序"
        "zx_cardflip"    -> "cards[{q,a}]  最多 12 张 · 点卡翻面"
        "zx_comment"     -> "comments[{who,text,at?}]  最多 8 条 · 可回复可表情"
        "zx_seek"        -> "dur,at?,marks?[{at,t}]  拖完松手才回传"
        else -> "未注册组件：$component"
    }
}
