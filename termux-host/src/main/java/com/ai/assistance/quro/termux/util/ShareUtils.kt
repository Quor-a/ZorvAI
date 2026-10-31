package com.ai.assistance.quro.termux.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * 分享工具（对齐 Termux ShareUtils）。
 */
object ShareUtils {

    @JvmStatic
    fun shareText(context: Context, title: String, text: String, chooserTitle: String) {
        if (text.isEmpty()) return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(sendIntent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @JvmStatic
    fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage("ShareUtils", "openUrl failed", e)
        }
    }

    @JvmStatic
    fun copyTextToClipboard(context: Context, text: String, toastMessage: String) {
        if (text.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        Logger.showToast(context, toastMessage, true)
    }

    @JvmStatic
    fun getTextStringFromClipboardIfSet(context: Context, consume: Boolean): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
        if (consume) {
            cm.setPrimaryClip(ClipData.newPlainText("text", ""))
        }
        return text
    }
}