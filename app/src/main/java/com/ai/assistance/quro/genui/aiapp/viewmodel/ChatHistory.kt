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

    /**
     * 把 assistant 历史回复里的界面 DSL 折叠掉，只留人话。
     *
     * ⚠️ 只折叠 ``` 围栏是不够的 —— 诊断实测模型**经常不写围栏**
     * （`含genui围栏=false`，整段 JSON 直接裸着输出，v1.0.96 用户截图两次都是这种）。
     * 旧实现遇到裸 JSON 只能"超长就硬截 800 字符"，于是历史里留下的是一段**被拦腰砍断的 JSON**，
     * 模型下一轮看到半截 JSON 就会接着往下编 / 把不同格式缝在一起 ——
     * 用户看到的"a2ui 和 `<row><spacer>` 标签糊成一团""type 里塞了颜色值"就是这么滚出来的。
     * 所以这里必须**整段**折叠，绝不留下截断的 JSON 残片。
     */
    fun compressAssistant(content: String): String {
        val folded = foldFences(content)
        val noJson = stripJsonBlobs(folded)
        return if (noJson.length <= MAX_ASSISTANT_CHARS) {
            noJson
        } else {
            // 已经折叠过 JSON，剩下的通常是人话；真还超长才截断
            noJson.take(MAX_ASSISTANT_CHARS).trimEnd() + foldNote("历史回复", noJson.length)
        }
    }

    /**
     * 折叠占位符的文案。
     *
     * ⚠️ 这行字是给**模型**看的（历史会进上下文），不是给人看的。
     * 早先用「〔genui 内容 96 字符已省略〕」这种中性描述，模型会把它理解成
     * 上一轮的界面内容，下一轮**原样复述**到画布上 —— 用户截图里画布孤零零一行
     * 「〔genui 内容 96 字符已省略〕」就是这么来的。
     * 所以措辞必须 ① 不像正文（方括号 + "系统提示"）② 明确叫它不要复述。
     */
    private fun foldNote(kind: String, length: Int): String =
        "[系统折叠提示：此处原有 $kind 共 $length 字符，已从上下文中移除；" +
            "不要在任何回复或界面里复述本提示]"

    /** 折叠 ```lang … ``` 代码围栏。 */
    fun foldFences(content: String): String = FENCE_REGEX.replace(content) { mr ->
        foldNote(mr.groupValues[1].ifBlank { "code" }, mr.value.length)
    }

    /**
     * 把文本里**最长的平衡 JSON 片段**换成占位符（`{…}` 与 `[…]` 都认，跳过字符串与转义）。
     *
     * 用"平衡扫描 + 取最长"，不用"第一个 `{` 到最后一个 `}`"：
     * 后者在正文里出现 `{a}` 这种小括号时会误判整段。小于 [MIN_JSON_SPAN] 的片段不动
     * （短内联 JSON 是正常表达，不必折）。
     */
    fun stripJsonBlobs(content: String, minJsonSpan: Int = MIN_JSON_SPAN): String {
        var s = content
        var guard = 0
        while (guard++ < MAX_FOLD_ROUNDS) {
            val span = largestBalancedSpan(s) ?: break
            if (span.second - span.first < minJsonSpan) break
            val inner = s.substring(span.first, span.second)
            // 不像 JSON 就别动（纯粹的 { 文本 } 说明性括号）
            if (!inner.contains("\":")) break
            val isArr = inner.startsWith("[")
            val label = if (isArr) "结构化数据" else "界面 JSON"
            s = s.substring(0, span.first) + foldNote(label, inner.length) + s.substring(span.second)
        }
        return s
    }

    /** 找出最长的 `{…}` / `[…]` 平衡片段，返回 [start, endExclusive)，找不到返回 null。 */
    private fun largestBalancedSpan(s: String): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var i = 0
        while (i < s.length) {
            val open = s[i]
            if (open != '{' && open != '[') {
                i++
                continue
            }
            var depth = 0
            var j = i
            var inStr = false
            var esc = false
            while (j < s.length) {
                val c = s[j]
                if (inStr) {
                    when {
                        esc -> esc = false
                        c == '\\' -> esc = true
                        c == '"' -> inStr = false
                    }
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                }
                j++
            }
            if (depth == 0 && j < s.length) {
                val len = j + 1 - i
                if (best == null || len > (best.second - best.first)) best = i to (j + 1)
                i = j + 1
            } else {
                i++
            }
        }
        return best
    }

    private val FENCE_REGEX = Regex("```([A-Za-z0-9_-]*)\\s*\\n?([\\s\\S]*?)```")

    /** 小于这个长度的 JSON 片段不折叠（正常内联表达）。 */
    private const val MIN_JSON_SPAN = 200

    private const val MAX_FOLD_ROUNDS = 8
}
