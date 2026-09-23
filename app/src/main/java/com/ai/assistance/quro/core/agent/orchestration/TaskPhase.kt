package com.ai.assistance.quro.core.agent.orchestration

/** 长程任务的阶段（策划-规划-设计-执行-校验-修正-交付）。 */
enum class TaskPhase(val label: String) {
    /** 策划方案：目标拆解与策略选择。 */
    STRATEGIZE("策划方案"),
    /** 规划方案：步骤拆解与执行顺序。 */
    PLAN("规划方案"),
    /** 设计方案：实现细节与约束。 */
    DESIGN("设计方案"),
    /** 执行：调用工具/子 Agent 落地。 */
    EXECUTE("执行"),
    /** 校验：结果是否符合预期。 */
    VERIFY("校验"),
    /** 修正：未通过时回到规划/执行。 */
    CORRECT("修正"),
    /** 交付判定：产物是否真正可交付给用户。 */
    DELIVER("交付判定"),
}
