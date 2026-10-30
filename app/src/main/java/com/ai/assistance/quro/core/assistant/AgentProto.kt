package com.ai.assistance.quro.core.assistant

/**
 * 跨设备 Agent 协议 —— **直接从 `Breeno.apk` 拷来的 `agents.proto` 原件逐字段照抄**。
 *
 * 契约文件在本仓：`app/src/main/assets/proto/agents.proto`（包 `com.oplus.pantaconnect.agents`，
 * proto3）。本文件不是"参考它设计的"，是把它的 message / enum 一个值一个值搬成 Kotlin：
 *
 * - `InternalAgentClient`（agentId / pkg / pid / appName / channelName / role / connectionId）
 * - `AgentEvent.EventType` 的 **9 个值**（含 PAYLOAD_UPDATE / PAYLOAD_REQUEST /
 *   EXTENSION_EVENT / DISPLAY_DEVICES_CHANGE —— 这四个此前我漏了）
 * - `StrategyType`（BLE / NSD / BASED_CONNECT / RTC / AUTO —— 含基座连接与 AUTO）
 * - `ConnectType`、`ConnectScheduleStrategyType`、`OpenConnectionMode`、`InternalPairAction`、
 *   `InternalPayloadType`、`InternalPayloadBuffer.TransferStatus`、`InternalDiscoverableDeviceLevel`
 *
 * 🔴 **枚举值的排列顺序按 proto 的编号来**（0,1,2…），不要重排：这些值会走 wire，
 * 顺序错了等于协议版本错。
 *
 * ## 哪些是我们自己的扩展（proto 里没有，必须标注清楚）
 * proto 只描述「谁是 Agent、怎么连、收到什么」。它**没有**：
 * - `capabilities`（本机能接哪些能力 id）→ 本仓扩展，用于"这个设备能不能干这件事"的本地决策；
 * - `endpoint` / `lastSeenAt` → 本仓扩展，给 UI 展示用。
 * 扩展字段一律带 `本仓扩展` 注释，避免以后有人误以为它们来自协议。
 */
enum class AgentRole(val wire: String) {
    /** proto: Role.Provider = 0 */
    PROVIDER("Provider"),

    /** proto: Role.Seeker = 1 */
    SEEKER("Seeker"),
}

/**
 * proto: `AgentEvent.EventType`，**9 个值一个不少**。
 *
 * 注意 `PAYLOAD_RECEIVE / PAYLOAD_UPDATE / PAYLOAD_REQUEST` 是三个不同方向/语义的事件，
 * 我此前只写了一个含糊的 "AGENT_PAYLOAD"，那等于丢掉协议一半的表意能力。
 */
enum class AgentEventType(val wire: String) {
    AGENT_FOUND("AGENT_FOUND"),
    AGENT_CONNECTED("AGENT_CONNECTED"),
    AGENT_DISCONNECTED("AGENT_DISCONNECTED"),
    PAYLOAD_RECEIVE("PAYLOAD_RECEIVE"),
    PAYLOAD_UPDATE("PAYLOAD_UPDATE"),
    PAYLOAD_REQUEST("PAYLOAD_REQUEST"),
    EXTENSION_EVENT("EXTENSION_EVENT"),
    AGENT_LOST("AGENT_LOST"),
    DISPLAY_DEVICES_CHANGE("DISPLAY_DEVICES_CHANGE"),
}

/** proto: `StrategyType`（BLE=0, NSD=1, BASED_CONNECT=2, RTC=3, AUTO=4）。 */
enum class AgentStrategy(val wire: String) {
    BLE("BLE"),
    NSD("NSD"),

    /** 基座连接（同账号设备常走这条，比 NSD 稳） */
    BASED_CONNECT("BASED_CONNECT"),
    RTC("RTC"),

    /** 由系统按网络/电量/历史成功率自选 */
    AUTO("AUTO"),
}

/** proto: `ConnectScheduleStrategyType` —— 多设备抢连时的调度策略。 */
enum class ConnectScheduleStrategy(val wire: String) {
    PREEMPT("PREEMPT"),
    FIFO("FIFO"),
    PRIORITY("PRIORITY"),
}

/** proto: `ConnectType`。 */
enum class AgentConnectType(val wire: String) {
    GATT("GATT"),
    SPP("SPP"),
    SPP_INSECURE("SPP_INSECURE"),
    P2P("P2P"),
    WLAN("WLAN"),
    USB("USB"),
    RTC_CONNECT("RTC_CONNECT"),
    NONE("NONE"),
    NETWORK_SETUP("NETWORK_SETUP"),
    TV_WLAN_P2P("TV_WLAN_P2P"),
}

/** proto: `InternalPayloadType`。 */
enum class AgentPayloadType(val wire: String) {
    BYTES("BYTES"),
    STREAM("STREAM"),
    FILE("FILE"),
}

/** proto: `InternalPayloadBuffer.TransferStatus`。 */
enum class AgentTransferStatus(val wire: String) {
    SUCCESS("SUCCESS"),
    FAILURE("FAILURE"),
    CANCELED("CANCELED"),
    IN_PROGRESS("IN_PROGRESS"),
}

/** proto: `InternalDiscoverableDeviceLevel`。 */
enum class AgentDiscoverableLevel(val wire: String) {
    SAME_ACCOUNT_DEVICE("SAME_ACCOUNT_DEVICE"),
    ALL_DEVICE("ALL_DEVICE"),
}

/** proto: `OpenConnectionMode`。 */
enum class AgentOpenConnectionMode(val wire: String) {
    CONNECT_AGENT("CONNECT_AGENT"),
    PAIR_ONLY("PAIR_ONLY"),
    CONNECT_CHANNEL_ONLY("CONNECT_CHANNEL_ONLY"),
}

/** proto: `InternalPairAction`。 */
enum class AgentPairAction(val wire: String) {
    ACCEPT("ACCEPT"),
    REJECT("REJECT"),
    PIN_AUTH("PIN_AUTH"),
    CUSTOMIZE_AUTH("CUSTOMIZE_AUTH"),
}

/** proto: `ExtensionArgs`。 */
data class AgentExtensionArgs(
    val type: String = "",
    val args: ByteArray = ByteArray(0),
) {
    // ByteArray 的 equals/hashCode 必须自己实现，否则两个内容相同的对象永远不相等
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentExtensionArgs) return false
        return type == other.type && args.contentEquals(other.args)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + args.contentHashCode()
}

/**
 * proto: `InternalAgentClient` —— 一个 Agent 连接的身份。
 *
 * @param connectionId 由服务端分配的连接号；未连接时为 0
 */
data class InternalAgentClient(
    val agentId: String = "",
    val pkg: String = "",
    val pid: Int = 0,
    val appName: String = "",
    val channelName: String = "",
    val role: AgentRole = AgentRole.PROVIDER,
    val connectionId: Long = 0L,
)

/** proto: `InternalPayloadBuffer` —— 带进度的大块数据（BYTES/STREAM/FILE）。 */
data class InternalPayloadBuffer(
    val client: InternalAgentClient = InternalAgentClient(),
    val type: AgentPayloadType = AgentPayloadType.BYTES,
    val data: ByteArray = ByteArray(0),
    val id: Int = 0,
    val totalLength: Long = 0L,
    val transferredLength: Long = 0L,
    val status: AgentTransferStatus = AgentTransferStatus.SUCCESS,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InternalPayloadBuffer) return false
        return client == other.client && type == other.type && data.contentEquals(other.data) &&
            id == other.id && totalLength == other.totalLength &&
            transferredLength == other.transferredLength && status == other.status
    }

    override fun hashCode(): Int {
        var r = client.hashCode()
        r = 31 * r + type.hashCode()
        r = 31 * r + data.contentHashCode()
        r = 31 * r + id
        r = 31 * r + totalLength.hashCode()
        r = 31 * r + transferredLength.hashCode()
        r = 31 * r + status.hashCode()
        return r
    }

    /** 传输进度 0..1（totalLength 为 0 时返回 1，避免除零）。 */
    val progress: Float
        get() = if (totalLength <= 0L) 1f else (transferredLength.toDouble() / totalLength).toFloat().coerceIn(0f, 1f)
}

/**
 * proto: `AgentEvent`。
 *
 * @param errorCode 连接/传输失败时非 0；具体含义按 `results.proto` 的 `ErrorCode` 解释
 */
data class AgentEvent(
    val type: AgentEventType,
    val client: InternalAgentClient = InternalAgentClient(),
    val displayName: String = "",
    val extension: AgentExtensionArgs = AgentExtensionArgs(),
    val payload: InternalPayloadBuffer? = null,
    val errorCode: Int = 0,
) {
    /** 单行 wire：写日志/诊断包用，字段名与 proto 一致。 */
    fun wire(): String {
        val sb = StringBuilder()
        sb.append("type=").append(type.wire)
        if (client.agentId.isNotEmpty()) sb.append(" agentId=").append(client.agentId)
        if (client.pkg.isNotEmpty()) sb.append(" pkg=").append(client.pkg)
        if (displayName.isNotEmpty()) sb.append(" displayName=").append(displayName)
        if (extension.type.isNotEmpty()) sb.append(" extension=").append(extension.type)
        payload?.let {
            sb.append(" payload=").append(it.type.wire)
            sb.append("/").append(it.status.wire)
            sb.append(" ").append((it.progress * 100).toInt()).append("%")
        }
        if (errorCode != 0) sb.append(" errorCode=").append(errorCode)
        return sb.toString()
    }
}

/** proto: `deviceconnections.proto / DeviceConnectionResult`。 */
data class DeviceConnectionResult(
    val address: String = "",
    val ip: String = "",
    val connectionId: Long = 0L,
)
