package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.model.QuroLocalModel

/**
 * 本地离线推理引擎。
 *
 * 职责边界：本接口只定义「离线模型如何执行一次对话」。模型文件的登记、路径管理、
 * 文件夹扫描由 [com.ai.assistance.quro.core.model.QuroLocalModelRepository] 负责。
 *
 * 当前提供 [QuroLocalEnginePlaceholder]：在原生运行时（MNN / llama.cpp 的 Android AAR）
 * 接入前返回明确提示，保证应用不崩溃、且用户能感知「模型已登记、执行待接入」。
 * 接入原生库后，只需替换 [run] 实现即可（无界面改动）。
 */
interface QuroLocalEngine {
    /**
     * 引擎是否**自己负责**模型的加载/常驻。
     *
     * 为什么需要这个标志：跨进程实现（full 风味的 `QuroLocalEngineRemote`）在
     * `:llm` 独立进程里跑推理，模型必须加载到**那个进程**的内存里。
     * 而主进程的 `LocalModelSessionHolder` 只看得见自己的地址空间 ——
     * 调用方若照旧先 `load()` 再 `run()`，会把这 GB 级权重加载进**主进程**，
     * 正是进程隔离要避免的那件事（低端机上直接被 LMK 带走）。
     *
     * 因此调用方（`QuroAssistant.routeLocal`）必须先问这个标志：
     *   - false（进程内实现）：先确保已加载，再跑 —— 既有行为不变；
     *   - true（跨进程实现）：跳过本地加载，由引擎进程自行确保就绪。
     */
    val managesOwnLoading: Boolean get() = false

    /**
     * 执行一次本地推理。
     *
     * @param contextWindow 会话上下文窗口（token）。**必须**由调用方传入与
     *   [com.ai.assistance.quro.core.model.QuroModelConfig.contextWindow] 一致的值：
     *   此前本地会话把 n_ctx 硬编码成 2048，而上层按 16000 token 预算拼 prompt，
     *   原生层只能从**头部**把 prompt 砍到 1536 token（system 提示词被腰斩）。
     * @param onToken 流式增量回调，参数为**累计**文本（与云端 onToken 语义一致）。
     *   传 null 表示不需要流式。本地推理在手机 CPU 上单次可达数分钟，
     *   不接流式则 UI 全程空白 → 用户观感即「不闪退但也不回复」。
     */
    fun run(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        contextWindow: Int = 0,
        toolSpecsJson: String? = null,
        onToken: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null,
        isCanceled: () -> Boolean = { false },
    ): QuroLlmResult
}

/** 占位实现：原生运行时未接入时的降级返回。 */
object QuroLocalEnginePlaceholder : QuroLocalEngine {    override fun run(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        contextWindow: Int,
        toolSpecsJson: String?,
        onToken: ((String) -> Unit)?,
        onThinking: ((String) -> Unit)?,
        isCanceled: () -> Boolean,
    ): QuroLlmResult {
        val typeName = if (model.type == com.ai.assistance.quro.core.model.QuroLocalModelType.LLAMA_CPP) "llama.cpp" else "MNN"
        return QuroLlmResult.Error(
            "本地离线模型「${model.name}」($typeName) 已登记，路径：${model.path}。" +
                "原生推理运行时（MNN / llama.cpp AAR）尚未接入，暂不能执行推理。请在模型配置中改用联网 API，或等待本地引擎接入。"
        )
    }
}

/**
 * 本地引擎「跑在哪个进程」的偏好读写。
 *
 * 为什么做成开关而不是直接换实现（硬规则第 1 条的落地节奏）：
 *   进程隔离是**运行期行为变更** —— 推理从主进程搬到 `:llm` 进程，
 *   绑定、回调、取消、OOM 降级全都是新的执行路径。
 *   规格书给的落地顺序也是「先跑通全流程 → 再加进程隔离」。
 *   保留开关意味着：万一在某个机型上隔离路径有问题，用户**自己**就能切回
 *   进程内跑（而不是等下一个版本），这是这类底层改造应有的退路。
 *
 * 为什么默认关闭：新路径尚未经过真机验证。默认开启等于让所有用户替我们试错。
 *   真机验证通过后，这里把 DEFAULT 改成 true 即可完成切换（一处改动）。
 */
object QuroLocalEnginePrefs {
    /** 与 QuroChatViewModel 共用的偏好文件（同 `app_language` 等 UI 偏好）。 */
    private const val PREF_FILE = "quro_ui"

    /** 键名：true = 本地推理跑在 `:llm` 独立进程。 */
    const val KEY_ISOLATED_PROCESS = "local_engine_isolated_process"

    /** 默认值。真机验证跨进程路径后可改为 true 完成切换。 */
    const val DEFAULT_ISOLATED_PROCESS = false

    fun isIsolated(context: android.content.Context): Boolean = runCatching {
        context.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_ISOLATED_PROCESS, DEFAULT_ISOLATED_PROCESS)
    }.getOrDefault(DEFAULT_ISOLATED_PROCESS)

    fun set(context: android.content.Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ISOLATED_PROCESS, enabled).apply()
        }
    }

    // ══════════════════ L5 · 温控自适应开关 ══════════════════
    //
    // 为什么做成用户开关而不是直接默认打开：
    //   温控会在 decode 循环里**主动下调线程数**。这是运行期行为变更 ——
    //   在温控策略激进的机型上，可能出现「一直跑在低档」的体感回落。
    //   默认关闭 + 用户可开，等于给了一条不用等下一版的退路。
    //
    // 与进程隔离开关的风险等级不同，所以默认值也不同：
    //   隔离路径出问题会**崩**，一眼可见、必须回退；
    //   温控路径出问题只是**变慢**，极难归因，且不会有人来报。
    //   越是无声的失败，默认值越要保守。

    /** 键名：true = 本地推理启用温控自适应（L5 主动降档）。 */
    const val KEY_THERMAL_ADAPTIVE = "local_engine_thermal_adaptive"

    /** 默认关闭。理由见上。 */
    const val DEFAULT_THERMAL_ADAPTIVE = false

    /** 采样间隔：每 2 秒一次，兼顾及时性与开销（规格建议值）。 */
    const val THERMAL_POLL_MS = 2000

    /** 当前是否启用温控自适应。 */
    fun isThermalAdaptive(): Boolean = runCatching {
        prefs()?.getBoolean(KEY_THERMAL_ADAPTIVE, DEFAULT_THERMAL_ADAPTIVE)
            ?: DEFAULT_THERMAL_ADAPTIVE
    }.getOrDefault(DEFAULT_THERMAL_ADAPTIVE)

    fun setThermalAdaptive(enabled: Boolean) {
        runCatching {
            prefs()?.edit()?.putBoolean(KEY_THERMAL_ADAPTIVE, enabled)?.apply()
        }
    }

    /**
     * 传给原生层的采样间隔：**0 = 禁用**。
     *
     * 刻意不抛异常：0 是原生层的「不干预」契约，任何时候都必须能安全降级到它。
     * 读偏好失败也返回 0（宁可没有温控，也不要因为一个开关读不到就影响推理）。
     */
    fun thermalPollMs(): Int = if (isThermalAdaptive()) THERMAL_POLL_MS else 0

    /**
     * 取偏好文件，**不需要调用方持有 Context**。
     *
     * 三个调用点（LocalModelSessionHolder / QuroLocalEngineNative.runLlama /
     * QuroLlmEngineHost.createLlama）都在无 Context 的路径上，后者还运行在
     * `:llm` 子进程 —— 恰是最需要读这个开关的地方。
     *
     * QuroApplication.appCtx 在 attachBaseContext 阶段就已赋值，且**早于**
     * isMainProcess() 守卫，所以主进程与所有副进程都拿得到。
     */
    private fun prefs(): android.content.SharedPreferences? =
        com.ai.assistance.quro.activity.QuroApplication.appCtx
            ?.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
}
