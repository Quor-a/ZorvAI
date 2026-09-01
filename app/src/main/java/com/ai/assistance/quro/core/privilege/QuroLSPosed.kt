package com.ai.assistance.quro.core.privilege

import android.content.Context
import android.content.pm.PackageManager

/**
 * LSPosed / Xposed 框架探测（与 ui.QuroLsposeScreen 的 UI 探测共享已知包名清单）。
 *
 * 仅做探测，不注入任何钩子、不申请任何敏感权限（符合 LSPosed 界面铁律：定义权属控制端，本应用只声明与使用）。
 * Zorv AI 的终端 / ACI / 自动化能力走自有管线（无障碍 · Shizuku · 设备管理员 · ROOT），不依赖 Xposed 也能运行；
 * LSPosed 仅用于需要更深系统钩子的场景（跨应用界面注入、系统级重定向）。
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
     * 本应用是否可能被框架纳入作用域：仅当框架已安装时用户才可在管理器内勾选本应用。
     * 框架不提供静态 API 判定具体作用域，故返回框架是否已安装（作用域须用户在管理器手动勾选）。
     */
    fun isAppInScope(ctx: Context): Boolean = isInstalled(ctx)

    /** 状态文本（供工具 / 权限页复用）。 */
    fun statusText(ctx: Context): String {
        val mgrs = installedManagers(ctx)
        return if (mgrs.isEmpty()) {
            "未安装 LSPosed / Xposed 框架（Zorv AI 走自有管线：无障碍/Shizuku/设备管理员/ROOT，无需 Xposed）"
        } else {
            "已安装：${mgrs.joinToString { it.second }}（在管理器「应用」中勾选本应用以纳入作用域）"
        }
    }
}
