package com.ai.assistance.quro.core.mika.bridge

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.ai.assistance.quro.IQuroShellService
import com.ai.assistance.quro.core.mika.phone.shell.QuroShellPort
import com.ai.assistance.quro.core.mika.phone.shell.ShellCodes
import com.ai.assistance.quro.core.shizuku.QuroShizuku

/**
 * 把 QuroAI 的特权服务（[IQuroShellService]，跑在 Shizuku UserService，uid 2000）
 * 适配成移植过来的 [QuroShellPort]。
 *
 * ## 为什么需要这一层
 *
 * Mika 的 `AndroidScreen`（动作编排 1810 行）直接吃它自己的 AIDL 契约。我们把它的
 * 操作逻辑逐字节搬了过来，只把这一个绑定点换成 [QuroShellPort] —— 方法名、参数、
 * 返回码语义都与上游一致，所以这里就是一层**纯转发**：不做额外判断、不改返回码。
 * 任何「聪明」的加工都会让上游那套「等界面安静 → 指纹核实 → 判断是否被切走」的
 * 逻辑失去意义。
 *
 * 服务未就绪时全部方法走安全默认值（启动类返回 [ShellCodes.START_ERROR]，
 * 查询返回 -1/null，注入返回 false），而不是抛异常 —— 上游对这些默认值都有分支。
 */
class QuroShellPortAdapter(
    private val serviceProvider: () -> IQuroShellService? = { QuroShizuku.acquireShellService() },
) : QuroShellPort {

    private companion object {
        private const val TAG = "QuroShellPortAdapter"
    }

    private fun svc(): IQuroShellService? = try {
        serviceProvider()
    } catch (t: Throwable) {
        Log.w(TAG, "取特权服务失败", t)
        null
    }

    // ─────────────── 虚拟屏 ───────────────

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        svc()?.let { runCatching { it.createDisplay(surface, width, height, dpi) }.getOrDefault(-1) } ?: -1

    override fun releaseDisplay(displayId: Int) {
        svc()?.let { runCatching { it.releaseDisplay(displayId) } }
    }

    // ─────────────── 任务与启动 ───────────────

    override fun startApp(displayId: Int, packageName: String): Int =
        svc()?.let { runCatching { it.startApp(displayId, packageName) }.getOrDefault(ShellCodes.START_ERROR) }
            ?: ShellCodes.START_ERROR

    override fun restartApp(displayId: Int, packageName: String): Int =
        svc()?.let { runCatching { it.restartApp(displayId, packageName) }.getOrDefault(ShellCodes.START_ERROR) }
            ?: ShellCodes.START_ERROR

    override fun topTaskId(displayId: Int): Int =
        svc()?.let { runCatching { it.topTaskId(displayId) }.getOrDefault(-1) } ?: -1

    override fun moveTask(taskId: Int, displayId: Int): Boolean =
        svc()?.let { runCatching { it.moveTask(taskId, displayId) }.getOrDefault(false) } ?: false

    override fun displayOfTask(taskId: Int): Int =
        svc()?.let { runCatching { it.displayOfTask(taskId) }.getOrDefault(-1) } ?: -1

    override fun packageOfTask(taskId: Int): String? =
        svc()?.let { runCatching { it.packageOfTask(taskId) }.getOrNull() }

    override fun userOfTask(taskId: Int): Int =
        svc()?.let { runCatching { it.userOfTask(taskId) }.getOrDefault(-1) } ?: -1

    override fun displayOfPackage(packageName: String): Int =
        svc()?.let { runCatching { it.displayOfPackage(packageName) }.getOrDefault(-1) } ?: -1

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int =
        svc()?.let { runCatching { it.displayOfPackageInUser(packageName, userId) }.getOrDefault(-1) } ?: -1

    override fun topActivity(displayId: Int): String? =
        svc()?.let { runCatching { it.topActivity(displayId) }.getOrNull() }

    // ─────────────── 输入注入 ───────────────

    override fun injectKey(displayId: Int, keyCode: Int): Boolean =
        svc()?.let { runCatching { it.injectKey(displayId, keyCode) }.getOrDefault(false) } ?: false

    override fun injectCtrlKey(displayId: Int, keyCode: Int): Boolean =
        svc()?.let { runCatching { it.injectCtrlKey(displayId, keyCode) }.getOrDefault(false) } ?: false

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        svc()?.let { runCatching { it.injectTap(displayId, x, y) }.getOrDefault(false) } ?: false

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        svc()?.let { runCatching { it.injectStroke(displayId, xs, ys, times) }.getOrDefault(false) } ?: false

    // ─────────────── 声音 ───────────────

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean =
        svc()?.let { runCatching { it.setAudioMuted(packageName, muted) }.getOrDefault(false) } ?: false

    override fun getAudioMode(packageName: String): String? =
        svc()?.let { runCatching { it.getAudioMode(packageName) }.getOrNull() }

    override fun setAudioMode(packageName: String, mode: String): Boolean =
        svc()?.let { runCatching { it.setAudioMode(packageName, mode) }.getOrDefault(false) } ?: false

    // ─────────────── 剪贴板 ───────────────

    override fun setClipText(text: String): Int =
        svc()?.let { runCatching { it.setClipText(text) }.getOrDefault(ShellCodes.CLIP_FAILED) }
            ?: ShellCodes.CLIP_FAILED

    override fun restoreClip() {
        svc()?.let { runCatching { it.restoreClip() } }
    }

    override fun savedClipText(): String? =
        svc()?.let { runCatching { it.savedClipText() }.getOrNull() }

    override fun restoreClipText(original: String, ours: String) {
        svc()?.let { runCatching { it.restoreClipText(original, ours) } }
    }

    // ─────────────── 打开链接 / 系统动作 ───────────────

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int =
        svc()?.let {
            runCatching { it.startView(displayId, action ?: "", uri ?: "", packageName ?: "") }
                .getOrDefault(ShellCodes.START_ERROR)
        } ?: ShellCodes.START_ERROR

    override fun startSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int =
        svc()?.let {
            runCatching { it.startSystemAction(displayId, intent, asMika) }.getOrDefault(ShellCodes.START_ERROR)
        } ?: ShellCodes.START_ERROR

    override fun startShare(displayId: Int, packageName: String, text: String): Int =
        svc()?.let {
            runCatching { it.startShare(displayId, packageName, text) }.getOrDefault(ShellCodes.START_ERROR)
        } ?: ShellCodes.START_ERROR

    // ─────────────── 命令 ───────────────

    override fun runSystemCommand(displayId: Int, command: String): Bundle =
        svc()?.let { runCatching { it.runSystemCommand(displayId, command) }.getOrNull() }
            ?: Bundle().apply {
                putInt("exitCode", -1)
                putString("output", "特权服务未就绪")
            }

    override fun runCommand(command: String): Bundle =
        svc()?.let { runCatching { it.runCommand(command) }.getOrNull() }
            ?: Bundle().apply {
                putInt("exitCode", -1)
                putString("output", "特权服务未就绪")
            }

    // ─────────────── 用户 / 包 ───────────────

    override fun listUsers(): String? = svc()?.let { runCatching { it.listUsers() }.getOrNull() }

    override fun listPackages(userId: Int): String? =
        svc()?.let { runCatching { it.listPackages(userId) }.getOrNull() }

    override fun isSystemPackage(packageName: String): Boolean =
        svc()?.let { runCatching { it.isSystemPackage(packageName) }.getOrDefault(false) } ?: false

    override fun appCopyUsers(): Bundle? = svc()?.let { runCatching { it.appCopyUsers() }.getOrNull() }

    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int =
        svc()?.let {
            runCatching { it.startAppInUser(displayId, packageName, userId, cold) }
                .getOrDefault(ShellCodes.START_ERROR)
        } ?: ShellCodes.START_ERROR

    // ─────────────── 主屏监视 ───────────────

    override fun watchMainScreen(windowMs: Int): Int =
        svc()?.let { runCatching { it.watchMainScreen(windowMs) }.getOrDefault(-1) } ?: -1

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? =
        svc()?.let { runCatching { it.mainArrivals(sinceUptimeMs) }.getOrNull() }

    // ─────────────── 设备准备 ───────────────

    override fun wakeDevice(): Boolean =
        svc()?.let { runCatching { it.wakeDevice() }.getOrDefault(false) } ?: false

    override fun dismissDeviceKeyguard(): Boolean =
        svc()?.let { runCatching { it.dismissDeviceKeyguard() }.getOrDefault(false) } ?: false

    override fun enterDeviceCredential(kind: Int, credential: ByteArray): Boolean =
        svc()?.let { runCatching { it.enterDeviceCredential(kind, credential) }.getOrDefault(false) } ?: false

    // ─────────────── 生命周期 ───────────────

    override fun identity(): IntArray =
        svc()?.let { runCatching { it.identity() }.getOrNull() } ?: intArrayOf(-1, -1)

    override fun releaseAll() {
        svc()?.let { runCatching { it.releaseAll() } }
    }

    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? =
        svc()?.let { runCatching { it.openDevtoolsSocket(name) }.getOrNull() }
}
