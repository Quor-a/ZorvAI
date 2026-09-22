package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.aiapp.agent.AgentThoughtChain
import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage

/**
 * 工具调用记录
 *
 * 用于思考面板中展示本次对话的工具调用历史。
 */
data class ToolCallRecord(
    /** 工具名称 */
    val name: String,
    /** 调用参数（JSON 字符串） */
    val arguments: String,
    /** 执行结果 */
    val result: String,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 是否成功 */
    val isSuccess: Boolean = true
)

/**
 * 生成式 UI 状态模型
 *
 * GenUI 对话不是消息列表，而是"当前界面"。
 * AI 每次回复替换整个屏幕的 UI。
 *
 * @property currentGenUI 当前显示的 GenUI DSL JSON（全屏渲染）
 * @property currentRequest 当前用户请求文本（显示在顶栏）
 * @property isStreaming 是否正在生成
 * @property streamingText 流式文本
 * @property streamingReasoning 流式推理
 * @property thoughtChain 思考链
 * @property toolCallRecords 工具调用记录列表（用于思考面板显示）
 * @property error 错误信息
 * @property hostPersonaName 当前激活的人格卡名（来自 ZorvAI 宿主，只读展示）
 * @property modelLabel 当前使用的模型（来自 ZorvAI 宿主模型配置，只读展示）
 * @property conversationHistory 对话历史（发给 LLM 的，不显示在 UI）
 * @property hasUI 是否有 UI 正在显示
 * @property debugInfo 画布诊断信息
 * @property pageStack 多级界面栈
 * @property works 历史作品集
 */
data class ChatState(
    val currentGenUI: String? = null,
    val currentRequest: String = "",
    val isStreaming: Boolean = false,
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val thoughtChain: AgentThoughtChain? = null,
    val toolCallRecords: List<ToolCallRecord> = emptyList(),
    val error: String? = null,
    /** 当前激活的人格卡名（ZorvAI 宿主，只读展示） */
    val hostPersonaName: String = "",
    /** 当前模型标签（ZorvAI 宿主模型配置，只读展示） */
    val modelLabel: String = "",
    val conversationHistory: List<GenUIChatMessage> = emptyList(),
    val hasUI: Boolean = false,
    val debugInfo: String? = null,
    val pageStack: List<String> = emptyList(),
    val works: List<com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem> = emptyList(),
    /** 新架构通道页（Markdown / A2UI 扁平表 / 内联 HTML），非空时全屏显示 */
    val channel: com.ai.assistance.quro.genui.aiapp.renderx.ChannelPage? = null
) {
    /** 是否可以发送消息 */
    val canSend: Boolean get() = !isStreaming
}
