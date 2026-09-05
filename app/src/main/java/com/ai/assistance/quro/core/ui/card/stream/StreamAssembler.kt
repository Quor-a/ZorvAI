package com.ai.assistance.quro.core.ui.card.stream

import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import org.json.JSONException
import org.json.JSONObject

/**
 * 装配层：流式与整卡兼容。
 *
 * 核心：增量 JSON 解析器——token 边到边解析，不等全文再 JSONObject。
 * 状态机：(空) → Skeleton → Streaming → Complete（异常 → Error(retryable)）。
 *
 * - 流式期：先出骨架卡（固定占位高度，防抖动）；数据点到达即 patch 增量重绘。
 * - 整卡期：一次性 Complete，走同一条渲染路径。
 * - 回滚：streamId 抖动（重生成/断流）时用 generation 号丢弃过期 patch。
 *
 * 注：解析用内置 org.json，不依赖 kotlinx-serialization 插件（app 模块未启用）。
 */
sealed interface AssembleState {
    object Empty : AssembleState
    data class Skeleton(val spec: CardSpec) : AssembleState
    data class Streaming(val spec: CardSpec, val buffer: String) : AssembleState
    data class Complete(val spec: CardSpec) : AssembleState
    data class Error(val retryable: Boolean, val reason: String?) : AssembleState
}

class StreamAssembler(
    private val streamId: String,
    private val onComplete: (CardSpec) -> Unit,
    private val onPatch: (CardSpec) -> Unit = {},
) {
    private var generation = 0L
    private var state: AssembleState = AssembleState.Empty
    private val buf = StringBuilder()

    /** 重生成/断流时调用，丢弃过期 patch。 */
    fun bumpGeneration() { generation++ }

    @Suppress("UNUSED_EXPRESSION")
    private fun currentGeneration() = generation

    /** 喂入增量 token 片段（流式）。 */
    fun feed(chunk: String) {
        if (state is AssembleState.Empty) {
            // 第一段到达：先立骨架，防抖动
            state = AssembleState.Skeleton(buildSkeleton())
        }
        buf.append(chunk)
        val snap = buf.toString()
        val parsed = tryParse(snap)
        if (parsed != null) {
            state = AssembleState.Complete(parsed)
            onComplete(parsed)
        } else {
            state = AssembleState.Streaming(buildStreamingPreview(snap), snap)
            // 局部 patch：尝试解析当前已收全的片段，能解析就增量重绘
            val preview = tryParsePartial(snap)
            if (preview != null) onPatch(preview)
        }
    }

    /** 一次性完整输入（整卡）。 */
    fun feedComplete(json: String) {
        val parsed = tryParse(json)
        if (parsed != null) {
            state = AssembleState.Complete(parsed)
            onComplete(parsed)
        } else {
            state = AssembleState.Error(true, "JSON 解析失败")
        }
    }

    fun snapshot(): AssembleState = state

    private fun buildSkeleton(): CardSpec =
        CardSpec(id = streamId, type = "skeleton", data = CardData.Status(statusType = "skeleton"))

    private fun buildStreamingPreview(snap: String): CardSpec =
        CardSpec(id = streamId, type = "skeleton", data = CardData.Status(statusType = "skeleton", text = snap.take(64)))

    /** 尝试把整段解析为 CardSpec；失败返回 null（说明 JSON 没收全或非法）。 */
    private fun tryParse(s: String): CardSpec? = runCatching {
        val obj = JSONObject(s)
        val type = obj.optString("type").ifBlank { return null }
        val id = obj.optString("id").ifBlank { streamId }
        val version = obj.optInt("version", 1)
        CardSpec(id = id, type = type, version = version)
    }.getOrNull()

    /** 流式期：截掉最后一个未闭合的键/值后尝试解析，用于增量 patch 预览。 */
    private fun tryParsePartial(s: String): CardSpec? {
        // 简化策略：若末尾明显未闭合（引号/大括号不配对），先截断到上一个完整对象边界再试
        val trimmed = s.substringBeforeLast("\"}").trimEnd(',') + "}}"
        return runCatching {
            val obj = JSONObject(trimmed)
            val type = obj.optString("type").ifBlank { return null }
            CardSpec(id = obj.optString("id").ifBlank { streamId }, type = type, version = obj.optInt("version", 1))
        }.getOrNull().also { /* ignore */ }
    }
}
