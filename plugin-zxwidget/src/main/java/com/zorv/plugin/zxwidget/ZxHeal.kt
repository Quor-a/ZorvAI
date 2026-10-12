package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * DSL 自愈 —— 校验失败时先尝试修，修不好再回喂模型。
 *
 * 为什么需要：模型输出 DSL 的错误高度集中在几类，而且都是机械性的：
 *   1. 数字写成字符串（"1200" 而非 1200）→ 图表 points 解析不出纵坐标
 *   2. 数组长度对不齐（rows 3 列，v 只给 2 个）→ 表格单元格串行
 *   3. 字段名写歪（columns 写成 cols / rows 写成 data / type 写成 kind）
 *   4. 类型名用中文（"数字" 而非 number）
 *   5. 引号用中文全角、尾部多余逗号
 *
 * 这些错误让模型重发一轮，成本是一次完整推理（几百到几千 token）；
 * 本地修一次，成本是几行字符串处理。
 *
 * 两条硬规则：
 *   1. 只修确定能修的——改完必须重新走校验，通过才算愈合成功
 *   2. 修了什么必须说出来——回喂模型时带上 fix 列表，让它下次别再犯
 */
object ZxHeal {

    data class HealResult(
        val ok: Boolean,
        val dsl: String = "",
        val fixes: List<String> = emptyList(),
        val residual: String = ""
    )

    /**
     * 尝试修复。component 为模板名或组件类型都接受。
     * reason 是上一次校验的失败原因，用于定向修复。
     */
    fun attempt(component: String, reason: String, args: Map<String, String?> = emptyMap()): HealResult {
        // 模板场景：从参数重新拼一份干净的 DSL，比修字符串可靠
        val tpl = ZxTemplate.get(component)
        if (tpl != null && args.isNotEmpty()) {
            val rebuilt = rebuildFromTemplate(tpl, args)
            val check = ZxDsl.validate(tpl.component, rebuilt.dsl)
            if (check.ok) return HealResult(true, rebuilt.dsl, rebuilt.fixes)
            return HealResult(false, residual = check.reason)
        }

        val raw = args["dsl"] ?: return HealResult(false, residual = reason)
        var dsl = raw
        val fixes = mutableListOf<String>()

        // ① 全角字符 → 半角
        val full = dsl
            .replace('"', '"').replace('"', '"')
            .replace('，', ',').replace('：', ':')
            .replace('【', '[').replace('】', ']')
        if (full != dsl) { dsl = full; fixes.add("全角标点转半角") }

        // ② 尾部多余逗号
        val noTrail = dsl.replace(Regex(",\\s*([}\\]])"), "$1")
        if (noTrail != dsl) { dsl = noTrail; fixes.add("去掉尾部多余逗号") }

        // ③ 字段名纠偏
        val renamed = dsl
            .replace("\"cols\"", "\"columns\"")
            .replace("\"data\"", "\"rows\"")
            .replace("\"kind\"", "\"type\"")
            .replace("\"values\"", "\"v\"")
            .replace("\"label\"", "\"title\"")
        if (renamed != dsl) { dsl = renamed; fixes.add("字段名纠偏（cols/data/kind/values/label）") }

        // ④ type 缺失时按 component 补
        if (!dsl.contains("\"type\"") && component.startsWith("zx_")) {
            dsl = dsl.trimStart().let {
                if (it.startsWith("{")) "{\"type\":\"$component\"," + it.removePrefix("{")
                else "{\"type\":\"$component\"}"       // 根本不是 JSON，放弃结构化修复
            }
            fixes.add("补上缺失的 type")
        }

        // ⑤ 数字字符串还原（针对 points / v 数组）
        val numeric = dsl.replace(Regex("\"(-?\\d+(?:\\.\\d+)?)\""), "$1")
        if (numeric != dsl) { dsl = numeric; fixes.add("数字还原为数值类型（去掉引号）") }

        // ⑥ 类型名中译英
        val typed = dsl
            .replace("\"数字\"", "\"number\"").replace("\"整数\"", "\"number\"")
            .replace("\"字符串\"", "\"string\"").replace("\"布尔\"", "\"boolean\"")
            .replace("\"是否\"", "\"boolean\"")
        if (typed != dsl) { dsl = typed; fixes.add("类型名中译英") }

        if (fixes.isEmpty()) return HealResult(false, residual = reason)

        // 修完必须重新校验，通过才算成功
        val type = runCatching { JSONObject(dsl).optString("type") }.getOrDefault(component)
        val check = ZxDsl.validate(type.ifBlank { component }, dsl)
        return if (check.ok) HealResult(true, dsl, fixes)
        else HealResult(false, fixes = fixes, residual = check.reason)
    }

    /** 从模板重新拼一份干净 DSL：比修字符串可靠得多 */
    private fun rebuildFromTemplate(
        tpl: ZxTemplate.Template,
        args: Map<String, String?>
    ): HealResult {
        var dsl = tpl.dsl
        val fixes = mutableListOf<String>()
        for (p in tpl.params) {
            val v = args[p]
            if (v.isNullOrBlank()) continue
            // 数值型参数去掉单位与引号，模型最爱犯这个
            var cleaned = v.trim()
            if (cleaned.matches(Regex("^-?\\d+(\\.\\d+)?$"))) {
                dsl = dsl.replace(Regex("\\{\\{\\s*\\$$p\\s*\\}\\}"), cleaned)
                continue
            }
            val stripped = cleaned
                .replace("¥", "").replace("$", "")
                .replace(",", "").replace("元", "").replace("万", "")
                .trim()
            if (stripped != cleaned) {
                fixes.add("参数 $p 去掉单位符号（$cleaned → $stripped）")
                cleaned = stripped
            }
            // 中文数字简单兜底
            cleaned = cn2num(cleaned).also { if (it != cleaned) fixes.add("参数 $p 中文数字转阿拉伯") }
            dsl = dsl.replace(Regex("\\{\\{\\s*\\$$p\\s*\\}\\}"), cleaned)
        }
        // 残留占位符说明参数没给全
        val leftover = Regex("\\{\\{\\s*\\$([A-Za-z0-9_]+)\\s*\\}\\}").findAll(dsl).toList()
        if (leftover.isNotEmpty())
            return HealResult(false, fixes = fixes,
                residual = "仍缺少参数：${leftover.joinToString { it.groupValues[1] }}")
        return HealResult(true, dsl, fixes)
    }

    private val CN = mapOf('零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
    private fun cn2num(s: String): String {
        if (!s.any { it in CN }) return s
        val m = Regex("([零一二三四五六七八九十]+)").find(s)
            ?: return s
        val raw = m.groupValues[1]
        val v = when {
            raw == "十" -> 10
            raw.startsWith("十") -> 10 + (CN[raw.last()] ?: 0)
            raw.contains("十") -> {
                val parts = raw.split('十')
                (CN[parts[0].firstOrNull()] ?: 0) * 10 + (parts.getOrNull(1)?.let { CN[it.firstOrNull()] } ?: 0)
            }
            else -> raw.map { CN[it] ?: 0 }.fold(0) { a, b -> a * 10 + b }
        }
        return s.replace(raw, v.toString())
    }

    /** 自愈过程渲染成终端卡 */
    fun explainAsTerminal(component: String, reason: String, args: Map<String, String?>): String {
        val r = attempt(component, reason, args)
        val lines = org.json.JSONArray()
        lines.put(JSONObject().put("lv", "cmd").put("c", "zx_heal"))
        lines.put(JSONObject().put("lv", "warn").put("c", "原错误：$reason"))
        if (r.ok) {
            r.fixes.forEach { lines.put(JSONObject().put("lv", "ok").put("c", "✓ 已修：$it")) }
            lines.put(JSONObject().put("lv", "out").put("c", r.dsl))
            lines.put(JSONObject().put("lv", "ok").put("c", "愈合成功，无需模型重发"))
        } else {
            r.fixes.forEach { lines.put(JSONObject().put("lv", "out").put("c", "· 已尝试：$it")) }
            lines.put(JSONObject().put("lv", "err").put("c", "仍失败：${r.residual}"))
            lines.put(JSONObject().put("lv", "warn").put("c", "需模型重发，请把上面的 fix 列表带上"))
        }
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_heal").put("exit", if (r.ok) 0 else 1).put("lines", lines).toString()
    }

    /** 统计：哪些错误类型最常发生，供模型侧优化 prompt */
    private val stat = linkedMapOf<String, Int>()
    fun record(kind: String) { stat[kind] = (stat[kind] ?: 0) + 1 }
    fun stats(): String = if (stat.isEmpty()) "暂无自愈记录"
    else stat.entries.sortedByDescending { it.value }.joinToString("\n") { "  ${it.key} ×${it.value}" }
}
