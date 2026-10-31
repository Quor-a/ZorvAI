package com.ai.assistance.quro.core.display

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 特权服务里盯着主屏的那一份:Agent 的动作前后一小段时间里,主屏最上面换了哪些任务([MainScreenLog])。
 * App 不是从自己在 后台虚拟屏上的页面、而是从后台(服务、通知的跳转、系统的选择器)打开另一个 App 时,系统把它放到主屏;
 * 宿主一侧据这份记录认出来、挪回去。
 *
 * 任务一变就看一次:向系统登记任务栈的监听(ITaskStackListener,shell 有管理任务的权限),
 * 监听只当「变了」的信号用,不解析参数(各版本的方法顺序不同),收到就重看一遍任务列表;
 * 登记不上时退回在盯着的那段时间里每 [POLL_MS] 看一次。查询时([arrivals])再当场看一次,不漏最后的样子。
 * 不在盯着的时候不看任务列表。
 */
internal class MainScreenWatch(
    private val taskManager: () -> Any,
    /** 此刻所有屏上的任务,按最近使用排。 */
    private val tasks: () -> List<MainScreenLog.Task>,
) {
    private val log = MainScreenLog()
    private val thread = HandlerThread("quro-main-watch").apply { start() }
    private val handler = Handler(thread.looper)
    /** 盯到什么时候(uptimeMillis)。 */
    @Volatile private var until = 0L
    /** 任务栈监听登记上了为 true,登记不上为 false,还没试过为 null。 */
    @Volatile private var listening: Boolean? = null
    private val queued = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    private val signal = StackSignal()

    /** 从现在起盯 [windowMs];返回此刻主屏最上面的任务(没有为 -1)。 */
    fun watch(windowMs: Int): Int {
        val now = SystemClock.uptimeMillis()
        val top = synchronized(log) {
            scanLocked(now, record = now < until)
            until = maxOf(until, now + windowMs.coerceIn(0, MAX_WINDOW_MS))
            log.topTaskId
        }
        if (listening == null) listening = register()
        if (listening == false && polling.compareAndSet(false, true)) handler.post(::poll)
        return top
    }

    /**
     * [since] 之后主屏冒到最上面的任务,先后排;每条另带此刻它在哪块屏(不在了为 -1),和这份记录是靠监听还是轮询得来的。
     * 字段见 [toBundle]。
     */
    fun arrivals(since: Long): Bundle = synchronized(log) {
        val now = SystemClock.uptimeMillis()
        if (now < until + GRACE_MS) scanLocked(now, record = true)
        toBundle(log.since(since), log::displayOf, listening == true)
    }

    fun close() {
        if (listening == true) runCatching {
            taskManager().javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager(), listener())
        }
        listening = null
        thread.quitSafely()
    }

    private fun scanLocked(now: Long, record: Boolean) {
        val list = try { tasks() } catch (t: Throwable) { Log.w(TAG, "cannot list tasks", t); return }
        log.scan(list, now, record)
    }

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        if (now >= until) return
        synchronized(log) { scanLocked(now, record = true) }
    }

    /** 任务栈变了:合并一下再看,一连串变化只看一次。 */
    private fun onSignal() {
        if (SystemClock.uptimeMillis() >= until) return
        if (queued.compareAndSet(false, true)) handler.postDelayed({ queued.set(false); scan() }, COALESCE_MS)
    }

    private fun poll() {
        if (SystemClock.uptimeMillis() >= until) { polling.set(false); return }
        scan()
        handler.postDelayed(::poll, POLL_MS)
    }

    @SuppressLint("PrivateApi")
    private fun listener(): Any =
        Class.forName("android.app.ITaskStackListener\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, signal)!!

    private fun register(): Boolean = try {
        val am = taskManager()
        am.javaClass.methods.first { it.name == "registerTaskStackListener" && it.parameterTypes.size == 1 }.invoke(am, listener())
        Log.i(TAG, "task stack listener registered")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "task stack listener unavailable, polling instead", t)
        false
    }

    /**
     * 系统按 ITaskStackListener 的接口回调过来的 Binder。只认「来了一次回调」,不读参数;
     * 这些回调都是单向的,不用回话。描述符照接口的名字填,系统那边按它包装。
     */
    private inner class StackSignal : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                onSignal()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val TAG = "QuroShellSvc"
        private const val DESCRIPTOR = "android.app.ITaskStackListener"
        const val POLL_MS = 100L
        private const val COALESCE_MS = 30L
        /** 窗口过了之后再查,仍当场看一次:宿主那边判完要一点时间。 */
        private const val GRACE_MS = 2_000L
        private const val MAX_WINDOW_MS = 30_000

        const val KEY_TASK = "task"
        const val KEY_PACKAGE = "package"
        const val KEY_USER = "user"
        const val KEY_AT = "at"
        const val KEY_FROM = "from"
        const val KEY_SAME_TASK = "sameTask"
        const val KEY_STANDARD = "standard"
        const val KEY_ACTIVITY = "activity"
        const val KEY_NOW = "now"
        const val KEY_LISTENING = "listening"

        /** 一组记录打成平行的数组(Bundle 里没有对象列表);[now] 是任务此刻在哪块屏。 */
        fun toBundle(list: List<MainScreenLog.Arrival>, now: (Int) -> Int, listening: Boolean): Bundle = Bundle().apply {
            putIntArray(KEY_TASK, list.map { it.taskId }.toIntArray())
            putStringArray(KEY_PACKAGE, list.map { it.packageName }.toTypedArray())
            putIntArray(KEY_USER, list.map { it.userId }.toIntArray())
            putLongArray(KEY_AT, list.map { it.at }.toLongArray())
            putIntArray(KEY_FROM, list.map { it.from }.toIntArray())
            putBooleanArray(KEY_SAME_TASK, list.map { it.sameTask }.toBooleanArray())
            putBooleanArray(KEY_STANDARD, list.map { it.standard }.toBooleanArray())
            putStringArray(KEY_ACTIVITY, list.map { it.topActivity }.toTypedArray())
            putIntArray(KEY_NOW, list.map { now(it.taskId) }.toIntArray())
            putBoolean(KEY_LISTENING, listening)
        }
    }
}
