package com.ai.assistance.quro.kaleidobox.android.engine

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.engine.*
import com.ai.assistance.quro.kaleidobox.core.model.*
import com.ai.assistance.quro.kaleidobox.core.security.PermissionGate
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM / Dex 引擎 —— Kaleido 在 Android 上**唯一零外部二进制即可真正运行**的引擎路径。
 *
 * 一个 Kotlin/Java 工具包 = 一个实现了 [KaleidoToolkit] 的类，
 * 其完全限定类名写在清单 `runtime.entry` 里。
 *
 * 两条加载路径：
 *   - 内置包（类在宿主 App classpath 上）：直接用应用 ClassLoader 加载 [RuntimeUnit.entry] 类；
 *   - 外部包（.dex/.apk 字节）：写到优化目录，用 [DexClassLoader] 加载 [RuntimeUnit.entry] 类。
 *
 * 加载后引擎调用一次 [KaleidoToolkit.attach] 注入宿主能力桥，之后每次 unit 调用走 [KaleidoToolkit.invoke]。
 */
class JvmDexEngine(
    private val appClassLoader: ClassLoader = JvmDexEngine::class.java.classLoader!!,
    private val optimizedDir: File? = null,
) : AbstractEngine() {

    override val kind = EngineKind.JVM_DEX
    override val displayName = "JVM / Dex (in-process Kotlin/Java)"
    override val languages = setOf(Lang.KOTLIN, Lang.JAVA)

    override fun isAvailable() = true

    override fun createSession(pkgId: String, runtime: RuntimeUnit, host: HostBridge, gate: PermissionGate): EngineSession {
        val session = JvmDexSession(pkgId, runtime, host, gate, this)
        liveSessions.add(session)
        return session
    }

    /** 供 Session 加载 [KaleidoToolkit] 实现类。 */
    internal fun loadToolkitClass(pkgId: String, runtime: RuntimeUnit, entryBytes: ByteArray): Class<out KaleidoToolkit> {
        val className = runtime.entry
        return try {
            val cls = if (entryBytes.isEmpty()) {
                // 内置包：类已在宿主 classpath 上
                appClassLoader.loadClass(className)
            } else {
                // 外部包：先字节级校验 dex 完整性，把坏 dex 挡在 ART 后台校验线程之外
                // （ART 校验坏 dex 可能直接 SIGBUS 杀进程，无法 try/catch 捕获）。
                DexValidator.assertLoadable(entryBytes, className)
                val dir = (optimizedDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")).apply { mkdirs() }
                // 清掉同名的旧 dex / oat / vdex / art，避免复用上一轮损坏的优化产物导致 SIGBUS。
                val stem = "$pkgId-${runtime.id}"
                dir.listFiles { f -> f.name.startsWith(stem) }?.forEach { runCatching { it.delete() } }
                val dexFile = File(dir, "$stem.dex").also { it.writeBytes(entryBytes) }
                val dcl = DexClassLoader(dexFile.absolutePath, dir.absolutePath, null, appClassLoader)
                // 立即解析目标类，并强制在当前线程同步解析其所有声明方法（含 signature 类型），
                // 把 ART 校验异常引到本线程（VerifyError/NoClassDefFoundError 可被 try/catch 捕获），
                // 而不是留给 ART 后台线程异步崩成 SIGBUS 杀进程。
                val cls = dcl.loadClass(className)
                cls.getDeclaredConstructor()
                runCatching {
                    cls.declaredMethods.forEach { m -> m.parameterTypes; m.returnType; m.exceptionTypes }
                }
                cls
            }
            requireToolkit(cls, pkgId, className)
        } catch (t: Throwable) {
            throw if (t is KaleidoException) t else
                KaleidoException.Engine("工具包类加载失败（包 $pkgId/${runtime.id}）: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun requireToolkit(cls: Class<*>, pkgId: String, className: String): Class<out KaleidoToolkit> =
        (cls as? Class<out KaleidoToolkit>)
            ?: throw KaleidoException.Engine("类 $className 未实现 KaleidoToolkit（包 $pkgId）")
}

/**
 * 一个 JVM_DEX 工具包的一次会话。会话是隔离边界：持有该包的 toolkit 实例与权限/配额通过 [gate] 约束。
 */
class JvmDexSession(
    override val pkgId: String,
    private val runtime: RuntimeUnit,
    private val host: HostBridge,
    private val gate: PermissionGate,
    private val engine: JvmDexEngine,
) : EngineSession {

    override val runtimeId: String = runtime.id
    override val engineKind: EngineKind = EngineKind.JVM_DEX
    override var state: SessionState = SessionState.CREATED
        private set

    private var toolkit: KaleidoToolkit? = null
    private val currentCtx = AtomicReference<InvokeContext?>(null)
    private var appCtx: KaleidoAppContext? = null

    /** 传给工具包的宿主投影：工具包只能看到这一层，不能直接拿 Activity / Context。 */
    private val toolkitHost = object : ToolkitHost {
        override fun call(capability: String, args: KValue): KValue {
            val ctx = currentCtx.get() ?: newCtx(null)
            return host.call(capability, args, ctx)
        }

        override fun log(level: String, tag: String, message: String) {
            val lvl = runCatching { LogLevel.valueOf(level.uppercase()) }.getOrDefault(LogLevel.INFO)
            host.log(lvl, tag, message, currentCtx.get() ?: newCtx(null))
        }

        override val appContext: KaleidoAppContext? get() = appCtx
    }

    private fun newCtx(unitName: String?) = InvokeContext(pkgId, runtimeId, unitName, host, gate)

    override fun attachApp(appContext: KaleidoAppContext) {
        appCtx = appContext
        appContext.mkdirs()
    }

    override fun onAppLifecycle(event: AppLifecycle, appContext: KaleidoAppContext?) {
        val ctx = appContext ?: appCtx ?: return
        val tk = toolkit ?: return
        runCatching { tk.onAppLifecycle(event, ctx) }
    }

    override fun load(entryBytes: ByteArray, entryName: String): KValue {
        if (state == SessionState.READY) return KValue.ok(true) // 幂等
        if (state != SessionState.CREATED && state != SessionState.FAULTED)
            return KValue.ok(true)
        state = SessionState.LOADING
        return try {
            val cls = engine.loadToolkitClass(pkgId, runtime, entryBytes)
            val instance = cls.getDeclaredConstructor().newInstance()
            toolkit = instance
            instance.attach(toolkitHost)
            // 加载完成后立即转发 CREATE，插件可在此初始化私有存储 / 资源。
            appCtx?.let { runCatching { tkSafeOnLifecycle(instance, AppLifecycle.CREATE, it) } }
            state = SessionState.READY
            KValue.ok(true)
        } catch (t: Throwable) {
            state = SessionState.FAULTED
            if (t is KaleidoException) t.toKValue() else KValue.fail("E_LOAD", t.message ?: "工具包加载失败")
        }
    }

    private fun tkSafeOnLifecycle(tk: KaleidoToolkit, event: AppLifecycle, ctx: KaleidoAppContext) {
        runCatching { tk.onAppLifecycle(event, ctx) }
    }

    override fun invoke(fn: String, args: List<KValue>, ctx: InvokeContext): KValue {
        val tk = toolkit ?: return KValue.Err("E_ENGINE", "工具包未加载 (包 $pkgId/${runtime.id})")
        if (state != SessionState.READY) return KValue.Err("E_ENGINE", "session 未就绪: $state")
        currentCtx.set(ctx)
        return try {
            // 门面把 unit 参数包成单元素 List<KValue>，这里还原成单一 KValue 交给工具包。
            val single = args.firstOrNull() ?: KValue.Null
            tk.invoke(fn, single, ctx)
        } catch (t: Throwable) {
            if (t is KaleidoException) t.toKValue() else KValue.fail("E_UNIT", t.message ?: "未知错误")
        } finally {
            currentCtx.set(null)
        }
    }

    override fun hasFunction(fn: String): Boolean {
        // JVM 工具包走单一 invoke 分发，无法静态枚举导出函数；返回 true 表示"不校验 exports"。
        return true
    }

    override fun exportedSymbols(): Set<String>? = null

    override fun close() {
        state = SessionState.CLOSED
        toolkit = null
    }
}
