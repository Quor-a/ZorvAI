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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * 命令完成检测用「哨兵协议」：每条命令后追加一行
 *   printf '\n\x1eQURO_DONE:%d:%s\x1e\n' "$?" "$PWD"
 * 读取端识别该哨兵行即可拿到上条命令的退出码与当前工作目录，并复位 busy 状态、打印新提示符。
 * 因 stdin 是管道而非 tty，shell 不会回显输入、也不会画提示符，所以命令回显与新提示符由本类手动补全。
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
                if (raw.contains(DONE_TOKEN)) {
                    parseDone(raw)
                    continue
                }
                val clean = stripAnsi(raw)
                if (clean.isNotEmpty()) appendLine(clean)
            }
        } catch (e: Exception) {
            if (!exited) appendLine("⚠ 读取流结束: ${e.message}")
        } finally {
            exited = true
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            appendLine("— shell 已退出 (exit $exitCode) —")
            val cb = onExit
            if (cb != null) kotlin.runCatching { cb(exitCode) }
        }
    }

    /** 解析哨兵行，提取退出码与 cwd，复位 busy，打印新提示符。 */
    private fun parseDone(raw: String) {
        val inner = raw.replace("\u001e", "").removePrefix(DONE_TOKEN)
        val idx = inner.indexOf(':')
        if (idx > 0) {
            runCatching { inner.substring(0, idx).toInt() }.onSuccess { lastExit = it }
            val path = inner.substring(idx + 1)
            if (path.isNotEmpty()) cwdState = path
        }
        busy = false
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
        appendLine(promptPrefix() + trimmed)
        busy = true
        launch {
            try {
                writer.write(trimmed)
                writer.write("\n")
                // 哨兵写到 stderr（C 库对 stderr 不做缓冲），再经 redirectErrorStream(true)
                // 合并进我们读取的同一流，确保完成信号立即到达、不被 stdout 的块缓冲卡住。
                writer.write("printf '\\n\\x1e")
                writer.write(DONE_TOKEN)
                writer.write(":%d:%s\\x1e\\n' \"\$?\" \"\$PWD\" >&2\n")
                writer.flush()
            } catch (e: Exception) {
                appendLine("⚠ 写入失败: ${e.message}")
                busy = false
            }
        }
    }

    /**
     * 发送原始输入（不回显、不加哨兵），用于喂给已运行的交互式程序
     * （如 python REPL / cat / read），或粘贴多行文本。
     */
    fun sendRaw(text: String) {
        if (exited) return
        launch {
            try {
                writer.write(text)
                if (!text.endsWith("\n")) writer.write("\n")
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
        private const val DONE_TOKEN = "QURO_DONE"
        private const val TAG = "QuroShellSession"

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
