import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/tools/CardCatalogTool.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

old_find = """        private fun findByIntent(query: String): Set<String> {
            if (AgentRag.total() <= 0) AgentRag.refresh()
            val engine = RagEngine()
            engine.clearDomain(DOMAIN_CARDS)
            CardSdk.all.forEach { spec ->
                engine.register(DOMAIN_CARDS, com.ai.assistance.quro.core.rag.RagDoc(
                    id = spec.type,
                    name = spec.type,
                    title = "[富卡片] " + spec.type,
                    description = spec.description,
                    capability = spec.type,
                    // 用 description 本身做关键词：它是作者手写的中文用途说明，
                    // 比另建同义词表更贴近真实意图，且新增卡片自动带上。
                    keywords = spec.description.split('，', '、', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
                    triggers = listOf(spec.category, spec.category + "卡片"),
                    concepts = listOf("卡片", spec.category),
                    priority = 0.6,
                    payload = spec.type,
                ))
            }
            val hits = runCatching {
                engine.search(query, DOMAIN_CARDS, limit = 6, minScore = 1.2)
            }.getOrDefault(emptyList())
            return hits.mapNotNull { it.doc.payload as? String }.toSet()
        }"""

new_find = """        /**
         * 卡片域检索专用引擎。
         *
         * 🔴 2026-10-08 从「每次调用现建现弃」改为**进程级单例**（懒建）。
         * 旧实现每次 `find` 都 new 一个 [RagEngine] 并注册 101 篇文档 ——
         * 模型一轮里试几次 find 就重建几次，纯浪费。索引内容只依赖 [CardSdk.all]
         * （静态注册表），不会变，所以缓存是安全的。
         */
        private val cardEngine: RagEngine by lazy {
            if (AgentRag.total() <= 0) AgentRag.refresh()
            RagEngine().also { e ->
                e.clearDomain(DOMAIN_CARDS)
                CardSdk.all.forEach { spec ->
                    e.register(DOMAIN_CARDS, com.ai.assistance.quro.core.rag.RagDoc(
                        id = spec.type,
                        name = spec.type,
                        title = "[富卡片] " + spec.type,
                        description = spec.description,
                        capability = spec.type,
                        // 用 description 本身做关键词：它是作者手写的中文用途说明，
                        // 比另建同义词表更贴近真实意图，且新增卡片自动带上。
                        keywords = spec.description.split('，', '、', ',', ' ').map { it.trim() }
                            .filter { it.isNotEmpty() },
                        triggers = listOf(spec.category, spec.category + "卡片"),
                        concepts = listOf("卡片", spec.category),
                        priority = 0.6,
                        payload = spec.type,
                    ))
                }
            }
        }

        /**
         * 🔴 卡片域的召回门槛（2026-10-08，从 1.2 降下来）。
         *
         * ## 降的实测依据（这是「老是使用同一个类型组件」的直接根因）
         *
         * 旧值 `minScore = 1.2`。而 [com.ai.assistance.quro.core.rag.RagEngine] 的
         * `fieldScore` 用 `coverage = hit / qt.size` —— 分母是**整句** token 数。
         * 中文按 bigram 切分后「来个仪表盘看完成度」是 9 个 token，
         * 于是**单个词最多只值 1/9 的覆盖率**，实测 top1 远低于 1.2 → **零命中**。
         *
         * 探针实测（改前，6 条真实说法只召回 1 条）：
         * | 说法 | 改前 | 正确答案 |
         * |---|---|---|
         * | 来个仪表盘看完成度 | ❌ 零命中 | gauge /speedometer |
         * | 把占比画出来 | ❌ 零命中 | pie |
         * | 做个时间线 | ❌ 零命中 | timeline |
         * | 展示排名 | ❌ 零命中 | scoreboard |
         * | 对比两组差距 | ❌ 零命中 | compare |
         * | 能打分的 | ✅ matrix | matrix |
         *
         * 机制上讲：**AI 查不到合适类型 → 只能反复用那几个耳熟能详的**
         * （table / keyvalue / text），这正是用户报的「老是使用一个类型组件」。
         * 检索修好之前，光在提示词里喊「别只用一种类型」是没有用的 ——
         * 它不是不愿用，是**用不到**别的。
         *
         * 取 0.15：低于它等于「查询里连一个卡片域实词都没有」，
         * 实测纯符号噪声在此拿 0 分，安全。
         */
        private const val CARD_MIN_SCORE = 0.15

        private fun findByIntent(query: String): Set<String> {
            val hits = runCatching {
                cardEngine.search(query, DOMAIN_CARDS, limit = 6, minScore = CARD_MIN_SCORE)
            }.getOrDefault(emptyList())
            return hits.mapNotNull { it.doc.payload as? String }.toSet()
        }"""

sub(old_find, new_find, 'find')

assert src != orig
t = p + '.tmp'
io.open(t, 'w', encoding='utf-8', newline='').write(src)
os.replace(t, p)
print('OK  %d -> %d' % (len(orig), len(src)))