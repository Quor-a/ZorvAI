package com.ai.assistance.quro.termux.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import com.ai.assistance.quro.termux.R
import com.ai.assistance.quro.termux.shell.QuroTerminalSession
import com.ai.assistance.quro.termux.util.NightMode
import com.ai.assistance.quro.termux.util.ThemeUtils

/**
 * 会话列表抽屉控制器（移植自 Termux `TermuxSessionsListViewController`）。
 *
 * 每行显示：[序号] 会话名 + 标题，运行中粗体、已结束斜体+删除线，
 * 异常退出红色。单击切换会话，长按重命名。
 */
class TermuxSessionsListViewController(
    mActivity: TermuxHostActivity,
    sessionList: List<QuroTerminalSession>
) : ArrayAdapter<QuroTerminalSession>(
    mActivity.applicationContext,
    R.layout.item_terminal_sessions_list,
    sessionList
), AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    private val mActivity: TermuxHostActivity = mActivity

    private val boldSpan = StyleSpan(Typeface.BOLD)
    private val italicSpan = StyleSpan(Typeface.ITALIC)

    @SuppressLint("SetTextI18n")
    @NonNull
    override fun getView(position: Int, convertView: View?, @NonNull parent: ViewGroup): View {
        var sessionRowView = convertView
        if (sessionRowView == null) {
            val inflater = mActivity.layoutInflater
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false)
        }

        val sessionTitleView = sessionRowView.findViewById<TextView>(R.id.session_title)

        val quroSession = getItem(position) ?: return sessionRowView
        val sessionAtRow = quroSession.terminalSession

        val shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode())

        if (shouldEnableDarkTheme) {
            sessionTitleView.background = ContextCompat.getDrawable(mActivity, R.drawable.session_background_black_selected)
        }

        val name = quroSession.name
        val sessionTitle = sessionAtRow.getTitle()

        val numberPart = "[" + (position + 1) + "] "
        val sessionNamePart = if (TextUtils.isEmpty(name)) "" else name
        val sessionTitlePart = if (TextUtils.isEmpty(sessionTitle)) "" else (if (sessionNamePart.isEmpty()) "" else "\n") + sessionTitle

        val fullSessionTitle = numberPart + sessionNamePart + sessionTitlePart
        val fullSessionTitleStyled = SpannableString(fullSessionTitle)
        fullSessionTitleStyled.setSpan(boldSpan, 0, numberPart.length + sessionNamePart.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        fullSessionTitleStyled.setSpan(italicSpan, numberPart.length + sessionNamePart.length, fullSessionTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        sessionTitleView.text = fullSessionTitleStyled

        val sessionRunning = sessionAtRow.isRunning()

        if (sessionRunning) {
            sessionTitleView.paintFlags = sessionTitleView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        } else {
            sessionTitleView.paintFlags = sessionTitleView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        val defaultColor = if (shouldEnableDarkTheme) Color.WHITE else Color.BLACK
        val color = if (sessionRunning || sessionAtRow.getExitStatus() == 0) defaultColor else Color.RED
        sessionTitleView.setTextColor(color)
        return sessionRowView
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val clickedSession = getItem(position) ?: return
        mActivity.termuxTerminalSessionClient.setCurrentSession(clickedSession)
        mActivity.drawer.closeDrawers()
    }

    override fun onItemLongClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long): Boolean {
        val selectedSession = getItem(position) ?: return true
        mActivity.termuxTerminalSessionClient.renameSession(selectedSession)
        return true
    }
}