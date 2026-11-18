package com.ai.assistance.quro.service

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.ai.assistance.quro.util.QuroDiag

/**
 * 系统级语音交互服务。
 *
 * 为什么必须有它：`Settings.Secure.voice_interaction_service` 只接受**真正的
 * VoiceInteractionService 组件**，而长按电源键在 Android 上是走
 * `VoiceInteractionManagerService.showSessionForActiveService(...)` 这条路的。
 * 没有这个组件，把本应用设成默认数字助理只会让电源键「按了没反应」——
 * 这正是之前版本最致命的问题。
 *
 * 本类不做语音识别本身：它只负责被系统拉起来，并把会话交给
 * [QuroVoiceInteractionSessionService]，由会话唤起语音球（就是应用内那条
 * 已经在工作的语音入口），从而复用完整的运行时、工具、Skills、MCP 与记忆。
 */
class QuroVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        QuroDiag.log(TAG, "voice interaction service ready")
    }

    override fun onShutdown() {
        QuroDiag.log(TAG, "voice interaction service shutdown")
        super.onShutdown()
    }

    companion object {
        private const val TAG = "quro-vis"

        const val COMPONENT =
            "com.ai.assistance.quro/com.ai.assistance.quro.service.QuroVoiceInteractionService"
    }
}

/** 会话服务：系统每次唤起都会新建一个会话。 */
class QuroVoiceInteractionSessionService : android.service.voice.VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): android.service.voice.VoiceInteractionSession =
        QuroVoiceInteractionSession(this)

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? =
        super.onBind(intent)
}

/**
 * 最小可用会话：被唤起即把控制权交回应用自己的语音入口，然后立刻结束会话，
 * 避免留下一个空白的系统助手浮层。
 */
class QuroVoiceInteractionSession(
    context: android.content.Context,
) : android.service.voice.VoiceInteractionSession(context) {

    private val host: android.content.Context = context

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val launched = runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                host,
                android.content.Intent(host, QuroVoiceBallService::class.java).apply {
                    action = QuroVoiceBallService.ACTION_VOICE_TALK
                },
            )
            true
        }.getOrDefault(false)

        if (!launched) {
            runCatching {
                host.startActivity(
                    android.content.Intent(
                        host,
                        com.ai.assistance.quro.activity.QuroMainActivity::class.java,
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { QuroDiag.log("quro-vis", "open chat failed: ${it.message}") }
        }
        // 交出去之后本会话不再承担 UI，主动收起，避免叠加一个空的系统浮层。
        runCatching { finish() }
    }
}
