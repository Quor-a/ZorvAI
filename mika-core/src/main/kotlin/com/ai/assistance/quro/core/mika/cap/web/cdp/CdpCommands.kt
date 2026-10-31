package com.ai.assistance.quro.core.mika.cap.web.cdp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

// 只给几个常用命令包一层,省掉手写参数名和拆结果的出错机会;其余命令直接用 CdpSession.send。

/**
 * `Page.frameNavigated` 的参数 [p] 说的是主框架 [frameId] 换了文档(换了文档,里面的隔离世界都没了)。
 * 同一文档里改地址是另一个事件,不算。[frameId] 为 null 时不算。
 */
fun mainFrameNavigated(p: JsonObject, frameId: String?): Boolean {
    val frame = p["frame"] as? JsonObject ?: return false
    if (frame["parentId"] != null) return false
    return frameId != null && frame["id"]?.jsonPrimitive?.contentOrNull == frameId
}

/** 页面里的脚本抛了异常。 */
class CdpEvaluationException(val details: JsonObject) :
    RuntimeException(
        details["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
            ?: details["text"]?.jsonPrimitive?.contentOrNull
            ?: "evaluation failed",
    )

/** `Runtime.evaluate`,返回 RemoteObject(`returnByValue` 时值在 `value` 里);脚本异常抛 [CdpEvaluationException]。 */
suspend fun CdpSession.evaluate(
    expression: String,
    sessionId: String? = null,
    awaitPromise: Boolean = true,
    returnByValue: Boolean = true,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): JsonObject {
    val r = send(
        "Runtime.evaluate",
        buildJsonObject {
            put("expression", expression)
            put("awaitPromise", awaitPromise)
            put("returnByValue", returnByValue)
        },
        sessionId,
        timeoutMs,
    )
    (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
    return r["result"]?.jsonObject ?: JsonObject(emptyMap())
}

/** `DOM.getDocument`,返回根节点。[depth] 为 -1 取整棵树,[pierce] 穿透 iframe 与 shadow root。 */
suspend fun CdpSession.getDocument(
    depth: Int = -1,
    pierce: Boolean = true,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): JsonObject = send(
    "DOM.getDocument",
    buildJsonObject {
        put("depth", depth)
        put("pierce", pierce)
    },
    sessionId,
    timeoutMs,
).getValue("root").jsonObject

/** `Accessibility.getFullAXTree`,返回扁平的节点列表。 */
suspend fun CdpSession.getFullAXTree(
    depth: Int? = null,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): List<JsonObject> = send(
    "Accessibility.getFullAXTree",
    buildJsonObject { if (depth != null) put("depth", depth) },
    sessionId,
    timeoutMs,
)["nodes"]?.jsonArray?.map { it.jsonObject }.orEmpty()

/** `Page.captureScreenshot`,返回解码后的图片字节。[format] 为 png / jpeg / webp。 */
suspend fun CdpSession.captureScreenshot(
    format: String = "png",
    quality: Int? = null,
    captureBeyondViewport: Boolean = false,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): ByteArray {
    val r = send(
        "Page.captureScreenshot",
        buildJsonObject {
            put("format", format)
            if (quality != null) put("quality", quality)
            put("captureBeyondViewport", captureBeyondViewport)
        },
        sessionId,
        timeoutMs,
    )
    return Base64.getDecoder().decode(r.getValue("data").jsonPrimitive.content)
}

/** `Input.dispatchMouseEvent`。[type]:mousePressed / mouseReleased / mouseMoved / mouseWheel;坐标是 CSS 像素。 */
suspend fun CdpSession.dispatchMouseEvent(
    type: String,
    x: Double,
    y: Double,
    button: String = "none",
    clickCount: Int = 0,
    modifiers: Int = 0,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
) {
    send(
        "Input.dispatchMouseEvent",
        buildJsonObject {
            put("type", type)
            put("x", x * inputScale)
            put("y", y * inputScale)
            put("button", button)
            put("clickCount", clickCount)
            if (modifiers != 0) put("modifiers", modifiers)
        },
        sessionId,
        timeoutMs,
    )
}

/** 左键单击:按下再抬起。 */
suspend fun CdpSession.click(x: Double, y: Double, sessionId: String? = null) {
    dispatchMouseEvent("mousePressed", x, y, "left", 1, sessionId = sessionId)
    dispatchMouseEvent("mouseReleased", x, y, "left", 1, sessionId = sessionId)
}

/** `Network.enable`;之后才会收到 `Network.*` 事件。 */
suspend fun CdpSession.enableNetwork(sessionId: String? = null, timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS) {
    send("Network.enable", JsonObject(emptyMap()), sessionId, timeoutMs)
}
