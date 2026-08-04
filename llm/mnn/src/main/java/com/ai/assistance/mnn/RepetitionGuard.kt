package com.ai.assistance.mnn

/**
 * 流式输出层的「复读退化」兜底检测器。
 *
 * ## 为什么需要它
 * MNN 的自回归循环（`ArGeneration::generate`，generate.cpp:50-105）只有两个退出条件：
 * 采样到 EOS（`Llm::is_stop`）或跑满 `max_new_tokens`。循环内部**没有任何 n-gram /
 * 重复检测兜底**。一旦模型进入退化态（持续吐同一短语且不吐 EOS），就会一路复读到
 * maxTokens 才停，用户侧表现为「主人～ 主人～ 主人～ ……」刷屏。
 *
 * 采样参数（repetition_penalty）是治本手段，本类是**治标的第二道防线**：即使采样层
 * 因为模型配置异常再次失效，也能在流式输出阶段及时截断，把损失限制在几十个字符内。
 *
 * ## 检测算法
 * 对累计输出的**尾部**做「最小周期」扫描：对每个候选短语长度 `p`（从小到大），取末尾
 * `p` 个字符作为重复单元，向前统计它连续出现了多少次。命中条件是**两个门槛同时满足**：
 *
 * 1. `repeats >= minRepeats` —— 重复次数够多；
 * 2. `repeats * p >= minSpan` —— 重复覆盖的字符跨度够长。
 *
 * 第 2 条是避免误伤的关键：单字符重复（如省略号「…………」）必须堆到 [Config.minSpanChars]
 * 个字符才算退化，而正常中文里「哈哈哈」「。。。」这种短重复远达不到阈值。
 *
 * ## 单元三分类（假阳性防线）
 * 只按「是否空白」排除是不够的。真实退化的重复单元是**语义内容**（「主人～」「啊」），
 * 而 Markdown 表格分隔行（`| --- | --- |`）、ASCII 横线（`====…`）、用户显式要求的
 * 重复载荷（32 位全 0）都是**排版 / 符号内容**，它们在正常回复里出现得极其频繁。
 * 因此 [detect] 把候选单元分成三类，走三套不同策略：
 *
 * | 分类 | 判据 | 策略 |
 * |---|---|---|
 * | 纯空白 | [String.isBlank] | 直接跳过（代码缩进 / 排版空行） |
 * | 语义型 | `Character.isLetter` 字符占比 >= [Config.semanticLetterPercent] | 常规门槛 [Config.minSpanChars] / [Config.repeatThreshold] |
 * | 排版型 | 其余（纯符号 / 纯标点 / 纯数字 / 符号占主体） | 严格门槛 [Config.symbolMinSpanChars] / [Config.symbolMinRepeats] |
 *
 * 「占比」而不是「是否含至少一个字母」，是因为 Markdown 表头 `| 列 | 列 | 列 |` 的最小
 * 周期是 `" 列 |"`——它含 1 个 CJK 字母，但字母只占 25%，本质仍是排版结构；而真实退化
 * 单元「主人～」占 67%、「主人～ 」占 50%、「啊」占 100%。50% 这条线把两者干净分开。
 *
 * ## 有意为之的偏向
 * 排版型单元的门槛（200 字符跨度 + 32 次重复）刻意定得很高，**宁可漏检也不误伤**：
 * 误伤会把正常表格 / 代码块的生成直接掐断（用户可见的功能损坏），而漏检只是退回到采样层
 * `repetition_penalty` 兜底（治本手段本来就在那一层）。
 *
 * 本类**非线程安全**，每次生成调用应新建一个实例。
 */
class RepetitionGuard(private val config: Config = Config()) {

    /**
     * 兜底检测配置。全部可调；把 [enabled] 设为 false 即可完全关闭本防线。
     *
     * @param enabled 是否启用检测。
     * @param minPhraseChars 参与扫描的最小重复单元长度（字符）。
     * @param maxPhraseChars 参与扫描的最大重复单元长度（字符）。超过此长度的复读不检测。
     *   取 64 是为了覆盖「整句复读」——一句中文陈述句常在 35~60 字，32 会整段漏检。
     * @param shortPhraseChars 「短语过短」的判定边界，`p <= shortPhraseChars` 时用更严格的
     *   [shortRepeatThreshold]，避免误伤正常的短重复。
     * @param repeatThreshold 长短语（`p > shortPhraseChars`）的最小连续重复次数。
     * @param shortRepeatThreshold 短短语（`p <= shortPhraseChars`）的最小连续重复次数。
     * @param minSpanChars 语义型单元判定退化所需的最小重复跨度（`repeats * p`）。防误伤主门槛。
     * @param semanticLetterPercent 单元中 `Character.isLetter` 字符占比达到该百分比才算
     *   「语义型单元」，走常规门槛；否则视为「排版型单元」，走 [symbolMinSpanChars] /
     *   [symbolMinRepeats] 严格门槛。取 0 等价于全部按语义处理（回到旧行为）。
     * @param symbolMinSpanChars 排版型单元的最小重复跨度，是排版型判定的**主约束**。200 的
     *   取值依据：12 列 Markdown 分隔行约 72 字符、40 个 `=` 的横线 40 字符、32 位二进制串
     *   32 字符，均安全；而真正的符号刷屏退化能轻松突破 200。
     * @param symbolMinRepeats 排版型单元最小连续重复次数的**上限封顶值**。实际门槛取
     *   `min(symbolMinRepeats, ceilDiv(symbolMinSpanChars, p))`，见 [symbolMinRepeatsFor]。
     *   之所以要封顶而不是直接取常数 32：常数 32 对长单元过严——40 字符的整行表格分隔行
     *   需要 32×40 = 1280 字符才命中，已超出 [scanWindowChars]，等于 `p` 在 33..64 区间
     *   完全检测不到（「整行分隔行反复复读」在小模型上并不罕见）。封顶后 p=40 只需 5 次
     *   重复（200 字符）即可命中，死区消除，而短单元（p<=6）的门槛不受影响。
     * @param scanWindowChars 只扫描累计文本的最后这么多字符，控制长回复下的开销。
     */
    data class Config(
        val enabled: Boolean = true,
        val minPhraseChars: Int = 1,
        val maxPhraseChars: Int = 64,
        val shortPhraseChars: Int = 3,
        val repeatThreshold: Int = 4,
        val shortRepeatThreshold: Int = 8,
        val minSpanChars: Int = 24,
        val semanticLetterPercent: Int = 50,
        val symbolMinSpanChars: Int = 200,
        val symbolMinRepeats: Int = 32,
        val scanWindowChars: Int = 1024,
    )

    /**
     * 一次退化命中的详情。
     *
     * @param phrase 被判定为复读的重复单元。
     * @param repeats 该单元在尾部连续出现的次数。
     * @param totalChars 命中时模型已累计输出的字符数。
     */
    data class Detection(
        val phrase: String,
        val repeats: Int,
        val totalChars: Int,
    )

    private val buffer = StringBuilder()

    /** 命中的退化详情；未命中为 null。 */
    var detection: Detection? = null
        private set

    /** 是否已经判定为复读退化。 */
    val tripped: Boolean
        get() = detection != null

    /** 当前累计的输出文本。 */
    val text: String
        get() = buffer.toString()

    /**
     * 吞入一段流式增量文本。
     *
     * @param delta 本次回调收到的增量字符串。
     * @return true 表示可以继续生成；false 表示已判定复读退化，调用方应中断生成。
     */
    fun accept(delta: String): Boolean {
        if (!config.enabled) return true
        if (tripped) return false
        if (delta.isNotEmpty()) buffer.append(delta)

        val hit = detect() ?: return true
        detection = hit
        return false
    }

    /**
     * 扫描尾部，返回命中的重复单元；未命中返回 null。
     */
    private fun detect(): Detection? {
        val total = buffer.length
        // 任何一类单元都不可能在低于最小跨度时命中，先做一次廉价短路。
        val cheapestSpan = minOf(config.minSpanChars, config.symbolMinSpanChars)
        if (total < cheapestSpan) return null

        // 只看尾部窗口，长回复下开销恒定。
        val windowStart = maxOf(0, total - config.scanWindowChars)
        val tail = buffer.substring(windowStart)
        val tailLen = tail.length

        val minP = maxOf(1, config.minPhraseChars)
        // 用最宽松的重复次数门槛（语义型长单元）算上界，具体单元再各自收紧。
        val maxP = minOf(config.maxPhraseChars, tailLen / maxOf(1, minRepeatsFor(config.maxPhraseChars)))

        // 从最短周期开始扫，保证「主人～主人～」命中 p=3 而不是 p=6。
        for (p in minP..maxOf(minP, maxP)) {
            if (p > config.maxPhraseChars) break

            val unit = tail.substring(tailLen - p)
            // ① 纯空白单元跳过：代码缩进 / 排版空行不应被判定为退化。
            if (unit.isBlank()) continue

            // ② / ③ 语义型走常规门槛，排版型（纯符号 / 纯标点 / 纯数字 / 符号占主体）走严格门槛。
            val semantic = isSemanticUnit(unit)
            val minRepeats = if (semantic) minRepeatsFor(p) else symbolMinRepeatsFor(p)
            val minSpan = if (semantic) config.minSpanChars else config.symbolMinSpanChars
            if (tailLen < p * minRepeats) continue

            var repeats = 1
            var idx = tailLen - p
            while (idx - p >= 0 && tail.regionMatches(idx - p, unit, 0, p)) {
                repeats++
                idx -= p
            }

            if (repeats >= minRepeats && repeats * p >= minSpan) {
                return Detection(phrase = unit, repeats = repeats, totalChars = total)
            }
        }
        return null
    }

    /**
     * 判断重复单元是否为「语义型」（字母占比达标）。
     *
     * `Character.isLetter` 覆盖 CJK 汉字、假名、谚文与拉丁字母，但**不包含**数字、标点、
     * 数学符号（含全角波浪号 `～`）、制表符号与 emoji——正好把「内容」与「排版」分开。
     *
     * @param unit 候选重复单元，长度必定 >= 1。
     * @return true 表示语义型单元；false 表示排版型单元。
     */
    private fun isSemanticUnit(unit: String): Boolean {
        var letters = 0
        for (c in unit) {
            if (Character.isLetter(c)) letters++
        }
        // 用整数乘法比较，避免浮点误差在 50% 边界上抖动（「主人～ 」恰好 2/4）。
        return letters * 100 >= unit.length * config.semanticLetterPercent
    }

    /** 按重复单元长度选择语义型单元的重复次数门槛。 */
    private fun minRepeatsFor(phraseChars: Int): Int =
        if (phraseChars <= config.shortPhraseChars) config.shortRepeatThreshold else config.repeatThreshold

    /**
     * 排版型单元的重复次数门槛：`min(symbolMinRepeats, 达到 symbolMinSpanChars 所需的重复次数)`。
     *
     * 让 [Config.symbolMinSpanChars] 始终当主约束，[Config.symbolMinRepeats] 只做封顶，
     * 避免长单元被「常数 32 次」这条与跨度脱钩的门槛卡死（详见 [Config.symbolMinRepeats]）。
     * 各档位实际门槛（默认 200 / 32）：
     *
     * | p | ceilDiv(200, p) | 实际门槛 | 命中所需跨度 |
     * |---|---|---|---|
     * | 1  | 200 | 32 → 但 span 仍要求 200 次 | 200 |
     * | 6  | 34  | 32 → 但 span 仍要求 34 次  | 204 |
     * | 40 | 5   | 5                          | 200 |
     * | 64 | 4   | 4                          | 256 |
     *
     * @param phraseChars 候选重复单元长度，必定 >= 1。
     * @return 该长度下排版型单元需要的最小连续重复次数，恒 >= 1。
     */
    private fun symbolMinRepeatsFor(phraseChars: Int): Int {
        val p = maxOf(1, phraseChars)
        // 整数上取整，等价于 ceil(symbolMinSpanChars.toDouble() / p) 但无浮点误差。
        val spanRepeats = (config.symbolMinSpanChars + p - 1) / p
        return maxOf(1, minOf(config.symbolMinRepeats, spanRepeats))
    }

    companion object {
        /** 退化文本被裁剪后保留的重复单元次数（保留少量重复，避免把正常语义切没）。 */
        private const val KEEP_REPEATS = 2

        /**
         * 裁掉退化文本尾部的多余重复，只保留 [KEEP_REPEATS] 次。
         *
         * 未命中（[detection] 为 null）时原样返回，保证正常回复零影响。
         *
         * @param text 模型累计输出的完整文本。
         * @param detection [accept] 命中的退化详情。
         * @return 裁剪后的文本；若裁剪结果为空白则退回原文，避免产出空回复。
         */
        @JvmStatic
        fun trimDegenerateTail(text: String, detection: Detection?): String {
            if (detection == null) return text
            val phrase = detection.phrase
            if (phrase.isEmpty() || detection.repeats <= KEEP_REPEATS) return text

            val dropCount = detection.repeats - KEEP_REPEATS
            val dropChars = dropCount * phrase.length
            if (dropChars <= 0 || dropChars >= text.length) return text

            // 复读一定位于尾部，直接从末尾切掉多余的那几段。
            val cut = text.substring(0, text.length - dropChars)
            return if (cut.isBlank()) text else cut.trimEnd()
        }
    }
}
