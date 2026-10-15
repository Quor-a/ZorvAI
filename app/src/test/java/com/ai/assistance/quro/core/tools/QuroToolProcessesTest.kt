package com.ai.assistance.quro.core.tools

import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.OutputStream

/**
 * issue #11 问题 8 回归锁：`QuroToolProcesses` 进程登记与强杀契约。
 *
 * 背景（用户实测）：点「停止生成」后界面恢复输入态，但对话框继续转圈、工具仍在跑。
 * 根因是取消信号没有级联到执行层：工具内部是 `Process.waitFor()` / proot 子进程等
 * 阻塞式代码，协程取消与 `withTimeout` 超时都只能干等阻塞调用自己返回。
 *
 * 本测试逐条钉死：
 *  1. 登记 + killAll：destroy / destroyForcibly 都被调用，Run 进入 killed 态；
 *  2. job 取消（用户停止 / withTimeout 超时同路径）：登记进程被强杀；
 *  3. killAll 之后迟到的 register：当场杀，不放进名单漏杀；
 *  4. endRun 后 register 空转、activeProcessCount 归零；
 *  5. 无活动 Run 时 register/unregister 安全空转（后台初始化路径）；
 *  6. 正常完成（job 未取消）不杀进程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuroToolProcessesTest {

    /** 记录销毁调用的假进程。 */
    private class FakeProcess : Process() {
        var destroyCalled = false
        var forciblyCalled = false
        var exitCode = 0
        override fun destroy() { destroyCalled = true }
        override fun destroyForcibly(): Process { forciblyCalled = true; return this }
        override fun exitValue(): Int = exitCode
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): java.io.InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
    }

    @Before
    fun setUp() {
        QuroToolProcesses.clearThreadLocal()
        QuroToolProcesses.killAllActive() // 清掉可能残留的在册 Run
    }

    @After
    fun tearDown() {
        QuroToolProcesses.killAllActive()
        QuroToolProcesses.clearThreadLocal()
    }

    @Test
    fun register_killAll_destroysProcesses() {
        val run = QuroToolProcesses.beginRun(null)
        val p1 = FakeProcess()
        val p2 = FakeProcess()
        QuroToolProcesses.register(p1)
        QuroToolProcesses.register(p2)
        assertEquals(2, QuroToolProcesses.activeProcessCount())

        run.killAll()

        assertTrue(p1.destroyCalled)
        assertTrue(p1.forciblyCalled)
        assertTrue(p2.destroyCalled)
        assertTrue(p2.forciblyCalled)
        assertTrue(run.killed)
        // 杀完后名单清空，不持死句柄
        assertEquals(0, QuroToolProcesses.activeProcessCount())
        QuroToolProcesses.endRun(run)
    }

    @Test
    fun killAll_isIdempotent() {
        val run = QuroToolProcesses.beginRun(null)
        val p = FakeProcess()
        QuroToolProcesses.register(p)
        run.killAll()
        val destroyCountAfterFirst = p.destroyCalled
        run.killAll() // 第二次不应再触发销毁（幂等）
        assertTrue(p.destroyCalled == destroyCountAfterFirst)
        QuroToolProcesses.endRun(run)
    }

    @Test
    fun registerAfterKill_immediatelyDestroyed() {
        val run = QuroToolProcesses.beginRun(null)
        run.killAll()
        val late = FakeProcess()
        QuroToolProcesses.register(late) // 来晚的进程：窄窗漏杀防护
        assertTrue(late.destroyCalled)
        assertTrue(late.forciblyCalled)
        assertEquals(0, QuroToolProcesses.activeProcessCount())
        QuroToolProcesses.endRun(run)
    }

    @Test
    fun jobCancel_killsRegisteredProcesses() {
        val job = Job()
        val run = QuroToolProcesses.beginRun(job)
        val p = FakeProcess()
        QuroToolProcesses.register(p)
        assertEquals(1, QuroToolProcesses.activeProcessCount())

        // 用户点「停止生成」→ job 取消 → invokeOnCompletion 在取消方线程强杀
        job.cancel()

        // invokeOnCompletion 可能异步派发，轮询等它落地
        val deadline = System.currentTimeMillis() + 2000
        while (!p.forciblyCalled && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("job 取消后登记进程必须被强杀", p.forciblyCalled)
        assertTrue(p.destroyCalled)
        assertTrue(run.killed)
        assertEquals(0, QuroToolProcesses.activeProcessCount())
    }

    @Test
    fun normalCompletion_doesNotKill() {
        val job = Job()
        QuroToolProcesses.beginRun(job)
        val p = FakeProcess()
        QuroToolProcesses.register(p)

        job.complete() // 正常完成（非取消）
        val deadline = System.currentTimeMillis() + 1000
        while (QuroToolProcesses.activeProcessCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertFalse("正常完成不得杀进程", p.destroyCalled)
        assertFalse(p.forciblyCalled)
        // 正常完成后 Run 已摘除（invokeOnCompletion 的收尾分支）
        assertEquals(0, QuroToolProcesses.activeProcessCount())
    }

    @Test
    fun endRun_stopsRegistration() {
        val run = QuroToolProcesses.beginRun(null)
        QuroToolProcesses.endRun(run)
        val p = FakeProcess()
        QuroToolProcesses.register(p) // endRun 之后：无活动 Run，安全空转
        assertFalse(p.destroyCalled)
        assertEquals(0, QuroToolProcesses.activeProcessCount())
    }

    @Test
    fun registerWithoutRun_isNoop() {
        // 后台初始化 / 非工具路径 spawn 的进程不应被任何 Run 持有
        val p = FakeProcess()
        QuroToolProcesses.register(p)
        QuroToolProcesses.unregister(p)
        assertEquals(0, QuroToolProcesses.activeProcessCount())
        assertFalse(p.destroyCalled)
    }

    @Test
    fun killAllActive_killsEveryRun() {
        val jobA = Job()
        val jobB = Job()
        QuroToolProcesses.beginRun(jobA)
        QuroToolProcesses.beginRun(jobB)
        val pa = FakeProcess()
        val pb = FakeProcess()
        QuroToolProcesses.register(pa)
        QuroToolProcesses.register(pb)
        assertEquals(2, QuroToolProcesses.activeProcessCount())

        QuroToolProcesses.killAllActive() // ViewModel onCleared 全局清杀

        assertTrue(pa.forciblyCalled)
        assertTrue(pb.forciblyCalled)
        assertEquals(0, QuroToolProcesses.activeProcessCount())
    }

    @Test
    fun cancelledJobAtBeginRun_killedImmediately() {
        val job = Job()
        job.cancel()
        // 拿着已取消的 job 建 Run：invokeOnCompletion 对已完成 job 立即触发
        val run = QuroToolProcesses.beginRun(job)
        assertTrue("已取消的 job 建 Run 时必须立即进入 killed 态", run.killed)
        val p = FakeProcess()
        QuroToolProcesses.register(p) // Run 已死且已摘除 ThreadLocal：安全空转，不崩
        assertFalse(p.destroyCalled)
        QuroToolProcesses.endRun(run)
    }

    @Test
    fun killedRunStillCurrent_registerImmediatelyKills() {
        // 窄窗防护的真实路径：job 在【工具线程】之外的线程被取消 →
        // handler 在取消方线程 killAll（工具线程的 ThreadLocal 仍指向这个已 killed 的 Run）→
        // 工具线程此时再 spawn 的进程必须当场杀，不能放进名单漏杀。
        val run = QuroToolProcesses.beginRun(null)
        run.killAll() // 等价于「取消方线程已强杀」，但工具线程的 current 仍指向 run
        val p = FakeProcess()
        QuroToolProcesses.register(p)
        assertTrue(p.destroyCalled)
        assertTrue(p.forciblyCalled)
        assertEquals(0, QuroToolProcesses.activeProcessCount())
        QuroToolProcesses.endRun(run)
    }
}
