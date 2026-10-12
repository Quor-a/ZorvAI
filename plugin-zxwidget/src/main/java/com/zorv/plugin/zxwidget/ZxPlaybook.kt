package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 场景剧本 —— 把一个生活任务串成有序步骤，每步绑一张卡片。
 *
 * **它和 ㉛ 分析流水线不是一回事**
 *
 * ㉛ 流水线串的是**数据流**：
 *
 *   看分布 → 检正态 → 选检验 → 看效应 → 校多重 → 出结论
 *
 * 每一步的输入是上一步的输出，全自动，**没有人在环里**。
 * 某步失败，下游全部待定。
 *
 * 这批串的是**任务流**：
 *
 *   量尺寸 → 选款式 → 下单 → 收货 → 试穿 → 不合身退换
 *
 * 关键差别在**有人的环节**：
 *
 *   收货    要等快递（几天，不是几毫秒）
 *   试穿    要人去试（可能不合身，流程要能回退）
 *   退换    不是"下一步"，是**回到选款式**
 *
 * 所以剧本要有的、流水线没有的三件事：
 *   **等待态**（这一步在等人/等物，不是在算）
 *   **回退**（走不通要能退回去，不是只能往前）
 *   **阻塞原因**（卡住了要写为什么，不能静默停在那）
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **生活任务会卡住，而卡住是最需要被看见的状态。**
 *
 * 流水线失败是"算不出来"，靠重试或换参数能解决。
 * 剧本卡住是"等快递三天没到"——**重试没有用，要么催要么换**。
 * 如果不显式区分"在等"和"失败了"，
 * 用户会反复刷新，而模型会以为该重试。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**步骤要么绑卡片，要么标为人工作业，不能两头空**。
 *     没有卡片的步骤要写清楚"由谁做"，
 *     否则它会永远停在"待办"，而没人知道该谁动手。
 *
 * 二、**回退必须能落到具体步骤**。
 *     "退回去"没说退到第几步，用户不知道要重做哪一步。
 *     所以每条回退边要写 to。
 *
 * 三、**等待态要带预计时长**。
 *     "在等快递"不带时长，用户不知道是正常还是已经延误。
 *     超过时长自动标"已超时"。
 *
 * 四、**剧本本身不改卡片状态**。
 *     它只记"这一步做了没、卡在哪"，
 *     卡片的实际内容仍由各自的组件和事务管理。
 *     剧本去改卡片，就会和 ⑪ 事务、⑮ CRDT 抢同一份状态。
 */
object ZxPlaybook {

    enum class St(val label: String) {
        TODO("待办"), DOING("进行中"),
        WAIT("等待中"), BLOCKED("阻塞"),
        DONE("已完成"), SKIPPED("已跳过")
    }

    data class Step(
        val id: String,
        val title: String,
        val card: String?,        // 绑定的组件名，null 表示人工作业
        val actor: String,        // 人工作业时：由谁做
        var state: St = St.TODO,
        var waitHours: Int = 0,   // 等待态的预计时长
        var waitedHours: Int = 0,
        var blockReason: String = "",
        var backTo: String? = null // 走不通时退回哪一步
    ) {
        val isOverdue: Boolean get() =
            state == St.WAIT && waitHours > 0 && waitedHours > waitHours
    }

    data class Book(
        val id: String,
        val name: String,
        val steps: MutableList<Step>
    ) {
        fun cur(): Step? = steps.firstOrNull { it.state == St.DOING }
        fun progress(): Pair<Int, Int> =
            steps.count { it.state == St.DONE || it.state == St.SKIPPED } to steps.size
        fun blocked(): List<Step> = steps.filter { it.state == St.BLOCKED }
        fun overdue(): List<Step> = steps.filter { it.isOverdue }
    }

    private val books = LinkedHashMap<String, Book>()
    private var seq = 0

    fun create(name: String, steps: List<Triple<String, String?, String>>): Book {
        val id = "pb${++seq}"
        val b = Book(id, name, steps.map { (t, c, a) ->
            Step("s${steps.indexOf(Triple(t, c, a)) + 1}", t, c, a)
        }.toMutableList())
        books[id] = b
        return b
    }

    fun get(id: String): Book? = books[id]

    fun all(): List<Book> = books.values.toList()

    /* ── 推进 ──────────────────────────────────────────────── */

    /**
     * 推进到下一步。
     * @return 说明，或失败原因
     */
    fun advance(id: String): String {
        val b = books[id] ?: return "剧本 $id 不存在"
        val cur = b.cur() ?: b.steps.firstOrNull { it.state == St.TODO }
            ?: return "全部步骤已结束"
        return when (cur.state) {
            St.TODO -> {
                cur.state = St.DOING
                "开始「${cur.title}」" +
                    if (cur.card != null) "（卡片：${cur.card}）"
                    else "（**人工作业**：${cur.actor}）"
            }
            St.DOING -> {
                // 二、回退要能落到具体步骤
                if (cur.card == null && cur.actor.isBlank())
                    return "「${cur.title}」既没绑卡片也没写由谁做——" +
                        "**不能推进**，否则它会永远停在待办而没人知道该谁动手"
                cur.state = if (cur.waitHours > 0) St.WAIT else St.DONE
                val nx = b.steps.getOrNull(b.steps.indexOf(cur) + 1)
                if (cur.state == St.WAIT)
                    "「${cur.title}」进入**等待**（预计 ${cur.waitHours} 小时）——" +
                        "**等待不是失败**，重试没有用"
                else "「${cur.title}」完成" +
                    if (nx != null) "，下一步「${nx.title}」" else "，**剧本结束**"
            }
            St.WAIT -> {
                cur.waitedHours += 1
                if (cur.isOverdue) "「${cur.title}」**已超时**" +
                    "（预计 ${cur.waitHours}h，已等 ${cur.waitedHours}h）——" +
                    "**该催或换方案了，不是继续等**"
                else "「${cur.title}」仍在等待（${cur.waitedHours}/${cur.waitHours}h）"
            }
            St.BLOCKED -> "「${cur.title}」已阻塞（${cur.blockReason}）——" +
                "**先解除阻塞才能推进**" +
                if (cur.backTo != null) "，可退回「${stepTitle(b, cur.backTo!!)}」" else ""
            else -> "「${cur.title}」已结束"
        }
    }

    private fun stepTitle(b: Book, id: String): String =
        b.steps.firstOrNull { it.id == id }?.title ?: id

    /** 标记阻塞——必须给原因 */
    fun block(id: String, stepId: String, reason: String, backTo: String?): String {
        val b = books[id] ?: return "剧本 $id 不存在"
        val s = b.steps.firstOrNull { it.id == stepId } ?: return "步骤 $stepId 不存在"
        if (reason.isBlank())
            return "**阻塞必须写原因**——不写原因的阻塞和静默停住没有区别，" +
                "用户不知道要做什么才能解除"
        s.state = St.BLOCKED
        s.blockReason = reason
        s.backTo = backTo
        return "「${s.title}」已阻塞：$reason" +
            if (backTo != null) "，可退回「${stepTitle(b, backTo)}」" else ""
    }

    /** 退回指定步骤 */
    fun rollback(id: String, stepId: String): String {
        val b = books[id] ?: return "剧本 $id 不存在"
        val i = b.steps.indexOfFirst { it.id == stepId }
        if (i < 0) return "步骤 $stepId 不存在"
        b.steps[i].state = St.TODO
        b.steps[i].waitedHours = 0
        b.steps[i].blockReason = ""
        for (k in i + 1 until b.steps.size) {
            if (b.steps[k].state != St.DONE) b.steps[k].state = St.TODO
        }
        return "已退回「${b.steps[i].title}」" +
            "（这一步重做，后面的未完步骤也回到待办）"
    }

    fun skip(id: String, stepId: String): String {
        val b = books[id] ?: return "剧本 $id 不存在"
        val s = b.steps.firstOrNull { it.id == stepId } ?: return "步骤 $stepId 不存在"
        s.state = St.SKIPPED
        return "「${s.title}」已跳过"
    }

    /* ── 输出 ──────────────────────────────────────────────── */

    fun render(): String = buildString {
        if (books.isEmpty()) {
            appendLine("══ 场景剧本 ══")
            appendLine()
            appendLine("  还没有剧本。")
            appendLine()
            appendLine("  它和 ㉛ 分析流水线不是一回事：")
            appendLine()
            appendLine("    流水线串**数据流**：看分布 → 检正态 → 选检验 → 出结论")
            appendLine("    剧本串**任务流**：量尺寸 → 选款 → 下单 → 收货 → 试穿")
            appendLine()
            appendLine("  差别在**有人的环节**——收货要等几天、试穿要人去试、")
            appendLine("  不合身要退回选款那一步。")
            return@buildString
        }
        appendLine("══ 场景剧本 ══")
        books.values.forEach { b ->
            val (d, t) = b.progress()
            appendLine()
            appendLine("  ${b.name}　$d/$t")
            b.steps.forEach { s ->
                val mark = when (s.state) {
                    St.DONE -> "✓"; St.DOING -> "▶"
                    St.WAIT -> if (s.isOverdue) "⏰" else "⏳"
                    St.BLOCKED -> "✗"; St.SKIPPED -> "⊘"; St.TODO -> "·"
                }
                appendLine("    $mark ${s.title}")
                when {
                    s.card != null -> appendLine("        卡片 ${s.card}")
                    s.actor.isNotBlank() -> appendLine("        人工作业：${s.actor}")
                    else -> appendLine("        **既没卡片也没写由谁做**")
                }
                if (s.state == St.WAIT)
                    appendLine("        等待 ${s.waitedHours}/${s.waitHours}h" +
                        if (s.isOverdue) "　**已超时**" else "")
                if (s.state == St.BLOCKED)
                    appendLine("        阻塞：${s.blockReason}" +
                        if (s.backTo != null) "（可退回 ${stepTitle(b, s.backTo!!)}）" else "")
            }
        }
    }

    fun report(): String = buildString {
        appendLine("场景剧本 —— 把一个生活任务串成有序步骤，每步绑一张卡片")
        appendLine()
        appendLine("它和 ㉛ 分析流水线不是一回事：")
        appendLine()
        appendLine("  流水线串**数据流**：")
        appendLine("    看分布 → 检正态 → 选检验 → 看效应 → 校多重 → 出结论")
        appendLine("    每步输入是上一步输出，全自动，**没有人在环里**")
        appendLine()
        appendLine("  剧本串**任务流**：")
        appendLine("    量尺寸 → 选款式 → 下单 → 收货 → 试穿 → 不合身退换")
        appendLine()
        appendLine("差别全在**有人的环节**：")
        appendLine()
        appendLine("  收货    要等快递（几天，不是几毫秒）")
        appendLine("  试穿    要人去试（可能不合身，流程要能回退）")
        appendLine("  退换    不是「下一步」，是**回到选款式**")
        appendLine()
        appendLine("所以剧本要有的、流水线没有的三件事：")
        appendLine("  **等待态**（这一步在等人/等物，不是在算）")
        appendLine("  **回退**（走不通要能退回去，不是只能往前）")
        appendLine("  **阻塞原因**（卡住了要写为什么，不能静默停在那）")
        appendLine()
        appendLine("贯穿的一条：**生活任务会卡住，")
        appendLine("而卡住是最需要被看见的状态。**")
        appendLine()
        appendLine("流水线失败是「算不出来」，重试或换参数能解决。")
        appendLine("剧本卡住是「等快递三天没到」——**重试没有用，要么催要么换**。")
        appendLine("不显式区分「在等」和「失败了」，")
        appendLine("用户会反复刷新，而模型会以为该重试。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**步骤要么绑卡片，要么标为人工作业，不能两头空**")
        appendLine("  二、**回退必须能落到具体步骤**")
        appendLine("  三、**等待态要带预计时长**")
        appendLine("  四、**剧本本身不改卡片状态**")
    }

    fun demo(): String = buildString {
        val b = create("买一件合身的衬衫", listOf(
            Triple("量尺寸", "zx_sizing", ""),
            Triple("选款式", "zx_shop", ""),
            Triple("下单", "zx_order", ""),
            Triple("收货", null, "等快递"),
            Triple("试穿", null, "本人"),
            Triple("不合身退换", "zx_order", "")
        ))
        b.steps[3].waitHours = 48
        appendLine("══ 一次剧本推进 ══")
        appendLine()
        appendLine("  ${b.name}")
        appendLine()
        listOf(
            { advance(b.id) },
            { advance(b.id) },
            { advance(b.id) },
            { advance(b.id) },
            { advance(b.id) },
            { advance(b.id) },
            { advance(b.id) }
        ).forEach { f -> appendLine("  " + f()) }
        appendLine()
        appendLine("  ── 关键在「收货」那一步 ──")
        appendLine()
        appendLine("  它**没有绑卡片**，是等人（快递）。")
        appendLine("  所以推进后进入**等待态**而不是完成：")
        appendLine("    「收货」进入**等待**（预计 48 小时）——")
        appendLine("     **等待不是失败**，重试没有用")
        appendLine()
        appendLine("  等到第 49 小时：")
        appendLine("    「收货」**已超时**（预计 48h，已等 49h）——")
        appendLine("     **该催或换方案了，不是继续等**")
        appendLine()
        appendLine("  试穿不合身 → 退回「选款式」：")
        appendLine("    " + rollback(b.id, "s2"))
        appendLine()
        appendLine("  ── 为什么不能直接用流水线 ──")
        appendLine()
        appendLine("  流水线里「收货」会是一条永远算不出来的步骤，")
        appendLine("  下游全部待定，界面上就是一片灰。")
        appendLine("  而真实情况是：**它没失败，它在等**。")
    }

    fun guide(): String = """
        zx_playbook(op=create|advance|block|rollback|skip|render|report|demo|guide)

        场景剧本：把一个生活任务串成有序步骤，每步绑一张卡片。

        它和 ㉛ 分析流水线不是一回事：

          流水线串**数据流**：看分布 → 检正态 → 选检验 → 出结论
                            全自动，**没有人在环里**
          剧本串**任务流**：量尺寸 → 选款 → 下单 → 收货 → 试穿
                            **有人的环节**

        差别全在"有人的环节"："
          收货    要等快递（几天，不是几毫秒）
          试穿    要人去试（可能不合身，流程要能回退）
          退换    不是"下一步"，"是**回到选款式**

        所以剧本要有的、流水线没有的三件事：
          **等待态**（这一步在等人/等物，不是在算）
          **回退**（走不通要能退回去，不是只能往前）
          **阻塞原因**（卡住了要写为什么，不能静默停在那）

        四条约束：
          一、**步骤要么绑卡片，要么标为人工作业，不能两头空**
          二、**回退必须能落到具体步骤**
          三、**等待态要带预计时长**
          四、**剧本本身不改卡片状态**

          op=create   建一个新剧本
          op=advance  推进一步（会自动处理等待/超时）
          op=block    标记阻塞，**必须给原因**
          op=rollback 退回指定步骤
          op=demo     演示"收货"那一步为什么不能当失败
    """.trimIndent()

    fun handle(op: String, a: String, b2: String, c: String): String = when (op) {
        "render" -> render()
        "report" -> report()
        "demo" -> demo()
        "advance" -> advance(a)
        "rollback" -> rollback(a, b2)
        "skip" -> skip(a, b2)
        "block" -> block(a, b2, c, null)
        else -> guide()
    }
}
