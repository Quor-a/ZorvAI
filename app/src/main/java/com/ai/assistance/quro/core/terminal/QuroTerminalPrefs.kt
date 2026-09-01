package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.quro.activity.QuroApplication

/**
 * 终端偏好（持久化，跨进程 / 会话共享）。
 *
 * 不写入公共 Download，不污染诊断目录，仅用应用私有 SharedPreferences。
 */
object QuroTerminalPrefs {
    private const val NAME = "quro_terminal_prefs"
    private const val KEY_USE_PTY = "use_pty"
    private const val KEY_WARN_DESTRUCTIVE = "warn_destructive"

    private val sp: SharedPreferences?
        get() = runCatching { QuroApplication.appCtx?.getSharedPreferences(NAME, Context.MODE_PRIVATE) }.getOrNull()

    /**
     * 真实 PTY 终端（实验）。
     *
     * 默认关闭：避免影响已跑通的管道会话链路。用户可在「设置 → 功能」开启后于真机 A/B 验证；
     * 开启后 shell 挂到伪终端（经 libtermux-terminal 的 createSubprocess），vim / top / python REPL 等可真正交互，
     * SIGINT 也能正常投递。若某机型异常，关闭即回退到管道会话。
     */
    var usePty: Boolean
        get() = sp?.getBoolean(KEY_USE_PTY, false) ?: false
        set(v) { sp?.edit()?.putBoolean(KEY_USE_PTY, v)?.apply() }

    /** 交互终端执行破坏性命令前打印醒目警告（默认开）。 */
    var warnDestructive: Boolean
        get() = sp?.getBoolean(KEY_WARN_DESTRUCTIVE, true) ?: true
        set(v) { sp?.edit()?.putBoolean(KEY_WARN_DESTRUCTIVE, v)?.apply() }
}
