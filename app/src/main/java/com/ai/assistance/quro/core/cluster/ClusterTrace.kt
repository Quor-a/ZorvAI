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
                            trace(e.taskId, "${nameOf(e.personaId)} 产出：${e.title}")
                        is ClusterEvent.Verdict ->
                            trace(e.taskId, "裁决「${if (e.pass) "通过" else "不通过"}」：${e.reason.take(120)}")
                        is ClusterEvent.ModelSwitched ->
                            trace(e.taskId, "模型切换 ${e.from} → ${e.to}（${e.reason}）")
                        is ClusterEvent.Error ->
                            trace(e.taskId, "⚠ ${e.message.take(160)}")
                        is ClusterEvent.Replanned ->
                            trace(e.taskId, "第 ${e.round} 次重规划：${e.reason.take(120)}")
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
