package com.ai.assistance.quro.core

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.ai.assistance.quro.core.hint.ExternalHint
import com.ai.assistance.quro.core.hint.ExternalHintDispatcher
import com.ai.assistance.quro.core.hint.ExternalHintPrefs
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 目录投递通道：监听 `quro_inbox/`，把外部写入的文件交给统一分发器。
 *
 * 文件既可以是 `{"id","ts","source","text","priority"}` 的 JSON，也可以直接是纯文本。
 * 只在文件写完并稳定后读取；解析出的 id 用于去重，AI 处理完一轮后由分发器把文件
 * 归档到 `processed/`，避免重复提醒与目录堆积。
 *
 * 与 1.5 秒轮询并用是为了兼容部分 ROM 上 FileObserver 丢失 CLOSE_WRITE 的情况。
 */
object QuroInboxWatcher {
    private const val TAG = "QuroInboxWatcher"
    private const val STABLE_MS = 800L
    private const val POLL_MS = 1500L
    private const val MAX_BYTES = 64 * 1024

    private val running = AtomicBoolean(false)
    private var observer: FileObserver? = null
    private var dir: File? = null
    private var poller: Thread? = null
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val fingerprints = LinkedHashSet<String>()

    fun start(context: Context) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        if (!ExternalHintPrefs.enabled(app)) {
            running.set(false)
            Log.i(TAG, "外部提示未启用，目录监听跳过")
            return
        }
        val d = ExternalHintPrefs.inboxDir(app)
        dir = d
        runCatching { d.mkdirs() }
        lock.withLock { fingerprints.clear() }
        startObserver()
        startPoller(app)
        Log.i(TAG, "目录监听已启动：${d.absolutePath}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { observer?.stopWatching() }
        observer = null
        lock.withLock { cond.signalAll() }
        poller = null
        Log.i(TAG, "目录监听已停止")
    }

    fun inboxPath(context: Context): String = ExternalHintPrefs.inboxDir(context).absolutePath

    /** 归档一个已处理 / 已丢弃的文件。 */
    fun archive(file: File) {
        if (!file.exists()) return
        val processed = File(file.parentFile, "processed")
        runCatching { processed.mkdirs() }
        val target = File(processed, file.name)
        runCatching {
            if (target.exists()) target.delete()
            if (!file.renameTo(target)) file.delete()
        }
    }

    private fun startObserver() {
        val path = dir?.absolutePath ?: return
        runCatching {
            observer = object : FileObserver(path, FileObserver.ALL_EVENTS) {
                override fun onEvent(event: Int, child: String?) {
                    if (child == null) return
                    lock.withLock { cond.signalAll() }
                }
            }
            observer?.startWatching()
        }.onFailure { Log.e(TAG, "FileObserver 启动失败，降级为纯轮询：${it.message}") }
    }

    private fun startPoller(app: Context) {
        poller = Thread {
            while (running.get()) {
                lock.withLock {
                    try {
                        cond.await(POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                    }
                }
                if (!running.get()) break
                runCatching { scan(app) }
            }
        }.also {
            it.isDaemon = true
            it.name = "quro-hint-inbox"
            it.start()
        }
    }

    private fun scan(app: Context) {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val files = runCatching {
            d.listFiles()?.filter { it.isFile && !it.name.endsWith(".ack") }
        }.getOrNull() ?: return
        for (f in files) {
            if (now - f.lastModified() < STABLE_MS) continue
            val size = f.length()
            val fp = "${f.absolutePath}:$size:${f.lastModified()}"
            if (lock.withLock { fp in fingerprints }) continue
            mark(fp)
            if (size > MAX_BYTES) {
                archive(f)
                continue
            }
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text.isBlank()) {
                archive(f)
                continue
            }
            ExternalHintDispatcher.submit(app, parse(f, text))
        }
    }

    private fun parse(file: File, raw: String): ExternalHint {
        val trimmed = raw.trim()
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        if (json != null) {
            val text = json.optString("text", "").ifBlank { json.optString("hint", "") }
            return ExternalHint(
                id = json.optString("id", "").ifBlank { stableId(file) },
                text = text,
                source = json.optString("source", "").ifBlank { "file" },
                priority = json.optInt("priority", 0),
                ts = json.optLong("ts", file.lastModified()),
                originFile = file,
            )
        }
        return ExternalHint(
            id = stableId(file),
            text = trimmed,
            source = "file",
            ts = file.lastModified(),
            originFile = file,
        )
    }

    private fun stableId(file: File): String =
        "file-${file.name}-${file.length()}-${file.lastModified()}"

    private fun mark(fp: String) {
        lock.withLock {
            fingerprints.add(fp)
            while (fingerprints.size > 2000) {
                val it = fingerprints.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }
}
