package com.ai.assistance.quro.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import org.json.JSONObject

/**
 * L1 · 引擎宿主服务 —— 跑在 `:llm` 独立进程里。
 *
 * 为什么单独一个进程（硬规则第 1 条）：
 *   4GB 级量化模型加载后 native heap 可到 3.8GB。放在主进程里，低端机上 LMK
 *   会在几十秒内把整个 App 杀掉 —— 用户看到的是「聊两句整个界面闪退」。
 *   独立进程后，被杀的是引擎进程：主界面活着，能给出「推理进程被系统回收，
 *   点这里重试」这种可读提示。附带收益是 GPU delegate 的 native crash
 *   只 kill 自己，不会连 UI 一起带走。
 *   代价是多 30–50MB 进程开销 —— 完全值。
 *
 * 本类只做「Binder 边界 + 错误码翻译 + 两条通道之间的内存仲裁」，业务在两个执行器里：
 *   · [QuroLlmEngineHost]   —— 抽象路径（generate）
 *   · [QuroLlmExecLocal]    —— 兼容路径（execLocal）
 *
 * 为什么仲裁必须放在**这一层**：两条通道都在本进程内加载权重，而进程内的
 * `LocalModelSessionHolder` 只知道自己那条链路，看不到另一条。
 * 服务是它们唯一的共同上游，所以「任何加载之前先让另一条释放」只能在这里做。
 * （硬规则第 3 条：同一时刻只让一个引擎持有大模型权重。）
 */
class QuroLlmService : Service() {

    companion object {
        private const val TAG = "QuroLlm.Service"

        /** 与 AndroidManifest 里 `android:process` 的值保持一致。 */
        const val PROCESS_NAME = ":llm"
    }

    private val host = QuroLlmEngineHost.instance()
    private val execLocal = QuroLlmExecLocal.instance()

    /**
     * 把宿主的 [QuroLlmEngineHost.Sink] 连到远端的 AIDL 回调。
     *
     * 每一次回调都包 runCatching：客户端可能在生成中途被系统杀掉 /
     * 主动解绑，此时 RemoteException 会从 Binder 代理抛出来。绝不能让它
     * 冒泡到推理线程 —— 那会让一次正常的「用户退出对话」变成引擎侧崩溃。
     */
    private class RemoteSink(private val cb: IQuroLlmCallback) : QuroLlmEngineHost.Sink {
        override fun ready(reqId: Long, engineName: String, backendJson: String) =
            safe("onReady") { cb.onReady(reqId, engineName, backendJson) }

        override fun prefill(reqId: Long, done: Int, total: Int) =
            safe("onPrefill") { cb.onPrefill(reqId, done, total) }

        override fun token(reqId: Long, text: String) =
            safe("onToken") { cb.onToken(reqId, text) }

        override fun thinking(reqId: Long, text: String) =
            safe("onThinking") { cb.onThinking(reqId, text) }

        override fun done(reqId: Long, finishReason: String, statsJson: String) =
            safe("onDone") { cb.onDone(reqId, finishReason, statsJson) }

        override fun error(reqId: Long, code: Int, message: String) =
            safe("onError") { cb.onError(reqId, code, message) }

        private inline fun safe(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: RemoteException) {
                // 客户端没了。不是错误，只是这次回调无处可去 —— 记一行就够，
                // 别打成 error 级别污染日志（用户正常退出对话时必然出现）。
                Log.d(TAG, "回调 $what 失败：客户端已断开（${e.message}）")
            } catch (t: Throwable) {
                Log.w(TAG, "回调 $what 异常", t)
            }
        }
    }

    private val binder = object : IQuroLlmService.Stub() {

        override fun openSession(modelPath: String?, optionsJson: String?): String {
            if (modelPath.isNullOrBlank()) {
                return JSONObject()
                    .put("ok", false)
                    .put("code", QuroLlmError.BAD_ARGUMENT)
                    .put("message", "modelPath 不能为空")
                    .toString()
            }
            // 硬规则第 3 条：抽象路径要加载权重前，先让兼容路径把它的常驻模型卸掉。
            runCatching { execLocal.evictLocal() }
                .onFailure { Log.w(TAG, "加载前清理 execLocal 常驻模型失败", it) }
            // 宿主已经把所有失败路径都收敛成 JSON 信封了（见 QuroLlmEngineHost.openSession）。
            // 这里只做最后一道兜底：万一信封本身构造过程出意外，
            // 也绝不让异常穿过 Binder —— 那在客户端会变成一个
            // message 为 "类名: 原文" 的通用 RuntimeException，错误码全丢。
            return try {
                host.openSession(modelPath, optionsJson ?: "{}")
            } catch (t: Throwable) {
                Log.e(TAG, "openSession 兜底捕获", t)
                JSONObject()
                    .put("ok", false)
                    .put("code", QuroLlmError.LOAD_FAILED)
                    .put("message", "模型加载异常：${t.javaClass.simpleName}: ${t.message}")
                    .toString()
            }
        }

        override fun closeSession(sessionId: Long) = host.closeSession(sessionId)

        override fun sessionLoaded(sessionId: Long): Boolean = host.sessionLoaded(sessionId)

        override fun generate(
            sessionId: Long,
            reqId: Long,
            requestJson: String?,
            cb: IQuroLlmCallback?
        ) {
            val sink = cb?.let { RemoteSink(it) }
            if (sink == null) {
                Log.w(TAG, "generate 收到空回调 reqId=$reqId，直接丢弃")
                return
            }
            // 注意：这里**不**包 try/catch 兜住一切再吞掉 ——
            // host.generate 本身保证「立即返回」，真正的推理在它自己的线程上跑，
            // 那条线程里的异常由 host 自己翻成 onError 回调。
            host.generate(sessionId, reqId, requestJson ?: "{}", sink)
        }

        override fun cancel(reqId: Long) {
            // 两条通道都要通知：reqId 是全局唯一的，而客户端并不知道
            // 这次请求走的是哪条通道。漏通知一条就是「点停止没反应」。
            host.cancel(reqId)
            execLocal.cancel(reqId)
        }

        override fun cancelAll(sessionId: Long) {
            host.cancelAll(sessionId)
            // 兼容路径没有会话概念（每次 execLocal 自带模型），所以按全部取消处理：
            // 调用方调 cancelAll 的语义是「把这个会话上的生成全停掉」，
            // 而 :llm 进程内同一时刻至多一个 execLocal 在飞，这不会误伤。
            execLocal.cancelAll()
        }

        override fun stats(sessionId: Long): String = host.stats(sessionId)

        override fun deviceProfile(): String = QuroDeviceProfile.describe()

        override fun diagnostics(): String = JSONObject()
            .put("abstractPath", runCatching { JSONObject(host.diagnostics()) }
                .getOrDefault(JSONObject()))
            .put("execLocalInFlight", execLocal.hasInFlight())
            .toString()

        override fun execLocal(reqId: Long, requestJson: String?, cb: IQuroLlmCallback?) {
            val sink = cb?.let { RemoteSink(it) }
            if (sink == null) {
                Log.w(TAG, "execLocal 收到空回调 reqId=$reqId，直接丢弃")
                return
            }
            if (requestJson.isNullOrBlank()) {
                sink.error(reqId, QuroLlmError.BAD_ARGUMENT, "requestJson 不能为空")
                return
            }
            // 硬规则第 3 条：兼容路径要加载权重前，先让抽象路径把会话全关掉。
            // closeAll 内部会 cancel 在飞生成 → 等 grace → release，
            // 所以这里返回时旧权重已经真的还给了系统，不会出现两个权重同驻。
            runCatching { host.closeAll() }
                .onFailure { Log.w(TAG, "加载前清理抽象路径会话失败", it) }
            execLocal.exec(reqId, requestJson, sink)
        }

        override fun shutdown() {
            Log.i(TAG, "收到 shutdown，卸载全部会话并退出 :llm 进程")
            host.closeAll()
            execLocal.evictLocal()
            stopSelf()
            // 退出整个进程才能真正把 native heap 还给系统。
            // 只 stopSelf 的话，进程会作为「已启动的服务」被系统缓存住，
            // 那 30–50MB 常驻开销照样留着 —— 而调这个方法的场景
            // （换模型 / 用户主动释放）恰恰就是要立刻腾内存。
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "onDestroy：释放引擎")
        host.closeAll()
        execLocal.cancelAll()
        super.onDestroy()
    }
}
