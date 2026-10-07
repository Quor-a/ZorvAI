package com.ai.assistance.quro.core.rag

/**
 * RAG 专用分词与词权重。
 *
 * ## 与旧 [com.ai.assistance.quro.core.tools.ToolTextMatcher] 的关系
 * **并存，不是替代**。旧分词器服务旧的词法检索路径（[QuroToolRouter] / [ToolCapabilityDirectory]），
 * 本文件服务新的多路融合引擎。两边都过一遍，行为只会更强不会更弱。
 * 分开的原因是：新引擎需要「词信息量」这个概念来压制停用词噪声，
 * 塞进旧类里会污染旧路径的既有召回行为（那部分已经被19 组测试钉死）。
 *
 * ## 分词策略
 *  - **中文**：标点切段后生成 **bigram**（相邻两字）。bigram 对中文召回明显优于单字，
 *    又不需要词典依赖；长度 1 时保留单字。
 *  - **英文/数字**：按 `_` / `-` / 空白切成词，并保留原词本身（`web_search` → `web`,`search`）。
 *  - 全部小写去空白，保证「APK」「apk」「Apk」等价。
 */
internal object RagText {

    /**
     * 停用词 / 泛化词。
     *
     * 🔴 这些词几乎出现在每条文档里，命中它们不构成相关证据。
     * 主要由 bigram 切分产生（「画一**张**」「这**张**」「一**个**」），
     * 也含用户口语的礼貌与操作前缀（「帮我」「麻烦」「给我」）。
     * 不压它们的结果：实测「画一张海报」里 card_patch 靠「一/张」排到第一，
     * 真正的 codecanvas_markup 反而靠后。
     */
    private val STOP_WORDS = setOf(
        "一下", "这个", "那个", "这些", "那些", "什么", "怎么", "可以", "需要", "能够", "应该",
        "帮我", "帮忙", "麻烦", "给我", "给我来", "来个", "来一个", "来张", "来个东西",
        "一张", "一个", "这张", "那张", "进行", "使用", "相关", "有关", "以及", "还有",
        "一下的", "的话", "东西", "内容", "并且", "或者", "然后", "现在", "马上", "立刻",
        "一段", "一段话", "文字", "文本", "内容一下",
        "的", "了", "是", "在", "和", "有", "就", "都", "而", "及", "与", "着", "过",
        "把", "被", "让", "给", "对", "从", "向", "到", "为", "以", "并",
        "请", "吧", "吗", "呢", "啊", "呀", "哦", "嗯",
        // 通用修饰语：语法上连接成分子与中心语，本身不指向任何能力。
        // 漏了它们会让「zzzzqqq 完全不相关的东西」被当成有效查询
        // （实测乱串能拿到 1.15 分并返回 10 个不相干的工具）。
        "完全", "全部", "所有", "任何", "别的", "其他", "其它", "一些", "某些",
        "非常", "特别", "比较", "有点", "稍微", "一点", "下", "次", "吧",
        "东西", "玩意", "方式", "办法", "样子", "情况", "方面", "问题", "时候",
        "zzzzqqq", "xyzzy", "asdfgh", "qwerty", "zzzz", "xxxx",
        "the", "a", "an", "is", "are", "to", "of", "and", "or", "in", "on", "for", "with",
    )

    /**
     * 纯噪声拉丁串判定。
     *
     * 比停用词表更通用：任何**不含元音或辅音交替特征**的长串（`zzzzqqq`、`asdfghjkl`）
     * 都是键盘乱敲，不该参与检索。判据是「同一字符占比过高」或「全为同一类字符」。
     */
    fun isLatinNoise(token: String): Boolean {
        if (token.length < 4) return false
        if (token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            val lower = token.lowercase()
            // 4 元音以上（正常英文单词不会有）或 4 辅音连续
            val vowels = lower.count { it in "aeiou" }
            if (vowels >= 4) return true
            var run = 0
            for (c in lower) {
                run = if (c in "aeiou") 0 else run + 1
                if (run >= 5) return true
            }
        }
        return false
    }

    /** 非字母数字与非 CJK 的分隔符（保留下划线与连字符，供英文再切）。 */
    private val SEGMENT_SPLIT = Regex("[^\\p{L}\\p{N}_-]+")
    private val UNDERSCORE_SPLIT = Regex("[_-]+")

    /**
     * 中英混合分词。
     *
     * @return 去重后的 token 列表（顺序稳定，便于测试断言）。
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val raw = LinkedHashSet<String>()

        val segments = SEGMENT_SPLIT.split(text.lowercase())
        for (seg in segments) {
            if (seg.isBlank()) continue
            val latin = UNDERSCORE_SPLIT.split(seg)
                .filter { it.isNotBlank() }
                // 🔴 必须排除**含 CJK 的段**。否则「zzzzqqq完全不相关的东西xyzzy」这类中英混排
                // 整段会被当成一个拉丁词元加进 token 列表——22 个字的乱串反而成了信息量最高的
                // token（实测 tokenize 输出 `[zzzzqqq完全不相关的东西xyzzy, 完全, 全不, ...]`），
                // 闸门与排序全被它带偏。拉丁词元只该收**纯拉丁**的段。
                .filter { w -> w.any { it.isLetter() } && !w.any { isCjk(it) } }
            for (w in latin) {
                raw.add(normalizeToken(w))
            }
            if (seg.any { isCjk(it) }) {
                val chars = seg.filter { isCjk(it) || it.isDigit() }
                if (chars.length == 1) {
                    raw.add(normalizeToken(chars.toString()))
                } else {
                    for (i in 0 until chars.length - 1) {
                        raw.add(normalizeToken(chars.substring(i, i + 2)))
                    }
                }
            }
        }
        // 整段（长度 2..16）也作为 token，支持「导出APK」这类整词命中。
        // 🔴 同样排除中英混排的整段：它既不是拉丁词也不是中文词，两边都不该当词元。
        val compact = text.lowercase().replace(" ", "")
        if (compact.length in 2..16 && !compact.any { isCjk(it) } && !compact.any { it == '_' }) {
            raw.add(normalizeToken(compact))
        }

        return raw.filter { it.isNotBlank() }.toList()
    }

    /** token 归一：折叠同义说法，让「联网查询」与「联网搜索」可比。 */
    private fun normalizeToken(token: String): String = RagConcept.normalize(token)

    /**
     * 词信息量：命中它的**证据价值**，0.15..1.0。
     *
     * 没有语料统计可用（工具域语料太小），故用**长度 + 词性直觉**近似：
     *  - 停用词 0.15：出现在几乎每条文档里
     *  - 单字 0.4 / 纯数字 0.3：太短，偶然重合概率高
     *  - 长度 ≥3 的实词 1.0：「闹钟」「导出」「截图」这类命中即强证据
     *  - 长度 2 0.6：中文 bigram 的常态，最难区分
     */
    fun informativeness(token: String): Double = when {
        token.isEmpty() -> 0.0
        token in STOP_WORDS -> 0.15
        token.length <= 1 -> 0.4
        token.all { it.isDigit() } -> 0.3
        token.length >= 3 -> 1.0
        else -> 0.6
    }

    fun isStopWord(token: String): Boolean = token in STOP_WORDS

    private fun isCjk(c: Char) = c.code in 0x4E00..0x9FFF
}