package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** 权限自检助手：缺失则返回友好提示，否则返回 null。 */
internal fun needsPermission(context: Context, vararg perms: String): String? {
    val missing = perms.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    return if (missing.isEmpty()) null else "需要权限：${missing.joinToString()}，请在系统设置中授予后重试。"
}
