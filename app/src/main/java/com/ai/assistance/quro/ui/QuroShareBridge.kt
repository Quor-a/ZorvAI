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

    /**
     * 应用自动发起的陪伴式回合（桌宠轻点互动 / 语音球）。
     *
     * 与手打消息的区别：**可见气泡**只放一句自然的短文本；给模型的**指令**
     * 与屏幕线索走隐藏上下文；失败时整轮静默撤下，不往聊天记录里写报错。
     */
    data class QuietTurn(
        /** 用户气泡里唯一可见的内容：一句干净的短话。 */
        val visibleText: String,
        /** 给模型的隐藏指令（互动模板 / 屏幕线索），以 hidden 消息注入，用户不可见。 */
        val hiddenInstruction: String? = null,
        /** 失败时静默撤下本轮，不落错误气泡。 */
        val silentOnError: Boolean = true,
        /** 消息来源（结构化接线，语义见 PetSource.key）。 */
        val source: String = "pet_tap",
        /** 屏幕分析的结构化结果，只注入隐藏上下文。 */
        val screenInsight: String? = null,
        /** 是否要求 AI 用工具把任务实际做完。 */
        val completeTask: Boolean = false,
        /** 回合关联 id，用于把 AI 回复回流到桌宠气泡。 */
        val requestId: Long = System.currentTimeMillis(),
    )

    private val _pendingText = mutableStateOf<String?>(null)
    val pendingText: State<String?> = _pendingText

    // 🔴 自动发送计数：>0 时对话框直接发送对应文本，不经输入框预填。
    private val _pendingAutoSend = mutableStateOf(0)
    val pendingAutoSend: State<Int> = _pendingAutoSend

    // 通道分离：自动发送的文本此前与输入框预填抢同一字段，预填方先 consume 掉，
    // 自动发送方醒来时文本已没了。现在自动发送有独立通道。
    private val _autoSendText = mutableStateOf<String?>(null)

    /** 安静回合通道：与普通自动发送共用触发计数，但携带结构化语义。 */
    private val _quietTurn = mutableStateOf<QuietTurn?>(null)

    /** 写入一条**直接发送**的文本（独立通道，不经预填/输入框）。 */
    fun emitAutoSend(text: String) {
        _autoSendText.value = text
        _pendingAutoSend.value = _pendingAutoSend.value + 1
    }

    /** 写入一条安静回合；触发计数与普通自动发送共用，ChatScreen 优先消费本通道。 */
    fun emitQuietTurn(turn: QuietTurn) {
        _quietTurn.value = turn
        _pendingAutoSend.value = _pendingAutoSend.value + 1
    }

    /** 结构化桌宠回合入口：桌宠输入框 / 屏幕分析 / 后台任务统一走这里。 */
    fun emitPetTurn(turn: com.ai.assistance.quro.core.pet.PetTurn) {
        emitQuietTurn(
            QuietTurn(
                visibleText = turn.visibleText,
                hiddenInstruction = turn.hiddenContext(),
                silentOnError = turn.silentOnError,
                source = turn.source.key,
                // 屏幕观察已由 PetTurn.hiddenContext() 合并进 hiddenInstruction，
                // 此处不再重复传，避免对话里出现两份相同的屏幕文本。
                completeTask = turn.completeTask,
                requestId = turn.requestId,
            ),
        )
    }

    /** 优先取安静回合；没有则回退到普通自动发送文本。二者只可能命中一个。 */
    fun consumeQuietTurn(): QuietTurn? {
        val t = _quietTurn.value
        _quietTurn.value = null
        return t
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
