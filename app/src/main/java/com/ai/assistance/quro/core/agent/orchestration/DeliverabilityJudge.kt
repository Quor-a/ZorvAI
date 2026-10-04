package com.ai.assistance.quro.core.agent.orchestration
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

/**
 * 交付闸门：在把任务产物交回用户前，判定其是否"真正可交付"。
 *
 * 不可交付时，系统**不立即交付**，而是把 [Deliverability.NotDeliverable.reason]/[suggestion]
 * 作为修正指令压回 Agent，让编排继续执行（重新规划/执行），直到可交付或超出预算升级。
 * 这是"所有任务不是马上交，而是先检查是否可交付，不是才继续 Agent 编排"的核心机制。
 */
fun interface DeliverabilityJudge {
    fun judge(brief: String, finalAnswer: String, context: String): Deliverability
}

/** 默认闸门：永远可交付（即保持旧行为，不拦截）。 */
object AlwaysDeliverable : DeliverabilityJudge {
    override fun judge(brief: String, finalAnswer: String, context: String): Deliverability =
        Deliverability.Deliverable("默认闸门：直接交付")
}

/**
 * 启发式闸门：兜底检查产物不像"空 / 报错 / 占位"，否则视为不可交付，要求继续编排。
 *
 * ## 🔴 判定口径铁律（闸门常驻后定的，务必读）
 * 失败标记的匹配必须是「**整段答复本身就是失败回执**」，而**不是**「答复里提到了失败」。
 * 反例（真实会发生、闸门一旦这么判就会误杀）：模型正常向用户解释
 * 「刚才那个工具执行失败了，原因是权限不足，我改用 xxx 重试并成功」——
 * 这是一段**完全可交付**的答复，包含「工具执行失败」字样。
 * 若按 `contains` 判，这段会被打回重做；而重做后模型大概率还是这么解释，
 * 于是白白多烧 2~3 轮 LLM 才撞上 [deliverAttempts] 上限强制放行。
 *
 * 因此这里用两个条件**同时**成立才判失败：
 *  1. 失败短语出现在**开头**（失败回执的习惯句式），且
 *  2. 答复**足够短**（超过 [MAX_ECHO] 说明模型在展开讲，不是在甩回执）。
 * 另外，含有 [RECOVERED] 这类「已恢复」信号时一律放行。
 */
object HeuristicDeliverabilityJudge : DeliverabilityJudge {
    /** 失败回执句式。必须配合「开头」+「短答复」才判失败，见类注释。 */
    private val BAD = listOf("工具执行失败", "工具执行异常", "工具执行超时", "未知工具", "需要权限")

    /** 超过这个长度就认为模型在展开讲解，不是在甩一句失败回执。 */
    private const val MAX_ECHO = 160

    /** 「已恢复 / 已成功」信号：出现即视为该失败已被处理，放行。 */
    private val RECOVERED = listOf("已成功", "已恢复", "重试成功", "已重试", "解决了", "已修复", "改用")

    override fun judge(brief: String, finalAnswer: String, context: String): Deliverability {
        val a = finalAnswer.trim()
        if (a.isEmpty() || a == "(已思考完毕)") {
            return Deliverability.NotDeliverable(
                "产物为空",
                "最终答复为空或仅为占位",
                "请基于已有工具结果给出实质答复。",
            )
        }
        // 已恢复信号优先：正文里出现它，说明失败只是叙述的一部分，不是产物本身。
        if (RECOVERED.any { a.contains(it) }) return Deliverability.Deliverable("含已恢复信号")

        val head = a.take(MAX_ECHO)
        val hit = BAD.firstOrNull { head.startsWith(it) || (head.length <= MAX_ECHO && a == it) }
        if (hit != null && a.length <= MAX_ECHO) {
            return Deliverability.NotDeliverable(
                "产物含失败标记",
                "最终答复本身就是一句失败回执：$hit",
                "请修正后重新执行相关工具并给出结论；若工具确实不可用，请明确告知用户替代方案。",
            )
        }
        return Deliverability.Deliverable("启发式检查通过")
    }
}