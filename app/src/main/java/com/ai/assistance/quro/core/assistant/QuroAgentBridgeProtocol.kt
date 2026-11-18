package com.ai.assistance.quro.core.assistant

/**
 * 「厂商助手入口 ↔ QuroAI 完整运行时」的跨进程协议（净室实现，纯常量）。
 *
 * 这个文件同时被 **app 主进程**（Provider 侧）和 **被 LSPosed Hook 的进程**
 * （system_server / SystemUI / Google App / 小布 / 超级小爱）加载，因此：
 *  · 不引用 Context、资源 ID、Application 单例或任何 app 侧运行时；
 *  · 只放协议常量，避免把整个应用初始化链拖进别人的进程。
 *
 * 为什么用 ContentProvider.call 而不是广播 / AIDL / Socket：
 *  · Hook 进程多是别的 UID，读不到本应用私有 SP，广播在 Android 8+ 又受后台限制；
 *  · ContentProvider.call 是同步 binder，system_server 与厂商 App 都能直接调用，
 *    且天然支持返回 Bundle，不必另建长连接或让 Hook 进程持有回调代理。
 *  · 不声明 android:permission：system_server / 厂商 App 不可能持有本应用签名权限，
 *    鉴权改在 Provider.call 内按 Binder.getCallingUid() 做白名单（见 [isCallerAllowed]）。
 */
object QuroAgentBridgeProtocol {

    const val SELF_PACKAGE = "com.ai.assistance.quro"
    const val AUTHORITY = "com.ai.assistance.quro.agent"
    const val CONTENT_URI = "content://$AUTHORITY"

    /** 协议版本；Provider 与 Client 不匹配时直接拒绝，避免字段语义漂移。 */
    const val VERSION = 1

    // ---- method ----
    /** 全能力清单：运行时配置 + 四通道 + 全部工具 + Skills + MCP + 厂商入口能力。 */
    const val METHOD_CAPABILITIES = "quro_agent_capabilities"

    /** 轻量运行时帧：助手跑在哪个进程、当前模型/人格/工具数、四通道可用性。 */
    const val METHOD_RUNTIME = "quro_agent_runtime"

    /** 跑一次完整 Agent 回合（含工具循环、Skills、MCP、记忆、Soul）。 */
    const val METHOD_RUN = "quro_agent_run"

    /** 把一次厂商入口接管的「请求 + 回复」写回 app 侧收件箱（UI 可观测、可回放）。 */
    const val METHOD_DELIVER = "quro_agent_deliver"

    /** 读取最近接管记录。 */
    const val METHOD_INBOX = "quro_agent_inbox"

    /**
     * 按 requestId + 游标增量拉取**回合内事件**（流式逐字 / 思考 / 工具调用 / 重试）。
     * 与 [METHOD_RUN] 配对：RUN 同步阻塞到回合结束，EVENTS 在另一个线程持续轮询，
     * 让厂商助手入口具备与主对话框对等的流式、思考与工具调用显示能力。
     */
    const val METHOD_EVENTS = "quro_agent_events"

    val METHODS: List<String> = listOf(
        METHOD_CAPABILITIES, METHOD_RUNTIME, METHOD_RUN, METHOD_DELIVER, METHOD_INBOX,
        METHOD_EVENTS,
    )

    // ---- extras / result keys ----
    const val EXTRA_VERSION = "protocol_version"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_TEXT = "text"
    const val EXTRA_CONTEXT = "context"
    const val EXTRA_IMAGES = "images"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_PERSONA_ID = "persona_id"
    const val EXTRA_TIMEOUT_MS = "timeout_ms"
    const val EXTRA_RESULT_JSON = "result_json"
    const val EXTRA_REPLY = "reply"
    const val EXTRA_OK = "deliver_ok"
    const val EXTRA_ERROR = "deliver_error"
    const val EXTRA_ELAPSED_MS = "deliver_elapsed_ms"
    const val EXTRA_LITE = "lite"
    /** 请求 app 侧在回合内产出可拉取的流式事件（默认 false，保持旧调用方行为不变）。 */
    const val EXTRA_STREAM_EVENTS = "stream_events"
    const val EXTRA_LIMIT = "limit"
    const val EXTRA_RECORDS = "records"

    // ---- 回合内事件流（METHOD_EVENTS）----
    const val EXTRA_EVENTS_AFTER_SEQ = "events_after_seq"
    const val EXTRA_EVENTS_JSON = "events_json"
    const val EXTRA_EVENTS_LAST_SEQ = "events_last_seq"
    const val EXTRA_EVENTS_DONE = "events_done"

    const val EVENT_SEQ = "seq"
    const val EVENT_TYPE = "type"
    const val EVENT_TS = "ts"
    const val EVENT_OK = "ok"
    const val EVENT_ROUND = "round"
    const val EVENT_KIND = "kind"
    const val EVENT_DELTA = "delta"
    const val EVENT_CHARS = "chars"
    const val EVENT_NAME = "name"
    const val EVENT_CALL_ID = "call_id"
    const val EVENT_ARGS = "args"
    const val EVENT_SUMMARY = "summary"
    const val EVENT_SUCCESS = "success"
    const val EVENT_MESSAGE = "message"
    const val EVENT_ATTEMPT = "attempt"
    const val EVENT_MAX_ATTEMPTS = "max_attempts"
    const val EVENT_DELAY_MS = "delay_ms"

    /** 事件类型；值与 [QuroAgentTurnEvents.emit] 的 type 参数一致。 */
    const val EVENT_RUN_STARTED = "run_started"
    const val EVENT_ROUND_STARTED = "round_started"
    const val EVENT_PROVIDER_REQUEST_STARTED = "provider_request_started"
    const val EVENT_PROVIDER_RESPONSE_STARTED = "provider_response_started"
    const val EVENT_BLOCK_START = "block_start"
    const val EVENT_BLOCK_DELTA = "block_delta"
    const val EVENT_BLOCK_END = "block_end"
    const val EVENT_TOOL_STARTED = "tool_started"
    const val EVENT_TOOL_FINISHED = "tool_finished"
    const val EVENT_MODEL_RETRY = "model_retry"
    const val EVENT_DONE = "done"

    /** [EVENT_KIND] 取值，与 Eta 的 AssistantBlockKind 对齐。 */
    const val BLOCK_TEXT = "TEXT"
    const val BLOCK_THINKING = "THINKING"
    const val BLOCK_TOOL_CALL = "TOOL_CALL"

    /** 「仅带前缀时接管」的约定前缀（大小写不敏感）。 */
    const val TAKEOVER_PREFIX = "quro"
    val TAKEOVER_PREFIXES: List<String> = listOf("quro", "zorv", "zorvai")

    fun hasTakeoverPrefix(text: String): Boolean {
        val head = text.trimStart().lowercase()
        return TAKEOVER_PREFIXES.any { head.startsWith(it) }
    }

    fun stripTakeoverPrefix(text: String): String {
        val raw = text.trimStart()
        val lower = raw.lowercase()
        val hit = TAKEOVER_PREFIXES.firstOrNull { lower.startsWith(it) } ?: return raw
        return raw.substring(hit.length).trimStart(':', '：', ',', '，', ' ', '-').trim()
    }

    const val RESULT_OK = "ok"
    const val RESULT_ERROR = "error"
    const val RESULT_TEXT = "text"
    const val RESULT_REASONING = "reasoning_content"
    const val RESULT_SOURCE = "source"
    const val RESULT_REQUEST_ID = "request_id"
    const val RESULT_ELAPSED_MS = "elapsed_ms"

    // ---- 入口来源 ----
    const val SOURCE_BREENO = "breeno"
    const val SOURCE_XIAOAI = "xiaoai"
    const val SOURCE_GEMINI = "gemini"
    const val SOURCE_CIRCLE = "circle_to_search"
    const val SOURCE_POWER = "power_key"
    const val SOURCE_SYSTEM = "system"
    const val SOURCE_UNKNOWN = "unknown"

    val SOURCES: List<String> = listOf(
        SOURCE_BREENO, SOURCE_XIAOAI, SOURCE_GEMINI,
        SOURCE_CIRCLE, SOURCE_POWER, SOURCE_SYSTEM, SOURCE_UNKNOWN,
    )

    fun normalizeSource(raw: String?): String =
        raw?.trim()?.lowercase()?.takeIf { it in SOURCES } ?: SOURCE_UNKNOWN

    fun sourceLabel(source: String): String = when (source) {
        SOURCE_BREENO -> "小布助手（ColorOS）"
        SOURCE_XIAOAI -> "超级小爱（HyperOS）"
        SOURCE_GEMINI -> "Gemini / Google App"
        SOURCE_CIRCLE -> "一圈即搜"
        SOURCE_POWER -> "长按电源键"
        SOURCE_SYSTEM -> "system_server"
        else -> "未标注入口"
    }

    // ---- 调用方白名单 ----
    /** system_server 的 UID。 */
    const val SYSTEM_UID = 1000

    /** adb shell / Vector 注入进程的 UID。 */
    const val SHELL_UID = 2000

    const val ROOT_UID = 0

    /**
     * 允许调用本 Provider 的包名。与 META-INF/xposed/scope.list 的声明作用域同源，
     * 再加自身与系统身份；不在此列的第三方 App 一律拒绝跑 Agent 回合。
     */
    val ALLOWED_CALLER_PACKAGES: Set<String> = setOf(
        "com.ai.assistance.quro",
        "android",
        "com.android.shell",
        "com.android.systemui",
        "com.google.android.googlequicksearchbox",
        "com.heytap.speechassist",
        "com.coloros.colordirectservice",
        "com.oplus.aimemory",
        "com.miui.voiceassist",
        "com.miui.home",
        "com.m.android.globallauncher",
        "com.mi.android.globallauncher",
    )

    fun isCallerAllowed(uid: Int, callerPackages: Array<out String>?): Boolean {
        if (uid == SYSTEM_UID || uid == SHELL_UID || uid == ROOT_UID) return true
        return callerPackages.orEmpty().any { it in ALLOWED_CALLER_PACKAGES }
    }

    /** 单次回合的默认与上限预算，避免 Hook 进程被无限阻塞。 */
    const val DEFAULT_TIMEOUT_MS = 120_000L
    const val MAX_TIMEOUT_MS = 240_000L

    fun sanitizeTimeout(raw: Long): Long = when {
        raw <= 0L -> DEFAULT_TIMEOUT_MS
        raw > MAX_TIMEOUT_MS -> MAX_TIMEOUT_MS
        else -> raw
    }
}
