package com.zorv.plugin.zxwidget

/**
 * 补救时限 —— 出事的第一分钟最值钱，按剩余时间给出"还来得及做什么"。
 *
 * **为什么需要它**
 *
 * 第三十八批每个组件都有自己的证据条（`evidence()`），
 * 但它们是**各自独立**的：
 *
 *   zx_claim     说"开箱照片还剩 72 小时"
 *   zx_damage    说"损坏照片还剩 48 小时"
 *   zx_refile    说"重报还剩 21 天"
 *
 * 各说各的。而真实情况是**一次事故会同时牵出好几项**：
 * 搬家磕坏了桌子 → 要拍照（48h）+ 要找搬家公司登记（当天）
 * + 要查合同里怎么赔（7 天内）。
 *
 * 用户不会分别打开三张卡去对时间。
 * 他需要的是**一个按紧迫度排好的清单**。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **出事的第一分钟最值钱。**
 *
 * 补救窗口是**单调递减**的——它只会变短，不会变长。
 * 所以排序不是"重要度"，是**剩余时间**。
 * 一个不那么重要但还剩 2 小时的事，
 * 比一个重要但还剩 20 天的事更该现在做。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**已过期的不静默删除，要说一次"没了"**。
 *     静默删掉，用户会以为还没到时间，
 *     过几天想起来再问，那时候更糟。
 *     所以保留但排在最后、标灰、写清后果——
 *     **让人死心也是一种帮助。**
 *
 * 二、**同一份证据可能被多项共用**。
 *     "外包装照片"同时服务于快递索赔和搬家损坏登记。
 *     不合并的话会列两条，用户以为要拍两次；
 *     合并了要说明它服务于哪几项——
 *     **拍一次顶两处，这才是真的省事。**
 *
 * 三、**窗口只减不增，不因任何操作重置**。
 *     这条最容易做错：用户点了"已办"，
 *     如果顺手把 leftH 重置成原值，
 *     那"还剩多久"就永远不变，整个机制是假的。
 *
 * 四、**要给"现在做这个要多久"**。
 *     只剩 2 小时，但做这件事要出门 1 小时——
 *     那其实已经来不及了。
 *     只给剩余时间不给耗时，用户会高估自己还来得及。
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 它不知道"这件事要做多久"，那是 `cost` 字段手工给的，
 * 不给就按 0 算（等于假设瞬间能做完），
 * 这会高估可行动窗口。
 */
object ZxRemedy {

    data class Item(
        val what: String,        // 要做什么
        val leftH: Int,          // 剩余小时（只减不增）
        val costH: Int,          // 做这件事要多久
        val miss: String,        // 过期后果
        val serves: List<String> // 服务于哪几项（可共用）
    ) {
        val gone: Boolean get() = leftH <= 0
        /** 真正可行动的窗口 = 剩余 − 耗时 */
        val slackH: Int get() = leftH - costH
        val urgent: Boolean get() = !gone && slackH <= 0
    }

    private val items = ArrayList<Item>()

    fun add(what: String, leftH: Int, costH: Int = 0, miss: String,
            serves: List<String> = emptyList()) {
        items.removeAll { it.what == what }
        items.add(Item(what, leftH, costH, miss, serves))
    }

    fun done(what: String) {
        // 三、不重置窗口——只标记完成，从清单里移走
        items.removeAll { it.what == what }
    }

    /** 按紧迫度排序：可行动的在前，已过期的最后 */
    fun ordered(): List<Item> = items.sortedWith(
        compareBy({ it.gone }, { -it.slackH.coerceAtLeast(Int.MIN_VALUE / 2) })
    ).let { list -> list.sortedBy { if (it.gone) 1 else 0 } }

    /** 共用证据：同 what 服务于多项 */
    fun shared(): List<Item> = items.filter { it.serves.size > 1 }

    fun h(h: Int): String = when {
        h <= 0 -> "已过期"
        h < 24 -> "$h 小时"
        else -> "${h / 24} 天 ${h % 24} 小时"
    }

    fun clear(): Int = items.size.also { items.clear() }

    fun render(): String = buildString {
        appendLine("══ 补救时限 ══")
        appendLine()
        if (items.isEmpty()) {
            appendLine("  还没有条目。")
            appendLine()
            appendLine("  第三十八批每个组件都有自己的证据条，")
            appendLine("  但它们是**各自独立**的。")
            appendLine()
            appendLine("  真实情况是**一次事故会同时牵出好几项**，")
            appendLine("  而用户不会分别打开三张卡去对时间。")
            return@buildString
        }
        ordered().forEach { it ->
            val tag = when {
                it.gone -> "没了"
                it.urgent -> "**现在就得做**"
                it.slackH <= 24 -> "今天内"
                else -> "还早"
            }
            appendLine("  $tag　${it.what}")
            appendLine("    剩 ${h(it.leftH)}" +
                if (it.costH > 0) "　做要 ${h(it.costH)}　**可行动窗口 ${h(it.slackH)}**" else "")
            if (it.serves.size > 1)
                appendLine("    一次做完顶 ${it.serves.size} 处：${it.serves.joinToString(" / ")}")
            if (it.gone) appendLine("    **${it.miss}**")
        }
    }

    fun report(): String = buildString {
        appendLine("补救时限 —— 出事的第一分钟最值钱")
        appendLine()
        appendLine("第三十八批每个组件都有自己的证据条，但它们是**各自独立**的。")
        appendLine()
        appendLine("真实情况是**一次事故会同时牵出好几项**：")
        appendLine()
        appendLine("  搬家磕坏了桌子 → 要拍照（48h）")
        appendLine("                 → 要找搬家公司登记（当天）")
        appendLine("                 → 要查合同里怎么赔（7 天内）")
        appendLine()
        appendLine("用户不会分别打开三张卡去对时间。")
        appendLine()
        appendLine("**出事的第一分钟最值钱。**")
        appendLine()
        appendLine("补救窗口是**单调递减**的——它只会变短，不会变长。")
        appendLine("所以排序不是「重要度」，是**剩余时间**：")
        appendLine()
        appendLine("  一个不那么重要但还剩 2 小时的事，")
        appendLine("  比一个重要但还剩 20 天的事更该现在做。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**已过期的不静默删除，要说一次「没了」**")
        appendLine("  二、**同一份证据可能被多项共用**")
        appendLine("  三、**窗口只减不增，不因任何操作重置**")
        appendLine("  四、**要给「现在做这个要多久」**")
        appendLine()
        appendLine("第一条最容易做废：静默删掉，用户会以为还没到时间，")
        appendLine("过几天想起来再问，那时候更糟。")
        appendLine("**让人死心也是一种帮助。**")
        appendLine()
        appendLine("第三条也容易做错：用户点了「已办」就顺手把 leftH 重置成原值，")
        appendLine("那「还剩多久」永远不变，整个机制是假的。")
        appendLine()
        appendLine("第四条：只剩 2 小时，但做这件事要出门 1 小时——")
        appendLine("那其实已经来不及了。只给剩余时间不给耗时，")
        appendLine("用户会高估自己还来得及。")
    }

    fun demo(): String = buildString {
        appendLine("══ 一次搬家损坏 ══")
        appendLine()
        add("拍损坏照片", 48, 1, "事后补拍没用——说不清是谁磕的",
            listOf("搬家损坏登记", "快递索赔（如果走物流）"))
        add("找搬家公司现场登记", 6, 2, "过了当天就不认了")
        add("查合同赔付条款", 168, 0, "不知道赔多少就谈不了")
        add("保留包装箱", 24, 0, "丢了就没法证明运输问题")
        appendLine("  按剩余时间排（不是按重要度）：")
        appendLine()
        ordered().forEach {
            appendLine("    ${if (it.gone) "没了" else if (it.urgent) "**现在就做**" else if (it.slackH <= 24) "今天内" else "还早"}　${it.what}")
            appendLine("      剩 ${h(it.leftH)}" +
                (if (it.costH > 0) "　做要 ${h(it.costH)}　**可行动 ${h(it.slackH)}**" else ""))
            if (it.serves.size > 1)
                appendLine("      **一次做完顶 ${it.serves.size} 处**：${it.serves.joinToString(" / ")}")
        }
        appendLine()
        appendLine("  ── 为什么按剩余时间排而不是按重要度 ──")
        appendLine()
        appendLine("  「查合同赔付条款」很重要，但剩 7 天；")
        appendLine("  「找搬家公司登记」剩 6 小时且要花 2 小时——")
        appendLine("  **可行动窗口只有 4 小时，它排在最前。**")
        appendLine()
        appendLine("  ── 第四条：可行动窗口 ──")
        appendLine()
        add("去网点开证明", 2, 3, "来不及了")
        val it = ordered().first()
        appendLine("  剩 ${h(it.leftH)}，但做要 ${h(it.costH)}")
        appendLine("  → **可行动窗口 ${h(it.slackH)}**——已经来不及了")
        appendLine("  只说「还剩 2 小时」，用户会以为还赶得上。")
    }

    fun guide(): String = """
        zx_remedy(op=add|done|list|report|demo|clear|guide)

        补救时限：出事的第一分钟最值钱，按剩余时间给出"还来得及做什么"。"

        第三十八批每个组件的证据条是各自独立的，
        但一次事故会同时牵出好几项，用户不会分别打开三张卡对时间。

        补救窗口**单调递减**——只会变短不会变长。
        所以排序不是"重要度"，"是**剩余时间**。

        四条约束：
          一、**已过期的不静默删除，要说一次"没了"**（让人死心也是帮助）
          二、**同一份证据可能被多项共用**（拍一次顶两处）
          三、**窗口只减不增，不因任何操作重置**
          四、**要给"现在做这个要多久"**（可行动窗口 = 剩余 − 耗时）

          op=add <事项> <剩余小时> <耗时小时> <过期后果> [服务于多项,逗号分隔]
          op=done <事项>   标记完成（**不重置窗口**）
          op=demo  演示"为什么按剩余时间排"
    """.trimIndent()

    fun handle(op: String, a: String, b: String, c: String, d: String, e: String): String = when (op) {
        "add" -> {
            val lh = b.toIntOrNull()
            if (a.isBlank() || lh == null)
                return "用法：add <事项> <剩余小时> <耗时小时> <过期后果> [服务于多项]"
            val cost = c.toIntOrNull() ?: 0
            val serves = e.split(",").map { it.trim() }.filter { it.isNotBlank() }
            add(a, lh, cost, d.ifBlank { "过期就没有了" }, serves)
            "已加入「$a」：剩 ${h(lh)}" +
                if (cost > 0) "，做要 ${h(cost)}，**可行动 ${h(lh - cost)}**" else ""
        }
        "done" -> { done(a); "已完成「$a」——**窗口不重置**（重置了整个机制就是假的）" }
        "list" -> render()
        "render" -> render()
        "report" -> report()
        "demo" -> demo()
        "clear" -> "已清空 ${clear()} 项"
        else -> guide()
    }
}
