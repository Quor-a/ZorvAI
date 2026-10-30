package com.ai.assistance.quro.core.directive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 外部指令（范式取自 OPPO Breeno 的 `heytap.speech.intent.action.TEXT_DIRECTIVE`）。
 *
 * Breeno 给第三方留了三种把话喂进助手的入口：文本指令（DIRECTIVE）、引擎 Binder（ENGINE_API）、
 * 深链（DeepLink）。我们此前只有终端那条窄通道（`TERMINAL_*` 广播），**没法让别的 App
 * 直接把一句话交给 AI 助手**。本对象就是那条"文本指令"通道的落点：
 *
 * 设计上刻意做成**进程内一次性信箱**而不是广播直连 UI：
 *  - 广播可能打在进程冷启动/后台时，UI 还没挂载，直接调 ViewModel 会丢；
 *  - 信箱留一份待消费指令，ChatScreen 挂载后自行取走，冷热启动都不会丢话。
 *
 * 生命周期：`post()` 写入 → `ChatScreen` 消费后 `consume()` 清空。只保留最新一条
 * （连点多次只发最后一条，避免重复刷屏）。
 */
data class AssistantDirective(
    /** 要交给 AI 的原始文本（已 trim）。 */
    val text: String,
    /** 来源标记，进日志用：receiver / share / deeplink。 */
    val source: String,
    val atMillis: Long,
)

object AssistantDirectiveBus {

    private val _pending = MutableStateFlow<AssistantDirective?>(null)

    /** 待消费指令（null = 无）。UI 侧 collect 它即可。 */
    val pending: StateFlow<AssistantDirective?> = _pending

    /**
     * 投递一条指令。空文本直接忽略（返回 false），避免下游把空消息发给模型。
     * @return 是否真的投递成功。
     */
    fun post(text: String?, source: String): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        _pending.value = AssistantDirective(t, source, System.currentTimeMillis())
        return true
    }

    /** UI 消费完毕清空（幂等）。 */
    fun consume() {
        _pending.value = null
    }

    /** 直接取走并清空（非 Flow 场景用）。 */
    fun take(): AssistantDirective? {
        val d = _pending.value
        _pending.value = null
        return d
    }
}
