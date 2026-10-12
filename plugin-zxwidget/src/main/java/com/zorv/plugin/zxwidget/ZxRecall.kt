package com.zorv.plugin.zxwidget

/**
 * 场景记忆 —— 记住"这类场景上次是怎么走完的"。
 *
 * **它和 ㉗ 偏好学习不是一回事**
 *
 * 偏好学习回答的是**组件级**的问题：
 *
 *   "这个用户在表格和图表之间，更常用哪个？"
 *
 * 它按组件名聚合，跟你在干什么无关。
 *
 * 场景记忆回答的是**场景级**的问题：
 *
 *   "上次租房，我画了 5 张卡，用户只点了 1 张——\n*    下次别再画 5 张了。"
 *
 * ── 它解决的具体问题 ────────────────────────────────────────
 *
 * 第三十七批那些组件（装修 / 就医 / 租房 / 报修 / 签证）
 * **都是重复场景**：一个人一辈子要搬几次家、看几次病、办几次签证。
 *
 * 但模型每次都从零开始：重新决定画哪些卡、按什么顺序。
 *
 *   上次：画了房源对比 → 通勤图 → 合同要点 → 搬家清单 → 水电过户
 *   实际：用户只点了房源对比和通勤图，后三张一眼没看
 *
 * 这次又从头画一遍五张。**多出来的三张不是"帮到忙"，是打断。**
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **要记"用户实际用了什么"，而不是"我画了什么"。**
 *
 * 只记画了什么，得到的结论是"上次画了 5 张"——
 * 这个结论会让人下次再画 5 张，而实际上有 3 张是浪费。
 *
 * 所以每条记录要分开存三件事：
 *
 *   drawn   画了哪些
 *   used    用户点了哪些（**真正重要的**）
 *   dropped 用户明确关掉/跳过哪些
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**认不出的场景不猜**。
 *     "帮我看看这个"算不出场景，返回 null。
 *     猜一个相近的场景会更糟——
 *     它会拿上次搬家的经验去指导这次看病，而两者毫无关系。
 *
 * 二、**最近优先，但要衰减**。
 *     三年前的租房经验（那时候的价格、通勤容忍度）现在不适用。
 *     半衰期 180 天，超期的记录降权但**不删除**——
 *     删了就永远学不会"这类场景需要什么"。
 *
 * 三、**要给"这次该少画什么"，不只"上次画了什么"**。
 *     "上次画了 5 张"是陈述；
 *     "上次 5 张里有 3 张你没点，这次先不画"才是建议。
 *
 * 四、**不自动改卡片，只给建议**。
 *     自动跳过某张卡，用户就永远看不到它，
 *     而这次他可能正需要——
 *     **他上次没点，不代表这次不需要。**
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 它认不出"场景相同但处境变了"：
 * 同样是租房，这次预算砍半、要带宠物，
 * 上次的经验仍然会被搬出来。
 * 所以建议里必须带上上次的上下文摘要，让用户自己判断适不适用。
 */
object ZxRecall {

    data class Ep(
        val scene: String,
        val ctx: String,                 // 上次的处境摘要
        val drawn: List<String>,         // 画了哪些组件
        val used: MutableSet<String>,    // 用户点了哪些
        val dropped: MutableSet<String>, // 明确关掉/跳过
        val at: Long                     // 逻辑时钟
    ) {
        val waste: List<String> get() = drawn.filter { it !in used && it !in dropped }
    }

    private val eps = ArrayList<Ep>()
    private var clock = 0L
    private const val HALF_LIFE = 180L

    /** 场景词表——认不出就返回 null，不猜 */
    private val SCENES = mapOf(
        "租房" to listOf("租房", "找房", "房源", "租", "搬家前", "看房"),
        "搬家" to listOf("搬家", "迁", "搬", "入住", "乔迁"),
        "装修" to listOf("装修", "翻新", "改造", "施工", "装"),
        "就医" to listOf("看病", "就医", "门诊", "挂号", "医院", "陪诊"),
        "报修" to listOf("报修", "维修", "漏水", "坏了", "物业"),
        "签证" to listOf("签证", "签", "出国", "使馆", "递签"),
        "面试" to listOf("面试", "求职", "投简历", "offer", "面"),
        "报销" to listOf("报销", "发票", "差旅", "票据"),
        "寄件" to listOf("寄", "快递", "寄件", "快递单"),
        "备考" to listOf("备考", "考试", "复习", "考研", "期末")
    )

    /** 从一句话里认场景；认不出返回 null */
    fun sceneOf(q: String): String? {
        var best: String? = null; var hit = 0
        SCENES.forEach { (k, ws) ->
            val n = ws.count { q.contains(it) }
            if (n > hit) { hit = n; best = k }
        }
        return best
    }

    /** 记一次场景（画了什么 / 用了什么 / 跳过了什么） */
    fun remember(scene: String, ctx: String, drawn: List<String>): Ep {
        clock++
        val e = Ep(scene, ctx, drawn, LinkedHashSet(), LinkedHashSet(), clock)
        eps.add(e)
        if (eps.size > 60) eps.removeAt(0)
        return e
    }

    fun markUsed(scene: String, comp: String) {
        eps.lastOrNull { it.scene == scene }?.used?.add(comp)
    }
    fun markDropped(scene: String, comp: String) {
        eps.lastOrNull { it.scene == scene }?.dropped?.add(comp)
    }

    /**
     * 下次同类场景的建议。
     * @return null 表示没有可依据的历史
     */
    fun advise(q: String): String? {
        // 一、认不出的场景不猜
        val s = sceneOf(q) ?: return null
        val mine = eps.filter { it.scene == s }
        if (mine.isEmpty()) return null
        val last = mine.last()
        // 二、衰减
        val age = clock - last.at
        val w = Math.pow(0.5, age.toDouble() / HALF_LIFE)
        val un = last.waste
        val useful = last.drawn.filter { it in last.used }
        return buildString {
            appendLine("「$s」上一次（${last.ctx}）：")
            appendLine("  画了 ${last.drawn.size} 张，你实际点了 ${last.used.size} 张")
            if (useful.isNotEmpty())
                appendLine("  用上的：${useful.joinToString(" / ")}")
            if (last.dropped.isNotEmpty())
                appendLine("  明确跳过的：${last.dropped.joinToString(" / ")}")
            // 三、要给"这次该少画什么"
            if (un.isNotEmpty()) {
                appendLine()
                appendLine("  **这次可以先不画**：${un.joinToString(" / ")}")
                appendLine("  上次这几张你没点也没关，多半是没用上。")
                appendLine("  **但不自动跳过——你上次没点，不代表这次不需要。**")
            } else {
                appendLine()
                appendLine("  上次画的每张都用上了，可以照原样再来一遍。")
            }
            // 诚实带上衰减
            if (w < 0.6) {
                appendLine()
                appendLine("  **这条经验已过 ${age} 天（权重 ${"%.2f".format(w)}）**——")
                appendLine("  处境可能变了，建议里的上下文摘要：${last.ctx}")
            }
        }.trimEnd()
    }

    fun list(): List<Ep> = eps.toList()

    fun clear(): Int = eps.size.also { eps.clear() }

    fun render(): String = buildString {
        appendLine("══ 场景记忆 ══")
        appendLine()
        if (eps.isEmpty()) {
            appendLine("  还没有记录。")
            appendLine()
            appendLine("  它和 ㉗ 偏好学习不是一回事：")
            appendLine()
            appendLine("    偏好学习：这个用户在表格和图表之间更常用哪个（**组件级**）")
            appendLine("    场景记忆：上次租房画了 5 张只点了 1 张，" +
                "下次别再画 5 张（**场景级**）")
            return@buildString
        }
        eps.forEach { e ->
            appendLine("  ${e.scene}　${e.ctx}")
            appendLine("    画了 ${e.drawn.size}：${e.drawn.joinToString(" / ")}")
            appendLine("    用了 ${e.used.size}：${e.used.joinToString(" / ").ifBlank { "（一张没点）" }}")
            if (e.dropped.isNotEmpty())
                appendLine("    跳过 ${e.dropped.size}：${e.dropped.joinToString(" / ")}")
            if (e.waste.isNotEmpty())
                appendLine("    **浪费 ${e.waste.size}：${e.waste.joinToString(" / ")}**")
        }
    }

    fun report(): String = buildString {
        appendLine("场景记忆 —— 记住「这类场景上次是怎么走完的」")
        appendLine()
        appendLine("它和 ㉗ 偏好学习不是一回事：")
        appendLine()
        appendLine("  偏好学习：这个用户在表格和图表之间更常用哪个（**组件级**）")
        appendLine("  场景记忆：上次租房画了 5 张只点了 1 张，" +
            "下次别再画 5 张（**场景级**）")
        appendLine()
        appendLine("第三十七批那些组件（装修 / 就医 / 租房 / 报修 / 签证）")
        appendLine("**都是重复场景**：一个人一辈子要搬几次家、看几次病、办几次签证。")
        appendLine()
        appendLine("但模型每次都从零开始：重新决定画哪些卡、按什么顺序。")
        appendLine()
        appendLine("  上次：房源对比 → 通勤图 → 合同要点 → 搬家清单 → 水电过户")
        appendLine("  实际：用户只点了房源对比和通勤图，后三张一眼没看")
        appendLine()
        appendLine("这次又从头画五张。**多出来的三张不是「帮忙」，是打断。**")
        appendLine()
        appendLine("贯穿一条：**要记「用户实际用了什么」，")
        appendLine("而不是「我画了什么」。**")
        appendLine()
        appendLine("只记画了什么，得到的结论是「上次画了 5 张」——")
        appendLine("这个结论会让人下次再画 5 张，而实际上有 3 张是浪费。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**认不出的场景不猜**")
        appendLine("  二、**最近优先，但要衰减**")
        appendLine("  三、**要给「这次该少画什么」，不只「上次画了什么」**")
        appendLine("  四、**不自动改卡片，只给建议**")
        appendLine()
        appendLine("第四条最要紧：自动跳过某张卡，用户就永远看不到它，")
        appendLine("而这次他可能正需要——**他上次没点，不代表这次不需要。**")
        appendLine()
        appendLine("它认不出「场景相同但处境变了」：")
        appendLine("同样是租房，这次预算砍半、要带宠物，")
        appendLine("上次的经验仍会被搬出来。")
        appendLine("所以建议里必须带上上次处境，让用户自己判断适不适用。")
    }

    fun demo(): String = buildString {
        appendLine("══ 一次场景记忆 ══")
        appendLine()
        val e = remember("租房", "预算 3000，通勤 30 分钟内",
            listOf("zx_rental", "zx_commute", "zx_contract", "zx_tripkit", "zx_utility"))
        markUsed("租房", "zx_rental")
        markUsed("租房", "zx_commute")
        markDropped("租房", "zx_contract")
        appendLine("  第一次租房：画了 5 张")
        appendLine("    用户点了 zx_rental、zx_commute")
        appendLine("    明确关掉 zx_contract")
        appendLine("    **zx_tripkit、zx_utility 没点也没关**")
        appendLine()
        appendLine("  半年后，用户说「我再看看房」：")
        appendLine()
        advise("我再看看房")?.lines()?.forEach { appendLine("    $it") }
        appendLine()
        appendLine("  ── 为什么不能只用偏好学习 ──")
        appendLine()
        appendLine("  偏好学习会说「用户常用 zx_rental」——对，但没用。")
        appendLine("  它答不了「这次还要不要画搬家清单」。")
        appendLine("  而那正是重复场景里最该省掉的。")
        appendLine()
        appendLine("  ── 为什么不建议自动跳过 ──")
        appendLine()
        appendLine("  这次他可能正要搬家（上次租完没搬是因为合租）。")
        appendLine("  自动跳过 zx_tripkit，他就永远看不到那张卡。")
    }

    fun guide(): String = """
        zx_recall(op=advise|remember|used|dropped|list|report|demo|clear|guide)

        场景记忆：记住"这类场景上次是怎么走完的"。"

        它和 ㉗ 偏好学习不是一回事：
          偏好学习：表格还是图表更常用（**组件级**）
          场景记忆：上次租房画了 5 张只点了 1 张（**场景级**）

        第三十七批那些组件（装修 / 就医 / 租房 / 报修 / 签证）
        **都是重复场景**，但模型每次都从零开始重新决定画哪些卡。

        贯穿一条：**要记"用户实际用了什么"，"而不是"我画了什么"**。
        只记画了什么，会让人下次再画同样的 5 张，而其中 3 张是浪费。

        四条约束：
          一、**认不出的场景不猜**
          二、**最近优先，但要衰减**
          三、**要给"这次该少画什么"，"不只"上次画了什么"**
          四、**不自动改卡片，只给建议**

          op=advise   <一句话>   按场景给建议
          op=remember <场景> <处境> <组件,组件>   记一次
          op=used     <场景> <组件>   标为"用上了"
          op=demo     演示"为什么不能只用偏好学习"
    """.trimIndent()

    fun handle(op: String, a: String, b: String, c: String): String = when (op) {
        "advise" -> advise(a) ?: "没认出场景，或这类场景没有历史——" +
            "**认不出就不猜**：拿搬家的经验去指导看病更糟"
        "remember" -> {
            val drawn = c.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (a.isBlank() || drawn.isEmpty()) return "用法：remember <场景> <处境> <组件,组件>"
            remember(a, b, drawn); "已记「$a」一次，画了 ${drawn.size} 张"
        }
        "used" -> { markUsed(a, b); "已标「$a」的 $b 为用上了" }
        "dropped" -> { markDropped(a, b); "已标「$a」的 $b 为跳过" }
        "list" -> render()
        "render" -> render()
        "report" -> report()
        "demo" -> demo()
        "clear" -> "已清空 ${clear()} 条"
        else -> guide()
    }
}
