package com.ai.assistance.quro.core.rag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * zx 组件 RAG 域：把 477 个 zx_ 动态组件灌进 [AgentRag.engine]，
 * 让 AI 写 `zx_web_card` 时**先语义检索、秒级命中正确组件 + 字段契约 + 可抄示例 DSL**，
 * 一次写对，根治「组件无分类无 RAG → AI 盲写不规范 DSL → 渲染不完整」。
 *
 * ## 背景（用户最新诉求：「组件没有分类，没有 RAG，AI 找半天」「不只是能写出来，还要 AI 快速写他想写的组件」）
 *
 * 旧链路里 AI 调 `zx_web_card` 只能靠 `zx_catalog` 工具翻 477 个名字、凭印象拼字段，
 * 经常写出 `data.items` / `content.items` 包裹、对象式数组、`chart` 用 `lines`/`points`
 * 别名等不规范 DSL —— 字段/数据落空，画了外壳没画数据。
 *
 * 现在把每个组件注册成独立 RAG 域（`zx_components`），与全局 `tools` / `prompts` /
 * `cluster_skills` 并列：
 *  - 文档含组件名、分类、中英文标签、字段、数组、**示例 DSL 全文**、关键词、触发词、概念；
 *  - AI 用意图查询（如「对比两个方案」「画一个占比饼图」「做个预约表单」）即可召回最匹配组件；
 *  - 命中后由 [componentJsonOf] / [render] 直接回吐「字段契约 + 可抄示例 DSL」，照填即一次写对；
 *  - 底层 [com.ai.assistance.quro.ui.chat.ZxWidgetInline] 的 harvest/pick 加固仍作安全网兜底。
 *
 * ## 为什么不并入 prompts 域
 *
 * prompts 域装的是系统提示词块且带 `alwaysSticky` 语义；组件是**按意图按需检索**的资产，
 * 混进 prompts 域会破坏隔离、稀释检索。独立域 `zx_components` 是唯一既走主 RAG 通道
 * 又不污染其他域的方案（与 `cluster_skills` 同理）。
 */
object ZxComponentRagIndex {

    /** 域标识。供 `rag_search(domain="zx_components")` 使用。 */
    const val DOMAIN = "zx_components"

    /**
     * 组件域独立召回门槛。
     *
     * 与 [ClusterSkillRagIndex.SKILL_MIN_SCORE] 同理：组件文档短、域内文档多（477），
     * 照抄工具域默认 `MIN_SCORE(0.62)` 会系统性砍掉真查询。取 0.12：
     * 低于它意味着「查询里连一个组件实词都没有」，安全且召回充分。
     */
    const val COMP_MIN_SCORE = 0.12

    /** 单次检索返回条数上限（多给几条让模型挑最贴切的）。 */
    private const val TOP_K = 12

    /** 资产文件名（位于 app assets 根）。 */
    private const val CATALOG_ASSET = "zx_catalog.json"
    private const val CONTRACT_ASSET = "zx_contracts.json"

    /** 解析后的组件表（资产首次读取后缓存；引擎域清空也不丢，便于重建索引）。 */
    private var parsed = false
    private val comps = LinkedHashMap<String, ZxComp>()

    /**
     * 单组件可检索记录。
     *
     * @param sampleJson 示例 DSL 的紧凑 JSON 字符串（无示例时为 null），命中后直接回吐给模型照抄。
     */
    data class ZxComp(
        val name: String,
        val category: String,
        val title: String,
        val zh: List<String>,
        val description: String,
        val keywords: List<String>,
        val triggers: List<String>,
        val concepts: List<String>,
        val fn: String,
        val fields: List<String>,
        val arrays: List<String>,
        val hasSample: Boolean,
        val sampleJson: String?,
    )

    /**
     * 单组件 → 可检索文档。
     *
     * - [RagDoc.name]：组件名（如 `zx_compare`），词法通道权重最高，AI 直说组件名时精确命中；
     * - [RagDoc.title]：人类可读标签（如 `Compare`），让「画个对比图」类查询能撞上；
     * - [RagDoc.description]：组件用途 + 关键字段/数组，概念通道可反查；
     * - [RagDoc.keywords]：名称 token + fn + 字段 + 数组 + 中文语义 + 示例扁平化文本；
     * - [RagDoc.triggers]：意图短语（展示/需要/绘制 ……）；
     * - [RagDoc.capability]：分类标识；
     * - [RagDoc.payload]：组件名字符串，命中后交给 [componentJsonOf] 取全文。
     */
    fun ZxComp.toDoc(): RagDoc {
        return RagDoc(
            id = name,
            name = name,
            title = if (zh.isNotEmpty()) "$name（${zh.joinToString("/")}）" else name,
            description = description,
            capability = category,
            keywords = keywords,
            triggers = triggers,
            concepts = concepts,
            priority = 0.5,
            hintBoost = 0.2,
            payload = name,
        )
    }

    /** 读取并解析两个资产文件到内存（幂等；异常吞掉返回 false）。 */
    private fun parseAssets(context: Context): Boolean = runCatching {
        if (parsed) return@runCatching true
        val catalogText = context.assets.open(CATALOG_ASSET).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }
        val contractText = context.assets.open(CONTRACT_ASSET).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }
        val catalog = JSONObject(catalogText)
        val contracts = JSONObject(contractText)
        val compsObj = catalog.getJSONObject("components")

        comps.clear()
        val names = compsObj.keys().asSequence().toList()
        for (name in names) {
            val c = compsObj.getJSONObject(name)
            val contract = contracts.optJSONObject(name)
            val sample = contract?.optJSONObject("sample")
            val sampleJson = sample?.toString()
            val comp = ZxComp(
                name = name,
                category = c.optString("category", "general"),
                title = c.optString("title", name),
                zh = c.optJSONArray("zh")?.toStrList() ?: emptyList(),
                description = c.optString("description", name),
                keywords = c.optJSONArray("keywords")?.toStrList() ?: emptyList(),
                triggers = c.optJSONArray("triggers")?.toStrList() ?: emptyList(),
                concepts = c.optJSONArray("concepts")?.toStrList() ?: emptyList(),
                fn = c.optString("fn", ""),
                fields = c.optJSONArray("fields")?.toStrList() ?: emptyList(),
                arrays = c.optJSONArray("arrays")?.toStrList() ?: emptyList(),
                hasSample = c.optBoolean("has_sample", false),
                sampleJson = sampleJson,
            )
            comps[name] = comp
        }
        parsed = true
        true
    }.getOrDefault(false)

    /**
     * 把 zx 组件库灌进 [AgentRag.engine]。
     *
     * 幂等 + 自愈：
     *  - 资产只解析一次并缓存；
     *  - 引擎域已存在文档则跳过（不重复注册）；
     *  - 引擎域被清空（如 refresh 重建）时，用缓存重建，无需重新读资产；
     *  - 任何异常吞掉返回 false，不打断调用方（组件 RAG 是增强，不是硬依赖）。
     *
     * @return 是否成功灌入。
     */
    fun install(context: Context): Boolean = runCatching {
        if (!parseAssets(context)) return@runCatching false
        val engine = AgentRag.engine
        if (engine.countOf(DOMAIN) > 0) return@runCatching true
        val docs = comps.values.map { it.toDoc() }
        engine.registerAll(DOMAIN, docs)
        true
    }.getOrDefault(false)

    /**
     * 按意图检索 zx 组件。
     *
     * 索引由 [install] 懒加载（RagSearchTool 在查 `zx_components` 域时透传 context 触发）；
     * 索引尚未安装时返回空，调用方自行决定是否先 install。
     */
    fun search(query: String, limit: Int = TOP_K): List<RagHit> {
        if (query.isBlank()) return emptyList()
        if (AgentRag.engine.countOf(DOMAIN) <= 0) return emptyList()
        return runCatching {
            AgentRag.engine.search(query, DOMAIN, limit.coerceIn(1, TOP_K), COMP_MIN_SCORE)
        }.getOrDefault(emptyList())
    }

    /**
     * 从检索结果取出**完整组件契约 + 分类 + 示例 DSL**，交给调用方回吐给模型。
     *
     * 与 [ClusterSkillRagIndex.skillBodyOf] 同理：AI 命中后必须拿到可抄的字段契约与示例，
     * 而不是只看 description 摘要。返回的 JSONObject 含：
     * `name / category / title / zh / description / fields / arrays / fn / sample / keywords`。
     */
    fun componentJsonOf(hit: RagHit): JSONObject? {
        val name = (hit.doc.payload as? String) ?: return null
        val comp = comps[name] ?: return null
        return JSONObject().apply {
            put("name", comp.name)
            put("category", comp.category)
            put("title", comp.title)
            put("zh", JSONArray(comp.zh))
            put("description", comp.description)
            put("fields", JSONArray(comp.fields))
            put("arrays", JSONArray(comp.arrays))
            put("fn", comp.fn)
            put("keywords", JSONArray(comp.keywords))
            if (comp.sampleJson != null) {
                // 原样回吐示例 DSL，AI 直接照抄改数据即可
                put("sample", JSONObject(comp.sampleJson))
            }
        }
    }

    /**
     * 把检索结果渲染成给模型看的组件清单（含字段契约 + 可抄示例 DSL）。
     *
     * 无命中时返回空串，由调用方决定措辞。格式示例：
     * ```
     * ### zx_compare（对比 / Compare）
     * 字段：onPick
     * 数组：columns, rows, v
     * 示例 DSL：{"type":"zx_compare","title":"方案对比",...}
     * ```
     */
    fun render(hits: List<RagHit>): String = buildString {
        hits.forEach { h ->
            val comp = (h.doc.payload as? String)?.let { comps[it] } ?: return@forEach
            append("### ").append(comp.name)
            if (comp.zh.isNotEmpty()) append("（").append(comp.zh.joinToString("/")).append("）")
            append("\n分类：").append(comp.category)
            append("\n用途：").append(comp.description)
            if (comp.fields.isNotEmpty()) append("\n字段：").append(comp.fields.joinToString(", "))
            if (comp.arrays.isNotEmpty()) append("\n数组：").append(comp.arrays.joinToString(", "))
            if (comp.sampleJson != null) {
                append("\n示例 DSL（照抄改数据即可）：\n").append(comp.sampleJson)
            }
            append("\n\n")
        }
    }

    /** zx 组件域当前文档数（诊断用）。 */
    fun stats(): Int = AgentRag.engine.countOf(DOMAIN)

    /** 资产已解析的组件数（诊断用）。 */
    fun parsedCount(): Int = comps.size

    private fun JSONArray.toStrList(): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until length()) {
            out.add(optString(i, ""))
        }
        return out.filter { it.isNotBlank() }
    }
}
