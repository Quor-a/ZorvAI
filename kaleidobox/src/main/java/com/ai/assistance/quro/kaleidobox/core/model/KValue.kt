package com.ai.assistance.quro.kaleidobox.core.model

import com.ai.assistance.quro.kaleidobox.core.util.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * 跨引擎统一值模型 —— Kaleido 的 ABI 基石。
 *
 * 设计要点：
 * 1. 所有引擎（QuickJS / LuaJIT / CPython / WASM / JVM）在边界上只交换 KValue，
 *    引擎内部用自己的原生类型，由各引擎 adapter 做双向 marshal。
 * 2. [KHandle] 让"不可序列化"的东西（流、回调、native 对象、大块二进制）以句柄形式跨界，
 *    避免深拷贝，也天然支持流式工具输出。
 * 3. 自带 [KErr] 而不是抛异常：跨语言异常在边界上极易丢失栈信息，统一错误值是更稳的选择。
 */
sealed class KValue {

    object Null : KValue() {
        override fun toString() = "null"
    }

    data class Bool(val value: Boolean) : KValue()
    data class I64(val value: Long) : KValue()
    data class F64(val value: Double) : KValue()
    data class Str(val value: String) : KValue()
    data class Bytes(val value: ByteArray) : KValue() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return value.contentEquals(other.value)
        }

        override fun hashCode() = value.contentHashCode()
    }

    data class Arr(val value: List<KValue>) : KValue()
    data class Obj(val value: Map<String, KValue>) : KValue()

    /** 跨界句柄：流 / 回调 / native 引用 / 大二进制块。宿主侧的真实对象在 HandleTable 里。 */
    data class Handle(val id: Long, val kind: HandleKind, val label: String = "") : KValue()

    /** 结构化错误。code 用于程序分支，message 给人看，detail 给日志/排障。 */
    data class Err(val code: String, val message: String, val detail: Obj? = null) : KValue()

    // ---------- 便捷读取（宽松转型，脚本语言友好） ----------

    val isNull: Boolean get() = this is Null
    val isTruthy: Boolean
        get() = when (this) {
            is Null -> false
            is Bool -> value
            is I64 -> value != 0L
            is F64 -> value != 0.0 && !value.isNaN()
            is Str -> value.isNotEmpty()
            is Arr -> value.isNotEmpty()
            is Obj -> value.isNotEmpty()
            is Bytes -> value.isNotEmpty()
            is Handle -> true
            is Err -> false
        }

    fun asString(): String = when (this) {
        is Str -> value
        is I64 -> value.toString()
        is F64 -> value.toString()
        is Bool -> value.toString()
        is Null -> ""
        is Err -> "[$code] $message"
        else -> Json.write(this)
    }

    fun asLongOr(default: Long = 0L): Long = when (this) {
        is I64 -> value
        is F64 -> value.toLong()
        is Str -> value.toLongOrNull() ?: default
        is Bool -> if (value) 1L else 0L
        else -> default
    }

    fun asDoubleOr(default: Double = 0.0): Double = when (this) {
        is F64 -> value
        is I64 -> value.toDouble()
        is Str -> value.toDoubleOrNull() ?: default
        else -> default
    }

    fun asBoolOr(default: Boolean = false): Boolean = when (this) {
        is Bool -> value
        is I64 -> value != 0L
        is Str -> value.equals("true", true) || value == "1"
        else -> default
    }

    fun asList(): List<KValue> = (this as? Arr)?.value ?: emptyList()
    fun asMap(): Map<String, KValue> = (this as? Obj)?.value ?: emptyMap()
    operator fun get(key: String): KValue = asMap()[key] ?: Null
    operator fun get(index: Int): KValue = asList().getOrNull(index) ?: Null

    companion object {
        // ---------- 构造糖 ----------
        // @JvmStatic：让端侧 ecj 编译的 Java 工具包能直接写 KValue.obj(...)/KValue.fail(...)，
        // 不必绕 KValue.Companion.INSTANCE。
        @JvmStatic fun of(v: Any?): KValue = when (v) {
            null -> Null
            is KValue -> v
            is Boolean -> Bool(v)
            is Byte -> I64(v.toLong())
            is Short -> I64(v.toLong())
            is Int -> I64(v.toLong())
            is Long -> I64(v)
            is Float -> F64(v.toDouble())
            is Double -> F64(v)
            is CharSequence -> Str(v.toString())
            is ByteArray -> Bytes(v)
            is Array<*> -> Arr(v.map { of(it) })
            is Iterable<*> -> Arr(v.map { of(it) })
            is Map<*, *> -> Obj(v.entries.associate { it.key.toString() to of(it.value) })
            else -> Err("marshal.unsupported", "无法转换的类型: ${v::class.java.name}")
        }

        @JvmStatic fun obj(vararg pairs: Pair<String, Any?>) = Obj(pairs.associate { it.first to of(it.second) })
        @JvmStatic fun arr(vararg items: Any?) = Arr(items.map { of(it) })

        @JvmStatic fun ok(data: Any? = null) = obj("ok" to true, "data" to data)
        @JvmStatic fun fail(code: String, message: String, detail: Map<String, Any?> = emptyMap()) =
            Err(code, message, Obj(detail.mapValues { of(it.value) }))
    }
}

enum class HandleKind { STREAM_IN, STREAM_OUT, CALLBACK, NATIVE_REF, BLOB, CHANNEL }

/**
 * 宿主侧句柄表。引擎拿到的是 id，不拿到对象本身 —— 这样引擎崩溃/重启后句柄可被统一回收，
 * 不会泄漏宿主的 FD 或内存。
 */
class HandleTable {
    private val next = AtomicLong(1)
    private val refs = java.util.concurrent.ConcurrentHashMap<Long, Any>()
    private val kinds = java.util.concurrent.ConcurrentHashMap<Long, HandleKind>()

    fun put(any: Any, kind: HandleKind, label: String = ""): KValue.Handle {
        val id = next.getAndIncrement()
        refs[id] = any
        kinds[id] = kind
        return KValue.Handle(id, kind, label)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(id: Long): T? = refs[id] as? T

    fun release(id: Long) {
        val kind = kinds.remove(id)
        val ref = refs.remove(id)
        if (kind == HandleKind.STREAM_OUT || kind == HandleKind.STREAM_IN) {
            (ref as? java.io.Closeable)?.close()
        }
    }

    fun releaseAllOf(owner: LongRange) {
        refs.keys.filter { it in owner }.forEach { release(it) }
    }

    fun size() = refs.size
}
