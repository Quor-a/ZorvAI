package com.ai.assistance.quro.core.websearch

import org.json.JSONObject

/**
 * 宿主 LLM 的极简抽象。
 *
 * 查询改写需要模型参与，但联网模块不应依赖任何具体模型实现：
 * 本地 MNN/Llama 推理、云端 OpenAI 兼容接口，都只需实现这一个方法即可注入。
 * 未注入时（completer = null）自动退化为规则式改写，联网能力依然可用。
 */
interface LlmCompleter {
    /**
     * @param system 系统提示
     * @param user 用户提示
     * @param maxTokens 输出上限，查询改写通常 200 足够
     * @return 模型输出文本；失败返回 null
     */
    suspend fun complete(system: String, user: String, maxTokens: Int = 200): String?
}

/**
 * QueryRewriter —— 把用户的自然语言问题，改写成搜索引擎能懂的查询词。
 *
 * 这是"AI 联网"最容易被低估的一环。用户问的是"现在买 XX 划算吗"，
 * 直接拿这句话去搜，返回的全是软文；而模型改写后的"XX 当前价格 2026 年行情"才能命中有用结果。
 * 浏览器方案里根本没有这一步——它只会把用户原话丢进搜索框。
 */
object QueryRewriter {

    private val SYSTEM = """
        你是搜索查询改写器。把用户的问题转换成搜索引擎可用的查询词。
        规则：
        1. 只输出 JSON，不要任何解释文字，不要 markdown 代码块；
        2. 生成 1-3 个查询词，互相之间角度不同（如：一个查事实、一个查评测、一个查最新动态）；
        3. 查询词要短、关键词化，去掉口语词和礼貌用语；
        4. 保留专有名词、型号、数字等不可替换的信息；中文人名/地名/专有名词必须保持连续完整，禁止在字间插入空格或拆成单字（如"郑钦文"必须整体输出，不得写成"郑 钦文"或"郑"）；
        5. 必须保留用户问题的全部核心语义（地点、主体、动作、时间），禁止只抽取其中一个修饰词（如"周末"）而丢弃主体（如"杭州/旅游/景点"）；每个查询词也要尽量覆盖核心词，不要只输出单一字面词（如"latest"）；
        6. recency 字段按问题需要填写：day / week / month / any。
        输出格式：{"queries":["查询1","查询2"],"recency":"week"}
    """.trimIndent()

    data class Rewrite(val queries: List<String>, val recency: String)

    suspend fun rewrite(
        completer: LlmCompleter?,
        question: String,
        nowIso: String
    ): Rewrite {
        // 还原可能被空格拆碎的实体（如 "郑 钦 文" → "郑钦文"），再进入改写流程
        val recovered = HanEntities.recoverFragmented(question)
        // 意图保真查询：永远基于用户原问抽取核心词（实体 + 显著英文词 + 中文词），
        // 不依赖模型质量——即使模型改写跑偏，送进引擎的查询仍锚定真实意图。
        val intentQ = buildIntentQuery(recovered)
        if (completer == null) return Rewrite(listOf(intentQ), "any")

        val user = "当前时间：$nowIso\n用户问题：$recovered"
        val raw = runCatching { completer.complete(SYSTEM, user, 200) }.getOrNull()
            ?: return Rewrite(listOf(intentQ), "any")

        val parsed = parse(raw) ?: return Rewrite(listOf(intentQ), "any")
        // 意图查询置于首位：保证不被 maxQueries 截断丢弃；模型查询提供角度多样性
        val queries = (listOf(intentQ) + parsed.queries)
            .map { it.trim() }
            .filter { it.length in 2..80 }
            .distinct()
            .take(3)
        return Rewrite(queries, parsed.recency)
    }

    /**
     * 意图保真查询：把用户原问转成"实体 + 显著词"的关键词串，整体不被 2-gram 拆碎。
     * 例："周末去杭州旅游推荐景点美食" → "杭州 周末 旅游 推荐 景点 美食"
     *     "latest breakthrough in AI 2026" → "latest breakthrough ai 2026"
     * 这是对抗"改写跑偏"的兜底：无论模型怎么改，这条查询永远锚定用户真实意图。
     */
    private fun buildIntentQuery(q: String): String {
        return HanEntities.protectTokens(q)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(80)
            .ifBlank { q.take(60) }
    }

    /** 解析模型输出，容错对待代码块包裹、多余文字等情况 */
    internal fun parse(raw: String): Rewrite? {
        val s = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val o = JSONObject(s.substring(start, end + 1))
            val arr = o.optJSONArray("queries") ?: return null
            val list = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val rawQ = arr.optString(i).trim()
                if (rawQ.length !in 2..80) continue
                // 中文连续汉字之间不允许插空格：防止"郑 钦文"被拆成单字去查字典/分词（web_search 中文人名分词缺陷修复）
                val q = rawQ.replace(Regex("""(?<=\p{IsHan})\s+(?=\p{IsHan})"""), "")
                if (q.length in 2..80) list.add(q)
            }
            if (list.isEmpty()) return null
            val rec = o.optString("recency", "any").lowercase()
            Rewrite(list.take(3), if (rec in setOf("day", "week", "month")) rec else "any")
        }.getOrNull()
    }

    /** 规则式兜底改写：去口语词、截断长度 */
    fun ruleClean(q: String): String {
        val noise = listOf("请问", "帮我", "我想知道", "能不能", "可以吗", "谢谢", "麻烦", "一下")
        var s = q.trim()
        noise.forEach { s = s.replace(it, "") }
        s = s.replace(Regex("""[？?。.!！,，、\s]+"""), " ")
        return s.trim().take(60).ifBlank { q.take(60) }
    }
}
