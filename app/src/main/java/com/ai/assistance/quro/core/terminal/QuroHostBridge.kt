package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 原生终端 / CMS / 开发者环境后端的 Kotlin 启动器与协议客户端。
 *
 * 对应 C 实现 [app/src/main/cpp/qurohost/qurohost.c]，编译产物 `libqurohost.so`
 * （与 libproot.so 同机制：Android 在 nativeLibraryDir 授予 .so 可执行权限，由
 * ProcessBuilder 直接执行）。Linux 模式下经 proot 跑进沙箱（子 shell = Ubuntu /bin/sh），
 * 设备模式回退 /system/bin/sh。
 *
 * 行协议（与 qurohost.c 严格对应）：
 *  - Kotlin→qurohost：以 US(0x1f)"@qurohost " 开头的行是控制命令（CMS/devenv），其余行透传子 shell。
 *  - qurohost→Kotlin：普通终端输出；以 US"@qurohost-resp " 开头的行是控制响应（JSON）。
 *
 * 旧路径（[QuroShellSession] 直连 ProcessBuilder / [QuroLinuxEnv] 的 Kotlin CMS 部署器）**完整保留**：
 * [resolveBinary] 返回 null 时 [buildLaunch] 返回 null，调用方回落旧实现，不抛异常。
 */
object QuroHostBridge {

    private const val TAG = "QuroHostBridge"
    private const val LIB = "libqurohost.so"

    /** 控制行前缀（Kotlin→qurohost）。 */
    const val CONTROL_PREFIX: String = "\u001f@qurohost "
    /** 控制响应前缀（qurohost→Kotlin）。 */
    const val CONTROL_RESP_PREFIX: String = "\u001f@qurohost-resp "

    /** 解析 libqurohost.so 路径；不存在返回 null（调用方回退旧路径）。 */
    fun resolveBinary(context: Context): String? {
        val primary = File(context.applicationInfo.nativeLibraryDir, LIB)
        if (primary.exists()) return primary.absolutePath
        Log.w(TAG, "⚠ libqurohost.so 不在 nativeLibraryDir，回退旧终端路径")
        return null
    }

    /**
     * 构造 host 模式终端会话的启动参数；不可用（无二进制）返回 null。
     * Linux 环境就绪时把 qurohost 经 proot 跑进沙箱，否则设备直跑。
     */
    fun buildLaunch(context: Context): QuroHostLaunch? {
        val bin = resolveBinary(context) ?: return null
        val linux = QuroLinuxEnv.shellLaunch(context) != null
        return if (linux) buildProotLaunch(context, bin) else buildDeviceLaunch(bin)
    }

    private fun buildDeviceLaunch(bin: String): QuroHostLaunch = QuroHostLaunch(
        args = mutableListOf(bin, "/system/bin/sh"),
        env = mutableMapOf(
            "TERM" to "xterm-256color",
            "HOME" to "/",
            "PATH" to "/system/bin:/system/xbin:/sbin",
            "LANG" to "en_US.UTF-8",
        ),
        workDir = File("/"),
    )

    /**
     * 复用 proot 基础参数（与 [QuroLinuxEnv.buildProotLaunch] 一致），但把 guest 程序
     * 从 `/bin/sh -c <cmd>` 换成 `<qurohost> /bin/sh`：把宿主 libqurohost.so 绑定进
     * 沙箱为 /qurohost，子 shell 用沙箱内 Ubuntu /bin/sh。
     */
    private fun buildProotLaunch(context: Context, bin: String): QuroHostLaunch {
        val proot = QuroLinuxEnv.prootPath(context)
        val rootfs = QuroLinuxEnv.rootfsPath(context)
        val home = QuroLinuxEnv.homePath(context)
        val tmp = QuroLinuxEnv.tmpPath(context)
        val loader = QuroLinuxEnv.loaderPath(context)
        val sandboxDir = File(context.filesDir, "linux-sandbox")
        val usrBinDir = File(sandboxDir, "usr/bin")
        val args = mutableListOf(
            proot,
            "--rootfs=$rootfs",
            "--link2symlink",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=$home:/root",
            "--bind=$tmp:/tmp",
        )
        if (File("/system/build.prop").canRead()) args.add("--bind=/system/build.prop:/system/build.prop")
        args.add("-0")
        args.add("-w"); args.add("/root")
        args.add("--bind=$bin:/qurohost")
        args.add("/qurohost"); args.add("/bin/sh")
        val env = mutableMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${usrBinDir.absolutePath}",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LD_LIBRARY_PATH" to "${sandboxDir.absolutePath}:${usrBinDir.absolutePath}",
            "PROOT_TMP_DIR" to tmp,
            "PROOT_LOADER" to loader,
        )
        return QuroHostLaunch(args, env, File(rootfs).parentFile ?: File(rootfs))
    }

    /** 启动一个常驻 host 进程（供 [QuroShellSession] host 模式持有）。 */
    fun launch(context: Context): QuroHostProcess? {
        val l = buildLaunch(context) ?: return null
        return try {
            val pb = ProcessBuilder(l.args)
            pb.directory(l.workDir)
            pb.environment().putAll(l.env)
            pb.redirectErrorStream(true)
            QuroHostProcess(pb.start())
        } catch (e: Exception) {
            Log.e(TAG, "启动 qurohost 失败: ${e.message}")
            null
        }
    }

    // ═══════════ 一次性 CMS / 开发者环境命令（原生优先，旧 Kotlin 路径作兜底在调用方） ═══════════

    fun cmsList(context: Context): String? = oneShot(context, "cms list")
    fun cmsDeploy(context: Context, id: String, entryBase64: String): String? = oneShot(context, "cms deploy $id $entryBase64")
    fun cmsRun(context: Context, id: String): String? = oneShot(context, "cms run $id")
    fun cmsRemove(context: Context, id: String): String? = oneShot(context, "cms remove $id")
    fun devenvStatus(context: Context): String? = oneShot(context, "devenv status")
    fun devenvProvision(context: Context, tool: String): String? = oneShot(context, "devenv provision $tool")

    /**
     * 拉起一个临时 qurohost，发送一条控制命令，读取对应的控制响应 JSON（剥掉前缀）后强杀进程。
     * 用于 CMS/DevEnv 屏幕的一次性查询/操作；持续终端会话走 [QuroShellSession] host 模式。
     */
    private fun oneShot(context: Context, cmd: String): String? {
        val p = launch(context) ?: return null
        return try {
            p.writer.write(CONTROL_PREFIX + cmd + "\n")
            p.writer.flush()
            var line: String?
            while (p.reader.readLine().also { line = it } != null) {
                val s = line ?: continue
                if (s.startsWith(CONTROL_RESP_PREFIX)) return s.substring(CONTROL_RESP_PREFIX.length)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "oneShot($cmd) 失败: ${e.message}")
            null
        } finally {
            runCatching { p.process.destroyForcibly() }
        }
    }
}

/** host 进程启动参数。 */
data class QuroHostLaunch(
    val args: MutableList<String>,
    val env: MutableMap<String, String>,
    val workDir: File,
)

/** 已启动的 host 进程句柄（读写流由调用方 [QuroShellSession] 的 drain 循环持有）。 */
class QuroHostProcess(val process: Process) {
    val reader: BufferedReader = process.inputStream.bufferedReader()
    val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
}
