package com.ai.assistance.quro.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.ai.assistance.quro.core.assistant.AssistantChannel
import com.ai.assistance.quro.core.assistant.AssistantDispatcher
import com.ai.assistance.quro.core.assistant.AssistantRequest
import com.ai.assistance.quro.core.assistant.AssistantServiceState
import com.ai.assistance.quro.core.assistant.AssistantSource
import com.ai.assistance.quro.core.assistant.protoResultCode
import com.ai.assistance.quro.core.assistant.sealedResultJson
import com.ai.assistance.quro.util.QuroDiag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 外部文本指令入口 —— **组件形态取自小布（com.heytap.speechassist v13.3.5）**。
 *
 * ## 小布是怎么做的（清单实证）
 * ```
 * [service] com.heytap.speechassist.agent.MessengerService  exported=true
 *   actions = heytap.speech.intent.action.TEXT_DIRECTIVE
 * ```
 * 名字就叫 **Messenger**Service —— 它走的是 `Messenger`（跨进程 Binder），不是广播。
 *
 * ## 为什么广播不行（我之前就是这么写错的）
 * 广播是**单向**的：第三方把指令丢进来，拿不到"收到了没 / 执行成功没 / 结果是什么"。
 * 而"外部把活派给助手"这个场景，调用方**必须**知道结果（否则它只能干等或自己猜）。
 * Messenger 是双向的：调用方放一个 `replyTo`，我们执行完把 result 回过去。
 *
 * 所以本 Service 做成：
 *  - `onBind` 返回 Messenger，`Message.replyTo` 存在就回执（走小布的这套语义）；
 *  - `onStartCommand` 也兼容（有些调用方只会 startService 带 EXTRA_TEXT）；
 *  - **同时保留广播入口**（`AssistantDirectiveReceiver`），因为对普通第三方 App 来说广播最简单，
 *    不能因为"要照抄小布"就把已经能用的路砍掉。
 *
 * ## 协议（与 results.proto 的 SealedResult 对齐）
 * 请求 `Message`：
 *   `what` = [MSG_TEXT]
 *   `data` = Bundle { "text": String, "skill": String?, "requestId": String? }
 * 回执 `Message`：
 *   `what` = [MSG_RESULT]
 *   `data` = Bundle { "resultCode", "errorCode", "message", "skillId", "sealed" }（见 [AssistantResult.sealedResultJson]）
 */
class AssistantDirectiveService : Service() {

    companion object {
        private const val TAG = "assistant-directive"

        /** 指令 action。名字对齐小布的 `heytap.speech.intent.action.TEXT_DIRECTIVE` 语义。 */
        const val ACTION_TEXT_DIRECTIVE = "com.ai.assistance.quro.action.TEXT_DIRECTIVE"

        const val MSG_TEXT = 1
        const val MSG_RESULT = 2

        private const val KEY_TEXT = "text"
        private const val KEY_SKILL = "skill"
        private const val KEY_REQUEST_ID = "requestId"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 收到的 Messenger 请求在这里处理。 */
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_TEXT) return
            val data = msg.data ?: Bundle()
            val text = data.getString(KEY_TEXT).orEmpty()
            val skill = data.getString(KEY_SKILL)
            val rid = data.getString(KEY_REQUEST_ID)
            val replyTo = msg.replyTo
            execute(text, skill, rid, replyTo)
        }
    })

    override fun onBind(intent: Intent?): IBinder? {
        // 只对 TEXT_DIRECTIVE 提供绑定；其他 action 不返回 binder，避免被当成通用服务
        return if (intent?.action == ACTION_TEXT_DIRECTIVE) incoming.binder else null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startService 形态（不需要回执的调用方）
        val i = intent
        val text = i?.getStringExtra(Intent.EXTRA_TEXT)
            ?: i?.getStringExtra(KEY_TEXT)
            ?: i?.getStringExtra("directive")
        if (i != null && !text.isNullOrBlank()) {
            execute(text, i.getStringExtra(KEY_SKILL), i.getStringExtra(KEY_REQUEST_ID), null)
        }
        // 不是粘性服务：不需要常驻，处理完即退（Messenger 绑定期间系统会保活）
        return START_NOT_STICKY
    }

    private fun execute(text: String, skill: String?, rid: String?, replyTo: Messenger?) {
        val denied = AssistantServiceState.gate(this, AssistantChannel.DIRECTIVE)
        if (denied != null) {
            QuroDiag.log(TAG, "denied rid=$rid ${denied.wire()}")
            reply(replyTo, rid, denied)
            if (replyTo == null) stopSelf()
            return
        }
        if (text.isBlank()) {
            QuroDiag.log(TAG, "忽略空指令 rid=$rid")
            if (replyTo == null) stopSelf()
            return
        }

        QuroDiag.log(TAG, "directive rid=$rid skill=$skill len=${text.length}")
        scope.launch {
            val r = AssistantDispatcher.dispatch(
                this@AssistantDirectiveService,
                AssistantRequest(
                    // 带 skill 时用显式调用语法，让分发器优先命中那个技能
                    text = if (!skill.isNullOrBlank()) "/$skill $text" else text,
                    source = AssistantSource.DIRECTIVE,
                    raw = text,
                ),
            )
            reply(replyTo, rid, r)
            if (replyTo == null) stopSelf()
        }
    }

    private fun reply(replyTo: Messenger?, rid: String?, r: com.ai.assistance.quro.core.assistant.AssistantResult) {
        QuroDiag.log(TAG, "result rid=$rid ${r.wire()}")
        if (replyTo == null) return
        runCatching {
            val b = Bundle().apply {
                putString("resultCode", r.protoResultCode().wire)
                putString("errorCode", if (r.ok) "ERROR_CODE_UNKNOWN" else r.code)
                putString("message", r.message)
                putString("skillId", r.skillId)
                putString("requestId", rid)
                // 严格按 results.proto 的 SealedResult 序列化，调用方可直接按协议解析
                putString("sealed", r.sealedResultJson())
            }
            replyTo.send(Message.obtain(null, MSG_RESULT).apply { data = b })
        }.onFailure {
            if (it is RemoteException) QuroDiag.log(TAG, "reply failed（调用方已退出）rid=$rid")
            else QuroDiag.log(TAG, "reply failed rid=$rid ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
