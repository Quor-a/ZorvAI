package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.net.UrlHost
import com.ai.assistance.quro.core.mika.observation.Place
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 调用参数;不是 JSON 对象时按空参数处理(由工具层去拒)。 */
internal fun parseArgs(json: String): JsonObject = try {
    Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
} catch (e: SerializationException) {
    JsonObject(emptyMap())
}

/** 结果里报的「在哪」:手机上是前台 App,网页上是站点(动作自带的 screen 里报的也算);都没报为 null。 */
internal fun placeOf(content: JsonObject): Place? {
    val screen = content["screen"] as? JsonObject
    fun f(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
    (f(content, "currentApp") ?: f(screen, "currentApp"))?.let { return Place.app(it) }
    return (f(content, "currentSite") ?: f(screen, "currentSite"))?.let(Place::site)
}

/** 结果里带着站点的动作(读屏的 currentSite、打开网页与读页的网址)。 */
private val SITE_RESULT_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage)

/**
 * 这次调用之后任务在哪;结果没说为 null(沿用之前的)。「在哪」分手机 App 与网站:换到网页、换了站点都算换了地方。
 * 打开网页、读页的结果只带网址:按网址的主机算到了哪个站点(和读屏报的 currentSite 同一算法)。
 */
internal fun placeAfter(verb: Verb?, content: JsonObject): Place? =
    placeOf(content) ?: if (verb in SITE_RESULT_VERBS && content["ok"] == JsonPrimitive(true))
        (content["url"] as? JsonPrimitive)?.contentOrNull?.let(UrlHost::of)?.let(Place::site) else null
