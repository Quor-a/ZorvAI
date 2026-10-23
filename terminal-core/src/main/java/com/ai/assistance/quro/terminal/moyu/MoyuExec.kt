package com.ai.assistance.quro.terminal.moyu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 墨语言统一执行层：所有需要在 Android 上跑起来的 ELF（qemu、aarch64 产物）都从这里出发。
 *
 * 为什么要这一层 —— Android 10 之后的一条硬限制：
 *
 *   `targetSdk >= 29` 的应用，其私有数据目录 `/data/user/0/<pkg>/...` 在 **SELinux**
 *   层面被禁止 execve（不是文件权限问题，`chmod +x` 无效）。ZorvAI 的 targetSdk 是 34，
 *   所以把 qemu 解压到 `filesDir` 后直接 `ProcessBuilder` 启动，拿到的必然是 EACCES。
 *
 *   唯一能执行的 App 内目录是 `nativeLibraryDir`（APK 的 lib/ 安装后落点）。所以：
 *
 *   1. 随包内置一个 aarch64 启动器，以 `libmoexec.so` 名字打进 jniLibs ——
 *      安装后它在 nativeLibraryDir 里，可以被拉起；
 *   2. 它把目标 ELF 读进 memfd（匿名内存文件，没有路径），再 `execveat(AT_EMPTY_PATH)`
 *      执行，绕开"按路径检查"的那道限制。
 *
 *   于是 filesDir 里的任何 ELF（包括动态编译出来的）都能跑。
 *
 * 三级执行策略（每次失败都把真实原因带回给界面，不做静默降级）：
 *
 *   1. 经 libmoexec.so（memfd）—— Android 10+ 的正路
 *   2. 直接 exec 目标文件 —— Android 9 及更早、或个别宽松 ROM
 *   3. 都失败 → 返回诊断文本，指出是 SELinux 还是文件缺失
 */
object MoyuExec {

    private const val TAG = "MoyuExec"

    /** 启动器在 nativeLibraryDir（**必须是可执行目录**，不能拷到 filesDir 再用）。 */
    fun launcher(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libmoexec.so")

    /**
     * 启动器是否就位。
     *
     * 注意：不能用 `canExecute()` 判断可用性 —— 它只查 inode 的 mode 位，看不到 SELinux。
     * 这里只判断文件存在，真正能不能跑由第一次执行的结果决定。
     */
    fun hasLauncher(context: Context): Boolean = launcher(context).isFile

    /** 组装命令行：有启动器就走 memfd，没有就退化为直接执行。 */
    fun command(context: Context, binary: File, args: List<String>): List<String> {
        val l = launcher(context)
        return if (l.isFile) {
            listOf(l.absolutePath, binary.absolutePath) + args
        } else {
            listOf(binary.absolutePath) + args
        }
    }

    /** 默认环境：指向 App 私有目录，避免工具去写系统只读路径。 */
    fun baseEnv(context: Context): Map<String, String> {
        val root = File(context.filesDir, "moyu")
        return mapOf(
            "HOME" to File(root, "workspace").apply { mkdirs() }.absolutePath,
            "TMPDIR" to File(root, "tmp").apply { mkdirs() }.absolutePath,
            "LANG" to "C.UTF-8",
        )
    }

    /**
     * 执行一个 ELF。
     *
     * @param binary  目标 ELF（可在 filesDir，任意位置）
     * @param viaLauncher 是否允许经 memfd 启动器；qemu 这类本身就是"宿主工具"的也走这条
     */
    suspend fun run(
        context: Context,
        binary: File,
        args: List<String> = emptyList(),
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 180_000,
        viaLauncher: Boolean = true,
    ): Result = withContext(Dispatchers.IO) {
        if (!binary.isFile) {
            return@withContext Result(-1, "找不到可执行文件：${binary.absolutePath}")
        }
        val dir = (workDir ?: File(context.filesDir, "moyu/workspace")).apply { mkdirs() }
        val mergedEnv = baseEnv(context) + env

        // ① 经 memfd 启动器
        if (viaLauncher && hasLauncher(context)) {
            val cmd = listOf(launcher(context).absolutePath, binary.absolutePath) + args
            val r = spawn(cmd, dir, mergedEnv, timeoutMs)
            // 127 = 启动器内三种方式全失败；126 = 启动器自身读不了目标。
            // 这两种才值得再试"直接执行"，其余退出码是目标程序自己的，必须原样返回。
            if (r.code != 127 && r.code != 126) {
                return@withContext r.copy(viaLauncher = true)
            }
            Log.w(TAG, "memfd 启动失败（code=${r.code}），改试直接执行：${r.output.take(300)}")
            val r2 = spawn(listOf(binary.absolutePath) + args, dir, mergedEnv, timeoutMs)
            if (r2.code != 126 && r2.code != 127) {
                return@withContext r2.copy(viaLauncher = false)
            }
            return@withContext Result(
                r2.code,
                buildString {
                    appendLine("目标程序无法在本次执行环境中启动。三级方式都失败：")
                    appendLine()
                    appendLine("① 经 memfd 启动器（libmoexec.so）：")
                    appendLine(indent(r.output))
                    appendLine("② 直接 exec（filesDir）：")
                    appendLine(indent(r2.output))
                    appendLine()
                    appendLine("如果是 EACCES / EPERM，说明这条 ROM 的 SELinux 同时禁止了")
                    appendLine("memfd 执行与私有目录执行。可用目标 x86_64（qemu）代替，")
                    appendLine("或把产物放进 APK 的 jniLibs 再随包安装。")
                },
                diag = true,
            )
        }

        // ② 直接执行
        val r = spawn(listOf(binary.absolutePath) + args, dir, mergedEnv, timeoutMs)
        r.copy(viaLauncher = false)
    }

    private suspend fun spawn(
        cmd: List<String>,
        dir: File,
        env: Map<String, String>,
        timeoutMs: Long,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder(cmd)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                Result(-1, (out + "\n[超时 ${timeoutMs / 1000}s，已终止]").trim(), timedOut = true)
            } else {
                Result(p.exitValue(), out.trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "spawn 失败：$cmd", e)
            Result(-1, "启动失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun indent(s: String): String =
        s.lines().joinToString("\n") { "   $it" }

    /** 执行结果。`viaLauncher` 记录这次到底走的是 memfd 还是直接 exec，便于真机排查。 */
    data class Result(
        val code: Int,
        val output: String,
        val timedOut: Boolean = false,
        val viaLauncher: Boolean? = null,
        val diag: Boolean = false,
    ) {
        val ok: Boolean get() = code == 0 && !timedOut
    }
}
