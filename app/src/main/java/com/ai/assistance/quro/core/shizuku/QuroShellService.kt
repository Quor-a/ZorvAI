package com.ai.assistance.quro.core.shizuku

import android.util.Log
import com.ai.assistance.quro.IQuroShellService
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService 实现（AIDL Stub）。
 *
 * 此类实例运行在 **Shizuku 特权进程**中（UID 0=root 或 2000=shell），
 * 因此 [exec] 中的 Runtime.exec() 以特权身份执行，等效于 ADB shell。
 *
 * 生命周期：
 *   - 由 [QuroShizuku] 通过 Shizuku.bindUserService() 绑定
 *   - Shizuku 调用 destroy() 时进程退出
 *   - Shizuku 服务断开时自动解绑
 *
 * ShellService 架构：通过 AIDL UserService 在 Shizuku 特权进程内执行命令。
 */
class QuroShellService : IQuroShellService.Stub() {

    companion object {
        private const val TAG = "QuroShellSvc"

        /** Binder 单次事务有 ~1MB 上限，留足头部余量。 */
        private const val MAX_CHUNK = 512 * 1024
    }

    override fun destroy() {
        Log.d(TAG, "destroy() called, exiting process")
        kotlin.system.exitProcess(0)
    }

    /**
     * 在 Shizuku 特权进程中执行 shell 命令。
     *
     * @param command 要执行的命令（如 "id"、"pm list packages"、"dumpsys activity top"）
     * @return stdout 内容（非空时）；否则 stderr 内容；均空则 "(无输出)"
     */
    override fun exec(command: String): String {
        return try {
            Log.d(TAG, "exec: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            val body = (out + err).trim()
            Log.d(TAG, "exec result: exit=$exitCode body=${body.take(120)}")
            if (body.isBlank()) "(无输出)" else body
        } catch (e: Exception) {
            Log.e(TAG, "exec failed", e)
            "Error: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 墨语言原生通道：文件落盘 / 查询 / 带超时执行 / 删除
    // ══════════════════════════════════════════════════════════════

    /**
     * 以特权身份把字节写进 path。这是 App 沙箱 → /data/local/tmp 的唯一通道：
     * App 写不进 /data/local/tmp，shell 也读不了 App 私有目录，所以只能由
     * 特权进程代写，调用方分块追加。
     */
    override fun writeChunk(path: String, data: ByteArray?, append: Boolean): Int {
        if (path.isBlank()) return -1
        if (data == null || data.isEmpty()) {
            // 空块：仅在建目录语义下有意义，直接返回当前大小
            return File(path).length().toInt()
        }
        if (data.size > MAX_CHUNK) {
            Log.w(TAG, "writeChunk 块过大 (${data.size})，建议 <= $MAX_CHUNK，仍尝试写入")
        }
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            FileOutputStream(f, append).use { it.write(data) }
            val n = f.length()
            n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "writeChunk 失败: $path", e)
            -1
        }
    }

    override fun fileSize(path: String): Long {
        if (path.isBlank()) return -1L
        return try {
            val f = File(path)
            if (f.isFile) f.length() else -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 带超时执行。原生通道要跑编译和基准测试，超时必须能收回进程，
     * 否则 [exec] 的 waitFor() 会把调用线程永久挂住。
     *
     * 做法：起一个看门狗线程，超时就 destroyForcibly()。注意**不能**只
     * destroyForcibly 而不管孙子进程 —— sh -c 里的 qemu/zig 是子进程，
     * 所以先试 `pkill -f <pattern>` 收尾巴，再强杀。
     */
    override fun execTimed(command: String, timeoutMs: Long): String {
        val budget = if (timeoutMs <= 0) 120_000L else timeoutMs
        return try {
            Log.d(TAG, "execTimed(${budget}ms): $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val finished = process.waitFor(budget, TimeUnit.MILLISECONDS)
            if (!finished) {
                // 收孙子进程：sh -c 起的 qemu / zig 不会随 sh 一起死
                runCatching {
                    Runtime.getRuntime().exec(
                        arrayOf("sh", "-c", "pkill -f 'quro-mo' 2>/dev/null; true")
                    ).waitFor(3, TimeUnit.SECONDS)
                }
                process.destroyForcibly()
                val out = runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                }.getOrDefault("")
                val err = runCatching {
                    BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                }.getOrDefault("")
                val body = (out + err).trim()
                Log.w(TAG, "execTimed 超时 ${budget}ms: $command")
                (if (body.isBlank()) "" else body + "\n") + "exit=timeout(${budget}ms)"
            } else {
                val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val err = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                val body = (out + err).trim()
                Log.d(TAG, "execTimed result: exit=${process.exitValue()} body=${body.take(120)}")
                if (body.isBlank()) "(无输出)" else body
            }
        } catch (e: Exception) {
            Log.e(TAG, "execTimed failed", e)
            "Error: ${e.message}"
        }
    }

    override fun remove(path: String): Boolean {
        if (path.isBlank()) return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "rm -rf '$path'"))
            p.waitFor(30, TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "remove 失败: $path", e)
            false
        }
    }
}
