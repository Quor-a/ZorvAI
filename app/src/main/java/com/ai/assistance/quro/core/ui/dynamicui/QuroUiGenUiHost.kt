package com.ai.assistance.quro.core.ui.dynamicui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.ai.assistance.quro.genui.sdk.interaction.ActionHost
import com.ai.assistance.quro.genui.sdk.interaction.DefaultActionHost
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把嵌入在动态 UI（quro-ui）里的 GenUI 组件交互桥接回 quro-ui 的回调循环。
 *
 * 设计：GenUI SDK 是一套独立的「生成式界面」渲染体系（530+ 原生组件，由 GenUiAgentActivity 作为
 * 独立应用承载）。本类让这套组件可以直接渲染在 quro-ui 面板内，并把组件里的交互（按钮、表单、事件）
 * 回传给主聊天的 AI——从而把「完整 GenUI Agent 的原生组件能力」接进动态 UI 组件。
 *
 * 桥接策略：
 *  - sendMessage / emit → 转成 QuroCallbackAction，交给 onAction（与 quro-ui 普通 callback 同一条管道，
 *    回发成用户消息给主聊天 AI），实现「动态 UI 里渲染的 GenUI 组件可交互、可对话」。
 *  - openUrl / copyToClipboard / showToast → 直接落地本机能力。
 *  - 其余 ActionHost 方法沿用 DefaultActionHost 的空实现（嵌入场景下用不到的导航 / 媒体 / 页控 / 对话框等）。
 */
class QuroUiGenUiHost(
    private val context: Context,
    private val onAction: (QuroUiAction, Map<String, String>) -> Unit,
) : DefaultActionHost() {

    override fun sendMessage(text: String) {
        if (text.isBlank()) return
        onAction(
            QuroCallbackAction(event = "genui_message", data = mapOf("text" to text)),
            emptyMap(),
        )
    }

    override fun emit(name: String, payload: JsonElement) {
        val value = when (payload) {
            is JsonPrimitive -> if (payload.isString) payload.content else payload.toString()
            else -> payload.toString()
        }
        onAction(
            QuroCallbackAction(event = name, data = mapOf("event" to name, "value" to value)),
            emptyMap(),
        )
    }

    override fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun copyToClipboard(text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("genui", text))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    override fun showToast(message: String, duration: String, level: String) {
        Toast.makeText(
            context,
            message,
            if (duration == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }
}
