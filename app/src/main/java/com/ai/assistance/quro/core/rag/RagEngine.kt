package com.ai.assistance.quro.core.rag

/**
 * 统一 RAG 检索引擎：**万物可RAG** 的单一入口。
 *
 * ## 定位
 * 本引擎不绑定任何具体领域。工具、系统提示词块、GenUI 组件、动态 UI 组件、
 * 技能、记忆条目……只要实现 [RagDoc]，就能挂上来被检索。这就是「万物可 RAG」的落点。
 *
 * ## 为什么单独建一个引擎（而不是继续扩 ToolCapabilityDirectory）
 * 旧检索层有四个硬天花板，每一条都直接导致「再模糊也查不到」：
 *  1. **纯字面**：只做精确 token 重叠，错别字/拼音缩写/换一种说法全部零命中；
 *  2. **零命中兜底是分类级瞎猜**：返回按 priority 排的前 12 个，跟查询毫无关系，
 *     模型看到的是「一批不相干的工具」，等于没查；
 *  3. **同义表有限且散落**：别名只覆盖百来条固定搭配，用户换个说法就掉出去；
 *  4. **一领域一实现**：工具能检索，提示词不能，UI 组件不能，改一个领域要重写一遍。
 *
 * ## 现在的做法：多路信号融合
 * 一次查询跑四路，各自独立给分，加权求和：
 *  1. **词法通道** — 精确 token 重叠（沿用 [RagText] 分词），保底最准的直���匹配；
 *  2. **字形通道** — 字符 3-gram Jaccard，容错字形近似（「微心」vs「微信」）；
 *  3. **拼音通道** — 拼音首字母包含（「wx」vs「微信」）；
 *  4. **概念通道** — [RagConcept] 把口语映射成能力名 / 概念词，让语义鸿沟被桥接。
 *
 * 关键设计：
 *  - **零命中不再是瞎猜**：四路全灭时走 [expandQuery] 的二次检索，用扩展词再找一轮；
 *    仍无解才返回空——但空列表是诚实的空，不是「假装有结果」。
 *  - **[legacyScore] 旁路**：旧检索层的得分作为一路信号并入。旧架构完整保留，
 *    只是从「唯一入口」降级为「四路之一」，这样新引擎永远不会比旧引擎差。
 */
class RagEngine {

    companion object {
        /**
         * 四路权重。
         *
         * 权重的取舍依据（不是拍脑袋）：
         *  - 词法最高：它是唯一能做到「精确无歧义」的通道，用户直接说出工具名时只有它能给满分；
         *  - 概念次之：它承载语义鸿沟，是「换一种说法」的唯一解，但对能力名过拟合时可能误伤，
         *    所以不给它压过词法的权��；
         *  - 字形再次：容错价值高，但短词下噪声大（「日」「月」距离 1），必须压住；
         *  - 拼音最低：首字母表只覆盖常用字，生僻字查不到，且包含匹配本身就有歧义
         *    （"wx" 既可能微信也可能���文），只能当辅助信号。
         */
        private const val W_LEXICAL = 3.0
        private const val W_CONCEPT = 2.2
        private const val W_SHAPE = 1.4
        private const val W_PINYIN = 0.9

        /**
         * 低于此分视为未命中。
         *
         * 🔴 必须是 0.62 而不是「看起来更严的」1.0：四路加权后的绝对分不可预测
         * （取决于命中字段数与信息量），定 1.0 会把大量真命中也砍掉。
         * 0.62 是实测出来的地板——纯靠 `priority` 先验（最高 0.08）与字形擦边
         * 撑不到这个分，只有「至少有一路通道真的命中了实义词」才够。
         */
        private const val MIN_SCORE = 0.62

        /**
         * 零命中时的二次扩展词上限。
         */
        private const val MAX_EXPANSION_TERMS = 8

        /**
         * 整串精确命中工具名时的额外加分。
         *
         * ## 为什么必须有这个常量
         * 意图扩展词（0.45 权重）会把「网络检索」这类词塞进 `get_network_info` 的查询里，
         * 而 `web_search` 的 handbook 里全是「联网」——扩展词足以让它反超**原文精确命中**。
         * 实测：`get_network_info` 搜自己名字，web_search 5.855 / 自己 4.949。
         * 用户直说了工具名时，精确命中必须是**压倒性**的，这一票不能被扩展词稀释。
         */
        private const val EXACT_NAME_BONUS = 6.0
    }

    /** 已注册的文档，按域分组。 */
    private val domains = LinkedHashMap<String, MutableList<RagDoc>>()

    /** 域 → 文档 id 集合，供 [unregister] 清理。 */
    private val docIndex = HashMap<String, String>()

    // ───────────────────────── 注册 ─────────────────────────

    /**
     * 注册（或覆盖）一个检索文档。
     *
     * @param domain 域标识，如 `"tools"` / `"prompts"` / `"genui"` / `"dynamic_ui"`。
     *               同 id 重复注册会**替换**而非追加，避免刷新注册表时文档翻倍。
     */
    fun register(domain: String, doc: RagDoc) {
        val list = domains.getOrPut(domain) { mutableListOf() }
        docIndex["$domain/${doc.id}"]?.let { oldId ->
            val idx = list.indexOfFirst { it.id == oldId }
            if (idx >= 0) list[idx] = doc else list.add(doc)
        } ?: run {
            list.add(doc)
            docIndex["$domain/${doc.id}"] = doc.id
        }
    }

    /** 批量注册。 */
    fun registerAll(domain: String, docs: List<RagDoc>) {
        docs.forEach { register(domain, it) }
    }

    /**
     * 清空某个域。
     *
     * 🔴 为什么不叫 `clear`：本引擎会被多处复用（工具刷新、UI 组件热重载），
     * 语义必须是「这个域整体换新」，而不是「误清全库」。域用错是极难查的 bug。
     */
    fun clearDomain(domain: String) {
        domains.remove(domain)?.forEach { docIndex.remove("$domain/${it.id}") }
    }

    /** 已注册文档总数（诊断用）。 */
    fun size(): Int = domains.values.sumOf { it.size }

    /** 某域的文档数，0 表示未注册。 */
    fun countOf(domain: String): Int = domains[domain]?.size ?: 0

    /** 某域的全部文档 id（测试与诊断用）。 */
    fun idsOf(domain: String): List<String> = domains[domain]?.map { it.id }.orEmpty()

    /**
     * 已注册的域标识列表（插入序）。
     *
     * 供 [com.ai.assistance.quro.core.rag.AgentRag.everything] 做**每域保底**检索：
     * 跨域时不按全局分数取前 N（会被大域吃满），而是逐域各取一批再合并。
     */
    fun domains(): List<String> = domains.keys.toList()

    // ───────────────────────── 检索 ─────────────────────────

    /**
     * 检索。
     *
     * @param domain 只在该域内检索；传 `null` 表示**跨域全库检索**（这就是「万物可 RAG」）。
     * @param limit 最多返回条数。
     * @param minScore 覆盖默认 [MIN_SCORE]。
     */
    fun search(
        query: String,
        domain: String? = null,
        limit: Int = 8,
        minScore: Double = MIN_SCORE,
    ): List<RagHit> {
        val raw = searchRaw(query, domain, limit, minScore)
        if (raw.isNotEmpty()) return raw

        // ── 零命中二次检索：换扩展词再来一轮，而不是返回一批不相干的东西 ──
        val expanded = expandQuery(query)
        if (expanded.isEmpty()) return emptyList()
        val pool = domains[domain] ?: return emptyList()
        val terms = (listOf(query) + expanded).take(1 + MAX_EXPANSION_TERMS)
        val best = HashMap<String, Pair<Double, RagDoc>>()
        for (t in terms) {
            // 🔴 扩展轮**同样要过闸门**。只守入口不守扩展轮时，
            // 「zzzzqqq完全不相关的东西」会被切出「不相关」「东西」这类 token，
            // 逐词重打后每个都能擦到 1.0 分——闸门形同虚设（实测返回 10 个不相干工具）。
            if (t != RagFuzzy.norm(query) && !hasUsefulSignal(t, domain)) continue
            for (doc in pool) {
                val s = score(doc, t, terms)
                if (s < minScore) continue
                val prev = best[doc.id]
                if (prev == null || s > prev.first) best[doc.id] = s to doc
            }
        }
        return best.values
            .sortedWith(compareByDescending<Pair<Double, RagDoc>> { it.first }.thenBy { it.second.id })
            .take(limit)
            .map { (s, d) -> RagHit(d.id, d.title, s, domain ?: "", d) }
    }

    /**
     * 检索但不丢弃任何结果（调试 / 覆盖率排查用）。
     *
     * 用途很具体：验收「所有工具是否都能被 RAG 到」时，需要看到**零分的工具**，
     * 否则只能靠猜哪个漏了。[RagCoverageReport] 靠这个方法逐条核对。
     */
    fun searchAll(domain: String, query: String): List<RagHit> {
        val pool = domains[domain] ?: return emptyList()
        val q = RagFuzzy.norm(query)
        if (!hasUsefulSignal(q, domain)) return emptyList()
        return pool
            .map { RagHit(it.id, it.title, score(it, query, listOf(query)), domain, it) }
            .sortedWith(compareByDescending<RagHit> { it.score }.thenBy { it.id })
    }

    private fun searchRaw(
        query: String,
        domain: String?,
        limit: Int,
        minScore: Double,
    ): List<RagHit> {
        val q = RagFuzzy.norm(query)
        if (q.isBlank()) return emptyList()
        // 🔴 查询自身信息量闸门：全是停用词/单字/纯噪声的查询直接判空。
        // 少了这道闸，「zzzzqqq 完全不相关的东西」能拿到 1.15 分——
        // 分数来自 priority 先验与字形擦边，而不是真的匹配到了什么。
        // 用户体验上「乱搜也有结果」比「明确搜不到」更糟：模型会认真去用错误的工具。
        if (!hasUsefulSignal(q, domain)) return emptyList()
        val terms = buildList {
            add(q)
            addAll(RagConcept.expandIntent(q))
        }
        val pools: List<Pair<String, List<RagDoc>>> = if (domain != null) {
            listOf(domain to (domains[domain] ?: emptyList()))
        } else {
            domains.map { it.key to it.value }
        }
        val hits = ArrayList<RagHit>()
        for ((dom, pool) in pools) {
            for (doc in pool) {
                val s = score(doc, q, terms)
                if (s >= minScore) hits.add(RagHit(doc.id, doc.title, s, dom, doc))
            }
        }
        return hits
            .sortedWith(compareByDescending<RagHit> { it.score }.thenBy { it.id })
            .take(limit)
    }

    /**
     * 查询里是否含有值得检索的实义信号。
     *
     * 三条判据，命中任一即放行：
     *  1. 整个查询精确等于某个已注册文档的名字；
     *  2. 分词后存在至少一个**中文实词**（非停用词、非通用修饰语、信息量达标）；
     *  3. 整个查询是**短拉丁串**（拼音缩写形态），且不是键盘噪声。
     *
     * 🔴 第 3 条是必需的，不是补丁：本引擎的拼音通道输入形态**就是**纯拉丁缩写
     * （「dygelj」= 读一下这个链接）。只按中文实词判会把这一整个形态毙掉。
     * 🔴 第 1 条同样必需：`get_network_info` 分词后全是拉丁词元，
     * 若只看中文实词信息量，用户直说的工具名也会被判成无效查询。
     */
    private fun hasUsefulSignal(q: String, domain: String?): Boolean {
        val flatQuery = q.replace("_", "").replace("-", "").replace(" ", "")
        val pools: List<List<RagDoc>> = if (domain == null) {
            domains.values.toList()
        } else {
            listOf(domains[domain] ?: emptyList())
        }
        for (pool in pools) {
            for (d in pool) {
                val flatName = d.name.replace("_", "").replace("-", "").lowercase()
                if (flatName.isNotEmpty() && flatName == flatQuery) return true
            }
        }
        val compact = flatQuery
        // 拼音缩写：整串就是短拉丁。长度上限 12 是为了排除长句。
        //
        // 🔴 这里**绝不能**调 isLatinNoise：拼音首字母天然不含元音
        // （「读一下这个链接」→ dyxzglj，连续 6 个辅音），会被「键盘乱敲」判据当成噪声，
        // 于是整条拼音查询在入口就被毙掉——实测拼音通道本身算得对（pinyinScore=1.0），
        // 却在 search 里零结果。
        // 拼音缩写与乱敲在字形上无法区分，那就**不区分**：让拼音通道去比目标首字母串，
        // 对不上自然得 0 分、乱敲自然被 MIN_SCORE 滤掉，比在这里猜要可靠。
        if (compact.length in 2..12 && compact.all { it in 'a'..'z' || it in '0'..'9' }) {
            return true
        }

        val toks = RagText.tokenize(q)
        if (toks.isEmpty()) return false
        return toks.any { t ->
            t.length >= 2 && !RagText.isStopWord(t) &&
                !RagText.isLatinNoise(t) && RagText.informativeness(t) >= 0.6
        }
    }

    /**
     * 单文档对一次查询的总分。
     *
     * @param terms 查询词全集（原文 + 意图扩展）。扩展词权重低于原文，
     *              避免一句「画海报」被扩��出来的「出图」压过用户真正的限定词。
     */
    private fun score(doc: RagDoc, query: String, terms: List<String>): Double {
        val primary = query
        var total = 0.0
        var primaryWeight = 0.0
        var secondaryWeight = 0.0
        for (t in terms) {
            val w = if (t == primary) 1.0 else 0.45
            if (t == primary) primaryWeight += w else secondaryWeight += w
            total += w * rawScore(doc, t)
        }
        val denom = primaryWeight + secondaryWeight
        val base = if (denom == 0.0) 0.0 else total / denom
        // 文档自身的 hint 参与一次轻量加权：作者声明的「这工具干什么用」可信度高于自动推断。
        return base * (1.0 + doc.hintBoost * 0.15)
    }

    private fun rawScore(doc: RagDoc, query: String): Double {
        val q = RagConcept.normalize(query)
        if (q.isBlank()) return 0.0

        // 通道 1：词法（多字段加权）
        var lexical = 0.0
        lexical += fieldScore(doc.name, q, 3.0)
        lexical += fieldScore(doc.title, q, 2.4)
        lexical += fieldScore(doc.description, q, 1.6)
        for (k in doc.keywords) lexical += fieldScore(k, q, 1.3)
        for (k in doc.triggers) lexical += fieldScore(k, q, 1.5)

        // 通道 2：概念（能力名 / 概念词命中）
        var concept = 0.0
        concept += fieldScore(doc.capability, q, 2.0)
        for (c in doc.concepts) concept += fieldScore(c, q, 1.6)
        // 触发词反查：用户说了这个能力的口语形态
        if (RagConcept.expandToCapacities(q).contains(doc.capability) && doc.capability.isNotBlank()) {
            concept += 1.8
        }

        // 通道 3：字形（3-gram Jaccard，取各字段最大值而非求和——避免长描述稀释）
        var shape = 0.0
        shape = maxOf(
            shape, RagFuzzy.trigramSimilarity(q, doc.name),
            RagFuzzy.trigramSimilarity(q, doc.title),
            RagFuzzy.trigramSimilarity(q, doc.description),
        )
        for (k in doc.keywords + doc.triggers + doc.concepts) {
            shape = maxOf(shape, RagFuzzy.trigramSimilarity(q, k))
        }
        // 整串包含是最强的字形证据（「导出APK」⊂「导出安装包」）
        if (q.length >= 3 && (doc.description.contains(q, true) || doc.name.contains(q, true) ||
                doc.title.contains(q, true) || doc.keywords.any { it.contains(q, true) })
        ) shape += 0.5

        // 通道 4：拼音首字母。逐 token 取最大（见 pinyinScoreTokenized 的说明）。
        var pinyin = 0.0
        pinyin = maxOf(
            pinyin,
            RagFuzzy.pinyinScoreTokenized(q, doc.name) { RagText.tokenize(it) },
            RagFuzzy.pinyinScoreTokenized(q, doc.title) { RagText.tokenize(it) },
        )
        for (k in doc.keywords + doc.triggers) {
            pinyin = maxOf(pinyin, RagFuzzy.pinyinScoreTokenized(q, k) { RagText.tokenize(it) })
        }

        var s = W_LEXICAL * lexical + W_CONCEPT * concept + W_SHAPE * shape + W_PINYIN * pinyin

        // 🔴 硬闸门：字形**不能单独**撑起一次命中。
        // 3-gram 擦边与 priority 先验加起来能到 1.2 分，会把纯擦边的文档顶到真答案前面。
        //
        // 历史教训（勿回退）：曾在这里加过「≥2 个成词 token」的严判据，想让乱串归零，
        // 结果把真查询也砍了——中文按 bigram 切分后 8 个 token 里通常只有 1 个成词
        // （实测「把这段视频弄短一点」只有「视频」在成词表里），于是四个视频工具
        // 全部并列 1.038，区分度归零。**模糊召回才是需求**，让乱串拿点低分是无所谓的。
        val pinyinSolid = pinyin >= 0.7
        if (lexical <= 0.0 && concept <= 0.0 && !pinyinSolid && !hasTypoEvidence(q, doc)) {
            return 0.0
        }

        // 错字纠正：命中近似词时给全额加分而不是打折，避免「微心搜微信」被字形通道压成噪声
        if (s > 0.0 && hasTypoEvidence(q, doc)) s += 0.6
        // 先验：作者声明的优先级
        s += doc.priority * 0.08
        // 整串精确命中 name（忽略大小写与下划线/短横差异）——见 EXACT_NAME_BONUS 的说明。
        // 这一票只在**原文**这一轮生效（rawScore 被扩展词调用时拿不到原文语义），
        // 所以放在这里由调用方保证 query 就是用户原话。
        val flatQuery = q.replace("_", "").replace("-", "").replace(" ", "")
        val flatName = doc.name.replace("_", "").replace("-", "").lowercase()
        val exactName = flatName.isNotEmpty() && flatName == flatQuery
        // 元工具压权（见 RagDoc.metaTool）——**精确命中时豁免**。
        //
        // 🔴 顺序有讲究，且「豁免」这一步不能省：
        //  1. 必须在 EXACT_NAME_BONUS **之前**：压权是「业务查询里让位」，
        //     而「用户直接点名」恰恰是它该拿满分的时候。
        //     初版把 `s *= 0.35` 放在加分之后，连精确命中一起压了——
        //     实测 rag_search 搜自己名字只排第 4（5.51），被 knowledge_search（11.67）压过去，
        //     而 knowledge_search 之所以那么高只是因为它描述里整段出现了「knowledge_rag_search」。
        //  2. 光「前移」还不够：压权是乘法，会把精确命中的分数一起缩小，
        //     而 `EXACT_NAME_BONUS` 是**固定加法**，压权越狠、加分越不够盖过别人。
        //     实测前移后 rag_search 精确搜索 7.948 仍排第 3（knowledge_search 11.665）。
        //     所以精确命中时根本不压——「用户点名 rag_search」时它就该是压倒性第一。
        if (doc.metaTool && !exactName) s *= 0.2
        if (exactName) s += EXACT_NAME_BONUS
        return s
    }


    /** 查询里的词与文档某个 token 构成拼写近似（错别字证据）。 */
    private fun hasTypoEvidence(query: String, doc: RagDoc): Boolean {
        val qTokens = RagText.tokenize(query)
        if (qTokens.isEmpty()) return false
        val docTokens = HashSet<String>()
        RagText.tokenize(doc.name).forEach { docTokens.add(it) }
        RagText.tokenize(doc.title).forEach { docTokens.add(it) }
        for (k in doc.keywords + doc.triggers + doc.concepts) RagText.tokenize(k).forEach { docTokens.add(it) }
        var evidence = 0
        for (t in qTokens) {
            if (t in docTokens) continue
            // 🔴 只有**成词**的近似才算错字线索。乱串切出的碎片（「全不」「关的」）
            // 在 268 个文档里随便就能撞上两个「近似」，不加这道限制时
            // 「zzzzqqq完全不相关的东西」会被判成错字证据，从而绕过硬闸门。
            if (t.length < 2) continue
            if (RagText.isStopWord(t) || RagText.isLatinNoise(t)) continue
            if (docTokens.any { RagFuzzy.isTypoMatch(t, it) }) evidence++
        }
        // 至少两个近似词才算证据：单个字的偶然近似（"日"~"月"）不能当错字线索
        return evidence >= 2
    }

    /** 字段命中率加权。与旧 `fieldScore` 同思路，但**归一化到 0..1**再乘权重。 */
    private fun fieldScore(field: String, query: String, weight: Double): Double {
        if (field.isBlank() || query.isBlank()) return 0.0
        val ft = RagText.tokenize(field)
        val qt = RagText.tokenize(query)
        if (ft.isEmpty() || qt.isEmpty()) return 0.0
        val set = ft.toHashSet()
        var hit = 0.0
        var hitWeight = 0.0
        for (t in qt) {
            if (!set.contains(t)) continue
            // 🔴 停用词**不算命中**（不是「算但只给 0.15 权重」）。
            //
            // 「算但降权」是错的：`coverage = hit / qt.size` 的分母里全是指南词，
            // 分子一旦把它们算进来，乱串也能靠通用词把 lexical 撑过 [rawScore] 的硬闸门。
            // 实测「zzzzqqq完全不相关的东西xyzzy」切出 7 个 bigram，
            // `experience_query` 靠「相关」（STOP_WORDS 里明写「几乎出现在每条文档里」）
            // + 「关的」拿到 1.225 分——**比真查询「把这段视频弄短一点」的 top1（1.0375）还高**。
            // 那正是 explain 把噪音当结果摊给模型的来源。
            //
            // 拉丁噪声同理：`zzzzqqq` / `xyzzy` 只是键盘乱敲，撞上任何字段都不构成证据。
            if (RagText.isStopWord(t) || RagText.isLatinNoise(t)) continue
            hit++
            hitWeight += RagText.informativeness(t)
        }
        if (hit == 0.0) return 0.0
        val coverage = hit / qt.size
        val informativeness = hitWeight / hit
        val bonus = if (hit >= qt.size) 0.5 else 0.0
        return (weight / 3.0) * (coverage * informativeness + bonus)
    }

    // ───────────────────────── 查询扩展 ─────────────────────────

    /**
     * 查询扩展：把长句拆成概念词。
     *
     * 这是「零命中也不空手」的第二道保险——第一道是四路融合，
     * 若四路全灭（例如用户整句都是这个工具域里没有的概念），就用概念词再试一轮。
     */
    fun expandQuery(query: String): List<String> {
        val q = RagConcept.normalize(query)
        if (q.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        // 能力名：口语 → 工具/能力标识符，这是最有效的一跳
        out.addAll(RagConcept.expandToCapacities(q))
        // 意图扩展词
        out.addAll(RagConcept.expandIntent(q))
        // 分词后取信息量最高的几个词单独检索
        RagText.tokenize(q)
            .sortedByDescending { RagText.informativeness(it) }
            .take(4)
            .forEach {
                // 扩展词必须是**实义词**：停用词、通用修饰语、键盘噪声一律不收。
                // 「不相关」这类词被切出来单独检索时，等于随机去撞库。
                if (it.length < 2) return@forEach
                if (RagText.isStopWord(it)) return@forEach
                if (RagText.informativeness(it) < 0.6) return@forEach
                // 纯 2 字 CJK bigram 大概率是跨词边界的碎片（实测「完全不相关」切出
                // 全不 / 不相 / 关的 / 的东），单独拿去撞库纯噪声。
                // 3 字以上、或含拉丁字母与数字的才可能是真词。
                if (it.length == 2 && it.all { c -> c.code in 0x4E00..0x9FFF }) return@forEach
                out.add(it)
            }
        return out.filter { it.isNotBlank() && it != q }.take(MAX_EXPANSION_TERMS)
    }
}

/**
 * 一个可检索文档。**任何领域的内容都能包装成它**。
 *
 * @param id 域内唯一标识。
 * @param name 技术标识（工具名 / 组件类型名 / 提示词块 id）。
 * @param title 人类可读标题。
 * @param description 说明正文，检索的主战场。
 * @param capability 所属能力标识（如 `web_search`），用于概念通道反查。
 * @param keywords 关键词，作者声明。
 * @param triggers 触发词，口语形态（可由 [RagConcept.triggersOf] 自动补）。
 * @param concepts 概念词，用于语义桥接。
 * @param priority 先验优先级 0..1，越大越容易被捞出。
 * @param hintBoost 作者置信加成 0..1。
 * @param payload 原始载荷，命中后交给调用方使用（schema / 组件描述 / 提示词正文）。
 * @param metaTool **元工具**（检索器自身，如 `rag_search` / `tool_router`）。
 *   元工具的关键词全是「工具/组件/怎么查」这类**元词汇**，与任何业务查询都有字面重叠，
 *   不压权就会在业务查询里抢top1（实测「把这段视频弄短一点」的 top1 变成 rag_search，
 *   真答案 video_gen 掉到榜外）。用户真要「查有什么工具」时它们才该排上来。
 */
data class RagDoc(
    val id: String,
    val name: String,
    val title: String,
    val description: String,
    val capability: String = "",
    val keywords: List<String> = emptyList(),
    val triggers: List<String> = emptyList(),
    val concepts: List<String> = emptyList(),
    val priority: Double = 0.5,
    val hintBoost: Double = 0.0,
    val payload: Any? = null,
    val metaTool: Boolean = false,
) {
    /** 检索命中的全部文本，拼成一条便于调试/回显。 */
    val searchableText: String
        get() = buildString {
            append(name).append(' ')
            append(title).append(' ')
            append(description)
            if (keywords.isNotEmpty()) append(' ').append(keywords.joinToString(" "))
            if (triggers.isNotEmpty()) append(' ').append(triggers.joinToString(" "))
            if (concepts.isNotEmpty()) append(' ').append(concepts.joinToString(" "))
        }
}

/**
 * 一条检索结果。
 *
 * @param doc 命中文档全文（含 payload），调用方据此取 schema / 提示词 / 组件定义。
 */
data class RagHit(
    val id: String,
    val title: String,
    val score: Double,
    val domain: String,
    val doc: RagDoc,
) {
    /** 渲染成给模型看的说明行。 */
    fun render(): String = buildString {
        append("**").append(nameOrId()).append("**")
        if (domain.isNotBlank()) append(" [").append(domain).append("]")
        if (doc.description.isNotBlank()) append("：").append(doc.description)
    }

    /** 回给模型时要用的**技术标识**：工具名 / 组件类型 / 提示词块 id。 */
    fun nameOrId(): String = doc.name.ifBlank { id }

    /**
     * 结构化回显（供 [com.ai.assistance.quro.core.tools.RagSearchTool] 用）。
     *
     * 为什么工具侧不自己拼：`RagDoc` 的字段含义（keywords / triggers / concepts 各是什么）
     * 只有引擎知道；让每个调用方各拼一次，早晚拼出互相不一致的版本。
     */
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("name", nameOrId())
        put("domain", domain)
        put("title", title)
        put("score", String.format(java.util.Locale.US, "%.3f", score))
        put("description", doc.description)
        if (doc.capability.isNotBlank()) put("capability", doc.capability)
    }
}

/**
 * 覆盖率报告：验收「所有工具是否都能被 RAG 到」的核心工具。
 *
 * ## 为什么必须有这个
 * 「所有工具都可 RAG」是个**可证伪**的断言。与其靠人肉翻 265 个工具抽查，
 * 不如对每个工具跑一遍代表性模糊查询，把零分的揪出来列成表——
 * 这也是 [com.ai.assistance.quro.core.tools.ToolRagCoverageReport] 的实现基础。
 */
class RagCoverageReport(
    val domain: String,
    val total: Int,
    val query: String,
    val missed: List<RagHit>,
    val weak: List<RagHit>,
    val covered: Int,
) {
    /** 全覆盖。 [weak] 非空时仍算覆盖（能召回，只是分数低）。 */
    val fullCoverage: Boolean get() = missed.isEmpty()

    fun summary(): String = buildString {
        appendLine("域：$domain　文档：$total　查询：「$query」")
        appendLine("可召回：$covered / $total")
        if (weak.isNotEmpty()) {
            appendLine("弱召回（分数 < ${WEAK_LINE}）：${weak.size}")
            weak.take(12).forEach { appendLine("  - ${it.id}（${fmt(it.score)}）") }
        }
        if (missed.isNotEmpty()) {
            appendLine("🔴 完全召不回（0 分）：${missed.size}")
            missed.take(20).forEach { appendLine("  - ${it.id}") }
        }
    }

    private fun fmt(v: Double) = String.format("%.3f", v)

    companion object {
        /** 弱召回线：低于此分虽能召回，但排序会靠后，实践中等于「查不到」。 */
        const val WEAK_LINE = 1.0
    }
}

/** 覆盖���核对。 */
fun RagEngine.coverageReport(domain: String, query: String, engine: RagEngine = this): RagCoverageReport {
    val all = engine.searchAll(domain, query)
    val missed = all.filter { it.score <= 0.0 }
    val weak = all.filter { it.score in 0.0001..RagCoverageReport.WEAK_LINE }
    val covered = all.count { it.score > 0.0 }
    return RagCoverageReport(
        domain = domain,
        total = all.size,
        query = query,
        missed = missed,
        weak = weak,
        covered = covered,
    )
}