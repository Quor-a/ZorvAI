package com.ai.assistance.quro.core.vision

import android.os.Handler
import android.os.Looper

/**
 * 屏幕捕获（MediaProjection）授权请求的全局闸门。
 *
 * 痛点：原先「看懂屏幕」只在用户**长按**开关时才弹系统授权框，AI 自己无法发起请求，
 * 导致 MediaProjection 像素抓帧几乎永远用不上（AI 只能退化到无障碍节点树）。
 *
 * 解法：
 * 1. 宿主 Activity（ChatScreen）在创建 MediaProjection 的 ActivityResultLauncher 后，
 *    把真正的发起逻辑注册进本对象的 [request] 字段。
 * 2. 任何组件（QuroVisionLoop 自动重试、AI 工具 enable_screen_capture）想发起授权时，
 *    直接调用 [MediaProjectionRequester.request()] 即可——无需持有 Activity 引用。
 * 3. 弹窗必须在主线程触发（ActivityResultLauncher.launch 的硬约束），这里统一兜底切主线程。
 */
object MediaProjectionRequester {

    /** 由宿主 Activity 注入：实际触发系统授权弹窗的 lambda。 */
    @Volatile var request: (() -> Unit)? = null

    /** 是否已经注册了可用的发起器（未注册时调用 [request] 静默无效，避免崩溃）。 */
    val available: Boolean get() = request != null

    /** 请求屏幕捕获授权（弹系统对话框）。非主线程调用自动切主线程。 */
    fun request() {
        val r = request ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r()
        } else {
            Handler(Looper.getMainLooper()).post(r)
        }
    }
}
