package com.ai.assistance.quro.core.mika.phone.shell

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface

/**
 * 特权侧（shell 身份）能力接口 —— **移植适配点**。
 *
 * 上游 Mika 的 `AndroidScreen` 直接吃 AIDL 的 `IMikaShell`。QuroAI 有自己的
 * `IQuroShellService`（跑在 Shizuku UserService，uid 2000），方法名与返回码不完全一致，
 * 因此这里定义一个同构接口：**方法名、参数、返回码语义与上游 AIDL 完全一致**，
 * 由 `QuroShellPortAdapter`（在 :app）把 `IQuroShellService` 映射过来。
 *
 * 这样 `AndroidScreen` 的动作编排代码可以逐字节保真 —— 换掉的只是注入的实现。
 * 返回码统一见 [ShellCodes]。
 */
interface QuroShellPort {

    // ─────────────── 虚拟屏 ───────────────

    fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int
    fun releaseDisplay(displayId: Int)

    // ─────────────── 任务与启动 ───────────────

    /** 0=已启动 1=用户正在主屏使用该 App（不启动） 2=没有可启动的入口 3=出错 */
    fun startApp(displayId: Int, packageName: String): Int

    /** 冷启动：先结束该 App 的进程再启动；返回码同 [startApp]，另有 4=App 在别的屏还有任务（改为普通启动） */
    fun restartApp(displayId: Int, packageName: String): Int

    fun topTaskId(displayId: Int): Int
    fun moveTask(taskId: Int, displayId: Int): Boolean
    fun displayOfTask(taskId: Int): Int
    fun packageOfTask(taskId: Int): String?
    fun userOfTask(taskId: Int): Int

    /** 该包任务所在的自己那块虚拟屏；不在任何一块上返回 -1 */
    fun displayOfPackage(packageName: String): Int
    fun displayOfPackageInUser(packageName: String, userId: Int): Int

    fun topActivity(displayId: Int): String?

    // ─────────────── 输入注入 ───────────────

    fun injectKey(displayId: Int, keyCode: Int): Boolean
    fun injectCtrlKey(displayId: Int, keyCode: Int): Boolean
    fun injectTap(displayId: Int, x: Int, y: Int): Boolean

    /**
     * 一根手指的一笔触摸：times[i] 毫秒（相对按下）时手指在 (xs[i], ys[i])；
     * 第一个点按下，最后一个点抬起。按时间逐个注入，注完才返回。
     */
    fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean

    // ─────────────── 声音 ───────────────

    fun setAudioMuted(packageName: String, muted: Boolean): Boolean
    fun getAudioMode(packageName: String): String?
    fun setAudioMode(packageName: String, mode: String): Boolean

    // ─────────────── 剪贴板 ───────────────

    /** 0=已设置 1=读不到原剪贴板（未改动） 2=出错 */
    fun setClipText(text: String): Int
    fun restoreClip()
    fun savedClipText(): String?
    fun restoreClipText(original: String, ours: String)

    // ─────────────── 打开链接 / 系统动作 ───────────────

    /** 返回码同 [startApp]，另有 5=没有 App 能打开 6=链接不允许 */
    fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int

    /** 返回码同 [startView]；[asMika] 供接手的页面要求 App 权限时以 App 身份启动 */
    fun startSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int

    /** 返回码同 [startApp]，另有 5=该包没有收文字分享的页面 */
    fun startShare(displayId: Int, packageName: String, text: String): Int

    // ─────────────── 命令 ───────────────

    /** 在该虚拟屏上下文中执行命令；返回 exitCode / output */
    fun runSystemCommand(displayId: Int, command: String): Bundle

    /** 以 shell 身份跑一条不限于某块屏的命令 */
    fun runCommand(command: String): Bundle

    // ─────────────── 用户 / 包 ───────────────

    /** `pm list users` 原样输出 */
    fun listUsers(): String?

    /** `pm list packages --user <userId>` 原样输出 */
    fun listPackages(userId: Int): String?

    fun isSystemPackage(packageName: String): Boolean

    /** 能打开里面 App 的其它用户（分身、工作资料）：users + summary */
    fun appCopyUsers(): Bundle?

    /** 在另一份用户里打开该包；只认 [appCopyUsers] 列出的那几份，别的返回 8 */
    fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int

    // ─────────────── 主屏监视 ───────────────

    /** 从现在起盯主屏 [windowMs]，返回此刻主屏最上面的任务 id（没有为 -1） */
    fun watchMainScreen(windowMs: Int): Int

    /** [sinceUptimeMs] 之后主屏冒到最上面的任务（字段见 MainScreenWatch.toBundle） */
    fun mainArrivals(sinceUptimeMs: Long): Bundle?

    // ─────────────── 设备准备 ───────────────

    fun wakeDevice(): Boolean
    fun dismissDeviceKeyguard(): Boolean
    fun enterDeviceCredential(kind: Int, credential: ByteArray): Boolean

    // ─────────────── 生命周期 ───────────────

    /** [uid, pid] */
    fun identity(): IntArray

    /** 撤掉本次绑定留下的改动（还原剪贴板、解除静音、释放所有虚拟屏），进程不退出 */
    fun releaseAll()

    /** 以 shell 身份连本应用 WebView 的调试套接字 */
    fun openDevtoolsSocket(name: String): ParcelFileDescriptor?
}

/**
 * 启动 / 剪贴板返回码 — 与上游常量**逐值一致**。
 *
 * 这些数字是跨进程契约：特权端与操作层各自编译，值一旦错位，
 * 现象是「App 明明启动了却报没入口」这类难查的错，所以按下表固定。
 */
object ShellCodes {
    const val START_OK = 0
    const val START_IN_USE_ON_MAIN = 1
    const val START_NOT_FOUND = 2
    const val START_ERROR = 3

    /** 冷启动时发现 App 在别的屏还有任务，已改为普通启动 */
    const val START_OK_COLD_IGNORED = 4
    const val START_UNRESOLVED = 5
    const val START_INVALID_LINK = 6
    const val START_IN_USE_BY_OTHER = 7
    const val START_USER_NOT_ALLOWED = 8

    /** appCopyUsers() 返回里的键：能打开该 App 的其它用户编号 */
    const val KEY_COPY_USERS = "users"

    const val CLIP_SET = 0
    const val CLIP_UNREADABLE = 1
    const val CLIP_FAILED = 2
}
