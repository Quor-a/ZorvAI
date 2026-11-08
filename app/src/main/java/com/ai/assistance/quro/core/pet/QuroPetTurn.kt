package com.ai.assistance.quro.core.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌宠 → 对话框的结构化接线层。
 *
 * 旧实现只有 `QuroShareBridge.emitAutoSend(String)` 一条裸字符串通道：
 * 桌宠轻点、对它说、屏幕分析、语音球全部挤在同一个入口，对话框无法区分
 * 「用户手打的一句话」和「应用自动发起的回合」，也无法把 AI 的回复回流给桌宠。
 *
 * 这里按消息来源分层，每一类回合有明确的语义与展示策略：
 *  - [PetSource.PET_INPUT] 用户在桌宠悬浮输入框里手打的内容 —— 完全等同正常用户消息；
 *  - [PetSource.PET_TAP] / [PetSource.PET_ACTION] 陪伴式互动 —— 可见气泡只放干净短句；
 *  - [PetSource.PET_SCREEN] / [PetSource.PET_TASK] 带屏幕观察的任务回合
 *    —— 屏幕结果走隐藏上下文，AI 可以据此调用工具完成任务；
 *  - [PetSource.PET_COMPANION] / [PetSource.VOICE] 主动陪伴与语音识别。
 *
 * 回复回流：[QuroPetTurnBus] 把本轮 AI 回复摘要推回桌宠气泡，形成双向闭环；
 * 失败回合（静默撤下）不回流，也不在对话里留下任何诊断文本。
 */
enum class PetSource(val key: String, val label: String) {
    USER("user", "用户"),
    PET_TAP("pet_tap", "轻点"),
    PET_ACTION("pet_action", "互动"),
    PET_INPUT("pet_action_input", "对它说"),
    PET_SCREEN("pet_screen", "看屏幕"),
    PET_TASK("pet_task", "后台任务"),
    PET_COMPANION("pet_companion", "主动陪伴"),
    VOICE("voice", "语音"),
    ;

    /** 是否属于「应用自动发起」的回合：失败时静默、需要冷却保护。 */
    val isAuto: Boolean get() = this != USER

    /** 是否允许把屏幕观察注入隐藏上下文。 */
    val allowsScreenContext: Boolean
        get() = this == PET_SCREEN || this == PET_TASK || this == PET_TAP || this == PET_COMPANION

    companion object {
        fun fromKey(key: String?): PetSource =
            entries.firstOrNull { it.key == key } ?: PET_TAP
    }
}

/** 一次桌宠回合的完整描述。 */
data class PetTurn(
    val source: PetSource,
    /** 用户气泡里唯一可见的一句干净文本。 */
    val visibleText: String,
    /** 给模型的隐藏指令（互动模板 / 屏幕观察 / 任务要求），用户不可见。 */
    val hiddenInstruction: String? = null,
    /** 屏幕分析的结构化结果，仅注入隐藏上下文。 */
    val screenInsight: String? = null,
    /** 是否要求 AI 用工具把任务做完（写进隐藏指令，交给主 Agent 循环）。 */
    val completeTask: Boolean = false,
    /** 失败时静默撤下整轮，不落错误气泡。 */
    val silentOnError: Boolean = true,
    /** 是否拉起主对话框展示本轮。 */
    val openChat: Boolean = true,
    /** 回合关联 id，用于把回复回流到桌宠气泡。 */
    val requestId: Long = System.currentTimeMillis(),
) {
    /** 合并成一条隐藏上下文；屏幕观察与指令都带明确标记，模型不会把它们复述给用户。 */
    fun hiddenContext(): String? {
        val parts = mutableListOf<String>()
        hiddenInstruction?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        screenInsight?.trim()?.takeIf { it.isNotBlank() }?.let {
            parts.add("[屏幕观察 · 仅本轮有效，不要原样复述]\n$it")
        }
        if (completeTask) {
            parts.add("[任务要求] 依据上面的屏幕观察，用可用工具把任务实际做完；做不了时直说原因，不要编造执行结果。")
        }
        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }
}

/**
 * 桌宠 ↔ 对话框的回复回流总线。
 *
 * ChatScreen 接住 [PetTurn] 时调用 [beginTurn]；ViewModel 本轮成功收尾时读
 * [activeTurnId] 并把回复摘要 [emitReply]，桌宠服务订阅 [reply] 显示气泡；
 * 失败（静默撤下）调用 [endTurn]，不回流。
 */
object QuroPetTurnBus {

    data class Reply(val requestId: Long, val text: String, val source: PetSource)

    private val _reply = MutableStateFlow<Reply?>(null)
    val reply: StateFlow<Reply?> = _reply.asStateFlow()

    @Volatile private var turnId: Long = 0L
    @Volatile private var turnSource: PetSource = PetSource.PET_TAP

    /** 当前正在等待回复的桌宠回合 id；0 表示没有。 */
    val activeTurnId: Long get() = turnId

    fun beginTurn(turn: PetTurn) {
        turnId = turn.requestId
        turnSource = turn.source
    }

    fun endTurn() {
        turnId = 0L
    }

    /** 本轮有真实回复时才调用；只把摘要推给桌宠气泡。 */
    fun emitReply(text: String) {
        val id = turnId
        if (id == 0L || text.isBlank()) return
        val clean = QuroQuietTurn.stripDiagnostics(text).trim().take(90)
        if (clean.isBlank()) return endTurn()
        _reply.value = Reply(id, clean, turnSource)
        turnId = 0L
    }

    fun consumeReply() {
        _reply.value = null
    }
}

/** 桌宠回合构造工厂：统一可见文案、隐藏指令与失败策略，避免各处自由拼接。 */
object QuroPetTurn {

    /** 用户在悬浮输入框里手打的一句话。 */
    fun input(text: String) = PetTurn(
        source = PetSource.PET_INPUT,
        visibleText = text.trim(),
        silentOnError = false, // 手打消息与普通用户消息一致，失败照常可见
    )

    /** 轻点陪伴互动。 */
    fun tap(label: String, instruction: String, screen: String? = null) = PetTurn(
        source = PetSource.PET_TAP,
        visibleText = label,
        hiddenInstruction = "[桌宠互动指令] $instruction",
        screenInsight = screen,
    )

    /** 快捷动作互动（不发起对话，只播动画）。 */
    fun action(label: String) = PetTurn(
        source = PetSource.PET_ACTION,
        visibleText = label,
        openChat = false,
    )

    /** 「看看当前屏幕」：屏幕观察 + 让 AI 说明画面。 */
    fun screen(text: String = "看看我现在在做什么", insight: String? = null) = PetTurn(
        source = PetSource.PET_SCREEN,
        visibleText = text,
        hiddenInstruction = "[桌宠屏幕分析] 用户让桌宠看当前屏幕。结合下面的屏幕观察，用两三句话说明当前界面、正在进行的事，并给出下一步建议。",
        screenInsight = insight,
    )

    /** 「后台虚拟屏完成任务」：屏幕观察 + 明确要求用工具执行。 */
    fun task(text: String, insight: String? = null) = PetTurn(
        source = PetSource.PET_TASK,
        visibleText = text,
        hiddenInstruction = "[桌宠后台任务] 用户让桌宠在后台虚拟屏上完成任务。先读屏幕观察，再用可用工具逐步执行并核对结果。",
        screenInsight = insight,
        completeTask = true,
    )

    /** 主动陪伴（预留，当前由语音/定时链路复用）。 */
    fun companion(text: String, insight: String? = null) = PetTurn(
        source = PetSource.PET_COMPANION,
        visibleText = text,
        hiddenInstruction = "[主动陪伴] 用一句自然、不打扰的话回应，20 字以内。",
        screenInsight = insight,
    )
}
