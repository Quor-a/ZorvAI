package com.ai.assistance.quro.termux.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.Log
import android.view.ContextMenu
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager.widget.ViewPager
import com.ai.assistance.quro.termux.R
import com.ai.assistance.quro.termux.shell.QuroTerminalSession
import com.ai.assistance.quro.termux.settings.TermuxAppSharedPreferences
import com.ai.assistance.quro.termux.settings.TermuxAppSharedProperties
import com.ai.assistance.quro.termux.util.ActivityUtils
import com.ai.assistance.quro.termux.util.KeyboardUtils
import com.ai.assistance.quro.termux.util.Logger
import com.ai.assistance.quro.termux.util.NightMode
import com.ai.assistance.quro.termux.util.ShareUtils
import com.ai.assistance.quro.termux.util.TermuxConstants
import com.ai.assistance.quro.termux.util.TextInputDialogUtils
import com.ai.assistance.quro.termux.util.ThemeUtils
import com.ai.assistance.quro.vt.ExtraKeysView
import com.ai.assistance.quro.vt.TerminalSession
import com.ai.assistance.quro.vt.TerminalView

/**
 * 增强终端宿主 Activity（移植自 Termux `TermuxActivity`，去品牌化精简版）。
 *
 * 完整宿主体验：
 * - 绑定 [TermuxHostService]，展示/管理 proot 终端会话
 * - 终端视图 + 额外按键工具栏 + 文本输入工具栏（ViewPager）
 * - 会话列表抽屉（新建/切换/重命名/删除）
 * - 上下文菜单（分享转录/选中文本/重置/杀会话/保持屏幕常亮/设置）
 * - 快捷键（Ctrl+Alt+组合键）
 * - 全屏 + 软键盘管理
 */
class TermuxHostActivity : AppCompatActivity(),
    TermuxHostService.TermuxTerminalSessionActivityClient {

    private val LOG_TAG = "TermuxHostActivity"

    // ==================== UI 组件 ====================
    private lateinit var mTerminalView: TerminalView
    private lateinit var mDrawer: DrawerLayout
    private lateinit var mTerminalToolbarViewPager: ViewPager
    private var mExtraKeysView: ExtraKeysView? = null
    private lateinit var mTermuxSessionsListView: ListView

    // ==================== 服务绑定 ====================
    private var mTermuxService: TermuxHostService? = null
    private var mBoundService = false
    private val mServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TermuxHostService.LocalBinder
            mTermuxService = binder.service
            mBoundService = true
            onServiceConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mTermuxService = null
            mBoundService = false
        }
    }

    // ==================== 客户端 ====================
    private lateinit var mTermuxTerminalViewClient: TermuxTerminalViewClient
    private lateinit var mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient
    private lateinit var mTermuxTerminalExtraKeys: TermuxTerminalExtraKeys
    private lateinit var mSessionsListViewController: TermuxSessionsListViewController

    // ==================== 状态 ====================
    private var mIsActivityRecreated = false
    private var mIsOnResumeAfterOnCreate = false
    private var mTerminalToolbarDefaultHeight: Float = 0f
    private var mNavBarHeight: Int = 0
    private var mCurrentSessionHandle: String? = null
    private var mShowTerminalToolbar = true

    /** 根视图底部 space 视图 id（供 RootView 查找）。 */
    val bottomSpaceViewId: Int = R.id.activity_termux_bottom_space_view

    // ==================== 偏好/属性 ====================
    private lateinit var mPreferences: TermuxAppSharedPreferences
    private lateinit var mProperties: TermuxAppSharedProperties

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题在 super.onCreate 之前设置
        mIsActivityRecreated = savedInstanceState != null

        TermuxAppSharedPreferences.build(this, false)
        TermuxAppSharedProperties.getProperties(this)?.let {
            // 应用夜间模式
            applyNightMode(it.getNightMode())
        }

        super.onCreate(savedInstanceState)

        mPreferences = TermuxAppSharedPreferences.getInstance()!!
        mProperties = TermuxAppSharedProperties.rebuild(this)

        setContentView(R.layout.activity_termux_host)

        // 关联根视图
        val rootView = findViewById<TermuxHostActivityRootView>(R.id.activity_termux_root_view)
        rootView.setActivity(this)

        // 初始化 UI
        mTerminalView = findViewById(R.id.terminal_view)
        mDrawer = findViewById(R.id.drawer_layout)
        mTerminalToolbarViewPager = findViewById(R.id.terminal_toolbar_view_pager)
        mTermuxSessionsListView = findViewById(R.id.terminal_sessions_list)

        mNavBarHeight = run {
            val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        }

        // 初始化客户端
        mTermuxTerminalSessionActivityClient = TermuxTerminalSessionActivityClient(this)
        mTermuxTerminalViewClient = TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient)
        mTermuxTerminalExtraKeys = TermuxTerminalExtraKeys(this, mTerminalView, mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient)

        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient)

        // 初始化会话列表
        mSessionsListViewController = TermuxSessionsListViewController(this, ArrayList())
        mTermuxSessionsListView.adapter = mSessionsListViewController
        mTermuxSessionsListView.setOnItemClickListener(mSessionsListViewController)
        mTermuxSessionsListView.setOnItemLongClickListener(mSessionsListViewController)

        // 初始化工具栏 ViewPager
        mTerminalToolbarDefaultHeight = calculateToolbarHeight()
        mTerminalToolbarViewPager.adapter = TerminalToolbarViewPager.PageAdapter(this, null)
        mTerminalToolbarViewPager.addOnPageChangeListener(
            TerminalToolbarViewPager.OnPageChangeListener(this, mTerminalToolbarViewPager)
        )

        // 绑定服务
        val serviceIntent = Intent(this, TermuxHostService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, mServiceConnection, BIND_AUTO_CREATE)

        // 客户端 onCreate
        mTermuxTerminalViewClient.onCreate()
        mTermuxTerminalSessionActivityClient.onCreate()

        // 全屏
        if (mProperties.isUsingFullScreen()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        // 工具栏可见性
        mShowTerminalToolbar = mPreferences.shouldShowTerminalToolbar()
        updateTerminalToolbarVisibility()
    }

    override fun onStart() {
        super.onStart()
        mTermuxTerminalViewClient.onStart()
        mTermuxTerminalSessionActivityClient.onStart()
    }

    override fun onResume() {
        super.onResume()
        mIsOnResumeAfterOnCreate = true
        mTermuxTerminalViewClient.onResume()
        mTermuxTerminalSessionActivityClient.onResume()
    }

    override fun onStop() {
        super.onStop()
        mTermuxTerminalViewClient.onStop()
        mTermuxTerminalSessionActivityClient.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mTermuxTerminalSessionActivityClient.onStop()
        if (mBoundService) {
            mTermuxService?.unsetTermuxTerminalSessionActivityClient()
            unbindService(mServiceConnection)
            mBoundService = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 重新应用主题与样式
        reloadActivityStyling()
    }

    // ==================== 服务连接 ====================

    private fun onServiceConnected() {
        val service = mTermuxService ?: return
        service.setTermuxTerminalSessionActivityClient(this)

        // 如果没有会话，自动创建第一个
        if (service.isTermuxSessionsEmpty()) {
            service.addSession(null, false) { session ->
                if (session != null) {
                    mTermuxTerminalSessionActivityClient.setCurrentSession(session)
                }
            }
        } else {
            val current = service.currentSession
            if (current != null) {
                mTermuxTerminalSessionActivityClient.setCurrentSession(current)
            }
        }
    }

    // ==================== 会话客户端回调 ====================

    override fun onSessionListChanged() {
        runOnUiThread {
            val sessions = mTermuxService?.termuxSessions ?: emptyList()
            mSessionsListViewController.clear()
            mSessionsListViewController.addAll(sessions)
            mSessionsListViewController.notifyDataSetChanged()
        }
    }

    override fun onSessionSelected(session: QuroTerminalSession) {
        runOnUiThread {
            mCurrentSessionHandle = session.terminalSession.mHandle
            mTermuxTerminalSessionActivityClient.setCurrentSession(session)
            checkAndScrollToSession(session)
        }
    }

    override fun onSessionFinished(session: QuroTerminalSession) {
        runOnUiThread {
            onSessionListChanged()
        }
    }

    override fun onTextChanged(session: QuroTerminalSession) {
        runOnUiThread {
            if (session.terminalSession === mTerminalView.currentSession) {
                mTerminalView.onScreenUpdated()
            }
        }
    }

    override fun onTitleChanged(session: QuroTerminalSession) {
        runOnUiThread {
            onSessionListChanged()
        }
    }

    // ==================== 公共访问器 ====================

    val terminalView: TerminalView get() = mTerminalView
    val drawer: DrawerLayout get() = mDrawer
    val terminalToolbarViewPager: ViewPager get() = mTerminalToolbarViewPager
    var extraKeysView: ExtraKeysView?
        get() = mExtraKeysView
        set(value) { mExtraKeysView = value }

    /** 当前终端会话（vtlib 层）。 */
    val currentSession: TerminalSession? get() = mTermuxService?.currentSession?.terminalSession

    /** 当前终端会话（宿主层）。 */
    val currentQuroSession: QuroTerminalSession? get() = mTermuxService?.currentSession

    val termuxHostService: TermuxHostService? get() = mTermuxService
    val preferences: TermuxAppSharedPreferences get() = mPreferences
    val properties: TermuxAppSharedProperties get() = mProperties
    val navBarHeight: Int get() = mNavBarHeight
    val terminalToolbarDefaultHeight: Float get() = mTerminalToolbarDefaultHeight
    val termuxTerminalViewClient: TermuxTerminalViewClient get() = mTermuxTerminalViewClient
    val termuxTerminalSessionClient: TermuxTerminalSessionActivityClient get() = mTermuxTerminalSessionActivityClient
    val termuxTerminalExtraKeys: TermuxTerminalExtraKeys get() = mTermuxTerminalExtraKeys

    fun isActivityRecreated(): Boolean = mIsActivityRecreated
    fun isOnResumeAfterOnCreate(): Boolean = mIsOnResumeAfterOnCreate
    fun isTerminalViewSelected(): Boolean = mTerminalView.isSelected

    /** Activity 是否对用户可见（用于跳过不可见时的回调）。 */
    fun isVisible(): Boolean = !isFinishing && !isDestroyed

    // ==================== 内部方法 ====================

    private fun applyNightMode(nightMode: NightMode) {
        NightMode.setAppNightMode(nightMode)
        val uiMode = when (nightMode) {
            NightMode.YES -> Configuration.UI_MODE_NIGHT_YES
            NightMode.NO -> Configuration.UI_MODE_NIGHT_NO
            NightMode.SYSTEM -> Configuration.UI_MODE_NIGHT_UNDEFINED
        }
        resources.configuration.setTo(resources.configuration)
        // 通过 delegate 应用夜间模式
        delegate.localNightMode = when (nightMode) {
            NightMode.YES -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            NightMode.NO -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            NightMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    private fun calculateToolbarHeight(): Float {
        val scale = mProperties.getTerminalToolbarHeightScaleFactor()
        val density = resources.displayMetrics.density
        return 48f * density * scale
    }

    /** 获取当前软键盘高度（用于根视图布局修正）。 */
    fun getKeyboardHeight(): Int {
        val rootView = findViewById<TermuxHostActivityRootView>(R.id.activity_termux_root_view) ?: return 0
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.rootView.height
        return screenHeight - rect.bottom
    }

    private fun updateTerminalToolbarVisibility() {
        mTerminalToolbarViewPager.visibility = if (mShowTerminalToolbar) View.VISIBLE else View.GONE
    }

    fun toggleTerminalToolbar() {
        mShowTerminalToolbar = mPreferences.toogleShowTerminalToolbar()
        updateTerminalToolbarVisibility()
    }

    fun showToast(message: String, longDuration: Boolean) {
        Logger.showToast(this, message, longDuration)
    }

    fun finishActivityIfNotFinishing() {
        if (!isFinishing) finish()
    }

    fun termuxSessionListNotifyUpdated() {
        onSessionListChanged()
    }

    private fun checkAndScrollToSession(session: QuroTerminalSession) {
        val service = mTermuxService ?: return
        val index = service.getIndexOfSession(session.terminalSession)
        if (index < 0) return
        mTermuxSessionsListView.setItemChecked(index, true)
        mTermuxSessionsListView.postDelayed({ mTermuxSessionsListView.smoothScrollToPosition(index) }, 1000)
    }

    /** 重新加载属性与样式。 */
    fun reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk()
        mTermuxTerminalViewClient.onReloadProperties()
        mTermuxTerminalExtraKeys.setExtraKeys()
        // 重新加载工具栏（额外按键可能变化）
        mTerminalToolbarViewPager.adapter = TerminalToolbarViewPager.PageAdapter(this, null)
        mTerminalToolbarViewPager.adapter?.notifyDataSetChanged()
    }

    /** 重新加载样式（夜间模式/字体/颜色）。 */
    fun reloadActivityStyling() {
        applyNightMode(mProperties.getNightMode())
        mTermuxTerminalViewClient.onReloadActivityStyling()
        mTermuxTerminalSessionActivityClient.onReloadActivityStyling()
    }

    // ==================== 上下文菜单 ====================

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        menu.setHeaderTitle(R.string.title_context_menu)
        menu.add(Menu.NONE, 0, Menu.NONE, R.string.title_select_url)
        menu.add(Menu.NONE, 1, Menu.NONE, R.string.title_share_transcript)
        menu.add(Menu.NONE, 2, Menu.NONE, R.string.title_share_selected_text)
        menu.add(Menu.NONE, 3, Menu.NONE, R.string.title_reset_terminal)
        menu.add(Menu.NONE, 4, Menu.NONE, R.string.title_kill_process)
        menu.add(Menu.NONE, 5, Menu.NONE, R.string.title_keep_screen_on)
        menu.add(Menu.NONE, 6, Menu.NONE, R.string.title_rename_session)
        menu.add(Menu.NONE, 7, Menu.NONE, R.string.title_settings)
        menu.add(Menu.NONE, 8, Menu.NONE, R.string.title_toggle_toolbar)
        menu.add(Menu.NONE, 9, Menu.NONE, R.string.title_toggle_keyboard)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            0 -> mTermuxTerminalViewClient.showUrlSelection()
            1 -> mTermuxTerminalViewClient.shareSessionTranscript()
            2 -> mTermuxTerminalViewClient.shareSelectedText()
            3 -> resetTerminalSession()
            4 -> killProcess()
            5 -> toggleKeepScreenOn()
            6 -> mCurrentSessionHandle?.let { handle ->
                val session = mTermuxService?.getSessionForHandle(handle)
                if (session != null) mTermuxTerminalSessionActivityClient.renameSession(session)
            }
            7 -> openSettings()
            8 -> toggleTerminalToolbar()
            9 -> mTermuxTerminalViewClient.onToggleSoftKeyboardRequest()
        }
        return true
    }

    private fun resetTerminalSession() {
        val session = mTermuxService?.currentSession ?: return
        session.terminalSession.reset()
        mTermuxTerminalSessionActivityClient.onResetTerminalSession()
    }

    private fun killProcess() {
        val session = mTermuxService?.currentSession ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.msg_kill_process)
            .setPositiveButton(android.R.string.yes) { _, _ ->
                session.finishIfRunning()
            }
            .setNegativeButton(android.R.string.no, null)
            .show()
    }

    private fun toggleKeepScreenOn() {
        val newValue = !mPreferences.shouldKeepScreenOn()
        mPreferences.setKeepScreenOn(newValue)
        mTerminalView.setKeepScreenOn(newValue)
    }

    private fun openSettings() {
        // 打开设置 Activity（SettingsActivity）
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    // ==================== 软键盘 ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentSession = mTermuxService?.currentSession?.terminalSession
        if (currentSession != null && mTermuxTerminalViewClient.onKeyDown(keyCode, event!!, currentSession)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (mTermuxTerminalViewClient.onKeyUp(keyCode, event!!)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onBackPressed() {
        // 如果抽屉打开，先关闭抽屉
        if (mDrawer.isDrawerOpen(Gravity.LEFT)) {
            mDrawer.closeDrawers()
            return
        }
        super.onBackPressed()
    }
}