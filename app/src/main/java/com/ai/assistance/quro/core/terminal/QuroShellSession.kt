package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.privilege.QuroShellQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 自包含的交互式 shell 会话（v127 重写，彻底移除 Termux / PTY 依赖）。
 *
 * 设计参考 Kai 9000（https://github.com/SimonSchubert/Kai）的 sandbox 思路：
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
) : CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.IO + job

    /** 滚动缓冲区（每行一条），由 Compose LazyColumn 渲染。 */
    val lines = mutableStateListOf<String>()

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

    private val process: Process = try {
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
        appendLine("— Zorv AI 终端已启动 (${if (mode == ShellMode.LINUX) "proot/Linux · Alpine aarch64" else "设备 · Toybox sh"}) —")
        appendLine(promptPrefix())
        launch { drain() }
    }

    /** 并发读取 stdout（已合并 stderr），按行追加到缓冲区；识别哨兵行。 */
    private fun drain() {
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val raw = line ?: continue
                if (QuroTerminalSentinel.looksLikeSentinel(raw, doneToken)) {
                    val done = QuroTerminalSentinel.parse(raw, doneToken)
                    if (done != null) {
                        // 哨兵可能与命令输出粘在同一行（命令没有以换行结尾时），
                        // 先把前半段真实输出打出来，再复位状态。
                        val head = stripAnsi(QuroTerminalSentinel.stripSentinel(raw, doneToken))
                        if (head.isNotEmpty()) appendLine(head)
                        onCommandDone(done)
                        continue
                    }
                    // 结构对不上：不是真哨兵（或被截断），当普通输出处理。
                    // 绝不在此复位 busy —— 用错误的退出码复位比多打一行糟糕得多。
                }
                val clean = stripAnsi(raw)
                if (clean.isNotEmpty()) appendLine(clean)
            }
        } catch (e: Exception) {
            if (!exited) appendLine("⚠ 读取流结束: ${e.message}")
        } finally {
            exited = true
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
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
        appendLine(promptPrefix())
    }

    /** 发送一条命令（带回显 + 哨兵），等价于用户在提示符后敲回车。 */
    fun sendCommand(cmd: String) {
        if (exited) return
        val trimmed = cmd.trim()
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
        appendLine(promptPrefix() + trimmed)
        lastInterrupted = false
        busy = true
        writeWithSentinel(trimmed)
    }

    /**
     * 把命令 + 哨兵一次性写进 shell 的 stdin。
     *
     * 哨兵写到 stderr（C 库对 stderr 不做缓冲），再经 `redirectErrorStream(true)`
     * 合并进我们读取的同一流，确保完成信号立即到达、不被 stdout 的块缓冲卡住。
     */
    private fun writeWithSentinel(cmd: String) {
        launch {
            try {
                writer.write(cmd)
                writer.write("\n")
                writer.write(QuroTerminalSentinel.emitCommand(doneToken))
                writer.write("\n")
                writer.flush()
            } catch (e: Exception) {
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
        if (mode == ShellMode.LINUX) "quro@linux:$cwdState\$ " else "$cwdState\$ "

    companion object {
        private const val MAX_LINES = 4000
        private const val TAG = "QuroShellSession"

        /** 软中断（写 ETX）后等待哨兵回来的宽限时间；超时即判定软中断失败。 */
        const val INTERRUPT_GRACE_MS: Long = 1200L

        /** 等待期间的轮询间隔。 */
        private const val INTERRUPT_POLL_MS: Long = 50L

        /**
         * 创建会话：Linux 环境就绪则走 proot，否则（或 proot 启动失败）回退设备 sh。
         * 关键：Linux(proot) 启动任何异常都被捕获并降级，绝不抛出，避免拖垮 ChatScreen 重组。
         */
        fun create(context: Context): QuroShellSession {
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
                dev.lines.add("⚠ proot 启动失败，已回退设备 shell（无 python3 / Alpine 能力）")
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

enum class ShellMode { DEVICE, LINUX }

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
