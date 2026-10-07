package com.ai.assistance.quro.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具调用摘要 —— 把「工具名 + 参数 + 结果」压成**一行能看懂的话**。
 *
 * ## 🔴 为什么要这一层
 * 旧渲染路径（`ChatScreen.formatJsonValue`）把参数和结果一律砍到 **57 字符 + 省略号**，
 * 于是用户在对话框里看到的是：
 * ```
 * run_code          {"code":"import numpy as np\ndf = pd.Data…}
 * web_search        {"query":"Kubernetes 调度算法有哪几种"}
 * write_file        {"path":"/sdcard/Docu…","content":"# 标题\n…"}
 * ```
 * 「执行了什么代码」「搜了什么」「写到哪个文件」**全都看不到**。
 *
 * 本层不改数据、不碰 Compose，纯字符串 → 结构体，所以能直接跑 JVM 单测
 * （无 Compose / 无 Robolectric 依赖）。
 *
 * 设计对齐业界共识（Claude Code / Cursor / Cline `tool-summary`）：
 * 头部 = **分类图标 + 工具名 + 一行副标题（目标物）+ 右侧耗时/状态**，
 * 折叠时就能判断「它到底干了什么」，展开才给全文。
 */
object QuroToolSummary {

    /** 工具族 —— 决定图标、配色与摘要策略。 */
    enum class Family {
        /** 代码执行 / 脚本 */
        CODE,

        /** 写文件 / 编辑文件 */
        FILE_WRITE,

        /** 读文件 / 列目录 / 查找 */
        FILE_READ,

        /** 联网搜索 / 抓网页 */
        WEB,

        /** 终端 / shell / proot */
        TERMINAL,

        /** 设备控制 / 无障碍操作 */
        DEVICE,

        /** 系统管理 / 权限 / root */
        SYSTEM,

        /** 文档 / 表格 / AIP 排版 */
        DOC,

        /** 图像 / 视频生成与识别 */
        MEDIA,

        /** 记忆 / 长期上下文 */
        MEMORY,

        /** 小程序 / 可视化组件 / UI 渲染 */
        UI,

        /** 其他 */
        OTHER,
    }

    /**
     * 一条关键指标（如「12 行 · 3 个文件」「退出码 1」）。
     * 折叠态直接显示，不必展开。
     */
    data class Metric(val label: String, val value: String)

    /** 展开态应该用哪种专属渲染器。 */
    enum class Body { CODE, DIFF, TEXT, JSON, IMAGE, EMPTY }

    /**
     * @param family    工具族（图标/配色/策略）
     * @param subtitle  一行副标题 —— 目标物（命令 / 路径 / 查询词 / 语言）
     * @param metrics   关键指标徽标
     * @param body      展开态渲染方式
     * @param bodyText  展开态正文（已按 body 预处理）
     * @param status    状态
     * @param exitCode  退出码（仅终端类）
     */
    data class Summary(
        val family: Family,
        val subtitle: String,
        val metrics: List<Metric>,
        val body: Body,
        val bodyText: String,
        val status: Status,
        val exitCode: Int? = null,
        val durationMs: Long = 0L,
    )

    enum class Status { RUNNING, SUCCESS, ERROR, WARNING, INFO }

    /**
     * 主入口。
     *
     * @param result 工具返回值；`null` 或空白 = **仍在执行中**（结果尚未回填）。
     */
    fun of(name: String, args: String, result: String?, durationMs: Long): Summary {
        val fam = familyOf(name)
        val jo = parseArgs(args)
        val running = result.isNullOrBlank()
        val status = if (running) Status.RUNNING else statusOf(result!!, fam)
        val metrics = mutableListOf<Metric>()

        var subtitle = ""
        var body = Body.EMPTY
        var bodyText = ""
        var exitCode: Int? = null

        when (fam) {
            Family.CODE -> {
                val code = jo.str("code", "source", "script", "input", "content")
                val lang = jo.str("language", "lang").ifBlank { guessLanguage(code) }
                subtitle = lang.ifBlank { "代码" }
                metrics += Metric("行数", countLines(code))
                body = Body.CODE
                bodyText = code.ifBlank { result.orEmpty() }
            }

            Family.TERMINAL -> {
                val cmd = jo.str("command", "cmd", "commandLine", "shell")
                subtitle = cmd
                exitCode = detectExitCode(result)
                exitCode?.let { metrics += Metric("退出码", it.toString()) }
                body = Body.TEXT
                bodyText = result.orEmpty()
            }

            Family.WEB -> {
                val q = jo.str("query", "q", "keyword", "keywords", "search", "search_query")
                subtitle = q.ifBlank { jo.str("url", "uri", "link").ifBlank { "联网检索" } }
                result?.let { countSearchHits(it)?.let { n -> metrics += Metric("结果", n) } }
                body = Body.TEXT
                bodyText = result.orEmpty()
            }

            Family.FILE_WRITE -> {
                val p = jo.str("path", "file_path", "filePath", "filename", "file", "target")
                subtitle = shortPath(p)
                val content = jo.str("content", "text", "data", "body")
                if (content.isNotBlank()) {
                    metrics += Metric("行数", countLines(content))
                    metrics += Metric("字节", "${content.toByteArray(Charsets.UTF_8).size}")
                    body = Body.DIFF
                    bodyText = content
                } else {
                    body = Body.TEXT
                    bodyText = result.orEmpty()
                }
            }

            Family.FILE_READ -> {
                val p = jo.str("path", "file_path", "filePath", "filename", "file", "pattern", "dir")
                subtitle = if (p.isBlank()) "读取文件" else shortPath(p)
                result?.let { r ->
                    countMatches(r)?.let { metrics += Metric("匹配", it) }
                    countLines(r).takeIf { it != "—" }?.let { metrics += Metric("行数", it) }
                }
                body = Body.TEXT
                bodyText = result.orEmpty()
            }

            Family.DOC -> {
                val p = jo.str("path", "file_path", "filePath", "title", "name")
                subtitle = if (p.isBlank()) "生成文档" else shortPath(p)
                body = if (result?.contains("\"kind\"") == true) Body.JSON else Body.TEXT
                bodyText = result.orEmpty()
            }

            Family.MEDIA -> {
                val p = jo.str("prompt", "desc", "description", "query", "path")
                subtitle = p.take(60).ifBlank { "生成内容" }
                body = Body.IMAGE
                bodyText = result.orEmpty()
            }

            Family.DEVICE -> {
                subtitle = jo.str("action", "target", "package", "text", "app").ifBlank { "设备操作" }
                body = Body.TEXT
                bodyText = result.orEmpty()
            }

            Family.SYSTEM -> {
                subtitle = jo.str("action", "setting", "target").ifBlank { "系统操作" }
                body = Body.TEXT
                bodyText = result.orEmpty()
            }

            Family.MEMORY -> {
                subtitle = jo.str("query", "key", "content", "text").take(50).ifBlank { "记忆操作" }
                body = Body.TEXT
                bodyText = result.orEmpty()
            }

            Family.UI -> {
                subtitle = jo.str("action", "component", "title", "code", "html").take(50).ifBlank { "界面渲染" }
                body = Body.JSON
                bodyText = result.orEmpty()
            }

            Family.OTHER -> {
                // 🔴 未知工具不能只显示名字 —— 至少把最长的参数值或结果首行亮出来，
                // 否则用户点开只能看到一坨 57 字符的 JSON。
                subtitle = jo.longestValue(60).ifBlank { result.orEmpty().lineSequence().firstOrNull().orEmpty() }
                body = Body.JSON
                bodyText = result.orEmpty()
            }
        }

        // 副标题兜底：绝不出现空白副标题
        if (subtitle.isBlank()) subtitle = result.orEmpty().lineSequence().firstOrNull().orEmpty().take(60)
        if (subtitle.isBlank()) subtitle = name

        return Summary(
            family = fam,
            subtitle = subtitle.trim(),
            metrics = metrics.filter { it.value != "—" && it.value.isNotBlank() },
            // 🔴 结果尚未回填 = 仍在执行中，此时一律不展示正文（哪怕已解析出参数）
            body = if (running) Body.EMPTY else body,
            bodyText = if (running) "" else bodyText,
            status = status,
            exitCode = exitCode,
            durationMs = durationMs,
        )
    }

    // ---------------- 族归类 ----------------

    /** 归族。顺序敏感：先匹配具体族，兜底 OTHER。 */
    fun familyOf(name: String): Family {
        val n = name.lowercase()
        return when {
            n.contains("web_search") || n.contains("webfetch") || n.contains("read_url") ||
                n.contains("open_url") || n.contains("http_request") || n == "web_" ->
                Family.WEB

            n.contains("run_code") || n.contains("python") || n.contains("exec_code") ||
                n.contains("node_run") || n.contains("creative_studio") ->
                Family.CODE

            n.contains("write_file") || n.contains("edit_file") || n.contains("aiwps_edit") ||
                n.contains("patch") || n.contains("move_file") || n.contains("copy_file") ||
                n.contains("delete_file") || n.contains("make_directory") ->
                Family.FILE_WRITE

            n.contains("read_file") || n.contains("list_dir") || n.contains("find_files") ||
                n.contains("file_info") || n.contains("grep") || n.contains("search_file") ->
                Family.FILE_READ

            n.contains("terminal") || n.contains("quroterm") || n.contains("shell") ||
                n.startsWith("linux_") || n.contains("root_exec") || n.contains("proot") ->
                Family.TERMINAL

            n.contains("tap") || n.contains("swipe") || n.contains("input_text") ||
                n.contains("scroll") || n.contains("screen") || n.contains("global_action") ||
                n.contains("click") || n.contains("key") || n.contains("shell_tap") ->
                Family.DEVICE

            n.contains("shizuku") || n.contains("device_admin") || n.contains("lock_screen") ||
                n.contains("set_camera") || n.contains("permission") || n.contains("root_status") ||
                n.contains("adb") || n.contains("open_app") || n.contains("package") ->
                Family.SYSTEM

            n.contains("doc") || n.contains("note") || n.contains("sheet") ||
                n.contains("table") || n.contains("ppt") || n.contains("office") || n.contains("aip") ->
                Family.DOC

            n.contains("image") || n.contains("video") || n.contains("audio") ||
                n.contains("tts") || n.contains("asr") || n.contains("voice") || n.contains("speech") ->
                Family.MEDIA

            n.contains("memory") || n.contains("summar") || n.contains("context") ->
                Family.MEMORY

            n.startsWith("ui_") || n.contains("miniapp") || n.contains("mini_app") ||
                n.contains("genui") || n.contains("card") || n.contains("widget") || n.contains("game_ui") ->
                Family.UI

            else -> Family.OTHER
        }
    }

    // ---------------- 状态判定 ----------------

    fun statusOf(result: String, fam: Family): Status {
        val head = result.take(200)
        // 🔴 终端类优先看退出码，比关键词可靠
        if (fam == Family.TERMINAL) {
            val ec = detectExitCode(result)
            if (ec != null) return if (ec == 0) Status.SUCCESS else Status.ERROR
        }
        return when {
            head.contains("❌") || head.contains("✗") || head.contains("Traceback") -> Status.ERROR
            head.contains("⚠") || head.contains("警告") || head.contains("warning") -> Status.WARNING
            head.contains("✅") || head.contains("✓") -> Status.SUCCESS
            head.contains("异常") || head.contains("失败") || head.contains("error") ||
                head.contains("Error") || head.contains("not found") || head.contains("缺少") -> Status.ERROR
            else -> Status.SUCCESS
        }
    }

    // ---------------- 小工具 ----------------

    /** 退出码：只认明确写出的，避免把普通数字误判成退出码。 */
    fun detectExitCode(result: String?): Int? {
        if (result.isNullOrBlank()) return null
        Regex("(?:exit[_ ]?code|退出码|exit status)\\s*[:=：]\\s*(-?\\d+)").find(result)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("\\bexit(?:ed)?\\s+(?:with\\s+)?(?:code\\s+)?(-?\\d+)\\b", RegexOption.IGNORE_CASE).find(result)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    /** 搜索结果条数。 */
    fun countSearchHits(result: String): String? {
        Regex("\"results\"\\s*:\\s*\\[").find(result)?.let {
            return null // JSON 数组不好数，退回行数
        }
        val n = Regex("^\\s*(?:\\d+[.)、]\\s+|[-*]\\s+).+").findAll(result).count()
        return if (n > 0) "$n 条" else null
    }

    /** 匹配行数。 */
    fun countMatches(result: String): String? {
        val m = Regex("(\\d+)\\s*(?:matches?|处|条匹配|个匹配)", RegexOption.IGNORE_CASE).find(result)
        if (m != null) return "${m.groupValues[1]} 处"
        return null
    }

    fun countLines(s: String): String = if (s.isBlank()) "—" else (s.count { it == '\n' } + 1).toString()

    /** 路径只保留末两段，中间用省略号（Android 路径又长又深）。 */
    fun shortPath(p: String): String {
        if (p.isBlank()) return ""
        val parts = p.split('/').filter { it.isNotBlank() }
        if (parts.size <= 2) return p
        return "…/" + parts.takeLast(2).joinToString("/")
    }

    /** 无语言标注时按关键字猜。 */
    fun guessLanguage(code: String): String {
        val c = code.trim()
        if (c.isEmpty()) return ""
        return when {
            c.startsWith("#!") && c.contains("python") -> "python"
            c.startsWith("#!") && (c.contains("bash") || c.contains("sh")) -> "shell"
            c.contains("def ") || c.contains("import ") && c.contains("print(") -> "python"
            c.contains("console.log") || c.contains("=>") && c.contains("{") -> "javascript"
            c.contains("fun ") || c.contains("val ") && c.contains(":") -> "kotlin"
            c.contains("public class") || c.contains("System.out") -> "java"
            c.contains("func ") || c.contains(":=") && c.contains("{") -> "go"
            c.contains("<?php") || c.startsWith("<?") -> "php"
            c.startsWith("<!DOCTYPE") || c.startsWith("<html") -> "html"
            else -> ""
        }
    }

    // ---------------- JSON 容错解析 ----------------

    /** 解析参数；失败或非对象都退回空对象，绝不抛异常打断渲染。 */
    private fun parseArgs(args: String): JSONObject =
        runCatching { JSONObject(args) }.getOrElse {
            runCatching { JSONObject(args.trim().removePrefix("```json").removeSuffix("```").trim()) }
                .getOrElse { JSONObject() }
        }

    /** 取第一个非空的候选键。 */
    private fun JSONObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = runCatching { optString(k, "") }.getOrDefault("")
            if (v.isNotBlank()) return v
        }
        // 🔴 模型有时把参数塞进 {"input":{"code":...}} 或 {"arguments":{...}}，下钻一层
        for (wrap in listOf("input", "arguments", "params", "args")) {
            val nested = runCatching { optJSONObject(wrap) }.getOrNull() ?: continue
            for (k in keys) {
                val v = runCatching { nested.optString(k, "") }.getOrDefault("")
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    /** 最长的字符串值 —— 用于未知工具兜底展示。 */
    private fun JSONObject.longestValue(limit: Int): String {
        var best = ""
        val it = keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = runCatching { opt(k) }.getOrNull() ?: continue
            val s = when (v) {
                is String -> v
                is JSONArray -> v.length().toString() + " 项"
                else -> continue
            }
            if (s.length > best.length) best = s
        }
        return best.take(limit)
    }
}
