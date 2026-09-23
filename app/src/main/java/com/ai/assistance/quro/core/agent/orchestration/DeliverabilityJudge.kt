package com.ai.assistance.quro.core.agent.orchestration

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
 * 作为落地默认候选——比 AlwaysDeliverable 多一道最低限度体检，但仍保守（不解析语义）。
 */
object HeuristicDeliverabilityJudge : DeliverabilityJudge {
    private val BAD = listOf("工具执行失败", "工具执行异常", "工具执行超时", "未知工具", "需要权限")
    override fun judge(brief: String, finalAnswer: String, context: String): Deliverability {
        val a = finalAnswer.trim()
        if (a.isEmpty() || a == "(已思考完毕)") {
            return Deliverability.NotDeliverable(
                "产物为空",
                "最终答复为空或仅为占位",
                "请基于已有工具结果给出实质答复。",
            )
        }
        val hit = BAD.firstOrNull { a.contains(it) }
        if (hit != null) {
            return Deliverability.NotDeliverable(
                "产物含失败标记",
                "最终答复含失败标记：$hit",
                "请修正后重新执行相关工具并给出结论。",
            )
        }
        return Deliverability.Deliverable("启发式检查通过")
    }
}
