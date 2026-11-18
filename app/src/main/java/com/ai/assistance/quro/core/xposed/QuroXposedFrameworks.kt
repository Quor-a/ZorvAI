package com.ai.assistance.quro.core.xposed

/**
 * 现代 Xposed 框架的识别常量与判定（净室实现，纯 Kotlin，不引用任何 Android 类）。
 *
 * 背景：2026 年起生态分成两条线——
 *  · **Vector**（JingMatrix 维护的 LSPosed 活跃分支，GPLv3）v2.2 起实现 libxposed **API 102**，
 *    管理器包名 `org.matrix.vector.manager`，注入到 `com.android.shell`(UID 2000)，
 *    依赖 Zygisk 环境（ZygiskNext / NeoZygisk / ReZygisk / Magisk 内置 Zygisk）。
 *  · 官方 LSPosed 2.x（闭源）与旧版 LSPosed 1.9.x / EdXposed / 原始 Xposed 仍在存量设备上。
 *
 * 旧实现只按「管理器 APK 是否安装」判定框架存在，而新框架的管理器是从通知里安装、
 * 可以隐藏、包名也不同，于是出现「框架服务已连接、作用域已配、Hook 已加载，app 却说未安装」。
 * 因此本文件只负责**识别与展示**，真正的「是否存在」判定交给
 * [QuroModuleServiceState] 的 XposedService binder —— 那是框架自己给出的权威信号。
 */
object QuroXposedFrameworks {

    /** 本模块声明并要求的最低现代 API。见 META-INF/xposed/module.prop。 */
    const val REQUIRED_API = 102

    /** 框架家族。UNKNOWN 不代表未安装，只代表无法归类（仍可能已连接）。 */
    enum class Family {
        VECTOR,
        LSPOSED_MODERN,
        LSPOSED_LEGACY,
        EDXPOSED,
        XPOSED_ORIGINAL,
        UNKNOWN,
    }

    data class ManagerDef(
        val pkg: String,
        val label: String,
        val family: Family,
        /** 该管理器对应的框架是否原生支持 libxposed API 102。 */
        val api102: Boolean,
        /** 拨号盘快捷入口；无则 null。 */
        val dialCode: String? = null,
    )

    /**
     * 已知管理器清单，按「新 → 旧」排列：installedManagers() 的 firstOrNull() 会优先打开新框架。
     * 包名是各项目构建配置里的客观常量，不是猜测。
     */
    val MANAGERS: List<ManagerDef> = listOf(
        ManagerDef(
            pkg = "org.matrix.vector.manager",
            label = "Vector Manager",
            family = Family.VECTOR,
            api102 = true,
            dialCode = "*#*#832867#*#*", // VECTOR
        ),
        ManagerDef(
            pkg = "org.lsposed.manager",
            label = "LSPosed Manager",
            family = Family.LSPOSED_MODERN,
            api102 = true,
            dialCode = "*#*#5776733#*#*", // LSPOSED
        ),
        ManagerDef(
            pkg = "com.tsng.edxposed",
            label = "EdXposed Manager",
            family = Family.EDXPOSED,
            api102 = false,
        ),
        ManagerDef(
            pkg = "org.meowcat.edxposed",
            label = "EdXposed Manager",
            family = Family.EDXPOSED,
            api102 = false,
        ),
        ManagerDef(
            pkg = "de.robv.android.xposed.installer",
            label = "Xposed Installer",
            family = Family.XPOSED_ORIGINAL,
            api102 = false,
        ),
    )

    /** Zygisk 提供者。它们是 Magisk/KernelSU 模块而非 APK，PackageManager 查不到。 */
    enum class ZygiskProvider(val label: String, val moduleDirNames: List<String>) {
        ZYGISK_NEXT("ZygiskNext", listOf("zygisknext", "zygisk_next")),
        NEO_ZYGISK("NeoZygisk", listOf("nezygisk", "neozygisk")),
        RE_ZYGISK("ReZygisk", listOf("rezygisk")),
        MAGISK_BUILTIN("Magisk 内置 Zygisk", emptyList()),
        UNKNOWN("未确认（需要 Root / Shizuku 才能读取 /data/adb/modules）", emptyList()),
    }

    /** 框架守护进程的常见目录，仅用于有特权时做辅助诊断。 */
    const val LSPD_DIR = "/data/adb/lspd"
    const val MODULES_DIR = "/data/adb/modules"

    fun defOf(pkg: String): ManagerDef? = MANAGERS.firstOrNull { it.pkg == pkg }

    /**
     * 框架是否存在的唯一判定口径：服务连接是框架自报的权威信号，管理器包名只是辅助。
     *
     * 抽成纯函数是为了能被契约测试钉死——历史上把管理器包名当唯一依据，
     * 直接导致 Vector 下「已连接却显示未安装」。
     */
    fun isFrameworkPresent(
        frameworkConnected: Boolean,
        installedManagerPkgs: List<String>,
    ): Boolean = frameworkConnected || installedManagerPkgs.isNotEmpty()

    fun labelOf(family: Family): String = when (family) {
        Family.VECTOR -> "Vector"
        Family.LSPOSED_MODERN -> "LSPosed 2.x"
        Family.LSPOSED_LEGACY -> "LSPosed 1.9.x（旧版）"
        Family.EDXPOSED -> "EdXposed"
        Family.XPOSED_ORIGINAL -> "Xposed Installer"
        Family.UNKNOWN -> "未识别框架"
    }

    /**
     * 综合判定框架家族。
     *
     * 优先级：框架自报名称 > 已安装管理器 > API 版本兜底。
     * 名称匹配用小写包含，兼容 "Vector 2.2" / "LSPosed" / "JingMatrix LSPosed" 等写法。
     */
    fun familyOf(
        frameworkName: String?,
        apiVersion: Int,
        installedManagerPkgs: List<String>,
    ): Family {
        val name = frameworkName?.lowercase().orEmpty()
        if (name.contains("vector") || name.contains("jingmatrix")) return Family.VECTOR
        if (name.contains("lsposed") || name.contains("lspd")) {
            return if (apiVersion >= 100) Family.LSPOSED_MODERN else Family.LSPOSED_LEGACY
        }
        if (name.contains("edxposed")) return Family.EDXPOSED
        if (name.contains("xposed")) return Family.XPOSED_ORIGINAL

        val families = installedManagerPkgs.mapNotNull { defOf(it)?.family }
        // 管理器里只要有一个现代框架，就按现代框架归类。
        return when {
            Family.VECTOR in families -> Family.VECTOR
            Family.LSPOSED_MODERN in families -> Family.LSPOSED_MODERN
            Family.LSPOSED_LEGACY in families -> Family.LSPOSED_LEGACY
            Family.EDXPOSED in families -> Family.EDXPOSED
            Family.XPOSED_ORIGINAL in families -> Family.XPOSED_ORIGINAL
            apiVersion >= 100 -> Family.UNKNOWN
            else -> Family.UNKNOWN
        }
    }

    /** 当前连接的框架能否加载本模块（API 102）。apiVersion 由框架自报，最可信。 */
    fun supportsApi102(family: Family, apiVersion: Int): Boolean = when {
        apiVersion >= REQUIRED_API -> true
        apiVersion > 0 -> false
        else -> family == Family.VECTOR || family == Family.LSPOSED_MODERN
    }

    /** 给用户的一句话诊断：区分「没连上」「连上了但版本旧」「连上了可用」。 */
    fun compatibilityNote(family: Family, connected: Boolean, apiVersion: Int): String = when {
        !connected -> "未收到框架服务连接"
        !supportsApi102(family, apiVersion) ->
            "当前框架 API $apiVersion，低于本模块要求的 API $REQUIRED_API；请升级到 Vector 2.2 / LSPosed 2.x"
        family == Family.VECTOR -> "Vector（API 102）已连接，模块可被加载"
        else -> "${labelOf(family)}（API $apiVersion）已连接，模块可被加载"
    }

    /** 从 `ls /data/adb/modules` 的输出里挑出已安装的 Zygisk 提供者。 */
    fun zygiskFromModuleListing(listing: String?): ZygiskProvider {
        if (listing.isNullOrBlank()) return ZygiskProvider.UNKNOWN
        val text = listing.lowercase()
        return ZygiskProvider.values().firstOrNull { provider ->
            provider.moduleDirNames.any { dir -> text.contains(dir) }
        } ?: ZygiskProvider.MAGISK_BUILTIN.takeIf {
            text.contains("magisk") || text.contains("zygisk")
        } ?: ZygiskProvider.UNKNOWN
    }
}
