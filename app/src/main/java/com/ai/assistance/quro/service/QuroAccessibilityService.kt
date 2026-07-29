package com.ai.assistance.quro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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

    /**
     * 把文字填入当前界面第一个可编辑框，供「粘贴键盘」调用。
     * 优先 ACTION_SET_TEXT（直接写入，无需焦点），失败回退：复制到剪贴板后 ACTION_PASTE。
     * 注意：这是通用辅助能力，不针对任何特定 App，不自动发送、不含任何绕过风控逻辑。
     */
    fun performPaste(text: String): String {
        val root = rootInActiveWindow ?: return "⚠️ 无法获取窗口根节点（APP 是否在前台？）"
        val target = findEditable(root) ?: return "❌ 未找到输入框：请先在目标 App 点一下要填的输入框"
        return try {
            val arg = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arg)) {
                "✅ 已填入输入框"
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("quro", text))
                if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) "✅ 已粘贴" else "❌ 输入失败"
            }
        } catch (e: Exception) {
            "❌ 输入失败：${e.message}"
        }
    }

    private fun findEditable(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount.coerceAtMost(40)) {
            val child = root.getChild(i) ?: continue
            val found = findEditable(child, depth + 1)
            if (found != null) return found
        }
        return null
    }
}
