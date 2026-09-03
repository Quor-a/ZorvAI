package com.ai.assistance.quro.core.privilege

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.ai.assistance.quro.core.adb.QuroAdbDebug
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import com.ai.assistance.quro.core.shizuku.QuroShizukuPkg
import com.ai.assistance.quro.terminal.privilege.TerminalPrivilegeBridge
import com.ai.assistance.quro.terminal.privilege.TerminalPrivilegeEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端「权限」面板接入 app 侧特权后端（ROOT / Shizuku / ADB / LSPosed / 共享存储）。
 *
 * 实现 [TerminalPrivilegeBridge]，在 [com.ai.assistance.quro.activity.QuroApplication.onCreate]
 * 注入到 [com.ai.assistance.quro.terminal.privilege.TerminalPrivilegeBridgeHolder]。
 *
 * 线程约定：probe 里的 root 探测 / adb 探测是阻塞操作，统一放 IO 线程，回调切回主线程，
 * 避免 Compose 主线程 ANR（与 QuroPrivilegeManager.probeAsync 一致）。
 */
class QuroTerminalPrivilegeBridge(
    private val appContext: Context,
) : TerminalPrivilegeBridge {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val KEY_ROOT = "root"
        const val KEY_SHIZUKU = "shizuku"
        const val KEY_ADB = "adb"
        const val KEY_LSPOSED = "lsposed"
        const val KEY_STORAGE = "storage"

        private const val SHIZUKU_REQUEST_CODE = 1024
    }

    override fun snapshot(): List<TerminalPrivilegeEntry> = listOf(
        rootEntry(cachedOnly = true),
        shizukuEntry(),
        adbEntry(cachedOnly = true),
        lsposedEntry(),
        storageEntry(),
    )

    override fun probe(onDone: (List<TerminalPrivilegeEntry>) -> Unit) {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                listOf(
                    rootEntry(cachedOnly = false),
                    shizukuEntry(),
                    adbEntry(cachedOnly = false),
                    lsposedEntry(),
                    storageEntry(),
                )
            }
            onDone(list)
        }
    }

    override fun request(activity: Activity, key: String, onDone: () -> Unit) {
        when (key) {
            KEY_ROOT -> requestRoot(activity)
            KEY_SHIZUKU -> requestShizuku(activity)
            KEY_ADB -> requestAdb(activity)
            KEY_LSPOSED -> requestLsposed(activity)
            KEY_STORAGE -> requestStorage(activity)
        }
        onDone()
    }

    // ── 各权限项状态 ──

    private fun rootEntry(cachedOnly: Boolean): TerminalPrivilegeEntry {
        val ok = if (cachedOnly) {
            QuroRootGateway.cachedRootAvailable() == true
        } else {
            runCatching { QuroRootGateway.isRootAvailable() }.getOrDefault(false)
        }
        return TerminalPrivilegeEntry(
            key = KEY_ROOT,
            title = "ROOT",
            status = if (ok) "Root 访问可用" else "未获取 Root",
            available = ok,
            detail = "su / Magisk · 用于修改系统分区、静默执行特权命令",
        )
    }

    private fun shizukuEntry(): TerminalPrivilegeEntry {
        val installed = runCatching { QuroShizuku.isInstalled(appContext) }.getOrDefault(false)
        val ready = runCatching { QuroShizuku.isReady }.getOrDefault(false)
        return TerminalPrivilegeEntry(
            key = KEY_SHIZUKU,
            title = "Shizuku",
            status = when {
                !installed -> "未安装 Shizuku"
                ready -> "Shizuku 已授权"
                else -> "Shizuku 未授权"
            },
            available = ready,
            detail = if (installed) "免 Root 调用系统 API（静默安装 / 冻结应用等）" else "需先安装 Shizuku（RikkaApps）",
        )
    }

    private fun adbEntry(cachedOnly: Boolean): TerminalPrivilegeEntry {
        val shizukuReady = runCatching { QuroShizuku.isReady }.getOrDefault(false)
        // cachedOnly 时绝不触发 isRootAvailable()（会 spawn su 阻塞主线程），只用缓存。
        val rootReady = if (cachedOnly) {
            QuroRootGateway.cachedRootAvailable() == true
        } else {
            runCatching { QuroRootGateway.isRootAvailable() }.getOrDefault(false)
        }
        val privileged = shizukuReady || rootReady
        val usb = runCatching { QuroAdbDebug.usbDebugEnabled(appContext) }.getOrNull()
        return TerminalPrivilegeEntry(
            key = KEY_ADB,
            title = "ADB / 无线调试",
            status = when {
                privileged -> "已具备特权通道（可静默执行 ADB）"
                usb == true -> "USB 调试已开启"
                else -> "未开启无线/USB 调试"
            },
            available = privileged,
            detail = "本机以 ADB 客户端身份控制设备 / 被电脑 adb connect 接管",
        )
    }

    private fun lsposedEntry(): TerminalPrivilegeEntry {
        val installed = runCatching { QuroLSPosed.isInstalled(appContext) }.getOrDefault(false)
        val inScope = runCatching { QuroLSPosed.isAppInScope(appContext) }.getOrDefault(false)
        return TerminalPrivilegeEntry(
            key = KEY_LSPOSED,
            title = "LSPosed",
            status = when {
                !installed -> "未安装 LSPosed / Xposed 框架"
                inScope -> "已安装并纳入作用域"
                else -> "已安装（未纳入作用域）"
            },
            available = inScope,
            detail = "可选：更深的系统钩子（本应用走自有管线，无需 Xposed 也能运行）",
        )
    }

    private fun storageEntry(): TerminalPrivilegeEntry {
        val ok = sharedStorageAccessible()
        return TerminalPrivilegeEntry(
            key = KEY_STORAGE,
            title = "共享存储",
            status = if (ok) "已授权（/sdcard 可挂载）" else "未授权所有文件访问",
            available = ok,
            detail = "授权后在终端内挂载 /sdcard，访问照片/下载等公共目录",
        )
    }

    // ── 授权入口 ──

    private fun requestRoot(activity: Activity) {
        // Root 无系统引导页；调用 isRootAvailable() 会触发 su 授权框（Magisk/KernelSU 弹窗）。
        QuroRootGateway.invalidateCache()
        Toast.makeText(activity, "正在请求 Root…请在弹出的授权框中选择允许", Toast.LENGTH_SHORT).show()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { QuroRootGateway.isRootAvailable() } }
            withContext(Dispatchers.Main) {
                val ok = QuroRootGateway.cachedRootAvailable() == true
                Toast.makeText(activity, if (ok) "Root 授权成功 ✓" else "未获取 Root（已拒绝或设备未 Root）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestShizuku(activity: Activity) {
        if (!QuroShizukuPkg.isInstalled(activity)) {
            // 未安装：跳商店（无商店则官网）
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setData(Uri.parse("market://details?id=${QuroShizukuPkg.storePackage()}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setData(Uri.parse(QuroShizukuPkg.HOMEPAGE))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            return
        }
        if (QuroShizuku.isReady) {
            Toast.makeText(activity, "Shizuku 已授权 ✓", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "正在请求 Shizuku 授权…", Toast.LENGTH_SHORT).show()
        // Shizuku 未运行则先拉起管理器；Binder 就绪后再 requestPermission。
        if (!QuroShizuku.isAlive) {
            openShizukuManager(activity)
            Toast.makeText(activity, "请先在 Shizuku 应用中启动服务，再回到本页点「请求授权」", Toast.LENGTH_LONG).show()
            return
        }
        val listener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _req, grant ->
            if (grant == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(activity, "Shizuku 授权成功 ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
        runCatching {
            QuroShizuku.requestPermission(activity, SHIZUKU_REQUEST_CODE, listener)
        }.onFailure {
            openShizukuManager(activity)
        }
    }

    private fun requestAdb(activity: Activity) {
        // hasPrivilegedChannel() 可能触发 root 探测（阻塞），放 IO 线程，结果回主线程。
        scope.launch {
            val privileged = withContext(Dispatchers.IO) {
                runCatching { QuroAdbDebug.hasPrivilegedChannel() }.getOrDefault(false)
            }
            if (privileged) {
                Toast.makeText(activity, "已具备特权通道，可直接在终端执行 ADB shell", Toast.LENGTH_SHORT).show()
            } else {
                QuroAdbDebug.openWirelessDebugging(activity)
            }
        }
    }

    private fun requestLsposed(activity: Activity) {
        val mgr = QuroLSPosed.installedManagers(activity).firstOrNull()
        if (mgr == null) {
            Toast.makeText(activity, "未检测到 LSPosed/Xposed 管理器（本应用无需 Xposed 也能运行）", Toast.LENGTH_LONG).show()
            return
        }
        val launch = runCatching { activity.packageManager.getLaunchIntentForPackage(mgr.first) }.getOrNull()
        if (launch != null) {
            runCatching { activity.startActivity(launch) }
        } else {
            Toast.makeText(activity, "请手动打开 ${mgr.second}，在「模块」中启用 Zorv AI 并勾选本应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestStorage(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${activity.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                openAppSettings(activity)
            }
        } else {
            openAppSettings(activity)
        }
    }

    // ── 内部工具 ──

    private fun sharedStorageAccessible(): Boolean {
        val accessible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            @Suppress("DEPRECATION")
            (appContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) ||
            @Suppress("DEPRECATION")
            (appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        if (!accessible) return false
        val f = Environment.getExternalStorageDirectory()
        return f != null && f.canRead()
    }

    private fun openShizukuManager(ctx: Context) {
        val pkg = QuroShizukuPkg.installed(ctx)
        if (pkg == null) return
        val permIntent = Intent(QuroShizukuPkg.Action.REQUEST_PERMISSION).setPackage(pkg)
        val resolved = runCatching { permIntent.resolveActivity(ctx.packageManager) }.getOrNull()
        if (resolved != null) {
            runCatching { ctx.startActivity(permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            runCatching {
                ctx.startActivity(
                    Intent(QuroShizukuPkg.Action.MAIN_ACTIVITY).setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
