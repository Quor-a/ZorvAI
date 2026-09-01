package com.ai.assistance.quro.core.privilege

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * LSPosed / Xposed 框架探测（与 ui.QuroLsposeScreen 的 UI 探测共享已知包名清单）。
 *
 * 本应用现已提供 opt-in LSPosed 模块（QuroXposedModule）：被纳入作用域时写真实作用域标记文件
 * （.lsposed_scope），isAppInScope() 据此真实判定，不再用「框架已安装」做假判定。
 * 模块另含可选跨应用注入 / 系统重定向桥（由外部 lsposed_bridge.json 配置驱动，默认关闭）。
 *
 * 不申请任何敏感权限、不定义任何 ai.aci.permission.*（定义权属控制端，本应用只声明与使用）。
 * Zorv AI 的终端 / ACI / 自动化能力走自有管线（无障碍 · Shizuku · 设备管理员 · ROOT），不依赖 Xposed 也能运行；
 * LSPosed 仅用于需要更深系统钩子的可选场景。
 */
object QuroLSPosed {

    private val KNOWN_MANAGERS = listOf(
        "org.lsposed.manager" to "LSPosed Manager",
        "com.tsng.edxposed" to "EdXposed Manager",
        "org.meowcat.edxposed" to "EdXposed Manager",
        "de.robv.android.xposed.installer" to "Xposed Installer",
    )

    /** 已安装的框架管理器（包名 + 展示名）。 */
    fun installedManagers(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return KNOWN_MANAGERS.mapNotNull { (pkg, name) ->
            runCatching { pm.getPackageInfo(pkg, 0) }.fold(
                onSuccess = { pkg to name },
                onFailure = { null },
            )
        }
    }

    fun isInstalled(ctx: Context): Boolean = installedManagers(ctx).isNotEmpty()

    /**
     * 本应用是否已被框架纳入作用域：框架已安装 且 存在模块写入的作用域标记文件（.lsposed_scope）。
     *
     * 机制：QuroApplication 每次启动先清除旧标记；若本应用确实被 LSPosed 钩中（纳入作用域），
     * QuroXposedModule 会在 attachBaseContext 钩子里重新写入标记。因此标记存在即代表「当前进程
     * 确实被钩中」——比原先「框架已安装即永真」的假判定更真实，且在取消作用域后随下次启动自愈。
     */
    fun isAppInScope(ctx: Context): Boolean {
        if (!isInstalled(ctx)) return false
        return scopeMarkerExists(ctx)
    }

    /** 模块写入的作用域标记是否存在。 */
    private fun scopeMarkerExists(ctx: Context): Boolean {
        return runCatching {
            File(ctx.filesDir, ".lsposed_scope").exists()
        }.getOrDefault(false)
    }

    /** 状态文本（供工具 / 权限页复用）。 */
    fun statusText(ctx: Context): String {
        val mgrs = installedManagers(ctx)
        return if (mgrs.isEmpty()) {
            "未安装 LSPosed / Xposed 框架（Zorv AI 走自有管线：无障碍/Shizuku/设备管理员/ROOT，无需 Xposed）"
        } else {
            val inScope = isAppInScope(ctx)
            if (inScope) {
                "已安装并纳入作用域：${mgrs.joinToString { it.second }}（opt-in 模块已生效）"
            } else {
                "已安装：${mgrs.joinToString { it.second }}（在管理器「模块」启用 Zorv AI 并勾选本应用以纳入作用域）"
            }
        }
    }
}
