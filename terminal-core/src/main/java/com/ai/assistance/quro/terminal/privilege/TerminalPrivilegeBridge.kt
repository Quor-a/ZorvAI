package com.ai.assistance.quro.terminal.privilege

import android.app.Activity

/**
 * 终端「权限」面板与特权后端之间的桥接接口。
 *
 * ## 为什么存在
 *
 * terminal-core 是独立 Android library，只依赖 compose / ftpserver 等基础库，
 * **不能反向依赖 app 模块**里的 core/privilege、core/shizuku、core/adb 等特权实现。
 * 因此特权能力通过本接口抽象，由 app 侧在启动时注入真实实现（holder 注入模式）。
 *
 * 终端（本模块）只依赖本接口与 [TerminalPrivilegeBridgeHolder]，对 ROOT / Shizuku /
 * ADB / LSPosed 的具体实现零感知。
 */
interface TerminalPrivilegeBridge {

    /**
     * 非阻塞快照：返回当前**已知**状态，不得在调用线程做阻塞探测（su / binder 探测会卡 UI）。
     * 用于面板首次渲染时立即显示。可能为「未验证」，随后由 [probe] 异步刷新为真实状态。
     */
    fun snapshot(): List<TerminalPrivilegeEntry>

    /**
     * 异步探测真实状态（阻塞操作放 IO），完成后回调最新列表（回到主线程）。
     */
    fun probe(onDone: (List<TerminalPrivilegeEntry>) -> Unit)

    /**
     * 请求授权 / 打开对应系统界面。
     *
     * @param activity 用于 Shizuku 系统授权框等需要 Activity 的场景；其它入口用其 context。
     * @param key 见 [TerminalPrivilegeEntry.key]。
     * @param onDone 授权动作发起 / 返回后回调（用于刷新面板状态）。
     */
    fun request(activity: Activity, key: String, onDone: () -> Unit)
}

/**
 * 终端特权桥的进程级 holder。
 *
 * app 侧在 [QuroApplication.onCreate] 注入实现；终端面板通过 [get] 读取。
 * 未注入时 [get] 返回 null，面板据此显示「未接入特权后端」。
 */
object TerminalPrivilegeBridgeHolder {
    @Volatile
    private var bridge: TerminalPrivilegeBridge? = null

    fun set(bridge: TerminalPrivilegeBridge?) {
        this.bridge = bridge
    }

    fun get(): TerminalPrivilegeBridge? = bridge
}
