package com.ai.assistance.quro.core.mika.cap.sandbox

import com.ai.assistance.quro.core.mika.tool.Entry
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Place
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Concurrency
import com.ai.assistance.quro.core.mika.tool.EvidenceLog
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import com.ai.assistance.quro.core.mika.tool.bool
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.str
import com.ai.assistance.quro.core.mika.tool.strArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val SANDBOX = "sandbox"

private const val TIMEOUT = "Time limit in seconds, 1-600 (default 60). The command is stopped when it runs out."

/**
 * 沙箱一组:设备支持时提供(运行环境没准备好时工具调用会等它)。路径都是沙箱里的路径;
 * 不是屏幕操作,不过锁屏关卡,也不进屏幕守卫(沙箱守卫另管)。执行在 [com.ai.assistance.quro.core.mika.cap.sandbox.SandboxTools] 里。
 */
internal interface SandboxTool : AgentTool {
    override val capability: String get() = SANDBOX
    override val offScreen: Boolean get() = true
    override fun available(ctx: ToolContext) = ctx.sandboxRunner != null
    override fun offered(ctx: ToolContext) = ctx.sandboxAvailable()
}

/** 命令与脚本的输出(失败的输出也是事实,照样记)、读到的文件、写入与导出的路径,都是模型可能引用的事实。 */
private fun res(r: ToolExecution, k: String) = (r.content[k] as? JsonPrimitive)?.contentOrNull
private fun ok(r: ToolExecution) = r.content["ok"] == JsonPrimitive(true)

internal object RunCommand : SandboxTool {
    override val verb = Verb.RunCommand
    override val label = "处理文件"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val composite = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("run_command",
        Prompts.tool("run_command"),
        mapOf(
            "command" to buildJsonObject { put("type", "string"); put("description", "The shell command, 1-20000 characters."); put("minLength", 1); put("maxLength", 20000) },
            "timeoutSec" to int(TIMEOUT, 1, 600),
            "cwd" to str("Optional working directory, a relative path under /workspace."),
        ), listOf("command"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> runCommand(a, ws, ctx.callId, ctx.sub, ctx.tools) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("command")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        // 命令是助手自己写的:放在来处里,不当证据;输出里原样是命令里的字的行(echo 回显)也不算
        listOfNotNull(res(r, "output")?.let { output("退出码 ${res(r, "exitCode")}:\n$it", Place.command("run_command「${o.argStr("command")?.take(200)}」"), o.argStr("command")) })
}

internal object RunBundledScript : SandboxTool {
    override val verb = Verb.RunBundledScript
    override val label = "运行自带脚本"
    override val risk = Risk.Write
    override val composite = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("run_bundled_script",
        Prompts.tool("run_bundled_script"),
        mapOf(
            "source" to str("Directory the script ships in, under /opt/quro/skills: the scriptSource from knowledge_read or skill_read, e.g. skills/documents."),
            "script" to str("Path of the script inside that directory, e.g. scripts/extract_pdf.py."),
            "args" to strArray("Arguments, at most 50, each passed unchanged.", 50),
            "timeoutSec" to int(TIMEOUT, 1, 600),
        ), listOf("source", "script"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, ready -> runScript(a, ws, ready.network, ctx.callId, ctx.sub, ctx.tools) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = listOfNotNull(o.argStr("source"), o.argStr("script")).joinToString("/")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        listOfNotNull(res(r, "output")?.let { output("退出码 ${res(r, "exitCode")}:\n$it", Place.command("run_bundled_script ${o.argStr("source")}/${o.argStr("script")}"), o["args"]?.toString()) })
}

internal object ReadFile : SandboxTool {
    override val verb = Verb.ReadFile
    override val label = "读文件"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("read_file",
        Prompts.tool("read_file"),
        mapOf(
            "path" to str("Sandbox path, absolute or relative to /workspace."),
            "offset" to int("Byte offset to start at (default 0).", 0),
            "limit" to int("Bytes to read, 1-262144 (default 65536).", 1, MAX_READ_BYTES),
        ), listOf("path"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> withContext(Dispatchers.IO) { readFile(a, ws) } }
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        // 读回助手自己用 write_file 写的文件,读到的是它自己写的字,不算证据
        listOfNotNull(if (ok(r) && res(r, "path") !in log.writtenPaths) res(r, "content")?.let { output(it, Place.file("read_file ${res(r, "path")}")) } else null)
}

internal object WriteFile : SandboxTool {
    override val verb = Verb.WriteFile
    override val label = "写文件"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("write_file",
        Prompts.tool("write_file"),
        mapOf(
            "path" to str("Sandbox path under /workspace, absolute or relative."),
            "content" to str("The text to write, at most 1 MB."),
            "append" to bool("Append instead of replacing (default false)."),
        ), listOf("path", "content"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> tree.shared { withContext(Dispatchers.IO) { writeFile(a, ws) } } }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (!ok(r)) return emptyList()
        res(r, "path")?.let { log.writtenPaths += it }
        return listOf(output("write_file 写入 ${res(r, "path")}(${res(r, "bytesWritten")} 字节,文件现为 ${res(r, "size")} 字节)"))
    }
}

internal object SearchFiles : SandboxTool {
    override val verb = Verb.SearchFiles
    override val label = "搜索文件"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("search_files",
        Prompts.tool("search_files"),
        mapOf(
            "pattern" to str("Regular expression to search for."),
            "path" to str("Directory or file to search (default /workspace)."),
            "glob" to str("Optional file name filter, e.g. *.csv or **/*.py."),
        ), listOf("pattern"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> withContext(Dispatchers.IO) { searchFiles(a, ws) } }
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (!ok(r)) return emptyList()
        return listOfNotNull((r.content["matches"] as? JsonArray)?.joinToString("\n") { m ->
            val mo = m as? JsonObject
            "${(mo?.get("file") as? JsonPrimitive)?.contentOrNull}:${(mo?.get("line") as? JsonPrimitive)?.contentOrNull}: ${(mo?.get("text") as? JsonPrimitive)?.contentOrNull}"
        }?.let { output(it, Place.command("search_files「${o.argStr("pattern")?.take(100)}」")) })
    }
}

internal object ShareFile : SandboxTool {
    override val verb = Verb.ShareFile
    override val label = "导出文件"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("share_file",
        Prompts.tool("share_file"),
        mapOf(
            "path" to str("Sandbox path of the file under /workspace."),
            "name" to str("Optional file name to save as (default: the file's own name)."),
        ), listOf("path"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> shareFile(a, ws) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        listOfNotNull(if (ok(r)) output("share_file 已存到 ${res(r, "savedAs")}") else null)
}

/** 沙箱给出的一条观察:命令输出、文件内容、写入与导出的路径。[authored] 是助手自己写的命令(输出里的回显不记)。 */
private fun output(text: String, place: Place? = null, authored: String? = null) =
    Observation(ObsKind.Output, text, source = SANDBOX, place = place, authored = authored)

internal object SandboxGroup {
    val tools: List<AgentTool> = listOf(RunCommand, RunBundledScript, ReadFile, WriteFile, SearchFiles, ShareFile)
}
