package com.ai.assistance.quro.genui.app.bridge

import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONObject

/**
 * data-ga 动作执行器 —— 声明式真实交互。
 * 表达式：动作:参数，多段用 | 分隔。
 *   toast:消息            原生 Toast
 *   notify:标题|内容      系统通知
 *   speak:文本            TTS 朗读
 *   copy:文本             写剪贴板
 *   open:URL              系统浏览器
 *   vibrate               震动 300ms
 * 例：<button data-ga="notify:天气提醒|今天有雨">提醒我</button>
 */
object GaActions {
    fun exec(context: Context, expr: String): JSONObject {
        val kind = expr.substringBefore(':').trim().lowercase()
        val rest = expr.substringAfter(':', "").trim()
        return when (kind) {
            "toast" -> { Toast.makeText(context, rest.ifBlank { "完成" }, Toast.LENGTH_SHORT).show(); ok() }
            "notify" -> {
                val title = rest.substringBefore("|").ifBlank { "通知" }
                val body = rest.substringAfter("|", "")
                com.ai.assistance.quro.genui.app.bridge.MoBridgeHost.notifyStatic(context, title, body)
                ok()
            }
            "speak" -> {
                val i = Intent("com.ai.assistance.quro.genui.app.TTS_SPEAK").putExtra("text", rest)
                context.sendBroadcast(i)
                ok()
            }
            "copy" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("gen", rest))
                ok()
            }
            "open" -> {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(rest)))
                ok()
            }
            "vibrate" -> {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(300)
                ok()
            }
            else -> JSONObject().put("ok", false).put("error", "未知动作：$kind（可用 toast/notify/speak/copy/open/vibrate）")
        }
    }

    private fun ok() = JSONObject().put("ok", true)
}
