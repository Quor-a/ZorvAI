package com.ai.assistance.quro.core.hint

import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.ai.assistance.quro.core.QuroInboxWatcher
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.workflow.platform.Device
import java.io.File

/**
 * 外部提示的统一入口：目录、广播、ACI 三条通道都汇到这里。
 *
 * 语义约束：
 *  - 提示以「系统提醒」进入当前对话，绝不伪造用户消息；
 *  - 带 id 去重，AI 真正处理完一轮后才归档对应文件；
 *  - 可全局静音、按来源静音、短窗口内多条合并为一条；
 *  - 会话空闲时自动唤醒一轮，生成中则写进本轮活动缓冲，由下一跳请求读到；
 *  - 对话框尚未创建时先落到账本，ViewModel 就绪后补投。
 */
object ExternalHintDispatcher {
    private const val TAG = "ExternalHint"
    private const val BROADCAST_PERMISSION = "ai.aci.permission.SEND_ASSISTANT_DIRECTIVE"
    private const val MAX_QUEUE = 60
    private const val MAX_TEXT = 2000
    private const val MAX_TOTAL = 9000

    private val queueLock = Any()
    private val queue = mutableListOf<ExternalHint>()
    private var flushScheduled = false

    @Volatile private var started = false
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var receiver: ExternalHintReceiver? = null
    private var appContext: Context? = null

    /** 账本是文件 IO：统一挪到后台 HandlerThread，主线程只保留 ViewModel 状态操作。 */
    private fun onWorker(block: () -> Unit) {
        val h = worker
        if (h != null) h.post { runCatching(block) } else runCatching(block)
    }

    /** 应用主进程启动时调用一次。失败不影响主流程。 */
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        appContext = app
        started = true
        runCatching {
            val thread = HandlerThread("quro-hint").also { it.start() }
            workerThread = thread
            worker = Handler(thread.looper)
        }.onFailure { Log.e(TAG, "后台线程启动失败，降级为同步处理", it) }
        registerBroadcast(app)
        QuroInboxWatcher.start(app)
        Log.i(TAG, "外部提示运行时已启动")
    }

    /** 设置页改动目录或总开关后重建目录监听；目录创建与扫描放到后台线程。 */
    fun restart(context: Context) {
        val app = context.applicationContext
        onWorker {
            QuroInboxWatcher.stop()
            QuroInboxWatcher.start(app)
            Log.i(TAG, "外部提示目录监听已重建")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { QuroInboxWatcher.stop() }
        runCatching { worker?.removeCallbacksAndMessages(null) }
        runCatching {
            receiver?.let { r -> appContext?.unregisterReceiver(r) }
        }
        receiver = null
        synchronized(queueLock) {
            queue.clear()
            flushScheduled = false
        }
        runCatching { workerThread?.quitSafely() }
        worker = null
        workerThread = null
    }

    private fun registerBroadcast(app: Context) {
        if (receiver != null) return
        val r = ExternalHintReceiver()
        val filter = IntentFilter(ExternalHintReceiver.ACTION)
        runCatching {
            ContextCompat.registerReceiver(
                app, r, filter,
                BROADCAST_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiver = r
            QuroDiag.log("HINT", "广播通道已注册，发送方需持有 $BROADCAST_PERMISSION")
        }.onFailure {
            Log.e(TAG, "广播通道注册失败（目录与 ACI 通道不受影响）", it)
        }
    }

    /**
     * 投递一条提示。三条通道与设置页的「测试投递」都走这里。
     * @return true 表示已接受并进入合并队列；false 表示被禁用 / 静音 / 重复而丢弃。
     */
    fun submit(context: Context, hint: ExternalHint): Boolean {
        val app = context.applicationContext
        val id = hint.id.ifBlank { "hint-${hint.ts}-${hint.text.hashCode()}" }
        val text = hint.text.trim().take(MAX_TEXT)
        if (text.isEmpty()) return false
        val clean = hint.copy(id = id, text = text)

        if (!ExternalHintPrefs.enabled(app)) {
            discard(app, clean, "disabled")
            return false
        }
        // 静音对重投同样生效：否则用户关掉来源后，未 ack 的旧事件仍会一直把提醒推回来。
        if (ExternalHintPrefs.muted(app) || ExternalHintPrefs.sourceMuted(app, clean.source)) {
            ExternalHintLedger.markSeen(app, listOf(id))
            ExternalHintLedger.note(app, id, clean.source, "muted")
            clean.originFile?.let { runCatching { QuroInboxWatcher.archive(it) } }
            ExternalHintLedger.takeFiles(app, listOf(id)).forEach { path ->
                runCatching { QuroInboxWatcher.archive(File(path)) }
            }
            QuroDiag.log("HINT", "静音丢弃 id=$id source=${clean.source}")
            return false
        }
        if (ExternalHintLedger.isSeen(app, id)) {
            if (ExternalHintLedger.hasPendingFile(app, id)) {
                // 已投递但来源文件仍未归档：AI 没处理完就遇到进程重启 / 目录重扫。
                // 走正常投递，由 ViewModel 按 id 去重（历史里已有这条提醒时只唤醒、不重复出气泡）。
                ExternalHintLedger.note(app, id, clean.source, "retry")
                QuroDiag.log("HINT", "未 ack 事件重投 id=$id")
                deliver(app, buildReminder(listOf(clean)), listOf(id))
                return true
            }
            discard(app, clean, "duplicate")
            return false
        }
        ExternalHintLedger.markSeen(app, listOf(id))
        clean.originFile?.let { ExternalHintLedger.putFile(app, id, it.absolutePath) }

        val size = synchronized(queueLock) {
            queue.add(clean)
            while (queue.size > MAX_QUEUE) queue.removeAt(0)
            queue.size
        }
        ExternalHintLedger.note(app, id, clean.source, "queued")
        QuroDiag.log("HINT", "submit id=$id source=${clean.source} urgent=${clean.urgent} queue=$size")

        val delay = if (clean.urgent) 0L else ExternalHintPrefs.mergeWindowMs(app)
        if (delay <= 0L) flush(app) else scheduleFlush(app, delay)
        return true
    }

    /** 广播接收器在主线程触发，走异步投递，避免账本文件 IO 卡住主线程。 */
    fun submitAsync(context: Context, hint: ExternalHint) {
        val app = context.applicationContext
        onWorker { submit(app, hint) }
    }

    /** 禁用或重复时直接归档文件并清掉待归档映射，避免目录被反复扫描。 */
    private fun discard(app: Context, hint: ExternalHint, state: String) {
        ExternalHintLedger.note(app, hint.id, hint.source, state)
        hint.originFile?.let { runCatching { QuroInboxWatcher.archive(it) } }
        ExternalHintLedger.takeFiles(app, listOf(hint.id)).forEach { path ->
            runCatching { QuroInboxWatcher.archive(File(path)) }
        }
    }

    private fun scheduleFlush(app: Context, delay: Long) {
        val handler = worker
        if (handler == null) {
            flush(app)
            return
        }
        synchronized(queueLock) {
            if (flushScheduled) return
            flushScheduled = true
        }
        handler.postDelayed({
            synchronized(queueLock) { flushScheduled = false }
            flush(app)
        }, delay)
    }

    private fun flush(app: Context) {
        val batch = synchronized(queueLock) {
            val copy = queue.toList()
            queue.clear()
            copy
        }
        if (batch.isEmpty()) return
        deliver(app, buildReminder(batch), batch.map { it.id })
    }

    private fun buildReminder(batch: List<ExternalHint>): String {
        val head = if (batch.size == 1) "【外部提示·事件 ${batch[0].id}】"
        else "【外部提示·共 ${batch.size} 条待处理】"
        val lines = batch.map { h ->
            "· ${h.id}（${h.source}）：${h.text.replace('\n', ' ')}"
        }
        val tail = "以上是外部应用投递的系统提醒，不是用户消息。请在下一步动作中优先处理，必要时调用工具；完成后用简短自然语言回应，不要复述事件编号或本段说明。"
        return (listOf(head) + lines + listOf("", tail)).joinToString("\n").take(MAX_TOTAL)
    }

    private fun deliver(app: Context, text: String, ids: List<String>) {
        val autoWake = ExternalHintPrefs.autoWake(app)
        main.post {
            var injected = false
            val ready = runCatching { QuroChatViewModel.isReadyForExternalHint() }.getOrDefault(false)
            if (ready) {
                injected = runCatching {
                    QuroChatViewModel.instance.injectExternalReminder(text, ids, autoWake)
                    true
                }.getOrElse {
                    QuroDiag.log("HINT", "注入失败转入待投递：${it.message}")
                    false
                }
            }
            if (!injected) {
                // 账本写入是文件 IO，不能压在触发投递的主线程上。
                onWorker {
                    ExternalHintLedger.addPending(
                        app, ExternalHintLedger.Pending(text, ids, System.currentTimeMillis())
                    )
                }
            }
        }
        runCatching { Device.notify(app, "有待处理的外部提示", text.take(120)) }
        runCatching { QuroAgentTrace.status("hint", "外部提示", "已投递 ${ids.size} 条系统提醒") }
        QuroDiag.log("HINT_DELIVER", "ids=${ids.size} autoWake=$autoWake len=${text.length}")
    }

    /** ViewModel 就绪（对话框创建）后补投冷启动期间积攒的提示。 */
    fun onViewModelReady(context: Context) {
        val app = context.applicationContext
        // 先在后台取账本，再把结果切回主线程注入，避免恢复会话时主线程读文件。
        onWorker {
            if (!runCatching { QuroChatViewModel.isReadyForExternalHint() }.getOrDefault(false)) return@onWorker
            val pending = ExternalHintLedger.takePending(app)
            if (pending.isEmpty()) return@onWorker
            val text = pending.joinToString("\n\n") { it.text }.take(MAX_TOTAL)
            val ids = pending.flatMap { it.ids }.distinct()
            val autoWake = ExternalHintPrefs.autoWake(app)
            main.post {
                val ok = runCatching {
                    QuroChatViewModel.instance.injectExternalReminder(text, ids, autoWake)
                    true
                }.getOrDefault(false)
                if (!ok) {
                    // 取走后注入失败：整批写回账本，等下次会话就绪再补投，绝不丢事件。
                    onWorker { pending.forEach { ExternalHintLedger.addPending(app, it) } }
                }
            }
            QuroDiag.log("HINT_PENDING", "delivered=${pending.size} ids=${ids.size}")
        }
    }

    /** AI 处理完一轮后调用：归档来源文件、按 id 差集清理待投递记录。 */
    fun ack(context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val app = context.applicationContext
        // 由生成协程触发，整段账本与文件归档放到后台线程执行。
        onWorker {
            // 会话历史里的提醒每轮都会被扫到，只处理第一次；否则会重复归档与重复计数。
            val fresh = ExternalHintLedger.filterUnacked(app, ids)
            if (fresh.isEmpty()) return@onWorker
            val paths = ExternalHintLedger.takeFiles(app, fresh)
            paths.forEach { path ->
                runCatching { QuroInboxWatcher.archive(File(path)) }
            }
            // 一条待投递记录可能混合多个 id：只摘掉本次处理掉的，保留其余部分，
            // 不能因为「含有一个已处理 id」就把整批未处理事件一起丢掉。
            ExternalHintLedger.dropIdsFromPending(app, fresh.toSet())
            ExternalHintLedger.note(app, "共 ${fresh.size} 条", "ai", "acked")
            QuroDiag.log("HINT_ACK", "ids=${fresh.size} archived=${paths.size}")
        }
    }

    fun queuedCount(): Int = synchronized(queueLock) { queue.size }
}
