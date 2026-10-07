package com.ai.assistance.quro.core.rag

import com.ai.assistance.quro.core.QuroToolSpec

/**
 * Agent 层 RAG 门面：**万物可RAG** 的统一入口。
 *
 * ## 定位
 * 上层有五个各自为政的检索入口（工具目录、系统提示词、GenUI 组件、动态 UI 组件、
 * 未来的技能/记忆条目），Agent 侧要记「这次该调哪个」。本门面把它们收成一次调用：
 *
 * ```
 * agent.retrieve("帮我把这段剪成 30 秒") →
 *   工具：codecanvas_onscreen_script / ffmpeg …
 *   提示词：media_generation / tool_discipline …
 *   界面：ui_widget / genui_draw …
 * ```
 *
 * 这不是「新检索算法」，而是**索引注册 + 跨域检索 + 结果归类**。
 * 检索算法在 [RagEngine]；各域内容由各自的 [install] 提供。
 *
 * ## 🔴 旧架构一行未删
 * - 工具：旧的 `tool_router` + [com.ai.assistance.quro.core.tools.ToolCapabilityDirectory] 仍可独立工作；
 * - 提示词：全量注入（[PromptRagIndex.Mode.FULL_ALWAYS]）仍可用；
 * - UI 组件：`list_components` 直查仍可用。
 * 本门面只是**在它们之上加一层可选的增强召回**，任何一域索引没装都不影响其它域。
 */
object AgentRag {

    /** 全局唯一引擎实例。
     *
     * 🔴 用单例而不是每次 new：索引构建要遍历全部工具（数百条），
     * 每次请求重建会让首字延迟明显变差。索引内容由 [refresh] 显式刷新，
     * 读路径完全无锁无副作用（[RagEngine] 内部只有 LinkedHashMap 的读）。
     */
    val engine: RagEngine = RagEngine()

    /**
     * 「结果可信」的分数线：top1 低于它就当作**弱相关**呈现（见 [explain]）。
     *
     * 🔴 这个数是量出来的，不是拍的：8 条真实口语探针里，
     * 强命中的 top1 分布在 4.0~7.5（`上网查一下最近的资料` 7.51、`看这个网址` 6.31、
     * `帮我搜一下这个主题` 5.63、`生成一张图` 5.37），而**跨过 WEAK_LINE(1.0)
     * 却明显不够**的都在 1.0~1.3。取 2.0 落在两组之间，且远离边界。
     *
     * 它**不参与召回**（不砍结果、不影响排序），只决定 explain 的措辞。
     */
    private const val CONFIDENT_LINE = 2.0

    /**
     * 重建全部域索引。
     *
     * @param toolSpecs 当前真实工具注册表；传空则**跳过工具域**（保留其它域已有索引）。
     */
    fun refresh(toolSpecs: List<QuroToolSpec> = emptyList()) {
        if (toolSpecs.isNotEmpty()) ToolRagIndex.install(engine, toolSpecs)
        PromptRagIndex.install(engine)
    }

    /** 各域当前文档数（诊断用）。 */
    fun stats(): Map<String, Int> = mapOf(
        ToolRagIndex.DOMAIN to engine.countOf(ToolRagIndex.DOMAIN),
        PromptRagIndex.DOMAIN to engine.countOf(PromptRagIndex.DOMAIN),
    )

    /** 总文档数。 */
    fun total(): Int = engine.size()

    // ───────────────────────── 分域检索 ─────────────────────────

    /** 找工具（模糊可命中）。 */
    fun tools(query: String, limit: Int = 8): List<RagHit> =
        runCatching { engine.search(query, ToolRagIndex.DOMAIN, limit) }.getOrDefault(emptyList())

    /** 找系统提示词块。 */
    fun prompts(query: String, limit: Int = 3): List<RagHit> =
        runCatching { engine.search(query, PromptRagIndex.DOMAIN, limit) }.getOrDefault(emptyList())


    /**
     * 跨域一次检索全库。
     *
     * 这是「万物可RAG」最直接的形态：不指定域，一次拿到所有相关的东西。
     * 用于「我该用什么」这类元问题——Agent 自己拿不准该走哪条路时先问一句。
     *
     * ## 🔴 为什么不能直接 `engine.search(query, null, limit)`
     * 域之间**规模差了两个数量级**：工具域 269 篇、动态UI 54、GenUI 19、提示词 8。
     * 全库按分数排序取前 N 时，结果几乎必然被工具域吃满——
     * 实测「画一个销售看板」跨域取前 14 条，**界面交付类一条都没有**，
     * `genui_agent_open` 排在第 20 名（0.766），而 `linux_install` 这类
     * 完全无关的工具有 19 条挤在它前面（0.7725）。
     *
     * 那样的话「万物可RAG」名义上通了、实际上等于只有工具能用，
     * 而用户报的「AI 好像不知道我有这个组件」正是这么来的。
     *
     * 所以这里改成**每域保底 + 全局补齐**：
     * 先每个域各取 [perDomain] 条（域内仍按分数排序），剩下的名额再按全局分数补。
     * 这样小域一定露脸，而域内排序的正确性完全不受影响。
     */
    fun everything(query: String, limit: Int = 12): List<RagHit> {
        val perDomain = (limit / 3).coerceIn(2, 6)
        val byDomain = runCatching {
            engine.domains().associate { dom ->
                dom to engine.search(query, dom, perDomain)
            }
        }.getOrDefault(emptyMap())
        // 每域保底：按域内分数取，域顺序稳定（LinkedHashMap 的插入序 = 注册序）
        val picked = ArrayList<RagHit>(limit)
        val seen = HashSet<String>()
        for ((_, hits) in byDomain) {
            for (h in hits) {
                if (picked.size >= limit) break
                if (!seen.add(h.domain + "/" + h.id)) continue
                picked.add(h)
            }
            if (picked.size >= limit) break
        }
        if (picked.size < limit) {
            // 全局补齐：把各域合起来重排，取还没选中的高分项。
            val pool = byDomain.values.flatten().sortedByDescending { it.score }
            for (h in pool) {
                if (picked.size >= limit) break
                if (seen.add(h.domain + "/" + h.id)) picked.add(h)
            }
            picked.sortByDescending { it.score }
        }
        return picked
    }

    /**
     * 渲染成人/模型可读的一行行建议。
     *
     * @param query 用户原始需求（用**原话**，不要预先概括——概括会丢掉关键限定词）。
     *
     * 🔴 **弱命中必须显式标注，不能装作是答案。**
     *
     * 本仓既有决策是「零命中绝不返回空」（空列表会被渲染成「未找到」，
     * 那正是用户报的「AI 查不到」）。但**兜底不是把一堆低分噪音原样摊给模型**：
     * 实测「zzzzqqq完全不相关的东西xyzzy」会返回 `experience_query` /
     * `memory_window_plan` 这类擦边工具，模型会认真去用错误的工具，比「没查到」更糟。
     *
     * ## 🔴 为什么不能用「分数低于 X 就不列」
     *
     * 试过，**做不到**。实测把 [RagEngine.fieldScore] 的停用词修正之后：
     *
     * | 查询 | 性质 | top1 分 |
     * |---|---|---|
     * | `zzzzqqq完全不相关的东西xyzzy` | 乱串 | 1.1116 |
     * | `把这段视频弄短一点` | **真实模糊查询** | **1.0375** |
     *
     * **乱串分数比真查询还高。** 中文 bigram 切分后八个 token 里只有「视频」是成词，
     * 真实模糊查询天然拿不到高覆盖率；而乱串的碎片（`关的`/`的全`）到处能撞上。
     * 所以任何「低于 X 判弱」的阈值都必然同时砍死真召回——
     * 这与历史上砍死四个视频工具的那次是同一个坑，只是换了个方向。
     *
     * 因此改走**呈现**：低于 [RagCoverageReport.WEAK_LINE] 是「没召回到」，
     * 只给改写建议；召回到了但**优势不够明显**（见 [CONFIDENT_LINE]）时照列，
     * 但**首行必须写明「弱相关」**——让模型知道自己拿到的是猜测而不是答案。
     *
     * ## ⚠️ 这条标注会误伤真查询，这是**有意的取舍**，不要当 bug 修掉
     *
     * 实测「把这段视频弄短一点」的 top1 只有 1.0375，同样会被标成「弱相关」——
     * 尽管它的 top3 全是视频工具、答得完全正确。
     *
     * 之所以仍然这么判：分数**无法**区分「乱串」与「真实但模糊」的查询
     * （1.1116 vs 1.0375，乱串还更高）。两种错误里，
     * **把噪音当答案**（模型自信地调错工具、用户看到完全跑偏）远比
     * **把好答案标成不确定**（模型多确认一次）有害。
     * 宁可误标，不可误导。
     */
    fun explain(query: String, limit: Int = 12): String {
        val hits = everything(query, limit)
        if (hits.isEmpty() || hits.first().score < RagCoverageReport.WEAK_LINE) {
            return "「$query」没有检索到足够相关的工具 / 提示词 / 组件。\n" +
                "试试更具体的说法（说出你想达成的效果，而不是你想用的工具名）。"
        }
        // 弱相关：仍按「绝不返回空」给结果，但必须让模型知道这是猜测。
        val weak = hits.first().score < CONFIDENT_LINE
        return buildString {
            if (weak) {
                appendLine("## 检索建议（**弱相关**：下面的候选与「$query」匹配很弱，可能都不是你要的）")
            } else {
                appendLine("## 检索建议（按相关度排序，共 ${hits.size} 条）")
            }
            hits.forEachIndexed { i, h ->
                appendLine("${i + 1}. ${h.render()}")
            }
            appendLine()
            if (weak) {
                append("如果下面这些都不是你要的，说明「$query」里的信息不足以定位能力——")
                append("请换个更具体的说法（说出你想达成的效果，而不是你想用的工具名），再检索一次。\n")
            }
            append("用到哪条就调哪条；拿不准就先 `tool_router.get_schema(name=...)` 看完整参数。")
        }
    }

    // ───────────────────────── 覆盖核对 ─────────────────────────

    /** 工具域覆盖报告。 */
    fun toolCoverage(): ToolCoverage = ToolRagIndex.coverage(engine)


    /**
     * 全域覆盖摘要。
     *
     * 用途只有一个：**验收「所有东西都可 RAG」这个断言**。
     * 任何一域出现 missed/missing 都说明索引漏登记，对应能力对模型而言等于不存在。
     */
    fun coverageSummary(): String = buildString {
        appendLine("═══ RAG 覆盖核对 ═══")
        appendLine("文档总数：${total()}")
        append(stats().entries.joinToString("\n") { "  ${it.key}: ${it.value}" })
        appendLine()
        append(toolCoverage().summary())
        appendLine()
    }
}
