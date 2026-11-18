package com.ai.assistance.quro.core.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 小布（ColorOS Breeno）接管：协议层净室实现。
 *
 * ## 正确的拦截方向（这一层最容易写反）
 *
 * 小布的一轮对话有三条通路，**每条只能做一件事**：
 *
 * | 通路 | 正确动作 | 写反的后果 |
 * |---|---|---|
 * | `MessageQueueManager.send`（出站） | **只观测**，永远放行 | 吞掉后语音协议状态机停在「等节点事件」，整轮会话挂死，连原生小布也再不能用 |
 * | `AIChatDataCenter.addChatBean` chatType=1（提问） | 捕获文本，同时**放行** | 不放行会让小布界面上的用户气泡消失 |
 * | `AIChatDataCenter.addChatBean` chatType=2（回答） | 本轮已认领时**丢弃厂商回答**，自己的回答走 `dispatchDirectives` 写回 | 不丢弃 → 两份答案；丢弃却不校验写回能力 → 空白回合 |
 *
 * ## 硬闸门
 *
 * 写回链路（Directive + Header + StreamTextCard + dispatchDirectives + addChatBean）
 * 只要缺一环，就**一个 hook 都不装**，直接退回原生。绝不出现
 * 「问题被吃掉、回答写不出来」——那对用户来说就是彻底不能用。
 *
 * ## 失败补偿
 *
 * 厂商回答被丢弃时会暂存 bean。若本轮运行时最终失败（桥不可用 / 超时 / 写回失败），
 * 就把暂存的厂商回答**重新派发**，用户至少拿到原生答案，而不是空白。
 */
internal object QuroBreenoHooks {

    private const val CHAT_TYPE_QUERY = 1
    private const val CHAT_TYPE_ANSWER = 2

    /** 一轮接管的有效期；超时视为放弃，厂商回答不再被丢弃。 */
    private const val TURN_TTL_MS = 120_000L
    private const val INJECTED_TTL_MS = 60_000L
    private const val REQUEST_DEDUP_WINDOW_MS = 8_000L
    private const val CLAIMED_RECORD_TTL_MS = 10 * 60_000L
    private const val BYPASS_TTL_MS = 5_000L
    private const val MARKER_KEY = "quroAgent"

    private val threadId = AtomicInteger()

    private val executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(8),
        { runnable ->
            Thread(runnable, "Quro-Breeno-${threadId.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var targets: QuroBreenoTargets? = null

    /** roomId → 本轮接管状态。 */
    private val turns = ConcurrentHashMap<String, Turn>()

    /** 回答指纹：用来认出「这张卡片是我自己写的」，必须放行。 */
    private val injectedAnswers = ConcurrentHashMap<String, Long>()

    private val startedRequests = ConcurrentHashMap<String, Long>()

    /** 已接管过的 recordId；小布翻历史会把旧提问重新灌进派发入口。 */
    private val claimedRecords = ConcurrentHashMap<String, Long>()

    /** 补偿放行标记：失败后重放厂商 bean 时，这一次必须真的走原生。 */
    private val bypassed = ConcurrentHashMap<Int, Long>()

    private class Turn(
        val roomId: String,
        val recordId: String,
        val query: String,
        val at: Long,
    ) {
        /** 本轮已了结（成功写回或已补偿），之后不再丢弃任何卡片。 */
        @Volatile
        var settled: Boolean = false

        /** 被丢弃、等待补偿的最后一帧厂商回答。 */
        @Volatile
        var pendingNative: Any? = null

        /**
         * 本轮捕获的图片快照（未解析，只含字符串引用）。
         *
         * 在 Hook 热路径只读字段，不做 JSON 解析；解析在后台线程（[executeTurn] 的
         * executor）里完成，避免卡主线程。
         */
        @Volatile
        var imageSnapshot: QuroBreenoImages.Snapshot = QuroBreenoImages.Empty
    }

    fun install(
        module: io.github.libxposed.api.XposedModule,
        classLoader: ClassLoader,
        hooks: QuroHookRegistrar,
    ) {
        val resolved = QuroBreenoTargets.resolve(classLoader)
        targets = resolved

        // ——— 硬闸门：写回链路不完整就整组不装，保持原生 ———
        if (!resolved.canDispatchAnswer) {
            hooks.missing(
                id = "breeno.runtime-contract",
                description = "小布写回链路",
                detail = "缺少 ${resolved.answerContractMissing.joinToString()}，" +
                    "本次不接管（小布保持原生可用）",
            )
            return
        }

        hooks.intercept(
            id = "breeno.chat-dispatch",
            executable = resolved.chatDispatch!!,
            description = "AIChatDataCenter 对话派发（提问捕获 / 厂商回答丢弃）",
        ) { chain ->
            handleChatDispatch(chain.getThisObject(), chain.getArg(0), chain)
        }

        // 语音/文本请求入口：某些机型不经过 addChatBean，纯语音提问只能从这里拿到。
        val cdm = resolved.cdmTextRequest
        if (cdm != null) {
            hooks.intercept(
                id = "breeno.cdm-text-request",
                executable = cdm,
                description = "CdmNode 语音文本请求（提问兜底捕获）",
            ) { chain ->
                handleCdmRequest(chain.getArg(0), chain)
            }
        } else {
            hooks.missing(
                id = "breeno.cdm-text-request",
                description = "CdmNode.voiceStart",
                detail = "未定位语音文本请求入口：纯语音提问只能靠对话派发捕获",
            )
        }

        // 出站消息：**只观测**。这里永远 chain.proceed()，不返回 null。
        val outbound = resolved.outboundSend
        if (outbound != null) {
            hooks.intercept(
                id = "breeno.outbound",
                executable = outbound,
                description = "MessageQueueManager.send（出站观测，不拦截）",
            ) { chain ->
                handleOutboundObserve(chain.getArg(0), chain)
            }
        } else {
            hooks.missing(
                id = "breeno.outbound",
                description = "MessageQueueManager.send",
                detail = "未定位出站消息方法（仅影响诊断信息，不影响接管）",
            )
        }

        hooks.skipped(
            id = "breeno.answer-sink",
            description = "StreamTextCard 回写",
            detail = "写回链路已校验通过：Directive + Header + StreamTextCard + dispatchDirectives",
        )

        val captureMissing = resolved.captureMissing
        if (captureMissing.isNotEmpty()) {
            log("观测通道缺失：${captureMissing.joinToString()}（接管仍可用）")
        }
    }

    // ---- 对话派发 ----

    private fun handleChatDispatch(
        holder: Any?,
        bean: Any?,
        chain: io.github.libxposed.api.XposedInterface.Chain,
    ): Any? {
        if (bean == null) return chain.proceed()
        QuroBreenoAnswerSink.dataCenterHolder = holder

        val type = (QuroHookSupport.invokeNoArgs(bean, "getChatType") as? Int) ?: return chain.proceed()
        val roomId = QuroHookSupport.invokeString(bean, "getRoomId")
        val content = QuroHookSupport.invokeString(bean, "getContent")

        if (type == CHAT_TYPE_QUERY) {
            // 提问：捕获 + 放行。放行是必须的，否则小布界面上的用户气泡就没了。
            QuroHookReport.recordEvent(
                QuroHookEnvironment.currentApplication(),
                "breeno.query",
                "room=${short(roomId)} chars=${content.length}",
            )
            if (content.isNotBlank() && QuroHookSupport.invokeString(bean, "getRecordId").isNotBlank()) {
                // 捕获图片：clientResult（type=104 多图）+ 文本图片引用，热路径只读字符串。
                val imageSnapshot = captureBreenoImages(bean, content)
                maybeTakeOver(
                    roomId = roomId,
                    recordId = QuroHookSupport.invokeString(bean, "getRecordId"),
                    text = content,
                    source = "aichat",
                    imageSnapshot = imageSnapshot,
                )
            }
            return chain.proceed()
        }

        if (type == CHAT_TYPE_ANSWER) {
            if (shouldDropNativeAnswer(roomId, bean, content)) {
                rememberPendingNative(roomId, bean)
                QuroHookReport.recordEvent(
                    QuroHookEnvironment.currentApplication(),
                    "breeno.suppress",
                    "room=${short(roomId)} chars=${content.length}",
                )
                log("已丢弃厂商回答 room=${short(roomId)}（本轮由运行时负责）")
                return null
            }
            return chain.proceed()
        }
        return chain.proceed()
    }

    /**
     * 是否丢弃这张厂商回答卡片。
     *
     * 只有同时满足才丢：本轮确实由我们认领、还没了结、且不是我们自己写的卡片。
     * 其余一律放行 —— 宁可多显示一张厂商卡片，也不能把用户晾在空白页。
     */
    private fun shouldDropNativeAnswer(roomId: String, bean: Any?, content: String): Boolean {
        if (consumeBypass(bean)) return false
        if (roomId.isBlank()) return false
        val turn = turns[roomId] ?: return false
        if (turn.settled) return false
        if (System.currentTimeMillis() - turn.at > TURN_TTL_MS) {
            turns.remove(roomId)
            return false
        }
        if (isOwnAnswer(roomId, bean, content)) return false
        return true
    }

    private fun rememberPendingNative(roomId: String, bean: Any?) {
        val turn = turns[roomId] ?: return
        turn.pendingNative = bean
    }

    // ---- CdmNode 请求（语音提问兜底） ----

    private fun handleCdmRequest(
        parameter: Any?,
        chain: io.github.libxposed.api.XposedInterface.Chain,
    ): Any? {
        if (parameter == null) return chain.proceed()
        val text = extractCdmText(parameter)
        // 语音请求主要带文本，图片引用从文本侧捕获（粘贴链接等）。
        val imageSnapshot = QuroBreenoImages.captureText(text, "cdm.data")
        if (!text.isNullOrBlank() || !imageSnapshot.isEmpty) {
            val recordId = QuroHookSupport.invokeString(parameter, "getRecordId")
                .ifBlank { QuroHookSupport.invokeString(parameter, "getCurrentRecordId") }
            // CDM 回合必须挂在真实 roomId 上：硬传 "" 会生成 "record:xxx" 伪键，
            // 回答分支用厂商 bean 的真实 roomId 查 turns 必然落空（不丢弃原生回答），
            // 写回卡片也会落错会话。
            val roomId = QuroHookSupport.invokeString(parameter, "getRoomId")
                .ifBlank { QuroHookSupport.invokeString(parameter, "getCurrentRoomId") }
            QuroHookReport.recordEvent(
                QuroHookEnvironment.currentApplication(),
                "breeno.cdm",
                "chars=${text?.length ?: 0} record=${short(recordId)} imgs=${imageSnapshot.inputCount}",
            )
            maybeTakeOver(
                roomId = roomId,
                recordId = recordId,
                text = text ?: "",
                source = "cdm",
                imageSnapshot = imageSnapshot,
            )
        }
        return chain.proceed()
    }

    /** `DmParameter.getData()` 是 JSON 字符串，文本字段随版本叫 text / query。 */
    private fun extractCdmText(parameter: Any): String? {
        val data = QuroHookSupport.invokeString(parameter, "getData")
        if (data.isBlank()) return null
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
        val direct = json.optString("text").ifBlank { json.optString("query") }
        if (direct.isNotBlank()) return direct
        return runCatching { json.optJSONObject("nlp")?.optString("text") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ---- 出站消息（只观测） ----

    private fun handleOutboundObserve(
        message: Any?,
        chain: io.github.libxposed.api.XposedInterface.Chain,
    ): Any? {
        if (message != null) {
            runCatching {
                val roomId = QuroHookSupport.invokeString(message, "getRoomId")
                val text = extractNlpText(message)
                if (!text.isNullOrBlank()) {
                    QuroHookReport.recordEvent(
                        QuroHookEnvironment.currentApplication(),
                        "breeno.outbound",
                        "room=${short(roomId)} nlpChars=${text.length}",
                    )
                }
            }
        }
        // 出站永远放行：吞掉它会让语音协议状态机停摆，整轮会话挂死。
        return chain.proceed()
    }

    /** 从 `Message.getEvents()` 里取 `Nlp/Text` 的用户原文。 */
    private fun extractNlpText(message: Any): String? {
        val events = QuroHookSupport.invokeNoArgs(message, "getEvents") as? Iterable<*> ?: return null
        var index = 0
        for (event in events) {
            if (index++ >= 32) break
            if (event == null) continue
            val header = QuroHookSupport.invokeNoArgs(event, "getHeader") ?: continue
            val namespace = QuroHookSupport.invokeString(header, "getNamespace")
            val name = QuroHookSupport.invokeString(header, "getName")
            if (namespace != "Nlp" || name != "Text") continue
            val payload = QuroHookSupport.invokeNoArgs(event, "getPayload") ?: continue
            val text = QuroHookSupport.invokeString(payload, "getText")
            if (text.isNotBlank()) return text
        }
        return null
    }

    // ---- 图片捕获 ----

    /**
     * 从小布提问 bean 捕获图片引用：clientResult（type=104 多图）+ 文本图片引用。
     *
     * 热路径只读已确认的字符串字段，不做 JSON 解析、不读 URI/文件；解析在
     * [executeTurn] 的后台线程完成。返回合并后的快照，无图时为 [QuroBreenoImages.Empty]。
     */
    private fun captureBreenoImages(bean: Any?, text: String): QuroBreenoImages.Snapshot {
        val clientResult = QuroHookSupport.invokeNoArgs(bean, "getClientResult")
        var snapshot = QuroBreenoImages.captureClientResult(clientResult)
        // 文本侧图片引用作为补充（如粘贴的 content:// / data:image 链接）。
        snapshot = QuroBreenoImages.merge(snapshot, QuroBreenoImages.captureText(text, "aichat.query.content"))
        return snapshot
    }

    // ---- 接管决策 ----

    private fun maybeTakeOver(
        roomId: String,
        recordId: String,
        text: String,
        source: String,
        imageSnapshot: QuroBreenoImages.Snapshot = QuroBreenoImages.Empty,
    ): Boolean {
        if (text.isBlank() && imageSnapshot.isEmpty) return false
        if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.VENDOR_ASSISTANT_TAKEOVER)) return false

        val requirePrefix = QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.VENDOR_REQUIRE_PREFIX)
        // 用布尔判定而非「剥后是否等于原文」：stripTakeoverPrefix 对未命中前缀的文本
        // 仍会 trimStart()，带前导空白的普通问句会被误判为「写了前缀」而绕过闸门。
        val hasPrefix = QuroAgentBridgeProtocol.hasTakeoverPrefix(text)
        // 纯图片（text 为空）不要求前缀，图片本身就是接管信号。
        if (requirePrefix && !hasPrefix && text.isNotBlank()) return false
        val query = QuroAgentBridgeProtocol.stripTakeoverPrefix(text).trim()
        if (query.isBlank() && imageSnapshot.isEmpty) return false

        val ctx = QuroHookEnvironment.currentApplication() ?: return false

        // 历史回灌只跑一次；失败时回滚，让用户重说一遍仍然有效。
        if (recordId.isNotBlank()) {
            prune(claimedRecords, CLAIMED_RECORD_TTL_MS)
            if (claimedRecords.putIfAbsent(recordId, System.currentTimeMillis()) != null) return false
        }

        val key = if (roomId.isNotBlank()) roomId else "record:$recordId"
        val dedupKey = "$key:${query.length}:${query.hashCode()}:${imageSnapshot.inputCount}"
        if (!markStarted(dedupKey)) return false

        // 同一 room 已有未了结的回合：让新的顶替旧的，旧回合不再丢弃任何卡片。
        turns[key]?.let { it.settled = true }
        val turn = Turn(key, recordId, query, System.currentTimeMillis())
        turn.imageSnapshot = imageSnapshot
        turns[key] = turn

        if (!executeTurn(ctx, turn, source)) {
            // 线程池满等「根本没开始」的情况：立刻撤销认领，原生链路继续。
            turns.remove(key)
            if (recordId.isNotBlank()) claimedRecords.remove(recordId)
            return false
        }
        return true
    }

    private fun executeTurn(ctx: Context, turn: Turn, source: String): Boolean {
        val resolved = targets ?: return false
        val uniqueId = QuroBreenoAnswerSink.newId()
        return try {
            executor.execute {
                // 解析图片快照为引用列表（后台线程，避免 JSON 解析卡主线程）。
                // 解析失败或超限只降级为无图，不阻断文本回合。
                val imageRefs = runCatching {
                    if (turn.imageSnapshot.isEmpty) emptyList()
                    else (QuroBreenoImages.resolve(turn.imageSnapshot) as? QuroBreenoImages.Resolution.Success)
                        ?.references?.map { it.value }
                        ?: emptyList()
                }.getOrDefault(emptyList())

                val hidden = "入口：小布（$source）。roomId=${short(turn.roomId)}；" +
                    "用户原话已作为本轮请求，请按完整运行时处理并直接给出最终答复。"
                val result = runCatching {
                    QuroAgentBridgeClient.takeover(
                        ctx,
                        QuroAgentBridgeProtocol.SOURCE_BREENO,
                        turn.query,
                        hidden,
                        imageRefs,
                        prefixHandled = true,
                    )
                }.getOrNull()

                if (result == null) {
                    log("小布回合未执行（桥不可用），补偿原生回答 room=${short(turn.roomId)}")
                    mainHandler.post { settleFailure(turn) }
                    return@execute
                }

                val ok = QuroAgentBridgeClient.isOk(result)
                val reply = QuroAgentBridgeClient.textOf(result).trim().ifBlank {
                    if (ok) "已完成。" else "处理失败：${
                        QuroAgentBridgeClient.errorOf(result).ifBlank { "运行时无返回" }
                    }"
                }

                mainHandler.post {
                    rememberInjected(turn.roomId, reply)
                    val written = runCatching {
                        QuroBreenoAnswerSink.dispatch(
                            targets = resolved,
                            roomId = turn.roomId,
                            recordId = turn.recordId,
                            query = turn.query,
                            content = reply,
                            uniqueId = uniqueId,
                            isFinal = true,
                            firstFrame = true,
                        )
                    }.getOrDefault(false)

                    if (written) {
                        turn.pendingNative = null
                        turn.settled = true
                        log("小布回答已写入 room=${short(turn.roomId)} chars=${reply.length} ok=$ok")
                    } else {
                        log("小布回答写入失败，补偿原生回答 room=${short(turn.roomId)}")
                        settleFailure(turn)
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            log("小布回合队列已满，本次保持原生行为")
            false
        }
    }

    /** 回合失败：把被丢弃的厂商回答放回去，别让用户对着空白页。 */
    private fun settleFailure(turn: Turn) {
        turn.settled = true
        val bean = turn.pendingNative ?: return
        turn.pendingNative = null
        val holder = QuroBreenoAnswerSink.dataCenterHolder ?: return
        val dispatch = targets?.chatDispatch ?: return
        bypassed[System.identityHashCode(bean)] = System.currentTimeMillis()
        runCatching { dispatch.invoke(holder, bean) }
            .onSuccess { log("已补偿原生回答 room=${short(turn.roomId)}") }
            .onFailure { log("原生回答补偿失败 room=${short(turn.roomId)}") }
    }

    /** 本轮彻底失败（连补偿都没有）：撤掉认领，后续卡片正常显示。 */
    @Suppress("unused")
    private fun abandon(turn: Turn) {
        turn.settled = true
        if (turn.recordId.isNotBlank()) claimedRecords.remove(turn.recordId)
    }

    // ---- 自己的回答识别 ----

    private fun rememberInjected(roomId: String, content: String) {
        prune(injectedAnswers, INJECTED_TTL_MS)
        injectedAnswers[answerKey(roomId, content)] = System.currentTimeMillis()
    }

    private fun isOwnAnswer(roomId: String, bean: Any?, content: String): Boolean {
        if (consumeBypass(bean)) return true
        if (carriesOwnMarker(bean)) return true
        prune(injectedAnswers, INJECTED_TTL_MS)
        val at = injectedAnswers[answerKey(roomId, content)] ?: return false
        return System.currentTimeMillis() - at <= INJECTED_TTL_MS
    }

    /**
     * 我们自己下发的卡片带着 `extend.quroAgent=true`。
     *
     * 只靠文本指纹不可靠：小布会把 markdown / 换行重新排版，指纹对不上时
     * 自己的回答反而会被自己吞掉 —— 这是最典型的「看起来成功、界面全空」。
     */
    private fun carriesOwnMarker(bean: Any?): Boolean {
        val raw = QuroHookSupport.invokeNoArgs(bean, "getClientResult")
        val text = when (raw) {
            null -> return false
            is String -> raw
            else -> runCatching { raw.toString() }.getOrDefault("")
        }
        if (text.isBlank()) return false
        return text.contains("\"$MARKER_KEY\"") && text.contains("true")
    }

    private fun consumeBypass(bean: Any?): Boolean {
        if (bean == null) return false
        val at = bypassed.remove(System.identityHashCode(bean)) ?: return false
        return System.currentTimeMillis() - at <= BYPASS_TTL_MS
    }

    private fun answerKey(roomId: String, content: String): String =
        "$roomId:${content.length}:${content.hashCode()}"

    private fun markStarted(key: String): Boolean {
        val now = System.currentTimeMillis()
        prune(startedRequests, REQUEST_DEDUP_WINDOW_MS)
        return startedRequests.putIfAbsent(key, now) == null
    }

    private fun prune(map: ConcurrentHashMap<String, Long>, ttlMs: Long) {
        val now = System.currentTimeMillis()
        map.entries.removeIf { (_, at) -> now - at > ttlMs }
        if (map.size > 256) map.clear()
    }

    private fun short(value: String): String =
        if (value.length <= 8) value else "${value.take(4)}…${value.takeLast(4)}"

    private fun log(message: String) {
        runCatching { Log.i(QuroModuleConfig.LOG_TAG, "[小布] $message") }
    }
}
