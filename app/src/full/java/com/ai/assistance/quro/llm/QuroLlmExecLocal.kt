package com.ai.assistance.quro.llm

import android.os.SystemClock
import android.util.Log
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.core.network.LocalModelLoader
import com.ai.assistance.quro.core.network.LocalModelLoaders
import com.ai.assistance.quro.core.network.QuroLocalEngineNative
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * L1 · 「兼容通道」在 :llm 进程内的执行器 —— 把一次完整的
 * `QuroLocalEngine.run(...)` 整段搬到引擎进程执行。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 它和 [QuroLlmEngineHost] 是什么关系？
 * ═══════════════════════════════════════════════════════════════════════
 * 两者是**同一层（L1）的两条并存通道**，不是新旧关系，也不会同时持有权重：
 *
 *   · [QuroLlmEngineHost]（抽象路径 / `generate`）
 *       服务端自己管会话、自己决定 prompt 形态。目标形态 —— 未来 C++ 侧
 *       `quro::llm::Engine` 收敛后，这一条会变成唯一的入口。
 *
 *   · [QuroLlmExecLocal]（兼容路径 / `execLocal`）—— **本类**
 *       直接跑既有 `QuroLocalEngineNative`。它的价值是让「进程隔离」这件事
 *       **独立于 L3 抽象先行落地**：现网跑通的 chat template 组装、思考段流式剥离、
 *       MNN 抗复读采样链、常驻会话闸门，全部原样复用，功能零退化。
 *
 * 分工的硬边界（由 [QuroLlmService] 强制）：两条通道**同一时刻只有一条持有大权重**
 * （硬规则第 3 条）。切换时先让另一条真正 release 再加载，见 [evictLocal]。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 三条实现纪律
 * ═══════════════════════════════════════════════════════════════════════
 * 1) **不做任何业务变换**。messages 原样交给 [QuroLocalEngineNative]，
 *    包括「自动加载模型」这一步也照抄主进程 routeLocal 的语义 —— 否则会出现
 *    「进程内能跑、隔离后门禁拦截」这种最难查的行为漂移。
 *
 * 2) **取消必须能真正打断**。`isCanceled` 闭包读的是原子标志，
 *    `QuroLocalEngineNative` 在原生 token 回调里检查它并 `return false` 终止
 *    decode 循环 —— 与进程内路径走的是同一条中断链路。
 *
 * 3) **每个 reqId 的执行是互斥的**。原生侧有静态 `genLock` 保证全局串行，
 *    这里额外用一个「会话级 CAS」把并发的第二个请求挡在门外，
 *    回 SESSION_BUSY 而不是让它排队 —— 排队会让用户以为卡住了。
 */
internal class QuroLlmExecLocal private constructor() {

    companion object {
        private const val TAG = "QuroLlm.ExecLocal"

        @Volatile
        private var INSTANCE: QuroLlmExecLocal? = null

        fun instance(): QuroLlmExecLocal = INSTANCE ?: synchronized(this) {
            INSTANCE ?: QuroLlmExecLocal().also { INSTANCE = it }
        }
    }

    /** 当前在飞的 reqId → 取消标志。cancel(reqId) 找到它并置位。 */
    private val inFlight = ConcurrentHashMap<Long, AtomicBoolean>()

    /**
     * 单线程执行器。
     *
     * 为什么不是 cached pool：原生侧 `QuroLocalEngineNative` 的静态 `genLock`
     * 已经把生成串行化了，多开线程只会让它们在锁上排队、白占内存；
     * 更糟的是「排队中的请求」无法被 cancel 及时打断（它还没进入推理循环）。
     * 单线程 + 前面的 CAS 拒绝，语义最干净。
     *
     * 但**不能**用 single-thread：加载模型（数秒~数十秒）与「用户放弃并点取消」
     * 必须能并行处理，否则取消要等加载跑完才生效。所以用 1 个执行线程 +
     * 任意多个等待线程的 pool，靠 CAS 保证真正进入执行区的只有一个。
     */
    private val worker = Executors.newCachedThreadPool { r ->
        Thread(r, "quro-llm-execLocal").apply { isDaemon = true }
    }

    /** 是否有请求在飞（内存互斥判定用：在飞时不允许别的通道卸载权重）。 */
    fun hasInFlight(): Boolean = inFlight.isNotEmpty()

    /**
     * 在 :llm 进程内执行一次完整本地推理。**立即返回**，结果经 [sink] 回流。
     *
     * @param reqId        客户端生成的全局唯一 id；所有回调都带它。
     * @param requestJson  见 `IQuroLlmService.execLocal` 的字段说明。
     */
    fun exec(reqId: Long, requestJson: String, sink: QuroLlmEngineHost.Sink) {
        // ── 解析：同步做，非法请求立刻回错误（不要丢进线程里再失败，
        //    那样用户要多等一次线程调度才看到报错）。
        val req = runCatching { JSONObject(requestJson) }.getOrElse {
            sink.error(reqId, QuroLlmError.BAD_ARGUMENT, "请求不是合法 JSON：${it.message}")
            return
        }
        val modelJson = req.optJSONObject("model")
        if (modelJson == null) {
            sink.error(reqId, QuroLlmError.BAD_ARGUMENT, "请求缺少 model 字段")
            return
        }
        val model = runCatching { QuroLlmWireCodec.decodeModel(modelJson) }.getOrElse {
            sink.error(reqId, QuroLlmError.BAD_ARGUMENT, "model 字段无法解析：${it.message}")
            return
        }
        if (model.path.isBlank()) {
            sink.error(reqId, QuroLlmError.BAD_ARGUMENT, "model.path 为空，无法定位模型")
            return
        }
        val messages = runCatching {
            QuroLlmWireCodec.decodeMessages(req.optJSONArray("messages") ?: JSONArray())
        }.getOrElse {
            sink.error(reqId, QuroLlmError.BAD_ARGUMENT, "messages 无法解析：${it.message}")
            return
        }

        val modelName = req.optString("modelName", model.name)
        val temperature = req.optDouble("temperature", 0.7).toFloat()
        val maxTokens = req.optInt("maxTokens", 512)
        val contextWindow = req.optInt("contextWindow", 0)
        val toolSpecsJson = req.optString("toolSpecsJson", "").takeIf { it.isNotBlank() }

        // ── 并发保护：同一个 reqId 重复投递直接忽略（oneway 重投时可能出现）。
        val flag = AtomicBoolean(false)
        if (inFlight.putIfAbsent(reqId, flag) != null) {
            Log.w(TAG, "reqId=$reqId 重复投递，忽略")
            return
        }

        worker.execute {
            val started = SystemClock.elapsedRealtime()
            val engineTag = when (model.type) {
                QuroLocalModelType.MNN -> "MNN"
                QuroLocalModelType.LLAMA_CPP -> "llama.cpp"
            }
            try {
                // ── 自动加载：与主进程 routeLocal 逐字对齐。
                //    进程隔离后「模型配置」页的加载按钮在主进程点，那边加载不了
                //    引擎进程的内存 —— 所以这里必须能自给自足地补上这一步。
                val loaderErr = ensureLoaded(model)
                if (loaderErr != null) {
                    sink.error(reqId, loaderErr.first, loaderErr.second)
                    return@execute
                }

                sink.ready(
                    reqId, engineTag,
                    JSONObject()
                        .put("engine", engineTag)
                        .put("process", QuroLlmService.PROCESS_NAME)
                        .put("isolated", true)
                        .put("channel", "execLocal")
                        .toString()
                )

                val result = QuroLocalEngineNative().run(
                    model = model,
                    modelName = modelName,
                    messages = messages,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    contextWindow = contextWindow,
                    toolSpecsJson = toolSpecsJson,
                    onToken = { accumulated ->
                        // 语义核对：QuroLocalEngine.onToken 传的是**累计**文本
                        // （见接口文档），与 AIDL 回调约定一致，直接转发不做二次累加。
                        if (!flag.get()) sink.token(reqId, accumulated)
                    },
                    onThinking = { accumulated ->
                        if (!flag.get()) sink.thinking(reqId, accumulated)
                    },
                    isCanceled = { flag.get() },
                )

                val ms = SystemClock.elapsedRealtime() - started
                val payload = QuroLlmWireCodec.encodeResult(result)
                Log.i(TAG, "execLocal 完成 reqId=$reqId engine=$engineTag ${ms}ms kind=${payload.optString("kind")}")
                sink.done(reqId, "stop", payload.toString())
            } catch (c: CancellationException) {
                // 用户打断 / 切走对话。**必须**与进程内路径表现一致：
                // QuroLocalEngineNative 被取消时抛的正是 CancellationException，
                // 上层据此走「⏹ 已停止生成」，而不是「⚠️ 推理异常」错误气泡。
                // 这里如果当成 GENERATE_FAILED 回报，用户会在每次点停止时看到一条
                // 假错误 —— 这是进程隔离最容易引入、也最容易被误判成「隔离把功能搞坏了」的回归。
                //
                // 已生成的部分文本不需要在这里回传：每一次 token 都已经实时推给
                // 主进程了（onToken 是累计语义），主进程手上的就是最新的部分结果。
                Log.i(TAG, "execLocal 被取消 reqId=$reqId（已生成的 token 早已回流）")
                sink.done(reqId, "cancel", JSONObject().put("kind", "cancel").toString())
            } catch (t: Throwable) {
                // 走到这里通常是 OOM 或原生层崩溃前的兜底。必须翻成可读错误 ——
                // 让它冒泡出去，主进程看到的是「引擎进程已死」，完全无法排障。
                Log.e(TAG, "execLocal 未预期异常 reqId=$reqId", t)
                sink.error(
                    reqId, QuroLlmError.GENERATE_FAILED,
                    "本地推理异常：${t.javaClass.simpleName}: ${t.message}"
                )
            } finally {
                inFlight.remove(reqId)
            }
        }
    }

    /**
     * 取消指定请求。任意线程可调。
     *
     * 只置标志，不试图去中断已经在原生层跑起来的 decode ——
     * 那是 [QuroLocalEngineNative] 的 `isCanceled` 闭包负责的事：
     * 它在每个 token 回调里读同一份标志并终止循环。
     * 这里抢锁去 free 权重反而会造出「释放与在飞调用竞争」的堆损坏。
     */
    fun cancel(reqId: Long) {
        inFlight[reqId]?.let {
            it.set(true)
            Log.i(TAG, "cancel reqId=$reqId 已置取消标志")
        }
    }

    fun cancelAll() {
        inFlight.keys.toList().forEach { cancel(it) }
    }

    /**
     * 卸载引擎进程内常驻的本地模型（内存互斥，硬规则第 3 条）。
     *
     * 由 [QuroLlmService] 在「另一条通道要加载模型」时调用。
     * 有在飞请求时**不**强行卸载 —— `LocalModelSessionHolder.unload()` 内部
     * 本来就有 activeGen 归零等待，这里只是不去添乱。
     */
    fun evictLocal() {
        val loader = LocalModelLoaders.get()
        if (loader.getState() is LocalModelLoader.State.None) return
        Log.i(TAG, "内存互斥：卸载本地常驻模型（另一条通道要占用权重）")
        runCatching { loader.unload() }
            .onFailure { Log.w(TAG, "卸载本地常驻模型失败", it) }
    }

    /**
     * 确保 [model] 已在**本进程**（:llm）加载完成。返回 null 表示就绪，
     * 否则返回 (错误码, 可读原因)。
     *
     * 与主进程 `QuroAssistant.routeLocal` 的自动加载分支语义一致：
     * 未加载就加载，失败即返回明确原因（而不是让下游拿一个晦涩的原生错误）。
     */
    private fun ensureLoaded(model: QuroLocalModel): Pair<Int, String>? {
        val loader = LocalModelLoaders.get()
        if (loader.isLoaded(model)) return null

        Log.i(TAG, "自动加载本地模型 | name=${model.name} | type=${model.type} | path=${model.path}")
        return when (val lr = loader.load(model)) {
            is LocalModelLoader.LoadResult.Success -> null

            is LocalModelLoader.LoadResult.Failure -> {
                // 把持有器的真实状态一并带出去。用户看到「加载失败」时，
                // 最需要知道的是**为什么**（路径不对 / 权重损坏 / 内存不足），
                // 而这些信息只在 holder 的 State 里。
                val stateDesc = runCatching {
                    when (val st = loader.getState()) {
                        is LocalModelLoader.State.Failed -> "（持有器状态：${st.message}）"
                        is LocalModelLoader.State.Loading -> "（持有器状态：仍在上一次加载中）"
                        else -> ""
                    }
                }.getOrDefault("")
                QuroLlmError.LOAD_FAILED to "本地模型加载失败：${lr.message}$stateDesc"
            }
        }
    }
}
