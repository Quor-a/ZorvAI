package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log2

/**
 * 第三十三批组件（消费、开发与娱乐）的校验 + 示例 + 规格。
 *
 * 这批查的是**「信息摆出来了，但用户没法判断它新不新、能不能信」**。
 *
 * 前十档查的一直是"分析得对不对"：
 *   画不出来 → 统计前提不成立 → 区间前提不成立 →
 *   因果前提无从判断 → 看起来确定了其实没那么确定 →
 *   推不出该做什么 → 记了下次用不上 → 落不到谁该做什么 →
 *   推演本身不可信 → 决策前提在过程中会变
 *
 * 前三十二批走得很深，但**窄**——全是"分析一个已经存在的事情"。
 * 而真实对话里大量需求根本不在分析：
 *
 *   "今天要不要带伞"      → 天气
 *   "最近有什么消息"      → 新闻
 *   "这段代码什么意思"    → 代码
 *   "你刚才调了什么工具"  → 工具调用
 *   "等我一下，我玩两把"  → 游戏
 *
 * 于是出现一种新的错法：**信息给了，但不能判断它新不新、能不能信**。
 *
 *   天气没有观测时间 → 用户不知道是刚才的还是昨天的
 *   新闻没有更新时间 → 用户不知道是新的还是旧的
 *   终端没有运行状态 → 用户以为卡住了
 *   工具调用没有结果 → 用户不知道 AI 干了什么
 *
 *   过期的天气/新闻是**错的**，而它看起来完全可信。
 *   这比没有更糟——没有的话用户会去查，有的话他直接用了。
 *
 * 具体要拦的（与 ZxRender33 注释一一对应）：
 *   weather    缺观测时间 / 预报 < 3 天 / 高温 < 低温
 *   wxradar    回波强度越界 / 坐标越界
 *   aqi        值越界 / 分项全部相同（判不出首要污染物）
 *   news       缺时间戳 / 全部同一来源（那叫专栏不叫新闻）
 *   headline   缺要点（那只是标题）
 *   feed       缺已读状态
 *   code       语言不在支持表 / 高亮行号越界
 *   codetheme  少于 2 个主题（对比不成立）
 *   shell      缺提示符
 *   tty        缺会话名 / 状态非法
 *   toolcall   缺工具名 / 状态非法 / **成功但没有结果**
 *   toolchain  依赖指向不存在的 id / 有环
 *   toolargs   缺字段 / 必填项无默认值
 *   2048       初始值非 2 的幂
 *   tictactoe  棋盘非 3x3
 */
object ZxDsl33 {

    val ALL = listOf(
        "zx_weather", "zx_wxradar", "zx_aqi",
        "zx_news", "zx_headline", "zx_feed",
        "zx_code", "zx_codetheme",
        "zx_shell", "zx_tty",
        "zx_toolcall", "zx_toolchain", "zx_toolargs",
        "zx_2048", "zx_tictactoe"
    )

    /** 代码高亮支持的语言 */
    private val LANGS = setOf("kotlin", "python", "js", "json", "bash", "sql")

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_weather"    -> weather(root)
        "zx_wxradar"    -> wxradar(root)
        "zx_aqi"        -> aqi(root)
        "zx_news"       -> news(root)
        "zx_headline"   -> headline(root)
        "zx_feed"       -> feed(root)
        "zx_code"       -> code(root)
        "zx_codetheme"  -> codetheme(root)
        "zx_shell"      -> shell(root)
        "zx_tty"        -> tty(root)
        "zx_toolcall"   -> toolcall(root)
        "zx_toolchain"  -> toolchain(root)
        "zx_toolargs"   -> toolargs(root)
        "zx_2048"       -> g2048(root)
        "zx_tictactoe"  -> ttt(root)
        else -> false to "未注册组件：$component"
    }

    private fun weather(o: JSONObject): Pair<Boolean, String> {
        val city = o.optString("city")
        if (city.isBlank()) return false to "zx_weather 缺少 city（城市名）"
        val code = o.optInt("code", 0)
        if (code !in 0..6) return false to "zx_weather 的 code=$code 超出 0-6（0晴1多云2阴3雨4雪5雷6雾）"
        val now = o.optDouble("now", Double.NaN)
        if (now.isNaN()) return false to "zx_weather 缺少 now（当前温度）"
        if (now < -80 || now > 60) return false to "zx_weather 的 now=$now 超出合理范围 [-80, 60]"
        // 缺观测时间：这是这批最该拦的一条
        val ts = o.optLong("ts", 0L)
        if (ts <= 0)
            return false to "zx_weather 缺少 ts（观测时间）——**没有观测时间的天气是假的**。" +
                "用户不知道这是刚才的还是昨天的。" +
                "过期的天气是**错的**，而它看起来完全可信；" +
                "这比没有更糟——没有的话用户会去查，有的话他直接用了"
        if (ts > System.currentTimeMillis() + 3600_000L)
            return false to "zx_weather 的 ts 在未来（超前 1 小时以上）——观测时间不能是未来的"
        val days = o.optJSONArray("days")
        if (days == null || days.length() < 3)
            return false to "zx_weather 至少需要 3 天预报，实际 ${days?.length() ?: 0} 天——" +
                "1-2 天叫「今天和明天」，不叫预报"
        val hi = o.optJSONArray("hi")
        val lo = o.optJSONArray("lo")
        if (hi == null || lo == null) return false to "zx_weather 缺少 hi / lo（高温/低温数组）"
        if (hi.length() != days.length() || lo.length() != days.length())
            return false to "zx_weather 的 days(${days.length()}) / hi(${hi.length()}) / " +
                "lo(${lo.length()}) **长度必须一致**——对不上曲线就画错行"
        for (k in 0 until hi.length()) {
            val h = hi.optDouble(k, Double.NaN)
            val l = lo.optDouble(k, Double.NaN)
            if (h.isNaN() || l.isNaN()) return false to "hi/lo[$k] 不是数值"
            // 高温 < 低温：曲线交叉，看起来像数据错了
            if (h < l) return false to "hi[$k]=$h **小于** lo[$k]=$l——" +
                "高温低于低温，两条曲线会交叉，用户会以为数据错了"
        }
        return true to ""
    }

    private fun wxradar(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("cells") ?: return false to "zx_wxradar 缺少 cells"
        val n = a.length()
        if (n == 0) return false to "zx_wxradar 的 cells 为空——**没有回波的雷达图是一片空白**"
        if (n > 200) return false to "zx_wxradar 最多 200 个格，实际 $n 个"
        for (k in 0 until n) {
            val c = a.optJSONObject(k)
                ?: return false to "cells[$k] 不是对象，应为 {\"x\":0-1,\"y\":0-1,\"v\":回波强度}"
            val x = c.optDouble("x", Double.NaN)
            val y = c.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) return false to "cells[$k] 缺少 x 或 y（归一化坐标 0-1）"
            if (x < 0 || x > 1 || y < 0 || y > 1)
                return false to "cells[$k] 的坐标 ($x, $y) 超出 0-1——会画到圈外，用户以为那是有效回波"
            val v = c.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "cells[$k] 缺少 v（回波强度 dBZ）"
            if (v < 0 || v > 75)
                return false to "cells[$k].v=$v 超出 0-75 dBZ——" +
                    "回波强度越界会被静默截断成最深色，用户以为那是特大暴雨"
        }
        val radius = o.optDouble("radius", 100.0)
        if (radius <= 0) return false to "zx_wxradar 的 radius=$radius 必须为正——" +
            "半径非正的话同心圈的刻度就没有意义"
        return true to ""
    }

    private fun aqi(o: JSONObject): Pair<Boolean, String> {
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return false to "zx_aqi 缺少 v（AQI 值）"
        if (v < 0 || v > 500)
            return false to "zx_aqi 的 v=$v 超出 0-500——AQI 量程是 0-500，超了仪表会画满"
        // 缺更新时间
        if (o.optLong("ts", 0L) <= 0)
            return false to "zx_aqi 缺少 ts（更新时间）——AQI 每小时都在变，" +
                "**没有时间戳的空气质量和没有时间戳的天气一样不可信**"
        val a = o.optJSONArray("items")
        if (a == null || a.length() == 0)
            return false to "zx_aqi 缺少 items（污染物分项）——**没有分项就判不出首要污染物**，" +
                "而那正是这个组件的价值"
        val n = a.length()
        if (n > 8) return false to "zx_aqi 最多 8 个分项，实际 $n 个"
        val vals = ArrayList<Double>()
        val names = HashSet<String>()
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":名称,\"v\":浓度,\"lim\":限值}"
            val nm = it.optString("n")
            if (nm.isBlank()) return false to "items[$k] 缺少 n（污染物名）"
            if (!names.add(nm)) return false to "items[$k] 的污染物名「$nm」重复"
            val pv = it.optDouble("v", Double.NaN)
            if (pv.isNaN()) return false to "items[$k] 缺少 v（浓度）"
            if (pv < 0) return false to "items[$k].v=$pv 不能为负"
            val lim = it.optDouble("lim", Double.NaN)
            if (lim.isNaN()) return false to "items[$k] 缺少 lim（限值）——" +
                "**没有限值就算不出占标率，也就判不出首要污染物**"
            if (lim <= 0) return false to "items[$k].lim=$lim 必须为正（除零）"
            vals.add(pv / lim)
        }
        // 分项全部相同：判不出首要污染物
        if (vals.maxOrNull()!! - vals.minOrNull()!! < 1e-9)
            return false to "zx_aqi 所有分项的**占标率完全相同**——" +
                "**判不出首要污染物**。这个组件的价值就在「哪个最该担心」，" +
                "全都一样就等于没有信息"
        return true to ""
    }

    private fun news(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items") ?: return false to "zx_news 缺少 items"
        val n = a.length()
        if (n == 0) return false to "zx_news 的 items 为空"
        if (n > 20) return false to "zx_news 最多 20 条，实际 $n 条"
        val srcs = HashSet<String>()
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"t\":标题,\"s\":来源,\"ts\":时间戳,\"h\":热度}"
            if (it.optString("t").isBlank()) return false to "items[$k] 缺少 t（标题）"
            val s = it.optString("s")
            if (s.isBlank()) return false to "items[$k] 缺少 s（来源）"
            srcs.add(s)
            // 缺时间戳
            if (it.optLong("ts", 0L) <= 0)
                return false to "items[$k] 缺少 ts（时间戳）——**新闻没有更新时间就不可信**。" +
                    "旧闻看起来和新闻一模一样，而用户会当成刚发生的"
            val h = it.optDouble("h", Double.NaN)
            if (h.isNaN()) return false to "items[$k] 缺少 h（热度）"
            if (h < 0) return false to "items[$k].h=$h 不能为负"
        }
        // 全部同一来源
        if (srcs.size < 2)
            return false to "zx_news 全部 ${n} 条来自同一来源「${srcs.firstOrNull()}」——" +
                "**那叫专栏不叫新闻**。单一来源无法交叉验证，一条错了全错"
        return true to ""
    }

    private fun headline(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("t").isBlank()) return false to "zx_headline 缺少 t（标题）"
        if (o.optString("s").isBlank()) return false to "zx_headline 缺少 s（来源）"
        if (o.optLong("ts", 0L) <= 0)
            return false to "zx_headline 缺少 ts（时间戳）——没有更新时间就判断不出新旧"
        if (o.optString("sum").isBlank())
            return false to "zx_headline 缺少 sum（摘要）——**只有标题没有摘要，那只是个链接**"
        val pts = o.optJSONArray("pts")
        // 缺要点
        if (pts == null || pts.length() == 0)
            return false to "zx_headline 缺少 pts（要点）——**只有标题和摘要，那不叫头条卡，那叫标题**。" +
                "头条卡的价值在把全文压成要点，让用户扫一眼就知道要不要点进去"
        if (pts.length() > 6) return false to "zx_headline 最多 6 个要点，实际 ${pts.length()} 个"
        for (k in 0 until pts.length())
            if (pts.optString(k).isBlank()) return false to "pts[$k] 为空"
        return true to ""
    }

    private fun feed(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items") ?: return false to "zx_feed 缺少 items"
        val n = a.length()
        if (n == 0) return false to "zx_feed 的 items 为空"
        if (n > 30) return false to "zx_feed 最多 30 条，实际 $n 条"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"t\":标题,\"s\":来源,\"ts\":时间戳,\"r\":是否已读}"
            if (it.optString("t").isBlank()) return false to "items[$k] 缺少 t（标题）"
            if (it.optString("s").isBlank()) return false to "items[$k] 缺少 s（来源）"
            if (it.optLong("ts", 0L) <= 0) return false to "items[$k] 缺少 ts（时间戳）"
            // 缺已读状态
            if (!it.has("r"))
                return false to "items[$k] 缺少 r（是否已读）——**订阅流没有已读标记，" +
                    "用户每次进来都要重新判断哪些看过哪些没看过**，而这个判断只能靠记忆"
        }
        return true to ""
    }

    private fun code(o: JSONObject): Pair<Boolean, String> {
        val lang = o.optString("lang")
        if (lang.isBlank()) return false to "zx_code 缺少 lang（语言）"
        // 语言不在支持表
        if (lang !in LANGS)
            return false to "zx_code 的 lang=\"$lang\" 不在支持表里（${LANGS.joinToString(" / ")}）——" +
                "**认不出的语言就明说认不出**，别当成普通文本硬画。" +
                "返回错的高亮比不高亮更糟，用户会以为那个颜色有意义"
        val src = o.optString("src")
        if (src.isBlank()) return false to "zx_code 缺少 src（源码）"
        val lines = src.split("\n")
        if (lines.size > 200)
            return false to "zx_code 最多 200 行，实际 ${lines.size} 行——" +
                "超了应该用 zx_codemap 给结构，而不是整段贴"
        // 高亮行号越界
        val hl = o.optJSONArray("hl")
        if (hl != null) {
            if (hl.length() > 20)
                return false to "zx_code 最多高亮 20 行，实际 ${hl.length()} 行——" +
                    "高亮超过 20 行就等于没有高亮"
            for (k in 0 until hl.length()) {
                val v = hl.optDouble(k, Double.NaN)
                if (v.isNaN()) return false to "hl[$k] 不是数值"
                val i = v.toInt()
                if (v != i.toDouble()) return false to "hl[$k]=$v 不是整数（行号必须是整数）"
                if (i < 1 || i > lines.size)
                    return false to "hl[$k]=$i 超出 1-${lines.size}——" +
                        "**高亮行号对不上就没有意义**，用户会去看一行不存在的代码"
            }
        }
        return true to ""
    }

    private fun codetheme(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("src").isBlank()) return false to "zx_codetheme 缺少 src（源码）"
        val lang = o.optString("lang", "kotlin")
        if (lang !in LANGS) return false to "zx_codetheme 的 lang=\"$lang\" 不在支持表里"
        val th = o.optJSONArray("themes")
        val n = th?.length() ?: 3
        // 少于 2 个主题：对比不成立
        if (th != null && n < 2)
            return false to "zx_codetheme 只有 $n 个主题——**对比不成立**。" +
                "一个主题那叫代码预览，不叫对比"
        if (n > 6) return false to "zx_codetheme 最多 6 个主题，实际 $n 个"
        if (th != null) for (k in 0 until n)
            if (th.optString(k).isBlank()) return false to "themes[$k] 为空"
        return true to ""
    }

    private fun shell(o: JSONObject): Pair<Boolean, String> {
        val prompt = o.optString("prompt")
        // 缺提示符
        if (prompt.isBlank())
            return false to "zx_shell 缺少 prompt（提示符）——**没有提示符就分不清哪行是输入、" +
                "哪行是输出**。翻历史时会把输出当成自己敲的命令"
        if (prompt.length > 12)
            return false to "zx_shell 的 prompt=\"$prompt\" 超过 12 个字符——" +
                "提示符太长会吃掉命令的输入空间"
        val hist = o.optJSONArray("hist")
        if (hist != null && hist.length() > 50)
            return false to "zx_shell 最多 50 条历史，实际 ${hist.length()} 条"
        return true to ""
    }

    private fun tty(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("tabs") ?: return false to "zx_tty 缺少 tabs"
        val n = a.length()
        if (n == 0) return false to "zx_tty 的 tabs 为空"
        if (n > 8) return false to "zx_tty 最多 8 个标签，实际 $n 个"
        val names = HashSet<String>()
        val ok = setOf("running", "idle", "error", "done")
        for (k in 0 until n) {
            val t = a.optJSONObject(k)
                ?: return false to "tabs[$k] 不是对象，应为 {\"n\":会话名,\"st\":状态,\"last\":最后输出}"
            val nm = t.optString("n")
            if (nm.isBlank()) return false to "tabs[$k] 缺少 n（会话名）"
            if (!names.add(nm)) return false to "tabs[$k] 的会话名「$nm」重复"
            val st = t.optString("st", "idle")
            // 状态非法
            if (st !in ok)
                return false to "tabs[$k].st=\"$st\" 非法（只认 ${ok.joinToString(" / ")}）——" +
                    "**状态认不出来就没法显示状态点**，而状态外显正是多标签的全部价值"
        }
        return true to ""
    }

    private fun toolcall(o: JSONObject): Pair<Boolean, String> {
        val name = o.optString("tool")
        // 缺工具名
        if (name.isBlank())
            return false to "zx_toolcall 缺少 tool（工具名）——**不知道调的是哪个工具，" +
                "这张卡就没有任何信息**。工具调用不透明正是 Agent 最被诟病的地方"
        val st = o.optString("st", "ok")
        val ok = setOf("ok", "err", "running", "denied")
        if (st !in ok)
            return false to "zx_toolcall 的 st=\"$st\" 非法（只认 ${ok.joinToString(" / ")}）"
        val ms = o.optLong("ms", 0L)
        if (ms < 0) return false to "zx_toolcall 的 ms=$ms 不能为负"
        val res = o.optString("res")
        // 成功但没有结果：最该报的一种
        if (st == "ok" && res.isBlank())
            return false to "zx_toolcall 状态是 ok 但**没有结果**——" +
                "这是最该拦的一种：用户会以为工具跑了，实际什么都没拿到。" +
                "要么补上 res，要么把状态改成 err"
        if (st == "err" && res.isBlank())
            return false to "zx_toolcall 状态是 err 但没有错误信息——" +
                "**只标个红点不给原因**，用户没法自己处理"
        if (res.length > 4000)
            return false to "zx_toolcall 的 res 有 ${res.length} 字符，超过 4000——" +
                "超了应该截断并提示完整结果在哪，而不是整段塞进气泡"
        return true to ""
    }

    private fun toolchain(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("steps") ?: return false to "zx_toolchain 缺少 steps"
        val n = a.length()
        if (n == 0) return false to "zx_toolchain 的 steps 为空"
        if (n > 16) return false to "zx_toolchain 最多 16 步，实际 $n 步"
        val ids = HashSet<String>()
        val deps = ArrayList<Pair<String, String>>()
        val ok = setOf("ok", "err", "running", "denied")
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "steps[$k] 不是对象，应为 {\"id\":标识,\"tool\":工具名,\"st\":状态,\"after\":依赖}"
            val id = s.optString("id")
            if (id.isBlank()) return false to "steps[$k] 缺少 id"
            if (!ids.add(id)) return false to "steps[$k] 的 id「$id」重复"
            if (s.optString("tool").isBlank()) return false to "steps[$k] 缺少 tool（工具名）"
            val st = s.optString("st", "ok")
            if (st !in ok) return false to "steps[$k].st=\"$st\" 非法（只认 ${ok.joinToString(" / ")}）"
            val after = s.optString("after", "")
            if (after.isNotBlank()) deps.add(id to after)
        }
        // 依赖指向不存在的 id
        deps.forEach { (id, after) ->
            if (after !in ids)
                return false to "steps 的「$id」依赖「$after」，但**没有这个 id**——" +
                    "依赖指向空处，链就断了，而界面上看起来是一条完整的链"
        }
        // 有环
        val g = deps.groupBy({ it.first }, { it.second })
        fun hasCycle(start: String): Boolean {
            val seen = HashSet<String>()
            var cur: String? = start
            while (cur != null) {
                if (!seen.add(cur)) return true
                cur = g[cur]?.firstOrNull()
            }
            return false
        }
        ids.forEach { if (hasCycle(it)) return false to "zx_toolchain 的依赖**成环**（在「$it」处）——" +
            "循环依赖的链永远走不到终点，执行时会死锁" }
        return true to ""
    }

    private fun toolargs(o: JSONObject): Pair<Boolean, String> {
        val tool = o.optString("tool")
        if (tool.isBlank()) return false to "zx_toolargs 缺少 tool（工具名）"
        val a = o.optJSONArray("fields")
        // 缺字段
        if (a == null || a.length() == 0)
            return false to "zx_toolargs 缺少 fields——**没有字段那不是表单**。schema 驱动的" +
                "意思是字段直接来自工具的参数声明，一个都没有说明声明没读到"
        val n = a.length()
        if (n > 12) return false to "zx_toolargs 最多 12 个字段，实际 $n 个"
        val types = setOf("string", "number", "bool", "enum", "array", "object")
        val names = HashSet<String>()
        for (k in 0 until n) {
            val f = a.optJSONObject(k)
                ?: return false to "fields[$k] 不是对象，应为 {\"n\":字段名,\"t\":类型,\"req\":是否必填,\"def\":默认值}"
            val nm = f.optString("n")
            if (nm.isBlank()) return false to "fields[$k] 缺少 n（字段名）"
            if (!names.add(nm)) return false to "fields[$k] 的字段名「$nm」重复"
            val t = f.optString("t", "string")
            if (t !in types) return false to "fields[$k].t=\"$t\" 非法（只认 ${types.joinToString(" / ")}）"
            val req = f.optBoolean("req", false)
            val def = f.optString("def", "")
            // 必填项无默认值
            if (req && def.isBlank())
                return false to "fields[$k]「$nm」是**必填但没有默认值**——" +
                    "表单一打开就处于「不能提交」的状态，而用户不知道该填什么才叫合法"
        }
        return true to ""
    }

    private fun g2048(o: JSONObject): Pair<Boolean, String> {
        val init = o.optString("init", "")
        if (init.isBlank()) return true to ""   // 不传则用默认开局
        val parts = init.split(",")
        if (parts.size != 16)
            return false to "zx_2048 的 init 需要 **16 个格子**（4x4），实际 ${parts.size} 个——" +
                "棋盘非 4x4 的话滑动合并的规则就不成立了"
        parts.forEachIndexed { k, s ->
            val v = s.trim().toIntOrNull()
                ?: return false to "zx_2048 的 init[$k]=\"${s.trim()}\" 不是整数"
            if (v < 0) return false to "zx_2048 的 init[$k]=$v 不能为负"
            // 非 2 的幂
            if (v != 0 && (v and (v - 1)) != 0)
                return false to "zx_2048 的 init[$k]=$v **不是 2 的幂**——" +
                    "2048 里所有格子都必须是 2 的幂（2/4/8…），" +
                    "出现别的数说明这不是一个合法局面，滑动合并会算出错误的分数"
            if (v > 131072) return false to "zx_2048 的 init[$k]=$v 过大（上限 131072）"
        }
        return true to ""
    }

    private fun ttt(o: JSONObject): Pair<Boolean, String> {
        val b = o.optString("board", "")
        if (b.isBlank()) return true to ""   // 不传则空盘开局
        val cells = b.split(",")
        if (cells.size != 9)
            return false to "zx_tictactoe 的 board 需要 **9 个格子**（3x3），实际 ${cells.size} 个——" +
                "棋盘非 3x3 的话三连判定就不成立了"
        var x = 0; var oo = 0
        cells.forEachIndexed { k, s ->
            val c = s.trim()
            if (c != " " && c != "X" && c != "O")
                return false to "zx_tictactoe 的 board[$k]=\"$c\" 非法（只认 X / O / 空格）"
            if (c == "X") x++
            if (c == "O") oo++
        }
        // 落子数不合法：X 先手，两者差不能超过 1
        if (abs(x - oo) > 1)
            return false to "zx_tictactoe 的落子数不合法：X $x 个、O $oo 个——" +
                "**X 先手，两者数量差不能超过 1**。差 2 以上说明这个局面在真实对局中不可能出现"
        if (oo > x)
            return false to "zx_tictactoe 的落子数不合法：O($oo) 比 X($x) 多——" +
                "O 是后手，不可能比先手落得多"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_weather" -> """
            {"type":"zx_weather","city":"揭阳","code":3,"now":21,"feels":19,
             "hum":82,"wind":"东南风 3 级","ts":1760000000000,
             "days":[{"d":"周一","c":3},{"d":"周二","c":1},{"d":"周三","c":0},
                     {"d":"周四","c":2},{"d":"周五","c":3}],
             "hi":[24,27,29,26,23],"lo":[18,19,20,21,17]}""".trimIndent()
        "zx_wxradar" -> """
            {"type":"zx_wxradar","site":"汕头雷达站","radius":120,
             "cells":[{"x":0.42,"y":0.38,"v":45},{"x":0.55,"y":0.30,"v":38},
                      {"x":0.60,"y":0.52,"v":18},{"x":0.35,"y":0.60,"v":12},
                      {"x":0.70,"y":0.44,"v":28},{"x":0.48,"y":0.66,"v":8}]}""".trimIndent()
        "zx_aqi" -> """
            {"type":"zx_aqi","n":"揭阳空气质量","v":118,"ts":1760000000000,
             "items":[{"n":"PM2.5","v":89,"lim":75},{"n":"PM10","v":132,"lim":150},
                      {"n":"O3","v":96,"lim":160},{"n":"NO2","v":41,"lim":80}]}""".trimIndent()
        "zx_news" -> """
            {"type":"zx_news","n":"AI 动态","items":[
               {"t":"某厂发布新模型","s":"机器之心","ts":1760000000000,"h":92},
               {"t":"开源社区周报","s":"InfoQ","ts":1759990000000,"h":61},
               {"t":"端侧推理新进展","s":"机器之心","ts":1759980000000,"h":44}]}""".trimIndent()
        "zx_headline" -> """
            {"type":"zx_headline","t":"端侧大模型迎来新突破","s":"机器之心",
             "ts":1760000000000,"sum":"昨日发布的方案把显存占用压到原来的三分之一。",
             "pts":["推理速度提升约 2 倍","已在多款机型验证","开源协议宽松"]}""".trimIndent()
        "zx_feed" -> """
            {"type":"zx_feed","n":"订阅","items":[
               {"t":"每周技术选读","s":"周刊","ts":1760000000000,"r":false},
               {"t":"开源项目月报","s":"GitHub","ts":1759990000000,"r":true},
               {"t":"设计资源合集","s":"设计站","ts":1759980000000,"r":false}]}""".trimIndent()
        "zx_code" -> """
            {"type":"zx_code","lang":"kotlin",
             "src":"fun render(o: JSONObject) {\n  // 只重组最后一个 token\n  val t = tokens.last()\n  return 42\n}",
             "hl":[2,3]}""".trimIndent()
        "zx_codetheme" -> """
            {"type":"zx_codetheme","lang":"kotlin",
             "src":"fun main() {\n  val x = 42\n  // 注释\n  println(\\"hi\\")\n}",
             "themes":["Dark+","Solarized","Monokai"]}""".trimIndent()
        "zx_shell" -> """
            {"type":"zx_shell","prompt":"$ ","hist":["ls","pwd"]}""".trimIndent()
        "zx_tty" -> """
            {"type":"zx_tty","n":"会话","tabs":[
               {"n":"build","st":"running","last":"> gradle assembleDebug"},
               {"n":"log","st":"idle","last":"tail -f app.log"},
               {"n":"test","st":"error","last":"FAILED: 3 tests"}]}""".trimIndent()
        "zx_toolcall" -> """
            {"type":"zx_toolcall","tool":"file_read","st":"ok","ms":42,
             "args":"{\\"path\\":\\"/sdcard/a.txt\\"}","res":"hello world"}""".trimIndent()
        "zx_toolchain" -> """
            {"type":"zx_toolchain","n":"改文件","steps":[
               {"id":"s1","tool":"file_read","st":"ok","ms":30},
               {"id":"s2","tool":"file_write","st":"ok","ms":55,"after":"s1"},
               {"id":"s3","tool":"run_test","st":"err","ms":2100,"after":"s2"}]}""".trimIndent()
        "zx_toolargs" -> """
            {"type":"zx_toolargs","tool":"file_write","fields":[
               {"n":"path","t":"string","req":true,"def":"/sdcard/a.txt"},
               {"n":"content","t":"string","req":true,"def":"hello"},
               {"n":"append","t":"bool","req":false,"def":"false"}]}""".trimIndent()
        "zx_2048" -> """
            {"type":"zx_2048","n":"2048",
             "init":"2,0,0,0,4,2,0,0,0,0,4,0,0,0,0,2"}""".trimIndent()
        "zx_tictactoe" -> """
            {"type":"zx_tictactoe","n":"井字棋","board":"X, ,O, ,X, , , ,O"}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_weather" -> "city + code∈0-6 + now + **ts>0（无观测时间会被拦）** + days/hi/lo 等长≥3；**hi<lo 会被拦**"
        "zx_wxradar" -> "cells[{x,y∈0-1,v∈0-75}] ≤200 + radius>0；**v 越界会被拦**"
        "zx_aqi" -> "v∈0-500 + **ts>0** + items[{n,v,lim>0}] ≤8；**占标率全同会被拦**"
        "zx_news" -> "items[{t,s,ts>0,h}] ≤20；**缺 ts 会被拦**；**全部同一来源会被拦**"
        "zx_headline" -> "t + s + ts>0 + sum + **pts 非空（缺要点会被拦）** ≤6"
        "zx_feed" -> "items[{t,s,ts>0,r}] ≤30；**缺 r（已读）会被拦**"
        "zx_code" -> "lang∈{kotlin/python/js/json/bash/sql} + src≤200行 + hl?∈1..行数；**越界会被拦**"
        "zx_codetheme" -> "src + lang? + themes?；**少于 2 个主题会被拦**"
        "zx_shell" -> "**prompt 非空（缺会被拦）**≤12字符 + hist?≤50"
        "zx_tty" -> "tabs[{n,st∈running/idle/error/done}] ≤8；**状态非法会被拦**"
        "zx_toolcall" -> "**tool 非空** + st∈ok/err/running/denied + ms? + res≤4000；**ok 但无 res 会被拦**"
        "zx_toolchain" -> "steps[{id,tool,st,after?}] ≤16；**依赖指向不存在 id 会被拦**；**有环会被拦**"
        "zx_toolargs" -> "**tool** + **fields 非空（缺会被拦）**[{n,t,req,def}] ≤12；**必填无默认值会被拦**"
        "zx_2048" -> "init?（16 格，每格 0 或 2 的幂）；**非 2 的幂会被拦**"
        "zx_tictactoe" -> "board?（9 格，X/O/空格）；**非 3x3 会被拦**；**X 与 O 数量差 >1 会被拦**"
        else -> "未注册组件：$component"
    }
}
