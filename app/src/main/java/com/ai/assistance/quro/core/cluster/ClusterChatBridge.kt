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

    /**
     * 工具调用合流窗口（毫秒）。窗口内的多次工具调用汇总成一条气泡，
     * 避免一次闭环调十几个工具把对话框刷爆（详见 toMessages 的ToolInvoked 分支）。
     */
    private const val TOOL_MERGE_WINDOW_MS = 3000L

    /**
     * 产物正文在气泡里最多展示多少字。
     *
     * 🔴 为什么不全文贴：产物动辄上万字（集群跑 14 轮就是），全贴会把对话框撑爆，
     * 反而又是「输出不完整」——用户要滚动十几屏才看到结论。截断 + 标注原长度，
     * 既看得到内容、也看得出「后面还有」。
     */
    private const val BODY_PREVIEW = 1200

    private val toolTrace = ArrayList<Pair<String, String>>()
    private var lastToolBucket = -1L

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

        // 🔴 2026-10-08 补验收方身份。用户直接问过「关键是谁验收？」——
        // 旧气泡只有 ✅/❌ 加一句理由，看不出是评审角色判的还是主持代验的。
        // 验收方不同结论的可信度完全不同，必须让人看得见。
        is ClusterEvent.Verdict -> listOf(
            msg(
                sender = e.verifier.ifBlank { "主持" },
                content = buildString {
                    append(if (e.pass) "✅ 验收通过" else "❌ 验收未通过")
                    if (e.verifier.isNotBlank()) append("（验收方：").append(e.verifier).append("）")
                    if (e.reason.isNotBlank()) append("：").append(e.reason)
                },
                host = true,
            )
        )

        // 🔴 2026-10-08 修「集群在对话框输出的内容不是正常的文本内容和产物」。
        //
        // 旧实现只投影 `"📎 产出：${e.title}"` —— 标题有、**产物正文一个字都没有**。
        // 集群跑了 14 轮真在干活，用户在对话框看到的却是十几条只有标题的空气泡，
        // 于是「跑没跑」完全无法判断。这是「输出不完整」最直接的证据。
        //
        // 现在带上正文：超长则截断（对话框不是产物仓库），并明确告诉用户内容在哪。
        is ClusterEvent.ArtifactProduced -> listOf(
            msg(
                sender = nameOf(e.personaId),
                content = buildString {
                    append("📎 产出：").append(e.title)
                    val body = e.body.trim()
                    if (body.isNotEmpty()) {
                        append("\n\n")
                        if (body.length > BODY_PREVIEW) {
                            append(body.take(BODY_PREVIEW))
                            append("\n… （全文 ").append(body.length).append(" 字，已截断）")
                        } else {
                            append(body)
                        }
                    }
                },
                host = RoleRegistry.isHost(e.personaId),
            )
        )

        is ClusterEvent.Error -> listOf(
            msg(sender = "集群", content = "⚠ ${e.message}", host = false)
        )

        // #190：工具执行轨迹。ReAct 之后「模型说自己做了什么」和「真做了什么」
        // 在最终文本里长得一模一样 —— 没有这一条，用户无从判断集群是在执行还是在编。
        //
        // 但**不能**每个工具一条气泡：一次闭环可能调十几个工具，会把对话框刷爆。
        // 做法：每 TOOL_MERGE_WINDOW_MS 只发一条汇总气泡（列出这一串调过的工具），
        // 窗口内的后续调用只累积、不再发泡。
        is ClusterEvent.ToolInvoked -> {
            val key = e.ts / TOOL_MERGE_WINDOW_MS
            val bucket = synchronized(toolTrace) {
                if (lastToolBucket != key) {
                    lastToolBucket = key
                    toolTrace.clear()
                }
                toolTrace += e.toolName to e.summary
                toolTrace.toList()
            }
            // 只有当累积的调用数 ≥ 3（说明确实是一串动作）时才发汇总泡；
            // 1-2 个工具的场合走上面各自的分支更清楚。
            if (bucket.size >= 3) {
                listOf(
                    msg(
                        sender = nameOf(e.personaId),
                        content = buildString {
                            append("🔧 连续调用 ${bucket.size} 个工具：")
                            append(bucket.joinToString(" → ") { it.first })
                            val last = bucket.last().second
                            if (last.isNotBlank()) append("\n└ ").append(last.take(300))
                        },
                        host = RoleRegistry.isHost(e.personaId),
                    )
                )
            } else {
                listOf(
                    msg(
                        sender = nameOf(e.personaId),
                        content = "🔧 调用工具 `${e.toolName}`" +
                            if (e.summary.isNotBlank()) "\n└ ${e.summary.take(300)}" else "",
                        host = RoleRegistry.isHost(e.personaId),
                    )
                )
            }
        }

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