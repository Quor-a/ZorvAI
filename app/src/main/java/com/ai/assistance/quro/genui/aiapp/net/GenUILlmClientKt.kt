package com.ai.assistance.quro.genui.aiapp.net

import org.json.JSONObject
import kotlin.text.Regex

/**
 * GenUILlmClient 常量与扩展
 *
 * 包含 LLM 客户端使用的顶层工具函数。
 * 常量定义在 GenUILlmClient.Companion 中。
 */

/**
 * 补全 chat completions 端点 URL
 * 如果 URL 不以 /chat/completions 结尾，则自动追加
 *
 * @param endpoint 基础 URL 或完整端点
 * @return 完整的 chat completions 端点 URL
 */
fun completeEndpoint(endpoint: String): String {
    var url = endpoint.trimEnd('/')
    if (!url.endsWith("/chat/completions")) {
        url += "/chat/completions"
    }
    return url
}

/**
 * 从错误消息中移除敏感的主机名和 IP 地址
 *
 * @param s 原始错误消息
 * @return 脱敏后的错误消息
 */
fun stripHostAndIp(s: String): String {
    return s.replace(Regex("https?://[^\\s/]+"), "[redacted]")
}

/**
 * 从 JSON 错误响应中提取错误消息
 *
 * @param plain 原始响应文本
 * @return 提取的错误消息，如果无法提取则返回 null
 */
fun extractJsonErrorMessage(plain: String): String? {
    return try {
        val obj = JSONObject(plain)
        val error = obj.optJSONObject("error")
        if (error != null) {
            error.optString("message", null)
        } else {
            obj.optString("error", null)
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 生成友好的 HTTP 错误信息
 *
 * @param code HTTP 状态码
 * @param raw 原始响应体
 * @return 格式化的错误消息
 */
fun friendlyHttpError(code: Int, raw: String): String {
    val extracted = extractJsonErrorMessage(raw)
    val safeRaw = extracted?.let { "：$it" } ?: ""
    val maskedRaw = stripHostAndIp(raw)
    return "HTTP $code$safeRaw (${maskedRaw.take(200)})"
}

/**
 * 生成友好的网络错误信息
 *
 * @param e 异常
 * @return 格式化的错误消息
 */
fun friendlyNetError(e: Exception): String {
    val message = e.message ?: "Unknown error"
    val masked = stripHostAndIp(message)
    return "网络错误：$masked"
}

// ==================== 工具参数修复 ====================

/**
 * 清理和修复工具参数字符串
 * 处理流式输出中可能不完整的 JSON
 *
 * @param args 原始参数字符串
 * @return 修复后的合法 JSON 字符串
 */
fun sanitizeToolArguments(args: String): String {
    val trimmed = args.trim()
    if (trimmed.isEmpty()) return "{}"
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

    // 尝试修复不完整的 JSON
    return try {
        JSONObject(trimmed).toString()
    } catch (_: Exception) {
        repairToolArguments(trimmed)
    }
}

/**
 * 修复不完整的 JSON 参数
 * 尝试关闭未闭合的括号和引号
 *
 * @param json 不完整的 JSON 字符串
 * @return 修复后的 JSON 字符串
 */
fun repairToolArguments(json: String): String {
    var result = json.trim()
    if (result.isEmpty()) return "{}"

    // 确保以 { 开头
    if (!result.startsWith("{")) {
        result = "{$result"
    }

    // 计算括号和引号
    var braceCount = 0
    var bracketCount = 0
    var inString = false
    var escaped = false

    for (c in result) {
        when {
            escaped -> escaped = false
            c == '\\' -> escaped = true
            c == '"' -> inString = !inString
            !inString && c == '{' -> braceCount++
            !inString && c == '}' -> braceCount--
            !inString && c == '[' -> bracketCount++
            !inString && c == ']' -> bracketCount--
        }
    }

    // 关闭未闭合的字符串
    if (inString) result += '"'

    // 关闭括号
    result = closeBrackets(result, braceCount, bracketCount)

    return try {
        JSONObject(result).toString()
    } catch (_: Exception) {
        "{}"
    }
}

/**
 * 关闭未闭合的括号
 *
 * @param json JSON 字符串
 * @param braceCount 未闭合的大括号数量
 * @param bracketCount 未闭合的中括号数量
 * @return 修复后的 JSON 字符串
 */
private fun closeBrackets(json: String, braceCount: Int, bracketCount: Int): String {
    var result = json
    // 移除末尾可能的逗号
    result = result.trimEnd().trimEnd(',')

    // 关闭括号
    repeat(bracketCount.coerceAtLeast(0)) {
        result += "]"
    }
    repeat(braceCount.coerceAtLeast(0)) {
        result += "}"
    }

    return result
}
