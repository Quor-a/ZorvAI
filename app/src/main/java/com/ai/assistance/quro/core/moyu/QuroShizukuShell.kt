package com.ai.assistance.quro.core.moyu

import com.ai.assistance.quro.core.shizuku.QuroShizuku
import com.ai.assistance.quro.terminal.moyu.MoyuPrivileged
import com.ai.assistance.quro.terminal.moyu.PrivilegedShell

/**
 * 把 [QuroShizuku] 适配成 terminal-core 需要的 [PrivilegedShell]。
 *
 * 为什么要有这一层：`PrivilegedShell` 定义在 terminal-core（墨语言所在模块），
 * 而 Shizuku 的封装 `QuroShizuku` 在 app 模块。terminal-core **不依赖** Shizuku ——
 * 原生通道是可选增强，缺了就该整体降级，而不是把一条重依赖塞进工具链模块。
 *
 * 所以方向是 app → terminal-core：app 实现接口，注入 [MoyuPrivileged]。
 */
object QuroShizukuShell : PrivilegedShell {

    private const val TAG = "QuroShizukuShell"

    override val isReady: Boolean
        get() = runCatching { QuroShizuku.isReady }.getOrDefault(false)

    override fun exec(command: String): String = QuroShizuku.exec(command)

    override fun execTimed(command: String, timeoutMs: Long): String =
        QuroShizuku.execTimed(command, timeoutMs)

    override fun writeChunk(path: String, data: ByteArray, append: Boolean): Int =
        QuroShizuku.writeChunk(path, data, append)

    override fun fileSize(path: String): Long = QuroShizuku.fileSize(path)

    override fun remove(path: String): Boolean = QuroShizuku.remove(path)

    /** 供 Application 启动时调用：装上就算数，卸载不了（降级即不注入）。 */
    fun install() {
        MoyuPrivileged.shell = this
    }
}
