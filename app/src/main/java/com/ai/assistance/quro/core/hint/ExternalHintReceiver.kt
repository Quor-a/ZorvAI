package com.ai.assistance.quro.core.hint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 广播投递通道。由应用主进程动态注册为可导出组件，发送方必须持有
 * `ai.aci.permission.SEND_ASSISTANT_DIRECTIVE`，通道本身可在设置里关闭。
 *
 * extras：id / text / source / priority，其中 text 必填。
 */
class ExternalHintReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.ai.assistance.quro.action.INJECT_HINT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!ExternalHintPrefs.enabled(context) || !ExternalHintPrefs.broadcastEnabled(context)) return
        val text = intent.getStringExtra("text")?.trim().orEmpty()
        if (text.isEmpty()) return
        val id = intent.getStringExtra("id")?.takeIf { it.isNotBlank() }
            ?: "broadcast-${System.currentTimeMillis()}-${text.hashCode()}"
        val source = intent.getStringExtra("source")?.takeIf { it.isNotBlank() } ?: "broadcast"
        val priority = intent.getIntExtra("priority", 0)
        ExternalHintDispatcher.submitAsync(
            context.applicationContext,
            ExternalHint(id = id, text = text, source = source, priority = priority),
        )
    }
}
