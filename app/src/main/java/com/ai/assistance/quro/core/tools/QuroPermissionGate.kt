package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 运行时权限请求网关（原创）。
 * 工具运行在 Application Context 上，无法自行弹出系统授权框；
 * 由承载对话的 Activity 注入 [QuroPermissionRequester] 实现，
 * 引擎在派发需要危险权限的工具前，先通过网关确保权限到位。
 */
interface QuroPermissionRequester {
    /** 确保 [permissions] 全部已授予；缺失则弹系统授权框并挂起等待结果。返回是否全部已授权。 */
    suspend fun ensure(permissions: List<String>): Boolean
}

object QuroPermissionHolder {
    /** 由 Activity 在 onCreate 注入、onDestroy 清空。 */
    var requester: QuroPermissionRequester? = null

    /** 在任意 Context 上检查权限是否已授予（无需 Activity）。 */
    fun isGranted(context: Context, permissions: List<String>): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
