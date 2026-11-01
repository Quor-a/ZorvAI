package com.ai.assistance.quro.core.termux

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * **真 Termux 环境**（Termux 官方 bootstrap），与 proot Ubuntu 是两条独立通道。
 *
 * ## 为什么要单独有这么一层（用户实测指出）
 *
 * 原来 `termux` 工具的 `run` / `pkg` 全走 [com.ai.assistance.quro.core.linux.QuroLinuxEnv]，
 * 也就是 proot Ubuntu 24.04。AI 实测反馈：
 *
 *  - ❌ 没有 `PREFIX` 变量（Termux 最核心的特征）
 *  - ❌ 没有 `TERMUX_VERSION`
 *  - ❌ 没有 `pkg` 包管理器（Termux 专属，不是 apt）
 *  - ❌ 没有 `/data/data/com.termux/files/` 路径
 *
 * 也就是说名字叫 Termux，跑的其实是 Ubuntu —— 和 `linux_run` 完全重复。
 * 用户要的是**官方定义的 Termux**：Termux 官方 bootstrap + PREFIX + pkg + TERMUX_VERSION，
 * 而 proot Ubuntu 保留在 `linux_*` 那条通道上。
 *
 * ## 实现方式
 *
 * 1. 首次把 `assets/termux-bootstrap-aarch64.zip`（Termux 官方 release 原包）解压到
 *    `filesDir/termux/usr`；
 * 2. 按包内 `SYMLINKS.txt` 还原符号链接 —— **这一步不能省**：`bin/sh`、`lib/libc.so`
 *    这些关键入口在 zip 里都是软链，不建链 bash 会直接起不来。
 *    （格式：`<目标名>←<相对 PREFIX 的链接路径>`，与 Termux 自带 termux-restore 一致）
 * 3. 用 proot 把 `filesDir/termux/usr` **bind 到 `/data/data/com.termux/files/usr`** ——
 *    Termux 的二进制 rpath 硬编码了这个路径，不映射过去会找不到自己的库；
 * 4. `PREFIX` / `HOME` / `TERMUX_VERSION` 等环境变量按官方语义给全。
 */
object TermuxEnvironment {

    private const val TAG = "TermuxEnvironment"
    private const val ASSET = "termux-bootstrap-aarch64.zip"

    /** Termux 官方在 Android 上的固定前缀。二进制 rpath 写死它，故必须原样还原。 */
    const val GUEST_PREFIX = "/data/data/com.termux/files/usr"
    const val GUEST_HOME = "/data/data/com.termux/files/home"

    /** 标记解压完成的文件（内容为 ASSET 大小，便于换包后自动重装）。 */
    private const val STAMP = ".bootstrap.stamp"

    fun termuxRoot(ctx: Context): File = File(ctx.filesDir, "termux")
    fun usrDir(ctx: Context): File = File(termuxRoot(ctx), "usr")
    fun homeDir(ctx: Context): File = File(termuxRoot(ctx), "home")

    /** 环境是否就绪：bash 存在且可执行。 */
    fun isReady(ctx: Context): Boolean {
        val bash = File(usrDir(ctx), "bin/bash")
        return bash.isFile && bash.canExecute()
    }

    /** 是否需要安装（未解压 / 换了 bootstrap 包）。 */
    fun needsInstall(ctx: Context): Boolean {
        if (!isReady(ctx)) return true
        val stamp = File(termuxRoot(ctx), STAMP)
        return !stamp.isFile || stamp.readText().trim() != assetSize(ctx).toString()
    }

    private fun assetSize(ctx: Context): Long = runCatching {
        ctx.assets.open(ASSET).use { it.available().toLong() }
    }.getOrDefault(0L)

    /**
     * 解压 bootstrap + 还原软链。**耗时操作，必须放 IO 线程。**
     *
     * @param onProgress 可选进度回调（已解压条目数 / 当前条目名）
     * @return null 表示成功，否则是失败原因
     */
    fun install(ctx: Context, onProgress: ((Int, String) -> Unit)? = null): String? {
        val usr = usrDir(ctx)
        val home = homeDir(ctx)
        return try {
            // 换包/损坏时先清掉旧的，避免半套文件混在一起
            if (usr.exists()) usr.deleteRecursively()
            usr.mkdirs()
            home.mkdirs()
            File(usr, "tmp").mkdirs()

            var count = 0
            var symlinksTxt: String? = null
            ctx.assets.open(ASSET).use { input ->
                ZipInputStream(input.buffered(1 shl 16)).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val name = e.name
                        if (name == "SYMLINKS.txt") {
                            // 软链清单要等普通文件解压完再处理（目标文件得先存在）
                            symlinksTxt = zip.readBytes().toString(Charsets.UTF_8)
                        } else if (!e.isDirectory && name.isNotBlank() && !name.contains("..")) {
                            val out = File(usr, name)
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered(1 shl 16).use { fos -> zip.copyTo(fos) }
                            // Termux 的可执行位靠 zip 外部属性，这里补上
                            if (name.startsWith("bin/") || name.startsWith("libexec/")) {
                                runCatching { out.setExecutable(true, false) }
                            }
                            count++
                            if (count % 200 == 0) onProgress?.invoke(count, name)
                        }
                        zip.closeEntry()
                        e = zip.nextEntry
                    }
                }
            }

            // 还原软链
            val missing = restoreSymlinks(usr, symlinksTxt)
            Log.i(TAG, "bootstrap 解压完成：$count 个文件，软链缺目标 $missing 个")

            // 兜底：bin/sh 指向 bash（部分精简包会漏掉这条）
            val sh = File(usr, "bin/sh")
            if (!sh.exists()) runCatching { Os.symlink("bash", sh.absolutePath) }

            File(termuxRoot(ctx), STAMP).writeText(assetSize(ctx).toString())
            if (isReady(ctx)) null else "解压完成但 bash 不可执行（可能 assets 损坏）"
        } catch (t: Throwable) {
            Log.e(TAG, "bootstrap 安装失败", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * 按 SYMLINKS.txt 建软链。
     *
     * 每行格式：`<目标名>←<相对 PREFIX 的链接路径>`，例如
     * `libnettle.so.8.11←./lib/libnettle.so` → 创建 `lib/libnettle.so` 指向 `libnettle.so.8.11`。
     * 目标用**裸文件名**（相对链接所在目录解析），与 Termux 官方一致。
     *
     * @return 因目标不存在而跳过的条数
     */
    private fun restoreSymlinks(usr: File, txt: String?): Int {
        if (txt.isNullOrBlank()) return 0
        var missing = 0
        var made = 0
        for (raw in txt.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val sep = line.indexOf('←')
            if (sep <= 0) continue
            val targetName = line.substring(0, sep).trim()
            val linkRel = line.substring(sep + 1).trim().removePrefix("./")
            if (targetName.isEmpty() || linkRel.isEmpty() || linkRel.contains("..")) continue
            val linkFile = File(usr, linkRel)
            linkFile.parentFile?.mkdirs()
            runCatching {
                runCatching { linkFile.delete() }
                Os.symlink(targetName, linkFile.absolutePath)
                made++
            }.onFailure { missing++ }
        }
        Log.i(TAG, "软链还原：成功 $made，失败/缺目标 $missing")
        return missing
    }

    /**
     * 在 Termux 环境里跑一条命令。
     *
     * 走 proot：rootfs = `filesDir/termux`，并把 `usr` bind 到 `/data/data/com.termux/files/usr`，
     * 这样 Termux 的二进制能按自己的 rpath 找到库，`$PREFIX` 也才是官方那个值。
     *
     * @return (exitCode, 合并后的输出)
     */
    fun run(ctx: Context, command: String, timeoutMs: Long = 120_000): Pair<Int, String> {
        if (!isReady(ctx)) return -1 to "Termux 环境未就绪（先执行安装）"

        val nativeLibDir = File(ctx.applicationInfo.nativeLibraryDir)
        val prootBin = File(nativeLibDir, "libproot.so")
        val loader = File(nativeLibDir, "libproot-loader.so")
        if (!prootBin.isFile) return -1 to "proot 二进制缺失（${prootBin.absolutePath}）"

        val root = termuxRoot(ctx)
        val usr = usrDir(ctx)
        val home = homeDir(ctx)
        val tmpDir = File(ctx.filesDir, "tmp").apply { mkdirs() }

        val cmd = mutableListOf(
            prootBin.absolutePath,
            "--rootfs=${root.absolutePath}",
            "--link2symlink",
            "--bind=/dev",
            "--bind=/dev/pts",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/sys",
            "--bind=$tmpDir:/tmp",
            "--bind=$tmpDir:$GUEST_PREFIX/tmp",
            // ★ 关键：Termux 二进制 rpath 硬编码这个路径，必须映射过去
            "--bind=${usr.absolutePath}:$GUEST_PREFIX",
            "--bind=${home.absolutePath}:$GUEST_HOME",
            "--bind=${ctx.filesDir.absolutePath}:/quro",
            "-0",
            "-w", GUEST_HOME,
            "$GUEST_PREFIX/bin/bash",
            "-lc",
            command,
        )

        val pb = ProcessBuilder(cmd)
        pb.directory(ctx.filesDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PREFIX"] = GUEST_PREFIX
        env["HOME"] = GUEST_HOME
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["PATH"] = "$GUEST_PREFIX/bin:$GUEST_PREFIX/bin/applets:/system/bin:/system/xbin"
        env["LD_LIBRARY_PATH"] = "$GUEST_PREFIX/lib:${nativeLibDir.absolutePath}"
        env["TMPDIR"] = "$GUEST_PREFIX/tmp"
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = loader.absolutePath
        // Termux 官方语义：这几个变量是「我是 Termux」的判据
        env["TERMUX_VERSION"] = ASSET_VERSION
        env["TERMUX_APP_PACKAGE"] = ctx.packageName
        env["TERMUX_MAIN_PACKAGE_FORMAT"] = "debian"
        env["TERMUX_IS_DEBUGGABLE_BUILD"] = "0"
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_ROOT"] = "/system"
        env["SHELL"] = "$GUEST_PREFIX/bin/bash"
        env["SSL_CERT_FILE"] = "$GUEST_PREFIX/etc/tls/cert.pem"

        return try {
            val p = pb.start()
            val out = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val t = Thread {
                runCatching {
                    reader.forEachLine { line ->
                        synchronized(out) { out.appendLine(line) }
                    }
                }
                done.set(true)
            }.apply { isDaemon = true; start() }

            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { p.destroyForcibly() }
                return -1 to (synchronized(out) { out.toString() } + "\n[超时 ${timeoutMs}ms，已终止]")
            }
            // 给读线程一点时间把剩余缓冲收完
            val deadline = System.currentTimeMillis() + 2000
            while (!done.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            p.exitValue() to synchronized(out) { out.toString() }
        } catch (t: Throwable) {
            Log.e(TAG, "termux run 失败", t)
            -1 to "执行失败：${t.message}"
        }
    }

    /** 给 UI/诊断用的环境摘要（AI 问「你是什么环境」时能报出真实值）。 */
    fun describe(ctx: Context): String = buildString {
        appendLine("Termux 环境：${if (isReady(ctx)) "已就绪" else "未安装"}")
        appendLine("PREFIX=$GUEST_PREFIX")
        appendLine("HOME=$GUEST_HOME")
        appendLine("TERMUX_VERSION=$ASSET_VERSION")
        appendLine("宿主路径=${usrDir(ctx).absolutePath}")
    }

    /**
     * 报给 `$TERMUX_VERSION` 的值。
     *
     * 取自打进 assets 的官方 bootstrap：`bootstrap-2026.11.01-r1+apt.android-7`。
     * 换 bootstrap 包时这里要跟着改 —— 它是「我是 Termux」的判据之一，
     * AI 和环境探测脚本都会读它。
     */
    private const val ASSET_VERSION = "2026.11.01-r1"
}
