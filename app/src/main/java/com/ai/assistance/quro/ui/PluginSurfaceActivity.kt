package com.ai.assistance.quro.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge
import com.ai.assistance.quro.plugin.extension.SurfaceHost

/**
 * 插件界面承载 Activity —— 「插件没有 Activity」这条限制的通用解法。
 *
 * 插件是独立 APK，由宿主 DexClassLoader 加载，但不是系统安装的应用，
 * 因此它自己的 Activity **起不来**（系统 PackageManager 里没有它）。
 * 所以宿主提供这一个 Activity，插件只负责返回一棵 View 树：
 *
 * ```
 * uiSurface("my_panel", "我的面板") { actCtx, host -> LinearLayout(actCtx).apply { ... } }
 * ```
 *
 * 与 KaleidoBox 的 KaleidoActivity 是同一套思路：宿主提供舞台，插件只出布景。
 * 打开方式：`startActivity(PluginSurfaceActivity.intent(ctx, "my_panel"))`，
 * 用户从插件管理面板点开，或由 AI 走 `plugin_surface_open` 工具打开。
 */
class PluginSurfaceActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SURFACE_ID = "plugin_surface_id"
        const val EXTRA_TITLE = "plugin_surface_title"

        fun intent(context: Context, surfaceId: String, title: String? = null) =
            Intent(context, PluginSurfaceActivity::class.java).apply {
                putExtra(EXTRA_SURFACE_ID, surfaceId)
                putExtra(EXTRA_TITLE, title)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private var released = false
    private var surfaceId: String = ""
    private var onRelease: (() -> Unit)? = null
    private var content: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceId = intent?.getStringExtra(EXTRA_SURFACE_ID) ?: ""
        val ext = HostToolBridge.surface(surfaceId)
        if (ext == null) {
            setContentView(messageView("插件界面不存在：$surfaceId\n（插件可能已被卸载或重载）"))
            return
        }

        title = intent?.getStringExtra(EXTRA_TITLE) ?: ext.title.ifBlank { ext.label }

        val host = object : SurfaceHost {
            override val pluginId: String
                get() = HostToolBridge.pluginIdOfSurface(surfaceId) ?: ""
            override fun close() = finish()
            override fun toast(message: String) {
                Toast.makeText(this@PluginSurfaceActivity, message, Toast.LENGTH_SHORT).show()
            }
            override fun runOnUi(block: () -> Unit) = runOnUiThread(block)
        }

        val view = try {
            ext.build(this, host)
        } catch (t: Throwable) {
            android.util.Log.w("PluginSurface", "插件界面构建失败: $surfaceId", t)
            null
        }

        if (view == null) {
            setContentView(messageView("插件界面构建失败：$surfaceId\n${ext.label}"))
            return
        }

        onRelease = ext.onRelease
        content = view
        setContentView(view)

        // 系统返回键 / 返回手势：先让插件界面自己处理（如浏览器返回上一页）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled =
                    (content as? com.ai.assistance.quro.plugin.extension.SurfaceBackHandler)?.onSurfaceBack() == true
                if (!handled) finish()
            }
        })
    }

    override fun onDestroy() {
        // 解绑插件占用的资源（例如把 WebView 从窗口摘下来，避免 Activity 泄漏）
        if (!released) {
            released = true
            runCatching { onRelease?.invoke() }
        }
        // 把插件 View 从容器摘下：它是插件长期持有的实例，不该随 Activity 一起销毁
        runCatching { (content?.parent as? ViewGroup)?.removeView(content) }
        content = null
        super.onDestroy()
    }

    private fun messageView(text: String): View = FrameLayout(this).apply {
        addView(TextView(this@PluginSurfaceActivity).apply {
            this.text = text
            setPadding(48, 96, 48, 48)
        })
    }
}
