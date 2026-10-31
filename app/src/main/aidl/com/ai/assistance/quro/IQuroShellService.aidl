package com.ai.assistance.quro;

import android.os.Bundle;

import android.view.Surface;

/**
 * Shizuku UserService AIDL 接口。
 *
 * 通过 [rikka.shizuku.Shizuku.bindUserService] 将此服务绑定到 Shizuku 特权进程（root/shell），
 * 所有 exec() 调用均在特权进程中以高权限 Runtime.exec 执行，
 * 无需反射调用 private API（newProcess），更稳定可靠。
 *
 * ## 为什么需要 writeChunk / fileSize
 *
 * 墨语言原生通道要把工具链（qemu、mo2x、zig…）**复制**一份到 `/data/local/tmp`。
 * 这条路绕不开「App 沙箱内 UID 与 shell UID 不一致」的问题：
 * App 自己写不进 `/data/local/tmp`，而 shell 也读不了 App 的私有目录。
 *
 * 于是只能让特权进程代为落盘。[exec] 只收 String，而 Binder 单次事务约 1MB 上限，
 * 所以 byte[] 分块追加（[writeChunk]）是唯一不依赖任何额外权限的传输通道。
 * 分块大小由调用方定，这里只负责「追加 + 建父目录」。
 *
 * ## 虚拟屏（后台屏）通道（6–14）
 *
 * 参照 Mika（github.com/wangzan101/Mika，Apache-2.0）的后台虚拟屏实现移植：
 * 在 shell 身份进程里用 DisplayManager.createVirtualDisplay 建一块**用户看不见的屏**，
 * 画面输出到 App 进程传进来的 Surface（ImageReader），因此截图完全不走无障碍。
 * 所有注入/启动都只作用于本服务自己建的屏（白名单校验），绝不会打扰用户的主屏。
 *
 * ShellService 架构：通过 AIDL UserService 在 Shizuku 特权进程内执行命令。
 */
import android.app.PendingIntent;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

interface IQuroShellService {
    /** 销毁服务进程（Shizuku 回调）。 */
    void destroy() = 16777114;
    /** 执行 shell 命令，返回合并后的 stdout + stderr。 */
    String exec(String command) = 1;

    /**
     * 以特权身份把字节追加/写入到 path（父目录不存在则创建）。
     *
     * @param path   绝对路径，如 /data/local/tmp/quro-mo/qemu-x86_64-static
     * @param data   本次写入的块（建议 <=512KB，Binder 单次事务有上限）
     * @param append true=追加，false=覆盖重建
     * @return 写完后该文件的累计字节数；失败返回 -1
     */
    int writeChunk(String path, in byte[] data, boolean append) = 2;

    /** 查询远端文件字节数；不存在返回 -1。 */
    long fileSize(String path) = 3;

    /**
     * 带超时的执行。原生通道要跑编译/基准，超时必须能收回进程，
     * 否则 [exec] 的 waitFor() 会把调用线程永久挂住。
     *
     * @return 合并的 stdout + stderr；超时末尾附 "exit=timeout(<ms>ms)"
     */
    String execTimed(String command, long timeoutMs) = 4;

    /** 删除远端文件/目录（rm -f / rm -rf），返回是否成功。 */
    boolean remove(String path) = 5;

    // ═══════════════ 后台虚拟屏（6–14）═══════════════

    /**
     * 建一块后台虚拟屏，画面输出到 surface。
     *
     * @return 新的 displayId；失败返回 -1
     */
    int createDisplay(in Surface surface, int width, int height, int dpi) = 6;

    /** 释放指定虚拟屏（只释放本服务创建的）。 */
    void releaseDisplay(int displayId) = 7;

    /**
     * 把 App 启动到本服务的虚拟屏上。
     *
     * @return 0=已启动 1=用户正在主屏使用该 App（不启动，不打扰主屏）
     *         2=没有可启动的入口 3=出错 4=该 App 正在本服务另一块屏上使用
     */
    int startApp(int displayId, String packageName) = 8;

    /** 在这块屏上点一下 (x, y)。 */
    boolean injectTap(int displayId, int x, int y) = 9;

    /** 在这块屏上按一个键（只放行白名单按键，绝不发电源/音量/Home 之类整机键）。 */
    boolean injectKey(int displayId, int keyCode) = 10;

    /**
     * 在这块屏上按一根手指的一笔：times[i] 毫秒（相对按下）时手指在 (xs[i], ys[i])。
     * 第一个点按下，最后一个点抬起；按时间逐个注入，注完才返回。
     */
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 11;

    /** 这块屏最上面任务的 taskId；没有任务返回 -1。 */
    int topTaskId(int displayId) = 12;

    /** 这块屏最上面 Activity 的完整类名；没有任务返回 null。 */
    String topActivity(int displayId) = 13;

    /** 该 taskId 所在的屏；任务不存在返回 -1。 */
    int displayOfTask(int taskId) = 14;

    /**
     * 开始盯主屏（0 号屏）：记下从现在起 [windowMs] 内主屏最上面换上来的任务。
     *
     * 用途：AI 的动作（启动 App / 点通知 / 走系统选择器）可能让 App 开到**用户主屏**，
     * 后台屏上看不出变化，模型会以为没点到而反复重试。动作前后各盯一次，就能认出来并挪回。
     *
     * @return 此刻主屏最上面的任务 id；主屏没有任务返回 -1
     */
    int watchMainScreen(int windowMs) = 15;

    /**
     * [sinceUptimeMs]（SystemClock.uptimeMillis）之后主屏冒到最上面的任务，按先后排。
     *
     * 字段是平行数组（Bundle 里没有对象列表），键名见 MainScreenWatch 的常量：
     * task / package / user / at / from / sameTask / standard / activity / now / listening。
     * 出错返回 null。
     */
    Bundle mainArrivals(long sinceUptimeMs) = 16;

    /**
     * 把一个任务挪到指定屏（`IActivityTaskManager.moveRootTaskToDisplay`，即 `am display move-stack`）。
     *
     * @return 是否成功；反射不可用时退回 am 命令行
     */
    boolean moveTask(int taskId, int displayId) = 17;

    // ═══════════ 以下为「后台虚拟屏」完整移植所需（对齐上游 43 方法契约）═══════════
    // 说明：这些是 AndroidScreen（动作编排 1810 行）真正调用的宿主能力。
    // 缺一个，移植过来的操作层就编不过、更跑不起来，所以必须补齐。

    /** 冷启动：先结束该 App 进程再启动；返回码同 startApp，另有 4=App 在别的屏还有任务 */
    int restartApp(int displayId, String packageName) = 18;

    /** 任务最上面页面所属的包；任务不存在返回 null */
    String packageOfTask(int taskId) = 19;

    /** 任务所属用户（分身/工作资料）；不存在返回 -1 */
    int userOfTask(int taskId) = 20;

    /** 该包任务所在的自己那块虚拟屏；不在任何一块上返回 -1 */
    int displayOfPackage(String packageName) = 21;

    /** 该包在 userId 那份用户里的任务所在屏；不在返回 -1 */
    int displayOfPackageInUser(String packageName, int userId) = 22;

    /** 往这块屏发 Ctrl+键（只放行 A 全选、V 粘贴） */
    boolean injectCtrlKey(int displayId, int keyCode) = 23;

    /** 该包 PLAY_AUDIO 静音开关 */
    boolean setAudioMuted(String packageName, boolean muted) = 24;

    /** 该包当前 PLAY_AUDIO 模式；读不到返回 null */
    String getAudioMode(String packageName) = 25;

    /** 把 PLAY_AUDIO 设回指定模式 */
    boolean setAudioMode(String packageName, String mode) = 26;

    /** 以 shell 身份设剪贴板（不弹「已复制」浮层）；0=已设置 1=读不到原剪贴板 2=出错 */
    int setClipText(String text) = 27;

    /** 还原 setClipText 之前的剪贴板 */
    void restoreClip() = 28;

    /** setClipText 之前用户剪贴板的文字；没有待还原的返回 null */
    String savedClipText() = 29;

    /** 剪贴板仍是 ours 时换回 original（空串则清空）；已被用户换掉时不动 */
    void restoreClipText(String original, String ours) = 30;

    /** 打开链接/设置页；返回码同 startApp，另有 5=没有 App 能打开 6=链接不允许 */
    int startView(int displayId, String action, String uri, String packageName) = 31;

    /** 系统动作（闹钟/计时器/日程等白名单），启动到这块屏；返回码同 startView */
    int startSystemAction(int displayId, in Intent intent, in PendingIntent asMika) = 32;

    /** 把一段文字分享给该包（ACTION_SEND text/plain），启动到这块屏；返回码同 startApp，另有 5=没有收文字分享的页面 */
    int startShare(int displayId, String packageName, String text) = 33;

    /** 在该虚拟屏上下文中执行命令；Bundle: exitCode(int) / output(String) */
    Bundle runSystemCommand(int displayId, String command) = 34;

    /** 以 shell 身份跑一条不限于某块屏的命令；Bundle: exitCode / output */
    Bundle runCommand(String command) = 35;

    /** `pm list users` 原样输出；出错 null */
    String listUsers() = 36;

    /** `pm list packages --user <userId>` 原样输出；出错 null */
    String listPackages(int userId) = 37;

    /** 该包是系统自带的（含更新过的系统 App）；查不到 false */
    boolean isSystemPackage(String packageName) = 38;

    /** 能打开里面 App 的其它用户（分身/工作资料）：Bundle: users(int[]) / summary(String) */
    Bundle appCopyUsers() = 39;

    /** 在另一份用户里打开该包；只认 appCopyUsers 列出的那几份，别的返回 8 */
    int startAppInUser(int displayId, String packageName, int userId, boolean cold) = 40;

    /** 点亮屏幕 */
    boolean wakeDevice() = 41;

    /** 解除锁屏（无凭据的滑动锁） */
    boolean dismissDeviceKeyguard() = 42;

    /** 输入设备凭据解锁；kind 见上游 CredentialKind */
    boolean enterDeviceCredential(int kind, in byte[] credential) = 43;

    /** 服务所在进程的 [uid, pid] */
    int[] identity() = 44;

    /** 撤掉本次绑定留下的改动（还原剪贴板、解除静音、释放所有屏），进程不退出 */
    void releaseAll() = 45;

    /** 以 shell 身份连本应用 WebView 的调试套接字；名字不合规矩或连不上返回 null */
    ParcelFileDescriptor openDevtoolsSocket(String name) = 46;
}

