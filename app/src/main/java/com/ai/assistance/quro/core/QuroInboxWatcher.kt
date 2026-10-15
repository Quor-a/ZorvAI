package com.ai.assistance.quro.core

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.assistance.quro.activity.QuroApplication
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.workflow.platform.Device
import java.io.File
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 外部事件唤醒 / 前置钩子（issue #13）。
 *
 * 监听约定目录 `quro_inbox/`（默认 `<filesDir>/quro_inbox`，可在 `quro_ui`
 * 偏好里改成 `getExternalFilesDir(null)/quro_inbox` 让 PC/脚本易写）。
 * 其他 App / 脚本 / 定时任务往里扔一个文本文件，即被当作「外部事件」——
 * 注入当前对话为一条**系统提醒**（非伪造用户消息），并弹出通知唤醒用户。
 *
 * 防护：
 *  - **去重**：按「路径+大小+修改时间」指纹记录已处理文件，不重复注入；
 *  - **静音**：`quro_inbox_muted=true` 时只记指纹、不注入（不打扰用户）；
 *  - **限流**：每分钟最多 [RATE_LIMIT] 个事件，超出丢弃（防目录被刷爆）。
 *
 * 由 [com.ai.assistance.quro.activity.QuroApplication.onCreate] 在**主进程**启动；
 * 进程被杀后停止（外部事件本就只在 App 存活时有意义）。
 */
object QuroInboxWatcher {

    private const val TAG = "QuroInboxWatcher"
    private const val PREFS = "quro_ui"
    private const val RATE_LIMIT = 60          // 每分钟最多处理事件数
    private const val STABLE_MS = 1000         // 文件需「修改时间 ≥1s 前」才读，避免读到写到一半的内容
    private const val POLL_MS = 1500           // 轮询间隔（FileObserver 唤醒后也会立即扫）

    private val running = AtomicBoolean(false)
    private var observer: FileObserver? = null
    private var dir: File? = null
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var poller: Thread? = null
    private val processed = LinkedHashSet<String>()   // 已处理指纹（路径:大小:mtime）
    private var rateWindowStart = 0L
    private var rateCount = 0

    /** 启动监听。enabled=false 时不启动。 */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("quro_inbox_enabled", true)) {
            running.set(false)
            Log.i(TAG, "quro_inbox 未启用，跳过启动")
            return
        }
        val custom = prefs.getString("quro_inbox_path", null)
        dir = if (custom.isNullOrBlank()) File(context.filesDir, "quro_inbox")
        else File(custom)
        runCatching { dir!!.mkdirs() }
        lock.withLock { processed.clear() }
        rateWindowStart = System.currentTimeMillis()
        rateCount = 0
        startObserver()
        startPoller()
        Log.i(TAG, "quro_inbox 监听已启动：${dir!!.absolutePath}")
    }

    /** 停止监听（进程退出 / 测试用）。 */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { observer?.stopWatching() }
        observer = null
        lock.withLock { cond.signalAll() }
        poller = null
        Log.i(TAG, "quro_inbox 监听已停止")
    }

    private fun startObserver() {
        runCatching {
            observer = object : FileObserver(dir!!.absolutePath, FileObserver.ALL_EVENTS) {
                override fun onEvent(event: Int, child: String?) {
                    if (child == null) return
                    // 仅唤醒轮询线程；真正的稳定读取由 scan() 负责，避免读到半截内容。
                    lock.withLock { cond.signalAll() }
                }
            }
            observer?.startWatching()
        }.onFailure { Log.e(TAG, "FileObserver 启动失败（降级为纯轮询）：${it.message}") }
    }

    private fun startPoller() {
        poller = Thread {
            while (running.get()) {
                // 没有新事件时安静等待一个轮询周期，有事件则被唤醒立即扫。
                lock.withLock {
                    try { cond.await(POLL_MS.toLong(), TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                }
                if (!running.get()) break
                runCatching { scan() }
            }
        }.also { it.isDaemon = true; it.name = "quro-inbox-poller"; it.start() }
    }

    /** 扫描目录，挑出「已写完且未处理」的文件并注入。 */
    private fun scan() {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val files = runCatching { d.listFiles()?.filter { it.isFile } }.getOrNull() ?: return
        for (f in files) {
            if (now - f.lastModified() < STABLE_MS) continue   // 还在写，下轮再扫
            val size = f.length()
            val fp = "${f.absolutePath}:$size:${f.lastModified()}"
            val already = lock.withLock { fp in processed }
            if (already) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text.isBlank()) { lock.withLock { processed.add(fp) }; continue }
            if (!allowEvent()) { lock.withLock { processed.add(fp) }; continue }  // 限流：记指纹后丢弃
            val ctx = QuroApplication.appCtx ?: continue
            val muted = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("quro_inbox_muted", false)
            if (muted) {
                lock.withLock { processed.add(fp) }
                Log.i(TAG, "quro_inbox 静音中，跳过事件：${f.name}")
                continue
            }
            injectReminder(ctx, f.name, text)
            lock.withLock { processed.add(fp) }
        }
    }

    /** 每分钟滑动窗口限流。 */
    private fun allowEvent(): Boolean {
        val now = System.currentTimeMillis()
        if (now - rateWindowStart > 60_000) { rateWindowStart = now; rateCount = 0 }
        if (rateCount >= RATE_LIMIT) return false
        rateCount++
        return true
    }

    /** 注入系统提醒到当前对话 + 发通知唤醒。 */
    private fun injectReminder(context: Context, name: String, text: String) {
        val reminder = "📥 外部事件提醒（来源文件：$name）\n$text"
        runCatching {
            val vm = QuroChatViewModel.instance
            Handler(Looper.getMainLooper()).post { vm.injectExternalReminder(reminder) }
        }.onFailure { Log.w(TAG, "注入系统提醒失败（ViewModel 未就绪）：${it.message}") }
        QuroAgentTrace.status("inbox", "📥 外部事件", "已从 quro_inbox/$name 注入系统提醒到对话")
        runCatching { Device.notify(context, "收到外部事件：$name", text.take(120)) }
    }
}
