package com.ai.assistance.quro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Quro 无障碍服务（CapOS L1 通道）：
 * 服务连接后注册自身实例，供 CapOS 内核调用 performAction / dispatchGesture 执行界面自动化。
 * 仅实现标准无障碍能力，不收集任何隐私内容。
 */
class QuroAccessibilityService : AccessibilityService() {
    companion object {
        var instance: QuroAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 预留扩展点：界面自动化 / 屏幕读取。当前不收集数据。
    }

    override fun onInterrupt() {
        // 注意：onInterrupt 由系统在「中断无障碍反馈」时调用（正常交互中频繁触发），
        // 并不代表服务已断开/被禁用，因此绝不能在此清空 instance —— 否则实时检测信号会抖动，
        // 表现为「授权已开但软件显示未检测到」。instance 只在服务真正销毁时清空（见 onDestroy）。
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 执行 UI 操作（供 CapOS 内核调用）。 */
    fun performAction(nodeInfo: AccessibilityNodeInfo?, action: Int): Boolean {
        return nodeInfo?.performAction(action) ?: false
    }

    /** 全局手势（例如下滑打开控制中心）。 */
    fun dispatchGesture(gesture: GestureDescription) {
        dispatchGesture(gesture, null, null)
    }
}
