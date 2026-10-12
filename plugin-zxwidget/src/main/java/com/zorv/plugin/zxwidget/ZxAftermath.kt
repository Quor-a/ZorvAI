package com.zorv.plugin.zxwidget

/**
 * 事后归档 —— 把一次事故的处置过程固化成预案。
 *
 * **它和 ㊶ 场景记忆不是一回事**
 *
 * 场景记忆记的是**顺利的那次**：
 *
 *   "上次租房画了 5 张卡，你只点了 2 张，这次可以先不画那 3 张"
 *
 * 它优化的是"少做无用功"。
 *
 * 事后归档记的是**出事的那次**：
 *
 *   "上次搬家磕坏了桌子，因为没当场拍照，赔不到。\n*    下次搬家第一件事：进门先拍一圈。"
 *
 * 它优化的是"下次别再吃同一个亏"。
 *
 * ── 为什么出事的那次更值得记 ────────────────────────────────
 *
 * 顺利的流程，下次照做就行——**错了也没什么代价**。
 * 出事的流程，**错了代价很大，而且同样的错会再犯**：
 *
 *   上次没拍照 → 赔不到 → 这次还没拍照（因为没人提醒）
 *   上次超支 2 万 → 这次还是超支（因为没记下是哪一项超的）
 *
 * 顺利的经验不记也能过，出错的经验不记一定会再错。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **要固化成"下次第一步做什么"，而不是"这次做错了什么"。**
 *
 *   记录"这次没拍照"     → 是陈述，下次还是不知道什么时候拍
 *   记录"下次进门先拍一圈" → 是动作，下次知道第一步做什么
 *
 * 所以每条归档必须是**一个动作 + 一个触发时机**：
 *
 *   动作：进门先拍一圈
 *   时机：搬家师傅到场的第一时间
 *
 * 没有时机的动作等于没说——用户不知道该在什么时候做。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**必须有"这次做错了什么"作为依据**。
 *     只写"下次要先拍照"，用户不知道为什么，
 *     没理由的规定不会被遵守。
 *     所以每条归档都带一条 `cause`（这次踩的坑）。
 *
 * 二、**时机要具体到能被触发**。
 *     "记得拍照"不叫时机，"师傅到场的第一时间"才叫。
 *     不具体的时机=没有时机，会被拦。
 *
 * 三、**同类归档要能去重**。
 *     搬两次家都会记"进门先拍一圈"，
 *     存两条的话下次会列两遍，用户开始忽略重复的——
 *     **被忽略的预案等于没有预案**。
 *
 * 四、**预案不自动执行，只提示**。
 *     自动执行意味着系统在用户不知情的情况下做事，
 *     而预案是建议——**用户有权判断这次不适用**。
 *     比如这次搬的是空房，没什么可拍的。
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 它认不出"这次其实不适用"：
 * 上次搬的是有家具的房子，这次搬空房，
 * 预案还是会提示"进门先拍一圈"。
 * 所以提示里必须带上触发它的那条原因，让用户自己判断。
 */
object ZxAftermath {

    data class Playbook(
        val scene: String,    // 场景
        val cause: String,    // 这次做错了什么（依据）
        val action: String,   // 下次第一步做什么
        val when_: String,    // 触发时机（必须具体）
        val cost: String      // 不做会怎样
    )

    private val books = ArrayList<Playbook>()

    /** 判定时机是否具体——含"时/前/后/第一/立刻/当场"等触发词 */
    private fun specific(w: String): Boolean {
        val keys = listOf("时", "前", "后", "第一", "立刻", "当场", "当天",
            "之前", "之后", "进场", "到场", "开始", "签收")
        return keys.any { w.contains(it) } && w.length >= 4
    }

    fun add(scene: String, cause: String, action: String, when_: String, cost: String): Pair<Boolean, String> {
        // 一、必须有依据
        if (cause.isBlank())
            return false to "**缺 cause（这次做错了什么）**——" +
                "只写「下次要拍照」而不写为什么，用户没理由遵守"
        // 二、时机要具体
        if (!specific(when_))
            return false to "**时机「$when_」不够具体**——" +
                "「记得拍照」不叫时机，「师傅到场的第一时间」才叫。" +
                "**不具体的时机 = 没有时机**，用户不知道什么时候做"
        if (action.isBlank()) return false to "缺 action（下次第一步做什么）"
        if (cost.isBlank())
            return false to "**缺 cost（不做会怎样）**——" +
                "**不写代价，用户不知道这条有多重要**"
        // 三、同类去重
        val dup = books.indexOfFirst { it.scene == scene && it.action == action }
        if (dup >= 0) {
            books[dup] = Playbook(scene, cause, action, when_, cost)
            return true to "「$scene」已有一条相同的预案，已更新（**不重复列**）"
        }
        books.add(Playbook(scene, cause, action, when_, cost))
        return true to "已记入「$scene」"
    }

    /** 下次同类场景的预案 */
    fun advise(scene: String): String? {
        val mine = books.filter { it.scene == scene }
        if (mine.isEmpty()) return null
        return buildString {
            appendLine("「$scene」的预案（来自上次出的事）：")
            mine.forEach { p ->
                appendLine()
                appendLine("  → **${p.action}**")
                appendLine("    时机：${p.when_}")
                appendLine("    依据：${p.cause}")
                appendLine("    不做会怎样：${p.cost}")
            }
            appendLine()
            appendLine("  **这些是提示不是自动执行**——" +
                "这次搬的是空房就没必要拍，你自己判断。")
        }.trimEnd()
    }

    fun list(): List<Playbook> = books.toList()

    fun clear(): Int = books.size.also { books.clear() }

    fun render(): String = buildString {
        appendLine("══ 事后归档 ══")
        appendLine()
        if (books.isEmpty()) {
            appendLine("  还没有归档。")
            appendLine()
            appendLine("  它和 ㊶ 场景记忆不是一回事：")
            appendLine()
            appendLine("    场景记忆：上次顺利的那次，优化「少做无用功」")
            appendLine("    事后归档：上次出事的那次，优化「别再吃同一个亏」")
            return@buildString
        }
        books.forEach { p ->
            appendLine("  ${p.scene}　→ **${p.action}**")
            appendLine("    时机：${p.when_}")
            appendLine("    依据：${p.cause}")
            appendLine("    不做：${p.cost}")
        }
    }

    fun report(): String = buildString {
        appendLine("事后归档 —— 把一次事故的处置过程固化成预案")
        appendLine()
        appendLine("它和 ㊶ 场景记忆不是一回事：")
        appendLine()
        appendLine("  场景记忆：上次顺利的那次，优化「少做无用功」")
        appendLine("  事后归档：上次出事的那次，优化「别再吃同一个亏」")
        appendLine()
        appendLine("**为什么出事的那次更值得记**")
        appendLine()
        appendLine("顺利的流程，下次照做就行——错了也没什么代价。")
        appendLine("出事的流程，**错了代价很大，而且同样的错会再犯**：")
        appendLine()
        appendLine("  上次没拍照 → 赔不到 → 这次还没拍照（因为没人提醒）")
        appendLine("  上次超支 2 万 → 这次还是超支（因为没记下是哪一项超的）")
        appendLine()
        appendLine("顺利的经验不记也能过，出错的经验不记一定会再错。")
        appendLine()
        appendLine("**要固化成「下次第一步做什么」，而不是「这次做错了什么」。**")
        appendLine()
        appendLine("  记录「这次没拍照」      → 陈述，下次还是不知道什么时候拍")
        appendLine("  记录「下次进门先拍一圈」 → 动作，下次知道第一步做什么")
        appendLine()
        appendLine("所以每条归档必须是**一个动作 + 一个触发时机**：")
        appendLine("没有时机的动作等于没说——用户不知道该在什么时候做。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**必须有「这次做错了什么」作为依据**")
        appendLine("  二、**时机要具体到能被触发**")
        appendLine("  三、**同类归档要能去重**")
        appendLine("  四、**预案不自动执行，只提示**")
        appendLine()
        appendLine("第三条最容易做废：搬两次家都会记「进门先拍一圈」，")
        appendLine("存两条下次会列两遍，用户开始忽略重复——")
        appendLine("**被忽略的预案等于没有预案。**")
        appendLine()
        appendLine("第四条：自动执行意味着系统在用户不知情时做事，")
        appendLine("而预案是建议——**用户有权判断这次不适用**。")
    }

    fun demo(): String = buildString {
        appendLine("══ 一次搬家事故 → 预案 ══")
        appendLine()
        appendLine("  上次搬家，桌子磕坏了，赔不到。")
        appendLine("  原因：没当场拍照，事后说不清是谁磕的。")
        appendLine()
        val (ok1, m1) = add("搬家", "没当场拍照，事后说不清是谁磕的",
            "进门先拍一圈（空房状态）", "搬家师傅到场的第一时间",
            "磕坏了赔不到，上次损失 1200")
        appendLine("  归档：${if (ok1) "✓" else "✗"} $m1")
        appendLine()
        val (ok2, m2) = add("搬家", "同上", "进门先拍一圈（空房状态）",
            "搬家师傅到场的第一时间", "磕坏了赔不到")
        appendLine("  再记一次：${if (ok2) "✓" else "✗"} $m2  ← **同类去重**")
        appendLine()
        val (ok3, m3) = add("搬家", "预算没按项记，超了 2 万不知道超在哪",
            "每笔增项当场记在手机上", "工人说要加项的当场",
            "结算时才发现超支，砍不掉了")
        appendLine("  归档：${if (ok3) "✓" else "✗"} $m3")
        appendLine()
        appendLine("  ── 时机不具体会被拦 ──")
        appendLine()
        val (ok4, m4) = add("搬家", "忘了留包装箱", "记得留包装箱",
            "记得", "丢了没法证明运输问题")
        appendLine("  ${if (ok4) "✓" else "✗"} $m4")
        appendLine()
        appendLine("  ── 半年后，用户说「我要搬家了」──")
        appendLine()
        advise("搬家")?.lines()?.forEach { appendLine("    $it") }
        appendLine()
        appendLine("  ── 为什么和场景记忆不同 ──")
        appendLine()
        appendLine("  场景记忆会说「上次搬家你只点了 2 张卡」——")
        appendLine("  那说的是**画几张卡**，答不了**进门该不该先拍照**。")
        appendLine("  而后者才是这次真会吃亏的地方。")
    }

    fun guide(): String = """
        zx_aftermath(op=add|advise|list|report|demo|clear|guide)

        事后归档：把一次事故的处置过程固化成预案。

        它和 ㊶ 场景记忆不是一回事：
          场景记忆：上次**顺利**的那次，优化"少做无用功"
          事后归档：上次**出事**的那次，优化"别再吃同一个亏"

        **为什么出事的那次更值得记**：
        顺利的流程下次照做就行，错了也没什么代价；
        出错的经验不记一定会再错。

        贯穿一条：**要固化成"下次第一步做什么"，"
        而不是"这次做错了什么"**。
        没有时机的动作等于没说。

        四条约束：
          一、**必须有"这次做错了什么"作为依据**
          二、**时机要具体到能被触发**（"记得拍照"不叫时机）
          三、**同类归档要能去重**（重复会让用户开始忽略）
          四、**预案不自动执行，只提示**

          op=add <场景> <做错了什么> <下次第一步> <触发时机> <不做会怎样>
          op=advise <场景>   给出该场景的预案
          op=demo  演示"为什么和场景记忆不同"
    """.trimIndent()

    fun handle(op: String, a: String, b: String, c: String, d: String, e: String): String = when (op) {
        "add" -> add(a, b, c, d, e).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        "advise" -> advise(a) ?: "「$a」还没有归档——" +
            "**只有真的出过事才该写预案**，没出事不用编"
        "list" -> render()
        "render" -> render()
        "report" -> report()
        "demo" -> demo()
        "clear" -> "已清空 ${clear()} 条"
        else -> guide()
    }
}
