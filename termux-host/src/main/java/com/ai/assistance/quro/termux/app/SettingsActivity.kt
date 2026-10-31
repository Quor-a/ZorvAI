package com.ai.assistance.quro.termux.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.ai.assistance.quro.termux.R
import com.ai.assistance.quro.termux.settings.TermuxAppSharedPreferences
import com.ai.assistance.quro.termux.settings.TermuxAppSharedProperties

/**
 * 增强终端设置界面（移植自 Termux `TermuxSettings`，去品牌化精简版）。
 *
 * 使用程序化偏好列表（避免引入全套偏好 XML/datastore 机制）：
 * - 字体大小
 * - 保持屏幕常亮
 * - 显示终端工具栏
 * - 软键盘启用
 * - 终端边缘调整
 * - 夜间模式
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // 使用空偏好层次，程序化添加
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val prefs = TermuxAppSharedPreferences.getInstance() ?: return
            val properties = TermuxAppSharedProperties.getProperties(requireContext()) ?: return

            // 字体大小
            val fontSizePref = findPreference<ListPreference>("font_size") ?: run {
                ListPreference(requireContext()).apply {
                    key = "font_size"
                    title = "字体大小"
                    entries = (6..32).map { "$it" }.toTypedArray()
                    entryValues = (6..32).map { "$it" }.toTypedArray()
                    value = prefs.getFontSize().toString()
                    summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                        "${pref.value} sp"
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        prefs.setFontSize((newValue as String).toInt())
                        true
                    }
                }.also { preferenceScreen.addPreference(it) }
            }

            // 保持屏幕常亮
            val keepScreenOn = SwitchPreferenceCompat(requireContext()).apply {
                key = "keep_screen_on"
                title = "保持屏幕常亮"
                summary = "终端显示期间屏幕不自动关闭"
                isChecked = prefs.shouldKeepScreenOn()
                setOnPreferenceChangeListener { _, newValue ->
                    prefs.setKeepScreenOn(newValue as Boolean)
                    true
                }
            }
            preferenceScreen.addPreference(keepScreenOn)

            // 显示工具栏
            val showToolbar = SwitchPreferenceCompat(requireContext()).apply {
                key = "show_terminal_toolbar"
                title = "显示终端工具栏"
                summary = "显示额外按键与文本输入工具栏"
                isChecked = prefs.shouldShowTerminalToolbar()
                setOnPreferenceChangeListener { _, newValue ->
                    prefs.setShowTerminalToolbar(newValue as Boolean)
                    true
                }
            }
            preferenceScreen.addPreference(showToolbar)

            // 软键盘
            val softKeyboard = SwitchPreferenceCompat(requireContext()).apply {
                key = "soft_keyboard_enabled"
                title = "启用软键盘"
                summary = "启用虚拟键盘输入"
                isChecked = prefs.isSoftKeyboardEnabled()
                setOnPreferenceChangeListener { _, newValue ->
                    prefs.setSoftKeyboardEnabled(newValue as Boolean)
                    true
                }
            }
            preferenceScreen.addPreference(softKeyboard)

            // 夜间模式
            val nightMode = ListPreference(requireContext()).apply {
                key = "night_mode"
                title = "夜间模式"
                entries = arrayOf("跟随系统", "开启", "关闭")
                entryValues = arrayOf("system", "yes", "no")
                value = properties.getNightMode().mode
                summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                    when (pref.value) {
                        "yes" -> "开启"
                        "no" -> "关闭"
                        else -> "跟随系统"
                    }
                }
                setOnPreferenceChangeListener { _, newValue ->
                    // 写入属性文件（termux.properties）
                    val props = properties
                    props.setNightMode(requireContext(), newValue as String)
                    true
                }
            }
            preferenceScreen.addPreference(nightMode)
        }
    }
}