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
        5. recency 字段按问题需要填写：day / week / month / any。
        输出格式：{"queries":["查询1","查询2"],"recency":"week"}
    """.trimIndent()

    data class Rewrite(val queries: List<String>, val recency: String)

    suspend fun rewrite(
        completer: LlmCompleter?,
        question: String,
        nowIso: String
    ): Rewrite {
        val fallback = Rewrite(listOf(ruleClean(question)), "any")
        if (completer == null) return fallback

        val user = "当前时间：$nowIso\n用户问题：$question"
        val raw = runCatching { completer.complete(SYSTEM, user, 200) }.getOrNull()
            ?: return fallback

        return parse(raw) ?: fallback
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
