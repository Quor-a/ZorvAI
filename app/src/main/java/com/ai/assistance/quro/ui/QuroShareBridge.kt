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

    // 🔴 自动发送计数（2026-11-05）：>0 时对话框直接发送对应文本，不经输入框预填
    // —— 供桌宠点击等「要产生真实聊天回合」的入口使用（对齐 chat_turn_trigger_pet_tap）。
    private val _pendingAutoSend = mutableStateOf(0)
    val pendingAutoSend: State<Int> = _pendingAutoSend

    // 🔴 通道分离（2026-11-07 修复「点宠物还要手动发送」）：自动发送的文本此前写进
    // pendingText —— 与输入框预填的 snapshotFlow 抢同一字段，预填方先 consume 掉，
    // 自动发送方醒来时文本已没了 → 文本留在输入框等用户手发。现在自动发送有独立
    // 通道（_autoSendText），预填通道永远看不到它。
    private val _autoSendText = mutableStateOf<String?>(null)

    /** 写入一条**直接发送**的文本（独立通道，不经预填/输入框）。 */
    fun emitAutoSend(text: String) {
        _autoSendText.value = text
        _pendingAutoSend.value = _pendingAutoSend.value + 1
    }

    /** 自动发送方取走文本（取走即清空）。 */
    fun consumeAutoSend(): String? {
        val t = _autoSendText.value
        _autoSendText.value = null
        return t
    }

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
