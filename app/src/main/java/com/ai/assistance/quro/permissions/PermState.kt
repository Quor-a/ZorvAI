package com.ai.assistance.quro.permissions

/**
 * 统一的权限状态机，作为所有权限（媒体 / 健康 / 闹钟）的归一化出口。
 *
 * - [Granted]    已授权，可直接调用对应 API。
 * - [NeedRequest] 需要运行时弹窗请求（用户尚未授权，且系统仍允许弹窗）。
 * - [NeedSettings] 已被永久拒绝（点了"不再询问"）或属于 appop/特殊权限，
 *                  无法运行时弹窗，必须引导用户到系统设置页手动开启。
 *
 * UI 层拿到状态后：Granted→直接干活；NeedRequest→调 helper.request()；
 * NeedSettings→跳对应设置页（媒体/健康/精确闹钟各自的 settings intent）。
 */
sealed class PermState {
    object Granted : PermState()
    object NeedRequest : PermState()
    object NeedSettings : PermState()
}
