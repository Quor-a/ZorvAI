package com.ai.assistance.quro.genui.app.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/**
 * MoTasks —— AI 自写后端扩展的常驻任务管道。
 *
 * 页面通过 MoBridge.task.schedule(id, intervalMin, code) 注册 JS 任务：
 *  - 任务（含代码体）持久化在 filesDir/gen/mo_tasks.json；
 *  - 画布 WebView 就绪期间，由主线程 Handler 按 interval 轮询触发，code 在画布里 evaluate；
 *  - 页面重开（渲染器重新 attach）时，过期任务**补跑一次**——离线缓存刷新类任务的兜底；
 *  - 进程被杀后任务定义仍在，但不会凭空拉活进程（如实限制，不假装后台永生）。
 *
 * 设计边界（为什么这样做）：
 *  - 任务代码与页面同信任级（都是 AI 写的、都在 WebView 沙箱里跑），不新增攻击面；
 *  - 上限 20 个任务、最短间隔 1 分钟——防死循环刷屏；
 *  - WeakReference 持 WebView：纸销毁不阻塞 GC，触发前查活。
 */
object MoTasks {

    private const val MAX_TASKS = 20
    private const val MIN_INTERVAL_MIN = 1L
    private const val TICK_MS = 30_000L

    class Task(
        val id: String,
        val intervalMin: Long,
        val code: String,
        var lastRun: Long
    )

    private var fileRef: File? = null
    private val tasks = LinkedHashMap<String, Task>()
    private var webViewRef: WeakReference<WebView>? = null
    private var handler: Handler? = null
    private var tickRunnable: Runnable? = null
    private val firedBatches = mutableSetOf<String>() // 本 tick 已触发去重

    @Synchronized
    fun attach(context: Context, webView: WebView) {
        fileRef = File(File(context.filesDir, "gen").apply { mkdirs() }, "mo_tasks.json")
        load()
        webViewRef = WeakReference(webView)
        ensureTicking()
        catchUp()
    }

    @Synchronized
    fun detach(webView: WebView) {
        if (webViewRef?.get() === webView) webViewRef = null
    }

    @Synchronized
    fun list(): JSONArray {
        val arr = JSONArray()
        tasks.values.forEach { t ->
            arr.put(JSONObject()
                .put("id", t.id)
                .put("interval_min", t.intervalMin)
                .put("last_run", t.lastRun)
                .put("code_chars", t.code.length))
        }
        return arr
    }

    @Synchronized
    fun schedule(id: String, intervalMin: Long, code: String): JSONObject {
        require(id.isNotBlank()) { "任务 id 不能为空" }
        require(code.isNotBlank()) { "任务代码不能为空" }
        val interval = maxOf(intervalMin, MIN_INTERVAL_MIN)
        require(tasks.containsKey(id) || tasks.size < MAX_TASKS) { "任务数已达上限 $MAX_TASKS，先 cancel 旧的" }
        tasks[id] = Task(id, interval, code, 0L)
        save()
        return JSONObject().put("ok", true).put("id", id)
            .put("interval_min", interval)
            .put("note", "画布就绪期间每 ${interval} 分钟触发；页面重开时过期任务补跑一次")
    }

    @Synchronized
    fun cancel(id: String): JSONObject {
        val gone = tasks.remove(id) != null
        save()
        return JSONObject().put("ok", gone).put("removed", gone)
    }

    /** 页面（重新）就绪：过期任务各补跑一次 */
    private fun catchUp() {
        val wv = webViewRef?.get() ?: return
        val now = System.currentTimeMillis()
        val due = synchronized(this) {
            tasks.values.filter { now - it.lastRun >= it.intervalMin * 60_000L }.map { it.id }
        }
        due.forEach { id -> fire(id, "catchup") }
    }

    private fun ensureTicking() {
        if (handler != null) return
        handler = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                tick()
                handler?.postDelayed(this, TICK_MS)
            }
        }
        tickRunnable = r
        handler?.postDelayed(r, TICK_MS)
    }

    private fun tick() {
        val wv = webViewRef?.get() ?: return
        val now = System.currentTimeMillis()
        val due = synchronized(this) {
            tasks.values.filter { now - it.lastRun >= it.intervalMin * 60_000L }.map { it.id }
        }
        due.forEach { id -> fire(id, "tick") }
    }

    private fun fire(id: String, why: String) {
        val wv = webViewRef?.get() ?: return
        val task = synchronized(this) {
            val t = tasks[id] ?: return
            val now = System.currentTimeMillis()
            if (now - t.lastRun < t.intervalMin * 60_000L) return  // 双保险去重
            t.lastRun = now
            save()
            t
        }
        wv.post {
            runCatching {
                wv.evaluateJavascript(
                    "try{(function(){try{$task.code}catch(e){console.warn('MoTasks[$id]','+e.message)}})()}catch(e){}",
                    null
                )
            }
        }
    }

    private fun load() {
        val f = fileRef ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            tasks.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                tasks[o.optString("id")] = Task(
                    o.optString("id"),
                    o.optLong("intervalMin", 1L).coerceAtLeast(MIN_INTERVAL_MIN),
                    o.optString("code"),
                    o.optLong("lastRun", 0L)
                )
            }
        }
    }

    @Synchronized
    private fun save() {
        val f = fileRef ?: return
        runCatching {
            val arr = JSONArray()
            tasks.values.forEach { t ->
                arr.put(JSONObject()
                    .put("id", t.id)
                    .put("intervalMin", t.intervalMin)
                    .put("code", t.code)
                    .put("lastRun", t.lastRun))
            }
            f.writeText(arr.toString())
        }
    }
}
