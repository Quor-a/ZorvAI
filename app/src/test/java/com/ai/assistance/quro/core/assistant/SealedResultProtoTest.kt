package com.ai.assistance.quro.core.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * `results.proto` 引用契约测试。
 *
 * ## 为什么必须有
 * 这两个文件是从 `Breeno.apk` 里**拷出来的协议**（`app/src/main/assets/proto/results.proto`）。
 * 一旦有人改了字段名、重排了枚举编号、或者"顺手"加个顶层字段，外部按协议解析的调用方就全挂，
 * 而这种错**本地完全不会报错**——只会静默返回一个对方读不懂的 JSON。
 *
 * 所以这里把三件事钉死：
 * 1. 顶层字段名必须就是 `resultCode / data / errorCode / message`（+ 两个编号辅助字段）；
 * 2. 枚举 wire 名与 Number 与 proto 完全一致（SUCCESS=1、ERROR=2、CANCELLED=3；
 *    ERROR_CODE_FOR_CONNECTION=2 等）；
 * 3. `data` 必须是 Base64 且能解回我们原来的 data JSON。
 */
class SealedResultProtoTest {

    @Test
    fun successMapsToProtoSuccess() {
        val r = AssistantResult.ok("t.ok", "好了", mapOf("k" to 1))
        val j = JSONObject(r.sealedResultJson())
        assertEquals("SUCCESS", j.getString("resultCode"))
        assertEquals(1, j.getInt("resultCodeNumber"))
        assertEquals("ERROR_CODE_UNKNOWN", j.getString("errorCode"))
        assertEquals(0, j.getInt("errorCodeNumber"))
        // 细码与技能 id 必须留在 message 里，不能丢
        assertTrue(j.getString("message").contains("ok"))
        assertTrue(j.getString("message").contains("skill=t.ok"))
    }

    @Test
    fun dataIsBase64OfOriginalJson() {
        val r = AssistantResult.ok("t.data", data = mapOf("count" to 3, "name" to "查手机"))
        val j = JSONObject(r.sealedResultJson())
        val decoded = String(Base64.getDecoder().decode(j.getString("data")), Charsets.UTF_8)
        val back = JSONObject(decoded)
        assertEquals(3, back.getInt("count"))
        assertEquals("查手机", back.getString("name"))
    }

    @Test
    fun timeoutMapsToCancelledRatherThanError() {
        // 超时/忙 = 对方等了但没等到，语义上是取消而非硬错误
        val r = AssistantResult.fail(AssistantCodes.TIMEOUT, "超时了")
        assertEquals(ProtoResultCode.CANCELLED, r.protoResultCode())
        val j = JSONObject(r.sealedResultJson())
        assertEquals("CANCELLED", j.getString("resultCode"))
        assertEquals(3, j.getInt("resultCodeNumber"))
        assertEquals("ERROR_CODE_FOR_TRANSPORT", j.getString("errorCode"))
    }

    @Test
    fun errorCodesAreClassifiedBySubsystem() {
        // proto 的 ErrorCode 是按子系统分类的 —— 这是它排障价值最高的地方
        assertEquals(ProtoErrorCode.FOR_CONNECTION, protoErrorCodeOf("agent-connect-failed"))
        assertEquals(ProtoErrorCode.FOR_TRANSPORT, protoErrorCodeOf(AssistantCodes.BUSY))
        assertEquals(ProtoErrorCode.FOR_DATA_BUS, protoErrorCodeOf(AssistantCodes.RELAY_DUPLICATE))
        assertEquals(ProtoErrorCode.FOR_DEVICE_MANAGER, protoErrorCodeOf(AssistantCodes.ENV_NOT_READY))
        assertEquals(ProtoErrorCode.UNKNOWN, protoErrorCodeOf("some-brand-new-code"))
    }

    @Test
    fun enumWiresAndNumbersMatchProtoFile() {
        // 与 assets/proto/results.proto 逐值对齐
        assertEquals(0 to "UNKNOWN", ProtoResultCode.UNKNOWN.number to ProtoResultCode.UNKNOWN.wire)
        assertEquals(1 to "SUCCESS", ProtoResultCode.SUCCESS.number to ProtoResultCode.SUCCESS.wire)
        assertEquals(2 to "ERROR", ProtoResultCode.ERROR.number to ProtoResultCode.ERROR.wire)
        assertEquals(3 to "CANCELLED", ProtoResultCode.CANCELLED.number to ProtoResultCode.CANCELLED.wire)

        assertEquals(0 to "ERROR_CODE_UNKNOWN", ProtoErrorCode.UNKNOWN.number to ProtoErrorCode.UNKNOWN.wire)
        assertEquals(1 to "ERROR_CODE_FOR_DISCOVERY", ProtoErrorCode.FOR_DISCOVERY.number to ProtoErrorCode.FOR_DISCOVERY.wire)
        assertEquals(2 to "ERROR_CODE_FOR_CONNECTION", ProtoErrorCode.FOR_CONNECTION.number to ProtoErrorCode.FOR_CONNECTION.wire)
        assertEquals(3 to "ERROR_CODE_FOR_TRANSPORT", ProtoErrorCode.FOR_TRANSPORT.number to ProtoErrorCode.FOR_TRANSPORT.wire)
        assertEquals(4 to "ERROR_CODE_FOR_DATA_BUS", ProtoErrorCode.FOR_DATA_BUS.number to ProtoErrorCode.FOR_DATA_BUS.wire)
        assertEquals(5 to "ERROR_CODE_FOR_DEVICE_MANAGER", ProtoErrorCode.FOR_DEVICE_MANAGER.number to ProtoErrorCode.FOR_DEVICE_MANAGER.wire)
        assertEquals(6 to "ERROR_CODE_FOR_NETWORK_MANAGER", ProtoErrorCode.FOR_NETWORK_MANAGER.number to ProtoErrorCode.FOR_NETWORK_MANAGER.wire)
    }

    @Test
    fun topLevelFieldNamesAreExactlyProtoFields() {
        val j = JSONObject(AssistantResult.ok("t.f").sealedResultJson())
        val expected = setOf(
            "resultCode", "data", "errorCode", "message",
            // 两个编号辅助字段：方便非 protobuf 的调用方按数字解析，不改变协议语义
            "resultCodeNumber", "errorCodeNumber",
        )
        val actual = j.keys().asSequence().toSet()
        assertEquals(expected, actual)
        assertNotNull(j.get("message"))
    }

    @Test
    fun agentEventTypesAllNinePresent() {
        // agents.proto 的 EventType 有 9 个值，此前我漏了 PAYLOAD_UPDATE / PAYLOAD_REQUEST /
        // EXTENSION_EVENT / DISPLAY_DEVICES_CHANGE 四个，等于丢掉一半表意能力
        assertEquals(9, AgentEventType.entries.size)
        val wires = AgentEventType.entries.map { it.wire }.toSet()
        assertEquals(
            setOf(
                "AGENT_FOUND", "AGENT_CONNECTED", "AGENT_DISCONNECTED",
                "PAYLOAD_RECEIVE", "PAYLOAD_UPDATE", "PAYLOAD_REQUEST",
                "EXTENSION_EVENT", "AGENT_LOST", "DISPLAY_DEVICES_CHANGE",
            ),
            wires,
        )
    }

    @Test
    fun strategyAndPayloadEnumsMatchProto() {
        assertEquals(
            listOf("BLE", "NSD", "BASED_CONNECT", "RTC", "AUTO"),
            AgentStrategy.entries.map { it.wire },
        )
        assertEquals(listOf("BYTES", "STREAM", "FILE"), AgentPayloadType.entries.map { it.wire })
        assertEquals(
            listOf("SUCCESS", "FAILURE", "CANCELED", "IN_PROGRESS"),
            AgentTransferStatus.entries.map { it.wire },
        )
        assertEquals(listOf("Provider", "Seeker"), AgentRole.entries.map { it.wire })
    }

    @Test
    fun payloadProgressHandlesZeroTotal() {
        // totalLength=0 时不能除零，按"已完成"处理
        val buf = InternalPayloadBuffer(totalLength = 0, transferredLength = 0)
        assertEquals(1f, buf.progress, 0.0001f)
        val half = InternalPayloadBuffer(totalLength = 100, transferredLength = 50)
        assertEquals(0.5f, half.progress, 0.0001f)
    }
}
