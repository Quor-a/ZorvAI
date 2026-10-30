package com.ai.assistance.quro.core.assistant

import android.content.Context
import com.ai.assistance.quro.util.QuroDiag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨设备 Agent 互联。
 *
 * **类型全部来自 `assets/proto/agents.proto` 原件**（见 [AgentProto]），本文件只做运行时：
 * 发现/连接/收发 + 事件总线。
 *
 * ## 现状（诚实标注，不含糊）
 * - 协议模型、事件总线、本地回环 Agent：**已可用**
 * - 真 NSD/BLE/RTC 发现：走 [AgentTransportAdapter] 实现，本仓**尚未提供任何实现**，
 *   因此 `startDiscovery()` 在没有注册 adapter 时会明确返回"没有实现"
 *   而不是假装在扫描——空列表 + 无原因是最糟的结果
 *
 * ## 与 proto 的对应关系
 * | proto | 本文件 |
 * |---|---|
 * | `InternalAgentClient` | [AgentDescriptor.client] |
 * | `AgentEvent` | 事件流 [events] 的元素类型 |
 * | `StrategyType` | [AgentDescriptor.strategy] / adapter 的 [AgentTransportAdapter.strategy] |
 * | `DeviceConnectionResult` | [DeviceConnectionResult] |
 *
 * 本仓扩展（proto 里没有）：[AgentDescriptor.capabilities] / `endpoint` / `lastSeenAt`。
 */
data class AgentDescriptor(
    /** proto: InternalAgentClient —— 身份与角色 */
    val client: InternalAgentClient,
    /** proto: AgentEvent.displayName */
    val displayName: String = "",
    /** proto: StrategyType —— 该 Agent 走哪条连接策略 */
    val strategy: AgentStrategy = AgentStrategy.NSD,
    // ─────────── 以下为本仓扩展（proto 无对应字段）───────────
    /** 本机可接的能力 id 列表，用于"这设备能不能干这件事"的本地决策 */
    val capabilities: List<String> = emptyList(),
    /** 端点：NSD 是 host:port，BLE 是 MAC，RTC 是 SDP 摘要 */
    val endpoint: String = "",
    val lastSeenAt: Long = System.currentTimeMillis(),
) {
    val agentId: String get() = client.agentId
    val role: AgentRole get() = client.role
    fun supports(skillId: String): Boolean = capabilities.isEmpty() || capabilities.contains(skillId)
}

/**
 * 传输适配器：真发现/真连接在这里实现，上层只认 [AgentLink]。
 *
 * 实现约定：
 * - 发现到设备 → `onFound`（内部会转成 [AgentEventType.AGENT_FOUND] 事件）
 * - 掉线 → `onLost`（转成 `AGENT_LOST`）
 * - 连接成功/失败各回调一次；**不要在这里做重试循环**（重试策略归 [AgentLink]）
 * - 大块数据请用 `InternalPayloadBuffer` 的进度字段，别在 `send` 里一把塞完
 */
interface AgentTransportAdapter {
    /** proto: StrategyType */
    val strategy: AgentStrategy

    fun startDiscovery(ctx: Context, onFound: (AgentDescriptor) -> Unit, onLost: (String) -> Unit)
    fun stopDiscovery()
    fun connect(ctx: Context, agent: AgentDescriptor): DeviceConnectionResult?
    fun disconnect(agentId: String)
    fun send(agentId: String, payload: InternalPayloadBuffer): Boolean
}

object AgentLink {
    private const val TAG = "assistant-agentlink"

    private val _agents = MutableStateFlow<List<AgentDescriptor>>(emptyList())
    /** 当前已知的 Agent（含自身回环）。 */
    val agents: StateFlow<List<AgentDescriptor>> = _agents.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(replay = 0, extraBufferCapacity = 32)
    /** proto 的 `AgentEvent` 事件流（9 种类型全支持）。 */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val adapters = LinkedHashMap<AgentStrategy, AgentTransportAdapter>()

    @Volatile
    private var discovering = false

    /** 本机 Agent id：包名 + `#self`（同机多用户/多实例也不会撞）。 */
    fun selfId(ctx: Context): String = ctx.packageName + "#self"

    /**
     * 注册自身。
     * 能力清单取自技能目录里"允许外部调用且可执行"的那些 —— 这也是 [AgentDescriptor.capabilities]
     * 存在的意义：别的设备据此决定要不要把活派过来。
     */
    fun registerSelf(ctx: Context): AgentDescriptor {
        val caps = AssistantSkillRegistry.all().filter { it.external && it.runnable }.map { it.id }
        val app = ctx.applicationContext
        val self = AgentDescriptor(
            client = InternalAgentClient(
                agentId = selfId(app),
                pkg = app.packageName,
                pid = android.os.Process.myPid(),
                appName = "ZorvAI",
                channelName = "assistant",
                role = AgentRole.PROVIDER,
                connectionId = 0L,
            ),
            displayName = "ZorvAI",
            // 本机自省不需要真传输，用 AUTO 表示"由上层自选"
            strategy = AgentStrategy.AUTO,
            capabilities = caps,
            endpoint = "local",
        )
        upsert(self)
        QuroDiag.log(TAG, "self registered caps=${caps.size} pid=${self.client.pid}")
        return self
    }

    fun registerAdapter(adapter: AgentTransportAdapter) {
        synchronized(this) { adapters[adapter.strategy] = adapter }
        QuroDiag.log(TAG, "adapter registered strategy=${adapter.strategy.wire}")
    }

    /** 当前已注册的传输策略（设置页/诊断用，避免"到底支不支持"靠猜）。 */
    fun availableStrategies(): List<AgentStrategy> = synchronized(this) { adapters.keys.toList() }

    fun isDiscovering(): Boolean = discovering

    /**
     * 启动发现。
     * @return 实际启动了的策略；空列表表示**没有任何传输实现**（调用方应据此提示用户）
     */
    fun startDiscovery(ctx: Context, strategies: List<AgentStrategy> = listOf(AgentStrategy.AUTO)): List<AgentStrategy> {
        if (discovering) return emptyList()
        val started = mutableListOf<AgentStrategy>()
        for (s in strategies) {
            // AUTO 表示"用所有已注册的"（proto 语义）
            val targets = if (s == AgentStrategy.AUTO) synchronized(this) { adapters.keys.toList() } else listOf(s)
            for (t in targets) {
                val a = synchronized(this) { adapters[t] } ?: continue
                runCatching {
                    a.startDiscovery(
                        ctx,
                        onFound = { d -> onFound(d) },
                        onLost = { id -> onLost(id) },
                    )
                    started += t
                    QuroDiag.log(TAG, "discovery started strategy=${t.wire}")
                }.onFailure {
                    // 真实症状：没有多播权限 / Wi-Fi 关闭 / ROM 阉割 NSD —— 显式报事件，
                    // 绝不让"发现不到设备"变成一个没有原因的空白列表
                    emit(
                        AgentEvent(
                            type = AgentEventType.AGENT_FOUND, // 失败也走同一总线由 errorCode 区分
                            errorCode = ProtoErrorCodes.FOR_DISCOVERY,
                            displayName = "discovery-failed:${t.wire}",
                            extension = AgentExtensionArgs(type = "error", args = (it.message ?: "").toByteArray()),
                        )
                    )
                    QuroDiag.log(TAG, "discovery failed strategy=${t.wire} err=${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
        discovering = started.isNotEmpty()
        if (!discovering) {
            QuroDiag.log(TAG, "startDiscovery: 没有任何已注册的传输实现（strategies=${strategies.map { it.wire }}）")
        }
        return started
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        synchronized(this) { adapters.values.toList() }.forEach { runCatching { it.stopDiscovery() } }
        QuroDiag.log(TAG, "stopDiscovery")
    }

    fun connect(ctx: Context, agentId: String): AssistantResult {
        val agent = _agents.value.find { it.agentId == agentId }
            ?: return AssistantResult.fail(AssistantCodes.SKILL_NOT_FOUND, "没有这个 Agent：$agentId", skillId = "link.connect")

        // 本机回环：不需要传输
        if (agent.endpoint == "local") {
            emit(AgentEvent(AgentEventType.AGENT_CONNECTED, agent.client, agent.displayName))
            return AssistantResult.ok("link.connect", "本地 Agent 已就绪", data = mapOf("agentId" to agentId))
        }

        val a = synchronized(this) { adapters[agent.strategy] }
            ?: return AssistantResult.fail(
                AssistantCodes.UNSUPPORTED,
                "还没有 ${agent.strategy.wire} 传输实现（已注册：${availableStrategies().map { it.wire }}）",
                skillId = "link.connect",
            )
        val r = runCatching { a.connect(ctx, agent) }.getOrElse {
            emit(
                AgentEvent(
                    type = AgentEventType.AGENT_DISCONNECTED,
                    client = agent.client,
                    errorCode = ProtoErrorCodes.FOR_CONNECTION,
                    extension = AgentExtensionArgs("error", (it.message ?: it.javaClass.simpleName).toByteArray()),
                )
            )
            null
        }
        return if (r != null) {
            emit(AgentEvent(AgentEventType.AGENT_CONNECTED, agent.client.copy(connectionId = r.connectionId), agent.displayName))
            AssistantResult.ok(
                "link.connect",
                "已连接 ${agent.displayName.ifBlank { agentId }}",
                data = mapOf(
                    "strategy" to agent.strategy.wire,
                    "connectionId" to r.connectionId,
                    "address" to r.address,
                    "ip" to r.ip,
                ),
            )
        } else {
            AssistantResult.fail("agent-connect-failed", "连接 $agentId 失败（详见 events 的 errorCode）", skillId = "link.connect")
        }
    }

    fun disconnect(agentId: String) {
        val agent = _agents.value.find { it.agentId == agentId } ?: return
        synchronized(this) { adapters[agent.strategy] }?.let { runCatching { it.disconnect(agentId) } }
        emit(AgentEvent(AgentEventType.AGENT_DISCONNECTED, agent.client, agent.displayName))
        QuroDiag.log(TAG, "disconnect $agentId")
    }

    /** 发一段数据（走 proto 的 InternalPayloadBuffer，带进度字段）。 */
    fun send(agentId: String, data: ByteArray, id: Int = 0): AssistantResult {
        val agent = _agents.value.find { it.agentId == agentId }
            ?: return AssistantResult.fail(AssistantCodes.SKILL_NOT_FOUND, "没有这个 Agent：$agentId", skillId = "link.send")
        val buf = InternalPayloadBuffer(
            client = agent.client,
            type = AgentPayloadType.BYTES,
            data = data,
            id = id,
            totalLength = data.size.toLong(),
            transferredLength = data.size.toLong(),
            status = AgentTransferStatus.SUCCESS,
        )
        if (agent.endpoint == "local") {
            emit(AgentEvent(AgentEventType.PAYLOAD_RECEIVE, agent.client, agent.displayName, payload = buf))
            return AssistantResult.ok("link.send", "已投递到本地总线", data = mapOf("bytes" to data.size))
        }
        val a = synchronized(this) { adapters[agent.strategy] }
            ?: return AssistantResult.fail(AssistantCodes.UNSUPPORTED, "还没有 ${agent.strategy.wire} 传输实现", skillId = "link.send")
        val ok = runCatching { a.send(agentId, buf) }.getOrDefault(false)
        return if (ok) {
            emit(AgentEvent(AgentEventType.PAYLOAD_RECEIVE, agent.client, agent.displayName, payload = buf))
            AssistantResult.ok("link.send", "已发送 ${data.size}B")
        } else {
            emit(
                AgentEvent(
                    type = AgentEventType.PAYLOAD_RECEIVE,
                    client = agent.client,
                    payload = buf.copy(status = AgentTransferStatus.FAILURE),
                    errorCode = ProtoErrorCodes.FOR_TRANSPORT,
                )
            )
            AssistantResult.fail("agent-send-failed", "发送失败", skillId = "link.send")
        }
    }

    fun peers(): List<AgentDescriptor> = _agents.value

    // ── 内部 ────────────────────────────────────────────────

    private fun onFound(d: AgentDescriptor) {
        upsert(d)
        emit(AgentEvent(AgentEventType.AGENT_FOUND, d.client, d.displayName))
        QuroDiag.log(TAG, "found ${d.agentId} strategy=${d.strategy.wire}")
    }

    private fun onLost(agentId: String) {
        val d = _agents.value.find { it.agentId == agentId }
        _agents.value = _agents.value.filterNot { it.agentId == agentId }
        emit(AgentEvent(AgentEventType.AGENT_LOST, d?.client ?: InternalAgentClient(agentId = agentId), d?.displayName.orEmpty()))
        QuroDiag.log(TAG, "lost $agentId")
    }

    private fun emit(e: AgentEvent) {
        _events.tryEmit(e)
    }

    private fun upsert(d: AgentDescriptor) {
        _agents.value = (_agents.value.filterNot { it.agentId == d.agentId } + d)
            .sortedBy { it.displayName.lowercase() }
    }
}

/** proto: `results.proto / ErrorCode` 的数值（AGENT 相关只用到这几个）。 */
internal object ProtoErrorCodes {
    const val UNKNOWN = 0
    const val FOR_DISCOVERY = 1
    const val FOR_CONNECTION = 2
    const val FOR_TRANSPORT = 3
    const val FOR_DATA_BUS = 4
    const val FOR_DEVICE_MANAGER = 5
    const val FOR_NETWORK_MANAGER = 6
}
