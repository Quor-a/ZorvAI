package com.ai.assistance.quro.ui

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State

/**
 * 系统分享桥：其它 App 通过 ACTION_SEND / ACTION_SEND_MULTIPLE 分享进来的文本/链接/文件，
 * 由 [com.ai.assistance.quro.activity.QuroMainActivity] 写入 [pendingText] / [pendingUris]，
 * 再由对话框（ChatScreen）观察并预填到输入框 / 作为附件，供用户确认后发送。
 *
 * 用 Compose State 而非普通字段，让对话框的 snapshotFlow 能在值变化时即时收到。
 */
object QuroShareBridge {
    private val _pendingText = mutableStateOf<String?>(null)
    val pendingText: State<String?> = _pendingText

    private val _pendingUris = mutableStateOf<List<Uri>>(emptyList())
    val pendingUris: State<List<Uri>> = _pendingUris

    /** 写入一条待处理的分享文本（覆盖上一条未消费的内容）。 */
    fun emit(text: String) {
        _pendingText.value = text
    }

    /** 写入分享内容：文本 + 可选文件 Uri 列表（图片/文件等）。 */
    fun emit(text: String?, uris: List<Uri>) {
        _pendingText.value = text
        _pendingUris.value = uris
    }

    /** 消费掉当前待处理内容（读取后置空，避免重复填充）。 */
    fun consume() {
        _pendingText.value = null
        _pendingUris.value = emptyList()
    }
}
