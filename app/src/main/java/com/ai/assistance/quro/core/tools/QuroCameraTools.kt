package com.ai.assistance.quro.core.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 相机工具：拍照 / 录像 / 取景等能力面。
 *  - [GetCameraCapabilitiesTool]  get_camera_capabilities —— 枚举摄像头数量/朝向/闪光灯/最大分辨率（只读）
 *  - [CaptureVideoTool]           capture_video           —— Camera2 + MediaRecorder 录像 MP4 → MediaStore
 *
 * 说明：`take_photo`（拍照）在 [QuroScreenshotTools.kt] 已有实现（委托系统相机 + Camera2 静默拍照兜底），
 * 本文件只补「能力查询」与「录像」两个此前确实缺失的工具，避免同名重复注册。
 *
 * 实现约定：QuroTool.run 是同步接口（由 QuroToolAdapter 在协程 IO 线程调用），
 * 而 Camera2 的 openCamera/createCaptureSession 都是异步回调，这里用
 * CountDownLatch + 超时等待把它们阻塞成同步语义；HandlerThread 承载所有 Camera2 回调。
 */

/** 相机工具公共基类：统一 CAMERA 权限声明。 */
abstract class CameraQuroTool : QuroTool {
    override val requiredPermissions = listOf(Manifest.permission.CAMERA)
}

/** get_camera_capabilities —— 枚举可用摄像头及其能力（只读，无副作用）。 */
class GetCameraCapabilitiesTool : CameraQuroTool() {
    override val name = "get_camera_capabilities"
    override val description = "枚举设备上的可用摄像头及其能力（前后摄、闪光灯、最大拍照分辨率）。用于回答「有几个摄像头/能不能拍照/分辨率多少」等问题。参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override val readOnly = true

    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.CAMERA)?.let { return it }
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            val arr = JSONArray()
            for (id in ids) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val max = map?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width * it.height }
                    ?.let { "${it.width}x${it.height}" } ?: "unknown"
                arr.put(JSONObject().apply {
                    put("id", id)
                    put("facing", facing)
                    put("flash_available", flash)
                    put("max_resolution", max)
                })
            }
            JSONObject().apply {
                put("cameras", arr)
                put("count", arr.length())
            }.toString()
        } catch (e: Exception) {
            "获取摄像头能力失败：${e.message}"
        }
    }
}

/** capture_video —— Camera2 + MediaRecorder 录像一段 MP4，写入相册。 */
class CaptureVideoTool : CameraQuroTool() {
    override val name = "capture_video"
    override val description = "用后置摄像头录制一段视频（无需预览、后台静默录制），写入系统相册，返回文件路径与时长。参数 {\"duration_sec\":10}（1-60 秒，默认 10）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "duration_sec":{"type":"integer","description":"录制时长（秒，1-60，默认 10）"}
        }
    }"""

    @Suppress("MissingPermission")
    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.CAMERA)?.let { return it }
        val duration = JSONObject(arguments).optInt("duration_sec", 10).coerceIn(1, 60)

        val thread = HandlerThread("QuroVideoCapture").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recorder: MediaRecorder? = null
        var temp: File? = null

        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()
            ?: return "无可用摄像头"

            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(MediaRecorder::class.java)?.maxByOrNull { it.width * it.height }
                ?: return "无法确定录像分辨率"

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            temp = File(context.cacheDir, "VIDEO_$ts.mp4")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder!!.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(size.width, size.height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(10_000_000)
                setOutputFile(temp!!.absolutePath)
                prepare()
            }
            val surface = recorder!!.surface

            device = openCameraBlocking(cm, cameraId, handler)
            session = createSessionBlocking(device!!, listOf(surface), handler)

            val request = device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
            }.build()
            session!!.setRepeatingRequest(request, null, handler)
            recorder!!.start()

            Thread.sleep(duration * 1000L)

            runCatching { recorder!!.stop() }
            recorder!!.release()
            recorder = null

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "VIDEO_$ts.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/zorv-ai")
                }
            }
            val uri = context.contentResolver
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { dest ->
                context.contentResolver.openOutputStream(dest)?.use { os ->
                    temp!!.inputStream().use { it.copyTo(os) }
                }
            }

            JSONObject().apply {
                put("file_path", uri?.toString())
                put("duration_ms", duration * 1000L)
                put("width", size.width)
                put("height", size.height)
            }.toString()
        } catch (e: Exception) {
            "录像失败：${e.message}"
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            thread.quitSafely()
            runCatching { temp?.delete() }
        }
    }
}

// ─── 同步阻塞辅助（Camera2 异步回调 → 同步语义）────────────────────────────

private fun openCameraBlocking(
    cm: CameraManager,
    cameraId: String,
    handler: Handler,
): CameraDevice {
    val latch = CountDownLatch(1)
    val ref = AtomicReference<CameraDevice>()
    val err = AtomicReference<String?>()
    @Suppress("MissingPermission")
    cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            ref.set(camera)
            latch.countDown()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            err.set("摄像头已断开")
            latch.countDown()
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            err.set("摄像头错误：$error")
            latch.countDown()
        }
    }, handler)
    if (!latch.await(10, TimeUnit.SECONDS)) throw RuntimeException("打开摄像头超时")
    err.get()?.let { throw RuntimeException(it) }
    return ref.get() ?: throw RuntimeException("打开摄像头失败")
}

private fun createSessionBlocking(
    device: CameraDevice,
    surfaces: List<android.view.Surface>,
    handler: Handler,
): CameraCaptureSession {
    val latch = CountDownLatch(1)
    val ref = AtomicReference<CameraCaptureSession>()
    val err = AtomicReference<String?>()
    val outputs = surfaces.map { android.hardware.camera2.params.OutputConfiguration(it) }
    val cfg = android.hardware.camera2.params.SessionConfiguration(
        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
        outputs,
        java.util.concurrent.Executors.newSingleThreadExecutor(),
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                ref.set(s)
                latch.countDown()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                err.set("会话配置失败")
                latch.countDown()
            }
        },
    )
    device.createCaptureSession(cfg)
    if (!latch.await(10, TimeUnit.SECONDS)) throw RuntimeException("配置会话超时")
    err.get()?.let { throw RuntimeException(it) }
    return ref.get() ?: throw RuntimeException("配置会话失败")
}
