package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage

/**
 * 对话上下文窗口化（发给 LLM 前的唯一入口）。
 *
 * 修复三个历史踩过的坑：
 *
 * ① **system 被截断丢弃**（旧实现 `history.takeLast(20)`）：
 *    system 在列表首位，一旦历史超过 20 条就被 takeLast 挤掉，
 *    模型因此丢掉「平台基座 + 人格卡 + GenUI 表达规则」全部约束，
 *    退化成普通聊天助手 —— 典型表现就是回一句「您这一轮连续发出了很多条指令，我来整理成清单…」。
 *    本实现把 system 单独拎出来，永远保留。
 *
 * ② **按「条数」切窗会把工具轮次切碎**：
 *    一次带工具调用的轮次能产生 5~10 条消息，按条数切窗等于把整轮上下文切掉，
 *    还会在窗口头部留下孤立的 `tool` 消息（与 assistant[tool_calls] 失配，部分网关直接 400）。
 *    本实现按**用户轮次**切窗：一条 user 开启一轮，user 之后的所有 assistant/tool 都归属该轮。
 *
 * ③ **历史里塞满整套界面 JSON**：
 *    每轮 assistant 回复都含 ```genui 全量 JSON（动辄上万字符），20 条就是几百 KB，
 *    既爆 token 又会让模型把旧界面当成新指令复述。
 *    本实现把「最近 N 轮」保留原文，更早的轮次压缩（围栏内容折叠 + 工具结果截断）。
 */
internal object ChatHistory {

    /** 保留最近多少个用户轮次（以 user 消息为界）。 */
    const val MAX_TURNS = 10

    /** 其中最近多少轮保留完整原文（含界面 JSON）。 */
    const val FULL_TURNS = 2

    /** 压缩轮次里单个工具结果的保留长度。 */
    const val MAX_TOOL_RESULT_CHARS = 2_000

    /** 压缩轮次里单条 assistant 文本的保留长度。 */
    const val MAX_ASSISTANT_CHARS = 800

    /**
     * 组装最终发给 LLM 的消息列表：`全部 system` + `最近 MAX_TURNS 轮非 system 消息`。
     *
     * @param history 内存中的完整对话历史（含首条 system）
     * @param maxTurns 保留的用户轮次上限
     * @param fullTurns 结束时保留多少轮完整原文（从末尾数）
     */
    fun toApiMessages(
        history: List<GenUIChatMessage>,
        maxTurns: Int = MAX_TURNS,
        fullTurns: Int = FULL_TURNS
    ): List<GenUIChatMessage> {
        if (history.isEmpty()) return emptyList()
        val system = history.filter { it.role == "system" }
        val rest = history.filter { it.role != "system" }
        if (rest.isEmpty()) return system

        // ── 按用户轮次切分 ──
        val turns = ArrayList<MutableList<GenUIChatMessage>>()
        for (m in rest) {
            if (turns.isEmpty() || m.role == "user") turns.add(mutableListOf())
            turns.last().add(m)
        }

        val kept = if (turns.size > maxTurns) turns.subList(turns.size - maxTurns, turns.size) else turns
        val shrinkBefore = (kept.size - fullTurns).coerceAtLeast(0)

        val flat = ArrayList<GenUIChatMessage>()
        kept.forEachIndexed { index, turn ->
            val shrink = index < shrinkBefore
            turn.forEach { m -> flat.add(if (shrink) shrinkMessage(m) else m) }
        }

        // 窗口头部若因切窗留下孤立 tool/assistant(tool_calls) 残片 → 去掉，
        // 保证第一条非 system 消息一定是 user（OpenAI 兼容网关的硬要求）。
        // 注意：**不能**去掉尾部的 tool 消息 —— 工具调用循环里 tool 结果必须原样回传，
        // 否则 function calling 直接失效。
        while (flat.isNotEmpty() && (flat.first().role == "tool" || flat.first().toolCalls != null)) {
            flat.removeAt(0)
        }

        return system + flat
    }

    /** 压缩一条历史消息（仅用于较老的轮次）。 */
    fun shrinkMessage(m: GenUIChatMessage): GenUIChatMessage = when (m.role) {
        "assistant" -> m.copy(content = compressAssistant(m.content))
        "tool" -> {
            val c = m.content
            m.copy(
                content = if (c.length > MAX_TOOL_RESULT_CHARS)
                    c.take(MAX_TOOL_RESULT_CHARS) + "…〔工具结果过长已截断〕" else c
            )
        }
        else -> m
    }

    /** 把 assistant 历史回复里的代码围栏（界面 JSON / 长文档）折叠掉，只留人话部分。 */
    fun compressAssistant(content: String): String {
        if (content.length <= MAX_ASSISTANT_CHARS) return content
        val noFence = FENCE_REGEX.replace(content) { mr ->
            val lang = mr.groupValues[1].ifBlank { "code" }
            "〔${lang} 内容 " + mr.value.length + " 字符已省略〕"
        }
        return if (noFence.length <= MAX_ASSISTANT_CHARS) {
            noFence
        } else {
            noFence.take(MAX_ASSISTANT_CHARS) + "…〔历史回复过长已截断〕"
        }
    }

    private val FENCE_REGEX = Regex("```([A-Za-z0-9_-]*)\\s*\\n?([\\s\\S]*?)```")
}
