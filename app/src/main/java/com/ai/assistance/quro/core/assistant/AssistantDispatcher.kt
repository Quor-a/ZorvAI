package com.ai.assistance.quro.core.assistant

import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.directive.AssistantDirectiveBus
import com.ai.assistance.quro.util.QuroDiag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 助手统一入口（单点路由）。
 *
 * 小布的 `PantanalRouterActivity`：**所有**外部/内部触发（文本指令、分享、选中文本、
 * 快捷磁贴、语音、广场点按、AI 工具调用、后台任务中转）都先进这里，由它决定：
 *
 * 1. 命中某个已注册技能 → 直接执行（快、确定、不烧模型 token）；
 * 2. 没命中 → 把原文投进对话信箱，交给 AI 自己用工具编排。
 *
 * 这样做的关键收益是**一件事只有一条路**：加新能力只需要往技能表里注册一项，
 * 六个入口同时生效，不会出现"广播能调、磁贴不能调"这种分叉。
 */
object AssistantDispatcher {
    private const val TAG = "assistant-router"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 异步提交一次调用（广播/磁贴这类回调不能阻塞的场景用）。
     * @param onDone 在后台线程回调，UI 侧自己切主线程
     */
    fun submit(ctx: Context, req: AssistantRequest, onDone: ((AssistantResult) -> Unit)? = null) {
        val app = ctx.applicationContext
        scope.launch {
            val r = runCatching { dispatch(app, req) }
                .getOrElse { AssistantResult.internalError("", it) }
            QuroDiag.log(TAG, "submit from=${req.source.wire} ${r.wire()}")
            onDone?.invoke(r)
        }
    }

    /**
     * 同步语义的分发（协程内调用）。
     *
     * 🔴 第一步永远是**闸门**：助手服务关了 / 该来源对应的通道关了，直接返回拒绝结果，
     * 绝不往下走到技能执行或 AI 投递。这是「关闭后任何情况下不允许启动」的唯一落点。
     *
     * 路由顺序：闸门 → 显式技能调用 → 关键词命中 → 交给 AI。
     */
    suspend fun dispatch(ctx: Context, req: AssistantRequest): AssistantResult {
        val denied = gateFor(ctx, req.source)
        if (denied != null) return denied

        val text = req.text.trim()
        if (text.isEmpty() && req.raw.isBlank()) {
            return AssistantResult.fail(AssistantCodes.NO_TEXT, "没有收到任何文本")
        }

        val skill = AssistantSkillRegistry.match(text)
        if (skill != null) {
            val args = if (req.args.isEmpty() && skill.keywords.any { text.length > it.length }) {
                // 把命中的关键词后面的部分当参数：`/terminal 跑 ls` → text="跑 ls"
                val kw = skill.keywords.filter { text.contains(it, true) }.maxByOrNull { it.length }
                if (kw != null) mergeArgs(req.args, "text" to text.replaceFirst(kw, "").trim())
                else req.args
            } else {
                req.args
            }
            val r = AssistantSkillRegistry.run(skill.id, req.copy(args = args))
            // 技能明确表示"我不确定，交给模型"时，继续往 AI 那条路走
            if (r.code == AssistantCodes.UNSUPPORTED && req.source != AssistantSource.AI) {
                return handoffToAi(ctx, req, reason = "skill ${skill.id} 需要模型编排")
            }
            return r
        }

        return handoffToAi(ctx, req, reason = "no skill matched")
    }

    /** 没命中技能：投进对话信箱，由 ChatScreen 消费后 vm.send。 */
    private fun handoffToAi(ctx: Context, req: AssistantRequest, reason: String): AssistantResult {
        val payload = req.text.ifBlank { req.raw }
        val ok = AssistantDirectiveBus.post(payload, "assistant:" + req.source.wire)
        QuroDiag.log(TAG, "handoff to ai reason=$reason ok=$ok text=${payload.take(120)}")
        if (!ok) {
            return AssistantResult.fail(AssistantCodes.NO_TEXT, "空文本，未投递给对话")
        }
        // 前台才拉起界面；后台（relay）场景只投信箱，避免突然弹窗打断用户
        if (req.source != AssistantSource.RELAY) {
            runCatching {
                ctx.startActivity(
                    Intent(ctx, QuroMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }.onFailure { QuroDiag.log(TAG, "open chat failed: ${it.message}") }
        }
        return AssistantResult.ok(
            message = "已交给 ZorvAI 处理",
            data = mapOf("routedTo" to "ai", "reason" to reason),
        )
    }

    /** 从任意 Intent 抽文本 + 判来源，统一成一个请求。 */
    fun requestOf(intent: Intent?, source: AssistantSource): AssistantRequest {
        if (intent == null) return AssistantRequest(source = source)
        val action = intent.action.orEmpty()
        val text = when (action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            else -> intent.getStringExtra("text")
                ?: intent.getStringExtra("directive")
                ?: intent.data?.getQueryParameter("text")
                ?: intent.data?.lastPathSegment
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: ""
        }
        val args = LinkedHashMap<String, String>()
        intent.extras?.keySet()?.forEach { k ->
            if (k == "text" || k == "directive") return@forEach
            val v = intent.extras?.get(k)
            if (v is String && v.isNotBlank()) args[k] = v
        }
        return AssistantRequest(
            text = text.trim(),
            args = args,
            source = source,
            raw = text,
            requestId = intent.getStringExtra(BackgroundTaskRelay.EXTRA_REQUEST_ID).orEmpty(),
        )
    }

    private fun mergeArgs(base: Map<String, String>, vararg pairs: Pair<String, String>): Map<String, String> {
        val m = LinkedHashMap(base)
        pairs.forEach { (k, v) -> if (v.isNotBlank() && !m.containsKey(k)) m[k] = v }
        return m
    }

    /**
     * 来源 → 需要检查的通道。
     *
     * `PLAZA` 不属于任何唤醒通道（是用户在本应用里自己点），只受总开关约束；
     * 其余来源一一对应设置页里的开关，关掉哪个就只有哪个失效。
     */
    private fun gateFor(ctx: Context, source: AssistantSource): AssistantResult? = when (source) {
        AssistantSource.PLAZA -> AssistantServiceState.gateServiceOnly(ctx)
        AssistantSource.DIRECTIVE -> AssistantServiceState.gate(ctx, AssistantChannel.DIRECTIVE)
        AssistantSource.SHARE -> AssistantServiceState.gate(ctx, AssistantChannel.SHARE)
        AssistantSource.TILE -> AssistantServiceState.gate(ctx, AssistantChannel.TILE)
        AssistantSource.VOICE -> AssistantServiceState.gate(ctx, AssistantChannel.VOICE_WAKEWORD)
        AssistantSource.AI -> AssistantServiceState.gate(ctx, AssistantChannel.AI_TOOL)
        AssistantSource.RELAY -> AssistantServiceState.gate(ctx, AssistantChannel.RELAY)
    }
}
