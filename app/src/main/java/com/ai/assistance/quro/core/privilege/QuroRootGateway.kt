package com.ai.assistance.quro.core.privilege

import android.content.Context
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import com.ai.assistance.quro.util.QuroDiag
import java.util.concurrent.TimeUnit

/**
 * Root 执行统一网关（E-7）。
 *
 * ## 为什么要有它
 *
 * 在此之前项目里有**三套并行的 root 执行实现**，各自写了一遍 quoting、超时、读流、审计：
 *   - `core/tools/QuroToolsRoot.kt` 的 `RootExecTool`
 *   - `core/novaterm/executor/RootExecutor.kt`
 *   - `core/shizuku/QuroShizuku.execAsRoot`
 *
 * 三套实现的 bug 各不相同（有的把命令按空格拆词、有的不设超时、有的从不关 FD、
 * 有的只看进程是否秒退就断言 root 可用），修一处漏两处。现在全部收敛到这里。
 *
 * ## 提供的保证
 *
 * 1. **正确的 quoting**：命令一律以 `arrayOf("su", "-c", command)` 形式传递，
 *    绝不拼成 `"su -c $command"` 单字符串（那会被 `Runtime.exec` 按空格拆 argv，
 *    导致 `su -c ls -la /sdcard` 里 `su -c` 只吃到 `ls`）。
 *    需要经 `sh -c` 二次转发时（Shizuku AIDL 路径）用 [shellQuote] 做单引号转义。
 * 2. **强制超时**：默认 [DEFAULT_TIMEOUT_MS]，超时 `destroyForcibly()`，绝不永久阻塞调用线程。
 * 3. **不泄漏 FD**：stdout/stderr 各起一个后台线程读干净，进程结束后 join + destroy。
 * 4. **统一审计**：每次执行都写 [QuroPrivilegeAudit] + [QuroDiag]。
 * 5. **降级链**：Shizuku-root → su → 失败，调用方无需自己判断。
 *
 * ## 线程模型
 *
 * [exec] 是**阻塞**调用（最长阻塞 timeoutMs），必须在 IO 线程调用，
 * 绝不能在主线程或 Compose composition 期调用。
 */
object QuroRootGateway {

    private const val TAG = "QuroRootGateway"

    /** 默认命令超时。Magisk 首次弹授权框需要用户交互，故不能太短。 */
    const val DEFAULT_TIMEOUT_MS: Long = 15_000L

    /** root 可用性探测的超时（只跑 `echo`，但要给 Magisk 弹框留时间）。 */
    const val PROBE_TIMEOUT_MS: Long = 5_000L

    /** 探测命令的约定回显串。 */
    private const val PROBE_TOKEN = "root_ok"

    /** 执行通道。 */
    enum class Channel {
        /** 经 Shizuku 特权进程提权（UID 0 直接执行 / UID 2000 再 su）。 */
        SHIZUKU,

        /** 直接 `su -c`。 */
        SU,

        /** 无可用通道。 */
        NONE,
    }

    /**
     * 一次 root 执行的结果。
     *
     * @param success 命令是否成功执行完毕且 exitCode == 0
     * @param output stdout + stderr 合并后的文本（已 trim）
     * @param exitCode 进程退出码；超时或未能启动时为 -1
     * @param channel 实际走通的通道
     * @param timedOut 是否因超时被强杀
     * @param error 通道不可用或异常时的错误说明，正常执行时为空串
     */
    data class RootResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int,
        val channel: Channel,
        val timedOut: Boolean = false,
        val error: String = "",
    ) {
        /** 给 AI / UI 展示用的紧凑文本。 */
        fun render(): String = when {
            error.isNotBlank() -> "❌ $error"
            timedOut -> "⏱ 命令超时已终止（${channel.name}）${if (output.isNotBlank()) "，已捕获输出：\n$output" else ""}"
            else -> "[${channel.name.lowercase()}] exit=$exitCode\n${output.ifBlank { "(无输出)" }}"
        }
    }

    // ════════════════════════════════════════
    // POSIX quoting
    // ════════════════════════════════════════

    /**
     * 把任意字符串包成一个 POSIX shell 单引号字面量，供需要二次经 `sh -c` 转发的场景使用。
     *
     * 实现在零依赖的 [QuroShellQuote]，此处仅转发，便于调用方就近取用。
     */
    fun shellQuote(s: String): String = QuroShellQuote.quote(s)

    // ════════════════════════════════════════
    // 可用性探测
    // ════════════════════════════════════════

    /**
     * 最近一次 [isRootAvailable] 的真实探测结果。
     *
     * `null` = 从未实测过。给「不能阻塞、又不许谎报」的调用方（如权限列表的同步构建）用：
     * 它们只能说「尚未验证」，绝不能拿「/system/bin/su 文件存在」当作已获授权。
     */
    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /** 最近一次真实探测的时间戳（毫秒），用于 TTL 短路，避免每次进权限页都弹 su 授权框（BUG C）。 */
    @Volatile
    private var lastProbeMs: Long = 0L

    /** 缓存有效期：在此窗口内的重复探测直接复用上次结果，不再 spawn `su`。 */
    private const val ROOT_PROBE_CACHE_TTL_MS = 60_000L

    /**
     * 读取缓存的 root 可用性，**不发起任何进程**、不阻塞。
     *
     * @return `true`=实测可用；`false`=实测不可用；`null`=尚未实测，调用方必须如实展示「未验证」
     */
    fun cachedRootAvailable(): Boolean? = cachedRootAvailable

    /** 丢弃缓存（用户在 Magisk/KernelSU 里改了授权后调用）。 */
    fun invalidateCache() {
        cachedRootAvailable = null
    }

    /**
     * 探测 root 是否真正可用（**阻塞**，需在 IO 线程调用）。
     *
     * 判定依据是 `su -c echo 'root_ok'` 的**实际输出**，而不是「进程是否很快退出」。
     * 后者两个方向都会误判：su 被拒绝时进程秒退（误报可用），
     * Magisk 首次弹框等用户点允许常常超时（误报不可用）。
     */
    fun isRootAvailable(): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedRootAvailable
        // TTL 短路：缓存窗口内直接复用，避免「设置→权限」页每次进入/回到前台都弹一次 su 授权框。
        // invalidateCache() 会把 cachedRootAvailable 置 null，绕过此短路，确保用户在 Root 管理器改授权后能立即重探。
        if (cached != null && (now - lastProbeMs) < ROOT_PROBE_CACHE_TTL_MS) {
            return cached
        }
        val r = runSu("echo '$PROBE_TOKEN'", PROBE_TIMEOUT_MS)
        val ok = r.exitCode == 0 && r.output.trim() == PROBE_TOKEN
        cachedRootAvailable = ok
        lastProbeMs = now
        return ok
    }

    /** 探测结果的可读描述，供权限页 L4 卡片展示。 */
    fun rootStatusText(): String =
        if (isRootAvailable()) "Root 访问可用" else "未获取 Root（su 被拒绝或设备未 Root）"

    /** 当前可用的最佳通道（不实际执行命令，仅做轻量判断）。 */
    fun preferredChannel(): Channel = when {
        runCatching { QuroShizuku.isReady }.getOrDefault(false) -> Channel.SHIZUKU
        else -> Channel.SU
    }

    // ════════════════════════════════════════
    // 主入口
    // ════════════════════════════════════════

    /**
     * 以 root 权限执行一条命令，按「Shizuku-root → su → 失败」降级。
     *
     * **阻塞**最长 timeoutMs，必须在 IO 线程调用。
     *
     * @param context 用于写审计日志；传 null 则只写 [QuroDiag] 不写审计
     * @param command 要执行的完整 shell 命令（无需自己加引号）
     * @param timeoutMs 超时毫秒
     * @param capsuleId 审计日志里的调用方标识
     */
    fun exec(
        context: Context?,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        capsuleId: String = "capos.root",
    ): RootResult {
        if (command.isBlank()) {
            return RootResult(false, "", -1, Channel.NONE, error = "命令为空")
        }
        QuroDiag.log(TAG, "▶ root exec: ${command.take(200)}")

        // ── 通道 1：Shizuku 特权进程 ──
        if (runCatching { QuroShizuku.isReady }.getOrDefault(false)) {
            val raw = runCatching { QuroShizuku.execAsRoot(command) }.getOrElse { e ->
                "❌ Shizuku 执行异常: ${e.message}"
            }
            if (!raw.startsWith("❌")) {
                // QuroShizuku 返回形如 "exit=0\n<body>"，把 exitCode 解出来
                val (code, body) = parseShizukuOutput(raw)
                val res = RootResult(code == 0, body, code, Channel.SHIZUKU)
                audit(context, capsuleId, command, res)
                return res
            }
            QuroDiag.log(TAG, "⚠ Shizuku root 通道不可用，降级 su：$raw")
        }

        // ── 通道 2：直接 su ──
        val res = runSu(command, timeoutMs)
        audit(context, capsuleId, command, res)
        return res
    }

    /**
     * 便捷重载：只要输出，不关心结构化结果。
     * 失败/超时会把错误信息直接体现在返回文本里。
     */
    fun execText(
        context: Context?,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        capsuleId: String = "capos.root",
    ): String = exec(context, command, timeoutMs, capsuleId).render()

    // ════════════════════════════════════════
    // 内部实现
    // ════════════════════════════════════════

    /**
     * 直接 `su -c <command>` 执行。
     *
     * 关键点：
     *  - 用 **数组形式** exec，避免 `"su -c $command"` 被按空格拆 argv；
     *  - stdout / stderr 各起后台线程读干净，避免管道写满导致子进程卡死、
     *    也避免 readText() 在进程不退出时永久阻塞；
     *  - 超时 destroyForcibly()，并回收已捕获的部分输出。
     */
    private fun runSu(command: String, timeoutMs: Long): RootResult {
        var process: Process? = null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process = p

            val outB = StringBuilder()
            val errB = StringBuilder()
            val tOut = Thread {
                runCatching { p.inputStream.bufferedReader().use { outB.append(it.readText()) } }
            }.apply { isDaemon = true; start() }
            val tErr = Thread {
                runCatching { p.errorStream.bufferedReader().use { errB.append(it.readText()) } }
            }.apply { isDaemon = true; start() }

            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                tOut.join(500)
                tErr.join(500)
                val partial = (outB.toString() + errB.toString()).trim()
                QuroDiag.log(TAG, "⏱ root 命令超时(${timeoutMs}ms)：${command.take(120)}")
                return RootResult(false, partial, -1, Channel.SU, timedOut = true)
            }

            tOut.join(1000)
            tErr.join(1000)
            val code = p.exitValue()
            val body = (outB.toString() + errB.toString()).trim()
            QuroDiag.log(TAG, "${if (code == 0) "✓" else "✗"} root exit=$code：${command.take(120)}")
            RootResult(code == 0, body, code, Channel.SU)
        } catch (e: SecurityException) {
            QuroDiag.log(TAG, "✗ su 被拒绝：${e.message}")
            RootResult(false, "", -1, Channel.NONE, error = "ROOT 不可用：su 被拒绝（设备未 Root 或 Root 管理器未授权本应用）")
        } catch (e: Exception) {
            QuroDiag.log(TAG, "✗ root 执行失败：${e.message}")
            RootResult(false, "", -1, Channel.NONE, error = "ROOT 执行失败：${e.message}")
        } finally {
            runCatching { process?.destroy() }
        }
    }

    /** 解析 [QuroShizuku] 返回的 `exit=<n>\n<body>` 文本。解析不出就当作 exit=0。 */
    private fun parseShizukuOutput(raw: String): Pair<Int, String> {
        if (!raw.startsWith("exit=")) return 0 to raw.trim()
        val nl = raw.indexOf('\n')
        if (nl < 0) {
            val code = raw.removePrefix("exit=").trim().toIntOrNull() ?: 0
            return code to ""
        }
        val code = raw.substring(5, nl).trim().toIntOrNull() ?: 0
        var body = raw.substring(nl + 1).trim()
        if (body == "(无输出)") body = ""
        return code to body
    }

    /** 统一审计落盘。 */
    private fun audit(context: Context?, capsuleId: String, command: String, res: RootResult) {
        if (context == null) return
        runCatching {
            QuroPrivilegeAudit.log(
                context,
                capsuleId,
                PrivilegeLevel.L4,
                "root exec [${res.channel.name}] ${command.take(160)}",
                res.success,
            )
        }
    }
}
