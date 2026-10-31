package com.ai.assistance.quro.terminal.runtime

import java.io.File

/**
 * proot 启动规格：供自研 PTY 链路与 vtlib 增强终端（TerminalSession）共用的启动参数。
 */
data class ProotLaunchSpec(
    /** proot 可执行文件路径（libproot.so）。 */
    val prootBin: String,
    /** 完整命令行（prootBin + 参数 + guest shell）。 */
    val command: List<String>,
    /** guest 环境变量。 */
    val env: Map<String, String>,
    /** 工作目录（guest 内绝对路径，如 /root）。 */
    val cwd: String,
    /** 宿主侧工作目录（用于 JNI createSubprocess 的 cwd 参数）。 */
    val hostCwd: File
) {
    /** 供 vtlib TerminalSession 使用的 shellPath（可执行文件）。 */
    val shellPath: String get() = prootBin

    /** 供 vtlib TerminalSession 使用的参数（不含可执行文件本身）。 */
    val args: Array<String> get() = command.drop(1).toTypedArray()
}