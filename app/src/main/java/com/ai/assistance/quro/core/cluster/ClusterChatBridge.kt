package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.QuroMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 🔴🔴 集群 → 对话框 的投影桥（本次修「集群进入对话框你老是另开」）
 *
 * ## 症状与真因
 *
 * 用户报「集群进入对话框你老是另开」。查下来不是"另开了一个窗口"，而是更糟的：
 * **集群的发言根本没进对话框**。
 *
 * `ClusterTraceBridge` 把所有集群事件都转发到了 [com.ai.assistance.quro.core.agent.QuroAgentTrace]，
 * 那是**思维链/诊断面板**，和对话框的消息流是两套完全独立的东西。所以：
 * - 设置页点「开始」→ 集群在后台真跑了（真调模型、真闭环）；
 * - 但用户盯着对话框，看到的是**一个字的回复都没有**。
 *
 * 旧集群有 `ClusterChatProjector` 专门干投影，重写时被一起删了，而新的桥只接了 trace——
 * **这是重写时漏掉的最后一根线**。
 *
 * ## 为什么用 excludeFromLlm
 *
 * 集群角色发言要 `excludeFromLlm = true`（用户看得见、LLM 看不见）：
 * 它们已在各自引擎上下文跑完；塞回主 LLM 的 messages 会让主 LLM 以为
 * 「已经有人答过了」而偷懒不再自己回答（#182 已确立的语义，两边不能混）。
 *
 * ## 为什么用 addAll 批量写而不是逐条 add
 *
 * 集群一次闭环可能产生十几条发言，逐条 `add` = 十几次 `onMutated`
 * → 十几次落盘 + 十几次主线程重组（这正是 #184/#185 的 ANR/OOM 风暴成因）。
 * [QuroConversationStore.addAll] 只触发**一次**回调。
 *
 * ## 为什么需要 registerSink / unregisterSink
 *
 * store 是**会话级**的（每个 ViewModel 持一个），而引擎是**进程级**单例。
 * 用户切会话时旧 store 就失效了 —— 若还往里写，发言会落到上一个会话里，
 * 表现为「换个对话还在冒集群气泡」。所以订阅必须跟着当前 store 走。
 */
object ClusterChatBridge {

    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile private var sink: QuroConversationStore? = null

    /**
     * 绑定当前对话框。传 null 解绑（切会话 / 页面销毁时调）。
     *
     * 同一个引擎可能被反复绑定（用户进设置页再回主对话），每次都会取消上一次订阅，
     * 保证**同一时刻只有一个投影目标** —— 否则发言会被写进多个 store（重复气泡）。
     */
    @Volatile private var job: kotlinx.coroutines.Job? = null

    fun registerSink(store: QuroConversationStore?, engine: ClusterEngine) {
        // 先解绑旧的，避免重复订阅导致同一发言进多个 store
        job?.cancel()
        job = null
        sink = store
        if (store == null) return

        job = scope.launch {
            engine.events.collect { e ->
                val msgs = toMessages(e) ?: return@collect
                if (msgs.isEmpty()) return@collect
                // 再取一次 sink：collect 期间可能已被切会话换掉
                val target = sink ?: return@collect
                runCatching { target.addAll(msgs) }
            }
        }
    }

    fun unregisterSink() {
        job?.cancel()
        job = null
        sink = null
    }

    /**
     * 把集群事件翻译成对话框气泡。只处理**用户该看见**的事件：
     * 角色发言、裁决、产出、错误、重规划、收尾。
     * 纯内部事件（验收标准、点名、模型切换）留在 trace 面板，不占对话框。
     */
    private fun toMessages(e: ClusterEvent): List<QuroMessage>? = when (e) {
        is ClusterEvent.RoleUtterance -> listOf(
            msg(
                sender = nameOf(e.personaId),
                content = e.text.ifBlank { "（无输出）" },
                host = RoleRegistry.isHost(e.personaId),
            )
        )

        is ClusterEvent.Verdict -> listOf(
            msg(
                sender = "主持",
                content = buildString {
                    append(if (e.pass) "✅ 裁决通过" else "❌ 裁决不通过")
                    if (e.reason.isNotBlank()) append("：").append(e.reason)
                },
                host = true,
            )
        )

        is ClusterEvent.ArtifactProduced -> listOf(
            msg(
                sender = nameOf(e.personaId),
                content = "📎 产出：${e.title}",
                host = RoleRegistry.isHost(e.personaId),
            )
        )

        is ClusterEvent.Error -> listOf(
            msg(sender = "集群", content = "⚠ ${e.message}", host = false)
        )

        is ClusterEvent.Replanned -> listOf(
            msg(
                sender = "主持",
                content = "↻ 第 ${e.round} 次重规划：${e.reason}",
                host = true,
            )
        )

        is ClusterEvent.Closed -> listOf(
            msg(
                sender = "集群",
                content = buildString {
                    append("■ 任务结束（${e.reason.name}）")
                    if (!e.summary.isNullOrBlank()) append("\n\n").append(e.summary)
                },
                host = false,
            )
        )

        // Started / AcceptanceDefined / Planned / SpeakerSelected / ModelSwitched
        // → 只进 trace 面板，不占对话框（否则一次闭环能刷出十几条噪声气泡）
        else -> null
    }

    private fun msg(sender: String, content: String, host: Boolean) = QuroMessage(
        role = "assistant",
        content = content,
        senderName = if (host) "集群主持 · $sender" else "集群 · $sender",
        // 🔴 用户看得见、LLM 看不见（见文件头说明）
        excludeFromLlm = true,
    )

    /** 角色名优先取人格卡里的名字，退回 id；主持固定叫「主持」 */
    private fun nameOf(personaId: String): String {
        if (RoleRegistry.isHost(personaId)) return "主持"
        val ctx = runCatching { ClusterRuntime.ctx() }.getOrNull() ?: return personaId
        return runCatching {
            com.ai.assistance.quro.core.QuroPersonaRepository(ctx).get(personaId)?.name
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: personaId
    }
}