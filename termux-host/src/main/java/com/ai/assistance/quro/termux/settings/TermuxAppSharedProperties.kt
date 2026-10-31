package com.ai.assistance.quro.termux.settings

import android.content.Context
import com.ai.assistance.quro.vt.QuroTerminalProperties
import com.ai.assistance.quro.termux.util.TermuxConstants
import java.io.File

/**
 * 终端宿主属性（对齐 TermuxAppSharedProperties）。
 *
 * 底层复用 vtlib 的 [QuroTerminalProperties]（已完整移植 termux.properties 属性系统），
 * 增加宿主级便捷 getter。
 */
class TermuxAppSharedProperties private constructor(
    private val mProperties: QuroTerminalProperties
) {

    companion object {
        @Volatile
        private var instance: TermuxAppSharedProperties? = null

        @JvmStatic
        fun getProperties(context: Context): TermuxAppSharedProperties {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        // 使用当前 application context 构建
                        val appContext = context.applicationContext
                        instance = TermuxAppSharedProperties(
                            QuroTerminalProperties(
                                appContext,
                                File(appContext.filesDir, ".termux/termux.properties")
                            )
                        )
                        instance!!.loadTermuxPropertiesFromDisk()
                    }
                }
            }
            return instance!!
        }

        /** 用指定 Context 重建（确保路径正确）。 */
        @JvmStatic
        fun rebuild(context: Context): TermuxAppSharedProperties {
            val props = QuroTerminalProperties(
                context.applicationContext,
                File(context.filesDir, ".termux/termux.properties")
            )
            props.loadPropertiesFromDisk()
            synchronized(this) {
                instance = TermuxAppSharedProperties(props)
            }
            return instance!!
        }
    }

    fun loadTermuxPropertiesFromDisk() = mProperties.loadPropertiesFromDisk()

    fun getProperty(key: String): String? = mProperties.getProperty(key)

    fun getPropertyValue(key: String, def: String?): String? = mProperties.getPropertyValue(key, def)

    fun isPropertyTrue(key: String, def: Boolean = false): Boolean = mProperties.isPropertyTrue(key, def)

    fun isPropertyFalse(key: String, def: Boolean = false): Boolean = mProperties.isPropertyFalse(key, def)

    fun getIntProperty(key: String, def: Int, min: Int, max: Int, ignoreErrorIfValueZero: Boolean = false): Int =
        mProperties.getIntProperty(key, def, min, max, ignoreErrorIfValueZero)

    fun getFloatProperty(key: String, def: Float, min: Float, max: Float, ignoreErrorIfValueZero: Boolean = false): Float =
        mProperties.getFloatProperty(key, def, min, max, ignoreErrorIfValueZero)

    fun getStringProperty(key: String, def: String, validValues: Set<String>): String =
        mProperties.getStringProperty(key, def, validValues)

    /** 读取内部属性值（快捷键码点等）。 */
    fun getInternalPropertyValue(key: String, defaultValue: Int?): Int? {
        val value = mProperties.getProperty(key) ?: return defaultValue
        // 支持单个字符（如 "c"）或数字码点（如 "99"）
        if (value.length == 1) return value[0].code
        return value.toIntOrNull() ?: defaultValue
    }

    // ── 宿主级便捷 getter ──

    fun isBackKeyTheEscapeKey(): Boolean =
        getStringProperty(
            QuroTerminalProperties.KEY_BACK_KEY_BEHAVIOUR,
            QuroTerminalProperties.DEFAULT_VALUE_BACK_KEY_BEHAVIOUR,
            QuroTerminalProperties.BACK_KEY_BEHAVIOUR_VALUES
        ) == QuroTerminalProperties.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE

    fun isEnforcingCharBasedInput(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_ENFORCE_CHAR_BASED_INPUT)

    fun isUsingCtrlSpaceWorkaround(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_USE_CTRL_SPACE_WORKAROUND)

    fun shouldOpenTerminalTranscriptURLOnClick(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_TERMINAL_ONCLICK_URL_OPEN, true)

    fun areHardwareKeyboardShortcutsDisabled(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS)

    fun areVirtualVolumeKeysDisabled(): Boolean {
        val value = getStringProperty(
            QuroTerminalProperties.KEY_VOLUME_KEYS_BEHAVIOUR,
            QuroTerminalProperties.DEFAULT_VALUE_VOLUME_KEYS_BEHAVIOUR,
            QuroTerminalProperties.VOLUME_KEYS_BEHAVIOUR_VALUES
        )
        return value == QuroTerminalProperties.IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME
    }

    fun getNightMode(): com.ai.assistance.quro.termux.util.NightMode {
        val value = getStringProperty(
            QuroTerminalProperties.KEY_NIGHT_MODE,
            QuroTerminalProperties.DEFAULT_VALUE_NIGHT_MODE,
            QuroTerminalProperties.NIGHT_MODE_VALUES
        )
        return com.ai.assistance.quro.termux.util.NightMode.modeOf(value)
    }

    fun isUsingFullScreen(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_USE_FULLSCREEN)

    fun getTerminalMarginHorizontal(): Int =
        getIntProperty(QuroTerminalProperties.KEY_TERMINAL_MARGIN_HORIZONTAL, 3, 0, 20)

    fun getTerminalMarginVertical(): Int =
        getIntProperty(QuroTerminalProperties.KEY_TERMINAL_MARGIN_VERTICAL, 0, 0, 20)

    fun getTerminalCursorBlinkRate(): Int =
        getIntProperty(QuroTerminalProperties.KEY_TERMINAL_CURSOR_BLINK_RATE, 0, 0, 5000)

    fun shouldExtraKeysTextBeAllCaps(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_EXTRA_KEYS_TEXT_ALL_CAPS)

    fun getTerminalToolbarHeightScaleFactor(): Float =
        getFloatProperty(QuroTerminalProperties.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, 1f, 0.5f, 3f)

    fun shouldSoftKeyboardBeHiddenOnStartup(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP)

    fun shouldEnableDisableSoftKeyboardOnToggle(): Boolean =
        getStringProperty(
            QuroTerminalProperties.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR,
            "enable-disable",
            setOf("enable-disable", "show-hide")
        ) == "enable-disable"

    fun getTerminalCursorStyle(): String =
        getStringProperty(
            QuroTerminalProperties.KEY_TERMINAL_CURSOR_STYLE,
            QuroTerminalProperties.DEFAULT_VALUE_TERMINAL_CURSOR_STYLE,
            QuroTerminalProperties.TERMINAL_CURSOR_STYLE_VALUES
        )

    fun getExtraKeys(): String? = mProperties.getProperty(QuroTerminalProperties.KEY_EXTRA_KEYS)

    fun getExtraKeysStyle(): String =
        getStringProperty(
            QuroTerminalProperties.KEY_EXTRA_KEYS_STYLE,
            QuroTerminalProperties.DEFAULT_VALUE_EXTRA_KEYS_STYLE,
            setOf("default", "arrows-only", "arrows-all", "all", "none")
        )

    /** 会话快捷键动作映射（属性键 → action Int 值）。 */
    fun getSessionShortcutAction(propertyKey: String): Int? =
        TermuxConstants.TERMUX_PROPERTY_CONSTANTS.MAP_SESSION_SHORTCUTS[propertyKey]

    /** 是否禁用会话切换 toast。 */
    fun areTerminalSessionChangeToastsDisabled(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST)

    /** 铃声行为。 */
    fun getBellBehaviour(): String =
        getStringProperty(
            QuroTerminalProperties.KEY_BELL_BEHAVIOUR,
            QuroTerminalProperties.DEFAULT_VALUE_BELL_BEHAVIOUR,
            QuroTerminalProperties.BELL_BEHAVIOUR_VALUES
        )

    /** 是否使用全屏 workaround（软键盘弹出时修正布局）。 */
    fun isUsingFullScreenWorkAround(): Boolean =
        mProperties.isPropertyTrue(QuroTerminalProperties.KEY_USE_FULLSCREEN_WORKAROUND, true)

    /** 写入夜间模式到 termux.properties。 */
    fun setNightMode(context: Context, mode: String) {
        val propertiesFile = File(context.filesDir, ".termux/termux.properties")
        propertiesFile.parentFile?.mkdirs()
        val props = java.util.Properties()
        if (propertiesFile.exists()) {
            propertiesFile.inputStream().use { props.load(it) }
        }
        props.setProperty(QuroTerminalProperties.KEY_NIGHT_MODE, mode)
        propertiesFile.outputStream().use { props.store(it, "ZorvAI 增强终端属性") }
        mProperties.loadPropertiesFromDisk()
    }
}