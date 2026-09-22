package com.ai.assistance.quro.genui.aiapp.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Agent 思考步骤类型
 *
 * AI 可以根据任务自行编排这些步骤的顺序和组合。
 * 不是所有步骤都必须执行，AI 按需选择。
 */
@Serializable
enum class ThoughtStepType {
    /** 理解用户意图 — 分析用户到底想要什么 */
    INTENT,

    /** 深度思考 — 分析问题、推导方案 */
    THINK,

    /** 规划 — 制定生成计划和结构 */
    PLAN,

    /** 检索/读取 — 查找已有知识、技能、模板 */
    RETRIEVE,

    /** 调用工具/技能 — 使用外部能力 */
    TOOL_CALL,

    /** 决策 — 在多个方案中选择 */
    DECISION,

    /** 写日记/笔记 — 记录本次经验 */
    JOURNAL,

    /** 生成 UI — 最终输出 */
    GENERATE,

    /** 自我修正 — 检查并修复问题 */
    SELF_CORRECT
}

/**
 * 单个思考步骤
 */
@Serializable
data class ThoughtStep(
    /** 步骤类型 */
    val type: ThoughtStepType,

    /** 步骤标题（简短） */
    val title: String,

    /** 详细内容 */
    val content: String = "",

    /** 状态 */
    val status: ThoughtStatus = ThoughtStatus.PENDING,

    /** 结果/产出（可选） */
    val result: String? = null,

    /** 工具调用详情（如果是 TOOL_CALL 类型） */
    val toolCall: ToolCallInfo? = null,

    /** 耗时（毫秒） */
    val durationMs: Long = 0,

    /** 子步骤（可嵌套） */
    val children: List<ThoughtStep> = emptyList()
)

/**
 * 思考步骤状态
 */
@Serializable
enum class ThoughtStatus {
    PENDING,    // 等待执行
    RUNNING,    // 正在执行
    SUCCESS,    // 成功完成
    FAILED,     // 失败
    SKIPPED     // 跳过
}

/**
 * 工具调用信息
 */
@Serializable
data class ToolCallInfo(
    /** 工具/技能名称 */
    val toolName: String,

    /** 调用参数 */
    val params: JsonObject? = null,

    /** 调用结果 */
    val result: String? = null,

    /** 是否成功 */
    val success: Boolean = true
)

/**
 * Agent 思考链 — 完整的思考过程
 */
@Serializable
data class AgentThoughtChain(
    /** 思考链 ID */
    val id: String,

    /** 用户原始请求 */
    val userRequest: String,

    /** 思考步骤列表（按执行顺序） */
    val steps: List<ThoughtStep> = emptyList(),

    /** 当前执行到第几步 */
    val currentStepIndex: Int = -1,

    /** 是否完成 */
    val isComplete: Boolean = false,

    /** 总耗时（毫秒） */
    val totalDurationMs: Long = 0
) {
    /**
     * 当前正在执行的步骤
     */
    val currentStep: ThoughtStep?
        get() = if (currentStepIndex in steps.indices) steps[currentStepIndex] else null

    /**
     * 已完成的步骤数
     */
    val completedCount: Int
        get() = steps.count { it.status == ThoughtStatus.SUCCESS || it.status == ThoughtStatus.FAILED }

    /**
     * 进度百分比
     */
    val progress: Float
        get() = if (steps.isEmpty()) 0f else completedCount.toFloat() / steps.size
}

/**
 * 思考步骤显示图标
 */
val ThoughtStepType.displayIcon: String
    get() = when (this) {
        ThoughtStepType.INTENT -> "🎯"
        ThoughtStepType.THINK -> "🤔"
        ThoughtStepType.PLAN -> "📋"
        ThoughtStepType.RETRIEVE -> "🔍"
        ThoughtStepType.TOOL_CALL -> "🔧"
        ThoughtStepType.DECISION -> "✅"
        ThoughtStepType.JOURNAL -> "📓"
        ThoughtStepType.GENERATE -> "✨"
        ThoughtStepType.SELF_CORRECT -> "🔄"
    }

/**
 * 思考步骤显示名称
 */
val ThoughtStepType.displayName: String
    get() = when (this) {
        ThoughtStepType.INTENT -> "理解意图"
        ThoughtStepType.THINK -> "深度思考"
        ThoughtStepType.PLAN -> "制定计划"
        ThoughtStepType.RETRIEVE -> "检索知识"
        ThoughtStepType.TOOL_CALL -> "调用技能"
        ThoughtStepType.DECISION -> "方案决策"
        ThoughtStepType.JOURNAL -> "记录经验"
        ThoughtStepType.GENERATE -> "生成界面"
        ThoughtStepType.SELF_CORRECT -> "自我修正"
    }
