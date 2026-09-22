package com.ai.assistance.quro.genui.app.miniapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ai.assistance.quro.R
import com.yuanbao.miniapp.core.MiniAppEngine
import com.yuanbao.miniapp.core.MiniAppView

/**
 * GenUI 小程序宿主：暗色主题适配（顶栏与 GenUI 暖色一致）。
 * 入参：appId（用户目录 filesDir/miniapps/<id>/ 优先，回退内置 assets）。
 */
class GenUiMiniAppActivity : Activity() {

    private var miniAppView: MiniAppView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appId = intent.getStringExtra("appId") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141210.toInt())   // GenUI 暗底
            // Android 15+ 强制 edge-to-edge：内容会画进状态栏/手势条区域，
            // 用 insets 给根布局让位，否则顶栏与系统时钟重叠（v0.26.7 修复）
            setOnApplyWindowInsetsListener { v, insets ->
                val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, sys.top, 0, sys.bottom)
                insets
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1E1B18.toInt())
            setPadding(28, 26, 28, 26)
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            setTextColor(0xFFD9A05B.toInt())
            textSize = 22f
            setPadding(0, 0, 30, 0)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = appId.ifBlank { "小程序" }
            setTextColor(0xFFF2EAD9.toInt())
            textSize = 15f
        }
        bar.addView(back)
        bar.addView(title)
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val container = FrameLayout(this).apply { setBackgroundColor(0xFF141210.toInt()) }
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val view = MiniAppEngine.createResolved(this, appId)
        if (view == null) {
            Toast.makeText(this, "未找到小程序：$appId", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        view.setTitleListener { t -> runOnUiThread { title.text = t } }
        view.setErrorListener { err -> runOnUiThread {
            Toast.makeText(this, err.take(140), Toast.LENGTH_LONG).show()
        } }
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        miniAppView = view
    }

    override fun onDestroy() {
        miniAppView?.let { MiniAppEngine.destroy(it) }
        miniAppView = null
        super.onDestroy()
    }
}
