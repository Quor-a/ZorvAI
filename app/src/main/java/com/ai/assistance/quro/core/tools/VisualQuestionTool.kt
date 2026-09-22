package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 待处理的问答问题 */
data class VisualPendingQuestion(
    val question: String,
    val options: List<String>,
    val allowCustom: Boolean,
    val title: String,
    val latch: CountDownLatch,
    val result: AtomicReference<String?>
)

/** 待处理的操作弹窗 */
data class VisualPendingAction(
    val title: String,
    val message: String,
    val buttons: List<VisualButtonConfig>,
    val latch: CountDownLatch,
    val result: AtomicReference<String?>
)

/** 按钮配置 */
data class VisualButtonConfig(
    val text: String,
    val value: String,
    val style: String = "primary"
)

/** 问答工具待处理队列（全局静态，UI 线程轮询） */
object VisualQuestionQueue {
    private const val TAG = "VisualQuestionQueue"
    val pendingQuestions = mutableListOf<VisualPendingQuestion>()

    // 修复：原 UI 用 while(true)+delay(500) 轮询，浪费 CPU 且与事件驱动模式不一致。
    // 这里加上 eventFlow，UI 改为 collect 触发。
    private val _eventChannel = Channel<QuestionEvent>(Channel.BUFFERED)
    val eventFlow = _eventChannel.receiveAsFlow()

    sealed class QuestionEvent {
        data class QuestionAdded(val index: Int) : QuestionEvent()
        data class QuestionRemoved(val index: Int) : QuestionEvent()
    }

    fun submitAnswer(index: Int, answer: String) {
        synchronized(pendingQuestions) {
            if (index in pendingQuestions.indices) {
                val pending = pendingQuestions[index]
                pending.result.set(answer)
                pending.latch.countDown()
                pendingQuestions.removeAt(index)
                Log.d(TAG, "用户提交答案: $answer")
                _eventChannel.trySend(QuestionEvent.QuestionRemoved(index))
            }
        }
    }

    fun getCurrentQuestion(): Pair<Int, VisualPendingQuestion>? {
        return synchronized(pendingQuestions) {
            if (pendingQuestions.isNotEmpty()) {
                0 to pendingQuestions[0]
            } else {
                null
            }
        }
    }

    /**
     * 由 app 层注入的「悬浮窗拉起器」。
     *
     * 为什么需要它：core 层不能直接依赖 service 包（会形成反向依赖），
     * 但询问必须能在**任何界面**弹出（用户在 GenUI 里发问时，界面可能在主对话、
     * 在设置页、甚至在别的 App）。于是这里留一个钩子，由 QuroApplication 注入
     * 「启动 VisualQuestionOverlayService」，有询问入队时自动拉起系统级悬浮窗。
     * 注入为空则退回 Activity 内 Dialog 的老行为。
     */
    @Volatile
    var overlayLauncher: (() -> Unit)? = null

    fun signalAdded() {
        synchronized(pendingQuestions) {
            if (pendingQuestions.isNotEmpty()) {
                _eventChannel.trySend(QuestionEvent.QuestionAdded(0))
            }
        }
        runCatching { overlayLauncher?.invoke() }
    }
}

/** 操作弹窗待处理队列（全局静态，UI 线程轮询） */
object VisualActionQueue {
    private const val TAG = "VisualActionQueue"
    val pendingActions = mutableListOf<VisualPendingAction>()

    private val _eventChannel = Channel<ActionEvent>(Channel.BUFFERED)
    val eventFlow = _eventChannel.receiveAsFlow()

    sealed class ActionEvent {
        data class ActionAdded(val index: Int) : ActionEvent()
        data class ActionRemoved(val index: Int) : ActionEvent()
    }

    fun submitAction(index: Int, value: String) {
        synchronized(pendingActions) {
            if (index in pendingActions.indices) {
                val pending = pendingActions[index]
                pending.result.set(value)
                pending.latch.countDown()
                pendingActions.removeAt(index)
                Log.d(TAG, "用户选择操作: $value")
                _eventChannel.trySend(ActionEvent.ActionRemoved(index))
            }
        }
    }

    fun getCurrentAction(): Pair<Int, VisualPendingAction>? {
        return synchronized(pendingActions) {
            if (pendingActions.isNotEmpty()) {
                0 to pendingActions[0]
            } else {
                null
            }
        }
    }

    /** 同 [VisualQuestionQueue.overlayLauncher]：由 app 层注入，用于拉起系统级悬浮窗。 */
    @Volatile
    var overlayLauncher: (() -> Unit)? = null

    fun signalAdded() {
        synchronized(pendingActions) {
            if (pendingActions.isNotEmpty()) {
                _eventChannel.trySend(ActionEvent.ActionAdded(0))
            }
        }
        runCatching { overlayLauncher?.invoke() }
    }
}

/**
 * 可视化问答弹窗工具：AI执行任务时可以弹出问题让用户选择答案或输入自定义答案
 */
class VisualQuestionTool : QuroTool {
    override val name = "visual_question"
    override val description = """⚠️【强制】可视化问答弹窗：遇到模糊命令/缺少信息/需要确认时，必须立刻调用此工具询问用户！
禁止猜测、禁止假设、禁止跳过询问直接执行！
参数：{"question":"问题内容","options":["选项1","选项2"],"allow_custom":true,"title":"标题","timeout":30}
返回：用户选择的答案。
使用场景（必须调用）：
- 用户指令模糊（"帮我处理一下"）→ 问清楚具体要做什么
- 缺少关键信息（文件路径、收件人等）→ 问用户要
- 多种理解可能 → 确认是哪种
- 不可逆操作（删除、发送）→ 先确认
- 多个选项 → 让用户选"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "question":{"type":"string","description":"要问用户的问题"},
            "options":{"type":"array","items":{"type":"string"},"description":"预设选项列表（可选）"},
            "allow_custom":{"type":"boolean","description":"是否允许用户输入自定义答案，默认true"},
            "title":{"type":"string","description":"弹窗标题（可选）"},
            "timeout":{"type":"integer","description":"超时时间（秒），默认60秒"}
        },
        "required":["question"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val question = args.optString("question", "").trim()
        if (question.isBlank()) return "visual_question 需要 question（问题内容）"

        val optionsArray = args.optJSONArray("options")
        val options = mutableListOf<String>()
        optionsArray?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotBlank() }?.let { options.add(it) }
            }
        }

        val allowCustom = args.optBoolean("allow_custom", true)
        val title = args.optString("title", "AI 问题").ifBlank { "AI 问题" }
        val timeout = args.optInt("timeout", 60)

        return try {
            val result = askUser(question, options, allowCustom, title, timeout)
            result ?: "用户未回答（超时或取消）"
        } catch (e: Exception) {
            Log.e(TAG, "问答失败", e)
            "问答失败: ${e.message}"
        }
    }

    private fun askUser(
        question: String,
        options: List<String>,
        allowCustom: Boolean,
        title: String,
        timeout: Int
    ): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)

        val pending = VisualPendingQuestion(
            question = question,
            options = options,
            allowCustom = allowCustom,
            title = title,
            latch = latch,
            result = result
        )

        synchronized(VisualQuestionQueue.pendingQuestions) {
            VisualQuestionQueue.pendingQuestions.add(pending)
        }
        // 修复：通知 UI 有新问题加入，让 UI 用 eventFlow 立即拉取
        VisualQuestionQueue.signalAdded()

        Log.d(TAG, "等待用户回答: $question (超时: ${timeout}s)")

        val answered = latch.await(timeout.toLong(), TimeUnit.SECONDS)

        return if (answered) {
            result.get()
        } else {
            synchronized(VisualQuestionQueue.pendingQuestions) {
                VisualQuestionQueue.pendingQuestions.remove(pending)
            }
            null
        }
    }

    companion object {
        private const val TAG = "VisualQuestionTool"
    }
}
