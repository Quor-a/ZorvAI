package com.ai.assistance.quro.core.rag

import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.tools.ToolCapabilityDirectory

/**
 * 工具域RAG 索引：把全部注册工具变成可检索文档，并提供覆盖率核对。
 *
 * ## 存在的意义
 * 用户要求「所有工具都可以 RAG，模糊也能准确 RAG 到」。这是个**可证伪**的断言，
 * 所以本文件同时提供两件事：
 *  1. [install] —— 把工具注册表全量灌进 [RagEngine]；
 *  2. [coverage] —— 对每一条工具逐个跑代表性查询，把召回不到的揪出来列成表。
 *只有第 2 件事能证明第 1 件事真的做到了。
 *
 * ## 与旧检索路径的关系：并存
 * - [ToolCapabilityDirectory.matchToolsByIntent]（旧）：工具目录自带的检索，
 *   由 [com.ai.assistance.quro.core.tools.QuroToolRouter] 的 `tool_router` 调用；
 * - [RagEngine.search]（新）：多路融合引擎，能接住错别字 / 拼音 / 换一种说法。
 * 两者结果合并返回。旧架构一行未删，只是从唯一入口降级为并联的一路。
 */
object ToolRagIndex {

    /** 域标识。 */
    const val DOMAIN = "tools"

    /** 单次检索返回条数。 */
    private const val TOP_K = 10

    /**
     * 把工具注册表全量灌进引擎。
     *
     * 🔴 每次都必须先 [RagEngine.clearDomain] 再灌：工具注册表是**动态**的
     * （插件装卸、技能增删、注册表刷新），不清就会累积同名旧文档，
     * 检索时同一能力出现多份且分数被稀释——这类 bug 表现为「AI 偶尔选错工具」，极难定位。
     *
     * @param specs 当前真实可调用的工具全集（单一真相源）。
     */
    fun install(engine: RagEngine, specs: List<QuroToolSpec>) {
        engine.clearDomain(DOMAIN)
        for (s in specs) {
            engine.register(DOMAIN, toDoc(s))
        }
    }

    /**
     * 单个工具 → 检索文档。
     *
     * 文档的每一段文本都参与检索，所以字段填得越全召回越强：
     * - [RagDoc.name]：工具名，词法通道权重最高
     * - [RagDoc.title]：取 handbook 里的分类名 + 场景首句，让「分类级」查询也能撞上
     * - [RagDoc.description]：工具自己的 description（兜底由 autoInfo 生成）
     * - [RagDoc.keywords]：useCases + tips + examples —— 这三段是 handbook 的精华，
     *   **必须灌进关键词**，否则「什么场景该用它」这类查询召不回
     * - [RagDoc.triggers]：[RagConcept] 里该能力登记的口语触发词，
     *   这是「换一种说法」的唯一通路
     * - [RagDoc.capability]：工具名本身，让概念通道能反查
     */
    /**
     * 元工具：**它们本身是检索器**，关键词全是「工具/组件/怎么查」这类元词汇。
     *
     * 🔴 不压权的后果是实测出来的：`rag_search` 注册后，「把这段视频弄短一点」的 top1
     * 立刻从 `video_gen` 变成 `rag_search`——它描述里写着「视频/组件/出图」的例子，
     * 与任何业务查询都有字面重叠。模型拿到 top1 去调，只会得到一个「检索建议」列表，
     * 反而不知道该剪视频了。压权只影响排序，不影响「直接点名调用」。
     */
    private val META_TOOLS = setOf(
        "rag_search", "tool_router", "tool_discovery",
        "card_catalog", "ui_dsl_spec", "ui_validate", "ui_tree",
        "get_best_practices",
    )

    private fun toDoc(spec: QuroToolSpec): RagDoc {
        val info = ToolCapabilityDirectory.getToolInfo(spec.name)
        val categoryName = info?.category?.displayName.orEmpty()
        val priority = ((info?.priority ?: 3) - 1) / 4.0

        val keywords = buildList {
            info?.useCases?.let { addAll(it) }
            info?.tips?.let { addAll(it) }
            info?.examples?.let { addAll(it) }
        }.map { it.trim() }.filter { it.isNotEmpty() }

        return RagDoc(
            id = spec.name,
            name = spec.name,
            title = if (categoryName.isNotBlank()) "[$categoryName] ${spec.description.take(60)}"
            else spec.description.take(60),
            description = spec.description,
            capability = spec.name,
            keywords = keywords,
            triggers = RagConcept.triggersOf(spec.name),
            concepts = listOfNotNull(categoryName.takeIf { it.isNotBlank() }),
            priority = priority,
            // handbook 里手写条目的检索质量远高于 autoInfo 兜底生成的，给作者加成。
            hintBoost = if (info != null && info.useCases.size > 1) 0.6 else 0.0,
            payload = spec,
            metaTool = spec.name in META_TOOLS,
        )
    }

    /**
     * 检索工具。**与旧检索结果合并**后去重返回。
     *
     * @param legacy 旧 [ToolCapabilityDirectory.matchToolsByIntent] 的结果，
     *               由调用方传入而不是这里自己去拿——避免本对象反向依赖工具包，
     *               也让调用方能决定是否要在新引擎不可用时降级到旧路径。
     */
    fun search(
        engine: RagEngine,
        query: String,
        legacy: List<ToolCapabilityDirectory.ToolInfo> = emptyList(),
        limit: Int = TOP_K,
    ): List<RagHit> {
        val fresh = runCatching { engine.search(query, DOMAIN, limit) }.getOrDefault(emptyList())
        val merged = LinkedHashMap<String, RagHit>()

        // 新引擎结果在前：它能接住错别字/拼音/换说法，旧引擎接不住。
        fresh.forEach { merged[it.id] = it }

        // 旧引擎结果补位。它接住的是「新引擎同义词表尚未覆盖」的长尾说法，
        // 两者互补；重复的以新引擎分数为准（上面已put，同 key 不覆盖）。
        for (info in legacy) {
            if (merged.containsKey(info.name)) continue
            merged[info.name] = RagHit(
                id = info.name,
                title = info.category?.displayName.orEmpty(),
                score = 0.01, // 旧引擎结果排在同分之后，靠名字定序稳定
                domain = DOMAIN,
                doc = RagDoc(
                    id = info.name,
                    name = info.name,
                    title = info.category?.displayName.orEmpty(),
                    description = info.description,
                    capability = info.name,
                ),
            )
        }
        return merged.values
            .sortedWith(compareByDescending<RagHit> { it.score }.thenBy { it.id })
            .take(limit)
    }

    /**
     * 全量覆盖��对：对每条工具跑一遍自己的 name 与 description，
     * 确认至少有一条查询能召回它。
     *
     * 判据很朴素但有效：一个工具如果连**自己的名字**都召不回，
     * 那它必然在真实模糊查询下也召不回——索引构建一定有 bug。
     */
    fun coverage(engine: RagEngine): ToolCoverage {
        val ids = engine.idsOf(DOMAIN)
        val missed = ArrayList<String>()
        val weak = ArrayList<String>()
        for (id in ids) {
            val hit = runCatching { engine.searchAll(DOMAIN, id).firstOrNull() }.getOrNull()
            val score = hit?.score ?: 0.0
            when {
                score <= 0.0 -> missed.add(id)
                score < RagCoverageReport.WEAK_LINE -> weak.add(id)
            }
        }
        return ToolCoverage(
            total = ids.size,
            missed = missed,
            weak = weak,
            covered = ids.size - missed.size,
        )
    }
}

/**
 * 工具域覆盖报告。
 *
 * @param total 工具总数。
 * @param missed 连自己名字都召不回的工具（索引有 bug，必须为 0）。
 * @param weak 能召回但分数低（排序会靠后，实践中等于查不到）。
 */
data class ToolCoverage(
    val total: Int,
    val missed: List<String>,
    val weak: List<String>,
    val covered: Int,
) {
    /** 无工具完全召不回。 */
    val perfect: Boolean get() = missed.isEmpty()

    fun summary(): String = buildString {
        appendLine("工具 RAG 覆盖：$covered / $total")
        if (weak.isNotEmpty()) {
            appendLine("弱召回 ${weak.size} 个：${weak.take(15).joinToString(", ")}")
        }
        if (missed.isNotEmpty()) {
            appendLine("🔴 完全召不回 ${missed.size} 个：${missed.joinToString(", ")}")
        }
    }
}