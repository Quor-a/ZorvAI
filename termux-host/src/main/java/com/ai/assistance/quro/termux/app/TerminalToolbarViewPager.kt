package com.ai.assistance.quro.termux.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.annotation.NonNull
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.ai.assistance.quro.termux.R
import com.ai.assistance.quro.vt.ExtraKeysView
import com.ai.assistance.quro.vt.TerminalSession

/**
 * 终端工具栏 ViewPager（移植自 Termux `TerminalToolbarViewPager`）。
 *
 * 两页：
 * - 第 0 页：额外按键栏（ExtraKeysView）
 * - 第 1 页：文本输入框（EditText），回车发送到当前会话
 */
class TerminalToolbarViewPager {

    /** 工具栏页面适配器。 */
    class PageAdapter(
        private val mActivity: TermuxHostActivity,
        private var mSavedTextInput: String?
    ) : PagerAdapter() {

        override fun getCount(): Int = 2

        override fun isViewFromObject(@NonNull view: View, @NonNull item: Any): Boolean = view === item

        @NonNull
        override fun instantiateItem(@NonNull collection: ViewGroup, position: Int): Any {
            val inflater = LayoutInflater.from(mActivity)
            val layout: View
            if (position == 0) {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false)
                val extraKeysView = layout as ExtraKeysView
                extraKeysView.setExtraKeysViewClient(mActivity.termuxTerminalExtraKeys)
                extraKeysView.setButtonTextAllCaps(mActivity.properties.shouldExtraKeysTextBeAllCaps())
                mActivity.extraKeysView = extraKeysView
                extraKeysView.reload(
                    mActivity.termuxTerminalExtraKeys.getExtraKeysInfo(),
                    mActivity.terminalToolbarDefaultHeight
                )

                // 全屏修正：软键盘弹出时保证额外按键可见
                if (mActivity.properties.isUsingFullScreen() && mActivity.properties.isUsingFullScreenWorkAround()) {
                    FullScreenWorkAround.apply(mActivity)
                }
            } else {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false)
                val editText = layout.findViewById<EditText>(R.id.terminal_toolbar_text_input)

                if (mSavedTextInput != null) {
                    editText.setText(mSavedTextInput)
                    mSavedTextInput = null
                }

                editText.setOnEditorActionListener { _, _, _ ->
                    val session: TerminalSession? = mActivity.currentSession
                    if (session != null) {
                        if (session.isRunning()) {
                            var textToSend = editText.text.toString()
                            if (textToSend.isEmpty()) textToSend = "\r"
                            session.write(textToSend)
                        } else {
                            mActivity.termuxTerminalSessionClient.removeFinishedSession(session)
                        }
                        editText.setText("")
                    }
                    true
                }
            }
            collection.addView(layout)
            return layout
        }

        override fun destroyItem(@NonNull collection: ViewGroup, position: Int, @NonNull view: Any) {
            collection.removeView(view as View)
        }
    }

    /** 页面切换监听：切到额外按键页时终端获得焦点，切到文本输入页时输入框获得焦点。 */
    class OnPageChangeListener(
        private val mActivity: TermuxHostActivity,
        private val mTerminalToolbarViewPager: ViewPager
    ) : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            if (position == 0) {
                mActivity.terminalView.requestFocus()
            } else {
                val editText = mTerminalToolbarViewPager.findViewById<EditText>(R.id.terminal_toolbar_text_input)
                editText?.requestFocus()
            }
        }
    }
}