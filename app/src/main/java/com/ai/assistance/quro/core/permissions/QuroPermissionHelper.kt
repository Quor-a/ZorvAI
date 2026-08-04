package com.ai.assistance.quro.core.permissions

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.ai.assistance.quro.core.privilege.QuroShizukuBridge
import com.ai.assistance.quro.core.shizuku.QuroShizukuPkg
import com.ai.assistance.quro.receiver.QuroDeviceAdminReceiver
import com.ai.assistance.quro.service.QuroAccessibilityService
import java.io.File

/**
 * 权限管理：
 * 把 8 类权限统一抽象成 [QuroPermissionItem]，UI 只负责展示状态 + 点击跳转引导。
 * 检测与跳转均为标准 Android API，无第三方依赖；ROOT/Shizuku 仅做「能力探测 + 引导」，
 * 不真正执行高风险操作（符合 Quro 轻量、安全的定位）。
 */
data class QuroPermissionItem(
    val id: String,
    val title: String,
    val desc: String,
    val granted: Boolean,
    /** 点击后引导用户去开启的 Intent；null 表示无法用 Intent 引导（如 ROOT 需自行 root 设备）。 */
    val guideIntent: Intent?,
    /** 无法用 Intent 引导时的补充说明。 */
    val note: String = "",
)

object QuroPermissionHelper {

    /** 探测 su 二进制常见路径（不真正请求 root，避免弹窗/风险）。 */
    private val ROOT_BINARIES = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/vendor/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
    )

    fun getItems(context: Context): List<QuroPermissionItem> {
        val pkg = context.packageName
        return listOf(
            storage(context),
            overlay(context, pkg),
            battery(context, pkg),
            location(context),
            accessibility(context, pkg),
            shizuku(context),
            admin(context),
            root(context),
        )
    }

    // 1) 存储权限
    private fun storage(ctx: Context): QuroPermissionItem {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.hasPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ||
                ctx.hasPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            ctx.hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
                ctx.hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return QuroPermissionItem(
            id = "storage",
            title = "存储权限",
            desc = "读取/保存图片、文件与本地数据",
            granted = granted,
            guideIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
            },
        )
    }

    // 2) 悬浮窗权限
    private fun overlay(ctx: Context, pkg: String): QuroPermissionItem {
        val granted = Settings.canDrawOverlays(ctx)
        return QuroPermissionItem(
            id = "overlay",
            title = "悬浮窗权限",
            desc = "支持全局悬浮语音球随时唤醒",
            granted = granted,
            guideIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.fromParts("package", pkg, null)
            },
        )
    }

    // 3) 电池优化豁免
    private fun battery(ctx: Context, pkg: String): QuroPermissionItem {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val granted = pm.isIgnoringBatteryOptimizations(pkg)
        return QuroPermissionItem(
            id = "battery",
            title = "电池优化豁免",
            desc = "后台保活，避免语音球被系统回收",
            granted = granted,
            guideIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            },
        )
    }

    // 4) 位置权限
    private fun location(ctx: Context): QuroPermissionItem {
        val granted = ctx.hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            ctx.hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return QuroPermissionItem(
            id = "location",
            title = "位置权限",
            desc = "基于位置的工具能力（如天气、附近搜索）",
            granted = granted,
            guideIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
            },
        )
    }

    // 5) 无障碍服务
    private fun accessibility(ctx: Context, pkg: String): QuroPermissionItem {
        val cn = android.content.ComponentName(ctx, QuroAccessibilityService::class.java)
        val enabled = isAccessibilityServiceEnabled(ctx, cn.flattenToString())
        return QuroPermissionItem(
            id = "accessibility",
            title = "无障碍服务",
            desc = "屏幕内容读取与界面自动化（高级能力）",
            granted = enabled,
            guideIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                // 部分 ROM 支持直接定位到本服务
                putExtra(":settings:fragment_args_key", cn.flattenToString())
                putExtra("EXTRA_FRAGMENT_ARG_KEY", cn.flattenToString())
            },
            note = if (enabled) "" else "在系统「无障碍」列表中找到「Quro 无障碍」并开启",
        )
    }

    // 6) Shizuku 服务
    private fun shizuku(ctx: Context): QuroPermissionItem {
        // 包名判定统一走 QuroShizukuPkg：主流 v12+ 是 moe.shizuku.privileged.api，
        // 旧代码写死 v11 的 moe.shizuku.manager，在主流机器上会误报「未安装」并把用户
        // 导去商店安装一个不存在的应用。
        val installedPkg = QuroShizukuPkg.installed(ctx)
        val installed = installedPkg != null
        val authorized = installed && QuroShizukuBridge.isAuthorized(ctx)
        return QuroPermissionItem(
            id = "shizuku",
            title = "Shizuku 服务",
            desc = "通过 Shizuku 获取 adb/系统级能力（免 root）",
            granted = authorized,
            guideIntent = when {
                !installed -> Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("market://details?id=${QuroShizukuPkg.storePackage()}")
                }
                // 已安装但未授权：拉起实际安装的那个包；Launcher Activity 被 ROM 隐藏时用协议 action 兜底
                !authorized -> ctx.packageManager.getLaunchIntentForPackage(installedPkg!!)
                    ?: Intent(QuroShizukuPkg.Action.MAIN_ACTIVITY).setPackage(installedPkg)
                else -> null
            },
            note = when {
                !installed -> "未安装 Shizuku，请先在应用商店安装"
                authorized -> "已授权，可正常调用"
                else -> "请在 Shizuku 中授权 Zorv AI 并确保其正在运行"
            },
        )
    }

    // 7) 设备管理员
    private fun admin(ctx: Context): QuroPermissionItem {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val cn = android.content.ComponentName(ctx, QuroDeviceAdminReceiver::class.java)
        val active = dpm.isAdminActive(cn)
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "授予 Zorv AI 设备管理员以启用高级系统管理能力")
        }
        return QuroPermissionItem(
            id = "admin",
            title = "设备管理员权限",
            desc = "锁定屏幕、远程管理等高级系统能力",
            granted = active,
            guideIntent = intent,
            note = if (active) "" else "在系统「设备管理员」中激活 Zorv AI",
        )
    }

    // 8) ROOT 访问权限
    private fun root(ctx: Context): QuroPermissionItem {
        val hasRoot = ROOT_BINARIES.any { File(it).exists() }
        return QuroPermissionItem(
            id = "root",
            title = "ROOT 访问权限",
            desc = "最高系统权限，可执行任意 shell 命令",
            granted = hasRoot,
            guideIntent = null,
            note = if (hasRoot) "已检测到 root 环境" else "需设备已 root 并授权终端；无法在应用内直接获取",
        )
    }

    // ---- 内部工具 ----

    private fun Context.hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(ctx: Context, serviceFlatName: String): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.id == serviceFlatName }
    }

    // ---- 实际能力调用（已授权后真实执行，非占位） ----

    /** 设备管理员「锁屏」（需已激活设备管理员）。 */
    fun lockScreen(context: Context): String = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val cn = android.content.ComponentName(context, QuroDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(cn)) return "设备管理员未激活，无法锁屏"
        dpm.lockNow()
        "已发送锁屏指令"
    }.getOrElse { "锁屏失败：${it.message}" }

    /** 经 ROOT（su）真实执行一条 shell 命令，返回 exit code + 输出。 */
    fun runRootCommand(command: String): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val err = proc.errorStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        val body = (out + err).trim()
        "exit=$code\n${if (body.isBlank()) "(无输出)" else body}"
    }.getOrElse { "ROOT 命令执行失败：${it.message}" }
}
