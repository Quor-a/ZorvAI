package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第八批组件（表单原语 / 状态呈现 / 集合展示）的校验 + 示例 + 规格。
 *
 * 这批校验的重心跟前七批又不一样，主要是三类：
 *
 *   一、唯一性约束。推荐项只能一个、色板不能有两个同 hex、
 *      可编辑列表不能有重复行——重复会让回传有歧义
 *      （"选中第 3 行"到底是哪个）。
 *
 *   二、量程前提。计量条的 max 必须大于 min、等级刻度必须落在量程内、
 *      环形进度必须在 0-100——超出会在绘制时静默截断，用户看到的是错的数。
 *
 *   三、掩码可行性。掩码必须有占位符、占位符总数不能为 0、
 *      初值长度不能超过占位符总数——初值超了会被截断，用户以为填对了。
 */
object ZxDsl8 {

    val ALL = listOf(
        "zx_radiogroup", "zx_checklist", "zx_avatargroup", "zx_meter",
        "zx_ring", "zx_stat", "zx_swatch", "zx_notice",
        "zx_price", "zx_keyvalue", "zx_inputmask", "zx_datalist"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_radiogroup"  -> radiogroup(root)
        "zx_checklist"   -> checklist(root)
        "zx_avatargroup" -> avatargroup(root)
        "zx_meter"       -> meter(root)
        "zx_ring"        -> ring(root)
        "zx_stat"        -> stat(root)
        "zx_swatch"      -> swatch(root)
        "zx_notice"      -> notice(root)
        "zx_price"       -> price(root)
        "zx_keyvalue"    -> keyvalue(root)
        "zx_inputmask"   -> inputmask(root)
        "zx_datalist"    -> datalist(root)
        else -> false to "未注册组件：$component"
    }

    private fun radiogroup(o: JSONObject): Pair<Boolean, String> {
        val opts = o.optJSONArray("options")
            ?: return false to "zx_radiogroup 缺少 options"
        if (opts.length() < 2) return false to "单选组至少 2 项，实际 ${opts.length()} 项"
        if (opts.length() > 6) return false to "单选组最多 6 项，实际 ${opts.length()} 项"
        for (i in 0 until opts.length()) {
            val op = opts.optJSONObject(i)
                ?: return false to "options[$i] 不是对象，应为 {\"k\":键,\"t\":标题,\"d\":描述}"
            if (op.optString("t").isBlank()) return false to "options[$i] 缺少 t（标题）"
            if (op.optString("k").isBlank())
                return false to "options[$i] 缺少 k（回传键），选中了模型也不知道是哪个"
            if (op.optString("d").length > 60)
                return false to "options[$i].d 超过 60 字（实际 ${op.optString("d").length}），描述太长会把行撑高"
        }
        val sel = o.optInt("sel", -1)
        if (sel !in -1 until opts.length())
            return false to "zx_radiogroup 的 sel=$sel 超出 -1-${opts.length() - 1}"
        return true to ""
    }

    private fun checklist(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_checklist 缺少 items"
        if (items.length() == 0) return false to "zx_checklist 的 items 为空"
        if (items.length() > 10) return false to "清单最多 10 项，实际 ${items.length()} 项"
        val seen = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: return false to "items[$i] 不是对象"
            if (it.optString("t").isBlank()) return false to "items[$i] 缺少 t（项文本）"
            val k = it.optString("k").ifBlank { "i$i" }
            if (!seen.add(k))
                return false to "items[$i].k=\"$k\" 重复——勾了哪一项有歧义"
        }
        return true to ""
    }

    private fun avatargroup(o: JSONObject): Pair<Boolean, String> {
        val people = o.optJSONArray("people")
            ?: return false to "zx_avatargroup 缺少 people"
        if (people.length() == 0) return false to "zx_avatargroup 的 people 为空"
        if (people.length() > 20) return false to "头像组最多 20 人，实际 ${people.length()} 人"
        for (i in 0 until people.length()) {
            val p = people.optJSONObject(i) ?: return false to "people[$i] 不是对象"
            if (p.optString("n").isBlank())
                return false to "people[$i] 缺少 n（姓名）——头像取首字，为空会显示成问号"
        }
        val max = o.optInt("max", 5)
        if (max !in 1..6) return false to "zx_avatargroup 的 max=$max 超出 1-6（再多会挤成一排线）"
        return true to ""
    }

    private fun meter(o: JSONObject): Pair<Boolean, String> {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0)
        if (max <= min) return false to "zx_meter 的 max($max) 必须大于 min($min)，条长算不出来"
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return false to "zx_meter 缺少 v（当前值）"
        // 超出量程会静默截断——用户看到的是错误的条长，还不如直接拦
        if (v < min || v > max)
            return false to "zx_meter 的 v=$v 超出 [$min,$max]，绘制时会被截断成错误的条长"
        val levels = o.optJSONArray("levels")
        if (levels != null) {
            if (levels.length() > 5) return false to "等级最多 5 档，实际 ${levels.length()} 档"
            for (i in 0 until levels.length()) {
                val l = levels.optJSONObject(i) ?: return false to "levels[$i] 不是对象"
                if (l.optString("n").isBlank()) return false to "levels[$i] 缺少 n（档位名）"
                val at = l.optDouble("at", Double.NaN)
                if (at.isNaN()) return false to "levels[$i] 缺少 at（刻度位置）"
                if (at < min || at > max)
                    return false to "levels[$i].at=$at 超出量程 [$min,$max]，刻度会画到条外"
            }
            // 刻度必须递增，否则标签顺序与刻度顺序不一致，读起来是乱的
            var prev = Double.NEGATIVE_INFINITY
            for (i in 0 until levels.length()) {
                val at = levels.optJSONObject(i)?.optDouble("at", 0.0) ?: 0.0
                if (at <= prev) return false to "levels[$i].at=$at 未递增（上一档 $prev），档位顺序会乱"
                prev = at
            }
        }
        return true to ""
    }

    private fun ring(o: JSONObject): Pair<Boolean, String> {
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return false to "zx_ring 缺少 v（进度）"
        if (v < 0 || v > 100)
            return false to "zx_ring 的 v=$v 超出 0-100，弧形角度算不出来（应传百分比）"
        return true to ""
    }

    private fun stat(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_stat 缺少 items"
        if (items.length() == 0) return false to "zx_stat 的 items 为空"
        if (items.length() > 4) return false to "统计卡最多 4 个，实际 ${items.length()} 个（每个 96dp，4 个已需横滚）"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: return false to "items[$i] 不是对象"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（指标名）"
            if (it.optString("v").isBlank()) return false to "items[$i] 缺少 v（显示值）"
            if (it.has("delta")) {
                val d = it.optDouble("delta", Double.NaN)
                if (d.isNaN()) return false to "items[$i].delta 不是数值"
                if (kotlin.math.abs(d) > 10000)
                    return false to "items[$i].delta=$d 绝对值过大，同比箭头没意义"
            }
        }
        return true to ""
    }

    private fun swatch(o: JSONObject): Pair<Boolean, String> {
        val colors = o.optJSONArray("colors")
            ?: return false to "zx_swatch 缺少 colors"
        if (colors.length() < 2) return false to "色板至少 2 个颜色，实际 ${colors.length()} 个"
        if (colors.length() > 12) return false to "色板最多 12 个，实际 ${colors.length()} 个"
        val seen = mutableSetOf<String>()
        for (i in 0 until colors.length()) {
            val c = colors.optJSONObject(i)
                ?: return false to "colors[$i] 不是对象，应为 {\"hex\":\"#5046E4\",\"n\":\"靛蓝\"}"
            val hex = c.optString("hex")
            if (hex.isBlank()) return false to "colors[$i] 缺少 hex（颜色值）"
            if (!hex.matches(Regex("#[0-9A-Fa-f]{6}")))
                return false to "colors[$i].hex=\"$hex\" 不是合法的 #RRGGBB"
            if (!seen.add(hex.lowercase()))
                return false to "colors[$i].hex=$hex 重复——两个一样的色块，选哪个都一样"
        }
        val sel = o.optInt("sel", 0)
        if (sel !in 0 until colors.length())
            return false to "zx_swatch 的 sel=$sel 超出 0-${colors.length() - 1}"
        return true to ""
    }

    private fun notice(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_notice 缺少 items"
        if (items.length() == 0) return false to "zx_notice 的 items 为空"
        if (items.length() > 4) return false to "通知最多 4 条，实际 ${items.length()} 条（再多会占满整屏）"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: return false to "items[$i] 不是对象"
            if (it.optString("d").isBlank())
                return false to "items[$i] 缺少 d（正文）——只有图标没有内容"
            val lv = it.optString("lv", "info")
            if (lv !in setOf("info", "ok", "warn", "err"))
                return false to "items[$i].lv=\"$lv\" 只能是 info / ok / warn / err"
        }
        val ttl = o.optInt("ttlMs", 0)
        if (ttl < 0) return false to "zx_notice 的 ttlMs 不能为负"
        return true to ""
    }

    private fun price(o: JSONObject): Pair<Boolean, String> {
        val plans = o.optJSONArray("plans")
            ?: return false to "zx_price 缺少 plans"
        if (plans.length() < 2) return false to "定价卡至少 2 档，实际 ${plans.length()} 档"
        if (plans.length() > 3) return false to "定价卡最多 3 档，实际 ${plans.length()} 档（每档 104dp）"
        var rec = 0
        for (i in 0 until plans.length()) {
            val p = plans.optJSONObject(i) ?: return false to "plans[$i] 不是对象"
            if (p.optString("n").isBlank()) return false to "plans[$i] 缺少 n（档位名）"
            if (p.optString("k").isBlank()) return false to "plans[$i] 缺少 k（回传键）"
            if (p.optString("price").isBlank()) return false to "plans[$i] 缺少 price（价格）"
            if (p.optBoolean("rec")) rec++
            val feats = p.optJSONArray("feats")
            if (feats != null && feats.length() > 4)
                return false to "plans[$i] 的 feats 最多 4 条，实际 ${feats.length()} 条"
        }
        if (rec > 1) return false to "plans 中有 $rec 个 rec=true，推荐档只能有一个"
        return true to ""
    }

    private fun keyvalue(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_keyvalue 缺少 rows"
        if (rows.length() == 0) return false to "zx_keyvalue 的 rows 为空"
        if (rows.length() > 10) return false to "键值列表最多 10 行，实际 ${rows.length()} 行"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"k\":\"键\",\"v\":\"值\",\"mono\":true}"
            if (r.optString("k").isBlank()) return false to "rows[$i] 缺少 k（键）"
            if (!r.has("v")) return false to "rows[$i] 缺少 v（值）——没有值就没什么可展示"
            if (r.optString("k").length > 12)
                return false to "rows[$i].k=\"${r.optString("k")}\" 超过 12 字，键列固定 84dp 会截断"
        }
        return true to ""
    }

    private fun inputmask(o: JSONObject): Pair<Boolean, String> {
        val mask = o.optString("mask")
        if (mask.isBlank()) return false to "zx_inputmask 缺少 mask（掩码）"
        val ph = o.optString("ph", "#")
        if (ph.length != 1)
            return false to "zx_inputmask 的 ph=\"$ph\" 必须是单个字符"
        val slots = mask.count { it == ph[0] }
        if (slots == 0)
            return false to "掩码 \"$mask\" 里没有占位符 '$ph'——没有可填的位置"
        if (slots > 24) return false to "掩码占位符共 $slots 位，超过 24 位（输入会非常慢）"
        if (o.has("v")) {
            val v = o.optString("v")
            if (v.length > slots)
                return false to "zx_inputmask 的 v 有 ${v.length} 位，但掩码只有 $slots 个占位符——多余部分会被截断，用户以为填对了"
        }
        return true to ""
    }

    private fun datalist(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_datalist 缺少 rows"
        if (rows.length() > 12) return false to "列表最多 12 行，实际 ${rows.length()} 行"
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val v = rows.optString(i)
            if (v.isBlank()) return false to "rows[$i] 是空字符串——空行删不掉也说不清是什么"
            if (!seen.add(v.trim()))
                return false to "rows[$i]=\"$v\" 重复——「选中第 3 行」这类回传会有歧义"
        }
        val maxLen = o.optInt("maxLen", 40)
        if (maxLen !in 4..120)
            return false to "zx_datalist 的 maxLen=$maxLen 超出 4-120"
        for (i in 0 until rows.length()) {
            if (rows.optString(i).length > maxLen)
                return false to "rows[$i] 长度 ${rows.optString(i).length} 超过 maxLen=$maxLen"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_radiogroup" -> """
            {"type":"zx_radiogroup","sel":-1,"options":[
              {"k":"a","t":"方案 A","d":"成本最低，周期 8 周"}",
              {"k":"b","t":"方案 B","d":"折中，周期 5 周"}",
              {"k":"c","t":"方案 C","d":"最快，风险较高"}"]}""".trimIndent()
        "zx_checklist" -> """
            {"type":"zx_checklist","items":[
              {"k":"t1","t":"引契约包","on":true},
              {"k":"t2","t":"注册扩展点","on":false},
              {"k":"t3","t":"对齐签名","on":false}]}""".trimIndent()
        "zx_avatargroup" -> """
            {"type":"zx_avatargroup","max":4,"label":"参与人",
             "people":[{"n":"李工","r":"前端"}",{"n":"小陈","r":"后端"}",
                       {"n":"王凯","r":"测试"}",{"n":"赵敏","r":"设计"}",
                       {"n":"孙宇","r":"产品"}"]}""".trimIndent()
        "zx_meter" -> """
            {"type":"zx_meter","label":"内存占用","v":62,"min":0,"max":100,
             "levels":[{"n":"正常","at":0},{"n":"偏高","at":60},{"n":"危险","at":85}]}""".trimIndent()
        "zx_ring" -> """
            {"type":"zx_ring","v":72,"label":"构建进度","sub":"正在编译模块 4/7"}""".trimIndent()
        "zx_stat" -> """
            {"type":"zx_stat","items":[
              {"n":"日活","v":"12.4k","delta":8.2},
              {"n":"崩溃率","v":"0.42%","delta":-0.3,"lowerBetter":true}]}""".trimIndent()
        "zx_swatch" -> """
            {"type":"zx_swatch","sel":0,"colors":[
              {"hex":"#5046E4","n":"靛蓝"}",{"hex":"#3FBF7F","n":"青绿"}",
              {"hex":"#E8A33D","n":"琥珀"}",{"hex":"#E5544B","n":"朱红"}"]}""".trimIndent()
        "zx_notice" -> """
            {"type":"zx_notice","ttlMs":0,"items":[
              {"lv":"err","t":"构建失败","d":"模块 app 编译错误"}",
              {"lv":"warn","t":"依赖过期","d":"3 个包有新版本"}"]}""".trimIndent()
        "zx_price" -> """
            {"type":"zx_price","plans":[
              {"k":"free","n":"免费","price":"0","unit":"/月","feats":["1 个会话"]},
              {"k":"pro","n":"专业","price":"39","unit":"/月","rec":true,
               "feats":["无限会话","本地模型","插件"]}]}""".trimIndent()
        "zx_keyvalue" -> """
            {"type":"zx_keyvalue","rows":[
              {"k":"版本","v":"1.0.90"},{"k":"包名","v":"com.ai.assistance.quro","mono":true},
              {"k":"签名","v":"D9:5B:1B:EC","mono":true}]}""".trimIndent()
        "zx_inputmask" -> """
            {"type":"zx_inputmask","label":"卡号","mask":"#### #### #### ####","ph":"#"}""".trimIndent()
        "zx_datalist" -> """
            {"type":"zx_datalist","hint":"添加白名单域名","maxLen":40,
             "rows":["api.example.com","cdn.example.com"]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_radiogroup"  -> "options[{k,t,d?≤60字}] + sel?  2-6 项 · 带描述的单选"
        "zx_checklist"   -> "items[{k,t,on?}]  最多 10 · k 不可重复 · 带进度"
        "zx_avatargroup" -> "people[{n,r?}] + max?(1-6)  重叠显示，溢出 +N"
        "zx_meter"       -> "v,min,max + levels?[{n,at}]  v/刻度必须在量程内且递增"
        "zx_ring"        -> "v(0-100) + label?/sub?  读屏念「进度 N%」"
        "zx_stat"        -> "items[{n,v,delta?,lowerBetter?}]  最多 4 · 箭头配色看语义"
        "zx_swatch"      -> "colors[{hex,n?}] + sel?  2-12 个 · hex 不可重复"
        "zx_notice"      -> "items[{lv(info/ok/warn/err),t?,d}]  最多 4 条"
        "zx_price"       -> "plans[{k,n,price,unit?,feats?,rec?}]  2-3 档 · rec 最多 1"
        "zx_keyvalue"    -> "rows[{k≤12字,v,mono?}]  最多 10 行 · 点行复制"
        "zx_inputmask"   -> "mask,ph?(单字符),v?  v 长度不得超占位符数"
        "zx_datalist"    -> "rows[] + maxLen?(4-120)  最多 12 行 · 不可重复"
        else -> "未注册组件：$component"
    }
}
