package com.ai.assistance.quro.core.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 把集群事件转发到 Zorv AI 已有的 QuroAgentTrace 上，
 * 这样多角色的讨论过程能直接渲染进现有的思维链 / 工具调用动画 UI，不需要新写一套界面。
 *
 * 在 Application 初始化时：
 *   ClusterTraceBridge.start(ClusterRuntime.get())
 */
object ClusterTraceBridge {

    private val scope = CoroutineScope(Dispatchers.Default)

    fun start(engine: ClusterEngine) {
        scope.launch {
            engine.events.collect { e ->
                // 【适配点 C】若 QuroAgentTrace 的实际方法名不同，改这一处
                runCatching {
                    when (e) {
                        is ClusterEvent.Started ->
                            trace(e.taskId, "主持接手任务：${e.goal}")
                        is ClusterEvent.AcceptanceDefined ->
                            trace(e.taskId, "验收标准：${e.items.joinToString("；")}")
                        is ClusterEvent.Planned ->
                            trace(e.taskId, "拆解为 ${e.count} 个子任务")
                        is ClusterEvent.SpeakerSelected ->
                            trace(e.taskId, "点名 ${nameOf(e.personaId)}（${e.reason}）")
                        is ClusterEvent.RoleUtterance ->
                            trace(e.taskId, "${nameOf(e.personaId)}：${e.text.take(160)}")
                        is ClusterEvent.ArtifactProduced ->
                            // 🔴 记产物正文字数：旧实现只记标题，于是
                            // 「产物本身是空的」与「产物没被记下来」在 trace 上长得一模一样。
                            trace(
                                e.taskId,
                                "${nameOf(e.personaId)} 产出：${e.title}" +
                                    if (e.body.isNotBlank()) "（${e.body.length} 字）" else "（⚠ 正文为空）"
                            )
                        is ClusterEvent.ToolInvoked ->
                            // #190：工具执行轨迹进 trace —— 排查「它到底动手了没」时，
                            // 这是唯一能区分「真执行」与「模型自称已执行」的证据。
                            trace(e.taskId, "${nameOf(e.personaId)} 调用工具 ${e.toolName}：${e.summary.take(120)}")
                        is ClusterEvent.Verdict ->
                            // 🔴 带上验收方（用户问过「关键是谁验收？」）
                            // 排查时这条最关键：结论是谁给的。
                            trace(
                                e.taskId,
                                "验收「${if (e.pass) "通过" else "不通过"}」" +
                                    " by ${e.verifier.ifBlank { "主持" }}" +
                                    "：${e.reason.take(120)}"
                            )
                        is ClusterEvent.ModelSwitched ->
                            trace(e.taskId, "模型切换 ${e.from} → ${e.to}（${e.reason}）")
                        is ClusterEvent.Error ->
                            trace(e.taskId, "⚠ ${e.message.take(160)}")
                        is ClusterEvent.Replanned ->
                            trace(e.taskId, "第 ${e.round} 次重规划：${e.reason.take(120)}")
                        // #192：能力核对轨迹 —— 排查「为什么这个角色能干这活」的唯一凭据。
                        // 用户看到「能力覆盖 2/5」才知道缺什么，而不是只看到一句"我来做"。
                        is ClusterEvent.CapabilityAudited ->
                            trace(e.taskId, "能力核对：${e.covered}/${e.total} 个子任务有人能接")
                        is ClusterEvent.CapabilityRemedied ->
                            trace(
                                e.taskId,
                                (if (e.ok) "能力已补：" else "能力补不上：") +
                                    "${e.ability.take(40)} → ${e.detail.take(120)}"
                            )
                        is ClusterEvent.Closed ->
                            trace(e.taskId, "任务关闭：${e.reason} ${e.summary ?: ""}")
                    }
                }
            }
        }
    }

    private fun trace(taskId: String, text: String) {
        // QuroAgentTrace 是现成的事件总线；这里只做单向文本转发
        com.ai.assistance.quro.core.agent.QuroAgentTrace.status(taskId, text, "")
    }

    private fun nameOf(personaId: String): String =
        if (RoleRegistry.isHost(personaId)) "主持" else personaId
}
