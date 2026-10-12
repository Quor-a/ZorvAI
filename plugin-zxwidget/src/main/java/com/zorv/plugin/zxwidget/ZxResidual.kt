package com.zorv.plugin.zxwidget

/**
 * 残留责任 —— 交接签字之后，这段责任还剩多久才真正结束。
 *
 * **为什么需要它**
 *
 * 第四十批每个组件都有自己的 `residual()` 条，
 * 但那是**卡内**的、一张卡一个数字：
 *
 *   装修尾款：质保还剩 90 天
 *   离职交接：追责期还剩 60 天
 *   权限回收：令牌滞后 24 小时才失效
 *
 * 三张卡各算各的。而真实情况是**这些残留是叠加的**：
 *
 *   一个员工离职了 →
 *     门禁卡归还（物理，立即）
 *     OA 账号关闭（系统，立即）
 *     VPN 令牌失效（令牌，12 小时后）
 *     生产库密钥轮换（密钥，24 小时后）
 *     在职时经手的问题（追责期 90 天）
 *     签过的竞业（12 个月）
 *
 * 六件事，六种"结束时间"。
 * 问"他现在还有没有责任"，答案是**看问的是哪一种**。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **结束时间取最晚的那个，而不是最早的。**
 *
 * 三种取法，只有一种是对的：
 *
 *   取最早：OA 关了就说结束了 → 但密钥还在他手里
 *   取平均：没有意义，责任不能平均
 *   取最晚：**只要还有一个没结束，这段责任就还没结束**
 *
 * 取最早是最常见的错误，因为它最符合直觉——
 * "该做的都做了"和"真的结束了"是两件事。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**必须分类**。
 *     四类残留的失效机制完全不同：
 *       质保 —— 东西还会不会坏（时间到了就结束）
 *       追溯 —— 以前的事现在才发现（时间到了就不能再追）
 *       过渡 —— 接手方还没上手（需要人主动动作才结束）
 *       残留 —— 钥匙/令牌/密钥还开着（必须主动关才结束）
 *     混在一起给一个时间，用户不知道哪个要主动做。
 *
 * 二、**主动型和被动型要分开**。
 *     质保、追溯是**被动**的：时间到了自动结束，不用管。
 *     过渡、残留是**主动**的：不去做就永远不会结束。
 *     **标成"还剩 X 天"会让人以为等着就行**——
 *     等着就行的那类确实可以等，但主动的那类等着就永远挂着。
 *
 * 三、**过期的要说清是"不能追了"还是"还要负责"**。
 *     这两者方向相反，但都叫"过期"：
 *       追溯期过了 → 你不能再找他了（对他有利）
 *       质保期过了 → 他不再负责了（对他有利）
 *       残留权限没关 → 他还能进去（对他无关，对你危险）
 *     第三种**没有"过期"概念**，它不会自己消失。
 *     把它混进倒计时里，用户会以为等着就会消失。
 *
 * 四、**不能凭空结束**。
 *     只有明确的关闭动作才能结束残留类。
 *     没动作就标"已结束"，等于说"密钥已经不能用了"——
 *     而他手里那把钥匙明明还能开门。
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 它不知道"残留权限有没有被用过"。
 * 那需要审计日志，这里只有登记。
 */
object ZxResidual {

    /** 四类残留 */
    enum class Kind(val label: String, val active: Boolean) {
        WARRANTY("质保", false),   // 被动：时间到自动结束
        TRACE("追溯", false),      // 被动
        TRANSITION("过渡", true),  // 主动：要人去接手才算完
        RESIDUAL("残留", true)     // 主动：必须去关
    }

    data class Item(
        val kind: Kind,
        val what: String,
        var hours: Int,   // 剩余小时；残留类若为 -1 表示"未关闭，无期限"
        var closed: Boolean = false
    )

    private val items = ArrayList<Item>()

    fun add(kind: String, what: String, hours: Int): Pair<Boolean, String> {
        val k = Kind.values().firstOrNull { it.name == kind || it.label == kind }
            ?: return false to "**残留类型只能是：质保 / 追溯 / 过渡 / 残留**——" +
                "**四类的失效机制完全不同，混在一起给一个时间没意义**"
        if (what.isBlank()) return false to "**缺 what（残留的是什么）**"
        // 四、不能凭空结束
        if (k.active && hours >= 0 && hours == 0)
            return false to "「${k.label}」是**主动型**，不能用 0 小时表示已结束——" +
                "**必须有关闭动作**（用 close 关掉它）。" +
                "**没动作就标已结束，等于说他手里那把钥匙开不了门**"
        items.add(Item(k, what, hours))
        return true to "已登记：${k.label}·$what" +
            if (k.active) "（**主动型，不关就永远挂着**）" else "（被动型，时间到自动结束）"
    }

    fun close(what: String): Pair<Boolean, String> {
        val it = items.firstOrNull { x -> x.what == what }
            ?: return false to "没有「$what」这条残留"
        if (!it.kind.active)
            return false to "「${it.what}」是${it.kind.label}类（**被动型**），" +
                "**时间到了自动结束，不需要手动关**"
        if (it.closed) return false to "「$what」已经关过了"
        it.closed = true
        return true to "已关闭：${it.what}"
    }

    /** 一、按类聚合；二、主动型优先；结束时间取最晚 */
    fun status(): String = buildString {
        if (items.isEmpty()) {
            appendLine("还没有登记任何残留。")
            return@buildString
        }
        val open = items.filter { !it.closed && (it.kind.active || it.hours > 0) }
        appendLine("══ 残留责任 ══")
        appendLine()
        Kind.values().forEach { k ->
            val mine = items.filter { it.kind == k }
            if (mine.isEmpty()) return@forEach
            appendLine("${k.label}（${if (k.active) "**主动**：不去做就永远挂着" else "被动：时间到自动结束"}）")
            mine.forEach { it ->
                val st = when {
                    it.closed -> "已关闭 ✓"
                    it.kind.active -> "**未关闭**"
                    it.hours > 0 -> if (it.hours >= 24) "剩 ${it.hours / 24} 天" else "剩 ${it.hours} 小时"
                    else -> "已结束"
                }
                appendLine("  · ${it.what}　$st")
            }
            appendLine()
        }
        if (open.isEmpty()) {
            appendLine("**都结束了。**")
        } else {
            // 结束时间取最晚
            val latestPassive = open.filter { !it.kind.active }.maxOfOrNull { it.hours }
            val actives = open.filter { it.kind.active }
            appendLine("**还没结束的：${open.size} 项**")
            if (latestPassive != null)
                appendLine("  被动类最晚还有 ${latestPassive / 24} 天（可以等）")
            if (actives.isNotEmpty()) {
                appendLine("  主动类 ${actives.size} 项：**等着不会消失，必须去做**")
                actives.forEach { appendLine("    · ${it.what}") }
            }
            appendLine()
            appendLine("**结束时间取最晚的那个，不是最早的。**")
            appendLine("取最早是最常见的错误——OA 关了就说结束了，")
            appendLine("但密钥还在他手里。")
        }
    }

    fun clear(): Int = items.size.also { items.clear() }

    fun report(): String = buildString {
        appendLine("残留责任 —— 交接签字之后，这段责任还剩多久才真正结束")
        appendLine()
        appendLine("第四十批每个组件都有自己的 residual() 条，")
        appendLine("但那是一张卡一个数字。真实情况是**这些残留是叠加的**：")
        appendLine()
        appendLine("  一个员工离职 →")
        appendLine("    门禁卡归还（物理，立即）")
        appendLine("    OA 账号关闭（系统，立即）")
        appendLine("    VPN 令牌失效（令牌，12 小时后）")
        appendLine("    生产库密钥轮换（密钥，24 小时后）")
        appendLine("    在职时经手的问题（追责期 90 天）")
        appendLine("    签过的竞业（12 个月）")
        appendLine()
        appendLine("六件事，六种「结束时间」。")
        appendLine("问「他现在还有没有责任」，答案是**看问的是哪一种**。")
        appendLine()
        appendLine("**结束时间取最晚的那个，而不是最早的。**")
        appendLine()
        appendLine("  取最早：OA 关了就说结束了 → 但密钥还在他手里")
        appendLine("  取平均：没有意义，责任不能平均")
        appendLine("  取最晚：**只要还有一个没结束，这段责任就还没结束**")
        appendLine()
        appendLine("取最早是最常见的错误，因为它最符合直觉——")
        appendLine("「该做的都做了」和「真的结束了」是两件事。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**必须分类**（质保/追溯/过渡/残留）")
        appendLine("  二、**主动型和被动型要分开**")
        appendLine("  三、**过期的要说清是「不能追了」还是「还要负责」**")
        appendLine("  四、**不能凭空结束**")
        appendLine()
        appendLine("第二条最关键：**标成「还剩 X 天」会让人以为等着就行**。")
        appendLine("等着就行的那类确实可以等，但主动的那类等着就永远挂着。")
        appendLine()
        appendLine("第三条：残留权限**没有「过期」概念**，它不会自己消失。")
        appendLine("把它混进倒计时里，用户会以为等着就会消失。")
    }

    fun demo(): String = buildString {
        appendLine("══ 一个员工的离职 ══")
        appendLine()
        add("质保", "装修质保", 2160).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        add("追溯", "在职问题追责", 2160).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        add("残留", "生产库密钥", -1).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        add("残留", "办公室钥匙", -1).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        add("过渡", "项目过渡期", 720).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        appendLine()
        appendLine("  ── 二、主动型不能用 0 小时 ──")
        appendLine()
        add("残留", "VPN 令牌", 0).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        appendLine()
        appendLine("  ── 当前状态 ──")
        appendLine()
        appendLine("  " + status().replace("\n", "\n  "))
        appendLine()
        appendLine("  ── 四、只有关闭动作能结束残留类 ──")
        appendLine()
        close("办公室钥匙").let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        appendLine("  " + close("装修质保").second)
        appendLine()
        appendLine("  钥匙关了，但**密钥还在他手里**——")
        appendLine("  取最早会说「都结束了」，取最晚才知道还差一个。")
    }

    fun guide(): String = """
        zx_residual(op=add|close|status|report|demo|clear|guide)

        残留责任：交接签字之后，这段责任还剩多久才真正结束。

        六件事六种结束时间，问"他现在还有没有责任"，"
        答案是**看问的是哪一种**。

        **结束时间取最晚的那个，而不是最早的。**
        取 earliest 是最常见的错误——OA 关了就说结束了，
        但密钥还在他手里。

        四条约束：
          一、**必须分类**：质保 / 追溯 / 过渡 / 残留
          二、**主动型和被动型要分开**
             质保、追溯是被动的（时间到自动结束）
             过渡、残留是主动的（不去做就永远不会结束）
             **标成"还剩 X 天"会让人以为等着就行**
          三、**过期的要说清是"不能追了"还是"还要负责"**
             残留权限没有"过期"概念，它不会自己消失
          四、**不能凭空结束**（主动类必须有关闭动作）

          op=add <质保|追溯|过渡|残留> <内容> <剩余小时，主动类填 -1 表示未关闭>
          op=close <内容>
          op=demo  演示"取最早 vs 取最晚"
    """.trimIndent()

    fun handle(op: String, a: String, b: String, c: String): String = when (op) {
        "add" -> add(a, b, c.toIntOrNull() ?: -1).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        "close" -> close(b).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        "status" -> status()
        "report" -> report()
        "demo" -> demo()
        "clear" -> "已清空 ${clear()} 条"
        else -> guide()
    }
}
