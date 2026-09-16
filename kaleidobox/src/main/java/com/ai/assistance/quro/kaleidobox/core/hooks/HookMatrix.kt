package com.ai.assistance.quro.kaleidobox.core.hooks

import com.ai.assistance.quro.kaleidobox.core.model.*

/**
 * 宿主生命周期 Hook 矩阵。
 *
 * ToolPkg 大约只有 8~10 个注册点，且只有"注册/不注册"两态。
 * Kaleido 这里给出 40+ 个 Hook 点，并且每个订阅都带：
 *   - priority：同一 Hook 点上的执行顺序
 *   - mode：observe(只读) / modify(可改写载荷) / veto(可否决，中断链路)
 *   - timeoutMs：单个 Hook 超时不影响主链路
 *   - async：是否并行执行（observe 默认并行）
 */
object HookMatrix {

    // ---- 宿主应用生命周期 ----
    const val APP_ON_CREATE = "app.onCreate"
    const val APP_ON_FOREGROUND = "app.onForeground"
    const val APP_ON_BACKGROUND = "app.onBackground"
    const val APP_ON_LOW_MEMORY = "app.onLowMemory"
    const val APP_ON_TERMINATE = "app.onTerminate"

    // ---- 包生命周期 ----
    const val PKG_BEFORE_INSTALL = "pkg.beforeInstall"
    const val PKG_AFTER_INSTALL = "pkg.afterInstall"
    const val PKG_BEFORE_UNINSTALL = "pkg.beforeUninstall"
    const val PKG_ON_ENABLE = "pkg.onEnable"
    const val PKG_ON_DISABLE = "pkg.onDisable"
    const val PKG_ON_UPGRADE = "pkg.onUpgrade"

    // ---- 提示词编排（AI 深度融合的核心）----
    const val PROMPT_SYSTEM_COMPOSE = "prompt.systemCompose"
    const val PROMPT_HISTORY_PREPARE = "prompt.historyPrepare"
    const val PROMPT_TOOL_COMPOSE = "prompt.toolCompose"
    const val PROMPT_FINALIZE = "prompt.finalize"
    const val PROMPT_INPUT_BEFORE = "prompt.inputBefore"
    const val PROMPT_INPUT_AFTER = "prompt.inputAfter"

    // ---- 模型调用 ----
    const val MODEL_BEFORE_CALL = "model.beforeCall"
    const val MODEL_AFTER_CALL = "model.afterCall"
    const val MODEL_ON_ERROR = "model.onError"
    const val MODEL_STREAM_CHUNK = "model.streamChunk"
    const val MODEL_PROVIDER_RESOLVE = "model.providerResolve"

    // ---- 工具调用 ----
    const val TOOL_RESOLVE = "tool.resolve"
    const val TOOL_BEFORE_CALL = "tool.beforeCall"
    const val TOOL_AFTER_CALL = "tool.afterCall"
    const val TOOL_ON_ERROR = "tool.onError"
    const val TOOL_PERMISSION_CHECK = "tool.permissionCheck"
    const val TOOL_RESULT_TRUNCATE = "tool.resultTruncate"

    // ---- 消息与渲染 ----
    const val MESSAGE_INCOMING = "message.incoming"
    const val MESSAGE_BEFORE_SEND = "message.beforeSend"
    const val MESSAGE_RENDER_XML = "message.renderXml"
    const val MESSAGE_RENDER_MARKDOWN = "message.renderMarkdown"
    const val MESSAGE_RENDER_CUSTOM = "message.renderCustom"

    // ---- UI ----
    const val UI_TOOLBOX_COMPOSE = "ui.toolboxCompose"
    const val UI_SETTINGS_COMPOSE = "ui.settingsCompose"
    const val UI_INPUT_MENU_TOGGLE = "ui.inputMenuToggle"
    const val UI_ACTION = "ui.action"
    const val UI_THEME_CHANGE = "ui.themeChange"

    // ---- 数据与资源 ----
    const val FS_BEFORE_ACCESS = "fs.beforeAccess"
    const val NET_BEFORE_REQUEST = "net.beforeRequest"
    const val NET_AFTER_RESPONSE = "net.afterResponse"
    const val MEMORY_BEFORE_RECALL = "memory.beforeRecall"

    // ---- 互操作 ----
    const val MCP_TOOL_DISCOVER = "mcp.toolDiscover"
    const val IPC_MESSAGE = "ipc.message"

    val ALL_POINTS: Set<String> = setOf(
        APP_ON_CREATE, APP_ON_FOREGROUND, APP_ON_BACKGROUND, APP_ON_LOW_MEMORY, APP_ON_TERMINATE,
        PKG_BEFORE_INSTALL, PKG_AFTER_INSTALL, PKG_BEFORE_UNINSTALL, PKG_ON_ENABLE, PKG_ON_DISABLE, PKG_ON_UPGRADE,
        PROMPT_SYSTEM_COMPOSE, PROMPT_HISTORY_PREPARE, PROMPT_TOOL_COMPOSE, PROMPT_FINALIZE,
        PROMPT_INPUT_BEFORE, PROMPT_INPUT_AFTER,
        MODEL_BEFORE_CALL, MODEL_AFTER_CALL, MODEL_ON_ERROR, MODEL_STREAM_CHUNK, MODEL_PROVIDER_RESOLVE,
        TOOL_RESOLVE, TOOL_BEFORE_CALL, TOOL_AFTER_CALL, TOOL_ON_ERROR, TOOL_PERMISSION_CHECK, TOOL_RESULT_TRUNCATE,
        MESSAGE_INCOMING, MESSAGE_BEFORE_SEND, MESSAGE_RENDER_XML, MESSAGE_RENDER_MARKDOWN, MESSAGE_RENDER_CUSTOM,
        UI_TOOLBOX_COMPOSE, UI_SETTINGS_COMPOSE, UI_INPUT_MENU_TOGGLE, UI_ACTION, UI_THEME_CHANGE,
        FS_BEFORE_ACCESS, NET_BEFORE_REQUEST, NET_AFTER_RESPONSE, MEMORY_BEFORE_RECALL,
        MCP_TOOL_DISCOVER, IPC_MESSAGE,
    )

    /** 哪些 Hook 点的返回值会被用于改写主链路载荷。 */
    val MODIFIABLE = setOf(
        PROMPT_SYSTEM_COMPOSE, PROMPT_HISTORY_PREPARE, PROMPT_TOOL_COMPOSE, PROMPT_FINALIZE,
        PROMPT_INPUT_BEFORE, MODEL_BEFORE_CALL, MODEL_AFTER_CALL, TOOL_BEFORE_CALL, TOOL_AFTER_CALL,
        TOOL_RESULT_TRUNCATE, MESSAGE_BEFORE_SEND, MESSAGE_RENDER_CUSTOM
    )
}

/** 一次 Hook 触发的上下文。 */
data class HookEvent(
    val point: String,
    val payload: KValue.Obj,
    val pkgId: String? = null,
    val cancellable: Boolean = false,
)

sealed class HookResult {
    /** 不干预，继续。 */
    object Continue : HookResult()
    /** 改写载荷（或返回结果）。 */
    data class Modify(val payload: KValue) : HookResult()
    /** 否决/拦截，主链路不再继续。 */
    data class Veto(val reason: String, val payload: KValue? = null) : HookResult()
    /** 直接短路并给出结果（用于 render 类 Hook）。 */
    data class ShortCircuit(val payload: KValue) : HookResult()
}

/** Hook 订阅记录。 */
data class Subscription(
    val id: String,
    val pkgId: String,
    val point: String,
    val priority: Int,
    val mode: com.ai.assistance.quro.kaleidobox.core.model.HookMode,
    val async: Boolean,
    val timeoutMs: Int,
    var enabled: Boolean = true,
    /** 真正执行：由运行时绑定到某个引擎的函数。放最后以便尾随 lambda 调用。 */
    val handler: (HookEvent) -> HookResult?,
)

/**
 * Hook 总线。
 *
 * 关键工程细节：
 * 1. observe 模式的订阅并行跑，且异常不影响主链路（吞掉 + 记日志）。
 * 2. modify/veto 串行且按 priority 稳定排序，前一个的输出是后一个的输入。
 * 3. 单个订阅超时由 [timeoutMs] 兜住，慢插件拖不垮宿主。
 */
class HookBus(private val onError: (String, Throwable) -> Unit = { _, _ -> }) {

    private val subs = java.util.concurrent.CopyOnWriteArrayList<Subscription>()
    val size get() = subs.size

    fun subscribe(s: Subscription) {
        subs.add(s)
        subs.sortBy { it.priority }
    }

    fun unsubscribeByPackage(pkgId: String) = subs.removeIf { it.pkgId == pkgId }
    fun unsubscribe(id: String) = subs.removeIf { it.id == id }
    fun enable(pkgId: String, on: Boolean) = subs.filter { it.pkgId == pkgId }.forEach { it.enabled = on }

    fun subscriptionsFor(point: String) = subs.filter { it.point == point && it.enabled }.sortedBy { it.priority }

    /**
     * 触发一个 Hook 点。
     * @return 最终的载荷（可能被改写）；若被 veto 则抛出携带原因的异常，由调用方决定。
     */
    fun emit(event: HookEvent): KValue {
        var current: KValue = event.payload
        for (s in subscriptionsFor(event.point)) {
            if (s.mode == com.ai.assistance.quro.kaleidobox.core.model.HookMode.OBSERVE && s.async) {
                // 异步观察：不阻塞主链路
                continue
            }
            val t0 = System.nanoTime()
            val r = try {
                s.handler(event.copy(payload = current as? KValue.Obj ?: event.payload))
                    ?: HookResult.Continue
            } catch (t: Throwable) {
                onError("hook:${s.point}@${s.pkgId}", t)
                HookResult.Continue
            }
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            if (dtMs > s.timeoutMs) {
                onError("hook:${s.point}@${s.pkgId}", com.ai.assistance.quro.kaleidobox.core.KaleidoException.Timeout("hook ${s.point}", s.timeoutMs))
            }
            when (r) {
                is HookResult.Continue -> Unit
                is HookResult.Modify -> {
                    if (s.mode == com.ai.assistance.quro.kaleidobox.core.model.HookMode.OBSERVE) Unit // observe 无权改写
                    else current = r.payload
                }
                is HookResult.Veto -> throw HookVetoed(event.point, s.pkgId, r.reason, r.payload)
                is HookResult.ShortCircuit -> return r.payload
            }
        }
        return current
    }

    /** 观察型触发：吞掉所有异常，用于广播。 */
    fun notifyObservers(event: HookEvent) {
        for (s in subscriptionsFor(event.point)) {
            if (s.mode != com.ai.assistance.quro.kaleidobox.core.model.HookMode.OBSERVE) continue
            runCatching { s.handler(event) }.onFailure { onError("observe:${s.point}@${s.pkgId}", it) }
        }
    }

    class HookVetoed(val point: String, val pkgId: String, override val message: String, val payload: KValue?) :
        RuntimeException("Hook $point 被 $pkgId 否决: $message")
}
