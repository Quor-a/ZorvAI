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
 * 协议模型对齐小布的 `agents.proto`：Agent 有 **id / 展示名 / 能力清单 / 传输方式 / 端点**，
 * 生命周期事件是 Found → Connected → Payload… → Disconnected 五态，传输策略有
 * **BLE / NSD（局域网服务发现）/ RTC** 三条，由能力与网络条件择优。
 *
 * 现状（诚实标注）：**协议与总线已就绪，本地回环 Agent 可用**；真正的 NSD/BLE/RTC
 * 发现要走 [AgentTransportAdapter] 实现（权限、多播、编解码都是独立工作量）。
 * 先把契约固定下来，后续接哪个传输都不用改上层调用方——上层只认 [AgentLink]。
 */
enum class AgentTransport(val wire: String) {
    /** 进程内/同机回环（自身能力自省、测试） */
    LOOPBACK("loopback"),

    /** 局域网服务发现（NSD/mDNS），适合同 Wi-Fi 下无配对快速发现 */
    NSD("nsd"),

    /** 蓝牙低功耗，适合无网络/低功耗常连 */
    BLE("ble"),

    /** 实时音视频/数据通道（WebRTC），适合大流量与穿透 */
    RTC("rtc"),
}

/**
 * 一个可被互联的 Agent。
 * @param capabilities 该 Agent 能接的指令 id 列表（对应 [AssistantSkill.id]）
 * @param endpoint 传输相关端点：NSD 是 `host:port`，BLE 是 MAC，RTC 是 SDP offer 摘要
 */
data class AgentDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: List<String> = emptyList(),
    val transport: AgentTransport = AgentTransport.LOOPBACK,
    val endpoint: String = "",
    val lastSeenAt: Long = System.currentTimeMillis(),
    val trusted: Boolean = false,
) {
    fun supports(skillId: String): Boolean = capabilities.isEmpty() || capabilities.contains(skillId)
}

/** 五态生命周期事件（对齐 agents.proto 的事件名）。 */
sealed class AgentLinkEvent(val wire: String) {
    data class Found(val agent: AgentDescriptor) : AgentLinkEvent("AGENT_FOUND")
    data class Lost(val agentId: String) : AgentLinkEvent("AGENT_LOST")
    data class Connected(val agentId: String, val transport: AgentTransport) : AgentLinkEvent("AGENT_CONNECTED")
    data class Disconnected(val agentId: String, val reason: String) : AgentLinkEvent("AGENT_DISCONNECTED")
    data class Payload(val agentId: String, val data: String) : AgentLinkEvent("AGENT_PAYLOAD")
    data class Failed(val agentId: String, val code: String, val message: String) :
        AgentLinkEvent("AGENT_FAILED")
}

/**
 * 传输适配器：真发现/真连接在这里实现，上层只认 [AgentLink]。
 *
 * 实现方约定：发现到设备就 [onFound]，掉线就 [onLost]；连接成功/失败回调各一次；
 * 不要在这里做重试循环（重试策略归 [AgentLink]）。
 */
interface AgentTransportAdapter {
    val transport: AgentTransport
    fun startDiscovery(ctx: Context, onFound: (AgentDescriptor) -> Unit, onLost: (String) -> Unit)
    fun stopDiscovery()
    fun connect(ctx: Context, agent: AgentDescriptor): Boolean
    fun disconnect(agentId: String)
    fun send(agentId: String, payload: String): Boolean
}

object AgentLink {
    private const val TAG = "assistant-agentlink"

    private val _agents = MutableStateFlow<List<AgentDescriptor>>(emptyList())
    /** 当前已知的 Agent（含自身回环）。 */
    val agents: StateFlow<List<AgentDescriptor>> = _agents.asStateFlow()

    private val _events = MutableSharedFlow<AgentLinkEvent>(replay = 0, extraBufferCapacity = 32)
    /** 生命周期事件流，UI / 日志订阅。 */
    val events: SharedFlow<AgentLinkEvent> = _events.asSharedFlow()

    private val adapters = LinkedHashMap<AgentTransport, AgentTransportAdapter>()

    @Volatile
    private var discovering = false

    /** 本机 Agent id：用包名占位（同机多用户/多实例也不会撞）。 */
    fun selfId(ctx: Context): String = ctx.packageName + "#self"

    /** 注册自身：能力清单直接取自技能目录里"允许外部调用"的那些。 */
    fun registerSelf(ctx: Context): AgentDescriptor {
        val caps = AssistantSkillRegistry.all().filter { it.external && it.runnable }.map { it.id }
        val self = AgentDescriptor(
            id = selfId(ctx),
            displayName = "ZorvAI",
            capabilities = caps,
            transport = AgentTransport.LOOPBACK,
            endpoint = "local",
            trusted = true,
        )
        upsert(self)
        QuroDiag.log(TAG, "self registered caps=${caps.size}")
        return self
    }

    fun registerAdapter(adapter: AgentTransportAdapter) {
        synchronized(this) { adapters[adapter.transport] = adapter }
        QuroDiag.log(TAG, "adapter registered transport=${adapter.transport.wire}")
    }

    fun startDiscovery(ctx: Context, transports: List<AgentTransport> = listOf(AgentTransport.NSD)) {
        if (discovering) return
        discovering = true
        for (t in transports) {
            val a = synchronized(this) { adapters[t] } ?: continue
            runCatching {
                a.startDiscovery(
                    ctx,
                    onFound = { d ->
                        upsert(d)
                        _events.tryEmit(AgentLinkEvent.Found(d))
                        QuroDiag.log(TAG, "found ${d.id} via ${d.transport.wire}")
                    },
                    onLost = { id ->
                        remove(id)
                        _events.tryEmit(AgentLinkEvent.Lost(id))
                        QuroDiag.log(TAG, "lost $id")
                    },
                )
            }.onFailure {
                // 真实症状：没有多播权限 / Wi-Fi 关闭 / ROM 阉割了 NSD —— 显式记下来，
                // 绝不让"发现不到设备"变成一个没有原因的空白列表。
                _events.tryEmit(AgentLinkEvent.Failed("", "agent-discovery-failed", "${t.wire}: ${it.message}"))
                QuroDiag.log(TAG, "discovery start failed transport=${t.wire} err=${it.javaClass.simpleName}: ${it.message}")
            }
        }
        QuroDiag.log(TAG, "startDiscovery transports=${transports.map { it.wire }} adapters=${adapters.keys.map { it.wire }}")
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        synchronized(this) { adapters.values.toList() }.forEach { runCatching { it.stopDiscovery() } }
        QuroDiag.log(TAG, "stopDiscovery")
    }

    fun connect(ctx: Context, agentId: String): AssistantResult {
        val agent = _agents.value.find { it.id == agentId }
            ?: return AssistantResult.fail(AssistantCodes.SKILL_NOT_FOUND, "没有这个 Agent：$agentId", skillId = "link.connect")
        if (agent.transport == AgentTransport.LOOPBACK) {
            _events.tryEmit(AgentLinkEvent.Connected(agentId, AgentTransport.LOOPBACK))
            return AssistantResult.ok("link.connect", "本地 Agent 已就绪", data = mapOf("agentId" to agentId))
        }
        val a = synchronized(this) { adapters[agent.transport] }
            ?: return AssistantResult.fail(
                AssistantCodes.UNSUPPORTED,
                "还没有 ${agent.transport.wire} 传输实现",
                skillId = "link.connect",
            )
        val ok = runCatching { a.connect(ctx, agent) }.getOrElse {
            _events.tryEmit(AgentLinkEvent.Failed(agentId, "agent-connect-failed", it.message ?: it.javaClass.simpleName))
            false
        }
        return if (ok) {
            _events.tryEmit(AgentLinkEvent.Connected(agentId, agent.transport))
            AssistantResult.ok("link.connect", "已连接 $agentId", data = mapOf("transport" to agent.transport.wire))
        } else {
            AssistantResult.fail("agent-connect-failed", "连接 $agentId 失败", skillId = "link.connect")
        }
    }

    fun send(agentId: String, payload: String): AssistantResult {
        val agent = _agents.value.find { it.id == agentId }
            ?: return AssistantResult.fail(AssistantCodes.SKILL_NOT_FOUND, "没有这个 Agent：$agentId", skillId = "link.send")
        if (agent.transport == AgentTransport.LOOPBACK) {
            _events.tryEmit(AgentLinkEvent.Payload(agentId, payload))
            return AssistantResult.ok("link.send", "已投递到本地总线", data = mapOf("bytes" to payload.length))
        }
        val a = synchronized(this) { adapters[agent.transport] }
            ?: return AssistantResult.fail(AssistantCodes.UNSUPPORTED, "还没有 ${agent.transport.wire} 传输实现", skillId = "link.send")
        val ok = runCatching { a.send(agentId, payload) }.getOrDefault(false)
        return if (ok) AssistantResult.ok("link.send", "已发送 ${payload.length}B")
        else AssistantResult.fail("agent-send-failed", "发送失败", skillId = "link.send")
    }

    fun peers(): List<AgentDescriptor> = _agents.value

    private fun upsert(d: AgentDescriptor) {
        _agents.value = (_agents.value.filterNot { it.id == d.id } + d)
            .sortedBy { it.displayName.lowercase() }
    }

    private fun remove(id: String) {
        _agents.value = _agents.value.filterNot { it.id == id }
    }
}
