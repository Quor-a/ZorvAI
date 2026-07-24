package com.ai.assistance.quro.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 音乐播放器事件总线：把「打开全屏音乐播放器」的请求从工具 / 卡片桥接到 ChatScreen。
 * 百分百开源（AOSP），无第三方播放库依赖。
 */
object QuroMusicLauncher {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    fun request() { _open.value = true }
    fun consume() { _open.value = false }
}
