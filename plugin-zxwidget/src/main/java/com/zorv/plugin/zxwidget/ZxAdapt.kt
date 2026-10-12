package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 真机适配 —— 同一份 DSL 在不同设备上该按什么参数渲染。
 *
 * **为什么到第三十四批才做**
 *
 * 386 个组件一路加下来，一直有个隐含假设没被处理过：
 * **所有设备都一样大、一样快**。
 *
 * 这个假设在气泡里勉强成立（气泡宽度固定在 330dp 左右），
 * 所以前三十三批没出问题。但一旦引入 UI_SURFACE 全屏（㉙）、
 * 多窗口（㉚）、折叠屏，就立刻不成立：
 *
 *   同一张 12 列的 table
 *     360dp 手机   → 每列 30dp，字被截断
 *     折叠屏展开   → 每列 100dp，右边空一大片
 *
 *   同一个 zx_wxradar（200 个回波格）
 *     旗舰机       → 60fps
 *     千元机       → 每帧 40ms，滑动掉成幻灯片
 *
 * 而现在的处理方式是**每个组件自己判断**，于是：
 *   zx_grid 自己按屏宽算列数
 *   zx_master 自己判断要不要并排
 *   zx_chart 自己决定点数上限
 *
 * 三处各写一遍，规则必然不一致——zx_grid 在 340dp 降到 2 列，
 * zx_master 在 300dp 才降，同一台设备上两个容器的"窄"定义不同。
 * 更糟的是**新组件不会自动获得这套能力**：第 387 个组件写的时候
 * 如果忘了判断屏宽，它在小屏上就是坏的，而且没人会发现，
 * 直到某个用户拿着小屏手机用到它。
 *
 * 所以抽成一层：组件不再自己判断设备，问这里要参数。
 *
 * ── 五条约束 ────────────────────────────────────────────────
 *
 * 一、**只改度量与布局，不改语义**。
 *     与主题变体（㉛）那条一致：列数、密度、图尺寸可以变，
 *     **有几列数据、能不能点、什么颜色代表什么**不能变。
 *     否则同一份 DSL 在不同设备上语义不同，模型没法预期渲染结果。
 *
 * 二、**触控目标不小于 44dp，且要给出"最多放几个"**。
 *     只说"不小于 44dp"没用——一排要放 8 个按钮，"都放大到 44dp"
 *     是放不下的。必须同时算出这一排放得下几个，超出的转成"更多"。
 *
 * 三、**字号放大时列宽要跟着涨**。
 *     系统字号放大到 1.3 倍，列宽不变就会**截断**。
 *     截断比挤更糟——挤只是难看，截断是**信息丢了**，
 *     而且用户看不出自己丢了什么（"12,345" 显示成 "12,3…" 他还以为是 123）。
 *
 * 四、**低端机降采样而不是降帧**。
 *     降帧（少画几次）会让滑动看起来一顿一顿；
 *     降采样（少画几个点）画出来还是那个形状，只是没那么精细。
 *     形状对而糙，好过形状错而卡。
 *
 * 五、**溢出要能提前算出来**。
 *     ㉚ 自评是渲染**后**评估，代价是已经画出来了才发现看不清。
 *     这里是下发**前**预估：算一下这张卡大概多高，
 *     超过可视区多少倍，超太多就建议直接展开（UI_SURFACE）。
 */
object ZxAdapt {

    /** 屏宽档位 */
    enum class Width(val id: String, val label: String) {
        XS("xs", "超窄 <300dp"),
        S("s", "窄 300-360dp"),
        M("m", "常规 360-420dp"),
        L("l", "宽 >420dp / 折叠展开");

        companion object {
            /** 注意边界：360 属于 M，不属于 S */
            fun of(dp: Int) = when {
                dp < 300 -> XS
                dp < 360 -> S
                dp < 420 -> M
                else -> L
            }
        }
    }

    /** 屏高档位 */
    enum class Height(val id: String, val label: String) {
        SHORT("short", "矮 <640dp"),
        NORMAL("normal", "常规 640-800dp"),
        TALL("tall", "高 >800dp");

        companion object { fun of(dp: Int) = when {
            dp < 640 -> SHORT; dp < 800 -> NORMAL; else -> TALL } }
    }

    /** 性能等级 */
    enum class Perf(val id: String, val label: String) {
        LOW("low", "低端"), MID("mid", "中端"), HIGH("high", "高端");

        /** 采样上限：低端机少画点，画出来形状仍对 */
        fun sampleCap(base: Int) = when (this) {
            LOW -> max(12, base / 4)
            MID -> max(40, base / 2)
            HIGH -> base
        }
        /** 低端机不做逐帧动画与重采样重绘 */
        fun allowAnimation() = this != LOW
    }

    /** 一次渲染的环境 */
    data class Env(
        val widthDp: Int = 360,
        val heightDp: Int = 720,
        val fontScale: Float = 1.0f,
        val dark: Boolean = true,
        val perf: Perf = Perf.MID,
        val reduceMotion: Boolean = false,
        val folded: Boolean = false      // 折叠屏当前是否折叠
    ) {
        val w: Width get() = Width.of(widthDp)
        val h: Height get() = Height.of(heightDp)
        /** 展开时屏宽会翻倍，判断档位要用展开后的宽度 */
        fun unfoldedWidth(real: Int) = if (folded) real else (real * 1.6f).toInt()
    }

    /** 触控目标最小尺寸（dp）——Material 规范 48，44 是触屏可接受下限 */
    private const val TOUCH_MIN = 44

    /**
     * 一次适配决策。
     * 全部是"度量与布局"，不含任何语义。
     */
    data class Decision(
        val cols: Int,              // 建议列数
        val density: String,        // compact / comfortable / accessible
        val touchDp: Int,           // 触控目标尺寸
        val maxTouchCount: Int,     // 这一行最多放几个触控目标
        val fontSizeSp: Int,        // 正文建议字号
        val rowHeightDp: Int,       // 行高
        val imageDp: Int,           // 图/画布高度
        val sampleCap: Int,         // 采样点上限
        val animate: Boolean,       // 是否允许动画
        val expand: Boolean,        // 是否建议展开到全屏
        val estHeightDp: Int,       // 预估卡片高度
        val note: String
    )

    /**
     * 为一类内容在给定环境下算渲染参数。
     *
     * @param kind 内容类型：table / chart / list / grid / canvas / form
     * @param items 条目数（行数 / 点数 / 列数）
     * @param wantCols 期望列数（模型在 DSL 里写的）
     */
    fun decide(kind: String, items: Int, wantCols: Int, env: Env): Decision {
        val fs = (12 * env.fontScale).toInt().coerceIn(9, 17)      // 正文基准 12sp
        val touch = max(TOUCH_MIN, (TOUCH_MIN * env.fontScale).toInt())
        val wide = env.w

        // 一、列数：屏宽 + 字缩共同决定
        // 关键：字缩放大时可用列数要减少，否则列宽不够字被截断
        val availDp = env.widthDp - 24                            // 左右各 12dp 内边距
        val minColDp = (58 * env.fontScale).toInt().coerceAtLeast(40)
        val fitCols = max(1, availDp / minColDp)
        val cols = max(1, min(min(wantCols, fitCols),
            when (wide) { Width.XS -> 2; Width.S -> 3; Width.M -> 4; Width.L -> 6 }))

        // 二、密度：内容多就压，内容少就放大
        val density = when {
            items * cols > 60 -> "compact"
            items * cols < 12 -> "accessible"
            else -> "comfortable"
        }
        val rowH = when (density) {
            "compact" -> (26 * env.fontScale).toInt().coerceAtMost(34)
            "accessible" -> (44 * env.fontScale).toInt()
            else -> (34 * env.fontScale).toInt()
        }

        // 三、触控：一排最多放几个
        val maxTouch = max(1, (env.widthDp - 16) / (touch + 4))

        // 四、图/画布高度：矮屏要压，否则一屏装不下别的内容
        val imgDp = when (env.h) {
            Height.SHORT -> 96
            Height.NORMAL -> 120
            Height.TALL -> 150
        }.let { (it * if (wide == Width.XS) 0.8f else 1f).toInt() }

        // 五、采样上限：低端机降采样
        val base = when (kind) {
            "chart" -> 240; "canvas" -> 200; "grid" -> 60
            else -> 400
        }
        val cap = env.perf.sampleCap(base)

        // 六、动画：系统"减少动画"或低端机都关
        val animate = !env.reduceMotion && env.perf.allowAnimation()

        // 七、预估高度 + 是否建议展开
        val est = when (kind) {
            "table", "list" -> items * rowH + 90
            "grid" -> ceil(items.toDouble() / cols).toInt() * imgDp + 90
            "chart", "canvas" -> imgDp + 90
            else -> items * rowH + 90
        }
        // 超过可视区 2.2 倍就建议展开——超过这个倍数，
        // 用户在气泡里要滚很久，而全屏能一次看到更多
        val expand = est > env.heightDp * 2.2

        val note = buildString {
            append("${wide.label} / ${env.h.label} / 字缩 ${env.fontScale} / ${env.perf.label}")
            if (cols < wantCols) append("　列数 $wantCols→$cols（放不下，字会截断）")
            if (cap < base) append("　采样 $base→$cap（低端机降采样，形状仍对）")
            if (!animate) append("　关动画")
            if (expand) append("　**建议展开**（预估 ${est}dp ≈ 可视区 ${(est.toFloat() / env.heightDp).toFixed(1)} 倍）")
        }
        return Decision(cols, density, touch, maxTouch, fs, rowH, imgDp, cap,
            animate, expand, est, note)
    }

    private fun Float.toFixed(n: Int) = "%.${n}f".format(this)

    /**
     * 折叠屏：展开时不是放大，是分栏。
     *
     * 这是最容易做错的一条。把 360dp 的布局等比拉到 700dp，
     * 得到的是"每列都很宽但只有三列"的怪布局——
     * 用户展开屏幕是为了**同时看更多**，不是为了看更大的字。
     */
    fun splitForUnfolded(env: Env): Pair<Float, Float> {
        val w = env.unfoldedWidth(env.widthDp)
        return if (w >= 600) 0.38f to 0.62f      // 主列表 38% / 详情 62%
        else 1f to 0f                             // 不够宽就不分
    }

    /**
     * 触控目标检查：一排要放 n 个，放不放得下。
     * 返回"能放几个"和"超出的怎么办"。
     */
    fun touchFit(n: Int, env: Env): Pair<Int, String> {
        val touch = max(TOUCH_MIN, (TOUCH_MIN * env.fontScale).toInt())
        val fit = max(1, (env.widthDp - 16) / (touch + 4))
        return if (n <= fit) n to ""
        else fit to "要放 $n 个，但${env.widthDp}dp 只放得下 $fit 个" +
            "（每个 ${touch}dp）——**剩下的进「更多」，不要缩小**。" +
            "缩到 30dp 的话手指点不准，误触比多点一次更烦"
    }

    /** 字号放大后，某个固定宽度还能不能放下这段文本 */
    fun textFits(chars: Int, widthDp: Int, fontScale: Float): Boolean {
        // 中文一个字约等于 1em，西文约 0.5em，取保守值
        val need = chars * 9 * fontScale
        return need <= widthDp
    }

    fun render(): String = buildString {
        val env = Env()
        appendLine("真机适配（默认环境：360×720 / 字缩 1.0 / 中端）")
        appendLine()
        appendLine("  ── 各屏宽下 12 列 table 的决策 ──")
        appendLine()
        for (wd in listOf(280, 330, 390, 700)) {
            val e = env.copy(widthDp = wd)
            val d = decide("table", 40, 12, e)
            appendLine("  ${wd}dp (${Width.of(wd).label})")
            appendLine("     列 ${d.cols}　行高 ${d.rowHeightDp}dp　字号 ${d.fontSizeSp}sp" +
                "　密度 ${d.density}")
            appendLine("     预估 ${d.estHeightDp}dp" + if (d.expand) "　**建议展开**" else "")
        }
        appendLine()
        appendLine("  ── 字缩对列数的影响 ──")
        appendLine()
        for (fs in listOf(0.85f, 1.0f, 1.15f, 1.3f)) {
            val d = decide("table", 40, 12, env.copy(fontScale = fs))
            appendLine("  字缩 $fs　列 ${d.cols}　行高 ${d.rowHeightDp}dp")
        }
        appendLine()
        appendLine("  **字缩放大时列数要减少**——列宽不变就会截断。")
        appendLine("  截断比挤更糟：挤只是难看，截断是**信息丢了**。")
    }

    fun report(): String = buildString {
        appendLine("真机适配 —— 同一份 DSL 在不同设备上该按什么参数渲染")
        appendLine()
        appendLine("386 个组件一路加下来，一直有个隐含假设没被处理过：")
        appendLine()
        appendLine("  **所有设备都一样大、一样快**。")
        appendLine()
        appendLine("这个假设在气泡里勉强成立（气泡宽度固定在 330dp 左右），")
        appendLine("所以前三十三批没出问题。但一旦引入 UI_SURFACE 全屏、")
        appendLine("多窗口、折叠屏，就立刻不成立：")
        appendLine()
        appendLine("  同一张 12 列的 table")
        appendLine("    360dp 手机  → 每列 30dp，字被截断")
        appendLine("    折叠屏展开  → 每列 100dp，右边空一大片")
        appendLine()
        appendLine("  同一个 zx_wxradar（200 个回波格）")
        appendLine("    旗舰机  → 60fps")
        appendLine("    千元机  → 每帧 40ms，滑动掉成幻灯片")
        appendLine()
        appendLine("而现在的处理方式是**每个组件自己判断**，于是三处各写一遍，")
        appendLine("规则必然不一致。更糟的是**新组件不会自动获得这套能力**：")
        appendLine("第 387 个组件写的时候如果忘了判断屏宽，它在小屏上就是坏的，")
        appendLine("而且没人会发现，直到某个用户拿着小屏手机用到它。")
        appendLine()
        appendLine("五条约束：")
        appendLine("  一、**只改度量与布局，不改语义**")
        appendLine("  二、**触控目标不小于 44dp，且要给出「最多放几个」**")
        appendLine("  三、**字号放大时列宽要跟着涨**")
        appendLine("  四、**低端机降采样而不是降帧**")
        appendLine("  五、**溢出要能提前算出来**")
        appendLine()
        appendLine("第二条最容易做废：只说「不小于 44dp」没用——")
        appendLine("一排要放 8 个按钮，「都放大到 44dp」是放不下的。")
        appendLine("必须同时算出这一排放得下几个，超出的转成「更多」。")
    }

    fun demo(): String = buildString {
        appendLine("══ 列数：屏宽 + 字缩共同决定 ══")
        appendLine()
        for (wd in listOf(280, 330, 390, 700)) {
            val d = decide("table", 40, 12, Env().copy(widthDp = wd))
            appendLine("  ${wd.toString().padEnd(4)}dp  列 ${d.cols}　行高 ${d.rowHeightDp}dp" +
                "　${d.density}${if (d.expand) "　**建议展开**" else ""}")
        }
        appendLine()
        appendLine("══ 字缩：放大时列数必须减少 ══")
        appendLine()
        for (fs in listOf(0.85f, 1.0f, 1.15f, 1.3f)) {
            val d = decide("table", 40, 12, Env().copy(fontScale = fs))
            appendLine("  字缩 $fs　列 ${d.cols}　行高 ${d.rowHeightDp}dp　字号 ${d.fontSizeSp}sp")
        }
        appendLine()
        appendLine("  列宽不变就会**截断**。截断比挤更糟——")
        appendLine("  挤只是难看，截断是**信息丢了**，")
        appendLine("  而且用户看不出自己丢了什么")
        appendLine("  （「12,345」显示成「12,3…」他还以为是 123）。")
        appendLine()
        appendLine("══ 触控：一排最多放几个 ══")
        appendLine()
        for (n in listOf(3, 5, 8)) {
            val (fit, msg) = touchFit(n, Env().copy(widthDp = 360))
            appendLine("  要放 $n 个 → 放得下 ${fit} 个" + if (msg.isBlank()) "" else "　超了")
        }
        appendLine()
        appendLine("  **剩下的进「更多」，不要缩小**。")
        appendLine("  缩到 30dp 手指点不准，误触比多点一次更烦。")
        appendLine()
        appendLine("══ 低端机：降采样不降帧 ══")
        appendLine()
        for (p in Perf.values()) {
            appendLine("  ${p.label.padEnd(4)} 采样 ${p.sampleCap(200)} / 200　" +
                "动画 ${if (p.allowAnimation()) "开" else "**关**"}")
        }
        appendLine()
        appendLine("  降帧会让滑动看起来一顿一顿；")
        appendLine("  降采样画出来还是那个形状，只是没那么精细。")
        appendLine("  **形状对而糙，好过形状错而卡。**")
    }

    fun guide(): String = """
        zx_adapt(op=decide|touch|split|fits|demo|report|guide, ...)

        真机适配：同一份 DSL 在不同设备上该按什么参数渲染。

        386 个组件一路加下来，一直有个隐含假设没被处理过：

          **所有设备都一样大、一样快。**

        这个假设在气泡里勉强成立（气泡宽度固定在 330dp 左右），
        所以前三十三批没出问题。但一旦引入 UI_SURFACE 全屏、
        多窗口、折叠屏，就立刻不成立：

          同一张 12 列的 table
            360dp 手机  → 每列 30dp，字被截断
            折叠屏展开  → 每列 100dp，右边空一大片

        五条约束：
          一、**只改度量与布局，不改语义**
          二、**触控目标不小于 44dp，且要给出「最多放几个」**
          三、**字号放大时列宽要跟着涨**
          四、**低端机降采样而不是降帧**
          五、**溢出要能提前算出来**

          第二条最容易做废：只说「不小于 44dp」没用——
          一排要放 8 个按钮，「都放大到 44dp」是放不下的。
          必须同时算出这一排放得下几个，超出的转成「更多」。

          op=decide  给定 kind/items/cols/环境 → 渲染决策
          op=touch   一排要放 n 个触控目标，放得下几个
          op=split   折叠展开时的分栏比例
          op=fits    某宽度在字缩后还能不能放下 n 个字符
    """.trimIndent()

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        val ops = setOf("decide", "touch", "split", "fits", "demo", "report", "guide")
        if (op !in ops)
            return false to "zx_adapt 的 op=\"$op\" 只支持 ${ops.joinToString(" / ")}"
        if (op == "decide") {
            val kind = o.optString("kind")
            val kinds = setOf("table", "chart", "list", "grid", "canvas", "form")
            if (kind !in kinds)
                return false to "decide 的 kind=\"$kind\" 非法（只认 ${kinds.joinToString(" / ")}）——" +
                    "**不知道内容类型就算不出合适的密度和采样上限**"
            val items = o.optInt("items", -1)
            if (items < 0) return false to "decide 需要 items（条目数）"
            if (items > 5000) return false to "decide 的 items=$items 超过 5000"
            val cols = o.optInt("cols", 1)
            if (cols < 1) return false to "decide 的 cols=$cols 至少为 1"
        }
        if (op == "touch") {
            val n = o.optInt("n", -1)
            if (n < 1) return false to "touch 需要 n（要放几个）"
        }
        val w = o.optInt("widthDp", 360)
        if (w < 200 || w > 2000)
            return false to "widthDp=$w 超出 200-2000——**不在这个范围的不是手机屏**，" +
                "给它算适配没有意义"
        val fs = o.optDouble("fontScale", 1.0)
        if (fs < 0.5 || fs > 3.0)
            return false to "fontScale=$fs 超出 0.5-3.0——系统字号缩放不在这个范围"
        return true to ""
    }
}
