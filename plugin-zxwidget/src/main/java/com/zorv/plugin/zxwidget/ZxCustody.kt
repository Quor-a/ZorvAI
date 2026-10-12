package com.zorv.plugin.zxwidget

/**
 * 监管链 —— 一件东西经过了几次手，定位"第几次交接开始对不上"。
 *
 * **它和 ㊺ 事后归档不是一回事**
 *
 * 事后归档记的是**教训**：
 *
 *   "上次没拍照赔不到，下次进门先拍一圈"
 *
 * 它回答"下次怎么办"。
 *
 * 监管链记的是**过程**：
 *
 *   "这只猫：主人 → 寄养家庭 → 主人，\n*    第一次交接 4.8kg，第二次交接 4.5kg"
 *
 * 它回答"这次到底在哪一步出的问题"。
 *
 * ── 为什么需要它 ────────────────────────────────────────────
 *
 * 第三十九批每个组件都是**一次**交接：
 * 寄养交接、设备归还、退租结算。
 * 各管一段。
 *
 * 但一件东西往往会经过**好几次**手：
 *
 *   快递：卖家 → 揽收 → 转运 → 派送 → 买家（五次）
 *   设备：出租方 → A 公司 → B 公司 → 出租方（三次）
 *   房子：房东 → 租客甲 → 租客乙 → 房东（三次）
 *
 * 出了问题（磕了、坏了、少了），
 * **每一方都能证明"我经手时是好的"**——
 * 因为每一次交接都只记录了那一次的状态。
 *
 * 于是没人能定位是哪一段出的事，
 * 结果就是**离损耗最近的一方背锅**（通常是最后接手的人）。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **串起来看，才看得出是哪一段断的。**
 *
 * 单独看每一次交接，每次都是"交出时完好、接手时完好"。
 * 全部串起来，才有"第 2 次交出完好、第 3 次接手时坏了"——
 * 问题出在**第 2 段运输**，不是最后接手的人弄坏的。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**每一环都要记交出方和接手方**。
 *     只记"现在在谁手上"是**位置**，不是**链**。
 *     位置丢了可以找，链断了就定位不了。
 *
 * 二、**状态必须能和上一环对比**。
 *     每环记的状态要是同一种（都是重量、都是"完好/有划痕"），
 *     否则串起来没法比。这条最容易被忽略——
 *     上一环写"4.8kg"，这一环写"看起来瘦了"，**比不了**。
 *
 * 三、**断裂点要明确指出来**。
 *     发现对不上时要说是"第 N 环交出 → 第 N+1 环接手"之间，
 *     不能只说"中间出问题了"。
 *     **说不出是哪一段，等于没定位。**
 *
 * 四、**缺环要明说，不能跳过续接**。
 *     中间少了一环，如果把前后直接连起来，
 *     看起来是连续的，实际那段是空的——
 *     **而问题恰恰最可能出在没记的那一段。**
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 它认不出"某一环记的状态是假的"。
 * 每一环都是当事人自己写的，串起来只能保证**前后一致**，
 * 保证不了**与事实一致**。
 * 这正是 ㊹ 确认登记存在的意义——
 * 两个模块要一起用：确认让每一环可信，监管链让整条链可定位。
 */
object ZxCustody {

    data class Link(
        val seq: Int,
        val from: String,
        val to: String,
        val state: String,   // 这一环接手时的状态
        val when_: String
    )

    private val chain = ArrayList<Link>()
    private var seq = 0

    /** 二、状态必须能和上一环对比 */
    private fun comparable(a: String, b: String): Boolean {
        if (a == b) return true
        val numA = Regex("-?\\d+(\\.\\d+)?").find(a)?.value
        val numB = Regex("-?\\d+(\\.\\d+)?").find(b)?.value
        // 都是数值状态（重量/金额）→ 可比
        if (numA != null && numB != null) return true
        // 都是枚举状态（完好/有划痕）→ 用同一张词表则可比
        val enumish = listOf("完好", "有划痕", "破损", "缺失", "正常", "异常")
        return enumish.any { a.contains(it) } && enumish.any { b.contains(it) }
    }

    fun link(from: String, to: String, state: String, when_: String = ""): Pair<Boolean, String> {
        // 一、每一环都要记交出方和接手方
        if (from.isBlank() || to.isBlank())
            return false to "**缺 from 或 to**——" +
                "**只记「现在在谁手上」是位置，不是链**。" +
                "位置丢了可以找，链断了就定位不了"
        if (state.isBlank())
            return false to "**缺 state（接手时的状态）**——" +
                "**没有状态的链只是一串经手人名单**"
        // 四、缺环要明说
        if (chain.isNotEmpty() && chain.last().to != from)
            return false to "**链条断了**：上一环交给了「${chain.last().to}」，" +
                "这一环却从「$from」开始——" +
                "**中间少了一环，不能跳过续接**。" +
                "**问题恰恰最可能出在没记的那一段**"
        // 二、状态要和上一环可比
        if (chain.isNotEmpty() && !comparable(chain.last().state, state))
            return false to "**状态和上一环比不了**：上一环记的是「${chain.last().state}」，" +
                "这一环记的是「$state」——**串起来没法比**。" +
                "要么都记数值（4.8kg），要么都用同一套词（完好/有划痕）"
        chain.add(Link(++seq, from, to, state, when_))
        return true to "第 $seq 环：$from → $to　【$state】"
    }

    /** 三、断裂点要明确指出来 */
    fun findBreak(): String = buildString {
        if (chain.size < 2) {
            appendLine("少于两环，看不出断裂。")
            return@buildString
        }
        var found = false
        for (i in 0 until chain.size - 1) {
            val a = chain[i]; val b = chain[i + 1]
            if (a.state != b.state) {
                found = true
                appendLine("**第 ${a.seq} 环交出 → 第 ${b.seq} 环接手之间状态变了**")
                appendLine("  第 ${a.seq} 环（${a.from} → ${a.to}）接手时：【${a.state}】")
                appendLine("  第 ${b.seq} 环（${b.from} → ${b.to}）接手时：【${b.state}】")
                appendLine("  → 问题出在**第 ${a.seq} 环交出之后、第 ${b.seq} 环接手之前**")
                if (b.from.isNotBlank())
                    appendLine("  → 也就是在「${b.from}」手上这段时间")
                appendLine()
            }
        }
        if (!found) {
            appendLine("全程 ${chain.size} 环状态一致，没有断裂。")
            appendLine()
            appendLine("**但这只说明前后一致，不说明与事实一致**——")
            appendLine("每一环都是当事人自己写的。这正是 ㊹ 确认登记存在的意义。")
        }
    }

    fun clear(): Int = chain.size.also { chain.clear(); seq = 0 }

    fun render(): String = buildString {
        appendLine("══ 监管链 ══")
        appendLine()
        if (chain.isEmpty()) {
            appendLine("  还没有环节。")
            appendLine()
            appendLine("  第三十九批每个组件都是**一次**交接，各管一段。")
            appendLine("  但一件东西往往会经过**好几次**手。")
            appendLine()
            appendLine("  出了问题，每一方都能证明「我经手时是好的」——")
            appendLine("  因为每一次交接都只记录了那一次的状态。")
            return@buildString
        }
        chain.forEach { l ->
            appendLine("  第 ${l.seq} 环　${l.from} → ${l.to}　【${l.state}】" +
                if (l.when_.isNotBlank()) "　${l.when_}" else "")
        }
        appendLine()
        append(findBreak())
    }

    fun report(): String = buildString {
        appendLine("监管链 —— 一件东西经过了几次手，定位「第几次交接开始对不上」")
        appendLine()
        appendLine("它和 ㊺ 事后归档不是一回事：")
        appendLine()
        appendLine("  事后归档：记**教训**，回答「下次怎么办」")
        appendLine("  监管链  ：记**过程**，回答「这次哪一步出的问题」")
        appendLine()
        appendLine("第三十九批每个组件都是**一次**交接，各管一段。")
        appendLine("但一件东西往往会经过**好几次**手：")
        appendLine()
        appendLine("  快递：卖→揽收→转运→派送→买家（五次）")
        appendLine("  设备：出租方→A→B→出租方（三次）")
        appendLine()
        appendLine("出了问题，每一方都能证明「我经手时是好的」，")
        appendLine("因为每次交接都只记录了那一次的状态。")
        appendLine()
        appendLine("于是没人能定位是哪一段出的事，")
        appendLine("结果是**离损耗最近的一方背锅**——通常是最后接手的人。")
        appendLine()
        appendLine("**串起来看，才看得出是哪一段断的。**")
        appendLine()
        appendLine("单独看每次交接，每次都是「交出时完好、接手时完好」。")
        appendLine("全部串起来，才有「第 2 次交出完好、第 3 次接手时坏了」——")
        appendLine("问题出在**第 2 段**，不是最后接手的人弄坏的。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**每一环都要记交出方和接手方**")
        appendLine("  二、**状态必须能和上一环对比**")
        appendLine("  三、**断裂点要明确指出来**")
        appendLine("  四、**缺环要明说，不能跳过续接**")
        appendLine()
        appendLine("第二条最容易被忽略：上一环写「4.8kg」，这一环写「看起来瘦了」，")
        appendLine("**比不了**——串起来也是白串。")
        appendLine()
        appendLine("第四条：中间少了一环，如果把前后直接连起来，")
        appendLine("看起来是连续的，实际那段是空的——")
        appendLine("**而问题恰恰最可能出在没记的那一段。**")
    }

    fun demo(): String = buildString {
        appendLine("══ 一台投影仪的四次交接 ══")
        appendLine()
        link("出租方", "A 公司", "完好").let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        link("A 公司", "B 公司", "完好").let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        link("B 公司", "出租方", "底部有划痕").let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        appendLine()
        appendLine("  ── 定位断裂点 ──")
        appendLine()
        appendLine("  " + findBreak().replace("\n", "\n  "))
        appendLine()
        appendLine("  ── 第一段：只记位置不记链 ──")
        appendLine()
        clear()
        appendLine("  " + link("", "A 公司", "完好").second)
        appendLine()
        appendLine("  ── 缺一环，不能跳过续接 ──")
        appendLine()
        clear()
        link("出租方", "A 公司", "完好")
        appendLine("  " + link("C 公司", "出租方", "完好").second)
        appendLine()
        appendLine("  ── 状态比不了 ──")
        appendLine()
        clear()
        link("出租方", "A 公司", "4.8kg")
        appendLine("  " + link("A 公司", "出租方", "看起来瘦了").second)
        appendLine()
        appendLine("  三条都是**不崩也不报错**的——它们只是让这条链")
        appendLine("  **看起来完整，实际定位不了**，而这正是它存在的意义所在。")
    }

    fun guide(): String = """
        zx_custody(op=link|find|list|report|demo|clear|guide)

        监管链：一件东西经过了几次手，定位"第几次交接开始对不上"。"

        它和 ㊺ 事后归档不是一回事：
          事后归档：记**教训**，回答"下次怎么办"
          监管链  ：记**过程**，回答"这次哪一步出的问题"

        出了问题，每一方都能证明"我经手时是好的"—"—
        因为每次交接都只记录了那一次的状态。
        结果是**离损耗最近的一方背锅**（通常是最后接手的人）。

        **串起来看，才看得出是哪一段断的。**

        四条约束：
          一、**每一环都要记交出方和接手方**（只记位置不是链）
          二、**状态必须能和上一环对比**（4.8kg 和 "看起来瘦了" 比不了）
          三、**断裂点要明确指出来**（说不出哪一段等于没定位）
          四、**缺环要明说，不能跳过续接**

          op=link <交出方> <接手方> <接手时的状态> [时间]
          op=find   定位断裂点
          op=demo  演示"为什么每次交接都完好，串起来却坏了"
    """.trimIndent()

    fun handle(op: String, a: String, b: String, c: String, d: String): String = when (op) {
        "link" -> link(a, b, c, d).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        "find" -> findBreak()
        "list" -> render()
        "render" -> render()
        "report" -> report()
        "demo" -> demo()
        "clear" -> "已清空 ${clear()} 环"
        else -> guide()
    }
}
