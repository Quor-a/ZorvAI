package com.ai.assistance.quro.kaleidobox.core.engine

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.security.CapabilityLexicon
import com.ai.assistance.quro.kaleidobox.core.security.PermissionGate
import com.ai.assistance.quro.kaleidobox.core.security.digestArgs

/**
 * 宿主能力注册表。
 *
 * 宿主 App 把自己愿意开放的能力注册进来（fs / net / ui / ai / sys ...），
 * 插件无论用什么语言，看到的都是同一个 `host.call('fs.read', {...})`。
 *
 * 这是"宿主深度融合"的核心：宿主不用为每个插件写胶水，
 * 插件也不用学 N 套宿主 API —— 只有一张能力表。
 */
class CapabilityRegistry {

    fun interface Handler {
        fun handle(args: KValue, ctx: InvokeContext): KValue
    }

    data class Binding(
        val capability: String,
        val handler: Handler,
        val requiresPermission: Boolean = true,
        val description: String = "",
    )

    private val bindings = java.util.concurrent.ConcurrentHashMap<String, Binding>()

    fun register(binding: Binding) {
        bindings[binding.capability] = binding
    }

    fun register(capability: String, description: String = "", requiresPermission: Boolean = true, h: Handler) =
        register(Binding(capability, h, requiresPermission, description))

    fun bind(capability: String, description: String = "", block: (KValue, InvokeContext) -> KValue) =
        register(capability, description, true, Handler(block))

    fun unregister(capability: String) = bindings.remove(capability)
    fun has(capability: String) = bindings.containsKey(capability)
    fun names(): Set<String> = bindings.keys.toSet()

    fun dispatch(capability: String, args: KValue, ctx: InvokeContext, gate: PermissionGate): KValue {
        val b = bindings[capability]
            ?: return KValue.Err("E_NO_CAPABILITY", "宿主未提供能力: $capability",
                KValue.obj("available" to bindings.keys.sorted()))

        if (b.requiresPermission) {
            if (!gate.isGranted(ctx.pkgId, capability)) {
                // 作用域能力（net.http:host=x）允许用基类能力兜底
                val base = CapabilityLexicon.split(capability).first
                if (base == capability || !gate.isGranted(ctx.pkgId, base)) {
                    return KValue.Err("E_PERMISSION", "包 ${ctx.pkgId} 未获得能力: $capability")
                }
            }
        }
        gate.audit(ctx.pkgId, capability, digestArgs(listOf(args)))
        return try {
            b.handler.handle(args, ctx)
        } catch (t: Throwable) {
            if (t is KaleidoException) t.toKValue()
            else KValue.Err("E_HOST", t.message ?: "宿主能力执行异常")
        }
    }

    /** 生成能力清单文档，供 SDK 生成类型定义 / 市场展示。 */
    fun describe(): String = buildString {
        appendLine("=== 宿主已开放能力 (${bindings.size}) ===")
        bindings.values.sortedBy { it.capability }.forEach {
            appendLine("%-28s %s".format(it.capability, it.description))
        }
    }
}

/**
 * 默认 HostBridge 实现。
 * 通过构造函数注入运行时回调，避免与 [com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime] 形成编译期循环依赖。
 */
class DefaultHostBridge(
    private val capabilities: CapabilityRegistry,
    private val gate: PermissionGate,
    private val unitInvoker: (String, KValue, InvokeContext) -> KValue,
    private val resourceReader: (String, InvokeContext) -> KValue,
    private val kvGet: (String, InvokeContext) -> KValue,
    private val kvSet: (String, KValue, InvokeContext) -> KValue,
    private val hookEmitter: (String, KValue.Obj, InvokeContext) -> KValue,
    private val logger: (LogLevel, String, String, InvokeContext) -> Unit,
) : HostBridge {

    override fun call(capability: String, args: KValue, ctx: InvokeContext): KValue =
        capabilities.dispatch(capability, args, ctx, gate)

    override fun supports(capability: String) = capabilities.has(capability)

    override fun invokeUnit(unitRef: String, args: KValue, ctx: InvokeContext): KValue {
        // 跨包调用需要显式能力，避免"任意包互调"变成提权通道
        if (!unitRef.startsWith("${ctx.pkgId}:")) {
            gate.require(ctx.pkgId, "pkg.invoke:any")
        }
        return unitInvoker(unitRef, args, ctx)
    }

    override fun readResource(key: String, ctx: InvokeContext) = resourceReader(key, ctx)

    override fun kvGet(key: String, ctx: InvokeContext): KValue = kvGet.invoke(key, ctx)
    override fun kvSet(key: String, value: KValue, ctx: InvokeContext): KValue = kvSet.invoke(key, value, ctx)

    override fun log(level: LogLevel, tag: String, message: String, ctx: InvokeContext) =
        logger(level, tag, message, ctx)

    override fun emit(point: String, payload: KValue.Obj, ctx: InvokeContext) =
        hookEmitter(point, payload, ctx)
}

/** 空实现，用于单测与"零能力"沙箱探测。 */
object NoopHostBridge : HostBridge {
    override fun call(capability: String, args: KValue, ctx: InvokeContext) =
        KValue.Err("E_NO_CAPABILITY", "无宿主（测试环境）: $capability")
    override fun supports(capability: String) = false
    override fun invokeUnit(unitRef: String, args: KValue, ctx: InvokeContext) =
        KValue.Err("E_NO_CAPABILITY", "无宿主")
    override fun readResource(key: String, ctx: InvokeContext) = KValue.Err("E_NO_CAPABILITY", "无宿主")
    override fun kvGet(key: String, ctx: InvokeContext) = KValue.Null
    override fun kvSet(key: String, value: KValue, ctx: InvokeContext) = KValue.Null
    override fun log(level: LogLevel, tag: String, message: String, ctx: InvokeContext) = Unit
    override fun emit(point: String, payload: KValue.Obj, ctx: InvokeContext) = payload
}
