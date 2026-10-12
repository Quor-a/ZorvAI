package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第十一批组件（富内容 / 空间布局 / 系统级界面）的校验 + 示例 + 规格。
 *
 * 这批校验的重心变了。前十批大多在查"形状会不会画错"
 * （数值越界、数组长度不齐、坐标非法）——
 * 这批查的是**内容会不会读不出来**：
 *
 *   · 富文本的块类型拼错，会静默退化成普通段落，
 *     标题看起来和正文一样，层级全丢
 *   · 引用块没有 v，只剩一根竖条
 *   · 座位图没有行标签无法定位，全 sold 则没法选
 *   · 平面图房间越界会画到框外，重叠会让两个房间互相盖住
 *
 * 这些都不会崩，也都不会报错——
 * 它们只是让卡片"看起来能用但实际读不出内容"，
 * 这比崩溃更糟，因为模型会以为成功了。
 */
object ZxDsl11 {

    val ALL = listOf(
        "zx_rich", "zx_quote", "zx_filecard", "zx_callout", "zx_hint",
        "zx_seat", "zx_floor", "zx_banner", "zx_divider", "zx_settings",
        "zx_command", "zx_empty"
    )

    private val RICH_TYPES = setOf("h1", "h2", "li", "num", "code", "hr", "p")
    private val CALLOUT_KINDS = setOf("info", "warn", "err", "ok")
    private val SEAT_ST = setOf("free", "vip", "sold")
    private val FLOOR_ST = setOf("free", "busy", "soon")

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_rich"     -> rich(root)
        "zx_quote"    -> quote(root)
        "zx_filecard" -> filecard(root)
        "zx_callout"  -> callout(root)
        "zx_hint"     -> hint(root)
        "zx_seat"     -> seat(root)
        "zx_floor"    -> floor(root)
        "zx_banner"   -> banner(root)
        "zx_divider"  -> divider(root)
        "zx_settings" -> settings(root)
        "zx_command"  -> command(root)
        "zx_empty"    -> empty(root)
        else -> false to "未注册组件：$component"
    }

    private fun rich(o: JSONObject): Pair<Boolean, String> {
        val blocks = o.optJSONArray("blocks")
            ?: return false to "zx_rich 缺少 blocks"
        if (blocks.length() == 0) return false to "zx_rich 的 blocks 为空"
        if (blocks.length() > 12) return false to "富文本最多 12 块，实际 ${blocks.length()} 块"
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i)
                ?: return false to "blocks[$i] 不是对象，应为 {\"t\":类型,\"v\":内容}"
            val t = b.optString("t", "p")
            // 拼错会静默退化成普通段落——标题看起来和正文一样，层级全丢
            if (t !in RICH_TYPES)
                return false to "blocks[$i].t=\"$t\" 不是合法类型（${RICH_TYPES.joinToString("/")}）——会静默退化成普通段落，标题层级会丢"
            if (t == "hr") continue
            if (b.optString("v").isBlank())
                return false to "blocks[$i] 是 $t 但 v 为空——会渲染出一个空块"
        }
        return true to ""
    }

    private fun quote(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("v").isBlank())
            return false to "zx_quote 缺少 v（引用内容）——只剩一根竖条，什么也读不到"
        val hex = o.optString("hex")
        if (hex.isNotBlank() && !hex.matches(Regex("#[0-9A-Fa-f]{6}")))
            return false to "zx_quote 的 hex=\"$hex\" 不是合法的 #RRGGBB"
        return true to ""
    }

    private fun filecard(o: JSONObject): Pair<Boolean, String> {
        val files = o.optJSONArray("files")
            ?: return false to "zx_filecard 缺少 files"
        if (files.length() == 0) return false to "zx_filecard 的 files 为空"
        if (files.length() > 6) return false to "文件卡最多 6 个，实际 ${files.length()} 个"
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i)
                ?: return false to "files[$i] 不是对象，应为 {\"k\":文件名,\"n\":显示名,\"size\":,\"d\":}"
            if (f.optString("n").isBlank())
                return false to "files[$i] 缺少 n（显示名）——用户看到一个空行"
            // k 决定图标颜色：没有后缀就分不清是代码还是图片
            if (f.optString("k").isBlank())
                return false to "files[$i] 缺少 k（带后缀的文件名）——图标颜色靠后缀决定"
        }
        return true to ""
    }

    private fun callout(o: JSONObject): Pair<Boolean, String> {
        val kind = o.optString("kind", "info")
        if (kind !in CALLOUT_KINDS)
            return false to "zx_callout 的 kind=\"$kind\" 只能是 ${CALLOUT_KINDS.joinToString("/")}"
        if (o.optString("v").isBlank())
            return false to "zx_callout 缺少 v（正文）——只剩一个图标"
        return true to ""
    }

    private fun hint(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("v").isBlank())
            return false to "zx_hint 缺少 v（展开后的内容）——点了展开什么都没有"
        return true to ""
    }

    private fun seat(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_seat 缺少 rows"
        if (rows.length() == 0) return false to "zx_seat 的 rows 为空"
        if (rows.length() > 8) return false to "座位图最多 8 排，实际 ${rows.length()} 排"
        var free = 0
        for (r in 0 until rows.length()) {
            val arr = rows.optJSONArray(r)
                ?: return false to "rows[$r] 不是数组"
            if (arr.length() > 10) return false to "每排最多 10 座，rows[$r] 有 ${arr.length()} 座"
            for (c in 0 until arr.length()) {
                val s = arr.optJSONObject(c)
                val st = s?.optString("st", "free") ?: "free"
                if (st !in SEAT_ST)
                    return false to "rows[$r][$c].st=\"$st\" 只能是 ${SEAT_ST.joinToString("/")}"
                if (st != "sold") free++
            }
        }
        // 全 sold 就没法选了
        if (free == 0)
            return false to "所有座位都是 sold——没有可选项，用户点不动"
        val max = o.optInt("max", 4)
        if (max !in 1..8) return false to "zx_seat 的 max=$max 超出 1-8"
        return true to ""
    }

    private fun floor(o: JSONObject): Pair<Boolean, String> {
        val rooms = o.optJSONArray("rooms")
            ?: return false to "zx_floor 缺少 rooms"
        if (rooms.length() == 0) return false to "zx_floor 的 rooms 为空"
        if (rooms.length() > 8) return false to "平面图最多 8 个房间，实际 ${rooms.length()} 个"
        val boxes = mutableListOf<DoubleArray>()
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i)
                ?: return false to "rooms[$i] 不是对象，应为 {\"n\":名,\"x\":,\"y\":,\"w\":,\"h\":,\"st\":}"
            if (r.optString("n").isBlank()) return false to "rooms[$i] 缺少 n（房间名）"
            val x = r.optDouble("x", Double.NaN); val y = r.optDouble("y", Double.NaN)
            val w = r.optDouble("w", Double.NaN); val h = r.optDouble("h", Double.NaN)
            if (x.isNaN() || y.isNaN()) return false to "rooms[$i] 缺少 x/y（0-1 相对坐标）"
            if (w.isNaN() || h.isNaN()) return false to "rooms[$i] 缺少 w/h（0-1 相对尺寸）"
            if (w <= 0 || h <= 0) return false to "rooms[$i] 的 w/h 必须为正，实际 ${w}×${h}"
            // 越界会画到框外
            if (x < 0 || y < 0 || x + w > 1.0001 || y + h > 1.0001)
                return false to "rooms[$i] 的范围 [${"%.2f".format(x)},${"%.2f".format(y)} → ${"%.2f".format(x + w)},${"%.2f".format(y + h)}] 超出 0-1，会画到框外"
            val st = r.optString("st", "free")
            if (st !in FLOOR_ST)
                return false to "rooms[$i].st=\"$st\" 只能是 ${FLOOR_ST.joinToString("/")}"
            boxes.add(doubleArrayOf(x, y, x + w, y + h))
        }
        // 重叠会让两个房间互相盖住
        for (i in 0 until boxes.size) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3])
                return false to "rooms[$i] 与 rooms[$j] 重叠——两个房间会互相盖住"
        }
        return true to ""
    }

    private fun banner(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("n").isBlank())
            return false to "zx_banner 缺少 n（标题）——只剩一条渐变色带"
        val hex = o.optString("hex")
        if (hex.isNotBlank() && !hex.matches(Regex("#[0-9A-Fa-f]{6}")))
            return false to "zx_banner 的 hex=\"$hex\" 不是合法的 #RRGGBB"
        return true to ""
    }

    private fun divider(o: JSONObject): Pair<Boolean, String> {
        // 无标签的分隔线是合法的，但空字符串标签会留一个孤立的间距
        return true to ""
    }

    private fun settings(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_settings 缺少 items"
        if (items.length() == 0) return false to "zx_settings 的 items 为空"
        if (items.length() > 8) return false to "设置项最多 8 条，实际 ${items.length()} 条"
        val types = setOf("switch", "pick", "text")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":,\"d\":,\"type\":,\"k\":}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n（设置项名）"
            if (it.optString("k").isBlank())
                return false to "items[$i] 缺少 k（回传键）——改了之后模型不知道改的是哪项"
            val type = it.optString("type", "switch")
            if (type !in types)
                return false to "items[$i].type=\"$type\" 只能是 ${types.joinToString("/")}"
            if (type == "pick") {
                val opts = it.optJSONArray("options")
                    ?: return false to "items[$i] 是 pick 但缺 options"
                if (opts.length() < 2) return false to "items[$i].options 至少 2 项，实际 ${opts.length()} 项"
                if (opts.length() > 3) return false to "items[$i].options 最多 3 项（气泡里放不下），实际 ${opts.length()} 项"
            }
        }
        return true to ""
    }

    private fun command(o: JSONObject): Pair<Boolean, String> {
        val cmds = o.optJSONArray("cmds")
            ?: return false to "zx_command 缺少 cmds"
        if (cmds.length() == 0) return false to "zx_command 的 cmds 为空"
        if (cmds.length() > 8) return false to "命令最多 8 条，实际 ${cmds.length()} 条"
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i)
                ?: return false to "cmds[$i] 不是对象，应为 {\"k\":键,\"n\":显示名,\"key\":快捷键}"
            if (c.optString("n").isBlank())
                return false to "cmds[$i] 缺少 n（显示名）——过滤靠它，没有就搜不到"
            if (c.optString("k").isBlank())
                return false to "cmds[$i] 缺少 k（回传键）"
        }
        return true to ""
    }

    private fun empty(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("n").isBlank())
            return false to "zx_empty 缺少 n（空状态说明）——只剩一个图标，用户不知道为什么空"
        // 有 btn 才有下一步；没有就该说清楚为什么空
        if (o.optString("btn").isBlank() && o.optString("d").isBlank())
            return false to "zx_empty 既没有 btn 也没有 d——只说「暂无」，用户不知道下一步干什么"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_rich" -> """
            {"type":"zx_rich","blocks":[
              {"t":"h1","v":"标题"}",{"t":"p","v":"正文段落"}",
              {"t":"li","v":"要点一"}",{"t":"code","v":"val a = 1"}]}""".trimIndent()
        "zx_quote" -> """
            {"type":"zx_quote","v":"被引用的原文","src":"出处","hex":"#5046E4"}""".trimIndent()
        "zx_filecard" -> """
            {"type":"zx_filecard","files":[
              {"k":"ZxRender.kt","n":"渲染层","size":"12.4 KB"},
              {"k":"spec.png","n":"视觉稿","size":"840 KB"}]}""".trimIndent()
        "zx_callout" -> """
            {"type":"zx_callout","kind":"warn","title":"注意",
             "v":"这段会在下一次渲染时失效"}"""".trimIndent()
        "zx_hint" -> """
            {"type":"zx_hint","n":"这是什么意思？","v":"展开后的说明文字","open":true}""".trimIndent()
        "zx_seat" -> """
            {"type":"zx_seat","max":2,
             "rows":[[{"k":"A1","st":"free"},{"k":"A2","st":"sold"}],
                     [{"k":"B1","st":"vip"},{"k":"B2","st":"free"}]]}""".trimIndent()
        "zx_floor" -> """
            {"type":"zx_floor","rooms":[
              {"n":"A 区","x":0.05,"y":0.08,"w":0.4,"h":0.35,"st":"free"},
              {"n":"B 区","x":0.55,"y":0.08,"w":0.4,"h":0.35,"st":"busy"}]}""".trimIndent()
        "zx_banner" -> """
            {"type":"zx_banner","k":"upgrade","e":"★","n":"升级到专业版",
             "d":"解锁全部 107 个组件","btn":"查看","hex":"#5046E4"}""".trimIndent()
        "zx_divider" -> """
            {"type":"zx_divider","n":"以下为可选"}"""".trimIndent()
        "zx_settings" -> """
            {"type":"zx_settings","items":[
              {"k":"notify","n":"推送通知","d":"有新消息时提醒","type":"switch","v":true},
              {"k":"theme","n":"主题","type":"pick","options":["自动","浅色","深色"]}]}""".trimIndent()
        "zx_command" -> """
            {"type":"zx_command","cmds":[
              {"k":"render","n":"渲染一张卡片","key":"⌘K"},
              {"k":"clear","n":"清空会话","key":"⌘⌫"}]}""".trimIndent()
        "zx_empty" -> """
            {"type":"zx_empty","e":"◌","n":"还没有卡片",
             "d":"让 AI 下发一张试试","btn":"生成示例"}"""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_rich"     -> "blocks[{t,v}]  t ∈ h1/h2/li/num/code/hr/p · ≤12 块"
        "zx_quote"    -> "v + src? + hex?  竖条为唯一标识"
        "zx_filecard" -> "files[{k,n,size?,d?}]  ≤6 · k 带后缀决定图标色"
        "zx_callout"  -> "kind(info/warn/err/ok) + v + title?"
        "zx_hint"     -> "v + n? + open?  三角指示指向"
        "zx_seat"     -> "rows[[{k,st}]]  ≤8×10 · st ∈ free/vip/sold · 不可全 sold"
        "zx_floor"    -> "rooms[{n,x,y,w,h,st}]  0-1 相对坐标 · 不可越界或重叠"
        "zx_banner"   -> "n + e? + d? + btn? + hex?"
        "zx_divider"  -> "n?  无标签即纯线"
        "zx_settings" -> "items[{n,k,type,d?}]  ≤8 · pick 的 options 2-3 项"
        "zx_command"  -> "cmds[{k,n,key?}]  ≤8 · 输入即过滤"
        "zx_empty"    -> "n + (d 或 btn 至少其一)"
        else -> "未注册组件：$component"
    }
}
