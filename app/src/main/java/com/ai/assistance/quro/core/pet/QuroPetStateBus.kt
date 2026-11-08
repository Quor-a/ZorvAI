package com.ai.assistance.quro.core.pet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.ai.assistance.quro.genui.sdk.components.PetPhase

/**
 * 桌面宠物全局状态总线（进程内单例）。
 *
 * 作用：把「对话框里的 AI 活动」投影到**桌面常驻宠物悬浮窗**——
 * 对话层（ChatScreen）在生成/工具调用状态变化时写入本总线，
 * 桌面宠物服务（QuroDesktopPetService）的 ComposeView 直接读取，
 * 同进程共享 Compose Snapshot 状态，写入即触发悬浮窗重组，无需轮询。
 *
 * 与语音球互不干扰：语音球自带会话状态（conversationActive / listening / speaking），
 * 本总线只承载「AI 在忙什么」的语义，供非交互的桌面宠物展示。
 */
object QuroPetStateBus {
    /** 宠物当前阶段：IDLE 空闲 / THINKING 思考 / TOOL_CALLING 执行工具 / GENERATING 生成回复。 */
    var phase by mutableStateOf(PetPhase.IDLE)

    /** 正在执行的工具名（TOOL_CALLING 阶段显示在宠物气泡上），无工具时为 null。 */
    var toolName by mutableStateOf<String?>(null)

    /** 附加状态文本（悬浮窗底部小字），null 时不显示。 */
    var statusText by mutableStateOf<String?>(null)

    /** AI 是否在忙（busy 期间环境动画暂停，避免覆盖真实状态）。 */
    val isActive: Boolean get() = phase != PetPhase.IDLE

    /** 事件反应：回复完成时短暂庆祝气泡。 */
    var celebrateText by mutableStateOf<String?>(null)
    private var celebrateJob: kotlinx.coroutines.Job? = null

    /** 对话层写入：本轮回复完成 → DONE 阶段 + 庆祝气泡，2.6s 后自动回 IDLE。 */
    fun onReplyComplete(summary: String?) {
        celebrateJob?.cancel()
        toolName = null
        statusText = null
        phase = PetPhase.DONE
        celebrateText = summary?.takeIf { it.isNotBlank() } ?: "回复完成 ✓"
        celebrateJob = kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(2600)
            celebrateText = null
            phase = PetPhase.IDLE
        }
    }

    /** 对话层写入：进入忙碌状态（带当前工具名）。 */
    fun onBusy(tool: String?) {
        celebrateJob?.cancel()
        celebrateText = null
        toolName = tool
        phase = if (tool != null) PetPhase.TOOL_CALLING else PetPhase.THINKING
    }

    /** 对话层写入：没有工具、也不在思考，但仍在生成正文 → GENERATING。 */
    fun onGenerating() {
        celebrateJob?.cancel()
        celebrateText = null
        toolName = null
        phase = PetPhase.GENERATING
    }

    /**
     * 🔴 互动动作（2026-11-06 修复「act 显示回复完成+动画码」）：
     * AI 的 pet(action=act) / 服务内互动统一入口 —— PERFORMING 弹跳 + label 气泡，
     * 2.6s 后回 IDLE。不再借用 onReplyComplete（那会显示错误的「回复完成 ✓」）。
     */
    fun onInteract(label: String, action: String) {
        celebrateJob?.cancel()
        celebrateText = null
        toolName = null
        statusText = label
        phase = PetPhase.PERFORMING
        celebrateJob = kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(2600)
            statusText = null
            phase = PetPhase.IDLE
        }
    }

    /** 对话层写入：回到空闲。 */
    fun onIdle() {
        celebrateJob?.cancel()
        celebrateText = null
        toolName = null
        statusText = null
        phase = PetPhase.IDLE
    }
}
