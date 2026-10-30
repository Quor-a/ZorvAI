package com.ai.assistance.quro.core.assistant

import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.util.QuroDiag
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 后台任务中转（external background task relay）。
 *
 * 对标小布的 `BackgroundTaskRelayActivity`
 * （`heytap.speech.intent.action.SUBMIT_BACKGROUND_TASK_RELAY`）：
 * 外部应用把一件「可能跑很久」的活交给助手宿主，宿主在后台跑完再**回执**，
 * 调用方不必自己保活、也不必等在前台。
 *
 * 设计取舍（都是踩过的坑）：
 * - **回执必发**：无论成功、失败、超时，都会发一条 `RELAY_RESULT_ACTION` 广播并带上 requestId，
 *   否则调用方只能等到自己超时，无法区分「还在跑」和「已经死了」。
 * - **幂等去重**：同一个 requestId 在 [DEDUP_WINDOW_MS] 内只执行一次，防外部重试风暴。
 * - **单并发**：同一时刻只跑一个 relay 任务，第二个返回 `busy`（后台算力有限，
 *   排队会让所有任务一起变慢，直接拒绝更诚实）。
 * - **硬超时**：超过 [TIMEOUT_MS] 记 `timeout` 并回执，避免挂死。
 */
object BackgroundTaskRelay {
    private const val TAG = "assistant-relay"

    /** 回执广播 action：调用方注册 receiver 收这个。 */
    const val RELAY_RESULT_ACTION = "com.ai.assistance.quro.action.RELAY_RESULT"

    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_OK = "ok"
    const val EXTRA_CODE = "code"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_SKILL_ID = "skill_id"
    const val EXTRA_RESULT = "result"

    /**
     * 严格按 proto `SealedResult{resultCode,data,errorCode,message}` 序列化的回执。
     * 字段名与 `assets/proto/results.proto` 一致，外部调用方可直接按协议解析。
     */
    const val EXTRA_SEALED_RESULT = "sealed_result"
    const val EXTRA_TARGET_PACKAGE = "target_package"

    private const val DEDUP_WINDOW_MS = 60_000L
    private const val TIMEOUT_MS = 180_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = ConcurrentHashMap<String, Long>()

    @Volatile
    private var running = false

    @Volatile
    var currentSkill: String = ""
        private set

    /**
     * 提交一个后台任务。
     * @param targetPackage 回执广播定向到的包名（可空 = 不限定接收方）
     */
    fun submit(
        ctx: Context,
        skillId: String,
        args: Map<String, String>,
        requestId: String,
        targetPackage: String? = null,
    ): AssistantResult {
        val app = ctx.applicationContext
        val rid = requestId.ifBlank { "relay-" + System.currentTimeMillis() }
        val now = System.currentTimeMillis()

        // 去重（顺手清理过期项，避免 map 无限增长）
        recent.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        val prev = recent.putIfAbsent(rid, now)
        if (prev != null) {
            QuroDiag.log(TAG, "submit rejected duplicate rid=$rid skill=$skillId")
            return AssistantResult.fail(
                AssistantCodes.RELAY_DUPLICATE,
                "同一个 requestId 已经在处理中或刚完成：$rid",
                skillId = skillId,
                data = mapOf("requestId" to rid, "firstSeenAt" to prev),
            )
        }

        if (running) {
            QuroDiag.log(TAG, "submit rejected busy rid=$rid running=$currentSkill")
            return AssistantResult.fail(
                AssistantCodes.BUSY,
                "后台已有任务在跑（$currentSkill），稍后再试",
                skillId = skillId,
                data = mapOf("requestId" to rid, "runningSkill" to currentSkill),
            )
        }

        running = true
        currentSkill = skillId
        QuroDiag.log(TAG, "submit accepted rid=$rid skill=$skillId args=$args pkg=$targetPackage")

        scope.launch {
            val req = AssistantRequest(
                text = args["text"].orEmpty(),
                args = args,
                source = AssistantSource.RELAY,
                requestId = rid,
            )
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                AssistantSkillRegistry.run(skillId, req)
            } ?: AssistantResult.fail(
                AssistantCodes.TIMEOUT,
                "任务超过 ${TIMEOUT_MS / 1000}s 未完成，已中止回执",
                skillId = skillId,
            )
            running = false
            currentSkill = ""
            sendReceipt(app, rid, result, targetPackage)
        }

        return AssistantResult.ok(
            skillId = "relay.submit",
            message = "已接收后台任务 $rid → $skillId",
            data = mapOf("requestId" to rid, "skillId" to skillId, "receiptAction" to RELAY_RESULT_ACTION),
        )
    }

    fun isBusy(): Boolean = running

    private fun sendReceipt(app: Context, rid: String, r: AssistantResult, targetPackage: String?) {
        runCatching {
            val i = Intent(RELAY_RESULT_ACTION).apply {
                if (!targetPackage.isNullOrBlank()) setPackage(targetPackage)
                putExtra(EXTRA_REQUEST_ID, rid)
                putExtra(EXTRA_OK, r.ok)
                putExtra(EXTRA_CODE, r.code)
                putExtra(EXTRA_MESSAGE, r.message)
                putExtra(EXTRA_SKILL_ID, r.skillId)
                putExtra(EXTRA_RESULT, r.wire())
                putExtra(EXTRA_SEALED_RESULT, r.sealedResultJson())
            }
            app.sendBroadcast(i)
            QuroDiag.log(TAG, "receipt rid=$rid ${r.wire()}")
        }.onFailure { QuroDiag.log(TAG, "receipt failed rid=$rid err=${it.javaClass.simpleName}: ${it.message}") }
    }
}
