package com.ai.assistance.quro.core.mika.cap.web.cdp

import com.ai.assistance.quro.core.mika.cap.web.DEVTOOLS_UNAVAILABLE
import com.ai.assistance.quro.core.mika.cap.web.EVAL_RESULT_MAX_CHARS
import com.ai.assistance.quro.core.mika.cap.web.EvalJs
import com.ai.assistance.quro.core.mika.cap.web.ReadScriptProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个标签的读脚本(见 [ReadScriptProtocol]):在主框架的读取世界里跑,一次一个。
 * 读取世界建一次、留着用(state 跨调用保留),页面换了文档它就没了,下次再建(结果里 fresh=true)。
 * 兜底:跑着时脚本发出任何网络请求、主框架要跳转(先 `Page.stopLoading`)都让这次以错误结束。
 * 到时限先让脚本之后的 sleep 失败;连这都等不到回应(同步死循环占着页面)才打断执行。
 */
class ReadScriptRunner(val session: CdpSession, private val sessionId: String? = null) {
    private val lock = Mutex()
    private val worldName = ReadScriptProtocol.WORLD_PREFIX + UUID.randomUUID()
    @Volatile private var context: Int? = null

    private class World(val frameId: String, val context: Int, val origin: String?, val fresh: Boolean)

    /** 连接断了抛 [CdpConnectionClosedException](由调用方判断是不是标签崩了)。 */
    suspend fun run(code: String, args: JsonObject?, timeoutMs: Long): EvalJs = lock.withLock {
        coroutineScope {
            val outcome = CompletableDeferred<EvalJs>()
            val running = AtomicBoolean(false)
            val answered = AtomicBoolean(false)
            var world: World? = null

            // 先订阅再动手:事件流不重放
            launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { e ->
                    if (e.sessionId != sessionId) return@collect
                    val w = world ?: return@collect
                    val p = e.params
                    when (e.method) {
                        "Runtime.executionContextDestroyed" -> if ((p["executionContextId"] as? JsonPrimitive)?.intOrNull == w.context) gone(outcome)
                        "Runtime.executionContextsCleared" -> gone(outcome)
                        // 不开 Runtime 时没有上下文销毁的事件:主框架换了文档,世界就没了
                        "Page.frameNavigated" -> if (mainFrameNavigated(p, w.frameId)) gone(outcome)
                        "Network.requestWillBeSent" -> if (running.get()) ReadScriptProtocol.requestViolation(p)?.let {
                            outcome.complete(EvalJs.Failed(BLOCKED_NETWORK, it))
                        }
                        "Page.frameRequestedNavigation" -> if (running.get() && (p["frameId"] as? JsonPrimitive)?.contentOrNull == w.frameId) {
                            launch { runCatching { session.send("Page.stopLoading", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) } }
                            outcome.complete(EvalJs.Failed(BLOCKED_NAVIGATION,
                                "the page tried to navigate to ${(p["url"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(200)} while the read script ran; it was stopped. Read scripts cannot navigate: use web_navigate or web_tap"))
                        }
                    }
                }
            }

            try {
                val w = try {
                    ensureWorld()
                } catch (e: CdpConnectionClosedException) {
                    throw e
                } catch (e: CdpTimeoutException) {
                    return@coroutineScope EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "the page did not answer in time")
                } catch (e: CdpException) {
                    return@coroutineScope EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "could not prepare the page for read scripts: ${e.errorMessage}")
                }
                world = w
                running.set(true)
                launch {
                    val r = try {
                        session.send("Runtime.evaluate", buildJsonObject {
                            put("expression", ReadScriptProtocol.run(code, args, EVAL_RESULT_MAX_CHARS + 1)); put("contextId", w.context)
                            put("awaitPromise", true); put("returnByValue", true)
                        }, sessionId, timeoutMs + STEP_TIMEOUT_MS)
                    } catch (e: CdpTimeoutException) {
                        outcome.complete(EvalJs.Failed(TIMEOUT, "the script did not finish within $timeoutMs ms")); return@launch
                    } catch (e: CdpConnectionClosedException) {
                        outcome.completeExceptionally(e); return@launch
                    } catch (e: CdpException) {
                        if (CONTEXT_GONE.containsMatchIn(e.errorMessage)) gone(outcome) else outcome.complete(EvalJs.Failed("script_error", e.errorMessage))
                        return@launch
                    }
                    answered.set(true)
                    val details = r["exceptionDetails"] as? JsonObject
                    outcome.complete(if (details != null) EvalJs.Failed("script_error", exceptionText(details))
                        else ReadScriptProtocol.result((r["result"] as? JsonObject)?.get("value") as? JsonObject, w.fresh))
                }
                launch {
                    delay(timeoutMs)
                    outcome.complete(EvalJs.Failed(TIMEOUT, "the script did not finish within $timeoutMs ms"))
                }
                outcome.await()
            } finally {
                running.set(false)
                val w = world
                withContext(NonCancellable) {
                    if (w != null && !answered.get() && context == w.context) stop(w.context)
                }
                coroutineContext.cancelChildren()
            }
        }
    }

    private fun gone(outcome: CompletableDeferred<EvalJs>) {
        context = null
        outcome.complete(EvalJs.Failed(NAVIGATED, "the page loaded a new document while the script ran, so the script stopped and its state is gone; read the new page"))
    }

    /** 脚本没跑完就收尾:先让它之后的 sleep 失败;这都等不到回应时它在跑同步死循环,才打断。 */
    private suspend fun stop(ctx: Int) {
        val answeredStop = try {
            session.send("Runtime.evaluate", buildJsonObject { put("expression", ReadScriptProtocol.STOP); put("contextId", ctx) }, sessionId, STEP_TIMEOUT_MS)
            true
        } catch (e: CdpTimeoutException) {
            false
        } catch (e: Exception) {
            true
        }
        if (!answeredStop) runCatching { session.send("Runtime.terminateExecution", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) }
    }

    /** 留着的读取世界还在就用它,不在(或第一次)就建一个并装上前置脚本。 */
    private suspend fun ensureWorld(): World {
        // 不开 Runtime:开着它时页面能察觉到调试器(有的网站因此一直重载);隔离世界、求值都不需要它
        // 兜底要看的事件:跳转请求、网络请求(已开着的再开一次无妨)
        session.send("Page.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        session.send("Network.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        val frameId = session.send("Page.getFrameTree", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)["frameTree"]
            ?.jsonObject?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw CdpException(0, "no main frame", "Page.getFrameTree")
        context?.let { c -> probe(c)?.let { origin -> return World(frameId, c, origin, fresh = false) } }
        context = null
        val ctx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", worldName)
            // 协议里的参数名就是这么拼的
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", ReadScriptProtocol.PRELUDE); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpException(0, exceptionText(it), "Runtime.evaluate") }
        val origin = probe(ctx) ?: throw CdpException(0, "the read world did not start", "Runtime.evaluate")
        context = ctx
        return World(frameId, ctx, origin, fresh = true)
    }

    /** 读取世界装好了时为页面的源;世界没了或没装为 null。 */
    private suspend fun probe(ctx: Int): String? = try {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", ReadScriptProtocol.PROBE); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (e: CdpException) {
        null
    }

    private fun exceptionText(details: JsonObject): String {
        val exception = details["exception"] as? JsonObject
        val description = (exception?.get("description") as? JsonPrimitive)?.contentOrNull
        val text = (details["text"] as? JsonPrimitive)?.contentOrNull
        return description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: text ?: "script failed"
    }

    private companion object {
        const val STEP_TIMEOUT_MS = 3_000L
        const val TIMEOUT = "timeout"
        const val NAVIGATED = "navigated"
        const val BLOCKED_NETWORK = "blocked_network"
        const val BLOCKED_NAVIGATION = "blocked_navigation"
        val CONTEXT_GONE = Regex("context|destroyed|Inspected target navigated", RegexOption.IGNORE_CASE)
    }
}
