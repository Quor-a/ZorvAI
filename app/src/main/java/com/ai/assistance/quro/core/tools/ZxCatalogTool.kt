package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * zx 组件名册 / 字段契约查询。
 *
 * 存在意义：zx 组件有 477 个，字段词汇规则但不可猜（`arms` 还是 `items`？`v` 还是 `value`？）。
 * 把 195 KB 的完整契约塞进提示词不现实，所以拆成两步：
 * **先 zx_catalog 拿名册与字段契约 → 再 zx_web_card 下发卡片**。
 *
 * 三个用法：
 * - 空参        → 全量名册（`组件名(主数组:项字段)` 紧凑格式）
 * - `query`     → 按子串过滤名册
 * - `component` → 单个组件的字段契约 + 示例 DSL
 */
class ZxCatalogTool : QuroTool {

    override val name = "zx_catalog"

    override val description = """查询 zx 组件名册与字段契约（Web UI 卡片 / zxwidget 动态交互界面用）。
做卡片前**先用本工具确认组件名与字段**，不要凭印象写组件名或字段名。

用法：
- 不传参：返回全部 477 个组件的紧凑名册，格式 `组件名(主数组:项字段,项字段)`
- query="chart"：按子串过滤组件名
- component="zx_abmon"：返回该组件声明的字段、数组与示例 DSL

zx 字段词汇约定（贯穿全部组件）：
n=名称/标签, v=数值, d/desc=描述, t=文本, k=键, s/e=起止, at/when=时间, st/status=状态,
who=人, u/unit=单位, hex=颜色, e/icon=图标, max/min=量程, items/rows/nodes/pts=数组容器""" +
        """

返回值里的 example 是可以直接抄的 DSL 样例；把它填上真实数据后交给 zx_web_card。"""

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "component":{"type":"string","description":"组件名，返回该组件的字段契约与示例 DSL"},
            "query":{"type":"string","description":"按子串过滤组件名，如 chart / tree / zx_ab"}
        },
        "required":[]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = try {
            if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
        } catch (e: Exception) {
            JSONObject()
        }
        val component = args.optString("component", "").trim()
        val query = args.optString("query", "").trim()

        val root = loadContracts(context)
            ?: return """{"ok":false,"error":"组件契约资源缺失（asset zx_contracts.json）"}"""
        val contracts = root.optJSONObject("contracts") ?: JSONObject()
        val comps = root.optJSONArray("components") ?: return """{"ok":false,"error":"契约缺少 components 字段"}"""

        // 单组件详情
        if (component.isNotEmpty()) {
            val ct = contracts.optJSONObject(component)
                ?: return """{"ok":false,"error":"没有名为 $component 的组件","hint":"用 zx_catalog 不传参看名册，或 query 过滤"}"""
            val out = JSONObject()
                .put("ok", true)
                .put("component", component)
                .put("fields", ct.optJSONArray("fields") ?: org.json.JSONArray())
                .put("arrays", ct.optJSONArray("arrays") ?: org.json.JSONArray())
            val sample = ct.opt("sample")
            if (sample != null && sample != JSONObject.NULL) out.put("example", sample)
            val itemKeys = itemKeysOf(ct)
            if (itemKeys.isNotEmpty()) out.put("item_fields", org.json.JSONArray(itemKeys))
            return out.toString()
        }

        // 名册
        val sb = StringBuilder()
        var count = 0
        for (i in 0 until comps.length()) {
            val name = comps.optString(i, "")
            if (name.isBlank()) continue
            if (query.isNotEmpty() && !name.contains(query, ignoreCase = true)) continue
            val ct = contracts.optJSONObject(name) ?: JSONObject()
            sb.append(rosterLine(name, ct)).append('\n')
            count++
        }
        if (count == 0) {
            return """{"ok":false,"error":"没有匹配 query="$query" 的组件","hint":"用 zx_catalog 不传参看全部名册"}"""
        }
        return JSONObject()
            .put("ok", true)
            .put("count", count)
            .put("roster", sb.toString().trimEnd())
            .toString()
    }

    /** `zx_abmon(arms:n,p,k)` —— 主数组 + 其项字段；无数组则列标量字段。 */
    private fun rosterLine(name: String, ct: JSONObject): String {
        val arrays = ct.optJSONArray("arrays") ?: org.json.JSONArray()
        val sample = ct.opt("sample")
        var mainArr = ""
        var itemKeys: List<String> = emptyList()
        for (i in 0 until arrays.length()) {
            val a = arrays.optString(i, "")
            val item = firstItem(sample, a)
            if (item != null) {
                mainArr = a
                itemKeys = item.keys().asSequence().toList()
                break
            }
        }
        if (mainArr.isEmpty() && arrays.length() > 0) mainArr = arrays.optString(0, "")
        return when {
            mainArr.isNotEmpty() && itemKeys.isNotEmpty() ->
                "$name($mainArr:${itemKeys.take(8).joinToString(",")})"
            mainArr.isNotEmpty() -> "$name($mainArr)"
            else -> {
                val f = ct.optJSONArray("fields") ?: org.json.JSONArray()
                "$name(${(0 until f.length()).map { f.optString(it, "") }.take(6).joinToString(",")})"
            }
        }
    }

    private fun itemKeysOf(ct: JSONObject): List<String> {
        val arrays = ct.optJSONArray("arrays") ?: return emptyList()
        val sample = ct.opt("sample")
        for (i in 0 until arrays.length()) {
            val item = firstItem(sample, arrays.optString(i, ""))
            if (item != null) return item.keys().asSequence().toList()
        }
        return emptyList()
    }

    /** sample[arrayField][0] —— 只要它是对象。 */
    private fun firstItem(sample: Any?, arrayField: String): JSONObject? {
        if (sample !is JSONObject || arrayField.isEmpty()) return null
        val arr = sample.optJSONArray(arrayField) ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(0)
    }

    private fun loadContracts(context: Context): JSONObject? = try {
        val text = context.assets.open("zx_contracts.json").bufferedReader().use { it.readText() }
        JSONObject(text)
    } catch (e: Exception) {
        Log.w(TAG, "读取 zx_contracts.json 失败: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "ZxCatalogTool"
    }
}
