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
    fun run(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
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
    ): QuroLlmResult {
        val typeName = if (model.type == com.ai.assistance.quro.core.model.QuroLocalModelType.LLAMA_CPP) "llama.cpp" else "MNN"
        return QuroLlmResult.Error(
            "本地离线模型「${model.name}」($typeName) 已登记，路径：${model.path}。" +
                "原生推理运行时（MNN / llama.cpp AAR）尚未接入，暂不能执行推理。请在模型配置中改用联网 API，或等待本地引擎接入。"
        )
    }
}
