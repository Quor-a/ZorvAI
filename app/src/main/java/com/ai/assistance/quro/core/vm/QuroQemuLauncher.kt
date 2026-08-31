package com.ai.assistance.quro.core.vm

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File

/**
 * QEMU 启动器（无 AVF 时的 VM 回退）：启动 qemu-system-aarch64（TCG 软件虚拟化），
 * 经 unix socket 串口把 guest 控制台桥接为 [QuroVmConsoleProcess]。
 *
 * 资产要求（置于 assets/vm/ 或由打包步骤注入）：
 *  - qemu-system-aarch64（交叉编译的 Android aarch64 二进制，静态链接最佳）
 *  - Image（Linux kernel）
 *  - disk.qcow2（含完整 Linux 根文件系统，如 Alpine/Ubuntu Cloud 镜像）
 * 缺失任一则 [start] 返回 null，由上层回退 proot。
 */
object QuroQemuLauncher {
    private const val TAG = "QuroQemuLauncher"
    private const val SOCK_NAME = "quro_vm_console.sock"

    fun start(context: Context): QuroVmConsoleProcess? {
        val qemu = File(QuroVmEnv.qemuPath(context))
        if (!qemu.exists()) {
            Log.w(TAG, "QEMU 二进制缺失: ${qemu.absolutePath}")
            return null
        }
        val kernel = File(QuroVmEnv.kernelPath(context))
        if (!kernel.exists()) {
            Log.w(TAG, "kernel 缺失: ${kernel.absolutePath}")
            return null
        }
        val disk = File(QuroVmEnv.diskPath(context))
        if (!disk.exists()) {
            Log.w(TAG, "disk 缺失: ${disk.absolutePath}")
            return null
        }

        val sockFile = File(context.cacheDir, SOCK_NAME)
        runCatching { sockFile.delete() }

        val cmd = listOf(
            qemu.absolutePath,
            "-M", "virt",
            "-cpu", "max",
            "-m", "1024",
            "-kernel", kernel.absolutePath,
            "-append", "console=ttyS0 root=/dev/vda rw quiet",
            "-drive", "file=${disk.absolutePath},format=qcow2,if=virtio",
            "-nographic",
            "-serial", "unix:${sockFile.absolutePath},server,nowait",
            "-display", "none",
            "-no-reboot",
        )
        Log.i(TAG, "启动 QEMU: ${cmd.joinToString(" ")}")
        val qemuProc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.e(TAG, "QEMU 启动失败: ${e.message}")
            diag(context, "QEMU 启动失败: ${e.message}")
            return null
        }

        // 等待 QEMU 监听 unix socket（最多 8s）
        val deadline = System.currentTimeMillis() + 8000
        while (!sockFile.exists() && System.currentTimeMillis() < deadline) {
            if (!qemuProc.isAlive) {
                Log.e(TAG, "QEMU 进程启动即退出（检查 kernel/disk 是否匹配）")
                diag(context, "QEMU 进程启动即退出")
                return null
            }
            Thread.sleep(200)
        }
        if (!sockFile.exists()) {
            Log.e(TAG, "等待 QEMU 控制台 socket 超时")
            diag(context, "等待 QEMU 控制台 socket 超时")
            runCatching { qemuProc.destroyForcibly() }
            return null
        }

        return try {
            val sock = LocalSocket()
            sock.connect(LocalSocketAddress(sockFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            Log.i(TAG, "✅ QEMU 控制台已连接 (unix socket)")
            QuroVmConsoleProcess(sock.inputStream, sock.outputStream) {
                runCatching { sock.close() }
                runCatching { qemuProc.destroyForcibly() }
                runCatching { sockFile.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接 QEMU 控制台失败: ${e.message}")
            diag(context, "连接 QEMU 控制台失败: ${e.message}")
            runCatching { qemuProc.destroyForcibly() }
            null
        }
    }

    /** 诊断日志写到 VM 资产目录（真机可用文件管理器取走，无需 adb）。 */
    private fun diag(context: Context, msg: String) {
        Log.e(TAG, msg)
        runCatching {
            File(QuroVmEnv.vmDir(context), "quro_qemu_diag.log")
                .appendText("[${System.currentTimeMillis()}] $msg\n")
        }
    }
}
