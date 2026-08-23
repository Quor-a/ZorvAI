package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
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

    fun submitAnswer(index: Int, answer: String) {
        if (index in pendingQuestions.indices) {
            val pending = pendingQuestions[index]
            pending.result.set(answer)
            pending.latch.countDown()
            pendingQuestions.removeAt(index)
            Log.d(TAG, "用户提交答案: $answer")
        }
    }

    fun getCurrentQuestion(): Pair<Int, VisualPendingQuestion>? {
        return if (pendingQuestions.isNotEmpty()) {
            0 to pendingQuestions[0]
        } else {
            null
        }
    }
}

/** 操作弹窗待处理队列（全局静态，UI 线程轮询） */
object VisualActionQueue {
    private const val TAG = "VisualActionQueue"
    val pendingActions = mutableListOf<VisualPendingAction>()

    fun submitAction(index: Int, value: String) {
        if (index in pendingActions.indices) {
            val pending = pendingActions[index]
            pending.result.set(value)
            pending.latch.countDown()
            pendingActions.removeAt(index)
            Log.d(TAG, "用户选择操作: $value")
        }
    }

    fun getCurrentAction(): Pair<Int, VisualPendingAction>? {
        return if (pendingActions.isNotEmpty()) {
            0 to pendingActions[0]
        } else {
            null
        }
    }
}

/**
 * 可视化问答弹窗工具：AI执行任务时可以弹出问题让用户选择答案或输入自定义答案
 */
class VisualQuestionTool : QuroTool {
    override val name = "visual_question"
    override val description = """可视化问答弹窗工具：弹出对话框向用户提问，用户可以选择预设选项或输入自定义答案。
参数：{"question":"问题内容","options":["选项1","选项2"],"allow_custom":true,"title":"标题","timeout":30}
返回：用户选择的答案。"""
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
