package com.ai.assistance.quro.core.network

/**
 * 推理「原地打转」的**主动掐断器**（纯逻辑，可单测）。
 *
 * ## 它解决的不是「轮次太多」，而是「单轮出不来」
 *
 * Agent 循环里的轮次上限、循环检测都作用在**轮与轮之间**。而实际卡死的形态是：
 * 模型在**这一轮的 reasoning 里**反复自我复述，`reasoning_content` 一帧帧往下流，
 * HTTP 流迟迟不结束 —— 上层拿不到返回、界面一直显示「思考中」，用户看到的就是死循环。
 * 轮次检测对这种形态**永远不会触发**，因为它压根没走到下一轮。
 *
 * 所以这里做的是：在流式接收过程中持续比对，一旦判定「在复读」就**立刻返回 true，
 * 由调用方 break 掉读取循环并关闭连接**——把等待从「几分钟直到 token 耗尽」压缩到几秒。
 *
 * ## 判据（两条，任一命中即断）
 *
 * 1. **复读**：相邻两段（各 [blockSize] 字符）的 trigram Jaccard 相似度 ≥ [similarThreshold]，
 *    连续 [strikeLimit] 次。正常推理会不断引入新概念，相似度天然低。
 * 2. **超长**：累积超过 [maxChars] 仍没有正文。正常推理几千字符就出结论，
 *    超过这个量级基本可以断定它在原地绕。
 *
 * 只做判断，不做 IO，方便单测把阈值钉死。
 */
class ReasoningLoopGuard(
    private val blockSize: Int = 160,
    private val similarThreshold: Double = 0.90,
    private val strikeLimit: Int = 3,
    private val maxChars: Int = 24_000,
) {

    private var lastBlock = ""
    private var lastIndex = -1
    private var strikes = 0
    private var stopped = false
    private var reason: String? = null

    /** 是否已判定为打转（判定后一直为 true）。 */
    val stalled: Boolean get() = stopped

    /** 可读的判定原因，用于日志与诊断，不影响主流程。 */
    val stallReason: String? get() = reason

    /**
     * 每收到一段 reasoning 调用一次。
     *
     * @param acc 到目前为止**累积**的全部 reasoning（不是增量）。
     * @return true 表示应当立即中断本次请求。
     */
    fun onReasoning(acc: CharSequence): Boolean {
        if (stopped) return true
        if (acc.length > maxChars) {
            stopped = true
            reason = "推理已累积 ${acc.length} 字符仍无正文（上限 $maxChars）"
            return true
        }
        if (acc.length < blockSize * 2) return false

        // 只在跨过新的 blockSize 边界时比较一次，避免每帧都算相似度
        val index = acc.length / blockSize
        if (index == lastIndex) return false
        lastIndex = index

        val cur = acc.substring(acc.length - blockSize).toString()
        if (lastBlock.isNotEmpty() && similarity(lastBlock, cur) >= similarThreshold) {
            strikes++
            if (strikes >= strikeLimit) {
                stopped = true
                reason = "连续 $strikeLimit 段推理高度重复（相似度 ≥ $similarThreshold）"
                return true
            }
        } else {
            strikes = 0
        }
        lastBlock = cur
        return false
    }

    companion object {
        /** 给用户的说明（这轮被强行收口时追加）。 */
        const val STALL_NOTE: String =
            "（已自动中断：模型在推理阶段反复重复同一段内容，未能产出结果。请把需求说得更具体一些，或换个说法再试。）"

        /**
         * trigram Jaccard 相似度：字符 3-gram 集合的交并比。
         *
         * 选它而不是编辑距离：中文没有词边界，字符级 3-gram 对「换几个词但说的是同一件事」
         * 足够敏感，对「引入新概念」也足够不敏感，且一次 O(n)。
         */
        fun similarity(a: String, b: String): Double {
            if (a.length < 3 || b.length < 3) return if (a == b) 1.0 else 0.0
            val sa = grams(a)
            val sb = grams(b)
            if (sa.isEmpty() || sb.isEmpty()) return 0.0
            var inter = 0
            for (g in sa) if (g in sb) inter++
            val union = sa.size + sb.size - inter
            return if (union <= 0) 0.0 else inter.toDouble() / union
        }

        private fun grams(s: String): Set<String> {
            val out = HashSet<String>(s.length)
            for (i in 0..s.length - 3) out.add(s.substring(i, i + 3))
            return out
        }
    }
}
