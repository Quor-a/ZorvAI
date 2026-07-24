package com.ai.assistance.quro.core.bot.adapters

import com.ai.assistance.quro.core.bot.QuroBotAdapter
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroOutboundMessage

/**
 * 本地测试机器人适配器（Phase 1「一个平台打通」）。
 *
 * 不需要任何外部 SDK / 后端：它只是把 [com.ai.assistance.quro.core.bot.QuroBotManager.sendLocalTest]
 * 触发的入站消息，经回复引擎得到的回复，通过内存监听器交给 App 内界面展示，完整验证
 * 「收消息 → QuroAssistant → 回传」链路可编译、可运行。
 *
 * 用法：设置页调用 QuroBotManager.instance(ctx).sendLocalTest(text)，
 * 并通过 [addReplyListener] 观察回复；[deliver] 仅把回复转发给监听器。
 */
class QuroLocalBotAdapter : QuroBotAdapter {
    override val platform = QuroBotPlatform.LOCAL

    private val listeners = mutableListOf<(String) -> Unit>()

    /** 注册一个回复监听器（设置页用于把 bot 回复显示在屏幕上）。 */
    fun addReplyListener(listener: (String) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeReplyListener(listener: (String) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    override fun isConfigured(): Boolean = true

    override suspend fun start() {
        // 本地适配器无需连接；直接可用。
    }

    override suspend fun stop() {
        synchronized(listeners) { listeners.clear() }
    }

    override suspend fun deliver(reply: QuroOutboundMessage) {
        synchronized(listeners) {
            listeners.forEach { it(reply.text) }
        }
    }
}
