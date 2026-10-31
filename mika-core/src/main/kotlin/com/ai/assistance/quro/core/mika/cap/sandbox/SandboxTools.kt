package com.ai.assistance.quro.core.mika.cap.sandbox

import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.tool.SubActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.regex.PatternSyntaxException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.workspace.GatedFile
import com.ai.assistance.quro.core.mika.workspace.SandboxError
import com.ai.assistance.quro.core.mika.knowledge.ScriptManifest
import com.ai.assistance.quro.core.mika.workspace.WorkspaceFileGate
import com.ai.assistance.quro.core.mika.workspace.SandboxPaths

private const val MB = 1L shl 20
private const val GB = 1L shl 30
internal const val DEFAULT_TIMEOUT_SEC = 60
internal const val MAX_READ_BYTES = 262_144
/** 运行环境没准备好时,工具最多等这么久(首次启动解包约一两分钟)。 */
internal const val PREPARE_WAIT_MS = 120_000L
private const val MIN_FREE = 500 * MB
private const val WORKSPACE_WARN = 2 * GB
private const val WORKSPACE_MAX = 4 * GB
private const val DEFAULT_READ = 65_536
private const val MAX_WRITE = MB
private const val MAX_IMAGE = 5 * MB
private const val MAX_SCRIPT_ARGS = 50
private const val MAX_MATCHES = 100
private const val MATCH_TEXT = 300
/** 搜索时跳过比这大的文件。 */
private const val SEARCH_FILE_MAX = 8 * MB

private val IMAGE_TYPES = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp")
private val KNOWN_TYPES = IMAGE_TYPES + mapOf(
    "gif" to "image/gif", "pdf" to "application/pdf", "zip" to "application/zip", "gz" to "application/gzip",
    "mp3" to "audio/mpeg", "mp4" to "video/mp4", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

private const val NOTE_NETWORK_ON = "Network access for the sandbox was turned on since your last sandbox call."
private const val NOTE_NETWORK_OFF = "Network access for the sandbox was turned off since your last sandbox call; commands can no longer reach the internet."
private const val OUTSIDE = "Paths must stay inside /workspace (or /opt/quro/skills for reading); links that lead outside are refused."
private const val HINT_TRUNCATED = "The output was clipped to its head and tail. The full output is in fullOutputPath; read it with read_file (offset/limit)."
private const val HINT_DROPPED = "Only the first 1 MB of output was kept. Write large output to a file instead of printing it."
private const val HINT_TIMED_OUT = "The command hit its time limit and was stopped. Use a larger timeoutSec (up to 600) or do less per command."
private const val HINT_KILLED = "Killed by the system (out of memory): the system killed the command, most likely for lack of memory. Use a lighter approach (smaller inputs, process in parts)."
private const val HINT_WORKSPACE_BIG = "The workspace uses more than 2 GB; delete files you no longer need (over 4 GB, writing is refused)."

/** 六个沙箱工具的执行;文件类工具由 Kotlin 直接读写宿主目录,不起沙箱进程。 */
/** search_files 的上限:最多搜多少个文件、读多少字节、用多久。 */
internal data class SearchLimits(val maxFiles: Int = 5000, val maxBytes: Long = 64 * MB, val timeMs: Long = 10_000)

private class SearchTimeout : RuntimeException(null, null, false, false)

/** 正则每读一个字都看一眼时间:写坏的正则(灾难性回溯)到点就停。 */
private class Deadlined(private val s: CharSequence, private val deadline: Long) : CharSequence {
    override val length get() = s.length
    override fun get(index: Int): Char {
        if (System.nanoTime() > deadline) throw SearchTimeout()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Deadlined(s.subSequence(startIndex, endIndex), deadline)
    override fun toString() = s.toString()
}

internal class SandboxTools(
    private val sandbox: SandboxPort,
    private val workspace: () -> String,
    private val visionEnabled: Boolean,
    private val search: SearchLimits = SearchLimits(),
    private val onCommand: (String) -> Unit = {},
    private val onProgress: (String) -> Unit = {},
    /** 运行环境还没准备好、工具在等它时(任务卡片显示「正在准备」)。 */
    private val onPreparing: () -> Unit = {},
    /** 开始跑技能脚本时(参数:技能目录/脚本 与参数);不给就按命令报。 */
    private val onScript: ((String) -> Unit)? = null,
    private val prepareWaitMs: Long = PREPARE_WAIT_MS,
) {
    /** 设备支持就提供工具:运行环境首次启动时在后台准备,工具调用时等它。 */
    fun available(): Boolean = sandbox.status() !is SandboxStatus.Unsupported

    /** 沙箱目录的树锁。 */
    val tree get() = sandbox.tree

    /** 等运行环境就绪后在本任务的工作区里做 [op];联网设置在任务中途变了时结果里另说一句。 */
    suspend fun run(op: suspend SandboxTools.(ws: String, ready: SandboxStatus.Ready) -> ToolExecution): ToolExecution {
        var status = sandbox.status()
        if (status !is SandboxStatus.Ready && status !is SandboxStatus.Unsupported) {
            onPreparing()
            status = sandbox.awaitReady(prepareWaitMs)
        }
        val ready = status as? SandboxStatus.Ready ?: return fail("sandbox_unavailable",
            if (status is SandboxStatus.Unsupported) "These tools are not supported on this device. Do the task without them."
            else "The Linux environment is not ready yet (${describe(status)}). Try again in a minute, or do the task without these tools.",
            recoverable = status !is SandboxStatus.Unsupported)
        val ws = workspace()
        val result = try { op(ws, ready) } catch (e: SandboxError) {
            fail(e.code, e.message ?: e.code, e.recoverable)
        }
        // 任务中途用户在设置里开关了联网:下一个沙箱工具的结果里说一句,模型别按旧的状态做
        val before = lastNetwork
        lastNetwork = ready.network
        if (before == null || before == ready.network) return result
        val note = if (ready.network) NOTE_NETWORK_ON else NOTE_NETWORK_OFF
        return ToolExecution(JsonObject(result.content + ("networkNote" to JsonPrimitive(note))), changed = result.changed,
            outcome = result.outcome, frame = result.frame, images = result.images, record = result.record, screenElements = result.screenElements)
    }

    private var lastNetwork: Boolean? = null

    private fun describe(s: SandboxStatus) = when (s) {
        is SandboxStatus.Unsupported -> "not supported on this device: ${s.reason}"
        SandboxStatus.NotInstalled -> "not installed"
        is SandboxStatus.Installing -> "installing, ${s.percent}%"
        is SandboxStatus.Broken -> "broken: ${s.reason}"
        is SandboxStatus.Ready -> "ready"
    }

    internal suspend fun runCommand(a: Args, ws: String, callId: String = "sandbox", sub: SubActions? = null, tools: ToolIndex? = null): ToolExecution {
        val command = a.str("command", max = 20_000)!!
        val timeout = a.int("timeoutSec", 1, 600) ?: DEFAULT_TIMEOUT_SEC
        val cwd = a.str("cwd", max = 1000)?.let { raw ->
            val n = SandboxPaths.normalize(raw)?.takeIf(SandboxPaths::isWritable) ?: return outside()
            n.removePrefix(SandboxPaths.WORKSPACE).trimStart('/').ifEmpty { null }
        }
        storageLow()?.let { return it }
        onCommand(command)
        return commandResult(sandbox.runWithPhone(ws, listOf("/bin/sh", "-lc", command), cwd, timeout * 1000L, onProgress,
            phoneHandler(callId, sub, tools)), ws)
    }

    internal suspend fun runScript(a: Args, ws: String, network: Boolean, callId: String = "script", sub: SubActions? = null, tools: ToolIndex? = null): ToolExecution {
        val source = a.str("source", max = 300)!!
        val script = a.str("script", max = 500)!!
        val args = scriptArgs(a.o["args"])
        val timeout = a.int("timeoutSec", 1, 600) ?: DEFAULT_TIMEOUT_SEC
        val skillDir = SandboxPaths.normalize(source, SandboxPaths.SKILLS)?.takeIf { it.startsWith("${SandboxPaths.SKILLS}/") }
            ?: return unavailable("source must be a directory under ${SandboxPaths.SKILLS}, e.g. skills/documents.")
        val path = SandboxPaths.normalize(script, skillDir)?.takeIf { it.startsWith("$skillDir/") }
            ?: return unavailable("script must be a path inside the source directory, e.g. scripts/tool.py.")
        val skillRel = skillDir.removePrefix("${SandboxPaths.SKILLS}/")
        val rel = path.removePrefix("$skillDir/")
        val interpreter = ScriptManifest.interpreterFor(rel)
            ?: return unavailable("only .py, .mjs, .js and .sh scripts can be run.")
        // 清单的 file 写技能目录下的路径;只写了文件名(相对 scripts/)的也认
        // 先把技能目录核对成打包的那份:App 更新带来的新脚本立刻可用,被改过的换回原样
        sandbox.prepare()
        val entry = sandbox.scriptManifest(skillRel).firstOrNull { it.file == rel || "scripts/${it.file}" == rel }
            ?: return unavailable("$rel is not listed as a runnable script of $skillRel (knowledge_read or skill_read lists its scripts).")
        val (runnable, why) = ScriptManifest.effectiveRunnable(entry, network)
        if (!runnable) return unavailable("$rel is not runnable now: $why.")
        if (sandbox.hostPath(ws, path, forWrite = false)?.isFile != true) return unavailable("$rel is missing from the source directory.")
        storageLow()?.let { return it }
        // /opt/quro/skills 在沙箱里写得动(PRoot 的绑定可写),所以跑之前逐字节核对打包的那份
        if (!sandbox.verifyScript(skillRel, rel)) return unavailable("$rel differs from the copy that comes with ZorvAI and could not be restored, so it was not run.")
        (onScript ?: onCommand)((listOf("$skillRel/$rel") + args).joinToString(" "))
        return commandResult(sandbox.runWithPhone(ws, listOf(interpreter, path) + args, null, timeout * 1000L, onProgress,
            phoneHandler(callId, sub, tools)), ws)
    }

    /** 能调哪些由工具自己声明([com.ai.assistance.quro.core.mika.tool.ToolInfo.sandboxCallable]);没给工具表时一个都不开放。 */
    private fun phoneHandler(callId: String, sub: SubActions?, tools: ToolIndex?): PhoneCommandHandler? = sub?.let { runner ->
        val sequence = AtomicInteger()
        PhoneCommandHandler { requested, args ->
            val tool = when (requested.removePrefix("phone_")) {
                "type" -> "phone_type_text"
                "clear" -> "phone_clear_text"
                "key" -> "phone_press_key"
                "launch" -> "phone_launch_app"
                "dump" -> "phone_list_elements"
                else -> "phone_${requested.removePrefix("phone_")}"
            }
            if (tools?.get(tool)?.sandboxCallable != true) buildJsonObject { put("ok", false); put("error", "unknown_phone_command") }
            else runner.run(Part.ToolCall("$callId.phone${sequence.incrementAndGet()}", tool, args.toString())).content
        }
    }

    private fun scriptArgs(v: kotlinx.serialization.json.JsonElement?): List<String> {
        if (v == null || v is JsonNull) return emptyList()
        if (v !is JsonArray) throw ArgError("args", "must be an array of strings")
        if (v.size > MAX_SCRIPT_ARGS) throw ArgError("args", "must have at most $MAX_SCRIPT_ARGS items")
        return v.map { e ->
            val p = e as? JsonPrimitive
            if (p == null || !p.isString || p.content.length > 10_000) throw ArgError("args", "items must be strings up to 10000 characters")
            p.content
        }
    }

    private fun storageLow(): ToolExecution? =
        if (sandbox.freeBytes() < MIN_FREE) fail("storage_low", "The phone has less than 500 MB free, so sandbox commands are refused. Tell the user to free up space.", recoverable = false)
        else null

    private suspend fun commandResult(r: CommandResult, ws: String): ToolExecution {
        val (shown, clipped) = OutputClip.clip(r.output)
        val truncated = clipped || r.droppedBytes > 0
        // 存不下完整输出也照样交出结果
        val full = if (clipped) runCatching { sandbox.tree.shared { sandbox.saveOutput(ws, r.output) } }.getOrNull() else null
        val hints = listOfNotNull(
            HINT_TIMED_OUT.takeIf { r.timedOut },
            HINT_KILLED.takeIf { r.killedBySystem },
            HINT_TRUNCATED.takeIf { full != null },
            HINT_DROPPED.takeIf { r.droppedBytes > 0 },
            workspaceHint(ws),
        )
        return ToolExecution(buildJsonObject {
            put("ok", r.exitCode == 0 && !r.timedOut)
            put("exitCode", r.exitCode); put("timedOut", r.timedOut); put("killedBySystem", r.killedBySystem)
            put("durationMs", r.durationMs); put("output", shown); put("outputTruncated", truncated)
            full?.let { put("fullOutputPath", it) }
            if (r.droppedBytes > 0) put("droppedBytes", r.droppedBytes)
            put("network", if (r.network) "on" else "off")
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        })
    }

    private fun workspaceHint(ws: String) = HINT_WORKSPACE_BIG.takeIf { sandbox.workspaceBytes(ws) > WORKSPACE_WARN }

    /** 规范化后的沙箱路径与它在关口里的 (根目录, 相对路径);越界(含链接指到外面)为 null。 */
    private class Target(val path: String, val root: File, val rel: String) {
        val host: File get() = if (rel.isEmpty()) root else File(root, rel)
    }

    private fun resolve(ws: String, raw: String, write: Boolean): Target? {
        val n = SandboxPaths.normalize(raw) ?: return null
        if (write && !SandboxPaths.isWritable(n)) return null
        // 先按路径判断越界,给出清楚的错误;真正读写时关口在句柄上再核一次
        sandbox.hostPath(ws, n, write) ?: return null
        val (root, rel) = sandbox.hostRoot(ws, n, write) ?: return null
        return Target(n, root, rel)
    }

    /** 关口拒绝时的工具结果:错误码原样,消息带上沙箱路径。 */
    private fun refused(path: String, e: SandboxError): ToolExecution = when (e.code) {
        WorkspaceFileGate.FILE_NOT_FOUND -> fail(e.code, "$path does not exist.")
        WorkspaceFileGate.NOT_A_FILE -> fail(e.code, "$path is not a regular file (a directory, link or special file); list directories with run_command (ls). ${e.message}")
        WorkspaceFileGate.OUTSIDE -> outside()
        else -> fail(e.code, "$path: ${e.message}", e.recoverable)
    }

    internal fun readFile(a: Args, ws: String): ToolExecution {
        val t = resolve(ws, a.str("path", max = 4096)!!, write = false) ?: return outside()
        val offset = a.int("offset", 0, Int.MAX_VALUE) ?: 0
        val limit = a.int("limit", 1, MAX_READ_BYTES) ?: DEFAULT_READ
        val g = try { sandbox.files.openRead(t.root, t.rel) } catch (e: SandboxError) { return refused(t.path, e) }
        return g.use { readOpened(t.path, g, offset, limit) }
    }

    private fun readOpened(path: String, g: GatedFile, offset: Int, limit: Int): ToolExecution {
        val size = g.size
        val ext = g.name.substringAfterLast('.', "").lowercase()
        IMAGE_TYPES[ext]?.let { type ->
            if (!visionEnabled) return notText(path, size, type, "This is an image, and the current model cannot view images.")
            if (size > MAX_IMAGE) return fail("file_too_large", "$path is ${size / MB} MB; images over 5 MB are not returned. Make a smaller copy first.")
            return ToolExecution(okObj { put("path", path); put("size", size); put("type", type) }, image = g.read(0, size.toInt()))
        }
        val bytes = if (offset >= size) ByteArray(0) else g.read(offset.toLong(), minOf(limit.toLong(), size - offset).toInt())
        // 从字的中间开始读:跳过开头的残字节
        var skip = 0
        if (offset > 0) while (skip < 3 && skip < bytes.size && (bytes[skip].toInt() and 0xC0) == 0x80) skip++
        val eof = offset + bytes.size >= size
        val (text, used) = decodeUtf8(bytes, skip, eof) ?: return notText(path, size, KNOWN_TYPES[ext] ?: "application/octet-stream", "This file is not UTF-8 text.")
        val next = offset + skip + used
        return ToolExecution(okObj {
            put("path", path); put("size", size); put("offset", offset); put("content", text)
            put("eof", next >= size)
            if (next < size) put("nextOffset", next)
        })
    }

    /** 严格按 UTF-8 解码;不是文本(解不开或含 NUL)为 null。没读到文件末尾时,末尾不完整的字留给下一次。 */
    private fun decodeUtf8(bytes: ByteArray, from: Int, eof: Boolean): Pair<String, Int>? {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, from, bytes.size - from)
        val out = CharBuffer.allocate(bytes.size)
        if (dec.decode(input, out, eof).isError) return null
        if (eof && (dec.flush(out).isError || input.hasRemaining())) return null
        out.flip()
        val s = out.toString()
        if ('\u0000' in s) return null
        return s to (input.position() - from)
    }

    internal fun writeFile(a: Args, ws: String): ToolExecution {
        val t = resolve(ws, a.str("path", max = 4096)!!, write = true) ?: return outside()
        val path = t.path
        val content = a.str("content", min = 0)!!.toByteArray(Charsets.UTF_8)
        val append = a.bool("append", false)
        if (content.size > MAX_WRITE) return fail("file_too_large", "content is ${content.size} bytes; at most 1 MB per write. Write in parts with append.")
        if (sandbox.workspaceBytes(ws) > WORKSPACE_MAX) return fail("storage_low", "The workspace uses more than 4 GB, so writing is refused. Delete files you no longer need.", recoverable = false)
        // 已有的文件在句柄上核实(普通文件、一个硬链接、在工作区里)之后才截断或接着写
        val size = try {
            sandbox.files.openWrite(t.root, t.rel, append).use { g -> g.write(content); g.currentSize() }
        } catch (e: SandboxError) {
            return if (e.code == WorkspaceFileGate.NOT_A_FILE) fail(e.code, "$path exists and is not a regular file (a directory, link or special file). ${e.message}")
            else refused(path, e)
        }
        val hint = workspaceHint(ws)
        return ToolExecution(okObj {
            put("path", path); put("bytesWritten", content.size); put("size", size)
            hint?.let { put("hint", it) }
        })
    }

    internal suspend fun searchFiles(a: Args, ws: String): ToolExecution {
        val raw = a.str("pattern", max = 1000)!!
        val regex = try { Regex(raw) } catch (e: PatternSyntaxException) { throw ArgError("pattern", "not a valid regular expression: ${e.description}") }
        val t = resolve(ws, a.str("path", max = 4096) ?: SandboxPaths.WORKSPACE, write = false) ?: return outside()
        val base = t.path
        val glob = a.str("glob", max = 300)?.let { g -> FileSystems.getDefault().getPathMatcher("glob:$g") to ('/' in g) }
        val rootPath = t.host.toPath()
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            // 工作区本身还没建(读不建目录):就是空的,不算找不到
            if (t.rel.isEmpty() && SandboxPaths.isWritable(base)) return ToolExecution(okObj { put("matches", JsonArray(emptyList())); put("truncated", false) })
            return fail("file_not_found", "$base does not exist.")
        }
        val matches = mutableListOf<JsonObject>()
        var stoppedBy: String? = null
        var files = 0
        var bytesRead = 0L
        val deadline = System.nanoTime() + search.timeMs * 1_000_000
        // 不跟随符号链接:链接可能指到工作区外面;边走边搜,不先把整棵树列出来
        Files.walk(rootPath).use { stream ->
            val it = stream.iterator()
            while (it.hasNext()) {
                currentCoroutineContext().ensureActive()
                val p = it.next()
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue
                if (glob != null && !glob.first.matches(if (glob.second) p.relativeTo(rootPath) else p.fileName)) continue
                // 列目录只用来找文件;读内容走关口,中途被换成链接的读不到
                val rel = listOf(t.rel, rootPath.relativize(p).joinToString("/") { it.name }).filter { it.isNotEmpty() }.joinToString("/")
                val g = try { sandbox.files.openRead(t.root, rel) } catch (e: SandboxError) { continue }
                val bytes = g.use {
                    val size = it.size
                    if (size > SEARCH_FILE_MAX) return@use null
                    if (++files > search.maxFiles || bytesRead + size > search.maxBytes) { stoppedBy = "size"; return@use null }
                    bytesRead += size
                    it.read(0, size.toInt())
                }
                if (stoppedBy != null) break
                val text = bytes?.let { decodeUtf8(it, 0, eof = true)?.first } ?: continue
                try {
                    for ((i, line) in text.lineSequence().withIndex()) {
                        if (!regex.containsMatchIn(Deadlined(line, deadline))) continue
                        if (matches.size >= MAX_MATCHES) { stoppedBy = "matches"; break }
                        matches += buildJsonObject {
                            put("file", sandboxPathOf(base, rootPath, p)); put("line", i + 1); put("text", line.take(MATCH_TEXT))
                        }
                    }
                } catch (e: SearchTimeout) {
                    stoppedBy = "time"
                }
                if (stoppedBy != null) break
                if (System.nanoTime() > deadline) { stoppedBy = "time"; break }
            }
        }
        return ToolExecution(okObj {
            put("matches", JsonArray(matches)); put("truncated", stoppedBy != null)
            when (stoppedBy) {
                "size" -> put("hint", "Stopped after searching ${search.maxFiles} files or ${search.maxBytes / MB} MB. Narrow it with path or glob.")
                "time" -> put("hint", "Stopped after ${search.timeMs / 1000} seconds; the pattern may be too slow. Simplify it or narrow it with path or glob.")
            }
        })
    }

    private fun sandboxPathOf(base: String, root: Path, p: Path): String {
        if (p == root) return base
        return "$base/" + p.relativeTo(root).joinToString("/") { it.name }
    }

    internal suspend fun shareFile(a: Args, ws: String): ToolExecution {
        val (f, name) = withContext(Dispatchers.IO) { ShareRules.check(sandbox, ws, a.str("path", max = 4096)!!, a.str("name", min = 0, max = 200)) }
        val saved = sandbox.share(f, name)
        return ToolExecution(okObj { put("savedAs", saved) })
    }

    private fun notText(path: String, size: Long, type: String, why: String) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", "not_text"); put("message", "$why $path is $size bytes ($type); its content is not returned.")
        put("recoverable", true); put("size", size); put("type", type)
    })

    private fun unavailable(why: String) = fail("script_unavailable", why, recoverable = false)
    private fun outside() = fail("path_outside_workspace", OUTSIDE)

    private fun okObj(b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("ok", true); b() }

    private fun fail(error: String, message: String, recoverable: Boolean = true) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("recoverable", recoverable)
    })
}
