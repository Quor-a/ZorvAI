package com.ai.assistance.quro.core.mika.record

import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.PlaceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 旧运行记录里改了名的事件,读取前换成现在的写法(只在读的时候换,文件不动):
 * - screen_text(读屏交给模型的元素文字)→ observed:一条读到的观察,地方是那时的站点或 App,编号为 0;
 * - screen_saved(存下一帧读屏画面)→ media。
 */
internal object LegacyEvents {
    fun upgrade(e: JsonElement): JsonElement {
        val o = e as? JsonObject ?: return e
        return when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
            "screen_text" -> buildJsonObject {
                put("type", "observed")
                o["atMs"]?.let { put("atMs", it) }
                o["step"]?.let { put("step", it) }
                put("kind", ObsKind.Read.wire)
                val site = o.str("site")
                val app = o.str("app")
                // 那时网页上报站点,手机上报 App
                (site?.let { PlaceKind.Site to it } ?: app?.let { PlaceKind.App to it })?.let { (k, n) ->
                    put("place", buildJsonObject { put("kind", k.wire); put("name", n) })
                    put("source", if (k == PlaceKind.Site) "web" else "phone")
                }
                put("text", (o["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().joinToString("\n"))
                o["inputs"]?.let { put("inputs", it) }
                o["focused"]?.let { put("focused", it) }
            }
            "screen_saved" -> buildJsonObject {
                put("type", "media")
                o["atMs"]?.let { put("atMs", it) }
                o["screenshotId"]?.let { put("mediaId", it) }
                o["step"]?.let { put("step", it) }
                o["app"]?.let { put("place", it) }
            }
            else -> o
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
