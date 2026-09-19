package com.ai.assistance.quro.genui.app.agent

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * 画布枢纽：让 agent 层的工具（page_preview/page_eval/page_errors）够到 UI 层的画布 WebView。
 *
 * 单一全局引用：GenUI 同屏只有一个主画布（A2UI 渲染器包着 WebView）。
 * UI 侧在画布 WebView 创建时注册 [web]、销毁时置空；agent 侧全部经 main 线程访问。
 *
 * [errors] 收集画布 JS 运行时错误（console.error / 未捕获异常），供 AI 自检时用
 * page_errors 读走（drain 语义：读了就清，避免旧错误反复出现）。
 */
object CanvasHub {

    @Volatile
    var web: WebView? = null
        set(value) { field = value?.also { it.tag = "genui-canvas" } }

    private val main = Handler(Looper.getMainLooper())
    private val errors = ArrayDeque<String>()   // 最新在后
    private const val MAX_ERRORS = 40

    /** UI 线程回调里记录 JS 错误（onConsoleMessage / JS 异常） */
    @Synchronized
    fun recordError(msg: String) {
        if (msg.isBlank()) return
        if (errors.size >= MAX_ERRORS) errors.removeFirst()
        errors.addLast(msg.take(500))
    }

    /** 读走全部已积累错误（drain） */
    @Synchronized
    fun drainErrors(): List<String> {
        val out = errors.toList()
        errors.clear()
        return out
    }

    /** 画布内执行 JS（挂起，主线程，3s 超时）。返回 evaluateJavascript 的 JSON 结果文本。 */
    suspend fun evaluate(js: String, timeoutMs: Long = 3000): String {
        val w = web ?: throw IllegalStateException("画布不存在（当前不在画布模式或画布未就绪）")
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                main.post {
                    runCatching {
                        w.evaluateJavascript(js) { result ->
                            if (cont.isActive) cont.resumeWith(Result.success(result ?: "null"))
                        }
                    }.onFailure { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
                }
            }
        } ?: throw IllegalStateException("画布 JS 执行超时（${timeoutMs}ms）")
    }
}
