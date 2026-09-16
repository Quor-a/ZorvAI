package com.ai.assistance.quro.kaleidobox.core.engine

import com.ai.assistance.quro.kaleidobox.core.model.KValue

/**
 * JVM/Dex 工具包的契约。
 *
 * 一个 Kotlin/Java 工具包 = 一个实现了 [KaleidoToolkit] 的类，
 * 其完全限定类名写在清单 `runtime.entry` 里，经 [com.ai.assistance.quro.kaleidobox.android.engine.JvmDexEngine]
 * 用 DexClassLoader（外部包）或应用类加载器（内置包）加载后实例化。
 *
 * 这是 kaleido 在 Android 上**唯一零外部二进制即可真正运行**的引擎路径：
 * 宿主 App 自带的 Kotlin/Java 代码跑在这里，完全复用宿主的类加载器与能力桥。
 */
interface KaleidoToolkit {

    /** 加载后由引擎调用一次，注入宿主能力桥。 */
    fun attach(host: ToolkitHost)

    /** 调用一个 unit（target 的 fn 部分）。args 已是标准化后的 KValue。 */
    fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue

    /**
     * 生命周期钩子（可选重写）。宿主在加载后发 [AppLifecycle.CREATE]，
     * 并在 [com.ai.assistance.quro.kaleidobox.KaleidoActivity] 前后台切换/销毁时转发对应事件。
     * 默认空实现，现有工具包无需改动即可编译。
     */
    fun onAppLifecycle(event: AppLifecycle, appContext: KaleidoAppContext) {}
}

/**
 * 宿主在工具包侧的投影：工具包只能看到这一层，不能直接拿 Activity / Context。
 * 想用宿主能力 = 走 [call]（受权限门控）；想打日志 = 走 [log]。
 */
interface ToolkitHost {
    /** 调用宿主能力（如 "sys.now" / "data.kv" / "ui.toast"），受 PermissionGate 约束。 */
    fun call(capability: String, args: KValue): KValue

    /** 结构化日志，宿主统一收集。 */
    fun log(level: String, tag: String, message: String)

    /** 本插件的 App 级上下文（私有文件/缓存目录）。由引擎在加载后注入，未注入时为 null。 */
    val appContext: KaleidoAppContext? get() = null
}
