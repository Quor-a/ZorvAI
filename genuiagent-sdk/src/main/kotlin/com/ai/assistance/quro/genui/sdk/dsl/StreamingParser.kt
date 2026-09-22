package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 流式 JSON 解析器
 *
 * 用于处理 AI 流式输出时不完整的 JSON。
 * 支持智能补全和部分解析，即使 JSON 尚未完成也能尽可能提取已生成的 UI 结构。
 *
 * 核心能力：
 * 1. 智能补全不完整 JSON（闭合括号、引号、数组等）
 * 2. 截断到最后一个完整的结构边界
 * 3. 渐进式解析：流式输出过程中逐步渲染
 */
object StreamingParser {

    /**
     * 复用 DslParser 的 Json 实例，确保多态序列化配置完全一致，
     * 同时避免重复配置和初始化顺序问题。
     */
    private val json: Json get() = DslParser.json

    /**
     * 尝试解析部分 JSON
     *
     * 策略：
     * 1. 首先尝试完整解析（JSON 可能已经完整）
     * 2. 失败则尝试智能补全后解析
     * 3. 再失败则尝试截断到最后一个完整结构后解析
     * 4. 都失败返回 null
     *
     * @param jsonString 可能不完整的 JSON 字符串
     * @return 解析成功返回 UISpec，失败返回 null
     */
    fun tryParsePartial(jsonString: String): UISpec? {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{")) return null

        // 快速预检：必须包含 "root" 键，否则根本不可能是有效的 GenUI DSL
        // 避免浪费时间去补全和解析只有 id/title 的残缺 JSON
        if (!containsRootKey(trimmed)) return null

        // 关键校验：root 键后面必须已经开始有值（至少有 { ）
        // 防止 "root": 后面截断的情况被错误接受
        if (!hasRootValueStarted(trimmed)) return null

        // 策略 1：直接完整解析
        runCatching { json.decodeFromString<UISpec>(trimmed) }
            .onSuccess { if (isValidUISpec(it)) return it }

        // 策略 2：智能补全后解析
        val completed = autoCompleteJson(trimmed)
        if (completed != trimmed) {
            runCatching { json.decodeFromString<UISpec>(completed) }
                .onSuccess { if (isValidUISpec(it)) return it }
        }

        // 策略 3：截断到最后一个完整的子组件后解析
        val truncated = truncateToLastComplete(trimmed)
        if (truncated != null && truncated.length < trimmed.length) {
            val completedTruncated = autoCompleteJson(truncated)
            runCatching { json.decodeFromString<UISpec>(completedTruncated) }
                .onSuccess { if (isValidUISpec(it)) return it }
        }

        return null
    }

    /**
     * 检查 UISpec 是否有效（至少要有有意义的 root 组件）
     *
     * 只有 id/title 没有 root 的 JSON 不算有效 UI，
     * 避免流式早期返回残缺结构导致渲染错误。
     */
    private fun isValidUISpec(spec: UISpec): Boolean {
        return spec.root.type.isNotBlank()
    }

    /**
     * 快速检查 JSON 字符串中是否包含 "root" 键（而非字符串值中的 root）
     *
     * 简单但有效的预检：查找 "root" 后面跟着冒号的模式。
     * 这比完整解析快得多，可以快速过滤掉明显不完整的 JSON。
     */
    private fun containsRootKey(json: String): Boolean {
        // 匹配 "root"\s*:  即 root 键后面跟着冒号（可能有空白）
        val pattern = """"root"\s*:"""
        return Regex(pattern).containsMatchIn(json)
    }

    /**
     * 检查 root 键后面是否已经开始有值
     *
     * 防止 "root": 后面截断（无值或只有空白）的情况被错误接受。
     * 要求 "root": 后面至少有一个非空白字符（通常是 { 表示对象值开始）。
     *
     * 例如：
     * - {"id":"x","root": → false（没有值）
     * - {"id":"x","root":  → false（只有空白）
     * - {"id":"x","root":{ → true（对象值已开始）
     * - {"id":"x","root":{"type":"column" → true
     */
    private fun hasRootValueStarted(json: String): Boolean {
        // 找到 "root" 键的位置
        val rootPattern = """"root"\s*:"""
        val match = Regex(rootPattern).find(json) ?: return false
        val afterColon = match.range.last + 1

        // 检查冒号后面是否有非空白字符
        for (i in afterColon until json.length) {
            val c = json[i]
            if (!c.isWhitespace()) {
                // 必须是 { 才算有效的 root 值开始
                // （root 是 UIComponent 对象，必须以 { 开头）
                return c == '{'
            }
        }
        // 冒号后面全是空白或没有内容
        return false
    }

    /**
     * 智能补全不完整的 JSON
     *
     * 通过追踪括号、引号、数组的嵌套状态，自动闭合所有未闭合的结构。
     * 处理以下不完整场景：
     * - 未闭合的字符串
     * - 未闭合的对象 {}
     * - 未闭合的数组 []
     * - 悬空的键名（缺少值）
     * - 末尾多余的逗号
     * - 对象键名不完整（正在输入键名时中断）
     */
    fun autoCompleteJson(json: String): String {
        val result = StringBuilder(json.length + 32)
        var inString = false
        var escaped = false
        val stack = ArrayDeque<Char>()  // 记录嵌套结构：'{' 或 '['
        var expectValue = false  // 刚读完冒号或逗号，期待值

        var i = 0
        while (i < json.length) {
            val c = json[i]

            when {
                escaped -> {
                    escaped = false
                    result.append(c)
                }
                c == '\\' && inString -> {
                    escaped = true
                    result.append(c)
                }
                c == '"' -> {
                    inString = !inString
                    result.append(c)
                }
                inString -> {
                    result.append(c)
                }
                c.isWhitespace() -> {
                    result.append(c)
                    i++
                    continue
                }
                c == '{' -> {
                    stack.addLast('{')
                    expectValue = false
                    result.append(c)
                }
                c == '}' -> {
                    if (stack.lastOrNull() == '{') {
                        stack.removeLast()
                    }
                    expectValue = false
                    result.append(c)
                }
                c == '[' -> {
                    stack.addLast('[')
                    expectValue = false
                    result.append(c)
                }
                c == ']' -> {
                    if (stack.lastOrNull() == '[') {
                        stack.removeLast()
                    }
                    expectValue = false
                    result.append(c)
                }
                c == ':' -> {
                    expectValue = true
                    result.append(c)
                }
                c == ',' -> {
                    expectValue = true
                    result.append(c)
                }
                else -> {
                    if (expectValue) {
                        expectValue = false
                    }
                    result.append(c)
                }
            }
            i++
        }

        // 如果在字符串中间结束，先闭合字符串
        var endedInKeyString = false
        if (inString) {
            result.append('"')
            // 判断这个字符串是否是对象中的键（而非值）
            // 如果在对象上下文中（栈顶是 {）且处于"期待"状态（前面是逗号或 {），
            // 则这个字符串是键名，需要补 :null
            endedInKeyString = expectValue && stack.lastOrNull() == '{'
        }

        // 去掉末尾的悬空逗号
        var idx = result.length - 1
        while (idx >= 0 && result[idx].isWhitespace()) idx--
        if (idx >= 0 && result[idx] == ',') {
            result.delete(idx, idx + 1)
        }

        // 如果最后一个非空白字符是冒号，说明值缺失，补一个 null
        idx = result.length - 1
        while (idx >= 0 && result[idx].isWhitespace()) idx--
        if (idx >= 0 && result[idx] == ':') {
            result.append("null")
        }

        // 如果在对象键名中间结束（键名字符串刚闭合，但没有冒号和值），补 :null
        // 需要重新检查最后一个非空白字符是否是 "（字符串结束）且在对象上下文中
        if (endedInKeyString) {
            idx = result.length - 1
            while (idx >= 0 && result[idx].isWhitespace()) idx--
            if (idx >= 0 && result[idx] == '"') {
                // 检查这是不是一个键（前面是逗号或 { 或 空白）
                // 简化：直接补 :null，后续如果不对也会在解析时失败并走降级策略
                result.append(":null")
            }
        }

        // 闭合所有未闭合的容器
        while (stack.isNotEmpty()) {
            when (stack.removeLast()) {
                '{' -> result.append('}')
                '[' -> result.append(']')
            }
        }

        return result.toString()
    }

    /**
     * 截断到最后一个完整的结构边界
     *
     * 当 JSON 损坏严重无法通过补全修复时，
     * 找到最后一个完整闭合的对象位置，从那里截断。
     *
     * 策略：追踪括号深度，找到最后一个完整闭合的对象位置。
     * 无论嵌套多深，只要是一个完整闭合的对象，都可以作为截断点，
     * 之后由 autoCompleteJson 负责闭合所有未闭合的外层结构。
     */
    private fun truncateToLastComplete(json: String): String? {
        // 先确认有 root 对象，确保是有效的 GenUI 结构
        val rootKeyIdx = findRootObjectStart(json)
        if (rootKeyIdx < 0) return null

        // 追踪从开头到各位置的括号深度
        var depth = 0
        var inString = false
        var escaped = false
        var lastCompleteObjectEnd = -1
        var enteredRoot = false  // 是否已进入 root 对象内部

        for (i in json.indices) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> {
                    depth++
                    // 到达 root 对象内部时标记（depth >= 2 表示在 root 的 { 内部）
                    if (i >= rootKeyIdx && depth >= 2) {
                        enteredRoot = true
                    }
                }
                !inString && c == '}' -> {
                    depth--
                    // 只有在已经进入 root 内部后，闭合的对象才算有效
                    // 任何深度的完整对象闭合都可以作为截断点
                    if (enteredRoot && depth >= 1) {
                        lastCompleteObjectEnd = i
                    }
                }
                !inString && c == '[' -> depth++
                !inString && c == ']' -> depth--
            }
        }

        if (lastCompleteObjectEnd > 0) {
            // 截断到最后一个完整闭合的对象末尾
            // 之后 autoCompleteJson 会负责闭合剩余的括号
            return json.substring(0, lastCompleteObjectEnd + 1)
        }

        return null
    }

    /**
     * 找到 root 对象的起始位置
     */
    private fun findRootObjectStart(json: String): Int {
        // 简单查找：找到 "root" 键后面的 {
        val rootPattern = "\"root\""
        val idx = json.indexOf(rootPattern)
        if (idx < 0) return -1

        // 找到对应的 {
        val braceIdx = json.indexOf('{', idx + rootPattern.length)
        return braceIdx
    }

    /**
     * 检查 JSON 字符串是否看起来是完整的
     */
    fun isJsonComplete(json: String): Boolean {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.first() != '{') return false
        if (trimmed.last() != '}') return false

        // 简单检查括号平衡
        var depth = 0
        var inString = false
        var escaped = false
        for (c in trimmed) {
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> depth--
            }
        }
        return depth == 0 && !inString
    }

    /**
     * 创建一个降级的加载中 UI
     * 用于流式输出早期，JSON 还不足以解析时显示
     */
    fun createLoadingUI(message: String = "正在生成界面..."): UISpec {
        return UISpec(
            id = "streaming_loading",
            title = "生成中",
            root = UIComponent(
                type = "column",
                style = UIStyle(
                    padding = EdgeInsets.all(24f),
                    arrangement = "center",
                    crossAlignment = "center"
                ),
                children = listOf(
                    UIComponent(
                        type = "text",
                        properties = buildJsonObject {
                            put("text", JsonPrimitive("⏳"))
                        },
                        style = UIStyle(
                            textSize = 32f,
                            textAlign = "center"
                        )
                    ),
                    UIComponent(
                        type = "spacer",
                        style = UIStyle(height = Dimension.Fixed(16f))
                    ),
                    UIComponent(
                        type = "body",
                        properties = buildJsonObject {
                            put("text", JsonPrimitive(message))
                        },
                        style = UIStyle(
                            textAlign = "center",
                            textColor = "#666666"
                        )
                    )
                )
            ),
            schemaVersion = "streaming"
        )
    }
}
