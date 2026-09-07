package com.ai.assistance.quro.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
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
import java.io.ByteArrayOutputStream

/**
 * QuroVisionLoop（原创）：屏幕理解闭环。
 *
 * 两种抓取路径，按优先级自动切换：
 *  1. [ScreenCaptureController]（MediaProjection 媒体投影 / 屏幕捕获 / 录屏投屏）
 *     —— 像素级抓帧，AI 真正"看到"当前屏幕画面（适合 WebView/游戏/复杂图像），
 *     由外部注入 [android.media.projection.MediaProjection] 实例后启用。
 *  2. 无障碍节点树（[QuroAccessibilityService.rootInActiveWindow]）—— 文本快照注入，
 *     不需要 MediaProjection 用户授权，省资源，但只能看到 UI 结构。
 *
 * 默认 [enabled] = true（"现已有的方案改成默开"）。开启后先尝试 MediaProjection 抓帧；
 * 抓不到帧（用户未授权 / MP 未注入 / 抓帧失败）自动 fallback 到无障碍节点树，保证屏幕理解
 * 默开可用，且 MediaProjection 授权后优先使用像素截图。
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

    /** 屏幕捕获控制器（MediaProjection 注入点）。外部 Activity 拿到授权回调后
     *  调用 [attachMediaProjection] → 后续优先用 MP 抓帧。
     *  用全局共享实例：JS 沙盒宿主 API（Tools.Media.screenshot）走同一份投影。 */
    val captureController: ScreenCaptureController = ScreenCaptureController.shared(appContext)

    /** 当前实际生效的抓取模式。true=MediaProjection 像素帧；false=无障碍节点树（fallback）。 */
    private val _useMediaProjection = MutableStateFlow(false)
    val useMediaProjection: StateFlow<Boolean> = _useMediaProjection.asStateFlow()

    /** 默认开（"现已有的方案改成默开"）。MediaProjection 未授权时走 fallback。 */
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _latestSnapshot = MutableStateFlow<String?>(null)
    val latestSnapshot: StateFlow<String?> = _latestSnapshot.asStateFlow()

    /** 最近一次抓到的屏幕像素帧（base64 data URI，便于直接喂给多模态模型）。 */
    private val _latestFrameBase64 = MutableStateFlow<String?>(null)
    val latestFrameBase64: StateFlow<String?> = _latestFrameBase64.asStateFlow()

    /** 最近一次抓到的屏幕像素帧（已落盘的临时文件路径，供 QuroAttachment / toVisionDataUri 复用）。 */
    private val _latestFramePath = MutableStateFlow<String?>(null)
    val latestFramePath: StateFlow<String?> = _latestFramePath.asStateFlow()

    private var tick: Job? = null

    /**
     * 由外部（通常是 ChatScreen 的 ActivityResultLauncher 回调）注入 MediaProjection 实例。
     * 注入后立即启动抓帧；后续 captureOnce 自动优先用 MP。
     */
    fun attachMediaProjection(projection: android.media.projection.MediaProjection) {
        captureController.attach(projection)
        captureController.start()
        _useMediaProjection.value = true
        if (_enabled.value && tick == null) start()
    }

    fun setEnabled(on: Boolean) {
        if (on == _enabled.value) return
        _enabled.value = on
        if (on) start() else stop()
    }

    private fun start() {
        // 检查 MP 是否就绪：captureController 处于 Running 则 MP 路径可用
        val mpReady = captureController.isRunning
        if (!mpReady && QuroAccessibilityService.instance == null) {
            _status.value = Status.Error("请先在「权限模式」中开启 L1 无障碍服务，或授权 MediaProjection 屏幕捕获")
            _enabled.value = false
            return
        }
        _status.value = Status.Running
        _useMediaProjection.value = mpReady
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
        _latestSnapshot.value = null
        _latestFrameBase64.value = null
    }

    /**
     * 读取一次当前屏幕快照。优先 MediaProjection 像素帧（_latestFrameBase64），
     * fallback 到无障碍节点树文本（_latestSnapshot）。
     */
    suspend fun captureOnce(): Boolean {
        _status.value = Status.Capturing
        // 1. 优先 MediaProjection 抓帧（屏幕捕获 / 录屏投屏）
        if (captureController.isRunning) {
            val frame = captureController.captureLatest()
            if (frame != null) {
                val b64 = encodeJpegBase64(frame)
                frame.recycle()
                if (b64 != null) {
                    _latestFrameBase64.value = b64
                    _latestFramePath.value = writeBase64ToFile(b64)
                }
                _useMediaProjection.value = true
                _status.value = Status.Ready
                return true
            }
            // 抓帧失败（极少见，可能 Surface 未就绪）→ 走 fallback
        }
        _useMediaProjection.value = false
        // 2. fallback：无障碍节点树（保留原有"现已有的方案"，默开即可用）
        return captureAccessibility()
    }

    private suspend fun captureAccessibility(): Boolean {
        val svc = QuroAccessibilityService.instance ?: run {
            _status.value = Status.Error("L1 无障碍服务未连接")
            return false
        }
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

    /** 取最近一次屏幕快照（text 形式，fallback 给 system prompt 注入用）。 */
    fun consumeLatestSnapshot(): String? {
        val s = _latestSnapshot.value ?: return null
        return if (s.isBlank()) null else s
    }

    /** 取最近一次屏幕像素帧的 base64 data URI（MP 模式给多模态注入用）。 */
    fun consumeLatestFrameBase64(): String? = _latestFrameBase64.value

    /** 取最近一次屏幕像素帧的临时文件路径（供 QuroAttachment / toVisionDataUri 复用，避免重复 base64 编解码）。 */
    fun consumeLatestFramePath(): String? = _latestFramePath.value

    /** 把 base64 data URI 解码写入 quro_screenshots/ 目录，返回绝对路径；写失败返回 null。 */
    private fun writeBase64ToFile(dataUri: String): String? {
        return try {
            val idx = dataUri.indexOf("base64,")
            if (idx < 0) return null
            val raw = Base64.decode(dataUri.substring(idx + 7), Base64.DEFAULT)
            val dir = java.io.File(appContext.filesDir, "quro_screenshots").apply { mkdirs() }
            // 单文件覆盖写（永远只保留最新一帧，避免堆积）
            val out = java.io.File(dir, "latest.jpg")
            java.io.FileOutputStream(out).use { it.write(raw) }
            out.absolutePath
        } catch (e: Exception) { null }
    }

    /** Bitmap → base64 JPEG data URI（"data:image/jpeg;base64,..."），缩放到最长边 [maxEdge] 控制体积。 */
    private fun encodeJpegBase64(bmp: Bitmap, maxEdge: Int = 1024, quality: Int = 80): String? {
        return try {
            val w = bmp.width
            val h = bmp.height
            val scale = (maxOf(w, h) / maxEdge).coerceAtLeast(1)
            val target = if (scale == 1) bmp else Bitmap.createScaledBitmap(bmp, w / scale, h / scale, true)
            val out = ByteArrayOutputStream()
            target.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (target !== bmp) target.recycle()
            val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$encoded"
        } catch (_: Exception) { null }
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
