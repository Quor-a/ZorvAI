package com.ai.assistance.quro.core.agent.orchestration

/**
 * 策划 / 规划 / 设计阶段的可注入实现：把一个任务简述展开成 [TaskPlan]。
 *
 * 默认不注入（null）→ 跳过该阶段，直接进入执行（保持旧行为）。
 * 真实实现通常由 LLM 调用承担：给模型任务简述与上下文，让它产出结构化计划。
 */
fun interface TaskPlanner {
    suspend fun plan(brief: String, context: String): TaskPlan
}
