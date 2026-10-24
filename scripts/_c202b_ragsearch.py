import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/tools/RagSearchTool.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

sub(
"""import com.ai.assistance.quro.core.rag.AgentRag
import com.ai.assistance.quro.core.rag.RagHit""",
"""import com.ai.assistance.quro.core.rag.AgentRag
import com.ai.assistance.quro.core.rag.PromptRagIndex
import com.ai.assistance.quro.core.rag.RagHit""",
'import')

# 🔴 检索入口：prompts 域必须走自己的门槛
sub(
"""            val crossDomain = jo.optBoolean("cross_domain", false)
            val hits: List<RagHit> = when (domain) {
                "tools" -> AgentRag.tools(q, limit)
                "prompts" -> AgentRag.prompts(q, limit.coerceAtMost(5))
                                else -> AgentRag.everything(q, limit)
            }""",
"""            val crossDomain = jo.optBoolean("cross_domain", false)
            val hits: List<RagHit> = when (domain) {
                "tools" -> AgentRag.tools(q, limit)
                // 🔴 prompts 域走自己的门槛（见 [PromptRagIndex.PROMPT_MIN_SCORE]）。
                // 旧代码走 AgentRag.prompts → 引擎默认 MIN_SCORE(0.62)，
                // 而 `fieldScore` 的 coverage 分母是整句 token 数，中文 bigram 切分后
                // 单个触发词最多值 0.1 —— 实测「这些数据做成表格给我看」top1 只有 0.318，
                // 直接零命中。这是「AI 被动检索规则」整条路失效的真因。
                "prompts" -> PromptRagIndex.searchPrompts(AgentRag.engine, q, limit.coerceAtMost(5))
                                else -> AgentRag.everything(q, limit)
            }""",
'gate')

# 🔴 domainMiss 里列别的域时，prompts 也用独立门槛
sub(
"""                            val got = runCatching {
                                when (dom) {
                                    "tools" -> AgentRag.tools(q, per)
                                    "prompts" -> AgentRag.prompts(q, per)
                                    else -> emptyList()
                                }
                            }.getOrDefault(emptyList())""",
"""                            val got = runCatching {
                                when (dom) {
                                    "tools" -> AgentRag.tools(q, per)
                                    "prompts" -> PromptRagIndex.searchPrompts(AgentRag.engine, q, per)
                                    else -> emptyList()
                                }
                            }.getOrDefault(emptyList())""",
'domainmiss')

# 🔴 核心：prompts 域回传完整正文
sub(
"""        private fun render(q: String, hits: List<RagHit>, explain: Boolean, note: String?): String {
            if (explain) return AgentRag.explain(q, hits.size)
            val arr = JSONArray()
            hits.forEach { arr.put(it.toJson()) }
            return JSONObject().apply {
                put("query", q)
                note?.let { put("note", it) }
                put("count", hits.size)
                put("hits", arr)""",
"""        private fun render(q: String, hits: List<RagHit>, explain: Boolean, note: String?): String {
            if (explain) return AgentRag.explain(q, hits.size)
            val arr = JSONArray()
            // 🔴 prompts 域必须带**完整规则正文**（payload 里的 PromptBlock.body）。
            // 旧实现只 put(hit.toJson())，而 toJson 只回 description，
            // 而 PromptBlock.toDoc 的 description 当时还是 body.take(200) ——
            // 于是 AI 主动检索「规则」时，命中了却只拿到 200 字碎片，
            // 拿到半条规则照样会用错。这是「改成 AI 被动使用」必须先补的洞。
            val promptHits = hits.filter { it.domain == PromptRagIndex.DOMAIN }
            val bodies = PromptRagIndex.renderFullBodies(promptHits)
            hits.forEach { h ->
                arr.put(
                    if (h.domain == PromptRagIndex.DOMAIN) {
                        val body = PromptRagIndex.ruleBodyOf(h)
                        h.toJson().apply {
                            if (body != null) {
                                put("rule_title", h.doc.title.ifBlank { h.id })
                                put("rule_body", body)
                            }
                        }
                    } else h.toJson()
                )
            }
            return JSONObject().apply {
                put("query", q)
                note?.let { put("note", it) }
                put("count", hits.size)
                put("hits", arr)
                // 🔴 额外给一份纯正文：模型读 hits 里的 rule_body 容易被 JSON 转义干扰，
                // 这份是可以直接照着执行的原文。
                if (bodies.isNotBlank()) put("rules_full_text", bodies)""",
'render')

sub(
"""                put(
                    "next",
                    "命中工具名后用 tool_router(action=\\"get_schema\\", name=...) 拿完整参数；" +
                        "命中卡片类型后用 card_catalog(types=[...]) 拿样例；" +
                        "命中组件类型后用 ui_dsl_spec 拿完整 DSL 规范。"
                )""",
"""                put(
                    "next",
                    "命中工具名后用 tool_router(action=\\"get_schema\\", name=...) 拿完整参数；" +
                        "命中卡片类型后用 card_catalog(types=[...]) 拿样例；" +
                        "命中组件类型后用 ui_dsl_spec 拿完整 DSL 规范；" +
                        "命中规则段（domain=prompts）后**按 rules_full_text 里那份正文执行**，不要只看摘要。"
                )""",
'next')

# 🔴 工具描述：改成把 rag_search 定位成"被动使用的主入口"
sub(
"""            "\\n- 你打算直接回「我不支持 / 我不会」之前——先搜一遍，目录里可能就有。\\n\\n" +""",
"""            "\\n- 你打算直接回「我不支持 / 我不会」之前——先搜一遍，目录里可能就有；\\n" +
            "- 拿不准「该用哪个组件 / 该守哪条规则」时——搜 domain=\\"prompts\\" 会带回**完整规则正文**。\\n\\n" +""",
'desc')

# 🔴 按行号精确插入：源码里该行没有前导 \n，且 \" 是真实的反斜杠+引号（Kotlin 转义）
lines = src.split('\n')
idx = next(i for i, l in enumerate(lines) if '严格隔离' in l and 'prompts' in l)
assert lines[idx].rstrip().endswith('+'), repr(lines[idx])
lines[idx] = (
    '            "\\n🔴 prompts 域命中时，每条 hit 带 rule_body、顶层带 rules_full_text，'
    '那是**规则原文**，照它执行即可（不要只看 description 摘要）。\\n" +\n'
    + lines[idx]
)
src = '\n'.join(lines)

assert src != orig
tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  bytes %d -> %d' % (len(orig), len(src)))