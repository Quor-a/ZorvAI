package com.ai.assistance.quro;

/**
 * Shizuku UserService AIDL 接口。
 *
 * 通过 [Shizuku.bindUserService] 将此服务绑定到 Shizuku 特权进程（root/shell），
 * 所有 exec() 调用均在特权进程中以高权限 Runtime.exec 执行，
 * 无需反射调用 private API（newProcess），更稳定可靠。
 *
 * 对齐 alian-android (https://github.com/xlb1130/alian-android) 的 ShellService 架构。
 */
interface IQuroShellService {
    /** 销毁服务进程（Shizuku 回调）。 */
    void destroy() = 16777114;
    /** 执行 shell 命令，返回合并后的 stdout + stderr。 */
    String exec(String command) = 1;
}
