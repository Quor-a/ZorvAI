package com.ai.assistance.quro.core.directive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 外部把一句话交给 ZorvAI 的三个入口（范式取自 OPPO Breeno 的 TEXT_DIRECTIVE）。
 *
 * 支持形态（任意一种，都落到同一个 [AssistantDirectiveBus]）：
 *
 * ```kotlin
 * // 1) 显式文本指令（推荐）
 * sendBroadcast(Intent("com.ai.assistance.quro.action.DIRECTIVE")
 *     .putExtra("text", "帮我看看这张图是什么"))
 *
 * // 2) 系统分享（SEND / text/plain）
 * startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
 *     type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "总结这段话：…")
 * }, "交给 ZorvAI"))
 *
 * // 3) 深链
 * startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("quro://assistant?text=你好")))
 * ```
 *
 * 与终端的 `TerminalBroadcastReceiver` 同一 house style：Action/Extra 全用常量，
 * 排序广播可回传结果；不同点是这里**不需要回执**（助手回复走会话，不返回给调用方）。
 *
 * 安全面：清单里用自定义权限 `ai.aci.permission.SEND_ASSISTANT_DIRECTIVE`（normal）约束发送方，
 * 且只接受**用户可见的一行文本**——不接受文件路径、不执行任何代码，避免变成任意指令通道。
 */
class AssistantDirectiveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AssistantDirective"

        /** 显式文本指令。 */
        const val ACTION_DIRECTIVE = "com.ai.assistance.quro.action.DIRECTIVE"

        /** 发送方需要持有的权限（与清单声明一致）。 */
        const val PERMISSION_SEND = "ai.aci.permission.SEND_ASSISTANT_DIRECTIVE"

        /** 文本 extra（两个键都认，便于第三方按习惯传）。 */
        const val EXTRA_TEXT = "text"
        const val EXTRA_DIRECTIVE = "directive"

        /** 结果 key：是否受理。 */
        const val RESULT_ACCEPTED = "accepted"
        const val RESULT_REASON = "reason"

        /** 深链 scheme/host。 */
        const val DEEPLINK_SCHEME = "quro"
        const val DEEPLINK_HOST = "assistant"

        /**
         * 从任意 Intent 里抽出要交给助手的文本；抽不到返回 null。
         * 提取顺序即优先级：显式 extra → SEND 的 EXTRA_TEXT → 深链 query → data 字符串。
         */
        fun textOf(intent: Intent): String? {
            intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
            intent.getStringExtra(EXTRA_DIRECTIVE)?.takeIf { it.isNotBlank() }?.let { return it }
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            val data: Uri? = intent.data
            if (data != null) {
                data.getQueryParameter(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
                // quro://assistant/<文本> 形态：把 path 当文本
                data.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 显式 DIRECTIVE 广播：要求发送方持有自定义权限（清单层不能挂，否则 SEND/VIEW 会被挡）。
        // 本应用自身（同 uid）恒放行——自测与内部调用不受影响。
        if (intent.action == ACTION_DIRECTIVE) {
            val granted = context.checkCallingOrSelfPermission(PERMISSION_SEND) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "拒绝未授权的 DIRECTIVE 广播（发送方缺少 $PERMISSION_SEND）")
                return
            }
        }
        val source = when {
            intent.action == ACTION_DIRECTIVE -> "receiver"
            intent.action == Intent.ACTION_SEND -> "share"
            intent.action == Intent.ACTION_VIEW -> "deeplink"
            else -> intent.action ?: "unknown"
        }
        val text = textOf(intent)
        if (text.isNullOrBlank()) {
            Log.w(TAG, "忽略无文本指令: action=${intent.action}")
            goAsync()?.let { it.finish() }
            return
        }
        AssistantDirectiveBus.post(text, source)
        Log.i(TAG, "收到外部指令 source=$source len=${text.length}")

        // 把 UI 带到前台，让 ChatScreen 挂载后消费这条指令（进程冷启动也不丢）。
        val open = Intent(context, com.ai.assistance.quro.activity.QuroMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(open) }
            .onFailure { Log.w(TAG, "拉起主界面失败（指令已入信箱，下次打开生效）", it) }

        // 排序广播时回执：调用方可据此知道"收到没"
        if (intent.action == ACTION_DIRECTIVE) {
            val pending = goAsync()
            runCatching {
                val b = android.os.Bundle().apply {
                    putBoolean(RESULT_ACCEPTED, true)
                    putString(RESULT_REASON, "queued")
                }
                pending?.setResultCode(0)
                pending?.setResultData(text)
                pending?.setResultExtras(b)
            }
            pending?.finish()
        }
    }
}
