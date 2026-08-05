package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.model.QuroLocalModel

/**
 * 本地离线推理引擎。
 *
 * 职责边界：本接口只定义「离线模型如何执行一次对话」。模型文件的登记、路径管理、
 * 文件夹扫描由 [com.ai.assistance.quro.core.model.QuroLocalModelRepository] 负责。
 *
 * 当前提供 [QuroLocalEnginePlaceholder]：在原生运行时（MNN / llama.cpp 的 Android AAR）
 * 接入前返回明确提示，保证应用不崩溃、且用户能感知「模型已登记、执行待接入」。
 * 接入原生库后，只需替换 [run] 实现即可（无界面改动）。
 */
interface QuroLocalEngine {
    /**
     * 执行一次本地推理。
     *
     * @param contextWindow 会话上下文窗口（token）。**必须**由调用方传入与
     *   [com.ai.assistance.quro.core.model.QuroModelConfig.contextWindow] 一致的值：
     *   此前本地会话把 n_ctx 硬编码成 2048，而上层按 16000 token 预算拼 prompt，
     *   原生层只能从**头部**把 prompt 砍到 1536 token（system 提示词被腰斩）。
     * @param onToken 流式增量回调，参数为**累计**文本（与云端 onToken 语义一致）。
     *   传 null 表示不需要流式。本地推理在手机 CPU 上单次可达数分钟，
     *   不接流式则 UI 全程空白 → 用户观感即「不闪退但也不回复」。
     */
    fun run(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        contextWindow: Int = 0,
        toolSpecsJson: String? = null,
        onToken: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null,
        isCanceled: () -> Boolean = { false },
    ): QuroLlmResult
}

/** 占位实现：原生运行时未接入时的降级返回。 */
object QuroLocalEnginePlaceholder : QuroLocalEngine {
    override fun run(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        contextWindow: Int,
        toolSpecsJson: String?,
        onToken: ((String) -> Unit)?,
        onThinking: ((String) -> Unit)?,
        isCanceled: () -> Boolean,
    ): QuroLlmResult {
        val typeName = if (model.type == com.ai.assistance.quro.core.model.QuroLocalModelType.LLAMA_CPP) "llama.cpp" else "MNN"
        return QuroLlmResult.Error(
            "本地离线模型「${model.name}」($typeName) 已登记，路径：${model.path}。" +
                "原生推理运行时（MNN / llama.cpp AAR）尚未接入，暂不能执行推理。请在模型配置中改用联网 API，或等待本地引擎接入。"
        )
    }
}
