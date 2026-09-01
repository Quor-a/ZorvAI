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
import com.ai.assistance.quro.core.privilege.QuroRootGateway
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
            setAlarm(context, pkg),
            exactAlarm(context, pkg),
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

    // 4-1) 「设置闹钟」（系统 SET_ALARM 普通权限 + AlarmClock.ACTION_SET_ALARM 跳转）
    /**
     * 对应系统「应用信息 → 所有权限」里的「设置闹钟」项。
     * SET_ALARM 是 normal permission，无需运行时申请；用户能在系统所有权限列表中看到。
     * 真正使用：通过 `AlarmClock.ACTION_SET_ALARM` Intent 跳转系统时钟 App 设闹钟。
     * 同时 Android 14+ 在精确闹钟被拒后，仍可用此入口降级（依赖系统时钟 App 的兜底）。
     */
    private fun setAlarm(ctx: Context, pkg: String): QuroPermissionItem {
        // SET_ALARM 是 normal permission，安装即默认授权
        val granted = ctx.hasPermission(android.Manifest.permission.SET_ALARM)
        return QuroPermissionItem(
            id = "set_alarm",
            title = "设置闹钟",
            desc = "跳转到系统时钟 App 设置闹钟/提醒（普通权限，安装即授）",
            granted = granted,
            // 点击直接跳到本应用的「应用信息」页，用户可在此处直观看到「设置闹钟」项
            guideIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
            },
            note = if (!granted) "未授权，请在应用详情中确认未被禁用" else "可点击下方「测试闹钟」直接跳到系统时钟设置",
        )
    }

    // 4-2) 「精确闹钟」（SCHEDULE_EXACT_ALARM，Android 12+ 需用户在系统设置授权）
    private fun exactAlarm(ctx: Context, pkg: String): QuroPermissionItem {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true // Android 12 以下不需要特殊权限
        }
        return QuroPermissionItem(
            id = "exact_alarm",
            title = "精确闹钟",
            desc = "应用自管闹钟/提醒（无需跳转系统时钟，Android 12+ 需用户授权）",
            granted = granted,
            guideIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$pkg")
                }
            } else {
                null
            },
            note = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) "Android 12 以下不需要特殊权限" else "Android 12+ 需在系统设置手动开启「精确闹钟」，点「开启」跳转，授权后可用「测试」按钮验证",
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
            note = if (enabled) "" else "在系统「无障碍」列表中找到「ZorvAI 无障碍」并开启",
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
            // E-11：文案与 quro_device_admin.xml 声明的策略逐条对应，不宽泛表述。
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "仅用于两项操作：锁定屏幕、禁用或恢复摄像头。")
        }
        return QuroPermissionItem(
            id = "admin",
            title = "设备管理员权限",
            desc = "锁定屏幕、禁用/恢复摄像头（仅此两项）",
            granted = active,
            guideIntent = intent,
            note = if (active) "" else "在系统「设备管理员」中激活 Zorv AI",
        )
    }

    // 8) ROOT 访问权限
    /**
     * ROOT 状态**如实**上报。
     *
     * 旧实现把「/system/bin/su 文件存在」直接当作 `granted = true`——但 su 存在
     * 只说明设备刷过 Root 管理器，本应用完全可能没被授权（甚至被明确拒绝），
     * 这是典型的谎报可用。
     *
     * 现在的判定：
     *  - 只有 [QuroRootGateway] **实测**通过（`su -c echo root_ok` 有真实回显）才算 granted；
     *  - 从未实测过时如实标注「未验证」，而不是猜一个 true；
     *  - 本函数是同步的（会在权限列表构建时调用），所以只读缓存、绝不发起 su 进程。
     */
    private fun root(ctx: Context): QuroPermissionItem {
        val suBinaryPresent = ROOT_BINARIES.any { File(it).exists() }
        val verified: Boolean? = QuroRootGateway.cachedRootAvailable()
        val note = when {
            verified == true -> "已实测：su 授权可用"
            verified == false && suBinaryPresent -> "检测到 su 二进制，但本应用未获授权（请在 Magisk/KernelSU 中允许 Zorv AI）"
            verified == false -> "未检测到可用的 su"
            suBinaryPresent -> "检测到 su 二进制，但尚未验证授权 —— 打开「系统权限 → L4 Root」可实测"
            else -> "需设备已 root 并授权本应用；无法在应用内直接获取"
        }
        return QuroPermissionItem(
            id = "root",
            title = "ROOT 访问权限",
            desc = "最高系统权限，可执行任意 shell 命令",
            granted = verified == true,
            guideIntent = null,
            note = note,
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

    /**
     * 经 ROOT 真实执行一条 shell 命令，返回 exit code + 输出。
     *
     * E-7：统一走 [QuroRootGateway]。旧实现是项目里第 4 套并行 root 执行，
     * 且 `proc.waitFor()` 不带超时——su 卡住（Magisk 弹框没人点）时会永久阻塞调用线程。
     *
     * **阻塞**调用，需在 IO 线程执行。
     */
    fun runRootCommand(context: Context?, command: String): String =
        QuroRootGateway.execText(context, command, capsuleId = "permissions.root_cmd")
}
