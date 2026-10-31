package com.ai.assistance.quro.core.mika.remote

import com.ai.assistance.quro.core.mika.device.DeviceAccess
import com.ai.assistance.quro.core.mika.device.toJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder

/** 普通 HTTP/JSON 适配器。无需 MCP 握手、工具发现或 tools/call;认证由 RemoteHost 统一执行。 */
class RemoteTaskApi(
    private val service: RemoteTaskService,
    private val serverVersion: String,
    private val device: DeviceAccess? = null,
) {
    fun handle(req: HttpRequest): HttpResponse = try {
        req.header("accept")?.lowercase()?.let {
            if ("application/json" !in it && "*/*" !in it && "application/*" !in it)
                throw ApiError(406, "not_acceptable", "Accept must include application/json")
        }
        route(req)
    } catch (e: ApiError) {
        error(e.status, e.code, e.message.orEmpty(), e.headers)
    } catch (e: RemoteTaskError) {
        val status = when (e.code) {
            RemoteTaskError.Code.INVALID_ARGUMENT -> 400
            RemoteTaskError.Code.NOT_FOUND -> 404
            RemoteTaskError.Code.CONFLICT -> 409
            RemoteTaskError.Code.UNAVAILABLE -> 503
        }
        error(status, e.code.name.lowercase(), e.message.orEmpty())
    }

    private fun route(req: HttpRequest): HttpResponse {
        val query = query(req)
        if (req.path != "$ROOT/tasks" && query.isNotEmpty()) invalid("This endpoint accepts no query parameters")
        if (req.path == ROOT) {
            method(req, "GET")
            return ok(buildJsonObject {
                put("api_version", 1); put("server_version", serverVersion)
                putJsonArray("capabilities") { add("tasks"); if (device != null) add("device_state") }
                putJsonObject("limits") {
                    put("max_wait_s", RemoteTaskService.MAX_WAIT_S)
                    put("max_goal", RemoteTaskService.MAX_GOAL)
                    put("max_answer", RemoteTaskService.MAX_ANSWER)
                }
            })
        }
        if (req.path == "$ROOT/device") {
            method(req, "GET")
            val access = device ?: throw ApiError(503, "device_unavailable", "Device state is unavailable")
            return ok(runBlocking { access.state().toJson() })
        }
        if (req.path == "$ROOT/tasks") {
            return when (req.method) {
                "GET" -> {
                    if (query.keys.any { it != "limit" }) invalid("Only limit is supported")
                    val limit = query["limit"]?.let { it.toIntOrNull() ?: invalid("limit must be an integer") }
                        ?: RemoteTaskService.DEFAULT_LIST
                    ok(buildJsonObject { putJsonArray("tasks") {
                        service.list(limit).forEach { add(RemoteStatus.describe(it)) }
                    } })
                }
                "POST" -> {
                    if (query.isNotEmpty()) invalid("Task submission accepts no query parameters")
                    val body = body(req, setOf("goal", "conversation"))
                    ok(RemoteStatus.describe(service.start(string(body, "goal").orEmpty(), string(body, "conversation"))), 201)
                }
                else -> wrongMethod("GET, POST")
            }
        }
        val parts = req.path.removePrefix("$ROOT/tasks/").split('/')
        if (!req.path.startsWith("$ROOT/tasks/") || parts.size !in 1..2 || !ID.matches(parts[0]))
            throw ApiError(404, "not_found", "No such endpoint")
        val id = parts[0]
        if (parts.size == 1) {
            method(req, "GET")
            return ok(RemoteStatus.describe(service.get(id)))
        }
        return when (parts[1]) {
            "wait" -> {
                method(req, "POST")
                val b = body(req, setOf("timeout_s"))
                val r = runBlocking { service.wait(id, integer(b, "timeout_s") ?: RemoteTaskService.DEFAULT_WAIT_S) }
                ok(buildJsonObject {
                    RemoteStatus.describe(r.task).forEach { (k, v) -> put(k, v) }; put("changed", r.changed)
                })
            }
            "answer" -> {
                method(req, "POST")
                val b = body(req, setOf("text"))
                ok(buildJsonObject { put("task_id", service.answer(id, string(b, "text").orEmpty())); put("accepted", true) })
            }
            "cancel" -> {
                method(req, "POST")
                body(req, emptySet())
                val r = service.cancel(id)
                ok(buildJsonObject {
                    put("task_id", r.taskId); put("accepted", r.accepted)
                    r.status?.let { put("status", it) }
                }, if (r.accepted) 202 else 200)
            }
            else -> throw ApiError(404, "not_found", "No such endpoint")
        }
    }

    private fun body(req: HttpRequest, allowed: Set<String>): JsonObject {
        if (req.header("content-type")?.substringBefore(';')?.trim()?.lowercase() != "application/json")
            throw ApiError(415, "unsupported_media_type", "Content-Type must be application/json")
        val b = runCatching { Json.parseToJsonElement(req.body.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: invalid("Body must be a JSON object")
        if (b.keys.any { it !in allowed }) invalid("Unsupported request fields")
        return b
    }

    private fun string(body: JsonObject, key: String): String? = when (val v = body[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else invalid("$key must be a string")
        else -> invalid("$key must be a string")
    }

    private fun integer(body: JsonObject, key: String): Int? = when (val v = body[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (!v.isString) v.intOrNull ?: invalid("$key must be an integer") else invalid("$key must be an integer")
        else -> invalid("$key must be an integer")
    }

    private fun query(req: HttpRequest): Map<String, String> {
        if (req.query.isEmpty()) return emptyMap()
        val fields = req.query.split('&').map {
            val pair = it.split('=', limit = 2)
            if (pair.size != 2) invalid("Malformed query")
            try { URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }
            catch (_: IllegalArgumentException) { invalid("Malformed query") }
        }
        if (fields.map { it.first }.distinct().size != fields.size) invalid("Duplicate query parameter")
        return fields.toMap()
    }

    private fun method(req: HttpRequest, expected: String) { if (req.method != expected) wrongMethod(expected) }
    private fun wrongMethod(allow: String): Nothing = throw ApiError(405, "method_not_allowed", "Method not allowed", mapOf("Allow" to allow))
    private fun invalid(message: String): Nothing = throw ApiError(400, "invalid_argument", message)
    private fun ok(body: JsonObject, status: Int = 200) = HttpResponse.json(status, body.toString())
    private class ApiError(val status: Int, val code: String, message: String, val headers: Map<String, String> = emptyMap()) : Exception(message)

    companion object {
        const val ROOT = "/api/v1"
        private val ID = Regex("[A-Za-z0-9_-]+")
        fun error(status: Int, code: String, message: String, headers: Map<String, String> = emptyMap()) =
            HttpResponse.json(status, buildJsonObject { putJsonObject("error") {
                put("code", code); put("message", message)
            } }.toString(), headers)
    }
}
