package com.ai.assistance.quro.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 视频播放事件总线：把「在应用内打开视频播放器」的请求从工具 / 文件选择器桥接到 ChatScreen。
 * 百分百开源（AOSP，基于框架 VideoView / MediaPlayer），无任何第三方播放库依赖。
 */
object QuroVideoLauncher {
    data class VideoRequest(val uri: String, val title: String)

    private val _event = MutableStateFlow<VideoRequest?>(null)
    val event: StateFlow<VideoRequest?> = _event

    /** 请求打开应用内视频播放器（uri 为 content:// 或文件路径或 http(s) 链接）。 */
    fun open(uri: String, title: String = "") {
        if (uri.isBlank()) return
        _event.value = VideoRequest(uri, title)
    }

    /** 消费当前事件（ChatScreen 在打开播放器后调用，避免重组重复触发）。 */
    fun consume() {
        _event.value = null
    }
}
