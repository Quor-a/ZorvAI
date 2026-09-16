package com.zorv.plugin.signcheck

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ai.assistance.quro.plugin.extension.SurfaceHost

/**
 * 签名核验面板。
 *
 * 宿主用通用 Activity 承载插件返回的 View 树，所以这里不写 Activity、不写布局 XML，
 * 纯代码构建（插件用宿主主题，不会出现样式割裂）。
 *
 * 颜色从宿主主题解析（colorBackground / textColorPrimary），亮色与暗色主题下都可读。
 * 每次点击「重新核验」都会重新读一遍 PackageManager，因此改完插件包重装后不用退出界面。
 */
class SignCheckPanel(
    private val actCtx: Context,
    private val host: SurfaceHost,
    private val pluginId: String,
) : ScrollView(actCtx) {

    private val listBox = LinearLayout(actCtx)

    private val colorBg: Int = themeColor(android.R.attr.colorBackground, Color.WHITE)
    private val colorFg: Int = themeColor(android.R.attr.textColorPrimary, Color.BLACK)
    private val colorDim: Int = 0xFF888888.toInt()

    init {
        setBackgroundColor(colorBg)
        isFillViewport = true

        val root = LinearLayout(actCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }

        root.addView(title("签名核验"))
        root.addView(hint("宿主接受插件的唯一条件是证书 SHA-256 相同"))

        root.addView(
            Button(actCtx).apply {
                text = "重新核验"
                setOnClickListener {
                    render()
                    host.toast("已重新核验")
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14); bottomMargin = dp(6) }
        )

        root.addView(listBox)
        addView(root)

        render()
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        listBox.removeAllViews()

        val app = actCtx.applicationContext
        val hostSig = SignCheck.hostSig(app)
        val selfSig = SignCheck.apkAt(app, SignCheck.selfApk(app, pluginId), "本插件")

        // ---- 宿主 ----
        listBox.addView(section("宿主"))
        listBox.addView(row("包名", hostSig.packageName ?: "—"))
        listBox.addView(row("版本", "${hostSig.versionName ?: "—"} (${hostSig.versionCode ?: "—"})"))
        listBox.addView(fingerprintBlock(hostSig.fingerprint))

        // ---- 本插件 ----
        listBox.addView(section("本插件"))
        listBox.addView(row("包名", selfSig.packageName ?: pluginId))
        listBox.addView(row("版本", "${selfSig.versionName ?: "—"} (${selfSig.versionCode ?: "—"})"))
        listBox.addView(fingerprintBlock(selfSig.fingerprint))
        listBox.addView(verdictBanner(SignCheck.verdict(hostSig, selfSig), hostSig.isSameAs(selfSig)))

        // ---- 其他插件 ----
        val others = SignCheck.installedPluginApks(app).filter { it.first != pluginId }
        listBox.addView(section("其他已装插件（${others.size}）"))
        if (others.isEmpty()) {
            listBox.addView(row("", "（无）"))
        } else {
            others.forEach { (id, file) ->
                val sig = SignCheck.apkAt(app, file, id)
                val ok = sig.isSameAs(hostSig)
                listBox.addView(row(
                    if (ok) "一致" else "不一致",
                    "$id\nv${sig.versionName ?: "?"} · ${sig.short}"
                ))
            }
        }

        listBox.addView(
            hint("提示：未签名的包会显示「（未签名）」。\n" +
                "这类包以及用了别的密钥的包，宿主都会以「签名与宿主不一致，拒绝安装」拒绝。")
        )
    }

    // ---------------- 小组件 ----------------

    private fun title(t: String) = TextView(actCtx).apply {
        text = t
        setTextColor(colorFg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun hint(t: String) = TextView(actCtx).apply {
        text = t
        setTextColor(colorDim)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, 0)
    }

    private fun section(t: String) = TextView(actCtx).apply {
        text = t
        setTextColor(colorFg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun row(label: String, value: String) = TextView(actCtx).apply {
        text = if (label.isEmpty()) value else "$label：$value"
        setTextColor(colorFg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(3), 0, dp(3))
    }

    /** 指纹按 ASCII 展示，用等宽字体以便逐段比对 */
    private fun fingerprintBlock(fp: String?) = TextView(actCtx).apply {
        text = fp ?: "（未签名 / 无法解析）"
        setTextColor(colorFg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, dp(2), 0, dp(6))
    }

    /** 结论条：一致=绿、不一致=红（与「涨红跌绿」无关，这里是状态色） */
    private fun verdictBanner(text: String, ok: Boolean) = TextView(actCtx).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setBackgroundColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    // ---------------- 工具 ----------------

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** 从宿主主题解析颜色属性；解析不到时回退，保证任何主题下都可读 */
    private fun themeColor(attr: Int, fallback: Int): Int = try {
        val tv = TypedValue()
        if (actCtx.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) actCtx.resources.getColor(tv.resourceId, actCtx.theme) else tv.data
        } else {
            fallback
        }
    } catch (_: Throwable) {
        fallback
    }

    @Suppress("unused")
    private fun noop(v: View) = v
}
