package com.ai.assistance.quro.cluster.bridge

import com.ai.assistance.quro.cluster.model.ModelCapabilities
import com.ai.assistance.quro.cluster.model.OutputProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 协议适配 —— 同一个集群里，角色 A 的模型支持 function calling，角色 B 的只会说人话，
 * 这套适配器让 SDK 上层用同一份代码驱动它们，只是在渲染与解析上分三种协议。
 */
object ProtocolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 按协议把"工具定义"渲染进请求。
     * - NATIVE_TOOL：走宿主原生 function calling，参数最省
     * - JSON_MODE  ：在 system 尾部追加 JSON 契约，模型回一段 JSON
     * - TEXT_REACT ：追加"行动/参数"文本协议，纯自然语言模型也能调工具
     */
    fun renderTools(protocol: OutputProtocol, tools: List<HostToolSchema>): ToolsRender {
        if (tools.isEmpty()) return ToolsRender(null, "")
        return when (protocol) {
            OutputProtocol.NATIVE_TOOL -> ToolsRender(tools, "")
            OutputProtocol.JSON_MODE -> ToolsRender(null, jsonContract(tools))
            OutputProtocol.TEXT_REACT -> ToolsRender(null, textContract(tools))
            OutputProtocol.AUTO -> error("AUTO 必须先用 capabilities.pickProtocol 解析")
        }
    }

    private fun jsonContract(tools: List<HostToolSchema>): String = buildString {
        appendLine("\n\n【输出格式】只输出一个 JSON 对象，不要有任何额外文字。可选结构：")
        appendLine("""{"type":"tool","name":"<工具名>","args":{...}}""")
        appendLine("""{"type":"final","content":"<最终回答>"}""")
        appendLine("可用工具：")
        tools.forEach { t ->
            appendLine("- ${t.name}：${t.description}；参数 schema: ${t.parametersJson}")
        }
    }

    private fun textContract(tools: List<HostToolSchema>): String = buildString {
        appendLine("\n\n【行动协议】你不能直接完成任务时，必须按以下纯文本格式输出一步行动：")
        appendLine("行动: <工具名>")
        appendLine("参数: {\"key\":\"value\"}")
        appendLine("如果你已经能给出最终结果，改用：")
        appendLine("结论: <你的最终回答>")
        appendLine("可用工具：")
        tools.forEach { t -> appendLine("- ${t.name}：${t.description}；参数: ${t.parametersJson}") }
    }

    data class ToolsRender(val native: List<HostToolSchema>?, val systemSuffix: String)

    /**
     * 从模型输出里抽取结构化结果。三级兜底，任何一级成功就返回，
     * 不会让"模型多说了两句废话"直接导致整个任务失败。
     */
    fun extract(raw: String, protocol: OutputProtocol): Extraction {
        val calls = mutableListOf<HostToolCall>()

        // 1) 文本协议：行动/参数
        if (protocol == OutputProtocol.TEXT_REACT) {
            val name = Regex("行动\\s*[:：]\\s*(.+)").find(raw)?.groupValues?.get(1)?.trim()
            val args = Regex("参数\\s*[:：]\\s*(.+)").find(raw)?.groupValues?.get(1)?.trim() ?: "{}"
            if (!name.isNullOrBlank()) {
                calls += HostToolCall("react_${System.nanoTime()}", name, args)
                return Extraction(calls, null)
            }
            val conclusion = Regex("结论\\s*[:：]\\s*([\\s\\S]+)").find(raw)?.groupValues?.get(1)?.trim()
            return Extraction(emptyList(), conclusion ?: raw.trim())
        }

        // 2) JSON：优先整体解析，失败则截取第一个 {...} 块
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: firstJsonObject(raw)
        if (obj != null) {
            val type = obj["type"]?.jsonPrimitive?.content
            if (type == "tool") {
                val n = obj["name"]?.jsonPrimitive?.content ?: obj["tool"]?.jsonPrimitive?.content
                if (!n.isNullOrBlank()) {
                    val args = obj["args"]?.toString() ?: obj["parameters"]?.toString() ?: "{}"
                    calls += HostToolCall("json_${System.nanoTime()}", n, args)
                    return Extraction(calls, null)
                }
            }
            val content = obj["content"]?.jsonPrimitive?.content
                ?: obj["summary"]?.jsonPrimitive?.content
                ?: obj["result"]?.jsonPrimitive?.content
            if (content != null) return Extraction(emptyList(), content)
            return Extraction(emptyList(), null, obj)
        }

        // 3) 兜底：当纯文本处理
        return Extraction(emptyList(), raw.trim())
    }

    private fun firstJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until raw.length) {
            when (raw[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val slice = raw.substring(start, i + 1)
                        return runCatching { json.parseToJsonElement(slice).jsonObject }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    data class Extraction(
        val toolCalls: List<HostToolCall>,
        val text: String?,
        val json: JsonObject? = null
    )

    /** 把宿主上报的能力与角色设置合并成最终可用协议 */
    fun resolve(protocol: OutputProtocol, caps: ModelCapabilities): OutputProtocol =
        caps.pickProtocol(protocol)
}
