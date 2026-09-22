package com.ai.assistance.quro.genui.aiapp.agent

import com.ai.assistance.quro.genui.aiapp.agent.ThoughtStepType.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * Agent 思考链处理器
 *
 * 负责解析 AI 流式输出中的思考步骤标记，
 * 维护思考链状态，并通知 UI 更新。
 *
 * 支持的思考标记格式（AI 在输出中用这些标记来编排思考过程）：
 *
 * <intent>
 *   分析用户意图...
 * </intent>
 *
 * <think>
 *   深度思考内容...
 * </think>
 *
 * <plan>
 *   规划内容...
 * </plan>
 *
 * <retrieve skill="card_patterns">
 *   检索结果...
 * </retrieve>
 *
 * <tool name="register_component" params='{...}'>
 *   调用结果...
 * </tool>
 *
 * <decision>
 *   决策内容...
 * </decision>
 *
 * <journal>
 *   日记内容...
 * </journal>
 *
 * <generate>
 *   生成过程描述...
 * </generate>
 *
 * <self_correct>
 *   自我修正内容...
 * </self_correct>
 */
class AgentThinkingProcessor {

    private var currentChain: AgentThoughtChain = AgentThoughtChain(
        id = UUID.randomUUID().toString(),
        userRequest = ""
    )

    private val steps = mutableListOf<ThoughtStep>()
    private var currentStepType: ThoughtStepType? = null
    private var currentStepTitle = ""
    private var currentStepContent = StringBuilder()
    private var currentToolCall: ToolCallInfo? = null

    private var buffer = StringBuilder()
    private var inTag = false
    private var tagName = StringBuilder()

    /**
     * 思考链更新回调
     */
    var onChainUpdate: ((AgentThoughtChain) -> Unit)? = null

    /**
     * 开始新的思考链
     */
    fun startChain(userRequest: String) {
        steps.clear()
        currentStepType = null
        currentStepTitle = ""
        currentStepContent = StringBuilder()
        currentToolCall = null
        buffer.clear()
        inTag = false
        tagName.clear()

        currentChain = AgentThoughtChain(
            id = UUID.randomUUID().toString(),
            userRequest = userRequest
        )
        notifyUpdate()
    }

    /**
     * 处理流式输出的增量文本
     * 解析思考标记，更新思考链
     */
    fun processChunk(chunk: String) {
        for (char in chunk) {
            processChar(char)
        }
    }

    private fun processChar(char: Char) {
        when {
            // 检测标签开始
            char == '<' && !inTag && !isInsideCodeBlock() -> {
                // 先把 buffer 里的内容加到当前步骤
                flushBufferToCurrentStep()
                inTag = true
                tagName.clear()
            }

            // 检测标签结束
            char == '>' && inTag -> {
                inTag = false
                handleTag(tagName.toString().trim())
                tagName.clear()
            }

            // 累积标签名或内容
            inTag -> tagName.append(char)
            else -> buffer.append(char)
        }
    }

    private fun isInsideCodeBlock(): Boolean {
        // 简单检测：buffer 中 ``` 的数量是否为奇数
        return buffer.windowed(3).count { it == "```" } % 2 == 1
    }

    private fun handleTag(tag: String) {
        // 去除属性，取标签名
        val tagParts = tag.split(" ", limit = 2)
        val tagName = tagParts[0].removePrefix("/").lowercase()
        val attributes = if (tagParts.size > 1) parseAttributes(tagParts[1]) else emptyMap()

        val isClosing = tag.startsWith("/")

        val stepType = when (tagName) {
            "intent" -> INTENT
            "think" -> THINK
            "plan" -> PLAN
            "retrieve" -> RETRIEVE
            "tool", "tool_call" -> TOOL_CALL
            "decision" -> DECISION
            "journal" -> JOURNAL
            "generate" -> GENERATE
            "self_correct", "correct" -> SELF_CORRECT
            else -> null
        }

        if (stepType != null) {
            if (!isClosing) {
                // 开始新步骤
                startStep(stepType, attributes)
            } else {
                // 结束当前步骤
                endStep(stepType)
            }
        }
    }

    private fun parseAttributes(attrStr: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        // 简单解析 key="value" 或 key='value'
        val pattern = Regex("""(\w+)\s*=\s*["']([^"']*)["']""")
        pattern.findAll(attrStr).forEach { match ->
            attrs[match.groupValues[1]] = match.groupValues[2]
        }
        return attrs
    }

    private fun startStep(type: ThoughtStepType, attributes: Map<String, String>) {
        // 先结束上一个未完成的步骤
        if (currentStepType != null) {
            endCurrentStep(ThoughtStatus.SUCCESS)
        }

        currentStepType = type
        currentStepContent = StringBuilder()
        currentStepTitle = attributes["title"] ?: type.displayName

        // 工具调用特殊处理
        if (type == TOOL_CALL) {
            currentToolCall = ToolCallInfo(
                toolName = attributes["name"] ?: attributes["tool"] ?: "unknown",
                params = attributes["params"]?.let {
                    runCatching {
                        // 简单解析 JSON 参数
                        buildJsonObject {
                            put("raw", JsonPrimitive(it))
                        }
                    }.getOrNull()
                }
            )
        }

        // 添加到步骤列表
        val step = ThoughtStep(
            type = type,
            title = currentStepTitle,
            content = "",
            status = ThoughtStatus.RUNNING,
            toolCall = currentToolCall
        )
        steps.add(step)

        updateCurrentIndex()
        notifyUpdate()
    }

    private fun endStep(type: ThoughtStepType) {
        if (currentStepType == type) {
            endCurrentStep(ThoughtStatus.SUCCESS)
        }
    }

    private fun endCurrentStep(status: ThoughtStatus) {
        val type = currentStepType ?: return

        // 更新最后一个步骤
        val lastIndex = steps.indexOfLast { it.type == type && it.status == ThoughtStatus.RUNNING }
        if (lastIndex >= 0) {
            val oldStep = steps[lastIndex]
            steps[lastIndex] = oldStep.copy(
                content = currentStepContent.toString().trim(),
                status = status,
                result = if (status == ThoughtStatus.SUCCESS) "完成" else null
            )
        }

        currentStepType = null
        currentStepContent = StringBuilder()
        currentToolCall = null
        updateCurrentIndex()
        notifyUpdate()
    }

    private fun flushBufferToCurrentStep() {
        if (buffer.isNotEmpty() && currentStepType != null) {
            currentStepContent.append(buffer)
            // 更新当前步骤的内容
            val lastIndex = steps.indexOfLast { it.type == currentStepType && it.status == ThoughtStatus.RUNNING }
            if (lastIndex >= 0) {
                val oldStep = steps[lastIndex]
                steps[lastIndex] = oldStep.copy(
                    content = currentStepContent.toString()
                )
                notifyUpdate()
            }
        }
        buffer.clear()
    }

    private fun updateCurrentIndex() {
        currentChain = currentChain.copy(
            steps = steps.toList(),
            currentStepIndex = steps.indexOfFirst { it.status == ThoughtStatus.RUNNING },
            isComplete = steps.isNotEmpty() && steps.all { it.status != ThoughtStatus.RUNNING && it.status != ThoughtStatus.PENDING }
        )
    }

    private fun notifyUpdate() {
        onChainUpdate?.invoke(currentChain)
    }

    /**
     * 完成思考链（所有步骤结束）
     */
    fun completeChain() {
        // 如果还有未结束的步骤，标记为完成
        if (currentStepType != null) {
            endCurrentStep(ThoughtStatus.SUCCESS)
        }
        flushBufferToCurrentStep()

        currentChain = currentChain.copy(
            steps = steps.toList(),
            isComplete = true
        )
        notifyUpdate()
    }

    /**
     * 获取当前思考链
     */
    fun getCurrentChain(): AgentThoughtChain = currentChain

    /**
     * 获取纯文本内容（去除思考标记后的实际内容）
     */
    fun getPlainContent(): String {
        // 对于 GenUI，最终生成的是 JSON，
        // 思考内容在思考标记内，实际 UI 代码在标记外
        return buffer.toString()
    }
}
