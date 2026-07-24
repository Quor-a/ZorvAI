package com.ai.assistance.quro.core.privilege

import android.content.Context
import android.content.pm.PackageManager
import java.io.DataOutputStream

/**
 * Shizuku 桥接（原创）。
 *
 * v115 升级：从纯探测层恢复为完整执行通道。
 *   - 探测层（isInstalled / isProviderReady / isAuthorized）：经 PackageManager 探测，无需 Shizuku API
 *   - 执行层（exec / freezePackage / installPackage）：优先走 [QuroShizuku] 真实 Binder IPC，
 *     降级为 Runtime.exec(sh)（能力受限）
 *
 * 依赖：dev.rikka.shizuku:api 库（v115 已恢复到 build.gradle.kts）。
 */
object QuroShizukuBridge {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val SHIZUKU_PROVIDER = "moe.shizuku.privileged.api.provider"
    private const val SHIZUKU_PERM = "moe.shizuku.manager.permission.API_V23"

    /** Shizuku 应用是否已安装。 */
    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    }.getOrDefault(false)

    /** Shizuku 服务(ContentProvider)是否就绪——即 Shizuku 应用已在运行、可提供 Binder。 */
    fun isProviderReady(ctx: Context): Boolean {
        // 仅经 PackageManager 探测 Shizuku 服务 ContentProvider 是否就绪（已去除对 Shizuku Binder 的硬依赖）。
        return runCatching {
            ctx.packageManager.resolveContentProvider(SHIZUKU_PROVIDER, 0) != null
        }.getOrDefault(false)
    }

    /** 本应用是否已被 Shizuku 授权（持有其 API 权限）。 */
    fun isAuthorized(ctx: Context): Boolean {
        // 仅经系统权限表探测本应用是否持有 Shizuku API 权限（已去除对 Shizuku Binder 的硬依赖）。
        return runCatching {
            ctx.packageManager.checkPermission(SHIZUKU_PERM, ctx.packageName) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** L2 探测：返回 Shizuku 状态（安装 / 启动 / 授权 三维）。 */
    fun state(ctx: Context): PrivilegeState {
        return when {
            !isInstalled(ctx) ->
                PrivilegeState(PrivilegeLevel.L2, false, "Shizuku 未安装（请在应用商店安装）")
            !isAuthorized(ctx) ->
                PrivilegeState(PrivilegeLevel.L2, false, "Shizuku 已安装但未授权（请在 Shizuku 应用中把本应用加入允许列表）")
            !isProviderReady(ctx) ->
                PrivilegeState(PrivilegeLevel.L2, false, "Shizuku 已授权但未启动（请在 Shizuku 应用中启动服务 / 连接）")
            else -> PrivilegeState(
                PrivilegeLevel.L2,
                true,
                "Shizuku 已就绪（已去除 Binder 依赖，仅作状态展示）",
            )
        }
    }

    /** 粗略判断 Shizuku 是否「真正可用」（已安装 + 已授权 + 服务就绪）。 */
    fun available(ctx: Context): Boolean =
        isInstalled(ctx) && isAuthorized(ctx) && isProviderReady(ctx)

    /** 经 Shizuku 执行命令（优先真实 Binder IPC，降级为系统 shell）。 */
    fun exec(ctx: Context, command: String): String {
        // 优先尝试 QuroShizuku 真实 Binder（v115 恢复）
        return try {
            val qs = Class.forName("com.ai.assistance.quro.core.shizuku.QuroShizuku").getField("INSTANCE")
                ?.get(null) ?: throw IllegalStateException("QuroShizuku 未初始化")
            val isReady = qs.javaClass.getMethod("isReady").invoke(qs) as? Boolean ?: false
            if (isReady) {
                val execMethod = qs.javaClass.getMethod("exec", String::class.java)
                execMethod.invoke(qs, command) as String
            } else {
                fallbackExec(command)
            }
        } catch (_: ClassNotFoundException) {
            // Shizuku API 库不可用（不应发生，但兜底）
            fallbackExec(command)
        } catch (e: Exception) {
            "Shizuku 执行异常：${e.message} → ${fallbackExec(command)}"
        }
    }

    private fun fallbackExec(command: String): String = runCatching {
        val proc = Runtime.getRuntime().exec("sh")
        val os = java.io.DataOutputStream(proc.outputStream)
        os.writeBytes("cmd $command\n")
        os.writeBytes("exit\n")
        os.flush()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val err = proc.errorStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        val body = (out + err).trim()
        "exit=$code (shell-降级)\n${if (body.isBlank()) "(无输出)" else body}"
    }.getOrElse { "执行失败：${it.message}" }

    /** 通过 Shizuku 冻结应用（降级通道，需真实 Shizuku Binder 才生效）。 */
    fun freezePackage(ctx: Context, packageName: String): String = exec(ctx, "package suspend $packageName")

    /** 通过 Shizuku 静默安装 APK（降级通道，需真实 Shizuku Binder 才生效）。 */
    fun installPackage(ctx: Context, apkPath: String): String = exec(ctx, "package install-existing $apkPath")
}
