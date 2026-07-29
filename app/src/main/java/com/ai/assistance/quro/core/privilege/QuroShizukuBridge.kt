package com.ai.assistance.quro.core.privilege

import android.content.Context
import android.content.pm.PackageManager
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import rikka.shizuku.Shizuku

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

    private const val SHIZUKU_PKG = "moe.shizuku.manager"
    private const val SHIZUKU_PKG_LEGACY = "moe.shizuku.privileged.api"

    /** Shizuku 应用是否已安装。 */
    fun isInstalled(ctx: Context): Boolean {
        val pm = ctx.packageManager
        // 优先判定用户实际安装的 Shizuku 管理器应用（v396 修复误报「未安装」）
        if (runCatching { pm.getPackageInfo(SHIZUKU_PKG, 0) }.getOrNull() != null) return true
        // 兜底：隐藏的系统配对组件（部分 ROM 上可能独立存在）
        return runCatching { pm.getPackageInfo(SHIZUKU_PKG_LEGACY, 0) }.getOrNull() != null
    }

    /** Shizuku 服务(ContentProvider)是否就绪——即 Shizuku 应用已在运行、可提供 Binder。 */
    fun isProviderReady(ctx: Context): Boolean {
        // Binder 存活即代表 Shizuku 服务已连接（与 isAlive 同义，保留以兼容既有调用）。
        return QuroShizuku.isAlive
    }

    /** 本应用是否已被 Shizuku 授权（持有其 API 权限）。 */
    fun isAuthorized(ctx: Context): Boolean {
        return runCatching {
            // 唯一可靠来源：Shizuku Binder 存活时，采用 Shizuku 自身的授权判定。
            // 注意：签名级权限 moe.shizuku.manager.permission.API_V23 由 Shizuku 内部托管，
            // 系统 PackageManager 不记录该授权（即使用户在 Shizuku 内已授权，PackageManager 仍返回 DENIED），
            // 因此「Binder 未存活」时绝不能据 PackageManager 判定为「未授权」，否则会误报。
            if (QuroShizuku.isAlive) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        }.getOrDefault(false)
    }

    /** L2 探测：返回 Shizuku 状态（安装 / 连接 / 授权 三维）。 */
    fun state(ctx: Context): PrivilegeState {
        if (!isInstalled(ctx)) {
            return PrivilegeState(PrivilegeLevel.L2, false, "Shizuku 未安装（请在应用商店安装 Shizuku）")
        }
        if (!QuroShizuku.isAlive) {
            return PrivilegeState(PrivilegeLevel.L2, false, "Shizuku 已安装但未连接（请打开 Shizuku 应用并保持运行，并在「已授权应用」中把本应用加入允许列表）")
        }
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) {
            val aidlMark = if (QuroShizuku.isAidlAvailable) " [AIDL]" else " [反射]"
            PrivilegeState(PrivilegeLevel.L2, true, "Shizuku 已就绪$aidlMark（特权命令通道可用，可执行系统级操作 / 冻结 / 静默安装）")
        } else {
            PrivilegeState(PrivilegeLevel.L2, false, "Shizuku 已连接但未授权（请在 Shizuku 应用中把本应用加入允许列表并点击授权）")
        }
    }

    /** 粗略判断 Shizuku 是否「真正可用」（已安装 + 已授权 + 服务就绪）。 */
    fun available(ctx: Context): Boolean =
        isInstalled(ctx) && QuroShizuku.isAlive && isAuthorized(ctx)

    /** 经 Shizuku 执行命令（严格模式：仅 Shizuku 就绪时执行，未就绪明确报错，绝不降级到 App 自身 UID）。 */
    fun exec(ctx: Context, command: String): String {
        if (!QuroShizuku.isReady) {
            return "❌ Shizuku 未就绪（请到 系统权限 → L2 Shizuku → 请求授权）"
        }
        return QuroShizuku.exec(command)
    }

    /** 通过 Shizuku 冻结应用（降级通道，需真实 Shizuku Binder 才生效）。 */
    fun freezePackage(ctx: Context, packageName: String): String = exec(ctx, "package suspend $packageName")

    /** 通过 Shizuku 静默安装 APK（降级通道，需真实 Shizuku Binder 才生效）。 */
    fun installPackage(ctx: Context, apkPath: String): String = exec(ctx, "package install-existing $apkPath")
}
