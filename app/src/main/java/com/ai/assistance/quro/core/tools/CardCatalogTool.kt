package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.cards.CardFence
import com.ai.assistance.quro.core.cards.CardPatch
import com.ai.assistance.quro.core.cards.CardSdk
import com.ai.assistance.quro.core.rag.AgentRag
import com.ai.assistance.quro.core.rag.RagEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * `card_catalog` 工具：可视化组件的**按需目录查询**。
 *
 * ## 为什么需要它
 *
 * 名册已扩到上百种，但把上百份完整样例常驻进系统提示词要吃掉十几 KB 上下文。
 * 于是拆成两级：
 *  - **常驻**：`ui_widget` / `ui_card` 的工具描述里带一行紧凑清单
 *    （[CardSdk.compactCatalog]，约 2KB），模型知道「有哪些类、每类有哪些卡」；
 *  - **按需**：真要写复杂卡时用本工具拉该类目的完整样例与字段说明。
 *
 * 没有这一环的话，新增组件只对「碰巧在围栏里见过样例」的模型有效 ——
 * 名册扩充就变成了静默的能力增长，而不是可见的能力增长。
 *
 * 参数：
 * - `category`：类目名（input/data/layout/action/nav/media/flow/decoration/aiwrite），可空
 * - `types`：type 名列表，可空
 * - `detail`：true 时返回每种的完整样例（默认）；false 时只回 type+说明，省 token
 * - `normalize`：传 true 时用真实组件名（如 `GanttChart`）也能查到，顺带返回归一化后的 type
 */
class CardCatalogTool : QuroTool {
    override val name = "card_catalog"
    override val description =
        "查询可视化组件（ui_widget / ui_card 可下发的富卡片）的可用类型与写法。" +
            "共 ${CardSdk.typeCount} 种，按 9 个类目组织。需要写复杂卡片时先用它拿到该类目的完整样例再下发，" +
            "不要凭空猜字段名——字段写错会静默渲染成兜底卡。" +
            "（本工具即 card_catalog；ui_widget / ui_card 的描述里已带一份紧凑清单，" +
            "样例太大故不在描述里常驻。）" +
            "参数：category（类目名，可空表示全部）/ types（type 名列表，可空）/ detail（是否回完整样例，默认 true）" +
            " / normalize（是否顺带做 A2UI 组件名归一化，如 GanttChart→gantt，默认 false）" +
            " / fence（是否改为返回正文围栏的 4 种头与属性语法 title=/theme=/compact，默认 false）" +
            " / patch（是否改为返回 card_patch 增量更新的补丁语法，默认 false）" +
            " / find（**按模糊描述检索**组件类型，直接说用户想要什么效果即可，默认空）。"

    /**
     * 参数 schema 用 [JSONObject] 运行时构造，而不是拼字符串。
     *
     * 拼字符串版本（四引号 raw string）在这工具里栽过一次：
     * properties 多闭一层花括号，**编译不报、运行不报**，只有工具真正下发、
     * 上游按 JSON Schema 解析时才炸。改成由库逐层构造，结构正确性不再靠人眼数括号。
     */
    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("category", JSONObject().apply {
                put("type", "string")
                put("description", "类目名，可空：input/data/layout/action/nav/media/flow/decoration/aiwrite")
            })
            put("types", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "type 名列表，可空表示该类目全部")
            })
            put("detail", JSONObject().apply {
                put("type", "boolean")
                put("description", "true 回完整样例 JSON（默认），false 只回 type 与说明")
            })
            put("normalize", JSONObject().apply {
                put("type", "boolean")
                put("description", "true 时同时返回 A2UI 别名归一化结果，默认 false")
            })
            put("fence", JSONObject().apply {
                put("type", "boolean")
                put("description", "true 时改为返回正文围栏的 4 种头与属性语法（title=/theme=/compact），默认 false")
            })
            put("patch", JSONObject().apply {
                put("type", "boolean")
                put("description", "true 时改为返回 card_patch 的 JSON Pointer 增量补丁语法，默认 false")
            })
            put("find", JSONObject().apply {
                put("type", "string")
                put("description", "**按模糊描述检索组件类型**：直接说用户想要什么效果即可，" +
                    "例如「来个仪表盘看完成度」「把占比画出来」「能打分的」。" +
                    "不必知道 type 的英文名。命中后照常用 detail/types 取完整样例。")
            })
        })
        put("required", JSONArray())
    }.toString()

    override val readOnly = true

    override fun run(context: Context, arguments: String): String = query(arguments)

    companion object {
        /**
         * 纯逻辑入口，**不碰任何 Android 依赖**，故可 JVM 单测。
         *
         * 工程没引 Robolectric，`unitTests.isReturnDefaultValues` 也给不出真 Context；
         * 而 [QuroTool.run] 的 context 形参非空，字节码插桩的 `checkNotNullParameter`
         * 连 unchecked cast 都拦得住。所以把逻辑整段搬到这里，工具只做薄包装。
         */
        fun query(arguments: String): String {
            return try {
                val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
                val category = jo.optString("category", "").ifBlank { null }
                val types = jo.optJSONArray("types")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it, "").ifBlank { null } }
                }
                val detail = jo.optBoolean("detail", true)
                val normalize = jo.optBoolean("normalize", false)
                val fence = jo.optBoolean("fence", false)
                val patchMode = jo.optBoolean("patch", false)
                val findQ = jo.optString("find", "").trim()
                // 模糊检索：把命中的 type 直接当 types 用，于是 detail/normalize 等后续参数照样生效，
                // 模型拿到的是「可直接照抄的完整样例」而不是又一层间接结果。
                val findHits = if (findQ.isNotEmpty()) findByIntent(findQ) else emptyList()

                // fence / patch 模式：模型问的是「围栏怎么写」或「补丁怎么写」，
                // 与组件目录无关，所以完全独立返回 —— 混进 items 会让模型在一堆组件里找语法。
                if (patchMode) return CardPatch.syntaxJson()
                if (fence) return fenceSyntaxJson()

                val cat = category?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                val want = (
                    types?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty() +
                        findHits
                    ).toSet()
                val picked = CardSdk.all.filter { spec ->
                    (cat == null || spec.category == cat) && (want.isEmpty() || spec.type in want)
                }

                val items = JSONArray()
                if (detail) {
                    // 🔴🔴 完整样例必须用**合并后的 want**（types ∪ find 的模糊召回），
                    // 不能传原始 types。
                    // 旧码传 `types`：于是 find 命中被算进 picked 却没进 full 样例，
                    // 模型拿到的 detail 结果里根本没有它召回的类型 ——
                    // 这就是「card_catalog 的 find 连官方示例查询都召不回」的直接原因。
                    // 🔴🔴 samples 必须用**合并后的 want**（types ∪ find 的模糊召回），
                    // 不能传原始 types —— 否则 find 命中被算进 picked 却没进 full 样例，
                    // 模型拿到的 detail 里根本没有它召回的类型。
                    // （实测症状：「card_catalog 的 find 连官方示例查询都召不回」）
                    //
                    // 未知类目时 samples 会返回 `[{"error":..., "known":[...]}]`，
                    // 这个**必须原样透传**：它是既有契约（CardCatalogToolTest 钉死），
                    // 也是模型自我纠正的唯一依据（known 列表就在同一对象里）。
                    val sampleTypes = if (want.isEmpty()) types else want.toList()
                    val full = JSONArray(CardSdk.samples(category, sampleTypes, maxChars = 8000))
                    for (i in 0 until full.length()) items.put(full.get(i))
                } else {
                    // 省 token 模式：只回 type / 类目 / 说明
                    picked.forEach { spec ->
                        items.put(JSONObject().apply {
                            put("type", spec.type)
                            put("category", spec.category)
                            put("description", spec.description)
                        })
                    }
                }
                if (findQ.isNotEmpty() && findHits.isEmpty()) {
                    // 模糊检索没召回到任何真实组件：必须说清「没召回到」而不是回一个空 items，
                    // 否则模型会把空数组当成「这类组件不存在」并直接告诉用户做不了。
                    return "没有召回到与「$findQ」直接匹配的卡片类型。可先用 category 或 types 直查" +
                        "（9 个类目：input/data/layout/action/nav/media/flow/decoration/aiwrite），" +
                        "或换个更具体的说法。"
                }
                if (!normalize) return items.toString()

                // 归一化附表：让模型知道「A2UI / RN 那套 PascalCase 名」对应哪个 snake_case type
                val norm = JSONArray()
                (if (want.isEmpty()) (types ?: emptyList()) else want.toList()).forEach { raw ->
                    val t = CardFence.normalizeType(raw)
                    norm.put(JSONObject().apply {
                        put("input", raw)
                        put("type", t)
                        put("known", t in CardSdk.types)
                    })
                }
                JSONObject().apply {
                    put("items", items)
                    put("normalized", norm)
                }.toString()
            } catch (e: Exception) {
                "❌ card_catalog 失败：${e.message}"
            }
        }

        /**
         * 按模糊描述召回**卡片 type**。
         *
         * ## 索引现读 [CardSdk]，不另维护一张表
         * 上百种卡片每新增一种就要在别处补一次登记，漏一次就是「这卡存在但 AI 永远想不到用」，
         * 而且**没有任何报错**。这里直接拿 [CardSdk.all] 建临时索引，
         * 于是新增卡片自动可检索——漏登记在结构上就不可能发生。
         *
         * 返回的是 type 名集合（不是文档），调用方拿它当 `types` 用，
         * 因此 detail / normalize / fence 等既有参数行为完全不变。
         */
        /**
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
        }

        /** [findByIntent] 的临时域标识。每次调用现建现弃，不进全局引擎。 */
        private const val DOMAIN_CARDS = "cards_lookup"

        /**
         * 围栏语法说明（`fence=true`）。
         *
         * 从 [CardFence] 现读而不是写死一份文案 —— 属性语法是**会变的**
         * （新增开关、改主题档位名），写死就会出现「工具说的和解析器认的不是一套」，
         * 而这种不一致只在模型真按错误文案下发时才暴露。
         */
        private fun fenceSyntaxJson(): String = JSONObject().apply {
            put("fences", JSONArray(CardFence.ALL_FENCES))
            put("usage", "```<围栏头> [属性...]\\n<JSON>\\n```")
            put(
                "switches",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("name", "compact")
                            put("effect", "卡片内边距收紧（14dp→10dp）；一组小卡片并排时必给，否则散成一堆")
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("name", "scroll")
                            put("effect", "内容超高时卡片内部滚动，而不是把气泡撑长")
                        }
                    )
                }
            )
            put(
                "valueAttrs",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("name", "title")
                            put("syntax", "title=Q3 复盘")
                            put("effect", "组级标题；各卡自己带 title 时以卡的为准")
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("name", "theme")
                            put("syntax", "theme=accent")
                            put("allowed", JSONArray(CardFence.THEME_PRESETS))
                            put("effect", "主题档位；不在白名单内一律降级为 accent")
                        }
                    )
                }
            )
            put(
                "examples",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("note", "一组带统一标题与主题的小卡片")
                            put(
                                "text",
                                "```cards title=Q3 复盘 theme=accent compact\\n" +
                                    "[{\"type\":\"stat\",\"label\":\"营收\",\"value\":\"1.2M\",\"trend\":\"up\"}]\\n```"
                            )
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("note", "流式友好：一行一个 JSON，已写完的行立刻出卡")
                            put(
                                "text",
                                "```cardjson title=日志\\n" +
                                    "{\"type\":\"stat\",\"label\":\"A\",\"value\":\"1\"}\\n" +
                                    "{\"type\":\"stat\",\"label\":\"B\",\"value\":\"2\"}\\n```"
                            )
                        }
                    )
                }
            )
            put("note", "属性只在围栏头上、JSON 之前；组合卡（含 children）请用 cards，逐行容器请用 cardjson")
        }.toString()
    }
}