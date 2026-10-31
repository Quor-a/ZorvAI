package com.ai.assistance.quro.core.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ai.assistance.quro.IQuroShellService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * AI 专用的后台虚拟屏（移植自 Mika 的 AgentDisplay，去品牌化）。
 *
 * 画面输出到**本进程**的 [ImageReader]，因此截图完全不走无障碍截图接口，
 * 也就不会受「无障碍未开启 / 安全窗口黑屏」影响。
 *
 * 帧处理限速到 ≤10 帧/秒：来帧时若离上次处理不足 100ms 就延后到期再取最新一帧，
 * 期间旧帧由 acquireLatestImage 丢弃，保证既有实时预览又不过度耗电。
 */
class QuroAgentDisplay(
    private val shell: IQuroShellService,
    val width: Int,
    val height: Int,
    dpi: Int,
) {
    private val thread = HandlerThread("quro-agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    /** 特权进程里建出来的屏号。 */
    val displayId: Int

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** 实时画面：限速 ≤10 帧/秒，缩到宽 ≤360（只用于对话页小窗预览）。 */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    /** 带行填充的整帧，宽 = rowStride / pixelStride。 */
    private var fullFrame: Bitmap? = null
    private var hasFrame = false
    private var lastProcessedAt = 0L
    private var pending = false
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previewWidth = minOf(PREVIEW_MAX_WIDTH, width)
    private val previewHeight = maxOf(1, height * previewWidth / width)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        val id = try {
            shell.createDisplay(reader.surface, width, height, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed（检查 Shizuku 是否已授权并运行）")
        }
        displayId = id
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                Canvas(preview).drawBitmap(
                    bmp,
                    Rect(0, 0, width, height),
                    Rect(0, 0, previewWidth, previewHeight),
                    paint,
                )
                _frames.value = preview
            }
        } finally {
            image.close()
        }
    }

    /** 最新一帧的整幅 PNG；还没收到画面时返回 null。 */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** 给识别用的原始尺寸副本，避免 PNG 编解码；调用方负责 recycle。 */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }

    /** 虚拟屏当前的输入面（ImageReader 的 surface）。UI 显示切走后可用它切回来。 */
    val inputSurface: android.view.Surface get() = reader.surface

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        // 在帧线程上收尾，避免与正在处理的帧并发
        handler.post {
            runCatching { reader.close() }
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val PREVIEW_MAX_WIDTH = 360
        const val FRAME_INTERVAL_MS = 100L
    }
}
