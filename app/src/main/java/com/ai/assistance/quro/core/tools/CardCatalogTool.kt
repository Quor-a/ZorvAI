package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.cards.CardFence
import com.ai.assistance.quro.core.cards.CardSdk
import org.json.JSONArray
import org.json.JSONObject

/**
 * `card_catalog` 工具：可视化组件的**按需目录查询**。
 *
 * ## 为什么需要它
 *
 * 名册已扩到 92 种，但把 92 份完整样例常驻进系统提示词要吃掉十几 KB 上下文。
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
            " / normalize（是否顺带做 A2UI 组件名归一化，如 GanttChart→gantt，默认 false）。"

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

                val cat = category?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                val want = types?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
                val picked = CardSdk.all.filter { spec ->
                    (cat == null || spec.category == cat) && (want.isEmpty() || spec.type in want)
                }

                val items = JSONArray()
                if (detail) {
                    // 完整样例：复用 CardSdk.samples（继承其类目校验与字符上限截断），
                    // 再把外层数组**摊平**并入 items —— 直接 put 会多包一层数组，
                    // 模型按数组元素取对象就会拿到 JSONArray 而不是 JSONObject
                    val full = JSONArray(CardSdk.samples(category, types, maxChars = 8000))
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
                if (!normalize) return items.toString()

                // 归一化附表：让模型知道「A2UI / RN 那套 PascalCase 名」对应哪个 snake_case type
                val norm = JSONArray()
                (types ?: emptyList()).forEach { raw ->
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
    }
}