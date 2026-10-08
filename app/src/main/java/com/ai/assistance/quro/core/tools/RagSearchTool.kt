package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.rag.AgentRag
import com.ai.assistance.quro.core.rag.PromptRagIndex
import com.ai.assistance.quro.core.rag.RagHit
import org.json.JSONArray
import org.json.JSONObject

/**
 * `rag_search` 工具：**万物可RAG** 的模型侧入口。
 *
 * ## 为什么需要它（工具 RAG 不够用）
 * [com.ai.assistance.quro.core.tools.QuroToolRouter] 的 `match_intent` 只检索**工具域**。
 * 但模型真正拿不定主意的时候，问的往往不是「哪个工具」，而是「**我该走哪条路**」：
 * - 「这个趋势用啥组件画」→ 该答`heatmap`（动态UI组件域），但工具域里没有这个东西；
 * - 「出海报」→ 该答 `codecanvas_onscreen_script`（确定性渲染）而不是 `image_gen`（AI 生图）；
 * - 「记忆怎么存」→ 工具域 + 提示词域的 `memory_policy` 段一起看才说得清。
 *
 * 这些问题过去只能靠模型自己的记忆判断，于是就有了用户报的
 * 「AI 好像不知道我有这个组件」「明明有这个功能它却说要不会」。
 *
 * ## 🔴 旧架构完整保留
 * 本工具**不替换** `tool_router` / `card_catalog` / `ui_dsl_spec` 任何一条老路：
 * - 想按工具名查 →照旧 `tool_router.match_intent`；
 * - 想按类目查卡片 → 照旧 `card_catalog(category=...)`；
 * - 想看动态 UI 完整规范 → 照旧 `ui_dsl_spec`。
 * 本工具只在**模型自己都说不清要什么**时先问一句「我该用什么」，拿到的建议里
 * 仍然会指向那些老工具，属于**上一层路由**而非替代。
 */
class RagSearchTool : QuroTool {

    override val name = "rag_search"

    override val description =
        "模糊检索：把你**用用户的原话**描述的需求，一次性落到「该用哪个工具 / 该注入哪条规则 / " +
            "该用哪个界面组件」上——工具、系统提示词规则、动态UI组件、界面交付路径四类一起搜。" +
            "\n\n什么时候用它（tool_router 的 match_intent 只管工具，不管组件与规则）：" +
            "\n- 你不知道该用哪个工具，且用户说的是效果不是工具名（「把这段视频弄短一点」）；" +
            "\n- 你在纠结该走哪条界面交付路径（「出海报」是 AI 生图还是确定性渲染？）；" +
            "\n- 用户想看趋势/占比/排行，你不确定名册里对应哪个组件类型；" +
            "\n- 你打算直接回「我不支持 / 我不会」之前——先搜一遍，目录里可能就有；\n" +
            "- 拿不准「该用哪个组件 / 该守哪条规则」时——搜 domain=\"prompts\" 会带回**完整规则正文**。\n\n" +
            "参数：query（**用户的原话**，别自己概括，概括会丢掉关键限定词）" +
            " / domain（all|tools|prompts，默认 all）" +
            " / limit（返回条数，默认 10）" +
            " / prefer_genui（true=优先整屏界面路径，做完整页面时用）" +
            " / cross_domain（**默认 false**：限定域零命中时**绝不**返回别的域的结果，" +
            "只会告诉你本域没有 + 其它域各有什么。只有你确定「就想要别的域的」时才传 true）" +
            " / explain（true 时回人类可读的建议清单，默认 false，回结构化 JSON）。\n\n" +
            "\n🔴 prompts 域命中时，每条 hit 带 rule_body、顶层带 rules_full_text，那是**规则原文**，照它执行即可（不要只看 description 摘要）。\n" +
            "🔴 domain 是**严格隔离**的：domain=\"prompts\" 就只会返回 prompts，" +
            "绝不会返回 tools。以前版本会在零命中时偷偷回退成跨域结果，" +
            "让你误以为「域过滤方向是反的」—— 那个 bug 已修，别再依赖那种行为。"

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "检索词。**用用户原话**，例如「把这段视频弄短一点」「来个仪表盘看完成度」。")
            })
            put("domain", JSONObject().apply {
                put("type", "string")
                put("description", "限定检索域：all（默认，跨域）/ tools（只搜工具）/ prompts（只搜规则段）")
                put("enum", JSONArray().apply { put("all"); put("tools"); put("prompts") })
            })
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "返回条数，默认 10")
            })
            put("prefer_genui", JSONObject().apply {
                put("type", "boolean")
                put("description", "true 时界面组件优先整屏 GenUI 路径（用户要「做一个完整页面」时传）")
            })
            put("cross_domain", JSONObject().apply {
                put("type", "boolean")
                put("description", "限定域零命中时是否回退到跨域结果。**默认 false**——" +
                    "默认行为是返回本域零命中 + 其它域各自的线索，绝不把别的域混进 hits")
            })
            put("explain", JSONObject().apply {
                put("type", "boolean")
                put("description", "true 回人类可读建议清单，false（默认）回结构化 JSON")
            })
        })
        put("required", JSONArray().apply { put("query") })
    }.toString()

    override val readOnly = true

    override fun run(context: Context, arguments: String): String = query(arguments)

    companion object {
        /**
         * 纯逻辑入口，不碰 Android 依赖，故可 JVM 单测
         * （同 [CardCatalogTool.query] 的理由：工程没引 Robolectric）。
         */
        fun query(arguments: String): String {
            val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            val q = jo.optString("query", "").trim()
            if (q.isEmpty()) {
                return "请提供 query，例如：rag_search(query=\"把这段视频弄短一点\")"
            }
            val domain = jo.optString("domain", "all").ifBlank { "all" }
            val limit = jo.optInt("limit", 10).coerceIn(1, 30)
            val preferGenUi = jo.optBoolean("prefer_genui", false)

            // 索引可能还没装（极早期调用 / 工具注册顺序变化）。**零命中绝不返回空**
            // ——空列表会被渲染成「未找到」，正是用户报的「查不到」，比多返回几条更糟。
            if (AgentRag.total() <= 0) {
                // 🔴 必须带上真实工具清单。无参 refresh() 只装 prompts，
                // tools 域会空 —— 那样 rag_search(domain="tools") 永远零命中，
                // 模型会以为「根本没有这个工具」。
                AgentRag.refresh(ToolCapabilityDirectory.specsSnapshot())
            }

            //🔴 限定域**就是限定域**。旧实现在零命中时无条件回退 everything()，
            // 只在 note 里写一行「本次限定域无命中」—— 而模型读的是 hits 不是 note，
            // 于是它看到「查 prompts 返回一堆 tools」就报「domain 过滤方向颠倒」。
            // 这是真 bug：过滤结果不该被静默替换掉。
            // 现在跨域必须**显式** cross_domain=true，且默认 false。
            val crossDomain = jo.optBoolean("cross_domain", false)
            val hits: List<RagHit> = when (domain) {
                "tools" -> AgentRag.tools(q, limit)
                // 🔴 prompts 域走自己的门槛（见 [PromptRagIndex.PROMPT_MIN_SCORE]）。
                // 旧代码走 AgentRag.prompts → 引擎默认 MIN_SCORE(0.62)，
                // 而 `fieldScore` 的 coverage 分母是整句 token 数，中文 bigram 切分后
                // 单个触发词最多值 0.1 —— 实测「这些数据做成表格给我看」top1 只有 0.318，
                // 直接零命中。这是「AI 被动检索规则」整条路失效的真因。
                "prompts" -> PromptRagIndex.searchPrompts(AgentRag.engine, q, limit.coerceAtMost(5))
                                else -> AgentRag.everything(q, limit)
            }

            if (hits.isEmpty() && crossDomain) {
                // 显式要求跨域才跨域：单域没命中不代表别的域也没有，
                // 但**必须是调用方主动要求**，否则就是在骗模型。
                val fb = AgentRag.everything(q, limit)
                if (fb.isEmpty()) return noHit(q, domain)
                return render(
                    q, fb, jo.optBoolean("explain", false),
                    note = "限定域 domain=$domain 零命中；以下是**跨域**结果，" +
                        "每条的 domain 字段才是它真正的归属。",
                )
            }
            if (hits.isEmpty()) {
                // 零命中绝不返回空（本仓铁律：空列表会被渲染成「未找到匹配的工具」），
                // 但要说清是**哪个域没命中**、以及别的域各有什么 —— 让模型自己决定下一步，
                // 而不是拿到一份被换域污染的结果。
                return domainMiss(q, domain, limit)
            }
            return render(q, hits, jo.optBoolean("explain", false), note = null)
        }

        /**
         * 全库零命中。
         *
         * 措辞铁律：必须说清是「全库都没检索到」而不是让模型以为某个域为空，
         * 并给出可执行的下一步（换说法 / 看类别清单）。
         */
        private fun noHit(q: String, domain: String): String =
            JSONObject().apply {
                put("query", q)
                put("requested_domain", domain)
                put("count", 0)
                put("hits", JSONArray())
                // 🔴 本工具对外的契约是 JSON（调用方直接 JSONObject(out)）。
                // 旧实现这里返回散文，跨域且全库空时会让调用方抛 JSONException。
                // 散文一字不改地留在 message 里，只是外面套了结构。
                put(
                    "message",
                    "没有检索到与「$q」直接相关的内容（限定域 domain=$domain，全库兜底同样为空）。",
                )
                put(
                    "next",
                    "换个更具体的说法（说清你想达成的效果，而不是你想用的工具名），" +
                        "或用 tool_router(action=\"list_categories\") 看有哪些类别。",
                )
            }.toString()

        /**
         * 限定域零命中 —— **本函数是「domain 过滤方向颠倒」这个 bug 的正解**。
         *
         * 旧实现在这里直接换成跨域结果，模型只读 hits，于是得出错误结论。
         * 现在：域严格隔离，并**结构化**列出每个域各自召回了什么，
         * 模型据此就能自己判断「该换域」还是「该改查询词」，而不是被误导。
         */
        private fun domainMiss(q: String, domain: String, limit: Int): String {
            val byDomain = org.json.JSONObject().apply {
                put("query", q)
                put("requested_domain", domain)
                put("count", 0)
                put("hits", org.json.JSONArray())
                put("note", "限定域 domain=$domain 零命中。这里没有混入任何其它域的结果" +
                    "（旧版本会在这里偷偷回退成跨域结果，导致「要 prompts 却返回 tools」）。")
                put(
                    "what_other_domains_return",
                    org.json.JSONArray().apply {
                        val per = (limit / 3).coerceIn(2, 4)
                        for (dom in listOf("tools", "prompts")) {
                            if (dom == domain) continue
                            val got = runCatching {
                                when (dom) {
                                    "tools" -> AgentRag.tools(q, per)
                                    "prompts" -> PromptRagIndex.searchPrompts(AgentRag.engine, q, per)
                                    else -> emptyList()
                                }
                            }.getOrDefault(emptyList())
                            put(org.json.JSONObject().apply {
                                put("domain", dom)
                                put("count", got.size)
                                put("names", org.json.JSONArray().apply {
                                    got.take(4).forEach { put(it.nameOrId()) }
                                })
                            })
                        }
                    }
                )
                put(
                    "next",
                    "若上面某个域有你要的，就用 rag_search(domain=\"那个域\", query=\"更具体的说法\") 重查；" +
                        "命中工具名后用 tool_router(action=\"get_schema\", name=...) 拿完整参数。"
                )
            }
            return byDomain.toString()
        }

        private fun render(q: String, hits: List<RagHit>, explain: Boolean, note: String?): String {
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
                if (bodies.isNotBlank()) put("rules_full_text", bodies)
                put(
                    "next",
                    "命中工具名后用 tool_router(action=\"get_schema\", name=...) 拿完整参数；" +
                        "命中卡片类型后用 card_catalog(types=[...]) 拿样例；" +
                        "命中组件类型后用 ui_dsl_spec 拿完整 DSL 规范；" +
                        "命中规则段（domain=prompts）后**按 rules_full_text 里那份正文执行**，不要只看摘要。"
                )
            }.toString()
        }
    }
}
