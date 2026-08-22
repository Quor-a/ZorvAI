package com.ai.assistance.quro.browser.consolekit

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 手动控制台面板：把通用 [AciConsoleContract] 接到 UI。
 *
 * 可复用性：换一个契约实现（[LocalConsoleEndpoint] 同进程 / [RemoteConsoleEndpoint] 跨进程）
 * 即可驱动不同的受控 App，本类与任何业务逻辑无关。
 *
 * 线程安全：所有快照拉取与 action 执行都在单线程池（非 UI 线程）进行，
 * 因为后端可能同步等待 WebView 主线程（post + CountDownLatch），主线程调用会死锁。
 */
class ManualConsolePanel(
    private val container: ViewGroup,
    private val outputLog: TextView?,
    private val endpoint: AciConsoleContract
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** 拉取并渲染最新快照（例如面板首次展开时调用）。 */
    fun refresh() {
        worker.execute {
            val snap = runCatching { endpoint.getSnapshot() }
                .getOrDefault(JSONObject().put("error", "拉取快照失败"))
            ui.post { AciConsoleRenderer.render(container, snap, ::dispatch) }
        }
    }

    /** 执行一个 action，并把结果写进日志、随后刷新快照。 */
    fun dispatch(action: String, payload: Map<String, String>) {
        worker.execute {
            val res = runCatching { endpoint.sendAction(action, payload) }
                .getOrDefault(JSONObject().put("error", "执行失败"))
            val summary = buildSummary(res)
            ui.post {
                appendLog("• $action → $summary")
                refresh()
            }
        }
    }

    fun destroy() {
        runCatching { worker.shutdownNow() }
    }

    private fun buildSummary(res: JSONObject): String {
        if (res.has("error")) return res.optString("error")
        val action = res.optString("action").let { if (it.isNotEmpty()) "$it " else "" }
        val msg = res.optString("message").let { if (it.isNotEmpty()) "· $it" else "" }
        val ok = if (res.has("ok")) "ok=${res.optBoolean("ok")} " else ""
        return "$action$ok${msg}".trim().ifEmpty { "done" }
    }

    private fun appendLog(s: String) {
        outputLog?.append("\n$s")
    }
}
