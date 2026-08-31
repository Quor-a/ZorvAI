package com.ai.assistance.quro.core.vm

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把 VM 串口控制台（AVF vsock / QEMU unix socket）包装成 [Process]，
 * 使 [com.ai.assistance.quro.core.terminal.QuroShellSession] 复用既有
 * 「常驻进程 + 行缓冲」逻辑而无需改动。
 *
 * VM 内是**真 TTY**：回显、信号（如 SIGINT）、提示符全部由 guest 内核/shell 完成，
 * 因此本包装层只负责转发字节流与生命周期（销毁 = 关 socket + 停 VM）。
 */
class QuroVmConsoleProcess(
    private val consoleIn: InputStream,
    private val consoleOut: OutputStream,
    private val onDestroy: () -> Unit,
) : Process() {

    private val exitLatch = CountDownLatch(1)
    @Volatile
    private var exited = false
    @Volatile
    private var code = 0

    override fun destroy() {
        destroyReal(0)
    }

    private fun destroyReal(c: Int) {
        if (exited) return
        runCatching { consoleOut.close() }
        runCatching { consoleIn.close() }
        runCatching { onDestroy() }
        exited = true
        code = c
        exitLatch.countDown()
    }

    override fun exitValue(): Int =
        if (exited) code else throw IllegalThreadStateException("VM console 仍存活")

    override fun getInputStream(): InputStream = consoleIn

    override fun getOutputStream(): OutputStream = consoleOut

    /** 错误流合并到同一控制台流（VM 控制台 stderr 通常即串行输出）。 */
    override fun getErrorStream(): InputStream = consoleIn

    override fun waitFor(): Int {
        exitLatch.await()
        return code
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
        exitLatch.await(timeout, unit)

    override fun destroyForcibly(): Process {
        destroyReal(0)
        return this
    }

    override fun isAlive(): Boolean = !exited
}
