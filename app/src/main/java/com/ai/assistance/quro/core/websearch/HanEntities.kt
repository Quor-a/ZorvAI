package com.ai.assistance.quro.core.websearch

/**
 * HanEntities —— 端侧中文 / 中英混排专有名词保护。
 *
 * 背景：原 [com.ai.assistance.quro.core.websearch.rank.ResultReranker.tokenize] 把中文按 2-gram 滑动切分，
 * 导致「郑钦文」被切成 ["郑钦","钦文"]、"C罗" 因含拉丁字母被完全漏掉、"iPhone 17 Pro" 被拆成三个孤立 token，
 * 相关度打分严重失真（任何含"郑钦"或"钦文"的页面都会被误判相关）。
 *
 * 本类做法：
 * 1. [detect] 用「词典 + 混排/产品型号正则」识别专有名词（人名、地名、品牌、型号等）；
 * 2. [protectTokens] 把命中的实体作为整体 token 保留（不再 2-gram），剩余文本仍走 2-gram 保证召回不退化；
 * 3. [recoverFragmented] 还原被空格拆碎的实体（如 "郑 钦 文" → "郑钦文"），供 QueryRewriter 兜底。
 *
 * 未命中词典/正则的普通中文继续 2-gram，因此行为向后兼容、召回不退化。
 */
object HanEntities {

    /** 常见中文专有名词词典（人名/地名/品牌/机构/赛事等），按需扩充 */
    val DICT: Set<String> = setOf(
        // 体育人物
        "郑钦文", "C罗", "梅西", "内马尔", "姆巴佩", "勒布朗詹姆斯", "库里", "姚明", "刘翔", "苏炳添", "孙颖莎", "樊振东",
        // 科技人物 / 品牌创始人
        "任正非", "马云", "马化腾", "雷军", "李彦宏", "张一鸣", "黄仁勋", "马斯克", "库克", "扎克伯格",
        // 地名
        "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "武汉", "西安", "南京", "苏州", "天津",
        "中国", "美国", "日本", "韩国", "俄罗斯", "德国", "法国", "英国", "印度", "巴西",
        // 品牌 / 产品母名
        "苹果", "华为", "小米", "三星", "OPPO", "vivo", "荣耀", "联想", "比亚迪", "特斯拉", "英伟达", "英特尔",
        "微信", "抖音", "快手", "淘宝", "京东", "拼多多", "B站", "网易", "百度",
        // 赛事 / 作品
        "奥运会", "世界杯", "欧冠", "温网", "法网", "美网", "澳网", "亚运会"
    )

    /** 混排 / 产品型号正则：拉丁字母 + 数字 + 可选后缀（保留原词大小写/空格） */
    private val MODEL = Regex(
        """(iPhone\s?\d+(?:\s?(?:Pro|Pro\s?Max|Plus|Mini|SE|Ultra))?""" +
            """|iPad(?:\s?(?:Pro|Air|mini|Mini))?""" +
            """|MacBook(?:\s?(?:Pro|Air))?""" +
            """|Apple\s?Watch""" +
            """|Galaxy\s?\w+""" +
            """|Mate\s?\d+""" +
            """|小米\s?\d+(?:\s?(?:Pro|Ultra|Pro\s?Max))?""" +
            """|Redmi\s?\w+""" +
            """|OPPO\s?\w+|vivo\s?\w+|realme\s?\w+""" +
            """|特斯拉\s?Model\s?[YS3]|Model\s?[YS3]""" +
            """|C罗|梅西|姆巴佩)""",
        RegexOption.IGNORE_CASE
    )

    private val EN = Regex("""[a-zA-Z0-9]{2,}""")
    private val HAN = Regex("""[一-鿿]+""")

    private val STOP = setOf(
        "的", "了", "吗", "呢", "是", "在", "有", "和", "与", "怎么", "如何", "什么", "为什么",
        "the", "a", "an", "is", "are", "of", "to", "in", "for", "how", "what", "why", "when"
    )

    /** 检测文本中的全部实体（词典 + 型号正则），返回原文片段（保持原样） */
    fun detect(text: String): List<String> {
        val out = LinkedHashSet<String>()
        // 1. 型号 / 混排正则（区分大小写，保留原词）
        MODEL.findAll(text).forEach { out.add(it.value.trim()) }
        // 2. 词典匹配（contains 即可，罕见子串误命中在相关度打分中影响有限）
        for (w in DICT) {
            if (w.length >= 2 && text.contains(w)) out.add(w)
        }
        return out.toList()
    }

    /**
     * 生成保护式 token 列表：
     * - 命中实体的整词作为单一 token（小写化以便匹配）；
     * - 实体之外的剩余文本仍按原逻辑 2-gram（中文）与英文词切分，保证召回不退化。
     */
    fun protectTokens(query: String): List<String> {
        val entities = detect(query)
        if (entities.isEmpty()) return residualTokenize(query)

        // 从长到短替换实体，避免短实体先匹配导致长实体被切断
        val sorted = entities.sortedByDescending { it.length }
        var work = query
        for (e in sorted) work = work.replace(e, "\u0000")

        val out = ArrayList<String>()
        for (e in sorted) out.add(e.lowercase())
        out.addAll(residualTokenize(work))
        return out.distinct().filter { it.isNotBlank() && it != "\u0000" }
    }

    /** 原 tokenize 兜底（无实体时直接用，保证行为一致） */
    fun fallbackTokenize(q: String): List<String> =
        residualTokenize(q).filter { it !in STOP }

    /** 还原被空格拆碎的实体：原问若被切成 "郑 钦 文" 这类，合并为无空格整体 */
    fun recoverFragmented(text: String): String {
        var s = text
        for (w in DICT) {
            if (w.length < 2) continue
            val spaced = w.map { Regex.escape(it.toString()) }.joinToString("\\s*")
            s = s.replace(Regex(spaced), w)
        }
        return s
    }

    /** 残文切分：英文词 + 中文 2-gram（与历史 tokenize 行为一致） */
    private fun residualTokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val en = EN.findAll(s.lowercase()).map { it.value }.toList()
        out.addAll(en.filter { it !in STOP })
        val cn = HAN.findAll(s).map { it.value }.toList()
        for (seg in cn) {
            if (seg.length <= 2) out.add(seg)
            else for (i in 0..seg.length - 2) out.add(seg.substring(i, i + 2))
        }
        return out.distinct().filter { it !in STOP }
    }
}
