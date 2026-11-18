package com.ai.assistance.quro.core.pet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.ai.assistance.quro.genui.sdk.components.PetPhase

/**
 * 宠物全局状态总线（进程内单例）。
 *
 * 桌宠悬浮窗与语音球宠物模式共用同一份「AI 在忙什么」的状态：
 * 任何一方写入，两个宿主读到的是同一个阶段 / 工具名 / 气泡文本，
 * 同进程共享 Compose Snapshot 状态，写入即触发重组，无需轮询。
 *
 * [voiceBallOwnsState] 用于仲裁：语音球正在对话时由它主导写状态，
 * 桌宠服务的行动轨迹订阅暂停写入，避免两个生产者互相覆盖。
 * [desktopPetShown] 让语音球知道桌宠已经在桌面显示，从而不再画第二个宠物。
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

    /** 语音球是否正在主导状态（桌宠的轨迹订阅此时让位）。 */
    var voiceBallOwnsState by mutableStateOf(false)

    /** 桌宠悬浮窗当前是否真的挂在桌面上（服务活着但不等于窗口可见）。 */
    var desktopPetShown by mutableStateOf(false)

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

    /** 互动动作统一入口：PERFORMING 弹跳 + label 气泡，2.6s 后回 IDLE。 */
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
