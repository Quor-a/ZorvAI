package com.ai.assistance.quro.termux.app

import android.util.Log
import android.view.Gravity
import android.view.View
import androidx.annotation.NonNull
import androidx.drawerlayout.widget.DrawerLayout
import com.ai.assistance.quro.vt.ExtraKeyButton
import com.ai.assistance.quro.vt.ExtraKeysConstants
import com.ai.assistance.quro.vt.ExtraKeysInfo
import com.ai.assistance.quro.vt.QuroTerminalProperties
import com.ai.assistance.quro.vt.TerminalExtraKeys
import com.ai.assistance.quro.vt.TerminalView
import com.google.android.material.button.MaterialButton

/**
 * 增强终端额外按键客户端（移植自 Termux `TermuxTerminalExtraKeys`）。
 *
 * 在 [TerminalExtraKeys] 基础上增加 Termux 特殊按键处理：
 * - KEYBOARD：切换软键盘
 * - DRAWER：开关会话列表抽屉
 * - PASTE：粘贴剪贴板文本
 * - SCROLL：切换自动滚动
 */
class TermuxTerminalExtraKeys(
    private val mActivity: TermuxHostActivity,
    @NonNull terminalView: TerminalView,
    private val mTermuxTerminalViewClient: TermuxTerminalViewClient?,
    private val mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient?
) : TerminalExtraKeys(terminalView) {

    private val LOG_TAG = "TermuxTerminalExtraKeys"
    private var mExtraKeysInfo: ExtraKeysInfo? = null

    /** 从属性加载额外按键配置。 */
    fun setExtraKeys() {
        mExtraKeysInfo = null
        runCatching {
            val properties = mActivity.properties
            val extraKeys = properties.getPropertyValue(
                QuroTerminalProperties.KEY_EXTRA_KEYS,
                QuroTerminalProperties.DEFAULT_VALUE_EXTRA_KEYS
            ) ?: QuroTerminalProperties.DEFAULT_VALUE_EXTRA_KEYS
            val extraKeysStyle = properties.getPropertyValue(
                QuroTerminalProperties.KEY_EXTRA_KEYS_STYLE,
                QuroTerminalProperties.DEFAULT_VALUE_EXTRA_KEYS_STYLE
            ) ?: QuroTerminalProperties.DEFAULT_VALUE_EXTRA_KEYS_STYLE
            ExtraKeysInfo(
                extraKeys,
                extraKeysStyle,
                ExtraKeysConstants.CONTROL_CHARS_ALIASES
            )
        }.onSuccess {
            mExtraKeysInfo = it
        }.onFailure { e ->
            Log.e(LOG_TAG, "无法加载 extra-keys 属性", e)
            // 回退默认键
            mExtraKeysInfo = runCatching {
                ExtraKeysInfo(
                    "[[\"ESC\",\"/\",\"HOME\",\"UP\",\"END\",\"PGUP\"],[\"TAB\",\"CTRL\",\"ALT\",\"LEFT\",\"DOWN\",\"RIGHT\",\"PGDN\"]]",
                    "default",
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES
                )
            }.getOrNull()
        }
    }

    fun getExtraKeysInfo(): ExtraKeysInfo? = mExtraKeysInfo

    override fun onExtraKeyButtonClick(view: View, buttonInfo: ExtraKeyButton, button: MaterialButton) {
        val key = buttonInfo.getKey()
        when (key) {
            "KEYBOARD" -> {
                mTermuxTerminalViewClient?.onToggleSoftKeyboardRequest()
            }
            "DRAWER" -> {
                val drawerLayout: DrawerLayout = mTermuxTerminalViewClient?.getActivity()?.drawer
                    ?: return
                if (drawerLayout.isDrawerOpen(Gravity.LEFT)) {
                    drawerLayout.closeDrawer(Gravity.LEFT)
                } else {
                    drawerLayout.openDrawer(Gravity.LEFT)
                }
            }
            "PASTE" -> {
                mTermuxTerminalSessionActivityClient?.onPasteTextFromClipboard(null)
            }
            "SCROLL" -> {
                val terminalView = mTermuxTerminalViewClient?.getActivity()?.terminalView
                if (terminalView != null && terminalView.mEmulator != null) {
                    terminalView.mEmulator.toggleAutoScrollDisabled()
                }
            }
            else -> super.onExtraKeyButtonClick(view, buttonInfo, button)
        }
    }
}