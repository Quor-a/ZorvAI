package com.ai.assistance.quro.core.vision

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.ai.assistance.quro.service.QuroAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * QuroVisionLoop（原创）：屏幕理解闭环。
 *
 * 复用已授权的 L1 无障碍服务，周期性读取当前屏幕的「无障碍节点树」（UI 结构：
 * 控件类型 / 文本 / 内容描述 / 资源 ID），以纯文本快照形式注入对话的系统提示，
 * 使 AI 能"看懂"当前屏幕在做什么——无需申请 MediaProjection、也无需像素截图权限。
 *
 * 说明：本环境公开 SDK 未暴露 AccessibilityService.takeScreenshot 的 ScreenshotResult 回调类型，
 * 因此不采用像素截图 + VLM 路线，而采用更稳定、更省资源、更保护隐私的「节点树理解」路线。
 * 若目标设备/SDK 暴露 takeScreenshot，可在 captureOnce 中替换为像素截图并走多模态附件。
 */
class QuroVisionLoop(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    sealed class Status {
        object Idle : Status()
        object Running : Status()
        object Capturing : Status()
        object Ready : Status()
        class Error(val msg: String) : Status()
        class Unsupported(val msg: String) : Status()
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _latestSnapshot = MutableStateFlow<String?>(null)
    val latestSnapshot: StateFlow<String?> = _latestSnapshot.asStateFlow()

    private var tick: Job? = null

    fun setEnabled(on: Boolean) {
        if (on == _enabled.value) return
        _enabled.value = on
        if (on) start() else stop()
    }

    private fun start() {
        if (QuroAccessibilityService.instance == null) {
            _status.value = Status.Error("请先在「权限模式」中开启 L1 无障碍服务")
            _enabled.value = false
            return
        }
        _status.value = Status.Running
        scope.launch { captureOnce() }
        tick = scope.launch {
            while (isActive && _enabled.value) {
                captureOnce()
                delay(INTERVAL_MS)
            }
        }
    }

    private fun stop() {
        tick?.cancel()
        tick = null
        _status.value = Status.Idle
    }

    /** 读取一次当前屏幕的无障碍节点树快照。返回是否成功。 */
    suspend fun captureOnce(): Boolean {
        val svc = QuroAccessibilityService.instance ?: run {
            _status.value = Status.Error("L1 无障碍服务未连接")
            return false
        }
        _status.value = Status.Capturing
        return try {
            val root = svc.rootInActiveWindow
            if (root == null) {
                _status.value = Status.Error("无法读取屏幕节点（无障碍服务可能不在前台）")
                return false
            }
            val sb = StringBuilder()
            walk(root, 0, sb, MAX_NODES)
            root.recycle()
            val text = sb.toString().trim()
            if (text.isBlank()) {
                _status.value = Status.Error("当前屏幕无可读节点")
                false
            } else {
                _latestSnapshot.value = text
                _status.value = Status.Ready
                true
            }
        } catch (e: Exception) {
            _status.value = Status.Error(e.message ?: "读取屏幕失败")
            false
        }
    }

    /** 取最近一次屏幕快照（不清除）。 */
    fun consumeLatestSnapshot(): String? {
        val s = _latestSnapshot.value ?: return null
        return if (s.isBlank()) null else s
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder, limit: Int) {
        if (node == null || sb.length > MAX_CHARS || depth > MAX_DEPTH) return
        val indent = "  ".repeat(depth.coerceAtMost(MAX_DEPTH))
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val txt = node.text?.toString()?.take(60) ?: ""
        val desc = node.contentDescription?.toString()?.take(60) ?: ""
        val hint = buildString {
            if (txt.isNotEmpty()) append(" text=\"$txt\"")
            if (desc.isNotEmpty()) append(" desc=\"$desc\"")
        }
        sb.append("$indent<$cls$hint>\n")
        val childCount = node.childCount.coerceAtMost(limit)
        for (i in 0 until childCount) {
            if (sb.length > MAX_CHARS) break
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, sb, limit)
            child.recycle()
        }
    }

    companion object {
        const val INTERVAL_MS = 5000L
        const val MAX_DEPTH = 12
        const val MAX_NODES = 400
        const val MAX_CHARS = 4000
    }
}
