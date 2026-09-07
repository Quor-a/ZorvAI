package com.ai.assistance.quro.core.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 屏幕捕获控制器（原创）：包装 [MediaProjection]（媒体投影/录屏投屏）抓帧能力。
 *
 * 与 [QuroVisionLoop] 的关系：本控制器负责"怎么抓一帧像素"；QuroVisionLoop 负责"抓什么、何时抓、注入对话"。
 * - 启动：本类必须先 [attach] 一份 [MediaProjection]（由 Activity 走
 *   `MediaProjectionManager.createScreenCaptureIntent()` 系统授权流程后回调拿到的实例），
 *   然后 [start] 创建 VirtualDisplay + ImageReader 抓帧循环。
 * - 抓帧：[captureLatest] 返回最近一帧 Bitmap（实时反映当前屏幕）。每次调用产生新 Bitmap，
 *   调用方用完务必 `bitmap.recycle()` 释放。
 * - 关闭：[stop] 释放 VirtualDisplay/ImageReader，但保留 [MediaProjection]（系统约束 token 不可二次获取）。
 *
 * 注意：MediaProjection 申请的屏幕内容包含其他 App 的敏感信息（密码/聊天记录等），
 * 仅在用户明确授权后启用，绝不静默抓帧。
 */
class ScreenCaptureController(
    private val appContext: Context,
) {
    private val tag = "ScreenCapture"

    enum class State { Idle, Starting, Running, Stopped, Error }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 是否已注入 [MediaProjection]（系统授权完成，可 [start]）。 */
    val isAttached: Boolean get() = projection != null

    /** 是否已成功 [start]（即 VirtualDisplay 已就绪、可调用 [captureLatest]）。 */
    val isRunning: Boolean get() = _state.value == State.Running

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    /** 注入系统授权回调里拿到的 [MediaProjection]。后续必须 [start] 才真正开始抓帧。 */
    fun attach(projection: MediaProjection) {
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(tag, "MediaProjection.onStop 回调，外部撤销授权")
                stopInternal(updateState = true)
            }
        }, handler)
    }

    /** 启动抓帧（在 [attach] 之后调用）。多次调用幂等。 */
    fun start() {
        val mp = projection ?: run {
            fail("MediaProjection 未注入，请先走系统授权流程")
            return
        }
        if (_state.value == State.Running) return
        _state.value = State.Starting
        try {
            ensureScreenMetrics()
            if (handlerThread == null) {
                handlerThread = HandlerThread("ScreenCapture").also { it.start() }
                handler = Handler(handlerThread!!.looper)
                // attach 时 handler 可能还是 null，这里在回调里如果已 attach 会自动用上
                projection?.let { p ->
                    // MediaProjection callback 必须在有 handler 时 register；attacher 流程里再做一次
                }
            }
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mp.createVirtualDisplay(
                "QuroScreenCapture",
                screenWidth, screenHeight, screenDensity,
                0 /* VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR */,
                imageReader!!.surface,
                null, handler,
            )
            _state.value = State.Running
            _lastError.value = null
        } catch (e: Exception) {
            Log.e(tag, "start 失败", e)
            fail(e.message ?: "ScreenCapture 启动失败")
        }
    }

    /**
     * 取最近一帧屏幕内容。返回的 Bitmap 需调用方 `recycle()` 释放；
     * 调用频率 ≤ 1Hz 即可（被 QuroVisionLoop INTERVAL_MS 限制）。
     */
    fun captureLatest(): Bitmap? {
        val reader = imageReader ?: return null
        if (_state.value != State.Running) return null
        return try {
            val image = reader.acquireLatestImage() ?: return null
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth
                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                // 裁掉右下行 padding 得到纯屏幕图
                if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    bitmap.recycle()
                    cropped
                } else {
                    bitmap
                }
            } catch (e: Exception) {
                image.close()
                null
            }
        } catch (e: Exception) {
            Log.w(tag, "captureLatest 失败", e)
            null
        }
    }

    /** 停止抓帧（保留 MediaProjection 实例）。调用 [start] 可重启。 */
    fun stop() {
        stopInternal(updateState = true)
    }

    /** 完全释放（不再可用）。 */
    fun release() {
        stopInternal(updateState = true)
        projection = null
        _state.value = State.Idle
    }

    private fun stopInternal(updateState: Boolean) {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
        try {
            handlerThread?.quitSafely()
        } catch (_: Exception) {}
        handlerThread = null
        handler = null
        if (updateState) _state.value = State.Stopped
    }

    private fun ensureScreenMetrics() {
        if (screenWidth > 0 && screenHeight > 0) return
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        Log.i(tag, "screen metrics: ${screenWidth}x${screenHeight} @${screenDensity}dpi")
    }

    private fun fail(msg: String) {
        _lastError.value = msg
        _state.value = State.Error
    }

    companion object {
        /** 全局共享单例：QuroVisionLoop（对话视觉闭环）与 JS 沙盒宿主 API（Tools.Media）
         *  共用同一份 MediaProjection 投影——用户在对话里授权一次，脚本运行时即可抓帧。 */
        @Volatile private var sharedRef: ScreenCaptureController? = null

        @JvmStatic
        fun shared(context: Context): ScreenCaptureController =
            sharedRef ?: synchronized(this) {
                sharedRef ?: ScreenCaptureController(context.applicationContext).also { sharedRef = it }
            }

        /** 构造系统授权 Intent（在 Activity 内 startActivityForResult / launcher.launch）。 */
        fun createConsentIntent(context: Context): Intent {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return mpm.createScreenCaptureIntent()
        }
    }
}