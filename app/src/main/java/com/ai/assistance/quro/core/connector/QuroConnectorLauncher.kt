package com.ai.assistance.quro.core.connector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 连接器页全局事件桥（2026-11-06）。
 *
 * 为什么存在：ChatOverlays 方法体逼近 JVM 64KB 上限（两次 Method too large
 * 编译失败都是它），不能往 ChatScreen 加任何「打开连接器」的状态/分支。
 * 所以连接器页的开关状态放在 QuroUtilityOverlays 内部自管理，外部入口
 * （AI 命令、设置项、桌宠动作）一律走本桥 `open()` 通知，与
 * QuroImageLauncher / QuroVideoLauncher 同一模式。
 *
 * 用法：
 * ```
 * QuroConnectorLauncher.open()                       // 任意线程可调
 * // QuroUtilityOverlays 内部 collect QuroConnectorLauncher.event 并 consume()
 * ```
 */
object QuroConnectorLauncher {

    /** 打开请求载荷（保留扩展位：未来可带定位到某个连接器的 id）。 */
    data class OpenRequest(val connectorId: String? = null)

    private val _event = MutableStateFlow<OpenRequest?>(null)

    /** UI 侧 collect 这个流；收到后调 [consume] 防重复响应。 */
    val event: StateFlow<OpenRequest?> = _event

    /** 请求打开连接器页（任意线程可调）。 */
    fun open(connectorId: String? = null) {
        _event.value = OpenRequest(connectorId)
    }

    /** UI 响应后消费，防重组重复打开。 */
    fun consume() {
        _event.value = null
    }
}
