package com.ai.assistance.quro.vt

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * 终端属性系统（移植自 Termux termux.properties 属性系统，Apache-2.0，已去品牌化）。
 *
 * 覆盖 Termux 属性系统支持的全部属性键与默认值，从 `termux.properties` 文件读取：
 * - boolean 键（isPropertyTrue / isPropertyFalse）
 * - int 键（范围校验 + 默认值）
 * - float 键（范围校验 + 默认值）
 * - string 枚举键（双向映射 + 默认值）
 *
 * 文件路径由宿主注入（默认 `filesDir/.termux/termux.properties`），与上游行为一致：
 * 每次读取都重新加载磁盘，避免长期缓存导致属性修改不生效。
 */
class QuroTerminalProperties(
    context: Context,
    propertiesFile: File? = null,
    private val propertiesList: Set<String> = TERMUX_APP_PROPERTIES_LIST
) {
    private val appContext = context.applicationContext
    private val mPropertiesFile: File? =
        propertiesFile ?: File(context.filesDir, ".termux/termux.properties")

    private val lock = Any()
    private var mProperties: Properties? = null

    /** 从磁盘加载属性文件到内存缓存。 */
    fun loadPropertiesFromDisk() {
        synchronized(lock) {
            mProperties = Properties()
            val file = mPropertiesFile ?: run {
                mProperties = Properties()
                return
            }
            if (!file.exists()) return
            runCatching {
                file.inputStream().use { mProperties?.load(it) }
            }.onFailure {
                android.util.Log.e(TAG, "Failed to load properties from ${file.absolutePath}", it)
                mProperties = Properties()
            }
        }
    }

    private fun ensureLoaded(): Properties {
        synchronized(lock) {
            if (mProperties == null) loadPropertiesFromDisk()
            return mProperties ?: Properties()
        }
    }

    /** 获取原始属性值，未找到返回 null。 */
    fun getProperty(key: String): String? = ensureLoaded().getProperty(key)

    /** 获取属性值，未找到返回默认值。 */
    fun getPropertyValue(key: String, def: String?): String? =
        getProperty(key) ?: def

    /** 判断 boolean 属性是否为 true（忽略大小写）。 */
    fun isPropertyTrue(key: String, def: Boolean = false): Boolean {
        val value = getProperty(key) ?: return def
        return value.equals("true", ignoreCase = true)
    }

    /** 判断 inverted boolean 属性（如 use-black-ui: 值 false 表示启用）。 */
    fun isPropertyFalse(key: String, def: Boolean = false): Boolean {
        val value = getProperty(key) ?: return def
        return value.equals("false", ignoreCase = true)
    }

    /** 获取 int 属性值（范围校验）。 */
    fun getIntProperty(key: String, def: Int, min: Int, max: Int, ignoreErrorIfValueZero: Boolean = false): Int {
        val value = getProperty(key) ?: return def
        val intValue = value.toIntOrNull() ?: run {
            logInvalid(key, value, def.toString())
            return def
        }
        if (intValue in min..max) return intValue
        if (ignoreErrorIfValueZero && intValue == 0) return intValue
        logInvalid(key, value, def.toString())
        return def
    }

    /** 获取 float 属性值（范围校验）。 */
    fun getFloatProperty(key: String, def: Float, min: Float, max: Float, ignoreErrorIfValueZero: Boolean = false): Float {
        val value = getProperty(key) ?: return def
        val floatValue = value.toFloatOrNull() ?: run {
            logInvalid(key, value, def.toString())
            return def
        }
        if (floatValue in min..max) return floatValue
        if (ignoreErrorIfValueZero && floatValue == 0f) return floatValue
        logInvalid(key, value, def.toString())
        return def
    }

    /** 从枚举映射获取字符串值，无效回退默认。 */
    fun getStringProperty(key: String, def: String, validValues: Set<String>): String {
        val value = getProperty(key) ?: return def
        return if (value in validValues) value else {
            logInvalid(key, value, def)
            def
        }
    }

    private fun logInvalid(key: String, value: String, def: String) {
        android.util.Log.e(TAG, "Invalid value \"$value\" for property \"$key\", using default \"$def\"")
    }

    companion object {
        private const val TAG = "QuroTerminalProps"

        // ── 属性键（与 TermuxPropertyConstants 保持一致）──
        const val KEY_DISABLE_FILE_SHARE_RECEIVER = "disable-file-share-receiver"
        const val KEY_DISABLE_FILE_VIEW_RECEIVER = "disable-file-view-receiver"
        const val KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS = "disable-hardware-keyboard-shortcuts"
        const val KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST = "disable-terminal-session-change-toast"
        const val KEY_ENFORCE_CHAR_BASED_INPUT = "enforce-char-based-input"
        const val KEY_EXTRA_KEYS_TEXT_ALL_CAPS = "extra-keys-text-all-caps"
        const val KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP = "hide-soft-keyboard-on-startup"
        const val KEY_RUN_TERMUX_AM_SOCKET_SERVER = "run-termux-am-socket-server"
        const val KEY_TERMINAL_ONCLICK_URL_OPEN = "terminal-onclick-url-open"
        const val KEY_USE_BLACK_UI = "use-black-ui"
        const val KEY_USE_CTRL_SPACE_WORKAROUND = "ctrl-space-workaround"
        const val KEY_USE_FULLSCREEN = "fullscreen"
        const val KEY_USE_FULLSCREEN_WORKAROUND = "use-fullscreen-workaround"
        const val KEY_BELL_BEHAVIOUR = "bell-character"
        const val KEY_TERMINAL_CURSOR_BLINK_RATE = "terminal-cursor-blink-rate"
        const val KEY_TERMINAL_CURSOR_STYLE = "terminal-cursor-style"
        const val KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = "delete-tmpdir-files-older-than-x-days-on-exit"
        const val KEY_TERMINAL_MARGIN_HORIZONTAL = "terminal-margin-horizontal"
        const val KEY_TERMINAL_MARGIN_VERTICAL = "terminal-margin-vertical"
        const val KEY_TERMINAL_TRANSCRIPT_ROWS = "terminal-transcript-rows"
        const val KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = "terminal-toolbar-height"
        const val KEY_SHORTCUT_CREATE_SESSION = "shortcut.create-session"
        const val KEY_SHORTCUT_NEXT_SESSION = "shortcut.next-session"
        const val KEY_SHORTCUT_PREVIOUS_SESSION = "shortcut.previous-session"
        const val KEY_SHORTCUT_RENAME_SESSION = "shortcut.rename-session"
        const val KEY_BACK_KEY_BEHAVIOUR = "back-key"
        const val KEY_DEFAULT_WORKING_DIRECTORY = "default-working-directory"
        const val KEY_EXTRA_KEYS = "extra-keys"
        const val KEY_EXTRA_KEYS_STYLE = "extra-keys-style"
        const val KEY_NIGHT_MODE = "night-mode"
        const val KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = "soft-keyboard-toggle-behaviour"
        const val KEY_VOLUME_KEYS_BEHAVIOUR = "volume-keys"

        // ── 枚举值 ──
        const val VALUE_BELL_BEHAVIOUR_VIBRATE = "vibrate"
        const val VALUE_BELL_BEHAVIOUR_BEEP = "beep"
        const val VALUE_BELL_BEHAVIOUR_IGNORE = "ignore"
        const val DEFAULT_VALUE_BELL_BEHAVIOUR = VALUE_BELL_BEHAVIOUR_VIBRATE

        const val VALUE_TERMINAL_CURSOR_STYLE_BLOCK = "block"
        const val VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = "underline"
        const val VALUE_TERMINAL_CURSOR_STYLE_BAR = "bar"
        const val DEFAULT_VALUE_TERMINAL_CURSOR_STYLE = VALUE_TERMINAL_CURSOR_STYLE_BLOCK

        const val IVALUE_BACK_KEY_BEHAVIOUR_BACK = "back"
        const val IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE = "escape"
        const val DEFAULT_VALUE_BACK_KEY_BEHAVIOUR = IVALUE_BACK_KEY_BEHAVIOUR_BACK

        const val DEFAULT_VALUE_DEFAULT_WORKING_DIRECTORY = "~"

        const val DEFAULT_VALUE_EXTRA_KEYS = "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"
        const val DEFAULT_VALUE_EXTRA_KEYS_STYLE = "default"

        const val IVALUE_NIGHT_MODE_TRUE = "true"
        const val IVALUE_NIGHT_MODE_FALSE = "false"
        const val IVALUE_NIGHT_MODE_SYSTEM = "system"
        const val DEFAULT_VALUE_NIGHT_MODE = IVALUE_NIGHT_MODE_SYSTEM

        const val IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE = "show/hide"
        const val IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE = "enable/disable"
        const val DEFAULT_VALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE

        const val IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL = "virtual"
        const val IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME = "volume"
        const val DEFAULT_VALUE_VOLUME_KEYS_BEHAVIOUR = IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL

        val BELL_BEHAVIOUR_VALUES = setOf(VALUE_BELL_BEHAVIOUR_VIBRATE, VALUE_BELL_BEHAVIOUR_BEEP, VALUE_BELL_BEHAVIOUR_IGNORE)
        val TERMINAL_CURSOR_STYLE_VALUES = setOf(VALUE_TERMINAL_CURSOR_STYLE_BLOCK, VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE, VALUE_TERMINAL_CURSOR_STYLE_BAR)
        val BACK_KEY_BEHAVIOUR_VALUES = setOf(IVALUE_BACK_KEY_BEHAVIOUR_BACK, IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE)
        val NIGHT_MODE_VALUES = setOf(IVALUE_NIGHT_MODE_TRUE, IVALUE_NIGHT_MODE_FALSE, IVALUE_NIGHT_MODE_SYSTEM)
        val SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_VALUES = setOf(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE)
        val VOLUME_KEYS_BEHAVIOUR_VALUES = setOf(IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL, IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME)

        val SESSION_SHORTCUT_KEYS = setOf(KEY_SHORTCUT_CREATE_SESSION, KEY_SHORTCUT_NEXT_SESSION, KEY_SHORTCUT_PREVIOUS_SESSION, KEY_SHORTCUT_RENAME_SESSION)

        /** Termux app 属性集合。 */
        val TERMUX_APP_PROPERTIES_LIST: Set<String> = setOf(
            KEY_DISABLE_FILE_SHARE_RECEIVER,
            KEY_DISABLE_FILE_VIEW_RECEIVER,
            KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
            KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
            KEY_ENFORCE_CHAR_BASED_INPUT,
            KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
            KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
            KEY_RUN_TERMUX_AM_SOCKET_SERVER,
            KEY_TERMINAL_ONCLICK_URL_OPEN,
            KEY_USE_BLACK_UI,
            KEY_USE_CTRL_SPACE_WORKAROUND,
            KEY_USE_FULLSCREEN,
            KEY_USE_FULLSCREEN_WORKAROUND,
            KEY_BELL_BEHAVIOUR,
            KEY_TERMINAL_CURSOR_BLINK_RATE,
            KEY_TERMINAL_CURSOR_STYLE,
            KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
            KEY_TERMINAL_MARGIN_HORIZONTAL,
            KEY_TERMINAL_MARGIN_VERTICAL,
            KEY_TERMINAL_TRANSCRIPT_ROWS,
            KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,
            KEY_SHORTCUT_CREATE_SESSION,
            KEY_SHORTCUT_NEXT_SESSION,
            KEY_SHORTCUT_PREVIOUS_SESSION,
            KEY_SHORTCUT_RENAME_SESSION,
            KEY_BACK_KEY_BEHAVIOUR,
            KEY_DEFAULT_WORKING_DIRECTORY,
            KEY_EXTRA_KEYS,
            KEY_EXTRA_KEYS_STYLE,
            KEY_NIGHT_MODE,
            KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR,
            KEY_VOLUME_KEYS_BEHAVIOUR
        )
    }
}