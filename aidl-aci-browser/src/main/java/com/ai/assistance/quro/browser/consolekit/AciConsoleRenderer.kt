package com.ai.assistance.quro.browser.consolekit

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.view.View
import org.json.JSONObject

/**
 * 通用 SDUI 渲染器（纯 View 体系，无 Compose / AppCompat 依赖）。
 * 把 [AciConsoleContract.getSnapshot] 返回的快照渲染成原生控件；按钮/输入回传给 onAction。
 * 与任何具体业务无关 —— 任意契约的快照都能渲染，所以第 2/3/N 个 App 的控制台 UI 完全一致。
 */
object AciConsoleRenderer {

    fun render(
        container: ViewGroup,
        snapshot: JSONObject,
        onAction: (action: String, payload: Map<String, String>) -> Unit
    ) {
        container.removeAllViews()
        val comps = snapshot.optJSONArray("components") ?: return
        for (i in 0 until comps.length()) {
            val c = comps.optJSONObject(i) ?: continue
            container.addView(buildWidget(container.context, c, onAction))
        }
    }

    private fun buildWidget(ctx: Context, c: JSONObject, onAction: (String, Map<String, String>) -> Unit): android.view.View {
        return when (c.optString("type")) {
            "heading" -> TextView(ctx).apply {
                text = c.optString("text")
                textSize = 15f
                setTextColor(0xFFF2F5F8.toInt())
                setPadding(4, 10, 4, 4)
            }
            "text" -> TextView(ctx).apply {
                text = c.optString("text")
                textSize = 12f
                setTextColor(0xFFCFE8F0.toInt())
                setPadding(4, 3, 4, 3)
            }
            "card" -> TextView(ctx).apply {
                val title = c.optString("title")
                val body = c.optString("body")
                text = if (title.isNotEmpty()) "$title\n$body" else body
                textSize = 12f
                setTextColor(0xFFCFE8F0.toInt())
                setPadding(10, 8, 10, 8)
                setBackgroundColor(0xFF0A1622.toInt())
            }
            "button" -> Button(ctx).apply {
                text = c.optString("label")
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x00000000)
                setOnClickListener { onAction(c.optString("action"), emptyMap()) }
            }
            "divider" -> View(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(0xFF223344.toInt())
            }
            "spacer" -> Space(ctx).apply { minimumHeight = 10 }
            "input" -> buildInput(ctx, c, onAction)
            "listitem" -> TextView(ctx).apply {
                text = "• ${c.optString("text")}"
                textSize = 11f
                setTextColor(0xFF8FA6B5.toInt())
                setPadding(4, 3, 4, 3)
            }
            else -> TextView(ctx).apply { text = c.optString("text") }
        }
    }

    private fun buildInput(ctx: Context, c: JSONObject, onAction: (String, Map<String, String>) -> Unit): android.view.View {
        val key = c.optString("key")
        val action = c.optString("action")
        return EditText(ctx).apply {
            hint = c.optString("placeholder")
            setText(c.optString("value"))
            textSize = 13f
            setTextColor(0xFF0A0A0A.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(10, 8, 10, 8)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    onAction(action, mapOf(key to text.toString().trim()))
                    true
                } else false
            }
        }
    }
}
