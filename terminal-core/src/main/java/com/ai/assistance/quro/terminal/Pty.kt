package com.ai.assistance.quro.terminal

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class Pty(
    val process: Process,
    val masterFd: FileDescriptor,
    private val ptyMaster: Int,
    private val childPid: Int
) {
    val stdout: InputStream = FileInputStream(masterFd)
    val stdin: OutputStream = FileOutputStream(masterFd)

    fun waitFor(): Int {
        return process.waitFor()
    }

    /**
     * 获取真实退出码（仅限进程已确认退出后调用）。
     *
     * 底层 waitFor(pid) 是阻塞的 waitpid：进程仍存活时会一直挂起，因此调用方
     * **必须先确认 [process.isAlive] == false**（例如在 readJob 读到 EOF 之后），
     * 且应放在后台线程（Dispatchers.IO）执行。失败返回 null。
     */
    fun tryGetExitCode(): Int? {
        return runCatching { Companion.waitFor(childPid) }.getOrNull()
    }

    fun destroy() {
        process.destroy()
        try {
            // It's important to close the master FD to signal EOF to the process
            stdout.close()
            stdin.close()
        } catch (e: IOException) {
            Log.e("Pty", "Error closing PTY streams", e)
        }
    }

    /**
     * 只关闭 PTY 主端读写流，**不向子进程发送任何信号**。
     *
     * 用于「命令已按回执确认完成」的场景：此时若仍走 [destroy]（对 pid 发 SIGHUP），
     * 会误伤该命令在后台派生的守护进程（sshd / Xvnc）。这些守护进程已自行 setsid
     * 或 nohup 脱离，关流不会波及它们。
     */
    fun closeQuietly() {
        try {
            stdout.close()
        } catch (_: Throwable) {
        }
        try {
            stdin.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "Pty"

        init {
            try {
                System.loadLibrary("pty")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libpty.so", e)
                // Handle error appropriately, maybe disable PTY functionality
            }
        }

        @Throws(IOException::class)
        fun start(command: Array<String>, environment: Map<String, String>, workingDir: File): Pty {
            val envArray = environment.map { "${it.key}=${it.value}" }.toTypedArray()

            // This will return an array of two integers: { pid, masterFd }
            val processInfo = createSubprocess(command, envArray, workingDir.absolutePath)
            val pid = processInfo[0]
            val masterFdInt = processInfo[1]

            if (pid <= 0 || masterFdInt <= 0) {
                throw IOException("Failed to create subprocess with PTY. pid=$pid, fd=$masterFdInt")
            }

            val fileDescriptor = Reflect.getFileDescriptor(masterFdInt)
            
            // We need a Process object to manage the subprocess lifetime
            val dummyProcess = object : Process() {
                override fun destroy() {
                    // Send SIGHUP to the process group to ensure all child processes are terminated
                    android.os.Process.sendSignal(pid, 1) // SIGHUP
                }

                override fun exitValue(): Int {
                    // sendSignal(pid, 0) 是「探活」：进程存在则正常返回（不抛异常）。
                    // Process.isAlive() 依赖 exitValue() 抛 IllegalThreadStateException 来判断存活，
                    // 所以这里必须严格遵守该契约。
                    try {
                        android.os.Process.sendSignal(pid, 0)
                        // 进程仍活着 → 抛 IllegalThreadStateException（契约要求）
                        throw IllegalThreadStateException("Process hasn't exited")
                    } catch (e: IllegalThreadStateException) {
                        throw e
                    } catch (e: IllegalArgumentException) {
                        // pid 不存在 = 进程已退出。
                        // 🔴 2026-10-31：旧实现把所有异常都当成「进程已死」并返回 0，
                        // 导致终端错误卡永远显示「exit=0 会话已结束」，掩盖真实的异常退出码。
                        // 这里不能阻塞调用 waitFor（可能在主线程），因此返回 -1 表示
                        // 「已退出但退出码未知」，让 UI 如实展示而不是误导成正常退出。
                        return -1
                    } catch (e: Exception) {
                        // 其他异常（探活本身失败）：保守处理，当作仍存活由调用方继续等待
                        throw IllegalThreadStateException("Process state unknown")
                    }
                }

                override fun getErrorStream(): InputStream? = null
                override fun getInputStream(): InputStream? = null
                override fun getOutputStream(): OutputStream? = null

                override fun waitFor(): Int {
                    return Companion.waitFor(pid)
                }
            }
            
            return Pty(dummyProcess, fileDescriptor, masterFdInt, pid)
        }

        private external fun createSubprocess(cmdArray: Array<String>, envArray: Array<String>, workingDir: String): IntArray

        private external fun waitFor(pid: Int): Int
        
        /**
         * 获取终端标志位
         * bit 0: ICANON - canonical mode (line-buffered input)
         * bit 1: ECHO - echo input characters
         * bit 2: ISIG - generate signals for special characters
         * bit 3: IEXTEN - enable extended input processing
         */
        private external fun getTerminalFlags(fd: Int): Int
        
        /**
         * 获取可读字节数（用于检测是否有输出等待读取）
         */
        private external fun getAvailableBytes(fd: Int): Int
    }
    
    /**
     * 获取 PTY 模式信息
     */
    fun getPtyMode(): PtyMode {
        val flags = Companion.getTerminalFlags(ptyMaster)
        val availableBytes = Companion.getAvailableBytes(ptyMaster)
        
        return PtyMode(
            isCanonicalMode = (flags and 0x01) != 0,
            isEchoEnabled = (flags and 0x02) != 0,
            isSignalEnabled = (flags and 0x04) != 0,
            isExtendedEnabled = (flags and 0x08) != 0,
            availableBytes = availableBytes
        )
    }
    
    /**
     * 设置 PTY 窗口大小
     * @param rows 行数
     * @param cols 列数
     * @return true 表示成功，false 表示失败
     */
    fun setWindowSize(rows: Int, cols: Int): Boolean {
        val result = setPtyWindowSize(ptyMaster, rows, cols)
        if (result == 0) {
            Log.d("Pty", "PTY window size updated to ${rows}x${cols}")
            return true
        } else {
            Log.e("Pty", "Failed to set PTY window size to ${rows}x${cols}")
            return false
        }
    }
    
    private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
}

/**
 * PTY 模式信息
 * 用于检测终端是否处于交互式输入状态
 */
data class PtyMode(
    val isCanonicalMode: Boolean,  // true = 行缓冲模式（正常命令），false = 字符模式（交互式输入）
    val isEchoEnabled: Boolean,     // 是否回显输入
    val isSignalEnabled: Boolean,   // 是否启用信号处理
    val isExtendedEnabled: Boolean, // 是否启用扩展处理
    val availableBytes: Int         // 可读字节数
) {
    /**
     * 判断是否正在等待交互式输入
     * 
     * 两种场景：
     * 1. 非规范模式（Node.js REPL, Python REPL）：禁用 ICANON，字符模式输入
     * 2. 规范模式但等待输入（apt upgrade, sudo）：保持 ICANON，但输出已停止
     */
    fun isWaitingForInput(): Boolean {
        // 场景 1: 非规范模式 = REPL（Node/Python）
        if (!isCanonicalMode && availableBytes == 0) {
            return true
        }
        
        // 场景 2: 规范模式但输出已停止 = 等待确认（apt/sudo）
        // 条件：缓冲区为空（输出已停止）
        if (availableBytes == 0) {
            // 需要由上层结合命令状态判断（是否有命令正在执行）
            return true
        }
        
        return false
    }
}

// Reflection helper to create FileDescriptor from an int fd.
object Reflect {
    fun getFileDescriptor(fd: Int): FileDescriptor {
        val fileDescriptor = FileDescriptor()
        try {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.set(fileDescriptor, fd)
        } catch (e: Exception) {
            throw IOException("Failed to create FileDescriptor from integer fd", e)
        }
        return fileDescriptor
    }
} 