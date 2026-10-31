package com.ai.assistance.quro.core.mika.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.normalize
import com.ai.assistance.quro.core.mika.userscreen.CapturedScreen
import com.ai.assistance.quro.core.mika.userscreen.UserScreen
import com.ai.assistance.quro.core.mika.userscreen.UserScreenText
import com.ai.assistance.quro.core.mika.appvision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * 截下用户此刻在主屏上看着的那一屏,连同前台 App 和屏上的字(见 [UserScreenText]:先读无障碍树,不够用时认画面上的字)。
 * 屏上有防截屏窗口(截图报错,或截出来几乎全黑而屏上有 App 窗口)时不留画面,只记下是哪个 App、截不到。
 * ZorvAI 自己的浮层要不要先藏起来由调用方定(见 [OverlayWindow.hiddenWhile]);读树时浮层、输入法本来就不算。
 */
internal object MainScreenCapture {
    private const val TAG = "ZorvAIScreenCapture"
    private const val JPEG_QUALITY = 88
    /** 认字最多等这么久:超时就只用树里的字。 */
    private const val OCR_WAIT_MS = 4_000L

    sealed interface Result {
        class Captured(val shot: CapturedScreen) : Result
        /** 前台是 ZorvAI 自己:不截。 */
        data object Self : Result
        data object Unavailable : Result
    }

    /** 截图(调用方已把浮层藏好)。读树要在截图的同一时刻:截完马上读。 */
    suspend fun shoot(service: ScreenAccessibility): Pair<ScreenAccessibility.MainShot, ScreenRead?> {
        val shot = service.takeMainScreenshot()
        val read = runCatching { readTree(service) }.getOrNull()
        return shot to read
    }

    /** 截下的画面和树整理成一屏(在后台:编码、认字);[topInset]/[bottomInset] 是状态栏、导航条占掉的像素。 */
    suspend fun build(
        service: ScreenAccessibility, shot: ScreenAccessibility.MainShot, read: ScreenRead?,
        topInset: Int, bottomInset: Int, appLabel: (String) -> String,
    ): Result = withContext(Dispatchers.Default) {
        val pkg = read?.topPackage
        if (pkg == service.packageName) {
            (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
            return@withContext Result.Self
        }
        val now = System.currentTimeMillis()
        val label = pkg?.let(appLabel)
        when (shot) {
            is ScreenAccessibility.MainShot.Failed -> {
                Log.w(TAG, "screenshot failed: ${shot.code}")
                Result.Unavailable
            }
            ScreenAccessibility.MainShot.Secure -> Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
            is ScreenAccessibility.MainShot.Ok -> {
                val bmp = shot.bitmap
                try {
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                    // 安卓 12 以前防截屏的窗口截出来是黑的:屏上有 App 窗口而画面几乎全黑就当作截不到,不发黑图
                    if (read?.anyAppWindow != false && BitmapImageOps.isMostlyBlack(jpeg)) {
                        return@withContext Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
                    }
                    val snap = read?.let { snapshotOf(it, bmp.width, bmp.height) }
                    val ocr = if (snap != null && UserScreenText.needsOcr(snap)) recognize(bmp) else null
                    val (lines, fromOcr) = snap?.let {
                        UserScreenText.lines(it, ocr, top = normalize(topInset, bmp.height), bottom = normalize(bmp.height - bottomInset, bmp.height))
                    } ?: (emptyList<com.ai.assistance.quro.core.mika.userscreen.ScreenLine>() to false)
                    Result.Captured(CapturedScreen(jpeg, UserScreen("", now, pkg, label, lines = lines, fromOcr = fromOcr)))
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /** 主屏上属于 App 的窗口(状态栏、导航栏、输入法、浮层不算)读成元素。 */
    private fun readTree(service: ScreenAccessibility): ScreenRead {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val m = service.resources.displayMetrics
        val size = runCatching { service.overlays().area() }.getOrNull()
        return ScreenReader.read(windows, size?.width ?: m.widthPixels, size?.height ?: m.heightPixels)
    }

    private fun snapshotOf(r: ScreenRead, width: Int, height: Int) = ScreenSnapshot(
        snapshotId = "user-screen", packageName = r.topPackage, elements = r.entries.map { it.element },
        width = width, height = height, hasWindow = r.anyAppWindow, windows = r.windows,
    )

    private suspend fun recognize(bmp: Bitmap) = try {
        withTimeoutOrNull(OCR_WAIT_MS) { PhoneOcr.read(bmp.copy(Bitmap.Config.ARGB_8888, false)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "text recognition failed", e)
        null
    }
}
