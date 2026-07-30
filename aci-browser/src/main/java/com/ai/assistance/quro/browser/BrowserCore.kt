package com.ai.assistance.quro.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * 浏览器内核（v3 · 简化版）。
 *
 * 职责：
 * 1. 持有 Activity 传来的显示 WebView 引用（供 ACI readHtml / getUrl / getTitle 使用）。
 * 2. 后台 loadUrl 操作（经主线程 Handler 切到 UI 线程）。
 *
 * 不再自己创建/管理 WebView 实例 —— 创建和生命周期全交给 BrowserActivity（XML 布局）。
 * 这消除了 v1/v2 中「applicationContext WebView → Activity 不渲染」的根因。
 */
object BrowserCore {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var displayWv: WebView? = null   // Activity 的显示 WebView
    @Volatile private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** BrowserActivity 把 XML 里的 WebView 注册过来，供 ACI 调用读取。 */
    @Synchronized
    fun registerDisplayWebView(wv: WebView) {
        displayWv = wv
    }

    /** Activity 销毁时注销。 */
    @Synchronized
    fun unregisterDisplayWebView() {
        displayWv = null
    }

    /** 获取当前显示 WebView（可能为 null，如果 Activity 未创建/已销毁）。 */
    fun getWebView(): WebView? = displayWv

    // ── ACI 调用入口 ──

    fun loadUrl(url: String) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            displayWv?.loadUrl(url)
            latch.countDown()
        }
        try { latch.await() } catch (_: InterruptedException) {}
    }

    fun getUrl(): String? {
        val ref = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        mainHandler.post { ref.set(displayWv?.url); latch.countDown() }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get()
    }

    fun getTitle(): String? {
        val ref = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        mainHandler.post { ref.set(displayWv?.title); latch.countDown() }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get()
    }

    /** 读取当前页面完整 HTML。 */
    fun readHtml(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) {
                latch.countDown()
            } else {
                wv.evaluateJavascript("document.documentElement.outerHTML") { html ->
                    ref.set(html ?: "")
                    latch.countDown()
                }
            }
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }
}
