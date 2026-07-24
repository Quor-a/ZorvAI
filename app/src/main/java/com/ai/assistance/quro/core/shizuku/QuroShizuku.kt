package com.ai.assistance.quro.core.shizuku

import android.content.Context
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Quro Shizuku 真实集成层（CapOS L2 通道）。
 *
 * 通过 Shizuku 的 Binder IPC 获得与 ADB/Shell 等效的执行能力：
 *   1) [request] 请求 Shizuku 授权（需在 Activity 中调用）
 *   2) [exec] 经 Shizuku 远程进程执行命令（非 Runtime.exec /system/bin/sh）
 *   3) [execAsRoot] 以 root 权限执行（需 Shizuku root 模式）
 *
 * 与 [com.ai.assistance.quro.core.privilege.QuroShizukuBridge] 协同：
 *   Bridge 负责"探测状态"，本类负责"真实执行"。
 */
object QuroShizuku {

    private const val TAG = "QuroShizuku"

    /** Shizuku 是否已就绪（binder 可用、权限已授予）。 */
    val isReady: Boolean get() = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrElse { false }

    /** 在 Activity 中请求 Shizuku 授权。 */
    fun requestPermission(activity: android.app.Activity, requestCode: Int, listener: Shizuku.OnRequestPermissionResultListener?) {
        if (!isInstalled(activity)) {
            Log.w(TAG, "Shizuku 未安装，无法请求权限")
            return
        }
        // Shizuku 13.x：requestPermission 仅接收 requestCode；
        // 结果回调需通过 addRequestPermissionResultListener 预先注册。
        try {
            listener?.let { Shizuku.addRequestPermissionResultListener(it) }
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) {
            // 降级
            Log.w(TAG, "Shizuku API 调用失败，请手动授权")
            try { activity.startActivity(android.content.Intent("moe.shizuku.manager.intent.action.REQUEST_PERMISSION")) }
            catch (_: Exception) { /* 忽略 */ }
        }
    }

    /** Shizuku 应用是否已安装。 */
    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    }.getOrElse { false }

    /**
     * 经 Shizuku 远程进程执行命令。
     *
     * 使用 Shizuku 的 IPC 通道创建远程 Shell 进程，以 adb/Shell UID 执行，
     * 可绕过普通应用权限限制（如 pm install / pm grant 等）。
     */
    fun exec(command: String): String {
        if (!isReady) return "❌ Shizuku 未就绪"
        return try {
            // 通过 Shizuku Binder 获取远程进程
            val process: Process = try {
                // 尝试新 API (Shizuku 13+)
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java,
                    Array<String>::class.java, String::class.java
                ).apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                method.invoke(null, arrayOf("sh"), null, null) as Process
            } catch (_: NoSuchMethodException) {
                // 降级：通过 UserService 执行
                return fallbackExec(command)
            }
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            val body = (out + err).trim()
            "exit=$exitCode\n${if (body.isBlank()) "(无输出)" else body}"
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku exec 失败", e)
            "❌ Shizuku 执行失败: ${e.message}"
        }
    }

    /**
     * 降级执行通道：当 Shizuku IPC 不可用时使用 Runtime.exec(sh)。
     * 能力有限（普通应用 UID），但保证不崩溃。
     */
    internal fun fallbackExec(command: String): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val err = proc.errorStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        val body = (out + err).trim()
        "exit=$code (shell-降级)\n${if (body.isBlank()) "(无输出)" else body}"
    }.getOrElse { "执行失败: ${it.message}" }

    /**
     * 经 Shizuku 以 root 权限执行命令（等效于 adb root shell 或 su -c）。
     * 需要 Shizuku 以 root 模式运行。
     */
    fun execAsRoot(command: String): String {
        if (!isReady) return "❌ Shizuku 未就绪"
        return try {
            // 先尝试 su 提权
            exec("su 0 $command")
        } catch (e: Exception) {
            "❌ Root 执行失败: ${e.message}"
        }
    }

    /** 获取 Shizuku 版本信息（调试用）。 */
    fun getVersionInfo(ctx: Context): String = try {
        """{
            |"installed":${isInstalled(ctx)},
            |"ready":$isReady,
            |"permission":${Shizuku.checkSelfPermission()},
            |"pid":${android.os.Process.myPid()}
        }""".trimMargin()
    } catch (e: Exception) {
        "{\"error\":\"${e.message}\"}"
    }
}
