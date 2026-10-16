package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.terminal.Pty
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths

/**
 * 真正内置的 SSH 服务端：跑在 App host 进程里，监听 127.0.0.1:2222，
 * 不依赖 proot / Ubuntu 环境 —— 即使「没有连接环境」也能连上。
 *
 * 后端 shell 由 [TerminalEnvironment.backendShellCommand] 决定：
 * Ubuntu rootfs 就绪时桥接 proot 登录 shell，否则退化为 Android host 的 busybox sh，
 * 满足「两边都要接」。
 */
object BuiltinSshServer {

    private const val TAG = "BuiltinSshServer"

    @Volatile
    private var sshd: SshServer? = null

    /** 当前后端（用于界面展示）。 */
    @Volatile
    var backendLabel: String = "—"
        private set

    /** 最近一次启动失败的原因（界面直接展示，不再猜「环境未就绪」）。 */
    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun start(context: Context): Boolean {
        if (sshd != null) return true
        lastError = null
        // 必须在任何 MINA 类被加载之前补 user.home，否则 setUpDefaultServer() 直接
        // 抛 ExceptionInInitializerError（Error，catch Exception 抓不住 → App 闪退）。
        SshAndroidCompat.ensure(context)
        val env = TerminalEnvironment(context)
        if (!env.ensureHostBackendReady()) {
            lastError = "宿主运行环境未就绪：native 组件（busybox/proot）缺失，请重新初始化环境"
            Log.e(TAG, lastError!!)
            return false
        }
        var server: SshServer? = null
        return try {
            val keyFile = Paths.get(context.filesDir.absolutePath, "ssh_host_key.ser")
            server = SshServer.setUpDefaultServer()
            server.host = "127.0.0.1"
            server.port = RemoteAccessManager.SSH_PORT
            val keyProvider = SimpleGeneratorHostKeyProvider()
            keyProvider.path = keyFile
            keyProvider.algorithm = "RSA"
            keyProvider.keySize = 2048
            server.keyPairProvider = keyProvider
            server.passwordAuthenticator = PasswordAuthenticator { _, password, _ ->
                password == RemoteAccessManager.LOGIN_PASSWORD
            }
            server.shellFactory = ShellFactory { _: ChannelSession -> SshShellCommand(env) }
            server.start()
            sshd = server
            backendLabel = env.backendLabel()
            Log.i(
                TAG,
                "SSH 服务已启动：127.0.0.1:${RemoteAccessManager.SSH_PORT}（后端 ${backendLabel}）"
            )
            true
        } catch (e: Throwable) {
            // 抓 Throwable：SSHD 在 Android 上可能抛 Error（见 SshAndroidCompat 注释）
            lastError = describe(e)
            Log.e(TAG, "SSH 服务启动失败：${lastError}", e)
            runCatching { server?.stop() }
            sshd = null
            false
        }
    }

    @Synchronized
    fun stop() {
        val s = sshd ?: return
        sshd = null
        try {
            s.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "停止 SSH 失败", e)
        }
        backendLabel = "—"
    }

    fun isRunning(): Boolean = sshd != null

    /** 把 Throwable 变成一句人话（类名 + 首个有消息的 cause）。 */
    private fun describe(e: Throwable): String {
        var t: Throwable? = e
        val seen = mutableSetOf<Throwable>()
        while (t != null && seen.add(t)) {
            val msg = t.message
            if (!msg.isNullOrBlank()) return "${t.javaClass.simpleName}：$msg"
            t = t.cause
        }
        return e.javaClass.simpleName
    }
}

/**
 * 每个 SSH 会话一个：把 SSHD 的通道 I/O 桥接到一个宿主 PTY 子进程
 * （proot Ubuntu 登录 shell 或 host busybox sh）。
 */
class SshShellCommand(private val env: TerminalEnvironment) : Command {

    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var error: OutputStream? = null
    private var exitCallback: ExitCallback? = null
    private var pty: Pty? = null
    private val pumpThreads = mutableListOf<Thread>()

    override fun setInputStream(`in`: InputStream) { input = `in` }
    override fun setOutputStream(out: OutputStream) { output = out }
    override fun setErrorStream(err: OutputStream) { error = err }
    override fun setExitCallback(callback: ExitCallback) { exitCallback = callback }

    override fun start(channel: ChannelSession, environment: Environment) {
        val command = env.backendShellCommand()
        val envMap = env.baseEnvironment()
        val pty = try {
            Pty.start(command, envMap, env.filesDirectory)
        } catch (e: Exception) {
            Log.e(TAG, "SSH 后端 PTY 启动失败", e)
            exitCallback?.onExit(1)
            return
        }
        this.pty = pty

        // 客户端协商的终端尺寸（环境变量 COLUMNS / LINES），缺省 80x24
        runCatching {
            val envVars = environment.getEnv()
            val cols = envVars[Environment.ENV_COLUMNS]?.toIntOrNull() ?: 80
            val rows = envVars[Environment.ENV_LINES]?.toIntOrNull() ?: 24
            if (cols > 0 && rows > 0) pty.setWindowSize(rows, cols)
        }

        val `in` = this.input
        val out = this.output
        val err = this.error

        // 客户端 → PTY 标准输入
        val inPump = Thread {
            try {
                `in`?.copyTo(pty.stdin)
            } catch (_: Throwable) {
            } finally {
                runCatching { pty.stdin.close() }
            }
        }.apply { isDaemon = true; name = "ssh-in-pump"; start() }
        pumpThreads.add(inPump)

        // PTY 标准输出 → 客户端（stdout + stderr 合并输出到 out）
        val outPump = Thread {
            try {
                pty.stdout.copyTo(out ?: return@Thread)
            } catch (_: Throwable) {
            } finally {
                runCatching { out?.close() }
                runCatching { err?.close() }
            }
        }.apply { isDaemon = true; name = "ssh-out-pump"; start() }
        pumpThreads.add(outPump)

        // 等待 shell 退出，回传退出码
        val waiter = Thread {
            val code = try {
                pty.waitFor()
            } catch (_: Throwable) {
                -1
            }
            runCatching { out?.close() }
            runCatching { err?.close() }
            runCatching { `in`?.close() }
            exitCallback?.onExit(code)
        }.apply { isDaemon = true; name = "ssh-wait"; start() }
        pumpThreads.add(waiter)
    }

    override fun destroy(channel: ChannelSession) {
        runCatching { pty?.destroy() }
        pumpThreads.forEach { runCatching { it.interrupt() } }
    }

    companion object {
        private const val TAG = "SshShellCommand"
    }
}
