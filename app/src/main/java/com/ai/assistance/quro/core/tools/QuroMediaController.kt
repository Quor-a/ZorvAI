package com.ai.assistance.quro.core.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局媒体播放状态（音乐）。由 [QuroMediaService] 写入，聊天界面的「播放卡片」与全屏播放器订阅。
 * 使用 Android 框架层 MediaPlayer（AOSP, Apache-2.0）——百分百开源，无任何第三方播放库依赖。
 *
 * v135 升级：支持播放列表(queue)、当前索引(index)、循环模式(loopMode)、随机(shuffle)、倍速(speed)。
 */
object QuroMediaController {
    data class Track(val uri: String, val title: String)

    /** loopMode：0=不循环 1=列表循环 2=单曲循环 */
    const val LOOP_OFF = 0
    const val LOOP_ALL = 1
    const val LOOP_ONE = 2

    data class State(
        val isPlaying: Boolean = false,
        val title: String = "",
        val uri: String = "",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val queue: List<Track> = emptyList(),
        val index: Int = 0,
        val loopMode: Int = LOOP_OFF,
        val shuffle: Boolean = false,
        val speed: Float = 1f,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(s: State) { _state.value = s }
    fun reset() { _state.value = State() }
}
