package com.ai.assistance.quro.core.network

import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.mnn.MNNLlmSession
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import java.io.File

/**
 * 原生本地推理引擎（full 风味专用实现）。
 *
 * 职责：直接驱动移植进来的 MNN / llama.cpp JNI 会话（[MNNLlmSession] / [LlamaSession]），
 * 把 QuroAI 的 [QuroChatMessage] 多轮历史映射为原生输入，把原生 token 流累积为
 * [QuroLlmResult.Text]，而不引入 operit 的 AIService / PromptTurn / Stream 那套 chat 子系统。
 *
 * 风味隔离约束：本文件位于 `app/src/full/java` 风味源码集，仅 `full` 风味可编译
 * （因为 `:mnn` / `:llama` 仅 `fullImplementation`）。[QuroAssistant.routeLocal] 在 `main`
 * 源码集通过反射实例化本类；`fdroid` 风味因 classpath 不含本类，反射失败回退
 * [QuroLocalEnginePlaceholder]。
 *
 * 已知局限（Phase 2 范围外，留待后续优化）：
 * - MNN 的 [MNNLlmSession.create] 不接收 temperature，MNN 采样温度暂未接入（见 runMnn 注释）。
 * - 每次 run 都重建原生会话（不跨对话缓存 KV-Cache），正确性优先；会话保活/池化是后续性能优化项。
 * - 工具调用（grammar）走 llama 的 applyStructuredChatTemplate，归 Phase 3 流式/工具链接入。
 */
class QuroLocalEngineNative : QuroLocalEngine {

    override fun run(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
    ): QuroLlmResult {
        return when (model.type) {
            QuroLocalModelType.MNN -> runMnn(model, messages, maxTokens)
            QuroLocalModelType.LLAMA_CPP -> runLlama(model, modelName, messages, temperature, maxTokens)
        }
    }

    // ------------------------------------------------------------------------------------------
    // MNN
    // ------------------------------------------------------------------------------------------

    private fun runMnn(
        model: QuroLocalModel,
        messages: List<QuroChatMessage>,
        maxTokens: Int,
    ): QuroLlmResult {
        val modelDir = resolveMnnDir(model.path)
        if (modelDir == null) {
            return QuroLlmResult.Error(
                "MNN 模型路径无效（需指向含 llm_config.json 的模型目录，或指向该目录内的 .mnn 文件）：" +
                    "path=${model.path}"
            )
        }

        val session = MNNLlmSession.create(modelDir.absolutePath)
        if (session == null) {
            return QuroLlmResult.Error("MNN 会话创建失败（模型目录：${modelDir.absolutePath}）。请确认含 llm_config.json 且权重文件完整。")
        }

        return try {
            // MNN 的 generateStream 接收完整 (role, content) 历史并生成一次补全。
            val history = buildMnnHistory(messages)
            val sb = StringBuilder()
            val ok = session.generateStream(history, maxTokens) { token ->
                sb.append(token)
                true // 返回 false 可中断生成；这里始终继续
            }
            if (!ok && sb.isEmpty()) {
                QuroLlmResult.Error("MNN 推理失败（会话返回 false 且无输出）。")
            } else {
                QuroLlmResult.Text(sb.toString())
            }
        } catch (e: Throwable) {
            QuroLlmResult.Error("MNN 推理异常：${e.message}")
        } finally {
            // 不跨 run 缓存会话：每次重建以保证多轮历史正确性（KV-Cache 不会被旧上下文污染）。
            runCatching { session.release() }
        }
    }

    /**
     * 把 QuroChatMessage 历史映射为 MNN 的 List<Pair<role, content>>。
     * MNN 仅识别 user / assistant / system 三种角色；tool 角色退化为 user 拼接结果，
     * 避免传入未知角色导致原生层处理异常。
     */
    private fun buildMnnHistory(messages: List<QuroChatMessage>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (m in messages) {
            val content = m.content
            if (content.isBlank()) continue
            val role = when (m.role.lowercase()) {
                "system" -> "system"
                "assistant" -> "assistant"
                "tool" -> "user" // MNN 无 tool 角色，退化为 user
                else -> "user"
            }
            out.add(role to content)
        }
        return out
    }

    /**
     * 解析 MNN 模型目录：
     * - path 是含 llm_config.json 的目录 → 直接用。
     * - path 是 .mnn 文件 → 用其所在父目录（用户选了权重文件而非文件夹时兼容）。
     * - 其它 → null。
     */
    private fun resolveMnnDir(path: String): File? {
        val f = File(path)
        return when {
            f.isDirectory -> f
            f.isFile -> f.parentFile
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------
    // llama.cpp
    // ------------------------------------------------------------------------------------------

    private fun runLlama(
        model: QuroLocalModel,
        modelName: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
    ): QuroLlmResult {
        val pathModel = resolveLlamaModelFile(model.path, modelName)
        if (pathModel == null) {
            return QuroLlmResult.Error(
                "llama.cpp 模型文件未找到（folder=${model.path}, modelName=$modelName）。" +
                    "请确认该文件夹下存在对应 .gguf 文件。"
            )
        }

        val session = LlamaSession.create(pathModel.absolutePath, LlamaSession.Config())
        if (session == null) {
            return QuroLlmResult.Error(
                "llama.cpp 会话创建失败（${pathModel.absolutePath}）。" +
                    "原因：${LlamaSession.getUnavailableReason()}"
            )
        }

        return try {
            // 设置采样参数（仅 temperature 来自调用方，其余取保守默认值）。
            runCatching {
                session.setSamplingParams(
                    temperature = temperature,
                    topP = 0.9f,
                    topK = 40,
                    repetitionPenalty = 1.1f,
                    frequencyPenalty = 0f,
                    presencePenalty = 0f,
                    penaltyLastN = 64,
                )
            }

            // 用聊天模板把历史拼成单条 prompt（addAssistant=false：让模型生成 assistant 回复）。
            val roles = messages.map { normalizeRole(it.role) }
            val contents = messages.map { it.content }
            val prompt = session.applyChatTemplate(roles, contents, addAssistant = false)
            if (prompt == null) {
                return QuroLlmResult.Error("llama.cpp 应用聊天模板失败（roles=$roles）。")
            }

            val sb = StringBuilder()
            val ok = session.generateStream(prompt, maxTokens) { token ->
                sb.append(token)
                true
            }
            if (!ok && sb.isEmpty()) {
                QuroLlmResult.Error("llama.cpp 推理失败（会话返回 false 且无输出）。")
            } else {
                QuroLlmResult.Text(sb.toString())
            }
        } catch (e: Throwable) {
            QuroLlmResult.Error("llama.cpp 推理异常：${e.message}")
        } finally {
            runCatching { session.release() }
        }
    }

    /**
     * 在 folder 下定位 .gguf 文件：优先 <modelName>.gguf，否则按 modelName 模糊匹配首个 .gguf。
     */
    private fun resolveLlamaModelFile(folder: String, modelName: String): File? {
        val dir = File(folder)
        if (!dir.isDirectory) return null
        val direct = File(dir, "$modelName.gguf")
        if (direct.isFile) return direct
        val direct2 = File(dir, modelName) // 可能已带扩展名
        if (direct2.isFile) return direct2
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.firstOrNull { it.name.removeSuffix(".gguf").equals(modelName, ignoreCase = true) }
    }

    private fun normalizeRole(role: String): String = when (role.lowercase()) {
        "system" -> "system"
        "assistant" -> "assistant"
        else -> "user"
    }
}
