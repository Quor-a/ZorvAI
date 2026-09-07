package com.ai.assistance.quro.core.python

import android.content.Context
import java.io.File

/**
 * 原生 CPython 3.14 引擎（嵌入模式，PEP 738 Tier 3 路线）。
 *
 * 集成方式遵循 python.org 官方 Android 嵌入指南：
 *  - libpython3.14.so + libssl/crypto/sqlite3_python.so → full 风味 jniLibs（arm64-v8a）；
 *  - 标准库 python3.14 整棵目录（含 lib-dynload C 扩展）→ full 风味 assets，首启解压到 filesDir/python；
 *  - libquropybridge.so（app/src/main/jni/pybridge.c）经 JNI 提供初始化 / 执行 / 中断；
 *  - PYTHONHOME = filesDir/python。
 *
 * 可用性：仅 full 风味可用；fdroid 风味无预编译库，ensure() 返回错误，
 * 调用方（RunCodeTool 等）自动降级到 Linux 沙箱 python3 / Brython。
 *
 * 执行模型：单解释器常驻进程；看门狗线程超时调 PyErr_SetInterrupt，
 * 用户代码在字节码边界收到 KeyboardInterrupt，安全打断。
 */
object PyEngine {

    const val PY_VERSION = "3.14.7"

    /** full 风味 assets 下的 Python 根（src/full/assets/python）。 */
    private const val ASSET_ROOT = "python"

    /** 解压目标：filesDir/python（PYTHONHOME）。 */
    private const val HOME_DIR = "python"

    private const val DEFAULT_TIMEOUT_MS = 15000L

    private sealed class State {
        object Uninit : State()
        object Loading : State()
        object Ready : State()
        data class Failed(val reason: String) : State()
    }

    @Volatile
    private var state: Any = State.Uninit

    data class Result(
        val stdout: String,
        val stderr: String,
        /** 引擎级错误（不可用 / 超时 / 异常）；用户代码的 traceback 在 stderr。 */
        val error: String?,
    ) {
        fun format(): String = buildString {
            if (error != null) append("⚠️ ").append(error).append('\n')
            if (stdout.isNotBlank()) append(stdout.trimEnd()).append('\n')
            if (stderr.isNotBlank()) append("⚠️ ").append(stderr.trim()).append('\n')
        }.trim().ifEmpty { "(无输出)" }
    }

    /** 初始化（含首启解压标准库，~2600 文件 / 几秒钟）。null=就绪，否则错误说明。 */
    fun ensure(context: Context): String? {
        when (val s = state) {
            is State.Ready -> return null
            is State.Failed -> return s.reason
        }
        synchronized(this) {
            when (val s = state) {
                is State.Ready -> return null
                is State.Failed -> return s.reason
                State.Loading -> return "Python 引擎初始化中"
                State.Uninit -> {}
            }
            state = State.Loading
            val err = doEnsure(context.applicationContext)
            if (err != null) {
                state = State.Failed(err)
                return err
            }
            state = State.Ready
            return null
        }
    }

    private fun doEnsure(ctx: Context): String? {
        // 1. 原生桥
        try {
            System.loadLibrary("quropybridge")
        } catch (e: UnsatisfiedLinkError) {
            return "原生 Python 桥不可用：${e.message}"
        }

        // 2. 标准库解压（版本标记文件在则跳过，升级换版本号即可全量重解压）
        val home = File(ctx.filesDir, HOME_DIR)
        val marker = File(home, ".stdlib-$PY_VERSION")
        if (!marker.exists()) {
            val top = runCatching { ctx.assets.list(ASSET_ROOT) }.getOrNull()
            if (top.isNullOrEmpty()) return "此构建未打包 Python 标准库（full 风味专属）"
            runCatching { extractAssetDir(ctx, ASSET_ROOT, ctx.filesDir) }
                .getOrElse { return "标准库解压失败：${it.message}" }
            marker.writeText(PY_VERSION)
        }

        // 3. TMPDIR（Python 找临时目录用，Android 仅 API 33+ 自动设置）
        runCatching { android.system.Os.setenv("TMPDIR", ctx.cacheDir.absolutePath, false) }

        // 4. 启动解释器
        return nativeInit(home.absolutePath)
    }

    /**
     * 运行一段 Python 代码。stdout/stderr 捕获后返回；超时经 KeyboardInterrupt 打断。
     * 引擎不可用时 error 带 reason，调用方自行降级。
     */
    fun run(context: Context, code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        ensure(context)?.let { return Result("", "", it) }
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                nativeInterrupt()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        return try {
            val out = arrayOfNulls<String>(2)
            nativeRun(code, out)
            val stdout = out[0] ?: ""
            val stderr = out[1] ?: ""
            val timedOut = stderr.contains("KeyboardInterrupt")
            Result(
                stdout = stdout,
                stderr = stderr,
                error = if (timedOut) "执行超时（>${timeoutMs / 1000}s，已中断）" else null,
            )
        } catch (e: Throwable) {
            Result("", "", "Python 执行异常：${e.message}")
        } finally {
            watchdog.interrupt()
        }
    }

    /** 引擎是否可用（不触发完整初始化时用于探测：仅看桥与资产是否打包）。 */
    fun probeAvailable(context: Context): Boolean {
        val hasAssets = runCatching {
            !context.assets.list(ASSET_ROOT).isNullOrEmpty()
        }.getOrDefault(false)
        return hasAssets
    }

    /* ===================== 资产解压 ===================== */

    /** 递归把 assets 下 path 目录整棵复制到 base 下（保持相对结构）。 */
    private fun extractAssetDir(context: Context, path: String, base: File) {
        val names = context.assets.list(path) ?: emptyArray()
        val targetDir = File(base, path)
        if (!targetDir.exists()) targetDir.mkdirs()
        for (name in names) {
            val subPath = "$path/$name"
            val input = try {
                context.assets.open(subPath)
            } catch (e: java.io.FileNotFoundException) {
                // 是目录 → 递归
                extractAssetDir(context, subPath, base)
                continue
            }
            input.use { ins ->
                File(targetDir, name).outputStream().use { ins.copyTo(it) }
            }
        }
    }

    /* ===================== native ===================== */

    private external fun nativeInit(home: String): String?
    private external fun nativeRun(code: String, out: Array<String?>): Int
    private external fun nativeInterrupt()
}
