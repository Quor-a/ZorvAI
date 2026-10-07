package com.codecanvas.core.script

/**
 * 脚本引擎统一抽象。
 *
 * 每种语言一个实现，全部产出同一份 [DrawList]，因此上层完全无需知道跑的是 JS 还是 Lua。
 */
interface ScriptEngine {

    /** 引擎对应的语言 */
    val language: ScriptLanguage

    /** 是否需要 Android Context（Python 因需启动解释器，返回 true） */
    val requiresContext: Boolean get() = false

    /**
     * 冷启动预热。JS/Lua 引擎首次执行有 100~300ms 初始化开销，建议在后台提前调用。
     */
    suspend fun prepare() {}

    /**
     * 执行脚本。
     *
     * 约定：脚本内部通过全局对象 `canvas` 调用绘图 API（由各引擎注入），
     * 引擎负责把这些调用翻译成 [DrawList]。
     *
     * @param script 脚本源码
     * @param out 指令收集器
     * @param sandbox 沙箱约束（超时/内存/权限）
     * @param bindings 宿主传入的变量，脚本可通过 `args` 访问
     */
    suspend fun execute(
        script: String,
        out: DrawList,
        sandbox: ScriptSandbox = ScriptSandbox(),
        bindings: Map<String, Any?> = emptyMap(),
    ): ScriptResult

    /**
     * 中断正在执行的脚本。宿主看门狗超时后调用。
     * JS / Lua 是同步执行，实际靠引擎侧的中断标志位在回调里抛异常实现软中断。
     */
    fun interrupt() {}

    fun release()
}

enum class ScriptLanguage(
    val id: String,
    val extensions: List<String>,
    /** 该引擎引入的 APK 体积增量，用于选型 */
    val sizeCostKb: Int,
) {
    JAVASCRIPT("javascript", listOf("js", "mjs"), 900),
    LUA("lua", listOf("lua"), 600),
    PYTHON("python", listOf("py"), 12_000),
    KOTLIN_DSL("kotlin-dsl", listOf("kts", "canvas"), 0),
    ;

    companion object {
        fun fromExtension(ext: String): ScriptLanguage? =
            entries.firstOrNull { ext.lowercase() in it.extensions }
    }
}

sealed interface ScriptResult {
    data class Success(
        val commandCount: Int,
        val durationMs: Long,
        /** 脚本 console/print 的输出，便于调试 */
        val logs: List<String> = emptyList(),
    ) : ScriptResult

    sealed interface Failure : ScriptResult {
        val message: String
        val stack: String?

        data class Compile(override val message: String, override val stack: String? = null) : Failure
        data class Runtime(override val message: String, override val stack: String? = null) : Failure
        data class Timeout(val timeoutMs: Long) : Failure {
            override val message: String get() = "脚本执行超时 ${timeoutMs}ms"
            override val stack: String? get() = null
        }

        data class Sandbox(override val message: String, override val stack: String? = null) : Failure
    }
}
