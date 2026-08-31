package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ai.assistance.quro.core.linux.CommandTranslator
import com.ai.assistance.quro.core.guest.QuroContainerManager
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.vm.QuroVmEnv
import com.ai.assistance.quro.core.privilege.QuroShellQuote
import com.ai.assistance.quro.terminal.kai.TerminalScreen
import com.ai.assistance.quro.terminal.kai.TerminalSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.ai.assistance.quro.core.aidlaci.AciNativeBridge
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciManager
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 自包含的交互式 shell 会话（v127 重写，彻底移除 Termux / PTY 依赖）。
 *
 * 设计采用常驻 shell 进程的 sandbox 思路：
 * 不依赖原生 PTY（Termux terminal-emulator 在 Compose 布局期会因 mRenderer.mFontWidth
 * 空指针而崩溃），改为常驻一个 shell 进程、把命令写进其 stdin、并发地把 stdout/stderr
 * 按行读入 Compose 的 SnapshotStateList 滚动缓冲区。
 *
 * - Linux 模式：常驻 `proot -R <rootfs> -b /system ... /bin/sh`（经 [QuroLinuxEnv.shellLaunch]），
 *   获得 python3 / nslookup / 任意写等完整能力；
 * - 设备模式：常驻 `/system/bin/sh`（Toybox），免权限、无 root/Shizuku。
 *
 * 命令完成检测用「哨兵协议」，见 [QuroTerminalSentinel]：每条命令后追加一行
 *   printf '\n\036<随机token>:%d:%s\036\n' "$?" "$PWD"
 * 读取端识别该哨兵行即可拿到上条命令的退出码与当前工作目录，并复位 busy 状态、打印新提示符。
 * 因 stdin 是管道而非 tty，shell 不会回显输入、也不会画提示符，所以命令回显与新提示符由本类手动补全。
 *
 * **E-8**：哨兵 token 从固定的 `QURO_DONE` 改为**每会话随机**，避免
 * `echo QURO_DONE` / `grep -r QURO_DONE` 这类命令的输出被误判为「命令已结束」。
 *
 * **E-9**：新增 [interrupt]，两阶段中断（软 ETX → 硬杀进程），
 * 弥补无 PTY 时无法投递 SIGINT 的缺陷。
 */
class QuroShellSession private constructor(
    private val context: Context,
    val mode: ShellMode,
    command: List<String>,
    env: Array<String>,
    cwd: String,
    externalProcess: Process? = null,
) : CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.IO + job

    /** VM 真 TTY 模式下，回显/提示符/信号全部由 guest 完成，本层只透传输入、不注入哨兵。 */
    private val passthroughEcho = mode == ShellMode.VM

    /** 滚动缓冲区（每行一条），由 Compose LazyColumn 渲染（VT 未启用时的兜底）。 */
    val lines = mutableStateListOf<String>()

    /**
     * 可选 VT 渲染引擎（移植自 Kai 的 VT100/xterm 引擎）。
     * 非 null 时，[drain] 会把**原始（含 ANSI 转义）**字节喂给它，由 Compose 画布渲染
     * 出真·终端（颜色 / 光标 / 加粗 / 清屏）；[lines] 仍同步维护一份纯文本用于导出与兜底。
     * 设为 null（默认）则维持旧管道行为，终端 UI 走 LazyColumn 纯文本。
     */
    var vt: TerminalScreen? = null

    /** VT 屏幕的不可变快照状态，Compose 渲染层收集它即可随终端输出重组。 */
    val vtSnapshot = mutableStateOf<TerminalSnapshot?>(null)

    /** 进程是否已退出。 */
    var exited by mutableStateOf(false)
        private set

    /** 退出码（进程自然结束时填充）。 */
    var exitCode by mutableIntStateOf(-1)
        private set

    /** 上一条命令的退出码（哨兵解析得到）。 */
    var lastExit by mutableIntStateOf(0)
        private set

    /** 是否正在等待当前命令完成（哨兵未回）。 */
    var busy by mutableStateOf(false)
        private set

    /** 当前工作目录（哨兵解析得到）。 */
    var cwdState by mutableStateOf(cwd)
        private set

    /** 进程退出回调（controller / 工具可挂接）。 */
    var onExit: ((Int) -> Unit)? = null

    /**
     * host 模式标志：终端由原生 qurohost 后端驱动（[QuroHostBridge] 启动的 libqurohost.so）。
     * 此模式下 [sendControl] 可下发 CMS / 开发者环境的原生控制命令，[drain] 会识别
     * qurohost 回传的控制响应并转交 [controlCallback]。否则（旧直连路径）这些能力不可用。
     */
    var isHost: Boolean = false
        internal set

    /** 控制响应回调：qurohost 发回的 US"@qurohost-resp " 行（已剥前缀，为 JSON 文本）。 */
    var controlCallback: ((String) -> Unit)? = null

    /**
     * 本会话专属的随机哨兵 token（E-8）。
     *
     * 每个会话一个，命令输出撞上的概率可忽略。旧实现全局固定 `QURO_DONE`，
     * `echo QURO_DONE` 就能让 busy 提前复位、退出码错乱。
     */
    private val doneToken: String = QuroTerminalSentinel.newToken()

    /**
     * 最近一次命令是否被用户中断（E-9）。
     * 供 UI 区分「命令正常结束」与「被 ^C 打断」。
     */
    var lastInterrupted by mutableStateOf(false)
        private set

    /**
     * 下一次哨兵回来时不打印提示符。
     *
     * 用于 [restoreCwd] 这类**内部**命令：它们不该在滚动区里留下痕迹，
     * 否则用户会看到凭空多出来的空提示符行。
     */
    @Volatile
    private var suppressNextPrompt: Boolean = false

    private val process: Process = externalProcess ?: try {
        val pb = ProcessBuilder(command)
        pb.directory(File(cwd))
        pb.environment().clear()
        for (e in env) {
            val idx = e.indexOf('=')
            if (idx > 0) pb.environment()[e.substring(0, idx)] = e.substring(idx + 1)
        }
        pb.redirectErrorStream(true)
        pb.start()
    } catch (e: Exception) {
        throw IllegalStateException("启动 shell 失败: ${e.message}", e)
    }

    private val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

    init {
        Log.i(TAG, "init: 创建会话, 模式=$mode, 命令=${command.joinToString(" ")}")
        when (mode) {
            ShellMode.VM -> appendLine("— Zorv AI 终端（VM · 完整 Linux 内核）已启动 —")
            ShellMode.LINUX -> appendLine("— Zorv AI 终端已启动 (proot/Linux · Ubuntu 24.04) —")
            ShellMode.DEVICE -> {
                appendLine("— Zorv AI 终端已启动 (设备 · Toybox sh) —")
                // ⚠ 设备模式 = proot 未启用，所有 Linux 命令都不可用。
                // 把**原因直接打进终端缓冲区**：用户在真机上无需 adb/logcat，截图即可取证。
                appendLine("⚠ 当前是 Android 设备 shell，apt-get / dpkg / python3 / node 等 Linux 命令均不可用。")
                runCatching {
                    val st = QuroLinuxEnv.probeLenient(context)
                    appendLine("   原因: ${st.reason}")
                    val p = QuroLinuxEnv.prootPath(context)
                    appendLine("   proot : $p (存在=${File(p).exists()})")
                    val rf = File(QuroLinuxEnv.rootfsPath(context))
                    appendLine("   rootfs: ${rf.absolutePath} (是目录=${rf.isDirectory}, 条目数=${rf.listFiles()?.size ?: 0})")
                    appendLine("   修复 : 点顶栏「检查更新/安装 Linux 环境」，或在对话发送 linux:install")
                }
            }
        }
        // VM 模式：guest shell 自行回显与提示符，本层不补 promptPrefix。
        if (mode != ShellMode.VM) appendLine(promptPrefix())
        Log.d(TAG, "init: 启动drain协程")
        launch { drain() }
    }

    /** 并发读取 stdout（已合并 stderr），按行追加到缓冲区；识别哨兵行。 */
    private fun drain() {
        Log.d(TAG, "drain: 开始读取stdout/stderr流")
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val raw = line ?: continue
                Log.d(TAG, "drain: 读取到行: '${raw.take(100)}${if (raw.length > 100) "..." else ""}'")
                // host 模式：识别 qurohost 的控制响应（US"@qurohost-resp " 开头），转交回调，不进终端缓冲区。
                if (isHost && raw.startsWith(QuroHostBridge.CONTROL_RESP_PREFIX)) {
                    controlCallback?.invoke(raw.substring(QuroHostBridge.CONTROL_RESP_PREFIX.length))
                    continue
                }
                if (QuroTerminalSentinel.looksLikeSentinel(raw, doneToken)) {
                    val done = QuroTerminalSentinel.parse(raw, doneToken)
                    if (done != null) {
                        // 哨兵可能与命令输出粘在同一行（命令没有以换行结尾时），
                        // 先把前半段真实输出打出来，再复位状态。
                        val head = stripAnsi(QuroTerminalSentinel.stripSentinel(raw, doneToken))
                        if (head.isNotEmpty()) {
                            appendLine(head)
                            vt?.writeText(head + "\n")
                            publishVt()
                        }
                        onCommandDone(done)
                        continue
                    }
                    // 结构对不上：不是真哨兵（或被截断），当普通输出处理。
                    // 绝不在此复位 busy —— 用错误的退出码复位比多打一行糟糕得多。
                }
                val clean = stripAnsi(raw)
                if (clean.isNotEmpty()) appendLine(clean)
                // VT 模式：把原始（含 ANSI 转义）行喂给 Kai 引擎渲染真·终端
                if (vt != null) {
                    vt!!.writeText(raw + "\n")
                    publishVt()
                }
            }
            Log.d(TAG, "drain: 读取流结束")
        } catch (e: Exception) {
            Log.e(TAG, "drain: 读取流异常", e)
            if (!exited) appendLine("⚠ 读取流结束: ${e.message}")
        } finally {
            exited = true
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            Log.d(TAG, "drain: 进程退出, exitCode=$exitCode")
            // 进程没了，任何等待中的命令都不会再有哨兵回来，必须解除 busy，
            // 否则 UI 永远卡在「运行中…」、中断按钮也失效。
            busy = false
            appendLine("— shell 已退出 (exit $exitCode) —")
            val cb = onExit
            if (cb != null) kotlin.runCatching { cb(exitCode) }
        }
    }

    /** 哨兵解析成功：落地退出码与 cwd，复位 busy，打印新提示符。 */
    private fun onCommandDone(done: QuroTerminalSentinel.Done) {
        lastExit = done.exitCode
        if (done.cwd.isNotEmpty()) cwdState = done.cwd
        busy = false
        if (suppressNextPrompt) {
            // 内部命令（如 restoreCwd 的 cd）不打提示符，避免多出一行空提示符
            suppressNextPrompt = false
            return
        }
        val pr = promptPrefix()
        appendLine(pr)
        vt?.writeText(pr)
        publishVt()
    }

    /** 发送一条命令（带回显 + 哨兵），等价于用户在提示符后敲回车。 */
    fun sendCommand(cmd: String) {
        if (exited) {
            Log.w(TAG, "sendCommand: 会话已退出，忽略命令: $cmd")
            return
        }
        val trimmed = cmd.trim()
        // VM 真 TTY：回显/提示符/信号由 guest 完成，本层只透传输入、不注入哨兵。
        if (passthroughEcho) {
            if (trimmed.isEmpty()) return
            if (trimmed == "clear" || trimmed == "cls") { clear(); return }
            // ACI 命令仍在本层拦截（qurohost 不在 VM 内）
            if (trimmed == "aci" || trimmed.startsWith("aci ")) { runAciCommand(trimmed); return }
            lastInterrupted = false
            writeRawDirect(CommandTranslator.translate(trimmed) + "\n")
            return
        }
        if (trimmed.isEmpty()) {
            // 空回车：仅补一个新提示符
            appendLine(promptPrefix())
            return
        }
        // clear/cls 拦截：clear 靠 ANSI ESC[2J，而 drain() 的 stripAnsi 会把 ANSI 转义全剥掉 →
        // 发给 shell 也不可见。直接清空缓冲区并补提示符，等价于清屏，不经过 shell。
        if (trimmed == "clear" || trimmed == "cls") {
            clear()
            appendLine(promptPrefix())
            return
        }
        // ACI 命令拦截：让终端内直接使用 ACI 的**全部**能力。
        // 注：qurohost 是经 ProcessBuilder 启动的独立原生进程、不在 JVM 内，
        // 无法用 JNI 调 ACI；故在本层（Kotlin 侧）拦截并把结果回显到终端。
        // 进程内原生代码则可直接用 libacihost.so 的 C API（aci_call/aci_list）。
        if (trimmed == "aci" || trimmed.startsWith("aci ")) {
            runAciCommand(trimmed)
            return
        }
        // 命令翻译：非 Ubuntu 命令（pkg/yum/pacman 等）→ Ubuntu 等价命令
        val translated = CommandTranslator.translate(trimmed)
        if (translated != trimmed) {
            appendLine("[router] $trimmed → $translated")
        }
        Log.d(TAG, "sendCommand: 发送命令 '$translated', 模式=$mode, busy=$busy")
        val echo = promptPrefix() + trimmed
        appendLine(echo)
        vt?.writeText(echo)
        if (vt != null) publishVt()
        lastInterrupted = false
        busy = true
        writeWithSentinel(translated)
    }

    // ═══════════════════ 终端内 ACI 命令 ═══════════════════

    /**
     * 异步执行终端内的 `aci` 命令。
     *
     * ⚠ ACI 调用是跨进程同步等待（最长约 15 秒），**绝不能**在调用线程直接跑——
     * sendCommand 可能来自 UI 主线程，阻塞即 ANR。故在 IO 线程执行，
     * 回显时切回主线程（lines 是 Compose 的 SnapshotStateList，应在主线程改）。
     */
    private fun runAciCommand(trimmed: String) {
        val echo = promptPrefix() + trimmed
        appendLine(echo)
        vt?.writeText(echo)
        if (vt != null) publishVt()
        launch {
            val out = try {
                executeAciCommand(trimmed)
            } catch (t: Throwable) {
                Log.w(TAG, "aci 命令执行失败", t)
                "aci: 执行失败: ${t.message}"
            }
            withContext(Dispatchers.Main) {
                out.lineSequence().forEach {
                    appendLine(it)
                    vt?.writeText(it + "\n")
                }
                val pr = promptPrefix()
                appendLine(pr)
                vt?.writeText(pr)
                if (vt != null) publishVt()
            }
        }
    }

    /** 解析并执行 aci 子命令（在 IO 线程）。 */
    private fun executeAciCommand(trimmed: String): String {
        val rest = trimmed.removePrefix("aci").trim()
        return when {
            rest.isEmpty() || rest == "help" -> aciHelp()
            rest == "list" -> aciList()
            rest == "targets" -> aciList()
            rest.startsWith("call") -> aciCall(rest.removePrefix("call").trim())
            else -> "未知 aci 子命令：$rest\n${aciHelp()}"
        }
    }

    private fun aciHelp(): String = buildString {
        appendLine("ACI 终端命令（使用 ACI 的全部能力）：")
        appendLine("  aci list                              列出所有受控端及其能力")
        appendLine("  aci call <包名> <能力> [参数JSON]      调用指定受控端的能力")
        appendLine("  aci help                              显示本帮助")
        appendLine("示例：")
        appendLine("  aci call com.ai.assistance.quro intent {\"mode\":\"activity\",\"action\":\"android.intent.action.VIEW\"}")
        appendLine("  aci call com.ai.assistance.quro provider {\"uri\":\"content://sms/inbox\",\"op\":\"query\",\"limit\":\"5\"}")
        appendLine("说明：参数值为标量（字符串/数字/布尔）；requireUserConfirm 的能力视为已在终端确认。")
    }.trimEnd()

    /** 列出所有受控端及能力。 */
    private fun aciList(): String {
        val idx = QuroAidlAciManager.getInstance().getCapabilityIndex()
        if (idx.isEmpty()) return "ACI：暂无已连接的受控端（先在 ACI 管理中心绑定）"
        return buildString {
            appendLine("ACI 受控端共 ${idx.size} 个：")
            idx.forEach { (pkg, caps) ->
                appendLine("● $pkg")
                if (caps.isEmpty()) {
                    appendLine("    （无已声明能力）")
                } else {
                    caps.forEach { c ->
                        val flag = if (c.isRequireUserConfirm) " [需确认]" else ""
                        appendLine("    ${c.id}$flag — ${c.description ?: ""}")
                    }
                }
            }
        }.trimEnd()
    }

    /**
     * 调用能力。格式：`call <包名> <能力> [参数JSON]`
     * 参数 JSON 可选，从第一个 `{` 起算；没有 JSON 时按空格切出能力名。
     */
    private fun aciCall(args: String): String {
        val sp = args.indexOf(' ')
        if (sp <= 0) return "用法：aci call <包名> <能力> [参数JSON]"
        val pkg = args.substring(0, sp).trim()
        var rest = args.substring(sp + 1).trim()

        val brace = rest.indexOf('{')
        val capability: String
        val json: String
        if (brace >= 0) {
            capability = rest.substring(0, brace).trim()
            json = rest.substring(brace).trim()
        } else {
            val sp2 = rest.indexOf(' ')
            if (sp2 > 0) {
                capability = rest.substring(0, sp2).trim()
                json = rest.substring(sp2 + 1).trim()
            } else {
                capability = rest
                json = ""
            }
        }
        if (pkg.isEmpty() || capability.isEmpty()) {
            return "用法：aci call <包名> <能力> [参数JSON]"
        }
        if (!json.isEmpty() && !json.startsWith("{")) {
            return "参数必须是 JSON 对象，实际：$json"
        }

        // 终端里用户主动敲命令即视为已确认（requireUserConfirm 的能力同样放行）
        val raw = AciNativeBridge.callJson(pkg, capability, json, confirmed = true)
        return formatAciResult(pkg, capability, raw)
    }

    /** 把 ACI 原生桥接返回的 JSON 渲染成终端可读文本。 */
    private fun formatAciResult(pkg: String, capability: String, raw: String): String {
        return try {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok", false)) {
                val code = o.optInt("code", -1)
                val err = o.optString("error", "unknown")
                "⛔ ACI 调用失败（$pkg / $capability，code=$code）：$err"
            } else {
                val data = o.optJSONObject("data")
                if (data == null || data.length() == 0) {
                    "✅ $pkg / $capability 调用成功（无返回数据）"
                } else {
                    buildString {
                        appendLine("✅ $pkg / $capability")
                        val keys = data.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = data.opt(k)
                            val s = v?.toString() ?: "null"
                            // 超长值截断，避免刷屏（完整内容仍可从 ACI 工具侧拿）
                            appendLine("  $k = ${if (s.length > 2000) s.take(2000) + "…(截断)" else s}")
                        }
                    }.trimEnd()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ACI 结果解析失败，原样输出", t)
            raw
        }
    }

    /**
     * 发送 CMS / 开发者环境的原生控制命令（仅 [isHost] 模式有效）。
     * 控制行以 [QuroHostBridge.CONTROL_PREFIX] 开头，由 qurohost 拦截处理、不进子 shell；
     * 响应经 [drain] 识别后转交 [controlCallback]。非 host 模式安全忽略。
     */
    fun sendControl(cmd: String) {
        if (!isHost) return
        runCatching {
            writer.write(QuroHostBridge.CONTROL_PREFIX + cmd + "\n")
            writer.flush()
        }
    }

    /**
     * 把命令 + 哨兵一次性写进 shell 的 stdin。
     *
     * 哨兵写到 stderr（C 库对 stderr 不做缓冲），再经 `redirectErrorStream(true)`
     * 合并进我们读取的同一流，确保完成信号立即到达、不被 stdout 的块缓冲卡住。
     */
    /**
     * 仅把文本原样写进底层进程 stdin（不补换行、不回显、不加哨兵），
     * 用于 VM 真 TTY 透传模式与内部 cd 命令。
     */
    private fun writeRawDirect(text: String) {
        launch {
            try {
                writer.write(text)
                writer.flush()
            } catch (e: Exception) {
                appendLine("⚠ 写入失败: ${e.message}")
            }
        }
    }

    private fun writeWithSentinel(cmd: String) {
        if (passthroughEcho) {
            // 真 TTY：仅透传命令，回显与提示符由 guest shell 完成
            launch { writeRawDirect(cmd + "\n") }
            return
        }
        launch {
            try {
                Log.d(TAG, "writeWithSentinel: 写入命令到stdin, cmd='$cmd'")
                writer.write(cmd)
                writer.write("\n")
                val sentinel = QuroTerminalSentinel.emitCommand(doneToken)
                Log.d(TAG, "writeWithSentinel: 写入哨兵, sentinel='$sentinel'")
                writer.write(sentinel)
                writer.write("\n")
                writer.flush()
                Log.d(TAG, "writeWithSentinel: 命令写入完成")
            } catch (e: Exception) {
                Log.e(TAG, "writeWithSentinel: 写入失败", e)
                appendLine("⚠ 写入失败: ${e.message}")
                busy = false
                suppressNextPrompt = false
            }
        }
    }

    /**
     * 会话重建后回到原来的工作目录（配合 [interrupt] 的硬中断）。
     *
     * 用 [QuroShellQuote.quote] 转义路径：目录名可能含空格、`$`、引号，
     * 直接拼 `cd $path` 会被 shell 二次解析而跳错目录甚至执行注入的命令。
     */
    fun restoreCwd(path: String) {
        if (exited || path.isBlank() || path == cwdState) return
        if (passthroughEcho) {
            // VM 真 TTY：直接透传 cd，guest shell 自行处理
            writeRawDirect("cd " + QuroShellQuote.quote(path) + "\n")
            return
        }
        busy = true
        suppressNextPrompt = true
        writeWithSentinel("cd " + QuroShellQuote.quote(path))
    }

    /**
     * 中断当前运行中的命令（E-9）。
     *
     * ## 为什么需要两阶段
     *
     * 本会话的 stdin 是**管道而非 PTY**，内核不会把 `^C` 字节翻译成 SIGINT
     * 投递给前台进程组——也就是说，没有 PTY 就**不可能**只中断前台任务而保住 shell。
     * 所以：
     *
     *  - **阶段 1（软）**：写入 ETX(`\u0003`)。对自己读原始 stdin 的程序
     *    （python REPL、`read`、部分 TUI）有效，它们会把 ETX 当作中断/EOF 处理。
     *    随后等待 [INTERRUPT_GRACE_MS]，看哨兵是否回来。
     *  - **阶段 2（硬）**：软中断无效时返回 `false`，由 [QuroTerminalController]
     *    负责杀掉整个 shell 进程并重建会话 + `cd` 回原目录。
     *    这一步会丢失 shell 内的环境变量/后台任务，所以必须让用户看得见发生了什么。
     *
     * @return `true` = 软中断成功（或本来就没有命令在跑）；`false` = 需要上层硬中断
     */
    suspend fun interrupt(): Boolean {
        if (exited) return true
        if (passthroughEcho) {
            // 真 TTY：ETX 会被内核翻译成 SIGINT 投递给 guest 前台进程组
            lastInterrupted = true
            appendLine("^C")
            runCatching { writer.write("\u0003"); writer.flush() }
            return true
        }
        if (!busy) return true

        lastInterrupted = true
        appendLine("^C")

        runCatching {
            writer.write("\u0003")
            writer.flush()
        }

        val deadline = System.currentTimeMillis() + INTERRUPT_GRACE_MS
        while (busy && !exited && System.currentTimeMillis() < deadline) {
            delay(INTERRUPT_POLL_MS)
        }
        return !busy || exited
    }

    /** 强制终止 shell 进程（硬中断第二阶段，由 controller 调用）。 */
    fun forceStop() {
        appendLine("⚠ 前台命令未响应软中断（管道 stdin 无法投递 SIGINT），已强制终止 shell")
        runCatching { process.destroyForcibly() }
    }

    /**
     * 导出当前滚动缓冲区到 `Documents/QuroDocs/terminal_<ts>.log`（E-10）。
     *
     * **阻塞**（写文件），必须在 IO 线程调用。
     * 传 `lines.toList()` 而不是 `lines` 本身：`lines` 是 Compose 的
     * `SnapshotStateList`，跨线程直接遍历可能读到撕裂的中间状态。
     *
     * @return 成功时为文件绝对路径，失败为 `null`
     */
    fun exportLog(): String? =
        QuroTerminalExport.export(context, lines.toList(), mode, cwdState)

    /**
     * 发送原始输入（不回显、不加哨兵），用于喂给已运行的交互式程序
     * （如 python REPL / cat / read），或粘贴多行文本。
     */
    fun sendRaw(text: String) {
        if (exited) return
        sendKey(if (text.endsWith("\n")) text else text + "\n")
    }

    /**
     * 发送一段**原样**字节序列，不补换行、不回显、不加哨兵（E-10 特殊按键行）。
     *
     * 与 [sendRaw] 的区别就在「不补换行」：`Tab`(`\t`)、`ESC`(`\u001b`)、
     * `^D`(`\u0004`) 这些控制字符一旦被补上换行，语义就全变了
     * （例如 `^D` 后面跟 `\n` 会让某些 REPL 先读到空行再收到 EOF）。
     */
    fun sendKey(seq: String) {
        if (exited || seq.isEmpty()) return
        launch {
            try {
                writer.write(seq)
                writer.flush()
            } catch (e: Exception) {
                appendLine("⚠ 写入失败: ${e.message}")
            }
        }
    }

    /** 清屏（仅清空滚动缓冲区，不影响底层进程）。 */
    fun clear() {
        lines.clear()
        vt?.clear()
        if (vt != null) publishVt()
    }

    /**
     * 把当前 VT 屏幕状态推送给 Compose 渲染层（drain 在 IO 线程调用；
     * [vtSnapshot] 是 Compose MutableState，跨线程写入由快照系统保证安全）。
     */
    private fun publishVt() {
        vt?.let { vtSnapshot.value = it.snapshot() }
    }

    /**
     * 把上一个会话的滚动内容接到本会话开头（硬中断重建会话时用）。
     *
     * 不这么做的话，用户按下「中断」后屏幕会突然被清空，看起来像应用崩了重开。
     * 只保留最近 [MAX_LINES] 的一半，给新会话留出缓冲空间。
     */
    fun prependHistory(previous: List<String>) {
        if (previous.isEmpty()) return
        val keep = previous.takeLast(MAX_LINES / 2)
        lines.addAll(0, keep)
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    /** 销毁会话：关闭流、结束进程、取消协程。 */
    fun destroy() {
        runCatching { writer.close() }
        runCatching { process.destroy() }
        job.cancel()
    }

    private fun appendLine(s: String) {
        lines.add(s)
        // 限制缓冲区上限，避免长会话内存无限增长
        if (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private fun promptPrefix(): String =
        when (mode) {
            ShellMode.LINUX -> "quro@linux:$cwdState\$ "
            ShellMode.VM -> "" // guest shell 自行回显提示符
            ShellMode.DEVICE -> "$cwdState\$ "
        }

    companion object {
        private const val MAX_LINES = 4000
        private const val TAG = "QuroShellSession"

        /** 软中断（写 ETX）后等待哨兵回来的宽限时间；超时即判定软中断失败。 */
        const val INTERRUPT_GRACE_MS: Long = 1200L

        /** 等待期间的轮询间隔。 */
        private const val INTERRUPT_POLL_MS: Long = 50L

        /**
         * 创建会话。
         *
         * 优先级：原生 host 后端（[QuroHostBridge] 启动 libqurohost.so，经 proot 进沙箱或设备直跑）
         * → 失败则回落旧直连路径（[createLegacy]，与 v127 行为一致）。任何异常都被捕获降级，
         * 绝不抛出，避免拖垮 ChatScreen 重组。旧 Termux 终端 / 旧 Kotlin CMS 部署器路径完整保留。
         */
    fun create(context: Context): QuroShellSession {
        // VM-first：AVF/pKVM 或 QEMU 真内核 Linux 优先；任一失败回退 proot 用户态 Linux。
        runCatching {
            val console = QuroVmEnv.startConsole(context)
            if (console != null) {
                val env = QuroVmEnv.vmShellEnv(context)
                Log.i(TAG, "✅ VM 后端已启动，创建 VM 模式会话")
                return QuroShellSession(context, ShellMode.VM, emptyList(), env, "/root", console)
            }
        }.onFailure { e ->
            Log.w(TAG, "VM 后端启动失败，回退 proot: ${e.message}")
        }
        // 直连 proot/设备 sh，不走 qurohost 包装层。
        // qurohost（libqurohost.so）是 Android ELF 二进制，动态链接器为
        // /system/bin/linker64。proot 将 / 映射到 Ubuntu rootfs，
        // rootfs 内无 linker64 → qurohost 在 proot 内秒退、终端无任何输出。
        // terminal_exec 走 QuroLinuxEnv.run() 绕过了 qurohost 所以正常，
        // 交互终端也应走同一路径（直连 proot）。
        return createLegacy(context)
    }

        /**
         * 强制本地 proot/设备终端（不尝试 VM 后端）。
         * 用于双终端场景的「本地窗格」：即使本机有 VM 能力，也显式走 proot，
         * 与 [create] 的 VM 优先窗格形成对照，保证两窗格后端不同、互不争抢 VM 资源。
         */
        fun createLocal(context: Context): QuroShellSession = createLegacy(context)

        /**
         * 旧直连路径（v127 行为）：Linux 环境就绪则 proot 常驻 sh，否则设备 sh。
         * 仅在 host 后端不可用或启动失败时调用，作为兜底。
         */
        private fun createLegacy(context: Context): QuroShellSession {
            // 优先用已导入的命名容器（tiny_container 范式，去品牌化）：
            // 若存在 rootfs 容器，终端直接跑该容器 proot，而非默认单 rootfs 沙箱。
            if (QuroContainerManager.isProvisioned(context)) {
                runCatching {
                    val proc = QuroContainerManager.launchSession(context)
                    if (proc != null) {
                        Log.i(TAG, "✅ 命名容器已启动，创建 LINUX 模式会话")
                        return QuroShellSession(
                            context, ShellMode.LINUX, emptyList(), emptyArray(),
                            context.filesDir.absolutePath, proc,
                        )
                    }
                }.onFailure { e -> Log.w(TAG, "命名容器启动失败，回退默认 proot: ${e.message}") }
            }
            val launch = QuroLinuxEnv.shellLaunch(context)
            if (launch != null) {
                try {
                    val (proot, args) = launch
                    // 注入 PROOT_LOADER / LD_LIBRARY_PATH 等（proot 从 nativeLibraryDir 取执行权限的关键）。
                    val env = QuroLinuxEnv.shellEnv(context)
                    // host 工作目录用真实存在的主机路径（proot 自身 -w /root 决定沙箱内目录）。
                    return QuroShellSession(context, ShellMode.LINUX, listOf(proot) + args, env, context.filesDir.absolutePath)
                } catch (e: Exception) {
                    // proot 启动失败（不可执行 / 架构不符 / 权限受限等）：降级设备 sh，终端依旧可用。
                    Log.w(TAG, "Linux(proot) shell 启动失败，回退设备 sh: ${e.message}")
                }
            }
            val dev = createDevice(context)
            if (launch != null) {
                dev.lines.add("⚠ proot 启动失败，已回退设备 shell（无 python3 / Ubuntu 能力）")
            }
            return dev
        }

        /** 设备模式：常驻 /system/bin/sh（Toybox），免权限、必然可执行，作为兜底。 */
        private fun createDevice(context: Context): QuroShellSession {
            val home = Environment.getExternalStorageDirectory().absolutePath
            val env = arrayOf(
                "TERM=xterm-256color",
                "HOME=$home",
                "PATH=/system/bin:/system/xbin:/sbin",
                "LANG=en_US.UTF-8",
            )
            return QuroShellSession(context, ShellMode.DEVICE, listOf("/system/bin/sh"), env, home)
        }
    }
}

enum class ShellMode { DEVICE, LINUX, VM }

/** 去掉 ANSI 转义序列（\x1b[...m 等），让管道模式下的输出干净可读。 */
private fun stripAnsi(s: String): String {
    var i = 0
    val sb = StringBuilder(s.length)
    while (i < s.length) {
        val c = s[i]
        if (c == '\u001b' && i + 1 < s.length && (s[i + 1] == '[' || s[i + 1] == ']')) {
            // 跳过 CSI/OSC：直到字母（终结符）或 BEL
            i += 2
            while (i < s.length) {
                val d = s[i]
                if ((d in 'a'..'z') || (d in 'A'..'Z') || d == '\u0007') {
                    i++
                    break
                }
                i++
            }
            continue
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}
