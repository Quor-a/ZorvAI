package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.cms.AuthorizationLevel

/**
 * CMS v2 执行引擎（语义能力运行时 · 执行层）。
 *
 * 统一执行入口：包装 [QuroCmsExecutor] 的仲裁 + 派发，额外产出**结构化结果** [CmsExecResult]
 * （成功 / 退出码 / stdout / stderr / 产物 / 耗时），并把整次执行记录进 [CmsStateStore]
 * （任务终态 + 日志），让 AI 经 cms_status / cms_logs / cms_result 回查，
 * 彻底解决「AI 执行拿不到结构化结果、只拿到一句文本」的痛点。
 */
data class CmsExecResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val artifacts: List<String> = emptyList(),
    val durationMs: Long,
    val taskId: String,
)

object CmsExecutionEngine {

    /**
     * 执行一次能力。返回结构化结果并落状态系统。
     * @param taskId 可选外部任务 id（AI 工具先 newTask 再执行，便于回查）；为空则内部新建。
     */
    suspend fun execute(
        context: Context,
        module: QuroCmsModule,
        cap: QuroCmsCapability,
        args: Map<String, String>,
        uiRequest: suspend (QuroCmsPermission) -> AuthorizationLevel?,
        taskId: String? = null,
    ): CmsExecResult {
        CmsStateStore.init(context)
        val tid = taskId ?: CmsStateStore.newTask("call", "${module.id}:${cap.id}")
        CmsStateStore.updateTask(tid, 10, "执行能力 ${cap.id}（${cap.actionType}）")
        CmsStateStore.setRunning(module.id, true)
        val t0 = System.currentTimeMillis()
        val res: String = try {
            QuroCmsExecutor(context).execute(module, cap, args, uiRequest)
        } catch (e: Exception) {
            "⛔ 执行异常：${e.message}"
        }
        val dur = System.currentTimeMillis() - t0
        val ok = !res.startsWith("⛔")
        val exitCode = if (ok) 0 else 1
        CmsStateStore.appendLog(module.id, "→ ${cap.id}: ${if (ok) "成功" else "失败"} (${dur}ms)")
        CmsStateStore.appendLog(module.id, res.take(2000))
        CmsStateStore.finishTask(tid, ok, res.take(200), exitCode, res, "", dur)
        CmsStateStore.setRunning(module.id, false)
        return CmsExecResult(ok, exitCode, res, "", emptyList(), dur, tid)
    }
}
