package com.ai.assistance.quro.termux.util

/**
 * Termux 宿主常量（去品牌化精简版）。
 */
object TermuxConstants {
    const val TERMUX_PACKAGE_NAME = "com.ai.assistance.quro"

    const val TERMUX_APP_NAME = "ZorvAI 增强终端"

    /** 前台服务通知 ID。 */
    const val TERMUX_APP_NOTIFICATION_ID = 9142

    /** 通知渠道 ID。 */
    const val TERMUX_APP_NOTIFICATION_CHANNEL_ID = "termux_host_sessions"

    /** 打开终端广播（通知插件等外部应用）。 */
    const val BROADCAST_TERMUX_OPENED = "com.ai.assistance.quro.termux.OPENED"

    /** 崩溃通知广播。 */
    const val BROADCAST_APP_CRASH = "com.ai.assistance.quro.termux.APP_CRASH"

    /** 属性快捷键 action 常量。 */
    const val ACTION_SHORTCUT_CREATE_SESSION = "create-session"
    const val ACTION_SHORTCUT_NEXT_SESSION = "next-session"
    const val ACTION_SHORTCUT_PREVIOUS_SESSION = "previous-session"
    const val ACTION_SHORTCUT_RENAME_SESSION = "rename-session"

    object TERMUX_ACTIVITY {
        const val TERMUX_ACTIVITY_NAME = "com.ai.assistance.quro.termux.app.TermuxHostActivity"

        const val ACTION_RELOAD_STYLE = "com.ai.assistance.quro.termux.RELOAD_STYLE"
        const val ACTION_NOTIFY_APP_CRASH = "com.ai.assistance.quro.termux.NOTIFY_APP_CRASH"
        const val ACTION_REQUEST_PERMISSIONS = "com.ai.assistance.quro.termux.REQUEST_PERMISSIONS"

        const val EXTRA_RECREATE_ACTIVITY = "com.ai.assistance.quro.termux.RECREATE_ACTIVITY"
        const val EXTRA_RELOAD_STYLE = "com.ai.assistance.quro.termux.RELOAD_STYLE"
        const val EXTRA_FAILSAFE_SESSION = "com.ai.assistance.quro.termux.FAILSAFE_SESSION"
    }

    object TERMUX_SERVICE {
        const val TERMUX_SERVICE_NAME = "com.ai.assistance.quro.termux.app.TermuxHostService"

        const val ACTION_STOP_SERVICE = "com.ai.assistance.quro.termux.STOP_SERVICE"
        const val ACTION_STOP_SESSION = "com.ai.assistance.quro.termux.STOP_SESSION"
        const val ACTION_WAKE_LOCK = "com.ai.assistance.quro.termux.WAKE_LOCK"
        const val ACTION_WAKE_UNLOCK = "com.ai.assistance.quro.termux.WAKE_UNLOCK"

        const val EXTRA_WAKE_LOCK = "com.ai.assistance.quro.termux.WAKE_LOCK"
    }

    object TERMUX_PROPERTY_CONSTANTS {
        // 属性键（对齐 TermuxPropertyConstants）
        const val KEY_BACK_KEY_BEHAVIOUR = "back-key"
        const val KEY_ENFORCE_CHAR_BASED_INPUT = "enforce-char-based-input"
        const val KEY_USE_CTRL_SPACE_WORKAROUND = "use-ctrl-space-workaround"
        const val KEY_EXTRA_KEYS = "extra-keys"
        const val KEY_EXTRA_KEYS_STYLE = "extra-keys-style"
        const val KEY_FULLSCREEN = "fullscreen"
        const val KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP = "hide-soft-keyboard-on-startup"
        const val KEY_SHOW_TERMINAL_TOOLBAR = "show-terminal-toolbar"
        const val KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE = "terminal-toolbar-height-scale"
        const val KEY_TERMINAL_CURSOR_STYLE = "terminal-cursor-style"
        const val KEY_TERMINAL_CURSOR_BLINK_RATE = "terminal-cursor-blink-rate"
        const val KEY_TERMINAL_MARGIN_HORIZONTAL = "terminal-margin-horizontal"
        const val KEY_TERMINAL_MARGIN_VERTICAL = "terminal-margin-vertical"
        const val KEY_TERMINAL_TRANSCRIPT_ROWS = "terminal-transcript-rows"
        const val KEY_NIGHT_MODE = "night-mode"
        const val KEY_VOLUME_KEYS = "volume-keys"
        const val KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS = "hardware-keyboard-shortcuts"
        const val KEY_OPEN_TERMINAL_TRANSCRIPT_URL_ON_CLICK = "open-terminal-transcript-url-on-click"
        const val KEY_EXTRA_KEYS_TEXT_ALL_CAPS = "extra-keys-text-all-caps"

        // 取值
        const val VALUE_BACK_KEY_BEHAVIOUR_ESCAPE = "escape"
        const val VALUE_BACK_KEY_BEHAVIOUR_BACK = "back"
        const val VALUE_VOLUME_KEYS_VIRTUAL = "virtual"
        const val VALUE_VOLUME_KEYS_VOLUME = "volume"
        const val VALUE_NIGHT_MODE_SYSTEM = "system"
        const val VALUE_NIGHT_MODE_YES = "yes"
        const val VALUE_NIGHT_MODE_NO = "no"

        // 光标样式
        const val VALUE_CURSOR_STYLE_BLOCK = "block"
        const val VALUE_CURSOR_STYLE_UNDERLINE = "underline"
        const val VALUE_CURSOR_STYLE_BAR = "bar"

        // 快捷键映射：属性键 → action
        val MAP_SESSION_SHORTCUTS: Map<String, String> = mapOf(
            "shortcut.create-session" to ACTION_SHORTCUT_CREATE_SESSION,
            "shortcut.next-session" to ACTION_SHORTCUT_NEXT_SESSION,
            "shortcut.previous-session" to ACTION_SHORTCUT_PREVIOUS_SESSION,
            "shortcut.rename-session" to ACTION_SHORTCUT_RENAME_SESSION
        )
    }
}