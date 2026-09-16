package com.zorv.plugin.zorvweb

/**
 * 引擎侧的各类环形缓冲 —— 从 ZorvBrowser 的 BrowserCore 迁来，去掉 ACI 审计缓冲（那是受控端审计用的）。
 *
 * 全部线程安全：WebView 回调在 UI 线程、工具调用在宿主协程线程，读写会并发。
 * 每类都带容量上限，避免长时间运行把内存吃满。
 */
internal object Buffers {

    // ── 页面 console.* 输出（WebChromeClient.onConsoleMessage 钩取）──
    data class ConsoleEntry(val level: String, val text: String, val source: String, val line: Int, val time: Long)

    object Console {
        private val list = mutableListOf<ConsoleEntry>()
        private val lock = Any()
        const val MAX = 1000

        fun add(level: String, text: String, source: String, line: Int) {
            synchronized(lock) {
                list.add(ConsoleEntry(level, text, source, line, System.currentTimeMillis()))
                while (list.size > MAX) list.removeAt(0)
            }
        }

        fun snapshot(limit: Int, filter: String): List<ConsoleEntry> = synchronized(lock) {
            val src = if (filter.isEmpty()) list else list.filter {
                it.text.contains(filter, true) || it.level.contains(filter, true) || it.source.contains(filter, true)
            }
            src.takeLast(limit)
        }

        fun clear() = synchronized(lock) { list.clear() }
        fun size(): Int = synchronized(lock) { list.size }
    }

    // ── 请求抓包（WebViewClient.shouldInterceptRequest，后台线程回调）──
    data class CapturedRequest(
        val url: String, val method: String, val headers: String,
        val isMainFrame: Boolean, val time: Long,
    )

    object Capture {
        private val list = mutableListOf<CapturedRequest>()
        private val lock = Any()
        const val MAX = 800
        @Volatile var enabled = true

        fun add(r: CapturedRequest) {
            if (!enabled) return
            synchronized(lock) {
                list.add(r)
                while (list.size > MAX) list.removeAt(0)
            }
        }

        fun snapshot(limit: Int, filter: String): List<CapturedRequest> = synchronized(lock) {
            val src = if (filter.isEmpty()) list else list.filter {
                it.url.contains(filter, true) || it.method.contains(filter, true) || it.headers.contains(filter, true)
            }
            src.takeLast(limit)
        }

        fun clear() = synchronized(lock) { list.clear() }
        fun size(): Int = synchronized(lock) { list.size }
    }

    // ── 页面事件流（onPageStarted / onPageFinished / onReceivedError）──
    data class PageEvent(val type: String, val url: String, val time: Long)

    object Events {
        private val list = mutableListOf<PageEvent>()
        private val lock = Any()
        const val MAX = 500

        fun add(type: String, url: String) {
            synchronized(lock) {
                list.add(PageEvent(type, url, System.currentTimeMillis()))
                while (list.size > MAX) list.removeAt(0)
            }
        }

        fun snapshot(limit: Int): List<PageEvent> = synchronized(lock) { list.takeLast(limit) }
        fun clear() = synchronized(lock) { list.clear() }
    }

    // ── 页面快照库（标签覆盖式，容量 20）──
    data class PageSnapshot(val id: String, val url: String, val title: String, val html: String, val time: Long)

    object Snapshots {
        private val map = LinkedHashMap<String, PageSnapshot>()
        private val lock = Any()
        const val MAX = 20

        fun save(label: String, url: String, title: String, html: String): String {
            val id = label.ifEmpty { "snap_${System.currentTimeMillis()}" }
            synchronized(lock) {
                map[id] = PageSnapshot(id, url, title, html, System.currentTimeMillis())
                while (map.size > MAX) map.remove(map.keys.first())
            }
            return id
        }

        fun list(): List<PageSnapshot> = synchronized(lock) { map.values.toList() }
        fun get(id: String): PageSnapshot? = synchronized(lock) { map[id] }
        fun clear() = synchronized(lock) { map.clear() }
    }
}
