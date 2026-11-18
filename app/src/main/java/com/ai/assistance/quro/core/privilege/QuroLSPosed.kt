package com.ai.assistance.quro.core.privilege

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ai.assistance.quro.core.xposed.QuroModuleConfig
import com.ai.assistance.quro.core.xposed.QuroModuleServiceState
import com.ai.assistance.quro.core.xposed.QuroXposedFrameworks
import com.ai.assistance.quro.core.xposed.QuroXposedFrameworks.Family
import com.ai.assistance.quro.core.xposed.QuroXposedFrameworks.ZygiskProvider
import java.io.File

/**
 * Xposed 框架探测与状态汇总（UI / 工具侧）。
 *
 * 判定层级（2026-11-12 修正，解决「Vector + ZygiskNext 识别不到」）：
 *  1. **权威信号**：[QuroModuleServiceState] 是否拿到 XposedService binder。框架自己把服务推过来，
 *     就证明它存在并正在运行 —— 这与管理器 APK 叫什么、有没有桌面图标无关。
 *  2. **辅助信号**：已安装的管理器 APK（Vector / LSPosed 2.x / 旧版 / EdXposed）。
 *  3. **展示信号**：框架自报的 name / version / apiVersion，用于区分 Vector 与 LSPosed、
 *     判断能否加载 API 102 模块。
 *
 * 旧实现把第 2 条当成唯一依据，而 Vector 管理器包名是 `org.matrix.vector.manager`
 * （不是 `org.lsposed.manager`），且可从通知安装、隐藏图标、甚至只开拨号入口，
 * 于是「框架已连、作用域已配、Hook 已加载」仍被误报成「未安装框架」。
 *
 * 原有对外 API（installedManagers / isInstalled / isAppInScope / statusText）签名保持不变，
 * 权限页、终端特权桥、工具调用方无需改动。
 */
object QuroLSPosed {

    /** 最近一次 Zygisk 提供者探测结果；仅由 refreshZygiskProviderBlocking() 写入。 */
    @Volatile
    private var zygiskProvider: ZygiskProvider = ZygiskProvider.UNKNOWN

    /** 已安装的框架管理器（包名 + 展示名）。按「新 → 旧」排列。 */
    fun installedManagers(ctx: Context): List<Pair<String, String>> =
        managerDefs(ctx).map { it.pkg to it.label }

    /** 已安装的管理器定义（含家族、API 102 能力、拨号入口）。 */
    fun managerDefs(ctx: Context): List<QuroXposedFrameworks.ManagerDef> {
        val pm = ctx.packageManager
        return QuroXposedFrameworks.MANAGERS.filter { def ->
            runCatching { pm.getPackageInfo(def.pkg, 0) }.isSuccess
        }
    }

    /**
     * 框架是否存在。
     *
     * 「服务已连接」优先：这是框架给出的 binder，比任何包名列表都可靠。
     * 只有服务未连接时才退回管理器 APK 探测（此时可能是模块未启用、框架未加载或用户未装管理器）。
     */
    fun isInstalled(ctx: Context): Boolean = QuroXposedFrameworks.isFrameworkPresent(
        frameworkConnected = isFrameworkConnected(),
        installedManagerPkgs = installedManagers(ctx).map { it.first },
    )

    /** 框架服务是否已连接（binder 已推送到本进程）。 */
    fun isFrameworkConnected(): Boolean = QuroModuleServiceState.isConnected()

    /** 框架返回的权威状态帧。 */
    fun moduleStatus() = QuroModuleServiceState.status()

    /** 框架家族（仅凭框架自报信息判定；框架未自报名称时返回 UNKNOWN）。 */
    fun frameworkFamily(): Family {
        val st = moduleStatus()
        return QuroXposedFrameworks.familyOf(
            frameworkName = st.frameworkName,
            apiVersion = st.apiVersion,
            installedManagerPkgs = emptyList(),
        )
    }

    /** 带 Context 的家族判定：框架自报名称优先，其次用已安装管理器包名。 */
    fun frameworkFamily(ctx: Context): Family {
        val st = moduleStatus()
        return QuroXposedFrameworks.familyOf(
            frameworkName = st.frameworkName,
            apiVersion = st.apiVersion,
            installedManagerPkgs = installedManagers(ctx).map { it.first },
        )
    }

    fun frameworkLabel(ctx: Context): String =
        QuroXposedFrameworks.labelOf(frameworkFamily(ctx))

    fun apiVersion(): Int = moduleStatus().apiVersion

    /** 当前框架能否加载本模块（声明为 API 102）。 */
    fun supportsApi102(ctx: Context): Boolean = QuroXposedFrameworks.supportsApi102(
        family = frameworkFamily(ctx),
        apiVersion = apiVersion(),
    )

    /** 一句话兼容性诊断，供 UI / 工具 / 权限面板共用。 */
    fun compatibilityNote(ctx: Context): String = QuroXposedFrameworks.compatibilityNote(
        family = frameworkFamily(ctx),
        connected = isFrameworkConnected(),
        apiVersion = apiVersion(),
    )

    /** 最近一次探测到的 Zygisk 提供者；未探测过返回 UNKNOWN。 */
    fun zygiskProvider(): ZygiskProvider = zygiskProvider

    /**
     * 探测 Zygisk 提供者（ZygiskNext / NeoZygisk / ReZygisk / Magisk 内置）。
     *
     * **阻塞**，必须在 IO 线程调用：它经 Root / Shizuku 读 `/data/adb/modules`。
     * 无特权时如实返回 UNKNOWN，绝不把「读不到」伪装成「没装」。
     */
    fun refreshZygiskProviderBlocking(): ZygiskProvider {
        val result = QuroRootGateway.exec(
            context = null,
            command = "ls ${QuroXposedFrameworks.MODULES_DIR} 2>/dev/null",
            timeoutMs = 4_000L,
            capsuleId = "xposed.zygisk_probe",
        )
        val provider = if (result.success) {
            QuroXposedFrameworks.zygiskFromModuleListing(result.output)
        } else {
            ZygiskProvider.UNKNOWN
        }
        zygiskProvider = provider
        return provider
    }

    /** 作用域是否已配置：以框架下发的作用域清单为准，不再读标记文件。 */
    fun scopePackages(): List<String> = moduleStatus().scope

    /** 当前确实加载了模块代码的进程。 */
    fun activeTargets() = moduleStatus().runningTargets

    fun missingScope(): List<String> = moduleStatus().missingScope

    /**
     * 模块是否已启用并纳入作用域。
     *
     * 判定：框架服务已连接 **且** 框架给出的作用域清单非空。不再要求「管理器 APK 已安装」——
     * Vector 管理器可以隐藏，硬要求会把已生效的模块误判为未启用。
     */
    fun isAppInScope(ctx: Context): Boolean =
        isFrameworkConnected() && scopePackages().isNotEmpty()

    /** 兼容旧调用：读一次遗留标记文件是否存在（仅用于诊断，不参与判定）。 */
    fun legacyMarkerExists(ctx: Context): Boolean = runCatching {
        File(ctx.filesDir, ".lsposed_scope").exists()
    }.getOrDefault(false)

    /**
     * 打开框架管理器。
     *
     * 顺序：可启动的 Launcher Intent → 拨号盘快捷入口（Vector / LSPosed 都支持）→ 应用详情页。
     * Vector 管理器常无桌面图标，Launcher Intent 可能为空，此时拨号码是唯一稳妥入口。
     */
    fun openManager(ctx: Context): Boolean {
        val defs = managerDefs(ctx)
        for (def in defs) {
            val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(def.pkg) }.getOrNull()
            if (launch != null) {
                val ok = runCatching {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(launch)
                }.isSuccess
                if (ok) return true
            }
            val dial = def.dialCode
            if (dial != null) {
                val ok = runCatching {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dial"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }.isSuccess
                if (ok) return true
            }
            val ok = runCatching {
                val details = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${def.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(details)
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    /** 状态文本（供工具 / 权限页复用）。 */
    fun statusText(ctx: Context): String {
        val st = moduleStatus()
        val family = frameworkFamily(ctx)
        val mgrs = installedManagers(ctx)
        return buildString {
            if (!st.connected && mgrs.isEmpty()) {
                append("未检测到 Xposed 框架（Vector / LSPosed / EdXposed）；Zorv AI 走自有管线：无障碍 / Shizuku / 设备管理员，无需 Xposed")
                return@buildString
            }
            val name = st.frameworkName?.takeIf { it.isNotBlank() }
                ?: QuroXposedFrameworks.labelOf(family)
            append("框架：$name")
            st.frameworkVersion?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            if (st.apiVersion > 0) append("（API ${st.apiVersion}）")
            if (mgrs.isNotEmpty()) append("；管理器：${mgrs.joinToString { it.second }}")
            if (!st.connected) {
                append("；未收到框架服务连接，不影响聊天、GUI、浏览器与普通 Linux 环境")
                val note = QuroXposedFrameworks.compatibilityNote(family, false, st.apiVersion)
                if (note.isNotBlank()) append("；$note")
                return@buildString
            }
            append("；已连接；作用域 ${st.scope.size}/${QuroModuleConfig.DECLARED_SCOPE.size}")
            append("；已加载模块进程 ${st.runningTargets.size} 个")
            val missing = st.missingScope
            if (missing.isNotEmpty()) append("；缺失：${missing.joinToString()}")
            if (!supportsApi102(ctx)) {
                append("；⚠ ${QuroXposedFrameworks.compatibilityNote(family, true, st.apiVersion)}")
            }
        }
    }
}
