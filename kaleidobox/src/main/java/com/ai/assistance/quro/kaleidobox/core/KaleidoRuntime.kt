package com.ai.assistance.quro.kaleidobox.core

import com.ai.assistance.quro.kaleidobox.core.engine.*
import com.ai.assistance.quro.kaleidobox.core.hooks.HookBus
import com.ai.assistance.quro.kaleidobox.core.hooks.HookEvent
import com.ai.assistance.quro.kaleidobox.core.hooks.HookMatrix
import com.ai.assistance.quro.kaleidobox.core.hooks.HookResult
import com.ai.assistance.quro.kaleidobox.core.hooks.Subscription
import com.ai.assistance.quro.kaleidobox.core.manifest.ManifestParser
import com.ai.assistance.quro.kaleidobox.core.manifest.ManifestValidator
import com.ai.assistance.quro.kaleidobox.core.model.*
import com.ai.assistance.quro.kaleidobox.core.eco.ToolPkgCompat
import com.ai.assistance.quro.kaleidobox.core.registry.*
import com.ai.assistance.quro.kaleidobox.core.security.CapabilityLexicon
import com.ai.assistance.quro.kaleidobox.core.security.InMemoryPermissionGate
import com.ai.assistance.quro.kaleidobox.core.security.PermissionGate
import com.ai.assistance.quro.kaleidobox.core.security.Quota
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Kaleido 运行时门面 —— 宿主 App 唯一需要打交道的对象。
 *
 * 典型接入（Android）：
 * ```kotlin
 * val runtime = KaleidoRuntime.builder()
 *     .capabilities { ... 注册 fs/net/ui/ai 能力 ... }
 *     .build()
 * runtime.install(source)
 * val out = runtime.invokeUnit("hello.greet", KValue.obj("name" to "世界"))
 * ```
 */
class KaleidoRuntime private constructor(
    val registry: PackageRegistry,
    val hooks: HookBus,
    val capabilities: CapabilityRegistry,
    val gate: PermissionGate,
    private val hostBridge: HostBridge,
    private val enableCache: Boolean,
    private val pluginStorage: PluginStorage? = null,
    private val appContextProvider: ((String) -> KaleidoAppContext?)? = null,
) {

    // pkg@version:runtimeId → session
    private val sessions = ConcurrentHashMap<String, EngineSession>()
    private val quotas = ConcurrentHashMap<String, Quota>()
    private val cache = UnitCache()
    /** 无持久化存储配置时的内存兜底（单测/无宿主场景）。 */
    private val kvStore = ConcurrentHashMap<String, ConcurrentHashMap<String, KValue>>()
    private val uiState = ConcurrentHashMap<String, ConcurrentHashMap<String, Any?>>()
    private val resolvers = UnitResolver(registry)
    /** 迁移包（如 ToolPkg）的转换报告，供宿主在 UI 上提示"这个包是自动迁移来的"。 */
    private val migrations = ConcurrentHashMap<String, String>()

    // ---------------- 安装 / 卸载 ----------------

    data class InstallOptions(
        /** 预先授予的能力（市场安装页勾选后的结果）。空集 = 全部待用户确认。 */
        val granted: Set<String> = emptySet(),
        /** 危险能力是否也直接放行（仅内置/自签包建议 true）。 */
        val trusted: Boolean = false,
        /** 允许自动安装缺失的 kaleido 依赖（由宿主提供 fetcher）。 */
        val dependencyFetcher: ((String, String) -> Pair<PackageSource, KaleidoManifest>?)? = null,
        val source: InstallSource = InstallSource.SIDELOAD,
    )

    fun install(pkgSource: PackageSource, options: InstallOptions = InstallOptions()): PackageRecord {
        // ---- 生态兼容：Operit ToolPkg 包免改造直接装 ----
        val parsed: Pair<KaleidoManifest, ToolPkgCompat.MigrationReport?> =
            if (ToolPkgCompat.isToolPkg(pkgSource)) {
                val pair = ToolPkgCompat.translate(pkgSource)
                if (!pair.first.ok) throw KaleidoException.Install(pair.second.id, pair.first.render())
                pair.second to pair.first
            } else {
                val text = pkgSource.read("kaleido.json")?.toString(Charsets.UTF_8)
                    ?: throw KaleidoException.Install("<unknown>", "缺少 kaleido.json")
                ManifestParser.parse(text) to null
            }
        val manifest: KaleidoManifest = parsed.first
        val migrationReport = parsed.second

        // ---- 校验 ----
        val issues = ManifestValidator.validate(manifest, capabilities.names() + CapabilityLexicon.all)
        val errors = issues.filter { it.level == ManifestValidator.Level.ERROR }
        if (errors.isNotEmpty())
            throw KaleidoException.Install(manifest.id, "清单校验失败:\n" + errors.joinToString("\n"))
        val warns = issues.filter { it.level == ManifestValidator.Level.WARN }

        // ---- 引擎可用性：本构建只注册了 jvm_dex（未打包 JS/Python/Lua/WASM 原生运行时）----
        manifest.runtime.forEach { rt ->
            val kind = EngineFabric.resolve(rt, manifest.id)
            if (kind !in EngineFabric.all())
                throw KaleidoException.Install(
                    manifest.id,
                    "引擎不可用: runtime '${rt.id}' 解析为 $kind，但本构建仅注册了 " +
                        "[${EngineFabric.all().keys.joinToString { it.name }}]。" +
                        "请改用 jvm_dex（Kotlin/Java）工具包：runtime { lang=\"kotlin\", engine=\"jvm_dex\"," +
                        " entry=\"实现 KaleidoToolkit 的类全名\" }（外部包需提供编译好的 .dex/.apk）"
                )
        }

        hooks.emit(HookEvent(HookMatrix.PKG_BEFORE_INSTALL, KValue.obj("id" to manifest.id, "version" to manifest.version)))

        // ---- 依赖 ----
        val rec = PackageRecord(manifest, "", source = options.source)
        val missing = registry.missingDependencies(rec)
        if (missing.isNotEmpty()) {
            val fetcher = options.dependencyFetcher
                ?: throw KaleidoException.Install(manifest.id, "缺少依赖: ${missing.joinToString(", ")}")
            missing.forEach { spec ->
                val (depId, range) = spec.split('@', limit = 2)
                val (src, _) = fetcher(depId, range)
                    ?: throw KaleidoException.Install(manifest.id, "无法获取依赖 $spec")
                install(src, options.copy(source = InstallSource.MARKET))
            }
        }

        // ---- 授权 ----
        manifest.capabilities.forEach { c ->
            val dangerous = CapabilityLexicon.isDangerous(c)
            if (!dangerous || options.trusted || c in options.granted) {
                gate.grant(manifest.id, c, permanent = true)
            }
        }

        val record = PackageRecord(
            manifest, "", System.currentTimeMillis(), true, true, options.source,
            integrity = pkgSource.digest("kaleido.json")
        )
        registry.install(record, pkgSource)
        migrationReport?.let { migrations[record.id] = it.render() }
        quotas["${record.id}@${record.version}"] = Quota.from(manifest.sandbox)

        // ---- 建会话 / 注册 hooks / UI ----
        try {
            manifest.runtime.filter { it.warm }.forEach { ensureSession(record, it) }
            registerHooks(record)
        } catch (t: Throwable) {
            registry.remove(record.id, record.version.toString())
            throw KaleidoException.Install(record.id, "初始化失败: ${t.message}")
        }

        hooks.emit(
            HookEvent(
                HookMatrix.PKG_AFTER_INSTALL,
                KValue.obj(
                    "id" to record.id,
                    "version" to record.version.toString(),
                    "warnings" to warns.map { it.toString() },
                    "migrated" to (migrationReport != null),
                )
            )
        )
        return record
    }

    fun uninstall(pkgId: String, version: String? = null) {
        val rec = registry.get(pkgId, version) ?: return
        val dependents = registry.dependents(pkgId).filter { it != pkgId }
        if (dependents.isNotEmpty())
            throw KaleidoException.Install(pkgId, "仍有包依赖它: ${dependents.joinToString()}")

        hooks.emit(HookEvent(HookMatrix.PKG_BEFORE_UNINSTALL, KValue.obj("id" to pkgId)))
        closeSessions(pkgId)
        hooks.unsubscribeByPackage(pkgId)
        cache.invalidate(pkgId)
        registry.remove(pkgId, rec.version.toString())
    }

    fun setEnabled(pkgId: String, on: Boolean) {
        registry.setEnabled(pkgId, on)
        hooks.enable(pkgId, on)
        if (!on) closeSessions(pkgId)
        hooks.notifyObservers(HookEvent(if (on) HookMatrix.PKG_ON_ENABLE else HookMatrix.PKG_ON_DISABLE,
            KValue.obj("id" to pkgId)))
    }

    // ---------------- 会话 ----------------

    private fun ensureSession(rec: PackageRecord, runtime: RuntimeUnit): EngineSession {
        val key = "${rec.id}@${rec.version}:${runtime.id}"
        sessions[key]?.let { if (it.state == SessionState.READY) return it }
        val src = registry.source(rec.id, rec.version.toString())
            ?: throw KaleidoException.Engine("找不到包内容: ${rec.id}")
        // JVM_DEX 的内置包，类在宿主 classpath 上、不对应包内文件（runtime.entry 即类全名），
        // 此时 src.read 返回 null —— 传空字节，由引擎按类名加载即可。
        // 外部包：dex 字节可放在 runtime.entry 路径、或常规 classes.dex、或 entry 类名对应路径。
        val bytes = src.read(runtime.entry)
            ?: src.read("classes.dex")
            ?: src.read("${runtime.entry.replace('.', '/')}.dex")
            ?: ByteArray(0)

        val engine = EngineFabric.resolveAvailable(runtime, rec.id)
        val session = engine.createSession(rec.id, runtime, hostBridge, gate)
        // 先注入插件 App 上下文（私有目录/缓存），再加载——加载完成时正好能发 CREATE。
        appContextOf(rec.id)?.let { session.attachApp(it) }
        val r = session.load(bytes, runtime.entry.substringAfterLast('/'))
        if (r is KValue.Err) throw KaleidoException.Engine("${rec.id}/${runtime.id} 加载失败: ${r.message}")
        sessions[key] = session
        return session
    }

    private fun closeSessions(pkgId: String) {
        sessions.keys.filter { it.startsWith("$pkgId@") }.forEach { k ->
            sessions.remove(k)?.let { s ->
                runCatching { s.onAppLifecycle(AppLifecycle.DESTROY, appContextOf(pkgId)) }
                runCatching { s.close() }
            }
        }
    }

    private fun registerHooks(rec: PackageRecord) {
        rec.manifest.hooks.forEachIndexed { i, h ->
            val (rtId, fn) = rec.manifest.resolveTarget(h.target)
            val rt = rec.manifest.runtimeById(rtId)
            hooks.subscribe(
                Subscription(
                    id = "${rec.id}#hook$i",
                    pkgId = rec.id,
                    point = h.point,
                    priority = h.priority,
                    mode = h.mode,
                    async = h.async,
                    timeoutMs = h.timeoutMs,
                    handler = { event ->
                        val out = callRaw(rec, rt, fn, listOf(event.payload))
                        interpretHookResult(out, h.mode)
                    },
                )
            )
        }
    }

    private fun interpretHookResult(v: KValue, mode: HookMode): HookResult = when {
        v is KValue.Err -> HookResult.Continue
        v is KValue.Obj && v.value["veto"]?.asBoolOr() == true ->
            HookResult.Veto(v.value["reason"]?.asString() ?: "被插件否决", v.value["payload"])
        v is KValue.Obj && v.value["__kSkip"] != null -> HookResult.Continue
        else -> if (mode == HookMode.OBSERVE) HookResult.Continue else HookResult.Modify(v)
    }

    // ---------------- 调用 ----------------

    /** 直接调用某个 runtime 的导出函数（内部用，不走权限/配额）。 */
    private fun callRaw(rec: PackageRecord, rt: RuntimeUnit, fn: String, args: List<KValue>): KValue {
        val session = runCatching { ensureSession(rec, rt) }
            .getOrElse { return if (it is KaleidoException) it.toKValue() else KValue.fail("E_ENGINE", it.message ?: "引擎异常") }
        val ctx = InvokeContext(rec.id, rt.id, null, hostBridge, gate)
        return session.invoke(fn, args, ctx)
    }

    /**
     * 调用一个 unit。
     * ref 支持三种形式：
     *   "greet"          全局 unit 名（自动定位提供它的包）
     *   "dev.x.hello:greet"  指定包
     *   配合 McpBridge 还能是 "mcp://server/tool"
     */
    fun invokeUnit(ref: String, args: KValue = KValue.Null, callerPkg: String? = null, timeoutMs: Int? = null): KValue {
        val parsed = resolvers.parse(ref)
        val (rec, unit) = resolvers.resolve(parsed, callerPkg)
        if (!rec.enabled) return KValue.fail("E_DISABLED", "包 ${rec.id} 已停用")

        val quota = quotas["${rec.id}@${rec.version}"] ?: Quota.from(rec.manifest.sandbox)
        val effectiveTimeout = timeoutMs ?: unit.timeoutMs

        // 1) 权限
        (unit.capabilities + listOfNotNull(if (unit.runtime == "mcp") "net.mcp" else null)).forEach {
            if (!gate.isGranted(rec.id, it))
                return KValue.Err("E_PERMISSION", "包 ${rec.id} 未获授权: $it")
        }

        // 2) 缓存
        val cacheKey = "${rec.id}:${unit.name}:${Json.write(Json.fromK(args))}"
        if (enableCache && unit.cacheTtlMs > 0 && unit.idempotent) {
            cache.get(cacheKey)?.let { return it }
        }

        // 3) 配额
        try {
            quota.enter()
        } catch (e: KaleidoException) {
            return e.toKValue()
        }

        val ctx = InvokeContext(
            pkgId = rec.id,
            runtimeId = rec.manifest.resolveTarget(unit.target).first,
            unitName = unit.name,
            host = hostBridge,
            gate = gate,
            deadlineMs = System.currentTimeMillis() + effectiveTimeout,
        )

        return try {
            // 4) tool.beforeCall（可被改写/否决）
            val before = try {
                hooks.emit(HookEvent(HookMatrix.TOOL_BEFORE_CALL, KValue.obj(
                    "unit" to unit.name, "pkg" to rec.id, "args" to args)))
            } catch (v: HookBus.HookVetoed) {
                return KValue.Err("E_VETOED", v.message)
            }
            val finalArgs = if (before is KValue.Obj) (before.value["args"] ?: args) else args

            val rt = rec.manifest.runtimeById(ctx.runtimeId)
            val session = ensureSession(rec, rt)
            val out = session.invoke(rec.manifest.resolveTarget(unit.target).second, listOf(finalArgs), ctx)

            // 5) tool.afterCall —— 可改写结果，但只在显式给出 result 字段时才替换，
            //    否则"只观察不改写"的钩子会把原始结果套进 {unit,pkg,result} 里
            val after = try {
                hooks.emit(HookEvent(HookMatrix.TOOL_AFTER_CALL,
                    KValue.obj("unit" to unit.name, "pkg" to rec.id, "result" to out)))
            } catch (_: HookBus.HookVetoed) { out }
            val finalOut = (after as? KValue.Obj)?.value?.get("result") ?: out

            if (enableCache && unit.cacheTtlMs > 0 && unit.idempotent && finalOut !is KValue.Err) {
                cache.put(cacheKey, finalOut, unit.cacheTtlMs)
            }
            finalOut
        } catch (t: Throwable) {
            hooks.notifyObservers(HookEvent(HookMatrix.TOOL_ON_ERROR,
                KValue.obj("unit" to unit.name, "pkg" to rec.id, "error" to (t.message ?: ""))))
            if (t is KaleidoException) t.toKValue() else KValue.fail("E_UNIT", t.message ?: "未知错误")
        } finally {
            quota.exit()
        }
    }

    // ---------------- UI ----------------

    /** 渲染一个 UI 表面。插件只返回纯数据树，宿主侧渲染器负责画。 */
    fun renderSurface(pkgId: String, surfaceId: String, state: Map<String, Any?> = emptyMap()): UiNode? {
        val rec = registry.get(pkgId) ?: return null
        val surface = rec.manifest.ui.firstOrNull { it.id == surfaceId } ?: return null
        val (rtId, fn) = rec.manifest.resolveTarget(surface.render)
        val rt = rec.manifest.runtimeById(rtId)
        val stateK = KValue.obj("state" to state)
        val out = callRaw(rec, rt, fn, listOf(stateK))
        val node = UiCodec.fromK(out) ?: return null
        UiLint.check(node).takeIf { it.isNotEmpty() }?.let { issues ->
            hostBridge.log(LogLevel.WARN, "kaleido.ui", "UI 校验问题: $issues",
                InvokeContext(pkgId, rtId, null, hostBridge, gate))
        }
        return node
    }

    /** 宿主把用户交互回灌给插件。 */
    fun dispatchUiAction(pkgId: String, action: UiAction): KValue {
        val rec = registry.get(pkgId) ?: return KValue.fail("E_NOT_FOUND", "包未安装: $pkgId")
        val surface = rec.manifest.ui.firstOrNull { it.id == action.surfaceId }
            ?: return KValue.fail("E_NOT_FOUND", "UI 表面未注册: ${action.surfaceId}")
        val target = surface.onAction ?: return KValue.fail("E_NO_HANDLER", "surface ${surface.id} 未声明 onAction")

        // 合并状态：插件可以在 onAction 里返回新的 state，宿主用它刷新 UI
        val store = uiState.getOrPut("$pkgId:${surface.id}") { ConcurrentHashMap() }
        action.state.forEach { (k, v) -> store[k] = v }
        val (rtId, fn) = rec.manifest.resolveTarget(target)
        val rt = rec.manifest.runtimeById(rtId)
        val out = callRaw(rec, rt, fn, listOf(KValue.obj(
            "surfaceId" to action.surfaceId,
            "nodeId" to action.nodeId,
            "actionId" to action.actionId,
            "payload" to action.payload,
            "state" to store.toMap(),
        )))
        hooks.notifyObservers(HookEvent(HookMatrix.UI_ACTION, KValue.obj(
            "pkg" to pkgId, "surface" to action.surfaceId, "action" to action.actionId)))
        return out
    }

    // ---------------- Hook ----------------

    fun emit(point: String, payload: KValue.Obj): KValue = hooks.emit(HookEvent(point, payload))
    fun notify(point: String, payload: KValue.Obj) = hooks.notifyObservers(HookEvent(point, payload))

    // ---------------- 供 AI 使用的工具描述 ----------------

    /** 生成 OpenAI/Anthropic 兼容的 function schema，直接喂给模型。 */
    fun toolSchemas(limit: Int = 128): List<Map<String, Any?>> =
        registry.allEnabled().flatMap { rec ->
            rec.manifest.units.map { u ->
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to u.name,
                        "description" to (u.description.ifBlank { u.title["zh"] ?: u.title["en"] ?: u.name }),
                        "parameters" to (u.params ?: mapOf(
                            "type" to "object", "properties" to emptyMap<String, Any?>(), "required" to emptyList<String>()
                        )),
                    ),
                    "_kaleido" to mapOf("pkg" to rec.id, "version" to rec.version.toString()),
                )
            }
        }.take(limit)

    // ---------------- 观测 ----------------

    fun stats(): String = buildString {
        appendLine("=== Kaleido Runtime ===")
        appendLine("registry: ${registry.stats()}")
        appendLine("sessions: ${sessions.size}")
        appendLine("hook subscriptions: ${hooks.size}")
        appendLine("capabilities: ${capabilities.names().size}")
        appendLine("cache entries: ${cache.size()}")
        appendLine()
        append(EngineFabric.snapshot())
    }

    fun installedPackages(): List<PackageRecord> = registry.all()

    /** 若该包是自动迁移来的（如 ToolPkg），返回迁移报告，否则 null。 */
    fun migrationReportOf(pkgId: String): String? = migrations[pkgId]

    // ---------------- 插件 App 级上下文与生命周期 ----------------

    /** 计算某包的 App 上下文（私有 filesDir/cacheDir）。宿主未配置时为 null。 */
    fun appContextOf(pkgId: String): KaleidoAppContext? = appContextProvider?.invoke(pkgId)

    /**
     * 转发生命周期事件给某包的所有 runtime 会话。
     * 由 [com.ai.assistance.quro.kaleidobox.KaleidoActivity] 在 onResume/onPause/onDestroy 调用，
     * 让插件像真 App 一样响应前后台与销毁。
     */
    fun notifyLifecycle(pkgId: String, event: AppLifecycle) {
        val rec = registry.get(pkgId) ?: return
        val appCtx = appContextOf(pkgId)
        rec.manifest.runtime.forEach { rt ->
            val key = "${pkgId}@${rec.version}:${rt.id}"
            sessions[key]?.onAppLifecycle(event, appCtx)
        }
    }

    // ---------------- Builder ----------------

    class Builder {
        private val caps = CapabilityRegistry()
        private val gateBuilder = { InMemoryPermissionGate() }
        private var gate: PermissionGate? = null
        private var registry = PackageRegistry()
        private val logger: (LogLevel, String, String, InvokeContext) -> Unit = { l, t, m, _ ->
            println("[${l.name}] $t: $m")
        }
        var enableCache: Boolean = true
        /** 插件 App 级持久化存储根目录（宿主传入 files 下的某个目录）。 */
        var storageDir: java.io.File? = null
        /** 计算某包 App 上下文（私有 filesDir/cacheDir）的工厂。 */
        var appContextProvider: ((String) -> KaleidoAppContext?)? = null

        fun capabilities(block: CapabilityRegistry.() -> Unit) = apply { caps.block() }

        fun engines(block: EngineFabric.() -> Unit) = apply { EngineFabric.block() }

        fun gate(g: PermissionGate) = apply { gate = g }
        fun registry(r: PackageRegistry) = apply { registry = r }
        fun storageDir(dir: java.io.File) = apply { storageDir = dir }
        fun appContextProvider(block: (String) -> KaleidoAppContext?) = apply { appContextProvider = block }

        fun build(): KaleidoRuntime {
            val g = gate ?: gateBuilder()
            lateinit var runtime: KaleidoRuntime
            val bridge = DefaultHostBridge(
                capabilities = caps,
                gate = g,
                unitInvoker = { ref, args, ctx -> runtime.invokeUnit(ref, args, ctx.pkgId) },
                resourceReader = { key, ctx ->
                    val rec = registry.get(ctx.pkgId)
                    val res = rec?.manifest?.resources?.firstOrNull { it.key == key }
                    val src = rec?.let { registry.source(it.id, it.version.toString()) }
                    if (res == null || src == null) KValue.Err("E_RESOURCE", "资源不存在: $key")
                    else src.read(res.path)?.let { KValue.Bytes(it) }
                        ?: KValue.Err("E_RESOURCE", "资源读取失败: ${res.path}")
                },
                kvGet = { key, ctx ->
                    runtime.kvOf(ctx.pkgId)[key] ?: KValue.Null
                },
                kvSet = { key, v, ctx -> runtime.kvOf(ctx.pkgId)[key] = v; KValue.ok(true) },
                hookEmitter = { point, payload, ctx -> runtime.emit(point, payload) },
                logger = logger,
            )
            runtime = KaleidoRuntime(
                registry, HookBus { where, t ->
                    logger(LogLevel.ERROR, where, t.message ?: "", InvokeContext("_host", "_", null, NoopHostBridge, g))
                }, caps, g, bridge, enableCache,
                storageDir?.let { PluginStorage(it) },
                appContextProvider,
            )
            return runtime
        }
    }

    internal fun kvOf(pkgId: String): MutableMap<String, KValue> =
        pluginStorage?.store(pkgId) ?: kvStore.getOrPut(pkgId) { ConcurrentHashMap() }

    companion object {
        fun builder() = Builder()
    }
}
