package com.ai.assistance.quro;

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
}
