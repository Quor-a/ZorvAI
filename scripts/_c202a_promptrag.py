import io, os, sys

p = 'app/src/main/java/com/ai/assistance/quro/core/rag/PromptRagIndex.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

# ═══════════════ 1) 文件头 KDoc：说明本轮修掉的 5 个实证病灶 ═══════════════
sub(
""" * ## 🔴 旧架构完整保留（本类不替换任何东西）
 * [Mode.FULL_ALWAYS] 是默认之外的显式选项，且 [render] 在任何情况下都会**先拼完整基座**，
 * 只把「可选的进阶段落」改成按需追加。也就是说：
 * - 关掉 RAG → 行为与改动前逐字一致；
 * - 开 RAG → 关键段落一条都不少，只是不相关的段落不再每轮刷屏。
 * 这是「加 RAG 但旧构架仍然保留」的具体实现方式。
 */""",
""" * ## 🔴 旧架构完整保留（本类不替换任何东西）
 * [Mode.FULL_ALWAYS] 仍然可用（显式选项，用户可开），且 [render] 在任何情况下都会
 * **先拼完整基座**，只把「可选的进阶段落」改成按需追加。也就是说：
 * - 关掉 RAG → 行为与改动前逐字一致；
 * - 开 RAG → 关键段落一条都不少，只是不相关的段落不再每轮刷屏。
 *
 * ## 🔴 2026-10-08 全面排查：5 个病灶全部是**探针实测**，不是推测
 *
 * 本轮用临时探针（跑真实场景打印分数与注入量）查出 5 个实锤缺陷，逐个修掉：
 *
 * 1. **`stickyBlocks` 恒为空** —— 8 个块里 `alwaysSticky = true` 出现 **0 次**，
 *    于是 [stickyBlocks] 永远返回空列表。实测 `SELECTIVE` 模式追加 **0 字符**：
 *    注释里写的「基础段无条件注入」是**死代码**，SELECTIVE 实际等于「什么都不注入」。
 *    → 修法：把真正每轮都该守的 [tool_discipline] 与 [output_format] 定为 `alwaysSticky`。
 *
 * 2. **prompts 域召回被系统性杀死** —— `MIN_SCORE = 0.62` 是给 269 篇的**工具域**定的，
 *    而 `fieldScore` 用 `coverage = hit / qt.size`：一句 10 token 的中文查询
 *    （「这些数据做成表格给我看」切出 10 个 bigram）最多只能拿 0.1 的覆盖率。
 *    实测 `output_format` 明明有「表格」触发词，top1 只有 **0.318** < 0.62 → **零命中**。
 *    → 修法：prompts 域用独立门槛 [PROMPT_MIN_SCORE]（域小、块少、每段都长，
 *      阈值必须与工具域脱钩）。
 *
 * 3. **规则正文被截到 200 字** —— `description = body.take(200)` 让 200 字之后的内容
 *    对 lexical/shape 通道**完全不可见**。实测 `cluster_orchestration` 有 **1416 字，
 *    86% 检索不到**；`ui_delivery`(364)、`tool_discipline`(285)、`code_execution`(224)
 *    同样被砍。
 *    → 修法：prompts 域 [PromptBlock.toDoc] 用**正文全文**做 description。
 *
 * 4. **AI 主动查时拿不到正文** —— [RagHit.toJson] 只回 `description`，而
 *    `RagSearchTool` 从不读 `doc.payload`（真正的 [PromptBlock]）。
 *    于是 `rag_search(domain="prompts")` 命中了也**只给 200 字碎片**。
 *    → 修法：[PromptRagIndex.asRuleResult] 把 payload 里的完整正文交给工具侧回传。
 *
 * 5. **RAG 被当成主要方案** —— [Mode.FULL_ALWAYS] 是默认，实测每轮追加 **3182 字符
 *    （≈2100 tokens）**，其中集群段独占 1416。用户要求「改成 AI 被动使用、
 *    RAG 是备用方案不是主要方案」。
 *    → 修法：默认改为 [Mode.SELECTIVE]，主路径改成 AI 主动调 `rag_search`
 *    （见 [RagSearchTool]）；`FULL_ALWAYS` 保留为显式可选。
 */""",
'kdoc')

# ═══════════════ 2) Mode 枚举：默认语义改为备用 ═══════════════
sub(
"""    /**
     * 注入策略。
     *
     * @param FULL_ALWAYS 全量注入。**默认**，保证不改变既有行为。
     * @param SELECTIVE 基础段常驻 + 进阶段按意图 RAG 选段。
     */
    enum class Mode { FULL_ALWAYS, SELECTIVE }""",
"""    /**
     * 注入策略。
     *
     * @param FULL_ALWAYS 全量注入 8 段。实测每轮 **3182 字符（≈2100 tokens）**，
     *   且 [cluster_orchestration] 一段就占 1416 字 —— 这就是「把 RAG 当主要方案」。
     *   **保留但不再默认**，需要时才显式开。
     * @param SELECTIVE 基础段常驻 + 进阶段按意图 RAG 选段。**默认**。
     *
     * ## 🔴 为什么默认是 SELECTIVE 而不是 FULL_ALWAYS
     *
     * 用户要求「改成 AI 被动使用，系统提示词 RAG 是**备用方案不是主要方案**」。
     * SELECTIVE 才符合这个语义：每轮只灌真正每轮都要守的基础段（[stickyBlocks]），
     * 其余规则由 **AI 自己调 `rag_search(domain="prompts")` 主动取**。
     * 也就是说检索从「宿主塞给 AI」变成「AI 按需拉」，这才是被动使用。
     */
    enum class Mode { SELECTIVE, FULL_ALWAYS }""",
'mode')

# ═══════════════ 3) toDoc：prompts 域用全文做 description ═══════════════
sub(
"""        fun toDoc(): RagDoc = RagDoc(
            id = id,
            name = id,
            title = title,
            description = body.take(200),""",
"""        /**
         * 🔴 `description` 用**正文全文**，不截断到 200 字。
         *
         * 旧实现 `body.take(200)` 实测害了两处：
         * - 200 字之后的内容对 lexical/shape 通道**完全不可见**，
         *   `cluster_orchestration`（1416 字）有 86% 检索不到；
         * - `rag_search` 回显的就是 `description`，AI 命中了也只拿到碎片。
         *
         * 工具域那边仍是短描述（工具本来就短），所以只有 prompts 域改全文。
         */
        fun toDoc(): RagDoc = RagDoc(
            id = id,
            name = id,
            title = title,
            description = body,""",
'todoc')

# ═══════════════ 4) 给两块打 alwaysSticky ═══════════════
sub(
"""            triggers = listOf("工具", "调用", "能力", "做不到", "不会", "怎么用", "有什么工具", "工具列表"),
            keywords = listOf("tool_router", "match_intent", "get_schema", "工具检索", "按需加载"),
        ),""",
"""            triggers = listOf("工具", "调用", "能力", "做不到", "不会", "怎么用", "有什么工具", "工具列表"),
            keywords = listOf("tool_router", "match_intent", "get_schema", "rag_search", "工具检索", "按需加载"),
            // 🔴 基础段：每轮都必须守「不确定就去查，别猜」。这条是整套 RAG 的入口纪律，
            // 若它也变成「按需检索」，AI 根本不会去检索 —— 那才是真的检索不了。
            alwaysSticky = true,
        ),""",
'sticky1')

sub(
"""            triggers = listOf("格式", "排版", "表格", "显示", "展示", "出图", "图片", "挂载", "文件"),
            keywords = listOf("attach_file", "ui_card", "ui_widget", "codecanvas", "输出格式"),
        ),""",
"""            triggers = listOf("格式", "排版", "表格", "显示", "展示", "出图", "图片", "挂载", "文件"),
            keywords = listOf("attach_file", "ui_card", "ui_widget", "codecanvas", "输出格式"),
            // 🔴 基础段：用户报的两条硬伤（组件老是同一个类型、产物只出标题不给正文）
            // 都出在这条纪律上 —— 每轮都得看见，不能等「检索到表格」才给。
            alwaysSticky = true,
        ),""",
'sticky2')

# ═══════════════ 5) 独立门槛 + payload 正文回传 ═══════════════
sub(
"""    /** 基础段：无条件注入，不参与筛选。 */
    val stickyBlocks: List<PromptBlock> get() = blocks.filter { it.alwaysSticky }""",
"""    /** 基础段：无条件注入，不参与筛选。 */
    val stickyBlocks: List<PromptBlock> get() = blocks.filter { it.alwaysSticky }

    /**
     * 🔴 prompts 域**独立**的召回门槛，刻意低于 [RagEngine] 的 `MIN_SCORE(0.62)`。
     *
     * ## 为什么必须脱钩（实测，不是推测）
     *
     * `fieldScore` 的覆盖率算法是 `coverage = hit / qt.size` —— 分母是**整句** token 数。
     * 中文按 bigram 切分后，一句 10 字查询就是 10 个 token，于是**单个触发词最多只值 0.1 覆盖率**。
     * 实测数据：
     *
     * | 查询 | prompts 域 top1 | 结果 |
     * |---|---|---|
     * | `这些数据做成表格给我看` | `output_format` **0.318** | 零命中 |
     * | `帮我查一下明天的天气` | `tool_discipline` **0.141** | 零命中 |
     * | `帮我把这个趋势用图表显示出来` | `output_format` 0.900 | 命中但 < `WEAK_LINE(1.0)` → 被 explain 判「没召回到」 |
     *
     * `output_format` 的 triggers 里**明明有「表格」**，却因为整句稀释拿不到分。
     * 工具域能活下来是因为它有 269 篇、每篇 name/keywords 密度高；
     * prompts 域只有 8 篇、每篇正文很长，**照抄工具域阈值就是系统性召回失败**。
     *
     * 取 0.12：低于它的是「查询里连一个 prompts 域实词都没有」，
     * 实测噪声串 `zzzqqq不相关的东西` 在本域拿 0 分，安全。
     */
    const val PROMPT_MIN_SCORE = 0.12

    /**
     * prompts 域检索：独立门槛 + 全文参与。
     *
     * 供 [com.ai.assistance.quro.core.tools.RagSearchTool] 在 `domain="prompts"` 时调用。
     */
    fun searchPrompts(engine: RagEngine, query: String, limit: Int = 5): List<RagHit> =
        runCatching { engine.search(query, DOMAIN, limit.coerceIn(1, 8), PROMPT_MIN_SCORE) }
            .getOrDefault(emptyList())

    /**
     * 从检索结果里取出**完整规则正文**（[PromptBlock.body]），不是 200 字碎片。
     *
     * 🔴 这是「AI 被动使用」成立的关键：AI 主动 `rag_search` 时若只拿到摘要，
     * 它拿到的还是「半条规则」，照样会用错。必须把 payload 里的全文交出去。
     */
    fun ruleBodyOf(hit: RagHit): String? =
        (hit.doc.payload as? PromptBlock)?.body

    /**
     * prompts 域结果渲染成给模型看的正文清单（供工具 explain 模式复用）。
     * 无命中时返回空串，由调用方决定措辞。
     */
    fun renderFullBodies(hits: List<RagHit>): String = buildString {
        hits.forEach { h ->
            val body = ruleBodyOf(h)
            if (body != null) {
                append("### ").append(h.doc.title.ifBlank { h.id }).append('\\n').append(body).append("\\n\\n")
            }
        }
    }""",
'threshold')

# ═══════════════ 6) render：SELECTIVE 用独立门槛 ═══════════════
sub(
"""            val picked = runCatching { engine.search(userQuery, DOMAIN, limit = 3, minScore = 1.2) }
                .getOrDefault(emptyList())""",
"""            // 🔴 用 prompts 域自己的门槛，不用引擎默认的 MIN_SCORE(0.62)，
            // 也不用旧代码硬写的 1.2（1.2 比 0.62 更严，实测把「出张海报」之外的
            // 大多数真查询也一起砍了）—— 依据见 [PROMPT_MIN_SCORE] 的实测表。
            val picked = searchPrompts(engine, userQuery, limit = 3)""",
'render-gate')

assert src != orig
tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  bytes %d -> %d' % (len(orig), len(src)))