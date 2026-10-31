package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.prompt.Feedback
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** 参数校验失败;message 点名字段。 */
internal class ArgError(val field: String, msg: String) : Exception("$field: $msg")

private val REF_RE = Regex("^@[ev][1-9]\\d*$")
internal val PAGE_REF_RE = Regex("^@e[1-9]\\d*$")
internal val LOCATED_REF = Regex("^@v[1-9]\\d*$")

internal class Args(val tool: ToolDef, val o: JsonObject) {
    init {
        o.keys.firstOrNull { it !in tool.allowed }?.let {
            // 说清楚能用哪些参数;能按元素操作的工具另说元素编号从哪来(按字找元素不是参数)
            val unknown = Feedback.format("args.unknown", "tool" to tool.name, "allowed" to tool.allowed.filter { k -> k != "reason" }.joinToString())
            throw ArgError(it, if ("ref" in tool.allowed) Feedback.format("args.unknown_ref", "unknown" to unknown) else unknown)
        }
        tool.required.firstOrNull { o[it] == null || o[it] is JsonNull }?.let { throw ArgError(it, "required") }
    }

    private fun prim(k: String): JsonPrimitive? = when (val v = o[k]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v
        else -> throw ArgError(k, "must be a scalar")
    }

    fun str(k: String, min: Int = 1, max: Int = Int.MAX_VALUE): String? {
        val p = prim(k) ?: return null
        if (!p.isString) throw ArgError(k, "must be a string")
        val s = p.content
        if (s.trim().length < min) throw ArgError(k, "must not be blank")
        if (s.length > max) throw ArgError(k, "must be at most $max characters")
        return s
    }

    fun number(k: String): Double? {
        val p = prim(k) ?: return null
        return p.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw ArgError(k, "must be a number")
    }

    fun int(k: String, min: Int, max: Int): Int? {
        val d = number(k) ?: return null
        if (d != Math.floor(d)) throw ArgError(k, "must be an integer")
        if (d < min || d > max) throw ArgError(k, "must be between $min and $max")
        return d.toInt()
    }

    /** 坐标容忍 [a,b](取中点)。 */
    fun coord(k: String): Int? {
        val v = o[k]
        if (v is JsonArray && v.size == 2) {
            val a = (v[0] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val b = (v[1] as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (a != null && b != null) {
                val c = ((a + b) / 2).roundToInt()
                if (c !in 0..999) throw ArgError(k, "must be between 0 and 999")
                return c
            }
        }
        return int(k, 0, 999)
    }

    fun strList(k: String, min: Int, max: Int, itemMax: Int = 200): List<String>? {
        val v = o[k]
        if (v == null || v is JsonNull) return null
        if (v !is JsonArray) throw ArgError(k, "must be an array of strings")
        if (v.size < min || v.size > max) throw ArgError(k, "must have $min-$max items")
        return v.map { e ->
            val p = e as? JsonPrimitive
            if (p == null || !p.isString || p.content.isBlank() || p.content.length > itemMax) throw ArgError(k, "items must be non-blank strings up to $itemMax characters")
            p.content
        }
    }

    fun intList(k: String, min: Int, max: Int): List<Int>? {
        val v = o[k]
        if (v == null || v is JsonNull) return null
        if (v !is JsonArray) throw ArgError(k, "must be an array of integers")
        if (v.size < min || v.size > max) throw ArgError(k, "must have $min-$max items")
        return v.map { e ->
            val d = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            if (d == null || d != Math.floor(d) || d < 1 || d > Int.MAX_VALUE) throw ArgError(k, "items must be positive integers")
            d.toInt()
        }
    }

    fun bool(k: String, default: Boolean): Boolean {
        val p = prim(k) ?: return default
        return when (p.content.trim().lowercase()) {
            "true" -> true
            "false", "" -> false
            else -> throw ArgError(k, "must be a boolean")
        }
    }

    /** [located]:也接受 find_visually 给的 @vN(点击和往一点上打字、清空能用)。 */
    fun ref(located: Boolean = false): Pair<String, String>? {
        val r = str("ref"); val s = str("snapshotId")
        if (r == null && s == null) return null
        if (r == null) throw ArgError("ref", "required when snapshotId is given")
        if (s == null) throw ArgError("snapshotId", "required when ref is given")
        if (!REF_RE.matches(r)) throw ArgError("ref", "must look like @e3")
        if (!located && LOCATED_REF.matches(r)) throw ArgError("ref", "@vN from locating on screen can only be tapped or typed into; use an @eN ref from a screen read here")
        return r to s
    }
}
