package com.yuanbao.miniapp.js

import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.util.parseJson

/**
 * Kotlin facade over the self-developed C++ JS engine.
 * NOT thread-safe: one instance must be driven by exactly one thread (the logic thread).
 */
class JsEngine internal constructor() {

    private var handle: Long = 0
    private var closed = false

    init {
        handle = JsNative.nativeCreate()
    }

    fun isAlive(): Boolean = !closed && handle != 0L

    /**
     * Runs [source]; returns the serialized result:
     * {"t":"number"|"string"|"boolean"|"object"|"null"|"undefined"|"function"|"error","v":...}
     */
    fun evaluate(source: String): EngineResult {
        val raw = JsNative.nativeEvaluate(handle, source)
        return EngineResult(raw)
    }

    /** Evaluates [expr] and returns its value as a Json tree. */
    fun evalJson(expr: String): Json {
        val r = evaluate(expr)
        return if (r.isError()) Json.Null else r.value
    }

    /** Evaluates [source] that must produce a function; returns an id usable with [invokeFunction]. */
    fun compileFunction(source: String): Int = JsNative.nativeCompileFunction(handle, source)

    fun invokeFunction(fnId: Int, argsJson: String = "[]"): EngineResult =
        EngineResult(JsNative.nativeInvokeFunction(handle, fnId, argsJson))

    fun callGlobal(name: String, argsJson: String = "[]"): EngineResult =
        EngineResult(JsNative.nativeCallGlobal(handle, name, argsJson))

    /** Exposes a Kotlin-implemented function to JS under [name]. */
    fun registerHostFunction(name: String) {
        JsNative.nativeRegisterHostFunction(handle, name)
    }

    fun lastError(): String = JsNative.nativeLastError(handle)

    fun close() {
        if (!closed && handle != 0L) {
            JsNative.nativeDestroy(handle)
            handle = 0
            closed = true
        }
    }

    /** Result of an evaluation, decoded from the engine's JSON envelope. */
    class EngineResult(private val raw: String) {
        val type: String
        val value: Json

        init {
            val parsed = runCatching { parseJson(raw) }.getOrNull()
            if (parsed is Json.Obj) {
                type = parsed["t"]?.asString() ?: "undefined"
                value = parsed["v"] ?: Json.Null
            } else {
                type = "undefined"
                value = Json.Null
            }
        }

        fun isError(): Boolean = type == "error"
        fun isFunction(): Boolean = type == "function"
        fun functionId(): Int = value.asInt()
        fun asString(): String = value.asString()
        fun asDouble(): Double = value.asDouble()
        fun asBoolean(): Boolean = value.asBoolean()
        fun errorMessage(): String = if (isError()) value.asString() else ""
        override fun toString(): String = raw
    }
}
