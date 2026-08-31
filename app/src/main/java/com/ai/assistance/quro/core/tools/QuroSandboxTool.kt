package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内隔离沙箱工具。
 *
 * 所有文件操作限定在 `context.filesDir/sandbox` 内，通过 canonical 路径比对做
 * 路径穿越防护（../ 与符号链接越界一律拒绝），不需要 root / proot 等任何特权。
 * 让 AI 在一个与应用其它数据隔离的工作区里执行 shell、读写文件、做文本检索与替换。
 */
class QuroSandboxTool : QuroTool {
    override val name = "sandbox"
    override val description = """应用内隔离沙箱：在一个与应用其它数据隔离的工作区里执行 shell、读写文件、做文本检索与替换。

所有路径都相对沙箱根（context.filesDir/sandbox），带路径穿越防护（../ 与符号链接越界一律拒绝），不需要 root / proot。
动作（action）：
- exec：在沙箱内执行 shell 命令（参数 command），返回 stdout/stderr/exit_code
- read：读文件（参数 path，可选 offset/limit 行号）
- write：写文件（参数 path + content，覆盖写入，自动建父目录）
- list：列出目录（参数 path，默认沙箱根）
- grep：在文件或目录内按行正则匹配（参数 pattern + path）
- edit：在文件内替换首个匹配文本（参数 path + old_string + new_string）
- reset：清空沙箱（删除全部内容，谨慎使用）
- status：返回沙箱磁盘用量与文件数

示例：
{"action":"exec","command":"echo hi && ls -la"}
{"action":"write","path":"hello.txt","content":"hello world"}
{"action":"read","path":"hello.txt"}
{"action":"list"}
{"action":"grep","pattern":"error","path":"logs"}
{"action":"edit","path":"hello.txt","old_string":"world","new_string":"ZorvAI"}
{"action":"reset"}
{"action":"status"}"""
    override val parametersJson = """{
  "type":"object",
  "properties":{
    "action":{"type":"string","enum":["exec","read","write","list","grep","edit","reset","status"],"description":"沙箱动作"},
    "path":{"type":"string","description":"相对沙箱根的文件/目录路径（read/write/list/grep/edit 用）"},
    "command":{"type":"string","description":"要执行的 shell 命令（exec 用）"},
    "content":{"type":"string","description":"写入的文本内容（write 用）"},
    "old_string":{"type":"string","description":"被替换的文本（edit 用）"},
    "new_string":{"type":"string","description":"替换后的文本（edit 用）"},
    "pattern":{"type":"string","description":"grep 的正则（grep 用）"},
    "offset":{"type":"integer","description":"读文件起始行（从0，默认0）"},
    "limit":{"type":"integer","description":"读文件最大行数（默认0=全部）"}
  },
  "required":["action"]
}"""

    override fun run(context: Context, arguments: String): String {
        val json = runCatching { JSONObject(arguments) }.getOrElse { return err("参数不是合法 JSON") }
        val action = json.optString("action", "")
        val home = getSandboxHome(context)
        return try {
            when (action) {
                "exec" -> doExec(home, json.optString("command", ""))
                "read" -> doRead(home, json.optString("path", ""), json.optInt("offset", 0), json.optInt("limit", 0))
                "write" -> doWrite(home, json.optString("path", ""), json.optString("content", ""))
                "list" -> doList(home, json.optString("path", ""))
                "grep" -> doGrep(home, json.optString("pattern", ""), json.optString("path", ""))
                "edit" -> doEdit(home, json.optString("path", ""), json.optString("old_string", ""), json.optString("new_string", ""))
                "reset" -> doReset(home)
                "status" -> doStatus(home)
                else -> err("未知 action: $action（可选 exec/read/write/list/grep/edit/reset/status）")
            }
        } catch (e: SecurityException) {
            err("路径越界被拒绝：${e.message}")
        } catch (e: Exception) {
            err("执行失败: ${e.message}")
        }
    }

    private fun getSandboxHome(context: Context): File {
        val home = File(context.filesDir, "sandbox")
        if (!home.exists()) home.mkdirs()
        return home
    }

    /** 把相对路径安全解析到沙箱根内；越界抛 SecurityException。 */
    private fun resolve(home: File, rel: String): File {
        val base = home.canonicalFile
        val target = if (rel.isBlank()) base else File(home, rel).canonicalFile
        if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) {
            throw SecurityException("路径越界: $rel")
        }
        return target
    }

    private fun doExec(home: File, command: String): String {
        if (command.isBlank()) return err("command 为空")
        val pb = ProcessBuilder("sh", "-c", command)
        pb.directory(home)
        pb.redirectErrorStream(false)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val errOut = p.errorStream.bufferedReader().use { it.readText() }
        val ok = runCatching { p.waitFor(30, TimeUnit.SECONDS) }.getOrDefault(false)
        val code = if (ok) p.exitValue() else { p.destroy(); -1 }
        return JSONObject().apply {
            put("ok", true)
            put("exit_code", code)
            put("stdout", out)
            put("stderr", errOut)
            if (!ok) put("note", "执行超过30秒被强制终止")
        }.toString()
    }

    private fun doRead(home: File, rel: String, offset: Int, limit: Int): String {
        val f = resolve(home, rel)
        if (!f.isFile) return err("不是文件或不存在: $rel")
        val allLines = f.readLines()
        val start = if (offset < 0) 0 else offset
        val sub = if (limit > 0) allLines.drop(start).take(limit) else allLines.drop(start)
        return JSONObject().apply {
            put("ok", true)
            put("path", rel)
            put("total_lines", allLines.size)
            put("content", sub.joinToString("\n"))
        }.toString()
    }

    private fun doWrite(home: File, rel: String, content: String): String {
        if (rel.isBlank()) return err("path 为空")
        val f = resolve(home, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return JSONObject().apply {
            put("ok", true)
            put("path", rel)
            put("bytes", content.toByteArray(Charsets.UTF_8).size)
        }.toString()
    }

    private fun doList(home: File, rel: String): String {
        val dir = resolve(home, rel)
        if (!dir.isDirectory) return err("不是目录: $rel")
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("is_dir", f.isDirectory)
                put("size", if (f.isFile) f.length() else 0)
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("path", rel.ifBlank { "/" })
            put("entries", arr)
        }.toString()
    }

    private fun doGrep(home: File, pattern: String, rel: String): String {
        if (pattern.isBlank()) return err("pattern 为空")
        val re = runCatching { Regex(pattern) }.getOrElse { return err("正则非法: $pattern") }
        val root = resolve(home, rel)
        val files = if (root.isFile) listOf(root) else root.walkTopDown().filter { it.isFile }.toList()
        val arr = JSONArray()
        files.forEach { f ->
            runCatching {
                f.useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        if (re.containsMatchIn(line)) {
                            arr.put(JSONObject().apply {
                                put("path", f.relativeTo(home).path)
                                put("line", i + 1)
                                put("content", line)
                            })
                        }
                    }
                }
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("matches", arr)
            put("count", arr.length())
        }.toString()
    }

    private fun doEdit(home: File, rel: String, oldStr: String, newStr: String): String {
        if (rel.isBlank()) return err("path 为空")
        if (oldStr.isEmpty()) return err("old_string 为空")
        val f = resolve(home, rel)
        if (!f.isFile) return err("不是文件: $rel")
        val text = f.readText()
        if (!text.contains(oldStr)) return err("文件中未找到 old_string")
        val replaced = text.replace(oldStr, newStr)
        f.writeText(replaced)
        val count = if (oldStr.isNotEmpty()) (text.length - text.replace(oldStr, "").length) / oldStr.length else 0
        return JSONObject().apply {
            put("ok", true)
            put("path", rel)
            put("replaced_count", count)
        }.toString()
    }

    private fun doReset(home: File): String {
        home.listFiles()?.forEach { it.deleteRecursively() }
        return JSONObject().apply {
            put("ok", true)
            put("note", "沙箱已清空")
        }.toString()
    }

    private fun doStatus(home: File): String {
        var bytes = 0L
        var count = 0
        home.walkTopDown().forEach {
            if (it.isFile) { bytes += it.length(); count++ }
        }
        return JSONObject().apply {
            put("ok", true)
            put("files", count)
            put("bytes", bytes)
            put("kb", bytes / 1024)
        }.toString()
    }

    private fun err(msg: String): String =
        JSONObject().apply { put("ok", false); put("error", msg) }.toString()
}
