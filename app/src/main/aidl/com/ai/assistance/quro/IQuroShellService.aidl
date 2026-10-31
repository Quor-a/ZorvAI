package com.ai.assistance.quro;

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
}

