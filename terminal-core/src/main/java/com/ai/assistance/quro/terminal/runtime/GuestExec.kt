package com.ai.assistance.quro.terminal.runtime

/**
 * Ubuntu（proot）环境的可用性分级。
 *
 * 只描述「rootfs 是否可用」这一件事，不掺任何 UI 语义；
 * 由调用方决定是提示、自动修复，还是引导用户去「环境配置」。
 */
enum class GuestEnvStatus {
    /** rootfs 已解压、/bin/bash 存在 —— 可直接在 guest 内执行命令。 */
    READY,

    /**
     * 正在解压安装（rootfs 内存在 .extracting 标记）。
     *
     * 🔴 2026-10-21 用户要求：「不应该限制时间，而是解压检测完了没有」。
     * 有了这个状态，UI 就能明确显示「正在解压…」而不是让用户盯着黑屏猜；
     * 会话初始化也可以按状态轮询，而不是固定超时后放弃（那会把还在解压的
     * rootfs 判成失败，反而制造半截残废）。
     */
    INSTALLING,

    /** rootfs 还没解压，但安装包还在本地 —— 可以自动完成初始化（耗时数分钟）。 */
    NEEDS_INSTALL,

    /** 既没有 rootfs 也没有安装包 —— 无法自动修复，需要用户走「环境配置」重新拉取。 */
    ASSET_MISSING,
}

/**
 * guest 一次性执行的返回结果。
 *
 * 关键点：**是否完成以「回执文件」为准，而不是以进程退出为准**。
 * 命令在 guest 内派生守护进程（sshd / Xvnc）时，proot 进程可能不及时退出，
 * 用它当完成信号会导致界面永久卡在「安装中」。
 */
data class GuestExecResult(
    /** 是否拿到了回执文件（即脚本真的跑到了收尾并写出退出码）。 */
    val completed: Boolean,
    /** guest 内脚本的真实退出码；未拿到回执时为 -1。 */
    val code: Int,
    /** 捕获到的全部输出（优先取 guest 侧落盘的日志文件，回退到 PTY 流）。 */
    val output: String,
    /** 是否因为超时被中断。 */
    val timedOut: Boolean,
    /** 通道自身的异常（连通道都没起来时才有值，如原生库缺失）。 */
    val error: String? = null,
) {
    val ok: Boolean get() = completed && code == 0 && error == null

    /** 取输出末尾若干行，供界面「日志」区展示。 */
    fun tailLines(max: Int): List<String> =
        output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(max)
}
