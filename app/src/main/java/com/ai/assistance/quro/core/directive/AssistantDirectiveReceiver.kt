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

        /**
         * 后台任务中转（对标小布 `SUBMIT_BACKGROUND_TASK_RELAY`）：
         * 外部应用提交一个会长跑的能力，宿主在后台跑完通过
         * [com.ai.assistance.quro.core.assistant.BackgroundTaskRelay.RELAY_RESULT_ACTION] 回执。
         */
        const val ACTION_RELAY_SUBMIT = "com.ai.assistance.quro.action.RELAY_SUBMIT"

        /** relay 用到的 extra 键。 */
        const val EXTRA_SKILL_ID = "skill_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_TARGET_PACKAGE = "target_package"

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
        // 显式 DIRECTIVE / RELAY 广播：要求发送方持有自定义权限（清单层不能挂，否则 SEND/VIEW 会被挡）。
        // 本应用自身（同 uid）恒放行——自测与内部调用不受影响。
        if (intent.action == ACTION_DIRECTIVE || intent.action == ACTION_RELAY_SUBMIT) {
            val granted = context.checkCallingOrSelfPermission(PERMISSION_SEND) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "拒绝未授权的广播（发送方缺少 $PERMISSION_SEND）action=${intent.action}")
                return
            }
        }

        // ── 后台任务中转 ─────────────────────────────────────────
        if (intent.action == ACTION_RELAY_SUBMIT) {
            val app = context.applicationContext
            val skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
            val rid = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
            val args = LinkedHashMap<String, String>()
            intent.extras?.keySet()?.forEach { k ->
                val v = intent.extras?.get(k)
                if (v is String && v.isNotBlank()) args[k] = v
            }
            val r = com.ai.assistance.quro.core.assistant.BackgroundTaskRelay.submit(
                app, skillId, args, rid, intent.getStringExtra(EXTRA_TARGET_PACKAGE),
            )
            Log.i(TAG, "relay submit skill=$skillId rid=$rid → ${r.code}")
            val pending = goAsync()
            runCatching {
                pending?.setResultCode(if (r.ok) 0 else 1)
                pending?.setResultData(r.wire())
                pending?.setResultExtras(android.os.Bundle().apply { putString(RESULT_REASON, r.code) })
            }
            pending?.finish()
            return
        }

        val text = textOf(intent)
        if (text.isNullOrBlank()) {
            Log.w(TAG, "忽略无文本指令: action=${intent.action}")
            return
        }

        // 交给统一分发器：先看有没有能直接执行的能力（快、确定、不烧 token），
        // 没命中再把原文投进对话信箱由 AI 编排。六种入口共用这一条路。
        val src = when {
            intent.action == Intent.ACTION_SEND -> com.ai.assistance.quro.core.assistant.AssistantSource.SHARE
            else -> com.ai.assistance.quro.core.assistant.AssistantSource.DIRECTIVE
        }
        val req = com.ai.assistance.quro.core.assistant.AssistantDispatcher
            .requestOf(intent, src).copy(text = text, raw = text)
        Log.i(TAG, "收到外部指令 source=${src.wire} len=${text.length}")
        val pending = goAsync()
        com.ai.assistance.quro.core.assistant.AssistantDispatcher.submit(context.applicationContext, req) { r ->
            runCatching {
                pending?.setResultCode(if (r.ok) 0 else 1)
                pending?.setResultData(r.wire())
                pending?.setResultExtras(android.os.Bundle().apply { putString(RESULT_REASON, r.code) })
            }
            pending?.finish()
        }
    }
}
