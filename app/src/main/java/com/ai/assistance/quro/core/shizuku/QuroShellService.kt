package com.ai.assistance.quro.core.shizuku

import android.util.Log
import com.ai.assistance.quro.IQuroShellService
import java.io.BufferedReader
import java.io.InputStreamReader

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
}
