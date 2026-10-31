package com.ai.assistance.quro.core.mika.prompt

import java.util.concurrent.ConcurrentHashMap

/**
 * 随 App 发布的提示词,都在资源目录 prompts/ 下,分层见 prompts/README.md:
 * system/ 身份(L0)、capabilities/ 各能力一段(L1)、tools/ 每个工具的说明、feedback/ 工具与主循环给模型的反馈、
 * roles/ 内部角色(列标准、核查、描述、定位……)。
 * 文字里的 {{name}} 由调用方填;漏填的直接报错,不把占位符发给模型。
 */
object Prompts {
    private val cache = ConcurrentHashMap<String, String>()

    /** 资源原文(去掉首尾空白)。 */
    fun read(path: String): String = cache.getOrPut(path) {
        checkNotNull(Prompts::class.java.getResourceAsStream("/prompts/$path")) { "missing prompts/$path" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
    }

    fun system(name: String): String = read("system/$name.md")

    fun capability(name: String): String = read("capabilities/$name.md")

    fun role(name: String): String = read("roles/$name.md")

    /** 入口工具那一组收着时的短说明(prompts/entries/<工具名>.md)。 */
    fun entry(tool: String): String = read("entries/$tool.md")

    /** 一个工具发给模型的说明(prompts/tools/<file>.md),填好占位符。 */
    fun tool(file: String, vararg vars: Pair<String, Any?>): String = fill(read("tools/$file.md"), vars.toMap())

    /** 一遍替换:填进去的值(屏上的字、用户的话)里即使有 {{…}} 也原样保留。 */
    fun fill(template: String, vars: Map<String, Any?>): String = PLACEHOLDER.replace(template) { m ->
        val k = m.groupValues[1]
        check(k in vars) { "unfilled placeholder {{$k}} in: ${template.take(80)}" }
        vars[k].toString()
    }

    private val PLACEHOLDER = Regex("\\{\\{([a-z0-9_]+)\\}\\}")
}
