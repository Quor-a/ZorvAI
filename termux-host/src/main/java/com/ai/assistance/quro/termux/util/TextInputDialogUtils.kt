package com.ai.assistance.quro.termux.util

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

/**
 * 文本输入对话框工具（对齐 Termux TextInputDialogUtils）。
 */
object TextInputDialogUtils {

    @JvmStatic
    fun textInput(
        context: Context,
        titleResId: Int,
        initialText: String?,
        positiveButtonTextResId: Int,
        positiveListener: (String) -> Unit,
        negativeButtonTextResId: Int,
        negativeListener: (String) -> Unit,
        inputType: Int,
        hintText: String?,
        errorText: String?
    ) {
        textInput(
            context,
            context.getString(titleResId),
            initialText,
            context.getString(positiveButtonTextResId),
            positiveListener,
            if (negativeButtonTextResId != 0) context.getString(negativeButtonTextResId) else null,
            negativeListener,
            inputType,
            hintText,
            errorText
        )
    }

    @JvmStatic
    fun textInput(
        context: Context,
        title: String,
        initialText: String?,
        positiveButtonText: String,
        positiveListener: (String) -> Unit,
        negativeButtonText: String?,
        negativeListener: (String) -> Unit,
        inputType: Int,
        hintText: String?,
        errorText: String?
    ) {
        val input = EditText(context).apply {
            setText(initialText)
            if (inputType != -1) this.inputType = inputType
            hint = hintText
            isSingleLine = true
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(positiveButtonText) { _, _ ->
                val text = input.text?.toString() ?: ""
                positiveListener(text)
            }
        if (negativeButtonText != null) {
            builder.setNegativeButton(negativeButtonText) { _, _ ->
                val text = input.text?.toString() ?: ""
                negativeListener(text)
            }
        }
        builder.show()
    }
}