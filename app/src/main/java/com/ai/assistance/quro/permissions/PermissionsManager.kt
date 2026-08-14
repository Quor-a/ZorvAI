package com.ai.assistance.quro.permissions

import androidx.appcompat.app.AppCompatActivity

/**
 * 统一权限入口：聚合 媒体 / 健康 / 闹钟 三类权限的状态与请求，对外暴露单一 [PermState] 视图。
 *
 * 用法（在 Activity 的 onCreate 中构造，因为 Media/Health 需要 AppCompatActivity 注册契约）：
 * ```
 * val pm = PermissionsManager(this)
 * when (pm.mediaState()) {
 *     PermState.Granted     -> { /* 直接读写媒体 */ }
 *     PermState.NeedRequest -> pm.media.request { granted -> ... }
 *     PermState.NeedSettings-> { /* 引导去应用设置 */ }
 * }
 * lifecycleScope.launch {
 *     when (pm.healthState()) {
 *         PermState.Granted     -> { /* 读/写健康数据 */ }
 *         PermState.NeedRequest -> pm.health.request { granted -> ... }
 *         else                  -> HealthPermissionHelper.openHealthConnectManage(this@XxxActivity)
 *     }
 * }
 * ```
 */
class PermissionsManager(private val activity: AppCompatActivity) {

    val media: MediaPermissionHelper = MediaPermissionHelper(activity)
    val health: HealthPermissionHelper = HealthPermissionHelper(activity)
    val alarm: AlarmPermissionHelper = AlarmPermissionHelper(activity)

    /** 媒体权限状态（委托给 [MediaPermissionHelper.mediaState]，已正确处理"首次 vs 永久拒绝"）。 */
    fun mediaState(): PermState = media.mediaState()

    /** 精确闹钟权限状态。 */
    fun alarmState(): PermState = alarm.alarmState()

    /** 健康权限状态（异步：需查询 Health Connect 已授权集合）。 */
    suspend fun healthState(): PermState =
        if (health.hasAllPermissions()) PermState.Granted else PermState.NeedRequest
}
