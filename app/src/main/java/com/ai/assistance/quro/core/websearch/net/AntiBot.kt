package com.ai.assistance.quro.core.websearch.net

/**
 * AntiBot —— 反爬/验证码页识别。
 *
 * 为什么必须显式识别：
 * 验证码页的 HTTP 状态码是 200，不识别的话会被当成"正常返回"，
 * 接着解析出 0 条，最终报"未获得可用结果"——排查时会误判成解析器问题或网络问题，
 * 实际却是被反爬拦截。显式识别后能给出准确结论，避免走错排查方向。
 *
 * 典型场景：百度返回"百度安全验证"页，状态码 200，但无任何真实结果。
 */
object AntiBot {

    /** 中英文常见拦截页特征 */
    private val SIGNALS = listOf(
        "安全验证", "请输入验证码", "验证中心", "人机验证", "访问验证",
        "检测到异常", "异常流量", "请完成安全验证",
        "captcha", "verify you are human", "are you a robot", "unusual traffic",
        "not a robot", "checking your browser", "please enable javascript",
        "access denied", "request blocked", "too many requests"
    )

    /** 外链统计正则：只统计指向外部站点的 a 标签 */
    private val EXT_LINK = Regex("""<a\s[^>]*href=["']https?://([^/"']+)""")

    /**
     * @param body 响应体，可为空
     * @return true 表示这是拦截页，不应作为有效结果使用
     *
     * 判定逻辑刻意采用"信号 + 外链数"双重条件：
     * 只看关键词会误伤——正常长页面（比如技术博客）完全可能提到 captcha 这个词；
     * 只看长度也不可靠——验证码页也可能很大（实测百度验证页可达数 MB）。
     * 但"命中拦截关键词"且"几乎没有外链"同时成立，基本可以确信是拦截页。
     */
    fun isBlocked(body: String?): Boolean {
        if (body.isNullOrBlank()) return true
        val lower = body.lowercase()

        val hit = SIGNALS.firstOrNull { lower.contains(it) } ?: return false

        // 命中关键词，但页面外链充足 → 是正常页面恰好提到该词，放行
        val links = countExternalLinks(lower, body)
        if (links >= 10) return false

        return true
    }

    /** 给出可读的判定原因，便于日志与自检报告 */
    fun reason(body: String?): String? {
        if (body.isNullOrBlank()) return "响应为空"
        val lower = body.lowercase()
        val hit = SIGNALS.firstOrNull { lower.contains(it) }
        if (hit != null && countExternalLinks(lower, body) < 10) {
            return "疑似反爬拦截页（命中特征：$hit，外链仅 ${countExternalLinks(lower, body)} 个）"
        }
        return null
    }

    /** 统计指向不同外部域名的链接数（去重后） */
    private fun countExternalLinks(lower: String, raw: String): Int {
        val domains = HashSet<String>()
        for (m in EXT_LINK.findAll(raw)) {
            val d = m.groupValues[1].removePrefix("www.")
            if (d.isNotBlank()) domains.add(d)
        }
        return domains.size
    }
}
