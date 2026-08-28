package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 对话框多语言后端：文本翻译工具。
 *
 * 消费 [QuroFunctionType.TRANSLATION] 功能绑定（跟随主模型 / 独立模型），
 * 经 [QuroLlmClient] 调用 LLM 完成翻译，结果直接返回对话框。
 * 此前「多语言后端没做出来」的根因是：配置层（QuroFeatureModelConfig.TRANSLATION）与
 * UI 入口（QuroFeatureModelConfigScreen）都已存在，但**没有真正的执行后端/工具**消费该配置。
 * 本工具即为该后端，使翻译能力可被 AI 真实调用。
 */
class TranslateTool : QuroTool {
    override val name = "translate"
    override val description = """文本翻译工具：将一段文本翻译成目标语言，结果直接返回对话框。
参数：{"text":"待翻译文本","target_lang":"目标语言(如 英语/中文/日语/English/Chinese)","source_lang":"源语言(可选,默认自动检测)"}
当用户要求翻译、或对话中出现需要翻译的外语内容时可使用本工具。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "text":{"type":"string","description":"待翻译的文本"},
            "target_lang":{"type":"string","description":"目标语言，如 英语、中文、日语、English、Chinese"},
            "source_lang":{"type":"string","description":"源语言(可选)，如 英语、中文；缺省则自动检测"}
        },
        "required":["text","target_lang"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return "translate 参数解析失败（需要 JSON：{\"text\":\"...\",\"target_lang\":\"英语\"}）"
        val text = args.optString("text", "").trim()
        if (text.isBlank()) return "translate 需要 text（待翻译文本）"
        val targetLang = args.optString("target_lang", "英语").trim().ifBlank { "英语" }
        val sourceLang = args.optString("source_lang", "").trim()

        val cfg = resolveConfig(context)
        if (cfg.apiKey.isBlank()) {
            return "未配置翻译模型 API Key。请在「设置 → 模型配置 / 功能模型绑定(翻译 TRANSLATION)」中填写 API Key 后再使用翻译。"
        }

        return try {
            val systemPrompt = buildString {
                append("你是一名专业的多语言翻译引擎。请将用户提供的文本翻译成「$targetLang」。")
                if (sourceLang.isNotBlank()) append("源语言为「$sourceLang」。")
                else append("源语言请自动检测。")
                append("只输出翻译结果本身，不要包含任何解释、前缀、引号或 Markdown 代码块标记。")
            }
            val messages = listOf(
                QuroChatMessage(role = "system", content = systemPrompt),
                QuroChatMessage(role = "user", content = text),
            )
            val result = runBlocking {
                QuroLlmClient().chat(
                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,
                    model = cfg.model,
                    messages = messages,
                    temperature = 0.3f,
                    maxTokens = cfg.maxTokens,
                )
            }
            when (result) {
                is QuroLlmResult.Text -> result.content.trim().ifBlank { "（翻译结果为空）" }
                is QuroLlmResult.ToolCalls -> (result.content ?: "").trim().ifBlank { "（翻译结果为空）" }
                is QuroLlmResult.Error -> "翻译失败：${result.message}"
            }
        } catch (e: Exception) {
            Log.e("TranslateTool", "翻译异常", e)
            "翻译异常：${e.message}"
        }
    }

    /** 解析翻译功能的最终配置：功能绑定(useGlobal=false) > 全局模型配置。 */
    private fun resolveConfig(context: Context): QuroModelConfig {
        val global = QuroModelConfigRepository(context).load()
        return QuroFunctionModelConfigRepository(context).resolveConfig(QuroFunctionType.TRANSLATION, global)
    }
}
