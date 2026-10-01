package com.ai.assistance.quro.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * L1 · 主进程侧的引擎客户端。
 *
 * 这一层只解决四件事，别往里加业务：
 *   1. **绑定 / 解绑** `:llm` 进程（含超时与失败可读化）；
 *   2. **reqId 分配** —— 因为回调是 oneway，无法靠调用顺序分辨归属；
 *   3. **空闲超时** —— oneway 丢包或进程被杀时，客户端会永远等不到终态。
 *      这里用「多久没收到任何事件」而不是「总共跑了多久」做判据：
 *      一次正常的 30 秒长生成不该被超时打断，而卡住 2 分钟没动静必须解锁。
 *   4. **取消** —— Flow 被取消（用户点停止 / 离开页面）时自动下发 cancel，
 *      不然引擎会在后台把这次生成跑完，白烧电。
 */
class LlmEngineClient(private val context: Context) {

    companion object {
        private const val TAG = "QuroLlm.Client"

        /** 绑定 :llm 进程的等待上限。冷启动要 fork + 加载 .so，给足 20 秒。 */
        private const val BIND_TIMEOUT_MS = 20_000L

        /** 流式过程中的空闲超时：超过这么久没有任何事件就判定卡死。 */
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
    }

    private var service: IQuroLlmService? = null
    private var bound = false
    private val reqSeq = AtomicLong(System.currentTimeMillis())

    /** connect() 挂起期间的回调出口。必须与 [connection] 配对 —— 见下面注释。 */
    private var bindCont: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IQuroLlmService.Stub.asInterface(binder)
            bound = true
            bindCont?.let { if (it.isActive) it.resume(true) }
            bindCont = null
            Log.i(TAG, ":llm 已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 进程被系统回收（LMK）或崩溃会走到这里 —— 这是**预期路径**，
            // 不是异常：独立进程的意义就是让引擎被杀时主界面活着。
            service = null
            Log.w(TAG, ":llm 连接断开（进程可能被系统回收）")
        }
    }

    /**
     * 绑定 :llm 进程。已绑定则直接返回 true。
     *
     * 注意必须复用同一个 [connection] 实例：`unbindService` 是按 ServiceConnection
     * 的对象身份匹配的，每次 connect 新建一个匿名对象会让 [disconnect] 解绑失败，
     * 结果是 :llm 进程被系统一直留着（那 30–50MB 常驻开销白付）。
     */
    suspend fun connect(): Boolean {
        if (service != null) return true
        val intent = Intent().apply {
            setClassName(context.packageName, QuroLlmService::class.java.name)
        }
        val ok = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                bindCont = cont
                val accepted = runCatching {
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
                if (!accepted) {
                    bindCont = null
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation { bindCont = null }
            }
        }
        if (ok != true) Log.w(TAG, "绑定 :llm 失败（超时或未接受）")
        return ok == true
    }

    fun disconnect() {
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        service = null
    }

    fun isConnected(): Boolean = service != null

    // ─────────────────────────────────────────────────────────────────────
    // 会话
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 打开会话。挂起（可能几十秒），失败抛 [LlmException]。
     *
     * 调用方负责整体超时（`withTimeout`）—— 这里不设，因为模型大小差异太大：
     * 一个 0.5B 可能 3 秒就绪，4B 要几十秒，用固定超时要么误杀要么形同虚设。
     */
    suspend fun openSession(modelPath: String, options: JSONObject = JSONObject()): Long {
        val svc = service ?: throw LlmException(QuroLlmError.SERVICE_NOT_BOUND, "推理进程未连接")
        val raw = try {
            svc.openSession(modelPath, options.toString())
        } catch (e: RemoteException) {
            throw LlmException(QuroLlmError.SERVICE_NOT_BOUND, "推理进程已断开：${e.message}")
        }
        val env = runCatching { JSONObject(raw) }.getOrElse {
            // 服务端契约被破坏（比如返回了非 JSON）——这属于实现 bug，
            // 但也要给出可读信息，而不是一个 JSONException 糊在用户脸上。
            throw LlmException(
                QuroLlmError.LOAD_FAILED,
                "推理进程返回了无法解析的结果：${raw?.take(160)}"
            )
        }
        if (!env.optBoolean("ok", false)) {
            throw LlmException(
                env.optInt("code", QuroLlmError.LOAD_FAILED),
                env.optString("message", "模型加载失败")
            )
        }
        return env.optLong("sessionId", 0L).takeIf { it > 0L }
            ?: throw LlmException(QuroLlmError.LOAD_FAILED, "推理进程未返回有效 sessionId")
    }

    fun closeSession(sessionId: Long) {
        runCatching { service?.closeSession(sessionId) }
    }

    fun isSessionLoaded(sessionId: Long): Boolean =
        runCatching { service?.sessionLoaded(sessionId) ?: false }.getOrDefault(false)

    // ─────────────────────────────────────────────────────────────────────
    // 生成
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 生成一个 [LlmEvent] 流。
     *
     * 结束语义（重要）：流的**唯一**正常结束方式是收到 [LlmEvent.Done] 或
     * [LlmEvent.Error]。不要用「收集完 flow」当作成功 —— oneway 语义下
     * 事件可能丢，flow 关闭本身不代表推理正常结束。
     *
     * @param idleTimeoutMs 空闲超时；传 0 关闭。
     */
    fun generate(
        sessionId: Long,
        request: JSONObject,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    ): Flow<LlmEvent> = eventStream(idleTimeoutMs) { svc, reqId, cb ->
        svc.generate(sessionId, reqId, request.toString(), cb)
    }

    /**
     * 兼容通道：在 :llm 进程内执行一次完整本地推理（见 `IQuroLlmService.execLocal`）。
     *
     * 与 [generate] 的区别只有一个：请求自带模型（不依赖服务端预先 openSession），
     * 所以拿到的是「一次完整 QuroLocalEngine.run」的等价语义。
     * 事件流语义与 [generate] 完全一致。
     *
     * @param request 见 `IQuroLlmService.execLocal` 的字段说明。
     */
    fun execLocal(
        request: JSONObject,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    ): Flow<LlmEvent> = eventStream(idleTimeoutMs) { svc, reqId, cb ->
        svc.execLocal(reqId, request.toString(), cb)
    }

    /**
     * [generate] / [execLocal] 共用的投递骨架。
     *
     * 为什么要抽出来：两者除了「投递哪个方法」之外，reqId 分配、看门狗、
     * 终态判定、提前退出的取消下发**逐字相同**。留两份拷贝的下场是
     * 修了其中一处忘了另一处 —— 而这里任何一处漏修都是「用户点停止没反应」
     * 或「卡死永远不解锁」这类难查的线上问题。
     *
     * @param submit 用给定的 reqId 与回调向服务端投递。抛 RemoteException 视为断连。
     */
    private fun eventStream(
        idleTimeoutMs: Long,
        submit: (IQuroLlmService, Long, IQuroLlmCallback) -> Unit,
    ): Flow<LlmEvent> = callbackFlow {
        val svc = service
        if (svc == null) {
            trySend(LlmEvent.Error(0L, QuroLlmError.SERVICE_NOT_BOUND, "推理进程未连接"))
            close()
            return@callbackFlow
        }

        val reqId = reqSeq.incrementAndGet()

        // 「这次请求已经有了终态」标志。
        //
        // 为什么必须有：callbackFlow 的 awaitClose **无论正常结束还是中途取消都会被调用**。
        // 如果不区分，每次正常完成（onDone）之后都会再补一发 cancel 给引擎 ——
        // 对抽象路径是「恰好没有在飞请求所以无操作」的侥幸，对单请求的 execLocal
        // 则可能命中下一个请求的计时窗口。这类竞态平时看不出来，压力下就是
        // 「偶尔有一次生成莫名被中断」。终态一旦到达，取消就必须闭嘴。
        var settled = false

        // 看门狗：每次收到事件就重置。用 Job 重启而不是计时累加，
        // 避免「收到事件但计时器没被正确取消」这类经典竞态。
        var watchdog: Job? = null
        fun armWatchdog() {
            if (idleTimeoutMs <= 0L) return
            watchdog?.cancel()
            watchdog = launch {
                delay(idleTimeoutMs)
                if (isActive) {
                    settled = true
                    trySend(
                        LlmEvent.Error(
                            reqId, QuroLlmError.TIMEOUT,
                            "推理进程 ${idleTimeoutMs / 1000} 秒无响应（可能已被系统回收）"
                        )
                    )
                    runCatching { svc.cancel(reqId) }
                    close()
                }
            }
        }
        armWatchdog()

        val cb = object : IQuroLlmCallback.Stub() {
            override fun onReady(rId: Long, engineName: String, backendJson: String) {
                if (rId != reqId) return
                armWatchdog()
                trySend(LlmEvent.Ready(rId, engineName, backendJson))
            }

            override fun onPrefill(rId: Long, done: Int, total: Int) {
                if (rId != reqId) return
                armWatchdog()
                trySend(LlmEvent.Prefill(rId, done, total))
            }

            override fun onToken(rId: Long, text: String) {
                if (rId != reqId) return
                armWatchdog()
                trySend(LlmEvent.Token(rId, text))
            }

            override fun onThinking(rId: Long, text: String) {
                if (rId != reqId) return
                armWatchdog()
                trySend(LlmEvent.Thinking(rId, text))
            }

            override fun onDone(rId: Long, finishReason: String, statsJson: String) {
                if (rId != reqId) return
                watchdog?.cancel()
                settled = true
                trySend(LlmEvent.Done(rId, finishReason, statsJson))
                close()
            }

            override fun onError(rId: Long, code: Int, message: String) {
                if (rId != reqId) return
                watchdog?.cancel()
                settled = true
                trySend(LlmEvent.Error(rId, code, message))
                close()
            }
        }

        try {
            submit(svc, reqId, cb)
        } catch (e: RemoteException) {
            watchdog?.cancel()
            settled = true
            trySend(LlmEvent.Error(reqId, QuroLlmError.SERVICE_NOT_BOUND, "投递失败：${e.message}"))
            close()
        }

        awaitClose {
            watchdog?.cancel()
            // 只有「消费者提前退出」（用户点停止 / 页面销毁）才需要把取消传下去，
            // 否则引擎会在后台把这次生成跑完，既费电又占着 KV cache。
            if (!settled) runCatching { svc.cancel(reqId) }
        }
    }

    fun cancel(reqId: Long) {
        runCatching { service?.cancel(reqId) }
    }

    fun cancelAll(sessionId: Long) {
        runCatching { service?.cancelAll(sessionId) }
    }

    /** 请求 :llm 进程退出，立刻把 30–50MB 常驻开销还回去。 */
    fun shutdown() {
        runCatching { service?.shutdown() }
        service = null
    }

    fun stats(sessionId: Long): String =
        runCatching { service?.stats(sessionId) ?: "{}" }.getOrDefault("{}")

    fun diagnostics(): String =
        runCatching { service?.diagnostics() ?: "{}" }.getOrDefault("{}")

    fun deviceProfile(): String =
        runCatching { service?.deviceProfile() ?: "{}" }.getOrDefault("{}")
}

/** 生成事件。终态只有 [Done] / [Error] 两者之一。 */
sealed class LlmEvent {
    data class Ready(val reqId: Long, val engineName: String, val backendJson: String) : LlmEvent()
    data class Prefill(val reqId: Long, val done: Int, val total: Int) : LlmEvent()
    data class Token(val reqId: Long, val text: String) : LlmEvent()

    /** 思考段累计文本。仅 execLocal 通道会产生（见 `IQuroLlmCallback.onThinking`）。 */
    data class Thinking(val reqId: Long, val text: String) : LlmEvent()

    data class Done(val reqId: Long, val finishReason: String, val statsJson: String) : LlmEvent()
    data class Error(val reqId: Long, val code: Int, val message: String) : LlmEvent()
}

class LlmException(val code: Int, override val message: String) : RuntimeException(message) {
    /** 带错误码人话名的完整描述，UI 可直接展示。 */
    val display: String get() = "${QuroLlmError.name(code)}：$message"
}
