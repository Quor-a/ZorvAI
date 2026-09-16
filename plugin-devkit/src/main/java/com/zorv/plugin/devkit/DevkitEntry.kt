package com.zorv.plugin.devkit

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * 示例插件：开发工具箱。
 *
 * 演示「一个插件可以是一整套工具」——本插件一次注册 6 个 AI 工具：
 *   dev_json / dev_base64 / dev_hash / dev_url / dev_regex / dev_uuid
 * 外加 1 个对外 ACI 能力（dev_hash）。
 *
 * 全部离线可用、零外部依赖，用来验证：
 *  · 多工具插件在宿主的注册与下发；
 *  · 枚举参数（ParamType.STRING + enum）能否被 LLM 正确填充；
 *  · required=false 的可选参数。
 */
class DevkitEntry : PluginEntry {

    override fun onCreate(ctx: PluginContext) {
        ctx.log("DevkitEntry", "开发工具箱启动 v${ctx.pluginVersion}")

        plugin(ctx) {

            // ---------- JSON 格式化 / 压缩 / 校验 ----------
            aiTool(
                name = "dev_json",
                description = "处理 JSON 文本：格式化（美化缩进）、压缩成单行、或校验是否合法。用户给一段 JSON 要求美化/校验时调用。"
            ) {
                param("text", ParamType.STRING, "要处理的 JSON 文本")
                param("mode", ParamType.STRING, "处理方式", required = false,
                    enum = listOf("pretty", "compact", "validate"), default = "pretty")
                execute { args ->
                    val raw = args.string("text")
                    if (raw.isBlank()) return@execute ToolResult.error("text 不能为空")
                    val mode = args.string("mode").ifBlank { "pretty" }
                    try {
                        when (mode) {
                            "validate" -> {
                                JSONObject(raw); ToolResult.text("JSON 合法 ✓ 顶层是对象")
                            }
                            "compact" -> ToolResult.text(JSONObject(raw).toString())
                            else -> ToolResult.text(JSONObject(raw).toString(2))
                        }
                    } catch (e: Throwable) {
                        ToolResult.error("JSON 解析失败：${e.message}")
                    }
                }
            }

            // ---------- Base64 ----------
            aiTool(
                name = "dev_base64",
                description = "对文本做 Base64 编码或解码。"
            ) {
                param("text", ParamType.STRING, "要编码/解码的文本")
                param("mode", ParamType.STRING, "encode 或 decode", required = false,
                    enum = listOf("encode", "decode"), default = "encode")
                execute { args ->
                    val t = args.string("text")
                    if (t.isBlank()) return@execute ToolResult.error("text 不能为空")
                    runCatching {
                        if (args.string("mode").ifBlank { "encode" } == "decode")
                            ToolResult.text(String(android.util.Base64.decode(t, android.util.Base64.DEFAULT)))
                        else
                            ToolResult.text(android.util.Base64.encodeToString(t.toByteArray(), android.util.Base64.NO_WRAP))
                    }.getOrElse { ToolResult.error("Base64 处理失败：${it.message}") }
                }
            }

            // ---------- 摘要哈希 ----------
            aiTool(
                name = "dev_hash",
                description = "计算文本的摘要哈希值（MD5 / SHA-1 / SHA-256）。"
            ) {
                param("text", ParamType.STRING, "要计算摘要的文本")
                param("algo", ParamType.STRING, "算法", required = false,
                    enum = listOf("md5", "sha1", "sha256"), default = "sha256")
                execute { args ->
                    val t = args.string("text")
                    if (t.isBlank()) return@execute ToolResult.error("text 不能为空")
                    ToolResult.text(hashOf(args.string("algo").ifBlank { "sha256" }, t))
                }
            }

            // ---------- URL 编解码 ----------
            aiTool(
                name = "dev_url",
                description = "对文本做 URL 百分号编码或解码。"
            ) {
                param("text", ParamType.STRING, "要编码/解码的文本")
                param("mode", ParamType.STRING, "encode 或 decode", required = false,
                    enum = listOf("encode", "decode"), default = "encode")
                execute { args ->
                    val t = args.string("text")
                    if (t.isBlank()) return@execute ToolResult.error("text 不能为空")
                    runCatching {
                        if (args.string("mode").ifBlank { "encode" } == "decode")
                            ToolResult.text(URLDecoder.decode(t, "UTF-8"))
                        else
                            ToolResult.text(URLEncoder.encode(t, "UTF-8"))
                    }.getOrElse { ToolResult.error("URL 处理失败：${it.message}") }
                }
            }

            // ---------- 正则测试 ----------
            aiTool(
                name = "dev_regex",
                description = "用正则表达式在文本里做匹配测试，返回所有命中片段与是否整体匹配。"
            ) {
                param("pattern", ParamType.STRING, "正则表达式")
                param("text", ParamType.STRING, "被匹配的文本")
                param("ignore_case", ParamType.BOOLEAN, "是否忽略大小写", required = false, default = "false")
                execute { args ->
                    val p = args.string("pattern")
                    if (p.isBlank()) return@execute ToolResult.error("pattern 不能为空")
                    val text = args.string("text")
                    runCatching {
                        val opts = if (args.boolean("ignore_case")) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        val re = Regex(p, opts)
                        val hits = re.findAll(text).map { it.value }.take(50).toList()
                        // ★ 必须包 ToolResult.text：runCatching 块若返回 String，而 getOrElse 返回 ToolResult，
                        //   公共父类会被推断成 Any，报 "Return type mismatch: expected 'ToolResult', actual 'Any'"。
                        ToolResult.text(
                            buildString {
                                appendLine("整体匹配：${re.containsMatchIn(text)}")
                                appendLine("命中数量：${re.findAll(text).count()}")
                                if (hits.isNotEmpty()) {
                                    appendLine("命中片段（最多 50 条）：")
                                    hits.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
                                }
                            }.trim()
                        )
                    }.getOrElse { ToolResult.error("正则非法：${it.message}") }
                }
            }

            // ---------- UUID ----------
            aiTool(
                name = "dev_uuid",
                description = "生成一个或多个随机 UUID。"
            ) {
                param("count", ParamType.INT, "生成个数，默认 1，上限 20", required = false, default = "1")
                execute { args ->
                    val n = args.int("count", 1).coerceIn(1, 20)
                    ToolResult.text((1..n).joinToString("\n") { UUID.randomUUID().toString() })
                }
            }

            // ---------- 对外 ACI 能力 ----------
            aciCapability(
                id = "dev_hash",
                description = "计算文本摘要哈希（MD5 / SHA-1 / SHA-256），供其他 App 或 Agent 调用"
            ) {
                param("text", ParamType.STRING, "要计算摘要的文本")
                param("algo", ParamType.STRING, "算法：md5 / sha1 / sha256", required = false)
                execute { args ->
                    ToolResult.text(hashOf(args.string("algo").ifBlank { "sha256" }, args.string("text")))
                }
            }
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }

    private fun hashOf(algo: String, text: String): String {
        val name = when (algo.lowercase()) {
            "md5" -> "MD5"
            "sha1", "sha-1" -> "SHA-1"
            else -> "SHA-256"
        }
        val d = MessageDigest.getInstance(name).digest(text.toByteArray(Charsets.UTF_8))
        return "${name.lowercase()}: " + d.joinToString("") { "%02x".format(it) }
    }
}
