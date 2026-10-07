package com.ai.assistance.quro.cluster.fake

import com.ai.assistance.quro.cluster.bridge.*
import com.ai.assistance.quro.cluster.model.ModelCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 测试/演示用的宿主模型桥：不联网、不花钱，用来验证主持能不能把任务推到闭环。
 * 它模拟的正是"宿主模型配置界面里已有三条配置"的情形：
 *  - fast（便宜快模型）→ 主持调度用
 *  - strong（强模型）→ 执行角色用
 *  - local（端侧小模型，并发 1，不支持工具）→ 验证降级与协议适配
 */
class FakeHostModelBridge(
    private val script: (String) -> String = DEFAULT_SCRIPT,
    private val failOnceFor: String? = null
) : BaseHostModelBridge() {

    private val fired = HashSet<String>()

    override suspend fun listModels(): List<HostModelInfo> = listOf(
        HostModelInfo("fast", "Fast-4o-mini",
            ModelCapabilities(supportsTools = true, supportsJsonMode = true, contextWindowTokens = 128_000, maxConcurrency = 4),
            group = "云端"),
        HostModelInfo("strong", "Strong-Pro",
            ModelCapabilities(supportsTools = true, supportsVision = true, supportsJsonMode = true, contextWindowTokens = 200_000, maxConcurrency = 2),
            group = "云端"),
        HostModelInfo("local", "Local-3B",
            ModelCapabilities(supportsTools = false, supportsJsonMode = false, contextWindowTokens = 4_096, maxConcurrency = 1),
            group = "本地", available = true)
    )

    override fun chat(request: HostChatRequest): Flow<HostChatChunk> = flow {
        delay(2)
        // 模拟一次失败，用于验证降级链
        if (failOnceFor == request.modelId && fired.add("fail:${request.modelId}")) {
            emit(HostChatChunk.Error("模拟该模型 500", retriable = true))
            return@flow
        }
        val last = request.messages.lastOrNull { it.role == HostRole.USER }?.content.orEmpty()
        val out = script(last)
        out.chunked(32).forEach { emit(HostChatChunk.Delta(it)) }
        emit(HostChatChunk.Usage(HostUsage(last.length / 4, out.length / 4)))
        emit(HostChatChunk.Done("stop"))
    }

    override suspend fun countTokens(modelId: String, text: String): Int = text.length / 4

    companion object {
        /** 让主持能闭环的脚本：按意图关键词返回对应 JSON */
        val DEFAULT_SCRIPT: (String) -> String = { input ->
            when {
                input.contains("只输出 JSON") && input.contains("验收标准") && input.contains("子任务") ->
                    """{"acceptance":{"description":"产出含3个要点的Markdown报告","checkList":["有3个要点","Markdown格式","字数>200"]},"nodes":[{"id":"n1","title":"起草报告","type":"WRITE","instruction":"写Markdown报告"},{"id":"n2","title":"校对报告","type":"VERIFY","instruction":"对照标准检查","dependsOn":["n1"]}]}"""
                input.contains("执行方案") -> """{"summary":"先列提纲再写正文","steps":["列提纲","写正文","自查"],"risks":["可能缺少数据"],"selfConfidence":0.85}"""
                input.contains("裁决") || input.contains("chosenAgent") ->
                    """{"pass":true,"reason":"方案可行","chosenAgent":"writer","mergedSteps":["列提纲","写正文"]}"""
                input.contains("对照验收标准严格检查") ->
                    if (input.contains("要点")) """{"pass":true,"reason":"满足全部验收项"}"""
                    else """{"pass":false,"reason":"缺少要点","failedChecks":["要点不足3个"]}"""
                input.contains("全部子任务已完成") -> """{"summary":"已产出报告并通过验收"}"""
                input.contains("新方案") -> """{"pass":true,"reason":"改为先补数据再重写","mergedSteps":["补数据","重写"]}"""
                else -> """{"pass":true,"reason":"默认通过","summary":"完成"}"""
            }
        }
    }
}
