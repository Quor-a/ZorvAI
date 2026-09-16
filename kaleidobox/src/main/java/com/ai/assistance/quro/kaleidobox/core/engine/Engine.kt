package com.ai.assistance.quro.kaleidobox.core.engine

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.model.*
import com.ai.assistance.quro.kaleidobox.core.security.PermissionGate
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * 引擎会话：一个包的一个 runtime 单元，对应一个会话。
 *
 * 为什么要有"会话"这层，而不是 Engine 直接调函数？
 *   - 引擎实例可以被多个包共享（manifest 里 runtime.shared=true），会话才是隔离边界；
 *   - 会话持有该包的权限/配额/句柄表，销毁时一次性回收，杜绝跨包泄漏；
 *   - 崩溃后只需重建会话，不用重启引擎。
 */
interface EngineSession : Closeable {
    val runtimeId: String
    val pkgId: String
    val engineKind: EngineKind
    val state: SessionState

    /** 加载入口文件，返回是否成功。失败时 message 应可直接展示给用户。 */
    fun load(entryBytes: ByteArray, entryName: String): KValue

    /** 调用已导出函数。args 已经过 KValue 标准化。 */
    fun invoke(fn: String, args: List<KValue>, ctx: InvokeContext): KValue

    /** 该会话内是否已定义该函数（用于安装期 exports 校验）。 */
    fun hasFunction(fn: String): Boolean

    /** 导出符号表（引擎无法静态分析时返回 null 表示"不校验"）。 */
    fun exportedSymbols(): Set<String>?

    /**
     * 引擎加载后由运行时注入插件 App 上下文（[KaleidoAppContext]）。
     * 默认空实现——非 JVM/Dex 引擎可忽略；JvmDexSession 会持有并转交插件。
     */
    fun attachApp(appContext: KaleidoAppContext) {}

    /** 转发生命周期事件给插件（可选重写）。 */
    fun onAppLifecycle(event: AppLifecycle, appContext: KaleidoAppContext?) {}
}

enum class SessionState { CREATED, LOADING, READY, FAULTED, CLOSED }

/**
 * 调用上下文：把宿主能力、权限门控、取消信号、流式输出一并带进去。
 * 这是"宿主深度融合"的抓手 —— 插件不是被隔离在沙箱里喊，而是能拿到结构化的宿主上下文。
 */
data class InvokeContext(
    val pkgId: String,
    val runtimeId: String,
    val unitName: String?,
    val host: HostBridge,
    val gate: PermissionGate,
    val deadlineMs: Long = System.currentTimeMillis() + 30_000,
    val onChunk: ((KValue) -> Unit)? = null,
    val attrs: Map<String, KValue> = emptyMap(),
) {
    fun remainingMs() = deadlineMs - System.currentTimeMillis()
    fun isExpired() = remainingMs() <= 0
}

/**
 * 引擎 SPI。新增一门语言 = 实现这个接口 + 注册到 [EngineFabric]，核心零改动。
 */
interface KaleidoEngine : Closeable {
    val kind: EngineKind
    val displayName: String
    val languages: Set<Lang>

    fun isAvailable(): Boolean

    /** 创建会话。shared 决定复用还是独占底层实例。 */
    fun createSession(pkgId: String, runtime: RuntimeUnit, host: HostBridge, gate: PermissionGate): EngineSession

    /** 引擎级统计，用于宿主做性能面板。 */
    fun stats(): EngineStats
}

data class EngineStats(
    val kind: EngineKind,
    val sessions: Int,
    val totalInvocations: Long,
    val totalNanos: Long,
    val faults: Int,
    val peakMemoryBytes: Long = 0,
) {
    val avgMs: Double get() = if (totalInvocations == 0L) 0.0 else totalNanos / 1e6 / totalInvocations
}

/**
 * 宿主能力桥：所有引擎（无论什么语言）看到的宿主 API **完全一致**，
 * 这是"多语言统一"的另一半 —— 不只是能跑多种语言，而是多种语言看到的宿主是同一个。
 */
interface HostBridge {
    /** 调用宿主能力，如 "fs.read" / "net.http" / "ui.toast"。 */
    fun call(capability: String, args: KValue, ctx: InvokeContext): KValue

    /** 宿主是否提供该能力（用于插件优雅降级）。 */
    fun supports(capability: String): Boolean

    /** 跨 runtime / 跨包调用另一个 unit。 */
    fun invokeUnit(unitRef: String, args: KValue, ctx: InvokeContext): KValue

    /** 读取本包打包资源。 */
    fun readResource(key: String, ctx: InvokeContext): KValue

    /** 包私有 KV 存储。 */
    fun kvGet(key: String, ctx: InvokeContext): KValue
    fun kvSet(key: String, value: KValue, ctx: InvokeContext): KValue

    /** 结构化日志，宿主统一收集。 */
    fun log(level: LogLevel, tag: String, message: String, ctx: InvokeContext)

    /** 触发宿主 Hook。 */
    fun emit(point: String, payload: KValue.Obj, ctx: InvokeContext): KValue
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 引擎工厂 / 注册表。
 * 支持：内置引擎自动探测、外部引擎 SPI 注入、按语言选默认引擎、按可用性降级。
 */
object EngineFabric {

    private val registry = java.util.concurrent.ConcurrentHashMap<EngineKind, KaleidoEngine>()
    private val overrides = java.util.concurrent.ConcurrentHashMap<Pair<Lang, String?>, EngineKind>()

    fun register(engine: KaleidoEngine) {
        registry[engine.kind] = engine
    }

    fun unregister(kind: EngineKind) = registry.remove(kind)

    fun get(kind: EngineKind): KaleidoEngine =
        registry[kind] ?: throw KaleidoException.Engine("引擎未注册: $kind")

    fun all(): Map<EngineKind, KaleidoEngine> = registry.toMap()

    /** 强制覆盖某语言（或某语言+包）的默认引擎，便于灰度/降级。 */
    fun preferFor(lang: Lang, engine: EngineKind, pkgId: String? = null) {
        overrides[lang to pkgId] = engine
    }

    fun resolve(runtime: RuntimeUnit, pkgId: String): EngineKind {
        overrides[runtime.lang to pkgId]?.let { return it }
        overrides[runtime.lang to null]?.let { return it }
        if (runtime.engine != EngineKind.AUTO) return runtime.engine
        return EngineKind.defaultFor(runtime.lang)
    }

    /**
     * 按可用性解析：优先首选引擎，不可用时按 fallback 链降级。
     */
    fun resolveAvailable(runtime: RuntimeUnit, pkgId: String): KaleidoEngine {
        val chain = buildList {
            add(resolve(runtime, pkgId))
            addAll(fallbacks(runtime.lang))
        }
        val rejected = mutableListOf<String>()
        for (k in chain) {
            val e = registry[k] ?: continue
            if (runtime.lang !in e.languages) {
                rejected += "${k}(不支持${runtime.lang})"
                continue
            }
            if (!e.isAvailable()) {
                rejected += "$k(不可用)"
                continue
            }
            return e
        }
        val registered = registry.keys.joinToString { it.name }
        throw KaleidoException.Engine(
            "无可用引擎: 语言=${runtime.lang}, 候选=${chain.joinToString()}" +
                (if (rejected.isNotEmpty()) ", 已排除: ${rejected.joinToString()}" else "") +
                " | 本构建已注册引擎=[$registered]；" +
                "KaleidoBox 在本构建仅支持 jvm_dex（Kotlin/Java）工具包——" +
                "请用 runtime { lang=\"kotlin\", engine=\"jvm_dex\", entry=\"实现 KaleidoToolkit 的类全名\" }，" +
                "外部包需提供编译好的 .dex/.apk"
        )
    }

    private fun fallbacks(lang: Lang): List<EngineKind> = when (lang) {
        Lang.JAVASCRIPT, Lang.TYPESCRIPT -> listOf(EngineKind.QUICKJS, EngineKind.NODE, EngineKind.WASM)
        Lang.PYTHON -> listOf(EngineKind.CPYTHON, EngineKind.NODE, EngineKind.WASM)
        Lang.LUA -> listOf(EngineKind.LUAJIT, EngineKind.WASM)
        Lang.WASM -> listOf(EngineKind.WASM)
        Lang.KOTLIN, Lang.JAVA -> listOf(EngineKind.JVM_DEX)
        Lang.SHELL -> listOf(EngineKind.NODE)
    }

    fun snapshot(): String = buildString {
        appendLine("=== Kaleido EngineFabric ===")
        registry.values.sortedBy { it.kind }.forEach { e ->
            val s = e.stats()
            appendLine(
                "%-10s %-16s langs=%-28s avail=%-5s sessions=%d calls=%d avg=%.2fms faults=%d".format(
                    e.kind.name, e.displayName, e.languages.joinToString(","),
                    e.isAvailable(), s.sessions, s.totalInvocations, s.avgMs, s.faults
                )
            )
        }
    }
}

/** 引擎实现的公共基类，负责统计和生命周期记账。 */
abstract class AbstractEngine : KaleidoEngine {
    private val invocationCount = AtomicLong()
    private val totalNanos = AtomicLong()
    private val faultCount = AtomicLong()
    protected val liveSessions = java.util.Collections.synchronizedSet(mutableSetOf<EngineSession>())

    fun recordInvocation(nanos: Long) {
        invocationCount.incrementAndGet()
        totalNanos.addAndGet(nanos)
    }

    fun recordFault() = faultCount.incrementAndGet()

    override fun stats() = EngineStats(
        kind = kind,
        sessions = liveSessions.size,
        totalInvocations = invocationCount.get(),
        totalNanos = totalNanos.get(),
        faults = faultCount.get().toInt(),
    )

    override fun close() {
        liveSessions.toList().forEach { runCatching { it.close() } }
        liveSessions.clear()
    }
}
