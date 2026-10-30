package com.ai.assistance.quro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.core.assistant.AssistantChannel
import com.ai.assistance.quro.core.assistant.AssistantServiceState
import com.ai.assistance.quro.service.AssistantWakeupService
import com.ai.assistance.quro.service.QuroVoiceBallService
import com.ai.assistance.quro.util.QuroDiag

/**
 * 唤醒命中接收器 —— 接 `:wakeup` 进程发来的"听到唤醒词了"。
 *
 * 为什么用广播而不是直接调服务：唤醒监听跑在**独立进程**（`android:process=":wakeup"`），
 * 崩了不影响主程序。跨进程最稳的通信就是同包定向广播（`setPackage`），
 * 既不涉及 Android 12+ 的后台服务启动限制，也不需要 AIDL。
 *
 * 收到之后主进程做的事，就是小布听到"小布小布"之后做的事：**把语音球叫起来并进入聆听**。
 * 语音球起不来（缺悬浮窗权限等）就退一步打开对话 —— 绝不静默。
 */
class AssistantWakeupHitReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "assistant-wakeup-hit"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AssistantWakeupService.ACTION_HIT) return
        val word = intent.getStringExtra("word").orEmpty()

        // 闸门再查一次：`:wakeup` 进程可能还活着，但用户在设置里刚把开关关了
        val denied = AssistantServiceState.gate(context, AssistantChannel.VOICE_WAKEWORD)
        if (denied != null) {
            QuroDiag.log(TAG, "hit denied word=$word ${denied.wire()}")
            // 顺手把监听也停掉，别让它在后台白耗电
            runCatching {
                context.startService(
                    Intent(context, AssistantWakeupService::class.java).apply {
                        action = AssistantWakeupService.ACTION_STOP
                    }
                )
            }
            return
        }

        QuroDiag.log(TAG, "wake word hit: $word")

        val started = runCatching {
            context.startForegroundService(
                Intent(context, QuroVoiceBallService::class.java).apply {
                    action = QuroVoiceBallService.ACTION_VOICE_TALK
                }
            )
        }.isSuccess

        if (!started) {
            QuroDiag.log(TAG, "语音球起不来，回落打开对话")
            runCatching {
                context.startActivity(
                    Intent(context, com.ai.assistance.quro.activity.QuroMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }.onFailure { QuroDiag.log(TAG, "open chat failed: ${it.message}") }
        }
    }
}
