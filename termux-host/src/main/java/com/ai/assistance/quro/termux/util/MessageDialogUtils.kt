package com.ai.assistance.quro.termux.util

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface

/**
 * 消息对话框工具（对齐 Termux MessageDialogUtils）。
 */
object MessageDialogUtils {

    @JvmStatic
    fun showMessage(
        context: Context,
        title: String,
        message: String,
        positiveText: String,
        positiveListener: DialogInterface.OnClickListener,
        negativeText: String?,
        negativeListener: DialogInterface.OnClickListener?,
        neutralText: String?
    ) {
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
        if (positiveText.isNotEmpty()) builder.setPositiveButton(positiveText, positiveListener)
        if (negativeText != null) builder.setNegativeButton(negativeText, negativeListener)
        if (neutralText != null) builder.setNeutralButton(neutralText, null)
        builder.show()
    }

    @JvmStatic
    fun showMessage(context: Context, title: String, message: String) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}