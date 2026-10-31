package com.ai.assistance.quro.termux.util

import android.text.TextUtils

/**
 * 数据工具（对齐 Termux DataUtils）。
 */
object DataUtils {
    const val TRANSACTION_SIZE_LIMIT_IN_BYTES = 1024 * 1024 // 1 MB

    @JvmStatic
    fun isNullOrEmpty(text: CharSequence?): Boolean = TextUtils.isEmpty(text)

    @JvmStatic
    fun getTruncatedCommandOutput(
        text: String?,
        byteLimit: Int,
        removeStart: Boolean,
        removeEnd: Boolean,
        addTrailingText: Boolean
    ): String {
        if (text == null) return ""
        if (text.toByteArray(Charsets.UTF_8).size <= byteLimit) return text

        val bytes = text.toByteArray(Charsets.UTF_8)
        val truncatedBytes = bytes.copyOfRange(0, byteLimit)
        var truncated = String(truncatedBytes, Charsets.UTF_8)
        // 避免截断在代理对中间
        while (truncated.isNotEmpty() && Character.isHighSurrogate(truncated.last())) {
            truncated = truncated.dropLast(1)
        }
        if (addTrailingText) truncated += "\n… [输出已截断]"
        return truncated
    }
}