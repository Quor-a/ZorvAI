package com.ai.assistance.quro.core.agent.loop

/**
 * 工具调用「原地打转」检测（纯逻辑，无 Android 依赖，可单测）。
 *
 * ## 为什么需要
 *
 * Agent 循环如果只靠「轮次上限」兜底，模型反复发起同一批调用时会一路跑到上限：
 * 云端默认 100 轮 × 每轮一次完整 LLM 请求，用户侧表现就是**界面一直转、几分钟不出结果**
 * —— 这就是「死循环思考」，不是工具慢。
 *
 * 真正的循环特征是**签名重复**（同样的工具 + 同样的参数），而非「轮次多」：
 * 合法的长任务每步都是新调用（读新文件、跑新命令），签名不重复，不能误杀。
 *
 * ## 三档处置
 *
 * - [Decision.Nudge]：首次发现重复 → 注入一次「请基于已有结果收尾」，给模型出路
 * - [Decision.Stop]：重复持续到 [repeatLimit] 次，或总轮次触顶 → 强制结束，不再烧 token
 * - [Decision.Continue]：正常
 *
 * 与 [ClosedLoopExecutor]（单工具级失败重试）互补：那个管「一个工具失败了怎么办」，
 * 本类管「整轮循环停不下来怎么办」。
 */
class LoopGuard(
    /** 滑动窗口大小：只在「近期做过的调用」里判重复，避免很早以前的一次相同调用导致误判。 */
    private val window: Int = 12,
    /** 连续重复多少次判死循环。 */
    private val repeatLimit: Int = 3,
    /** 轮次硬上限（不管有没有重复，到这就停）。 */
    private val hardStopAt: Int = 40,
) {

    enum class Decision { Continue, Nudge, Stop }

    private val recent = ArrayDeque<String>()
    private var streak = 0
    private var nudged = false
    private var rounds = 0

    /** 已进行的轮数（含无工具调用的纯文本轮）。 */
    val round: Int get() = rounds

    /** 已经给过「请收尾」提示了吗。 */
    val hasNudged: Boolean get() = nudged

    /**
     * 每轮 LLM 返回后调一次。
     *
     * @param signatures 本轮发起的全部工具调用签名（建议 `name|arguments`）。
     *        空列表 = 本轮没有调工具（正在出正文），视为正常收尾，不累计。
     */
    fun onRound(signatures: List<String>): Decision {
        rounds++
        if (rounds >= hardStopAt) return Decision.Stop
        if (signatures.isEmpty()) {
            // 纯文本轮：模型在做输出而不是打转，清零
            streak = 0
            return Decision.Continue
        }
        val sig = signatures.joinToString("\n")
        val repeated = recent.contains(sig)
        recent.addLast(sig)
        while (recent.size > window) recent.removeFirst()

        if (!repeated) {
            streak = 0
            return Decision.Continue
        }
        streak++
        if (streak >= repeatLimit) return Decision.Stop
        if (!nudged) {
            nudged = true
            return Decision.Nudge
        }
        return Decision.Continue
    }

    companion object {
        /** 给模型的收尾提示（hidden 消息，不展示给用户）。 */
        const val NUDGE_PROMPT: String =
            "[系统提示] 你已连续多轮发起**完全相同**的工具调用，结果不会因为重跑而变化。" +
                "请立刻停止调用工具，直接基于已有的工具结果给出最终答复；确有必须继续的步骤，请换一种做法。"

        /** 用户可见的强制停止说明。 */
        const val STOP_NOTE: String = "（已自动停止：检测到重复调用同一工具且结果未变。请补充更具体的信息，或告诉我下一步该做什么。）"
    }
}
