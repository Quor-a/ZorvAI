package com.ai.assistance.quro.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 图片查看请求桥（与 [QuroVideoLauncher] 同构）：
 * 深层 UI 事实（活动详情弹层的「查看图片」动作）→ ChatScreen 才能接的线。
 *
 * ChatScreen 在媒体启动事件桥里 collect 本事件，落到全屏图片查看器。
 */
object QuroImageLauncher {
    data class ImageRequest(val path: String, val title: String)

    private val _event = MutableStateFlow<ImageRequest?>(null)
    val event: StateFlow<ImageRequest?> = _event

    /** 请求打开全屏图片查看器（path 为本地文件绝对路径）。 */
    fun open(path: String, title: String = "") {
        if (path.isBlank()) return
        _event.value = ImageRequest(path, title)
    }

    /** 消费当前事件（ChatScreen 在打开查看器后调用，避免重组重复触发）。 */
    fun consume() {
        _event.value = null
    }
}
