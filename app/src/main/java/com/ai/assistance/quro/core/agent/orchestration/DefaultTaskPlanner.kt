package com.ai.assistance.quro.core.agent.orchestration

/**
 * 默认策划器：**Agent 的一环，不是可选功能**。
 *
 * ## 为什么需要一个「默认」实现
 * [TaskPlanner] 此前只能由调用方注入，没注入就整段跳过——于是「策划/规划/设计」
 * 实际上从未在真实对话里跑过。它是纯 `suspend` 接口，真实实现通常由 LLM 承担，
 * 但**每次对话都额外调一次 LLM 换计划**代价太高（延迟 + token + 半数情况计划没用），
 * 所以默认走这个**纯本地、零 token、零延迟**的实现。
 *
 * ## 它做什么
 * 把用户指令拆成三个必答问题，作为隐藏 system 提示在首轮注入：
 *  - 要做什么（目标）
 *  - 依赖什么（需要的工具 / 信息 / 前置）
 *  - 怎么验证（做完如何确认真的成了）
 *
 * 最后一问是关键：绝大多数「AI 干了但没干成」的表现是**没验证**就宣布完成。
 *
 * ## 刻意不做的事
 * 不生成 [TaskStep] 步骤列表——真正的步骤拆解要靠模型看工具目录后自己决定，
 * 这里是纯本地启发式，编出来的步骤只会误导。步骤留给 [TaskPlan.steps] 为空。
 * 不调用 LLM、不做语义解析、不猜工具名。
 */
object DefaultTaskPlanner : TaskPlanner {
    override suspend fun plan(brief: String, context: String): TaskPlan {
        val b = brief.trim()
        if (b.isEmpty()) return TaskPlan()
        return TaskPlan(
            strategy = "目标：$b",
            plan = buildString {
                appendLine("执行前先确认三件事：")
                appendLine("1) 要做什么：把上面的目标复述成一句可判定完成/未完成的话。")
                appendLine("2) 依赖什么：这一步需要哪些工具或信息？工具不在手上就先说明，别假装调过。")
                appendLine("3) 怎么验证：做完用什么证据说明它真的成了（命令输出、文件内容、检索结果）。")
            },
            // design 刻意留空：本地实现没有工具目录与代码上下文，编不出可靠的设计方案。
            // 填一堆猜测反而会让模型以为自己已经了解代码结构。
            design = "",
            steps = emptyList(),
            raw = "",
        )
    }
}
