package com.ai.assistance.quro.core.network

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.llm.LlmEngineClient
import com.ai.assistance.quro.llm.LlmEvent
import com.ai.assistance.quro.llm.QuroLlmError
import com.ai.assistance.quro.llm.QuroLlmWireCodec
import com.ai.assistance.quro.util.QuroDiag
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * 本地引擎的**跨进程**实现：把一次完整的 `run(...)` 交给 `:llm` 独立进程执行。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么要有它（硬规则第 1 条：进程隔离）
 * ═══════════════════════════════════════════════════════════════════════
 * 4GB 级量化模型加载后 native heap 可到 3.8GB。跑在主进程里，低端机上 LMK
 * 会在几十秒内连 UI 一起杀掉 —— 用户看到的是「聊两句整个界面闪退」。
 * 换到 `:llm` 进程后：被杀的是引擎进程，主界面活着并给出可读提示；
 * 附带收益是 GPU delegate 的 native crash 只 kill 自己。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 与 [QuroLocalEngineNative] 的关系：**同一份逻辑，换个进程跑**
 * ═══════════════════════════════════════════════════════════════════════
 * 本类**刻意不做任何 prompt 组装、思考段剥离、工具解析** —— 那些全部留在
 * `:llm` 侧的 [QuroLocalEngineNative] 里原样执行（见 `QuroLlmExecLocal`）。
 * 理由：那些逻辑是长期真机调出来的（chat template 的 addAssistant、
 * MNN 抗复读采样链、<think> 流式剥离、工具消息排序），在主进程重写一遍
 * 必然与原路径漂移，而漂移的表现是「本地推理时好时坏」，极难定位。
 *
 * 所以本类只负责三件事：
 *   1. **绑定**引擎进程并投递请求；
 *   2. 把 oneway 的 [LlmEvent] 流翻译回 `run(...)` 的同步回调语义
 *      （`onToken` 是**累计**文本，与接口约定一致）；
 *   3. 把取消与断连翻译成调用方熟悉的结果 —— 取消**抛 CancellationException**，
 *      与 [QuroLocalEngineNative] 被中断时的行为逐字一致，
 *      这样上层「⏹ 已停止生成」的展示逻辑不需要改。
 *
 * 风味隔离：本类位于 `app/src/full/java`，仅 full 风味编译
 * （fdroid 无 `:mnn` / `:llama`，也就没有引擎进程可连）。
 * [com.ai.assistance.quro.core.QuroAssistant] 通过反射按偏好选择
 * 本类或 [QuroLocalEngineNative]。
 */
class QuroLocalEngineRemote(context: Context) : QuroLocalEngine {

    private companion object {
        const val TAG = "QuroLocalEngineRemote"

        /**
         * Binder 单次 transaction 上限约 1MB。留出余量到 900KB 就提前拒绝 ——
         * 超限时系统抛的是 TransactionTooLargeException，那条栈对用户毫无意义，
         * 不如自己给一条「对话过长」的明确提示。
         */
        const val MAX_REQUEST_CHARS = 900_000

        /** 与 QuroLocalEngineNative 被中断时抛出的消息保持一致（上层按它判「已停止」）。 */
        const val LOCAL_CANCEL_MSG = "local generation canceled"
    }

    private val client = LlmEngineClient(context.applicationContext)

    /**
     * 模型加载由 `:llm` 进程自行负责（见 `QuroLlmExecLocal.ensureLoaded`）。
     *
     * 主进程不能替它加载：那会把 GB 级权重装进主进程，正是进程隔离要避免的事。
     */
    override val managesOwnLoading: Boolean = true

    override fun run(
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
        val t0 = System.nanoTime()

        // ── 1. 组装载荷。体积预检放在最前：宁可在本地立刻给出「对话过长」，
        //      也不要让 Binder 抛 TransactionTooLargeException。
        val request = JSONObject()
            .put("model", QuroLlmWireCodec.encodeModel(model))
            .put("modelName", modelName)
            .put("messages", QuroLlmWireCodec.encodeMessages(messages))
            .put("temperature", temperature.toDouble())
            .put("maxTokens", maxTokens)
            .put("contextWindow", contextWindow)
            .apply { toolSpecsJson?.let { put("toolSpecsJson", it) } }

        val payload = request.toString()
        if (payload.length > MAX_REQUEST_CHARS) {
            QuroDiag.log("LocalEngineRemote", "✗ 载荷过大 | chars=${payload.length}")
            return QuroLlmResult.Error(
                "本地对话上下文过长（约 ${payload.length / 1024}KB），超过了进程间传输上限。\n" +
                    "请新建对话，或在「设置 → 模型配置」里调小上下文窗口后重试。"
            )
        }

        if (!ensureConnected()) {
            return QuroLlmResult.Error(
                "无法连接本地推理进程。\n" +
                    "可能原因：系统回收了推理进程 / 应用被限制后台进程数。\n" +
                    "请重试；若持续失败，可到「设置 → 模型配置」重新加载模型。"
            )
        }

        val engineName = when (model.type) {
            QuroLocalModelType.MNN -> "MNN"
            QuroLocalModelType.LLAMA_CPP -> "llama.cpp"
        }
        QuroDiag.log(
            "LocalEngineRemote",
            "▶ 跨进程 run start | engine=$engineName | path=${model.path} | " +
                "msgs=${messages.size} | temp=$temperature | max=$maxTokens | " +
                "ctxWindow=$contextWindow | stream=${onToken != null} | chars=${payload.length}"
        )

        // ── 2. 收集事件流。run() 是同步阻塞接口，所以在这里桥接到挂起世界。
        //      用 runBlocking 而非改造接口：进程内路径（QuroLocalEngineNative）
        //      同样是阻塞的，保持接口不变才能让上层零改动切换两条路径。
        var failure: String? = null

        try {
            runBlocking {
                client.execLocal(request).collect { ev ->
                    when (ev) {
                        is LlmEvent.Ready -> QuroDiag.log(
                            "LocalEngineRemote",
                            "✓ 引擎就绪 | engine=${ev.engineName} | backend=${ev.backendJson}"
                        )

                        is LlmEvent.Token -> {
                            // 语义核对：execLocal 的 onToken 已经是**累计**文本，
                            // 与 QuroLocalEngine.onToken 的约定一致 → 直接转发，不做二次累加。
                            // （若这里再累加一次，UI 会看到文本成倍重复。）
                            onToken?.let { cb -> runCatching { cb(ev.text) } }
                            if (isCanceled()) throw CancellationException(LOCAL_CANCEL_MSG)
                        }

                        is LlmEvent.Thinking ->
                            onThinking?.let { cb -> runCatching { cb(ev.text) } }

                        // 预填充进度目前只入诊断日志（UI 的等待动画独立于它）。
                        is LlmEvent.Prefill ->
                            if (ev.total > 0 && ev.done == ev.total) {
                                QuroDiag.log("LocalEngineRemote", "· prefill 完成 | ${ev.total} token")
                            }

                        is LlmEvent.Done -> {
                            if (ev.finishReason == "cancel") {
                                // 引擎侧自己判定被取消（原生 decode 循环收到 abort）。
                                // 与进程内路径一致地抛出，让上层走「已停止生成」。
                                throw CancellationException(LOCAL_CANCEL_MSG)
                            }
                            val env = runCatching { JSONObject(ev.statsJson) }.getOrNull()
                            if (env == null) {
                                failure = "推理进程返回了无法解析的结果：${ev.statsJson.take(160)}"
                                throw ResultReady(
                                    QuroLlmResult.Error(failure!!)
                                )
                            }
                            QuroDiag.log(
                                "LocalEngineRemote",
                                "✓ 结果信封 | kind=${env.optString("kind")}"
                            )
                            throw ResultReady(QuroLlmWireCodec.decodeResult(env))
                        }

                        is LlmEvent.Error -> {
                            failure = if (ev.code == QuroLlmError.TIMEOUT) {
                                "本地推理进程长时间无响应（可能已被系统回收）。请重试。"
                            } else {
                                ev.message
                            }
                            throw ResultReady(QuroLlmResult.Error(failure!!))
                        }
                    }
                }
            }
            // 走到这里说明流结束了但没有终态事件 —— oneway 丢包或进程被杀。
            // 绝不能当成成功（那会让 UI 收到空回复还以为正常）。
            failure = failure ?: "本地推理进程未返回结果（连接可能已中断）"
        } catch (done: ResultReady) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            val r = done.result
            when (r) {
                is QuroLlmResult.Text -> QuroDiag.log(
                    "LocalEngineRemote",
                    "✓ 跨进程 run done | engine=$engineName | ${ms}ms | chars=${r.content.length}"
                )
                is QuroLlmResult.ToolCalls -> QuroDiag.log(
                    "LocalEngineRemote",
                    "✓ 跨进程 run done (toolcalls) | engine=$engineName | ${ms}ms | calls=${r.calls.size}"
                )
                is QuroLlmResult.Error -> QuroDiag.log(
                    "LocalEngineRemote",
                    "✗ 跨进程 run error | engine=$engineName | ${ms}ms | ${r.message}"
                )
            }
            return r
        } catch (c: CancellationException) {
            // 取消不是失败。原样抛出，与 QuroLocalEngineNative 逐字一致 ——
            // 上层据此走「⏹ 已停止生成」而不是错误气泡。
            QuroDiag.log("LocalEngineRemote", "· 跨进程 run 已取消 | engine=$engineName")
            throw CancellationException(LOCAL_CANCEL_MSG)
        } catch (t: Throwable) {
            QuroDiag.log(
                "LocalEngineRemote",
                "✗ 跨进程 run 异常 | ${t.javaClass.simpleName}: ${t.message}"
            )
            return QuroLlmResult.Error(
                "本地推理（独立进程）执行异常：${t.javaClass.simpleName}: ${t.message}"
            )
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        val detail = failure ?: "本地推理未返回结果"
        QuroDiag.log("LocalEngineRemote", "✗ 跨进程 run 无终态 | engine=$engineName | ${ms}ms | $detail")
        return QuroLlmResult.Error(detail)
    }

    /**
     * 确保与 `:llm` 进程已连接。
     *
     * 失败时**先解绑再重试一次**：引擎进程被 LMK 杀掉后，系统的
     * ServiceConnection 可能还留着旧状态（`onServiceDisconnected` 与
     * 「下一次 bind 会自动拉起进程」之间存在窗口），直接复用会一直失败。
     * 解绑能强制清掉这个状态，重新 bind 会拉起新进程。
     */
    private fun ensureConnected(): Boolean {
        if (client.isConnected()) return true
        val first = runCatching { runBlocking { client.connect() } }.getOrDefault(false)
        if (first) return true

        Log.w(TAG, "首次绑定 :llm 失败，解绑后重试一次")
        runCatching { client.disconnect() }
        return runCatching { runBlocking { client.connect() } }.getOrDefault(false)
    }

    /** 主动释放引擎进程（换模型 / 退出本地对话时调，立刻还回 30–50MB）。 */
    fun shutdown() {
        runCatching { client.shutdown() }
        runCatching { client.disconnect() }
    }

    /**
     * 内部信号：把「已拿到终态结果」从 collect 里带出来。
     *
     * 为什么用异常而不是变量赋值：`collect` 的 lambda 无法直接跳出 `runBlocking`，
     * 而收到终态后必须**立刻停止收集**（oneway 下终态之后仍可能有迟到事件，
     * 继续收集只会拿到噪声）。抛一个私有异常是最直接的「就地跳出」手段。
     *
     * 关掉栈与 suppression（`enableSuppression=false, writableStackTrace=false`）：
     * 这是纯控制流信号，抓栈毫无价值，反而会在诊断日志里制造误导性的调用链。
     * 它永远不会逃出本类的 run()。
     */
    private class ResultReady(val result: QuroLlmResult) : Exception(null, null, false, false)
}
