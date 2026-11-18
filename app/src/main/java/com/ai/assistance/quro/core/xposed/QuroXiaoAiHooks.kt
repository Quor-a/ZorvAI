package com.ai.assistance.quro.core.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 超级小爱（HyperOS, `com.miui.voiceassist`）接管：净室实现。
 *
 * ## 拦截方向（上一版在这里写反了，是「看起来成功、界面全空」的根因）
 *
 * 正确分工只有一句话：**在出站请求处拦，在卡片出口处只读**。
 *
 * | 位置 | 正确动作 | 写反的后果 |
 * |---|---|---|
 * | 语音引擎处理出站事件的方法 `(Event) -> boolean`（`Nlp.Request`） | 认领后返回 `true`，让厂商请求不再发出 | 不拦 → 厂商答案照常返回 |
 * | 卡片管理器 `addCard(Card)` | **只记录实例，永远放行** | 在这里按 dialogId 吞卡片 → 把我们自己写回的卡片也一起吞掉，于是两边都没有答案，界面全空 |
 *
 * 上一版正是在 `addCard` 上吞卡片：厂商答案被吞、自己写的卡片又被同一个钩子吞掉，
 * 日志还打印「已写入」。这属于方向性错误，不是参数没调好。
 *
 * ## 硬闸门
 *
 * 出站拦截点、卡片类型、`addCard` 三者缺一，就**不启用接管**，小爱保持原生可用。
 * 宁可不接管，也不能出现「问题没了、答案也没有」。
 */
internal object QuroXiaoAiHooks {

    private const val OPERATION_MANAGER =
        "com.xiaomi.voiceassistant.instruction.base.OperationManager"
    private const val UI_MANAGER = "com.xiaomi.voiceassistant.UiManager"
    private const val EVENT_CLASS = "com.xiaomi.ai.api.common.Event"
    private const val FLOW_TOAST_CARD =
        "com.xiaomi.voiceassistant.instruction.card.stream.FlowTemplateToastCard"
    private const val CARD_PACKAGE_PREFIX = "com.xiaomi.voiceassistant.card"

    /**
     * 语音引擎类名。发布包里被混淆，且各版本不同——这是运行时事实常量，
     * 不是任何项目的实现代码。命中不了就在自检里如实报告，绝不做半吊子接管。
     */
    private val ENGINE_CANDIDATES = listOf(
        "com.xiaomi.voiceassistant.speech.MiSpeechEngine",
        "com.xiaomi.voiceassistant.engine.MiSpeechEngine",
        "y00.r0",
    )

    private const val CLAIM_TTL_MS = 120_000L
    private const val REQUEST_DEDUP_WINDOW_MS = 8_000L
    private const val CARD_RETRY_DELAY_MS = 250L
    private const val CARD_MAX_RETRIES = 8
    private const val QUERY_CACHE_TTL_MS = 120_000L
    /** latestQuery 只在最近一轮有效，避免拿几小时前的旧提问顶替空 query。 */
    private const val TURN_TRACKER_TTL_MS = 15_000L

    private val threadId = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(4),
        { runnable ->
            Thread(runnable, "Quro-XiaoAi-${threadId.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** dialogId → 认领时间。认领后同一 dialog 的后续请求继续拦。 */
    private val claims = ConcurrentHashMap<String, Long>()
    private val startedRequests = ConcurrentHashMap<String, Long>()

    /** `setQueryInfo` 记下的本轮查询，供 `Nlp.Request` 关联。 */
    private val pendingQueries = ConcurrentHashMap<String, CachedQuery>()
    @Volatile
    private var latestQuery: CachedQuery? = null

    private data class CachedQuery(val dialogId: String, val query: String, val at: Long)

    @Volatile private var flowCardClass: Class<*>? = null
    @Volatile private var floatManager: Any? = null
    @Volatile private var addCardMethod: Method? = null

    fun install(
        module: io.github.libxposed.api.XposedModule,
        classLoader: ClassLoader,
        hooks: QuroHookRegistrar,
    ) {
        installQueryCapture(classLoader, hooks)

        val cardSink = resolveCardSink(classLoader, hooks)
        val outbound = resolveOutbound(classLoader, hooks)

        // ——— 硬闸门：写不回 / 拦不住，就不接管 ———
        if (cardSink == null || outbound == null) {
            hooks.missing(
                id = "xiaoai.runtime-contract",
                description = "超级小爱接管链路",
                detail = buildString {
                    append("本次不接管（小爱保持原生可用）：")
                    if (outbound == null) append("未定位语音引擎出站拦截点；")
                    if (cardSink == null) append("未定位卡片写回通道；")
                },
            )
            return
        }

        hooks.intercept(
            id = "xiaoai.outbound",
            executable = outbound,
            description = "语音引擎出站事件（Nlp.Request 认领）",
        ) { chain ->
            val event = chain.getArg(0)
            // 认领成功 → 返回 true，厂商请求不再发出；否则原样放行。
            if (claimOutbound(event)) true else chain.proceed()
        }

        // 卡片出口：**只读实例，绝不吞**。写回也走这个方法，吞了等于吞自己。
        hooks.intercept(
            id = "xiaoai.card-sink",
            executable = addCardMethod!!,
            description = "卡片管理器 addCard（只记录实例）",
        ) { chain ->
            floatManager = chain.getThisObject()
            chain.proceed()
        }
    }

    // ---- 提问缓存（关联 dialogId） ----

    private fun installQueryCapture(classLoader: ClassLoader, hooks: QuroHookRegistrar) {
        val managerClass = QuroHookSupport.findClassOrNull(classLoader, OPERATION_MANAGER)
        val method = managerClass?.let {
            QuroHookSupport.findMethod(
                it,
                "setQueryInfo",
                String::class.java,
                String::class.java,
                JSONObject::class.java,
            )
        }
        if (method == null) {
            hooks.missing(
                id = "xiaoai.query-info",
                description = "OperationManager.setQueryInfo",
                detail = "未找到 setQueryInfo：只能从 Nlp.Request 自带的 query 取文本",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.query-info",
            executable = method,
            description = "OperationManager.setQueryInfo（本轮提问缓存）",
        ) { chain ->
            val dialogId = (chain.getArg(0) as? String).orEmpty().trim()
            val query = (chain.getArg(1) as? String).orEmpty().trim()
            if (dialogId.isNotBlank() && query.isNotBlank()) {
                val cached = CachedQuery(dialogId, query, System.currentTimeMillis())
                pendingQueries[dialogId] = cached
                latestQuery = cached
                pruneQueries()
            }
            chain.proceed()
        }
    }

    // ---- 出站拦截点 ----

    /**
     * 在候选引擎类里找「唯一一个 `(Event) -> boolean` 的实例方法」。
     * 方法名被混淆，但签名不会变：它就是要不要继续发送的判定。
     */
    private fun resolveOutbound(classLoader: ClassLoader, hooks: QuroHookRegistrar): Method? {
        val eventClass = QuroHookSupport.findClassOrNull(classLoader, EVENT_CLASS) ?: return null
        ENGINE_CANDIDATES.forEach { className ->
            val engine = QuroHookSupport.findClassOrNull(classLoader, className) ?: return@forEach
            val hit = QuroHookSupport.findDeclaredMethods(engine) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 1 && m.parameterTypes[0] == eventClass &&
                    m.returnType == Boolean::class.javaPrimitiveType
            }.singleOrNull()
            if (hit != null) {
                log("出站拦截点：$className#${hit.name}")
                return hit
            }
        }
        return null
    }

    private fun claimOutbound(event: Any?): Boolean {
        if (event == null) return false
        return runCatching { claimOutboundInner(event) }.getOrDefault(false)
    }

    private fun claimOutboundInner(event: Any): Boolean {
        // 只认「发起 NLP 请求」这一个事件；别的事件必须原样放行。
        if (QuroHookSupport.invokeString(event, "getFullName") != "Nlp.Request") return false

        val dialogId = QuroHookSupport.invokeString(event, "getId").trim()
        if (dialogId.isBlank()) return false
        if (isClaimed(dialogId)) return true

        if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.VENDOR_ASSISTANT_TAKEOVER)) return false

        val payload = QuroHookSupport.invokeNoArgs(event, "getPayload")
        val eventQuery = payload?.let { QuroHookSupport.invokeString(it, "getQuery") }.orEmpty()
        // latestQuery 只在最近一轮有效，且命中即消费，避免隔很久的空 query
        // 拿旧提问去跑、或同一条缓存被复用多次。
        val cached = pendingQueries[dialogId] ?: latestQuery?.takeIf {
            System.currentTimeMillis() - it.at <= TURN_TRACKER_TTL_MS
        }?.also { latestQuery = null }
        val rawQuery = eventQuery.ifBlank { cached?.query.orEmpty() }.trim()
        if (rawQuery.isBlank()) return false

        val requirePrefix = QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.VENDOR_REQUIRE_PREFIX)
        // 用布尔判定而非「剥后 == 原文」（后者会被前导空白绕过）；剥离只做一次，
        // submitTurn 传 prefixHandled=true，防止 takeover 二次判前缀造成固定失败卡。
        if (requirePrefix && !QuroAgentBridgeProtocol.hasTakeoverPrefix(rawQuery)) return false
        val query = QuroAgentBridgeProtocol.stripTakeoverPrefix(rawQuery).trim()
        if (query.isBlank()) return false

        val ctx = QuroHookEnvironment.currentApplication() ?: return false

        val dedupKey = "$dialogId:${query.length}:${query.hashCode()}"
        if (!markStarted(dedupKey)) return true // 已在跑，本轮继续拦，避免厂商请求漏出

        claims[dialogId] = System.currentTimeMillis()
        QuroHookReport.recordEvent(ctx, "xiaoai.query", "dialog=${short(dialogId)} chars=${query.length}")

        if (!submitTurn(ctx, dialogId, query)) {
            claims.remove(dialogId)
            return false
        }
        return true
    }

    // ---- 回合 ----

    private fun submitTurn(ctx: Context, dialogId: String, query: String): Boolean = try {
        executor.execute {
            val hidden = "入口：超级小爱。dialogId=${short(dialogId)}；" +
                "用户原话已作为本轮请求，请按完整运行时处理并直接给出最终答复。"
            val result = runCatching {
                QuroAgentBridgeClient.takeover(
                    ctx,
                    QuroAgentBridgeProtocol.SOURCE_XIAOAI,
                    query,
                    hidden,
                    prefixHandled = true,
                )
            }.getOrNull()

            // 无论成功失败都必须给出一张卡片：出站请求已经被拦掉，界面不能留空。
            val reply = when {
                result == null -> "处理失败：运行时无响应"
                QuroAgentBridgeClient.isOk(result) -> QuroAgentBridgeClient.textOf(result).trim()
                    .ifBlank { "已完成。" }
                else -> "处理失败：${
                    QuroAgentBridgeClient.errorOf(result).ifBlank { "运行时无返回" }
                }"
            }
            mainHandler.post { renderAnswer(dialogId, reply, 0) }
        }
        true
    } catch (_: RejectedExecutionException) {
        log("小爱回合队列已满，本次保持原生行为")
        false
    }

    /** 卡片管理器可能还没创建，按 250ms 退避重试；失败也必须留一条可见结果。 */
    private fun renderAnswer(dialogId: String, text: String, attempt: Int) {
        val manager = floatManager ?: resolveFloatManager()
        val cardType = flowCardClass
        val add = addCardMethod
        if (manager == null || cardType == null || add == null) {
            if (attempt < CARD_MAX_RETRIES) {
                mainHandler.postDelayed({ renderAnswer(dialogId, text, attempt + 1) }, CARD_RETRY_DELAY_MS)
            } else {
                claims.remove(dialogId)
                log("小爱卡片管理器一直不可用，本轮回答无法显示")
            }
            return
        }
        val ok = runCatching {
            val card = newFlowCard(cardType, text) ?: return@runCatching false
            QuroHookSupport.invokeByName(card, "setDialogId", dialogId)
            add.invoke(manager, card)
            true
        }.getOrDefault(false)
        if (ok) {
            // 认领保留到 TTL 到期或会话清理（Eta RecentIds 成功后不清除）；
            // 立刻 remove 会让同一 dialog 的后续事件重新触发整轮 Agent 并再写一张卡。
            log("小爱回答已写入 dialog=${short(dialogId)} chars=${text.length}")
        } else if (attempt < CARD_MAX_RETRIES) {
            mainHandler.postDelayed({ renderAnswer(dialogId, text, attempt + 1) }, CARD_RETRY_DELAY_MS)
        } else {
            claims.remove(dialogId)
            log("小爱回答写入失败")
        }
    }

    // ---- 卡片通道 ----

    private fun resolveCardSink(classLoader: ClassLoader, hooks: QuroHookRegistrar): Method? {
        val uiManagerClass = QuroHookSupport.findClassOrNull(classLoader, UI_MANAGER)
        val getFloatManager = uiManagerClass?.let { QuroHookSupport.findMethod(it, "getFloatManager") }
        val managerType = getFloatManager?.returnType
        if (managerType == null) {
            hooks.missing(
                id = "xiaoai.card-sink",
                description = "UiManager.getFloatManager",
                detail = "未找到类型推导入口",
            )
            return null
        }

        // addCard 的参数类型就是卡片基类；优先取 com.xiaomi.voiceassistant.card 包下的那个。
        val addCard = QuroHookSupport.findDeclaredMethods(managerType) { m ->
            m.name == "addCard" && m.parameterCount == 1 &&
                m.parameterTypes[0].name.startsWith(CARD_PACKAGE_PREFIX)
        }.singleOrNull() ?: QuroHookSupport.findDeclaredMethods(managerType) { m ->
            m.name == "addCard" && m.parameterCount == 1
        }.singleOrNull()

        if (addCard == null) {
            hooks.missing(
                id = "xiaoai.card-sink",
                description = "${managerType.simpleName}.addCard",
                detail = "卡片管理器类型已定位，但没有单参 addCard",
            )
            return null
        }

        flowCardClass = QuroHookSupport.findClassOrNull(classLoader, FLOW_TOAST_CARD)
        if (flowCardClass == null) {
            hooks.missing(
                id = "xiaoai.card-create",
                description = "FlowTemplateToastCard",
                detail = "缺少卡片类型，无法写回回答",
            )
            return null
        }
        addCardMethod = addCard
        return addCard
    }

    private fun resolveFloatManager(): Any? = runCatching {
        val ctx = QuroHookEnvironment.currentApplication() ?: return@runCatching null
        val clazz = Class.forName(UI_MANAGER, false, ctx.classLoader)
        val getInstance = QuroHookSupport.findCompatibleMethod(clazz, "getInstance", arrayOf<Any?>(ctx))
            ?: return@runCatching null
        val ui = getInstance.invoke(null, ctx) ?: return@runCatching null
        QuroHookSupport.invokeNoArgs(ui, "getFloatManager")
    }.getOrNull()?.also { floatManager = it }

    /** `FlowTemplateToastCard(String)`；版本差异时退到任意单 String 构造器。 */
    private fun newFlowCard(type: Class<*>, text: String): Any? {
        runCatching { return type.getConstructor(String::class.java).newInstance(text) }
        return runCatching {
            type.declaredConstructors.firstOrNull { c ->
                c.parameterTypes.size == 1 && c.parameterTypes[0] == String::class.java
            }?.apply { isAccessible = true }?.newInstance(text)
        }.getOrNull()
    }

    // ---- 状态 ----

    private fun isClaimed(dialogId: String): Boolean {
        val at = claims[dialogId] ?: return false
        if (System.currentTimeMillis() - at > CLAIM_TTL_MS) {
            claims.remove(dialogId)
            return false
        }
        return true
    }

    private fun pruneQueries() {
        val now = System.currentTimeMillis()
        pendingQueries.entries.removeIf { (_, v) -> now - v.at > QUERY_CACHE_TTL_MS }
        if (pendingQueries.size > 32) pendingQueries.clear()
    }

    private fun markStarted(key: String): Boolean {
        val now = System.currentTimeMillis()
        startedRequests.entries.removeIf { (_, at) -> now - at > REQUEST_DEDUP_WINDOW_MS }
        return startedRequests.putIfAbsent(key, now) == null
    }

    private fun short(value: String): String =
        if (value.length <= 8) value else "${value.take(4)}…${value.takeLast(4)}"

    private fun log(message: String) {
        runCatching { Log.i(QuroModuleConfig.LOG_TAG, "[小爱] $message") }
    }
}
