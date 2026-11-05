package com.ai.assistance.quro.core.termux

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * **真 Termux 环境**（Termux 官方 bootstrap），与 proot Ubuntu 是两条独立通道。
 *
 * ## 为什么要单独有这么一层
 *
 * 原来 `termux` 工具的 `run` / `pkg` 全走 [com.ai.assistance.quro.core.linux.QuroLinuxEnv]，
 * 也就是 proot Ubuntu 24.04 —— 名字叫 Termux，跑的其实是 Ubuntu，没有 `PREFIX` /
 * `TERMUX_VERSION` / `pkg`。用户要的是**官方定义的 Termux**，proot Ubuntu 保留在 `linux_*` 通道。
 *
 * ## 实现方式（以下每一条都对照官方源码逐项核对过）
 *
 * 1. 把 `assets/termux-bootstrap-aarch64.zip`（Termux 官方 release 原包）解压到
 *    `filesDir/termux/usr`；
 * 2. 按包内 `SYMLINKS.txt` 还原符号链接 —— **这一步不能省**：`bin/sh`、`lib/libcrypto.so`
 *    这些关键入口在 zip 里都是软链，不建链 bash 直接起不来。
 *    格式与官方 `TermuxInstaller.setupBootstrapIfNeeded` 完全一致：`<链接目标>←<相对 PREFIX 的链接名>`；
 * 3. 🔴 **执行通道不是「直接 exec」，是官方的 system_linker_exec**（详见 [TermuxExec]）。
 *    bootstrap 里都是 Android/Bionic 二进制（`PT_INTERP=/system/bin/linker64`），但 Android 10 起
 *    SELinux 禁止 `untrusted_app` 域 exec `app_data_file`（= `/data/data/<pkg>` 下任何文件），
 *    本应用 targetSdk 34 正落在这个域里。于是**对私有目录里的 ELF 做 chmod、重装、repair 一律无效**，
 *    `execve` 永远 EACCES —— 旧实现把它误判成「可执行位异常」，repair 循环到天荒地老。
 *    正确做法（官方 `termux-exec` 2.5.0 的做法）：第一个进程用
 *    `execve(/system/bin/linker64, [<prefix>/bin/bash, -lc, cmd])` 起来（linker 属
 *    `system_linker_exec` 域，可被 app exec，它再以**读权限 mmap** 目标 ELF），
 *    之后所有子进程由 `$LD_PRELOAD` 的 termux-exec 自动改写同一条路径。
 *    走这条路时主预加载库必须是 **linker 变体**，由 [installPreloadVariant] 按官方
 *    `termux-exec-ld-preload-lib setup` 语义选好。
 * 4. bootstrap 内 500+ 处硬编码官方前缀 `/data/data/com.termux/files/usr`（脚本 shebang、
 *    `etc/profile`、`pkg`/`apt`/`dpkg` 配置）。原生执行时该路径在本应用沙箱里不存在，
 *    解压后把文本文件里的该前缀**按字节**改写成真实安装路径（[relocatePrefixBytes]）——
 *    注意是字节级替换，不能 `toString(UTF_8)` 再写回：包里大量非 UTF-8 的 share/terminfo
 *    数据会被替换字符污染（旧实现就踩了这个坑）；
 * 5. **`LD_PRELOAD` 预加载 termux-exec**：这是「`pkg install` 装完之后跑不起来」的真因。
 *    官方 `bin/login` 在启动时 `export LD_PRELOAD=$PREFIX/lib/libtermux-exec-ld-preload.so`，
 *    由它拦截 `execve`：(a) 把新装包里写死的官方前缀 shebang 重写成真实前缀（读
 *    `TERMUX__PREFIX`）；(b) 在 system_linker_exec 模式下把每次 exec 改写成走 linker64。
 *    旧实现没设 `LD_PRELOAD`，于是 bootstrap 自带命令能跑、**用户用 pkg 现装的任何包全部跑不起来**；
 * 6. **安装到 staging 再 rename**（官方 TermuxInstaller 第 3/5 步）：直接往 `usr` 里解压，
 *    中途被杀进程就会留下半套文件，而 `bash` 已存在 → `isReady` 骗过上层，之后怎么重装都修不好。
 *    现在解压到 `usr.staging`，全部完成才原子 rename；
 * 7. 环境变量给全**两代命名**：官方 v0.119 起把 `TERMUX_APP_PACKAGE` 一类改名为
 *    `TERMUX_APP__PACKAGE_NAME`，并新增 `TERMUX__PREFIX` / `TERMUX__HOME` / `TERMUX__ROOTFS` /
 *    `TERMUX__VAR` / `TERMUX__USER_ID`。bootstrap 里的 `termux-tools` 脚本（`pkg`、
 *    `termux-change-repo`、`termux-setup-storage`…）读的是**新名字**，只给老名字会静默失败。
 *    其中 `TERMUX_APP__DATA_DIR` / `TERMUX__SE_PROCESS_CONTEXT` 还决定 termux-exec 会不会对
 *    某个路径启用 system_linker_exec，必须与真实路径、真实 SE 域一致。
 */
object TermuxEnvironment {

    private const val TAG = "TermuxEnvironment"
    private const val ASSET = "termux-bootstrap-aarch64.zip"

    /** Termux 官方在 Android 上的固定前缀。二进制 DT_RUNPATH 写死它，故必须原样识别。 */
    const val GUEST_PREFIX = "/data/data/com.termux/files/usr"
    const val GUEST_HOME = "/data/data/com.termux/files/home"

    /** 解压完成标记（内容为 bootstrap 版本号）：没有它 = 半套文件，必须重装。 */
    private const val STAMP = ".bootstrap.stamp"

    /** 运行时修正标记（内容为修正版本号）。 */
    private const val RUNTIME_FIX_STAMP = ".runtimefix.stamp"

    /** v3：补 LD_PRELOAD 变体选择（linker / direct）+ system_linker_exec 环境变量。 */
    /**
     * 运行时修正版本。
     *
     * 🔴 **每次改动 [applyRuntimeFixes] 里的内容都必须 +1**：修正只在启动时按 stamp 跑一次，
     * 版本号不变的话，已经装过旧版 bootstrap 的设备（stamp 相同）永远拿不到新修正，
     * 表现就是「代码明明改了，真机还是老毛病」。
     *  v3 → v4：补 `Dir::Bin::methods`（apt method 路径）、keyring 归位、https→http 降级。
     */
    private const val RUNTIME_FIX_VERSION = "5"

    /** 探测出来的执行通道缓存（避免每次冷启动都靠试错）。 */
    private const val MODE_STAMP = ".execmode"

    /** termux-exec 的 LD_PRELOAD 实测不可用时落这个标记，后续不再尝试（避免每条命令都失败一次）。 */
    private const val LD_PRELOAD_BROKEN = ".ldpreload.broken"

    /** 打到 assets 的 bootstrap 版本（官方 release 包）。换包时改这里。 */
    const val BOOTSTRAP_VERSION = "2026.11.01-r1"

    /** 给外部（zorv 脚本、status 文案）用的版本号。 */
    val bootstrapVersion: String get() = BOOTSTRAP_VERSION

    /** 单文件重定位上限：超过这个大小基本是数据文件，跳过（不可能是脚本/shebang）。 */
    private const val RELOCATE_MAX_BYTES = 8L * 1024 * 1024

    fun termuxRoot(ctx: Context): File = File(ctx.filesDir, "termux")
    fun usrDir(ctx: Context): File = File(termuxRoot(ctx), "usr")
    fun homeDir(ctx: Context): File = File(termuxRoot(ctx), "home")

    /** 解压暂存目录（全部完成后再 rename 成 [usrDir]，见类注释第 6 点）。 */
    private fun stagingDir(ctx: Context): File = File(termuxRoot(ctx), "usr.staging")

    /**
     * 环境**文件层**是否就绪 = `bin/bash` 在位 **且** 安装完成标记在位。
     *
     * 这里**不再**要求 `bash.canExecute()`：走 system_linker_exec 时 x 位毫无意义
     * （linker 只要读权限），而 `access(X_OK)` 本来也不反映 SELinux 的 exec 限制 ——
     * 旧实现正是在这里「看着是就绪、跑起来 Permission denied」。能不能跑由 [probe] 说了算。
     */
    fun isReady(ctx: Context): Boolean {
        val bash = File(usrDir(ctx), "bin/bash")
        if (!bash.isFile) return false
        val stamp = File(termuxRoot(ctx), STAMP)
        return stamp.isFile && stamp.readText().trim() == BOOTSTRAP_VERSION
    }

    /** 是否需要安装（未安装 / 半套 / 换了 bootstrap 包）。 */
    fun needsInstall(ctx: Context): Boolean = !isReady(ctx)

    /**
     * 解压 bootstrap + 还原软链 + 前缀重定位 + 补可执行位 + 选预加载变体。**耗时操作，必须放 IO 线程。**
     *
     * @param onProgress 可选进度回调（已解压条目数 / 当前条目名）
     * @return null 表示成功，否则是失败原因
     */
    fun install(ctx: Context, onProgress: ((Int, String) -> Unit)? = null): String? {
        val usr = usrDir(ctx)
        val home = homeDir(ctx)
        val staging = stagingDir(ctx)
        return try {
            // 1) 清掉上次残留的暂存目录（官方 TermuxInstaller 第 3 步）
            if (staging.exists()) staging.deleteRecursively()
            // 运行时修正标记基于「上一套 usr」，重装后必须重跑，否则 apt/dpkg 目录不会重建
            File(termuxRoot(ctx), RUNTIME_FIX_STAMP).delete()
            // 换了一套文件，执行通道的探测结果也要重来
            File(termuxRoot(ctx), MODE_STAMP).delete()
            modeCache = null
            if (!staging.mkdirs() && !staging.isDirectory) return "无法创建暂存目录 ${staging.absolutePath}"
            home.mkdirs()
            File(staging, "tmp").mkdirs()

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
                        } else if (name.isNotBlank() && !name.contains("..")) {
                            val out = File(staging, name)
                            if (e.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().buffered(1 shl 16).use { fos -> zip.copyTo(fos) }
                                // 官方只给 bin/、libexec、apt 的 helper/methods 设执行位；这里
                                // 对齐官方，其余 ELF（含 .so）由 ensureElfExecutable 统一补
                                if (isExecutableEntry(name)) runCatching { out.setExecutable(true, false) }
                                count++
                                if (count % 200 == 0) onProgress?.invoke(count, name)
                            }
                        }
                        zip.closeEntry()
                        e = zip.nextEntry
                    }
                }
            }

            // 2) 还原软链（在暂存目录里建，软链目标是相对路径，rename 后依然有效）
            val missing = restoreSymlinks(staging, symlinksTxt)
            Log.i(TAG, "bootstrap 解压完成：$count 个文件，软链失败/缺目标 $missing 个")

            // 3) bin/sh 兜底（部分精简包会漏掉这条）
            val sh = File(staging, "bin/sh")
            if (!sh.exists()) runCatching { Os.symlink("bash", sh.absolutePath) }

            // 4) 前缀重定位：暂存目录里的文本文件改写成**最终真实路径**（不是暂存路径！）
            val relocated = relocatePrefixBytes(staging, GUEST_PREFIX, usr.absolutePath)
            Log.i(TAG, "前缀重定位：$relocated 个文本文件 → ${usr.absolutePath}")

            // 5) 补可执行位：zip 里 lib 目录下的 .so 多为 0644，缺 x 位会让链接器 mmap PROT_EXEC 时报 EACCES
            //    （system_linker_exec 通道其实只要读权限，但 direct 通道与部分自检脚本仍看 x 位）
            val elfCount = ensureElfExecutable(staging)
            Log.i(TAG, "ELF 可执行位修正：$elfCount 个")

            // 6) 原子切换：先删旧树再 rename（rename 同一分区内是原子操作）
            if (usr.exists() && !usr.deleteRecursively()) {
                Log.w(TAG, "旧 usr 目录删不干净，继续尝试 rename")
            }
            if (!staging.renameTo(usr)) {
                // rename 失败（极少数机型 /data 分区限制）→ 退化为拷贝
                Log.w(TAG, "staging rename 失败，退化为拷贝")
                copyTree(staging, usr)
                staging.deleteRecursively()
            }

            // 7) 运行时修正（apt/dpkg 目录 + APT_CONFIG + LD_PRELOAD 变体），见 applyRuntimeFixes
            applyRuntimeFixes(ctx)?.let { err -> Log.e(TAG, "Termux 运行时修正失败：$err") }

            // 8) 先落标记再接通道探测：文件已经在位，探测失败不该再触发一次解压（那正是 repair 死循环的来源）
            File(termuxRoot(ctx), STAMP).writeText(BOOTSTRAP_VERSION)
            // 换包/重装后 termux-exec 可能恢复可用，清掉旧的不可用标记
            File(termuxRoot(ctx), LD_PRELOAD_BROKEN).delete()

            // 9) 真跑一次，把「文件在位但执行通道不通」如实报出来（含原因，不再叫用户 repair）
            val mode = resolveMode(ctx)
            val (code, out) = run(ctx, "true")
            if (code != 0) {
                lastExecError = out.take(1200)
                return "已解压，但执行通道不可用（$mode）：$out"
            }
            null
        } catch (t: Throwable) {
            Log.e(TAG, "bootstrap 安装失败", t)
            runCatching { staging.deleteRecursively() }
            t.message ?: t.javaClass.simpleName
        }
    }

    /** 官方 chmod 0700 的条目（可执行文件，不含 .so）：bin/、libexec、apt helper/methods。 */
    private fun isExecutableEntry(name: String): Boolean =
        name.startsWith("bin/") || name.startsWith("libexec") ||
            name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")

    /** 递归拷贝（rename 失败时的兜底）。软链按软链拷。 */
    private fun copyTree(from: File, to: File) {
        from.listFiles()?.forEach { f ->
            val dst = File(to, f.name)
            if (f.isDirectory) {
                dst.mkdirs()
                copyTree(f, dst)
            } else {
                runCatching { f.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } } }
                if (f.canExecute()) runCatching { dst.setExecutable(true, false) }
            }
        }
    }

    /**
     * 按 SYMLINKS.txt 建软链（与官方 [TermuxInstaller] 完全一致的解析方式）。
     *
     * 每行格式：`<链接目标>←<相对 PREFIX 的链接路径>`，例如
     * `libnettle.so.8.11←./lib/libnettle.so` → 创建 `lib/libnettle.so` 指向 `libnettle.so.8.11`。
     * 目标用**裸文件名/相对路径**（相对链接所在目录解析），与官方一致。
     *
     * @return 因目标不存在等原因失败的条数
     */
    private fun restoreSymlinks(root: File, txt: String?): Int {
        if (txt.isNullOrBlank()) return 0
        var failed = 0
        var made = 0
        for (raw in txt.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val sep = line.indexOf('←')
            if (sep <= 0) continue
            val target = line.substring(0, sep).trim()
            val linkRel = line.substring(sep + 1).trim().removePrefix("./")
            if (target.isEmpty() || linkRel.isEmpty() || linkRel.contains("..")) continue
            val linkFile = File(root, linkRel)
            linkFile.parentFile?.mkdirs()
            runCatching {
                // 目标可能是残留的悬空软链（exists() 对悬空链接返回 false），一律先删
                runCatching { linkFile.delete() }
                Os.symlink(target, linkFile.absolutePath)
                made++
            }.onFailure { failed++ }
        }
        Log.i(TAG, "软链还原：成功 $made，失败 $failed")
        return failed
    }

    /**
     * 递归给目录下所有 ELF 文件补可执行位。
     *
     * 按**文件内容**（ELF 魔数 `\x7fELF`）判定，比按路径白名单稳。
     * 注意：这里读前 4 字节用 `read(ByteArray)` 手写循环，**不能用 `readNBytes`** ——
     * 那是 Android 13（API 33）才有的方法，minSdk 26 机型上会 `NoSuchMethodError`（旧实现踩过）。
     */
    private fun ensureElfExecutable(root: File): Int {
        if (!root.isDirectory) return 0
        var count = 0
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            runCatching {
                if (isElf(f) && !f.canExecute()) {
                    f.setExecutable(true, false)
                    count++
                }
            }
        }
        return count
    }

    /** 读文件头 4 字节判 ELF（避开 readNBytes 的 API 33 限制）。 */
    private fun isElf(f: File): Boolean {
        val head = ByteArray(4)
        val read = runCatching {
            f.inputStream().use { ins ->
                var off = 0
                while (off < 4) {
                    val n = ins.read(head, off, 4 - off)
                    if (n <= 0) break
                    off += n
                }
                off
            }
        }.getOrDefault(0)
        return read == 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
    }

    /**
     * 把 bootstrap 文本文件里硬编码的官方前缀改写成真实安装路径（**字节级**替换）。
     *
     * 🔴 旧实现是 `bytes.toString(UTF_8).replace(...).writeText(...)`：包里大量
     * `share/`、`terminfo` 数据不是合法 UTF-8，解码再编码会把非法字节换成 U+FFFD，
     * **静默写坏文件**（换来的是 `less`/`nano`/`man` 一类工具莫名其妙的乱码报错）。
     * 字节级替换只动命中的那串 ASCII，其余字节原样保留。
     *
     * 二进制（含 NUL 字节）跳过：它们的库查找靠运行时 `LD_LIBRARY_PATH` 覆盖 `DT_RUNPATH`，
     * 且路径长度不同无法原地改写。
     *
     * @return 实际改写的文件数
     */
    private fun relocatePrefixBytes(root: File, oldPrefix: String, newPrefix: String): Int {
        if (oldPrefix == newPrefix) return 0
        val from = oldPrefix.toByteArray(Charsets.UTF_8)
        val to = newPrefix.toByteArray(Charsets.UTF_8)
        var count = 0
        root.walkTopDown().forEach { f ->
            if (!f.isFile || f.length() > RELOCATE_MAX_BYTES) return@forEach
            runCatching {
                val bytes = f.readBytes()
                if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return@runCatching // 二进制跳过
                val replaced = replaceAll(bytes, from, to) ?: return@runCatching // 未命中
                f.writeBytes(replaced)
                // 重写会丢掉执行位，按原样补回
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (isExecutableEntry(rel)) f.setExecutable(true, false)
                count++
            }
        }
        return count
    }

    /** 简单子串全替换；未命中返回 null。 */
    private fun replaceAll(src: ByteArray, from: ByteArray, to: ByteArray): ByteArray? {
        var first = indexOf(src, from, 0)
        if (first < 0) return null
        val out = ByteArrayOutputStream(src.size + to.size * 4)
        var cursor = 0
        while (first >= 0) {
            out.write(src, cursor, first - cursor)
            out.write(to)
            cursor = first + from.size
            first = indexOf(src, from, cursor)
        }
        out.write(src, cursor, src.size - cursor)
        return out.toByteArray()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, start: Int): Int {
        if (needle.isEmpty()) return -1
        val last = hay.size - needle.size
        var i = start
        while (i <= last) {
            if (hay[i] == needle[0]) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    /**
     * 一次性运行时修正：pkg/apt/dpkg 运行时目录 + APT 配置重定向 + LD_PRELOAD 变体。
     *
     * 🔴 apt/dpkg 的 `Dir::*`、`DPKG_ADMINDIR` 是**编译进二进制**的官方前缀路径。
     * 文本文件能重定位，二进制不能，所以只能靠运行时环境把它们的数据库/缓存/配置
     * 指到真实前缀 —— 这就是 `APT_CONFIG` + `DPKG_ADMINDIR` 的作用。
     *
     * 用标记文件保证只跑一次，同时覆盖「全新安装」与「已装旧 bootstrap、升级后需补」两种设备。
     *
     * @return null 表示成功；否则是失败原因
     */
    private fun applyRuntimeFixes(ctx: Context): String? {
        val root = termuxRoot(ctx)
        val stamp = File(root, RUNTIME_FIX_STAMP)
        if (stamp.isFile && stamp.readText().trim() == RUNTIME_FIX_VERSION) return null
        val usr = usrDir(ctx)
        if (!usr.isDirectory) return "bootstrap 未解压，无法应用运行时修正"

        // 1) 已装旧包的设备：补一次前缀重定位（幂等，已改过的文件里不再含官方前缀）
        val relocated = relocatePrefixBytes(usr, GUEST_PREFIX, usr.absolutePath)
        if (relocated > 0) Log.i(TAG, "运行时修正：补重定位 $relocated 个文本文件")

        // 2) pkg / apt / dpkg 运行时目录必须在真实前缀下就位，否则包管理扑空
        for (sub in listOf(
            "tmp",
            "var/lib/dpkg/info", "var/lib/dpkg/updates", "var/lib/dpkg/triggers",
            "var/lib/dpkg/alternatives",
            "var/cache/apt/archives/partial", "var/lib/apt/lists/partial",
            "etc/apt/trusted.gpg.d", "etc/apt/preferences.d", "etc/apt/sources.list.d",
        )) File(usr, sub).mkdirs()
        val dpkgStatus = File(usr, "var/lib/dpkg/status")
        if (!dpkgStatus.isFile) runCatching { dpkgStatus.writeText("") }

        // 3) APT 配置：把编译进二进制的官方 Dir::* 重定向到真实前缀。
        // 内容由 buildAptConf 生成（纯函数、可单测），这里只负责落盘。
        runCatching {
            File(usr, "etc/apt/apt-quro.conf").writeText(buildAptConf(usr.absolutePath))
        }

        // 3b) 官方 keyring 归位。
        // bootstrap 把公钥放在 share/termux-keyring/*.gpg，而 apt 只认
        // etc/apt/trusted.gpg.d/（官方 termux-keyring 包的 postinst 做的就是把它拷过去）。
        // 不拷 → `apt update` 必报 NO_PUBKEY，pkg 装任何包都失败。
        runCatching {
            val src = File(usr, "share/termux-keyring")
            val dst = File(usr, "etc/apt/trusted.gpg.d")
            if (src.isDirectory) {
                dst.mkdirs()
                src.listFiles { f -> f.isFile && f.name.endsWith(".gpg") }?.forEach { key ->
                    val target = File(dst, key.name)
                    if (!target.isFile || target.length() != key.length()) {
                        key.copyTo(target, overwrite = true)
                    }
                }
            }
        }

        // 3c) https method 缺失时降级到 http。
        // 这套 bootstrap 的 lib/apt/methods 里只有 http / file / copy / gpgv / rsh / store，
        // **没有 https**；而 mirrors/* 与 sources.list 全是 https:// → apt 必然失败。
        // 注意 `pkg` 会用 mirrors 里的 $MAIN 重写 sources.list（bin/pkg 第 346 行），
        // 所以两个地方都得改，只改 sources.list 会被 pkg 覆盖回去。
        // 传输走明文，但仓库签名（Release 的 GPG + Packages/deb 的 hash）仍然生效。
        runCatching {
            if (!File(usr, "lib/apt/methods/https").isFile) {
                val mirrors = File(usr, "etc/termux/mirrors")
                if (mirrors.isDirectory) {
                    mirrors.walkTopDown().filter { it.isFile }.forEach { m ->
                        val text = m.readText()
                        if (text.contains("https://")) m.writeText(downgradeHttpsToHttp(text))
                    }
                }
                val sources = File(usr, "etc/apt/sources.list")
                if (sources.isFile) {
                    val text = sources.readText()
                    if (text.contains("https://")) {
                        sources.writeText(
                            "# 本机 apt 未编译 https method，已降级 http（GPG 签名校验仍然生效）\n" +
                                downgradeHttpsToHttp(text),
                        )
                    }
                }
            }
        }

        // 3d) 🔴 apt 包装层：`APT_CONFIG` / `-c` 在真机上实测都读不到（apt-config dump 里
        //     Dir 仍是官方前缀），只有命令行 `-o` 一定生效。这里把 apt 一族改成
        //     「真身 *.real + 同名 shim」，所有调用方（pkg / apt-get / termux run / AI）一并覆盖。
        runCatching { installAptShims(usr) }
            .onSuccess { if (it > 0) Log.i(TAG, "apt 包装层：$it 个二进制改为 -o 强制重定向") }
            .onFailure { Log.w(TAG, "apt 包装层安装失败：${it.message}") }

        // 4) termux-env 文件（官方 TermuxShellEnvironment.writeEnvironmentToFile 的等价物）：
        //    少数脚本直接 source $PREFIX/etc/termux/termux.env，没有它就读不到 PREFIX/HOME
        runCatching {
            val envFile = File(usr, "etc/termux/termux.env")
            envFile.parentFile?.mkdirs()
            envFile.writeText(exportLines(ctx).entries.joinToString("\n") { (k, v) -> "export $k=\"$v\"" } + "\n")
        }

        // 5) 🔴 LD_PRELOAD 变体：等价官方 postinst 里的
        //    `termux-exec-ld-preload-lib setup`（它内部按 `termux-exec-system-linker-exec is-enabled` 选）
        installPreloadVariant(ctx, decideMode())?.let { Log.w(TAG, "LD_PRELOAD 变体选择失败：$it") }

        runCatching { File(usr, "var/log/apt").mkdirs() }
        runCatching { stamp.writeText(RUNTIME_FIX_VERSION) }
        return null
    }

    /**
     * 把对应变体装成主库 `lib/libtermux-exec-ld-preload.so`（官方脚本就是 `rm -f` + `cp -a`）。
     *
     * 🔴 必须先 `delete()` 再写新文件，不能原地覆盖：被覆盖的是**同一个 inode**，
     * 已经 mmap 了这个库的进程会直接 SIGSEGV（官方脚本注释原话）。
     */
    /**
     * 生成 APT 主配置内容（`APT_CONFIG` 指向的文件，官方 apt 读的是编译期写死的
     * `/data/data/com.termux/files/usr/etc/apt/apt.conf`，我们前缀不同，所以必须用
     * `APT_CONFIG` 显式指定，见 [exportLines]）。
     *
     * 🔴 抽成纯函数是为了能单测：**这段内容错一行，`pkg` 整条链路就失效**，
     * 而它无法在本机（x86 Windows）用 aarch64 的 apt 实跑验证 —— 只能靠断言钉住关键行。
     *
     * @param p Termux 前缀的真实绝对路径（`.../files/termux/usr`）
     */
    /**
     * apt 的目录覆盖表：**唯一数据源**，配置文件（[buildAptConf]）与命令行（[buildAptShim]）
     * 都从这里渲染，避免「文件里写了、命令行里漏了」两套配置各说各话。
     *
     * 🔴 为什么必须重定向：`Dir::*` 全在 `libapt-pkg.so` 里被编译成官方前缀
     * `/data/data/com.termux/files/usr/...`（`strings lib/libapt-pkg.so` 可见），
     * `lib/apt/methods/http` 里甚至直接嵌了 `/data/data/com.termux/files/usr/lib`。
     * 本应用的前缀是 `/data/user/0/<pkg>/files/termux/usr`，二者不同 → apt 到
     * 不存在的目录里找 method → "The method driver ... could not be found"。
     * 二进制路径改不了（新前缀更长，无法原地等长替换），只能靠配置/参数覆盖。
     */
    internal fun aptDirOverrides(p: String): List<Pair<String, String>> = listOf(
        "Dir" to "$p/",
        "Dir::State" to "$p/var/lib/apt",
        "Dir::State::status" to "$p/var/lib/dpkg/status",
        "Dir::State::lists" to "$p/var/lib/apt/lists",
        "Dir::Cache" to "$p/var/cache/apt",
        "Dir::Cache::archives" to "$p/var/cache/apt/archives",
        "Dir::Etc" to "$p/etc/apt",
        "Dir::Etc::sourcelist" to "$p/etc/apt/sources.list",
        "Dir::Etc::sourceparts" to "$p/etc/apt/sources.list.d",
        "Dir::Etc::preferences" to "$p/etc/apt/preferences",
        "Dir::Etc::preferencesparts" to "$p/etc/apt/preferences.d",
        "Dir::Etc::trusted" to "$p/etc/apt/trusted.gpg",
        "Dir::Etc::trustedparts" to "$p/etc/apt/trusted.gpg.d",
        "Dir::Log" to "$p/var/log/apt",
        "Dir::Bin::dpkg" to "$p/bin/dpkg",
        "Dir::Bin::methods" to "$p/lib/apt/methods",
        "Dir::Bin::solvers" to "$p/lib/apt/solvers",
        "Dir::Bin::apt-key" to "$p/bin/apt-key",
        "Dir::Bin::gpg" to "$p/bin/gpg",
        "Dir::Bin::gpgv" to "$p/lib/apt/methods/gpgv",
        "DPkg::Path" to "$p/bin",
        // Android 上进程 uid 既不是 0、也不存在 `_apt` 用户；不放开降权用户，method 子进程起不来。
        "APT::Sandbox::User" to "root",
        // apt 默认会重置 method 子进程的环境，那样 `LD_LIBRARY_PATH` 就丢了，
        // 而 method 的 RUNPATH 硬编码在官方前缀 → 找不到 libapt-pkg.so 起不来。必须保留。
        "APT::Sandbox::ResetEnvironment" to "false",
        // 仓库 Release 的 Valid-Until 过期很常见（Termux stable 通道）。
        // 只关这一项；**签名校验保持开启**，不放开 unauthenticated。
        "Acquire::Check-Valid-Until" to "false",
    )

    /** 渲染成 apt.conf 语法（`Key "value";`）。 */
    internal fun buildAptConf(p: String): String = buildString {
        for ((k, v) in aptDirOverrides(p)) appendLine("$k \"$v\";")
    }

    /** 包装脚本里标识「这是 App 生成的 shim」的固定串，用来做幂等判断。 */
    internal const val APT_SHIM_MARK = "# ZorvAI-apt-shim v1"

    /** 允许被包装的 apt 一族可执行文件（真 ELF，改名 *.real 后由 shim 代理）。 */
    internal val APT_SHIM_BINS = listOf("apt", "apt-get", "apt-cache", "apt-mark", "apt-config", "apt-key")

    /**
     * 生成 apt 包装脚本（纯函数，单测钉死）：
     * 真身改名 `*.real`，同名脚本用 `-o` 把每个 `Dir::*` 重新指一遍再 exec。
     *
     * 🔴 为什么不靠 `APT_CONFIG` 或 `-c`：用户真机实测两者都无效
     * （`apt-config dump` 里 `Dir` 仍是 `/data/data/com.termux/files/usr/...`）。
     * 而 `-o` 是 apt 解析命令行时**直接写进 Configuration** 的，优先级高于一切配置文件，
     * 且完全不依赖「文件能不能被读到」——这是唯一 100% 生效的通道（apt 自身也靠它）。
     */
    internal fun buildAptShim(p: String, realName: String): String {
        val d = "$" // shell 变量前缀；Kotlin 模板里必须显式构造，否则 $P 会被当 Kotlin 变量
        return buildString {
            appendLine("#!/$p/bin/bash")
            appendLine(APT_SHIM_MARK)
            appendLine("# apt 的 Dir 是编译期写死在 libapt-pkg.so 里的官方前缀，用 -o 强制重定向。")
            appendLine("# 想改配置请改 TermuxEnvironment.aptDirOverrides，不要改这个文件（会被覆盖）。")
            appendLine("P=\"$p\"")
            appendLine("export APT_CONFIG=\"${d}P/etc/apt/apt-quro.conf\"")
            appendLine("export LD_LIBRARY_PATH=\"${d}P/lib${d}{LD_LIBRARY_PATH:+:${d}LD_LIBRARY_PATH}\"")
            appendLine("exec \"${d}P/bin/$realName\" \\")
            for ((k, v) in aptDirOverrides(p)) {
                appendLine("  -o \"$k=$v\" \\")
            }
            append("  \"${d}@\"\n")
        }
    }

    /**
     * 给 apt 一族套包装层（幂等）。返回本次改写的文件数。
     *
     * 为什么必须是文件系统层：`pkg`、`apt-get`、用户手敲、AI 的 `termux run` 全都走
     * `$PREFIX/bin/apt`，套在这里一次覆盖所有调用方，不用挨个改脚本。
     */
    private fun installAptShims(usr: File): Int {
        val p = usr.absolutePath
        var n = 0
        for (name in APT_SHIM_BINS) {
            val bin = File(usr, "bin/$name")
            if (!bin.isFile) continue // apt-key 在 apt 3.x 已移除，缺了不算错
            val real = File(usr, "bin/$name.real")
            val head = runCatching {
                val buf = ByteArray(minOf(bin.length().toInt().coerceAtLeast(0), 4096))
                bin.inputStream().use { it.read(buf) }
                String(buf, Charsets.ISO_8859_1)
            }.getOrDefault("")
            val alreadyShim = head.contains(APT_SHIM_MARK)
            if (!alreadyShim) {
                // 真身是 ELF：先改名保留。上次半途失败时 .real 已在，直接沿用。
                if (!real.isFile && !bin.renameTo(real)) {
                    Log.w(TAG, "apt 包装层：$name 改名失败，跳过")
                    continue
                }
                // rename 成功时 bin 已不存在，直接写；.real 已在时 bin 也已被上一轮改走
            }
            if (!real.isFile) {
                Log.w(TAG, "apt 包装层：$name.real 不存在，跳过（避免做出一个死链脚本）")
                continue
            }
            runCatching {
                bin.writeText(buildAptShim(p, "$name.real"))
                bin.setExecutable(true, false)
                n++
            }.onFailure { Log.w(TAG, "apt 包装层：写 $name 失败（${it.message}）") }
        }
        return n
    }

    /**
     * 把镜像清单 / sources.list 里的 `https://` 换成 `http://`。
     *
     * 只在本机**没有** `lib/apt/methods/https` 时调用（这套 bootstrap 就没有）。
     * 明文只影响传输，仓库签名（Release 的 GPG + Packages/deb 的 hash）仍然生效。
     */
    internal fun downgradeHttpsToHttp(text: String): String = text.replace("https://", "http://")

    private fun installPreloadVariant(ctx: Context, mode: TermuxExec.Mode): String? {
        val usr = usrDir(ctx)
        val libDir = File(usr, "lib")
        val src = File(libDir, TermuxExec.primaryPreload(mode))
        if (!src.isFile) return "缺少 ${src.name}（bootstrap 可能不完整）"
        val target = File(libDir, "libtermux-exec-ld-preload.so")
        val marker = File(termuxRoot(ctx), ".preload.variant")
        if (marker.isFile && marker.readText().trim() == mode.id && target.isFile) return null
        return runCatching {
            target.delete()
            src.copyTo(target, overwrite = true)
            marker.writeText(mode.id)
            Log.i(TAG, "LD_PRELOAD 主库 = ${src.name}（mode=${mode.id}）")
            null
        }.getOrElse { "安装 ${src.name} 失败：${it.message}" }
    }

    /**
     * Termux「我是谁」的完整环境变量表（两代命名都在，官方 v0.119 前后脚本各读各的）。
     *
     * 参照官方 `TermuxShellEnvironment.getEnvironment()` + `bin/login` 的导出逻辑：
     *  - `PREFIX` / `HOME`：老名字，Termux 最核心判据；
     *  - `TERMUX__PREFIX` / `TERMUX__HOME` / `TERMUX__ROOTFS` / `TERMUX__VAR` / `TERMUX__USER_ID`：
     *    v0.119 新名字，`termux-tools` 脚本与 **termux-exec 重写 shebang 时读的就是它**；
     *  - `TERMUX_APP__PACKAGE_NAME` / `TERMUX_APP__DATA_DIR` / `TERMUX_APP_PACKAGE` /
     *    `TERMUX_APP_PACKAGE_MANAGER`：包名与包管理器判据（`pkg` 用来决定走 apt 还是 pacman），
     *    其中 `TERMUX_APP__DATA_DIR` 还是 termux-exec 判定「这个路径要不要走 linker64」的基准。
     */
    private fun exportLines(ctx: Context): LinkedHashMap<String, String> {
        val usr = usrDir(ctx)
        val home = homeDir(ctx)
        val root = termuxRoot(ctx)
        val p = usr.absolutePath
        val map = LinkedHashMap<String, String>()
        map["HOME"] = home.absolutePath
        map["PREFIX"] = p
        map["TERMUX__PREFIX"] = p
        map["TERMUX__HOME"] = home.absolutePath
        map["TERMUX__ROOTFS"] = root.absolutePath
        map["TERMUX__VAR"] = "$p/var"
        map["TERMUX__USER_ID"] = "0"
        map["TERMUX_VERSION"] = BOOTSTRAP_VERSION
        map["TERMUX_APP_PACKAGE"] = ctx.packageName
        map["TERMUX_APP__PACKAGE_NAME"] = ctx.packageName
        map["TERMUX_APP__DATA_DIR"] = dataDirOf(ctx)
        map["TERMUX_APP__LEGACY_DATA_DIR"] = "/data/data/${ctx.packageName}"
        map["TERMUX_APP__FILES_DIR"] = "${dataDirOf(ctx)}/files"
        map["TERMUX_APP_PACKAGE_MANAGER"] = "apt"
        map["TERMUX_MAIN_PACKAGE_FORMAT"] = "debian"
        map["TERMUX_IS_DEBUGGABLE_BUILD"] = "0"
        map["SHELL"] = "$p/bin/bash"
        map["PATH"] = "$p/bin:$p/bin/applets:/system/bin:/system/xbin"
        // 官方 Android 7+ 靠 DT_RUNPATH 找库；我们的前缀与 RUNPATH 里的官方路径不同，
        // 必须用 LD_LIBRARY_PATH 覆盖（bionic 对非 setuid 进程仍然认它）
        map["LD_LIBRARY_PATH"] = "$p/lib"
        map["TMPDIR"] = "$p/tmp"
        map["LANG"] = "en_US.UTF-8"
        // 不设 LC_ALL / TZ：bionic 只认 C / C.UTF-8 / en_US.UTF-8 这几种写法，
        // 强行设 LC_ALL 会让部分工具吐 "cannot change locale"；TZ 交给系统，`date` 才对得上手机时区
        map["TERM"] = "xterm-256color"
        map["COLORTERM"] = "truecolor"
        map["ANDROID_DATA"] = "/data"
        map["ANDROID_ROOT"] = "/system"
        map["SSL_CERT_FILE"] = "$p/etc/tls/cert.pem"
        map["SSL_CERT_DIR"] = "$p/etc/tls"
        map["DPKG_ADMINDIR"] = "$p/var/lib/dpkg"
        val aptConf = File(usr, "etc/apt/apt-quro.conf")
        if (aptConf.isFile) map["APT_CONFIG"] = aptConf.absolutePath
        seProcessContext()?.let { map["TERMUX__SE_PROCESS_CONTEXT"] = it }
        return map
    }

    /**
     * 应用数据目录：termux-exec 用它判定「这个路径是不是 app_data_file」。
     *
     * 必须与真实前缀同源（有些 ROM 的 `applicationInfo.dataDir` 是 `/data/data/...`，
     * 而 `filesDir` 走 `/data/user/0/...`）—— 两边字符串不一致，termux-exec 就不会启用
     * system_linker_exec，子进程又变回 EACCES。
     */
    private fun dataDirOf(ctx: Context): String {
        val usr = usrDir(ctx).absolutePath
        val declared = runCatching { ctx.applicationInfo.dataDir }.getOrNull()
        if (!declared.isNullOrBlank() && usr.startsWith("$declared/")) return declared
        val cut = usr.substringBeforeLast("/termux/", "")
        return cut.ifBlank { declared ?: "/data/data/${ctx.packageName}" }
    }

    /**
     * 当前进程的 SELinux 域（部分 ROM 读不了，返回 null，官方同样视为「豁免」）。
     *
     * 必须过 [TermuxExec.sanitizeSeContext]：该 proc 文件在部分内核上返回**定长 NUL 填充**，
     * 直接把带 `\0` 的字符串塞进环境变量，Android 的 `ProcessEnvironment#validateValue()`
     * 会抛 `Invalid environment variable value`，整条命令一次都起不来（本机就是这么挂的）。
     */
    fun seProcessContext(): String? = runCatching {
        TermuxExec.sanitizeSeContext(File("/proc/self/attr/current").readText())
    }.getOrNull()

    /** 按官方规则给出的「应该走哪条通道」（不含实测）。 */
    private fun decideMode(): TermuxExec.Mode =
        TermuxExec.decideMode(Build.VERSION.SDK_INT, Process.myUid(), seProcessContext())

    // ───────────────────────── 执行通道 ─────────────────────────

    /** 探测/实测出来的通道（进程内缓存，落盘在 [MODE_STAMP]）。 */
    @Volatile
    private var modeCache: TermuxExec.Mode? = null

    /** 最近一次「通道级」失败原因，供 status / 归因使用。 */
    @Volatile
    var lastExecError: String? = null
        private set

    /**
     * 实测哪条通道真能跑：先按官方规则猜，再真 exec 一次 `true` 验证；猜错就换另一条并记住。
     *
     * 为什么不能只按规则硬判：个别 ROM/魔改内核的 SE 域字符串与 AOSP 不一致（例如厂商自定义域），
     * 规则会判错。真跑一次成本只有一次 fork/exec，比「判错后整条通道静默 EACCES」划算得多。
     */
    fun resolveMode(ctx: Context): TermuxExec.Mode {
        modeCache?.let { return it }
        // 落盘的 .execmode **只当「优先尝试顺序」，不当权威**：
        // 旧版本可能在脏环境下写下错误通道（例如环境变量非法导致的失败），
        // 无条件信任它会让修好后的版本继续走死路。
        val saved = File(termuxRoot(ctx), MODE_STAMP).takeIf { it.isFile }?.let { f ->
            runCatching { TermuxExec.Mode.of(f.readText().trim()) }.getOrNull()
        }
        val guess = saved ?: decideMode()
        val other = if (guess == TermuxExec.Mode.SYSTEM_LINKER) {
            TermuxExec.Mode.DIRECT
        } else {
            TermuxExec.Mode.SYSTEM_LINKER
        }
        for (m in listOf(guess, other)) {
            val (code, out) = attempt(ctx, m, "true", 20_000)
            if (code == 0) {
                modeCache = m
                runCatching { File(termuxRoot(ctx), MODE_STAMP).writeText(m.id) }
                if (saved != null && m != saved) {
                    Log.w(TAG, "丢弃落盘通道标记（$saved → 实测 $m）")
                } else if (saved == null && m != decideMode()) {
                    Log.w(TAG, "执行通道与政策判定不一致：政策=${decideMode()} 实测=$m（已采用实测）")
                }
                return m
            }
            Log.w(TAG, "通道探测失败[$m]：${out.take(300)}")
        }
        modeCache = guess
        return guess
    }

    /** 通道 = 策略 + 预加载变体。两个变体都存在时都会试，最后再试一把不带预加载。 */
    private fun preloadChain(ctx: Context, mode: TermuxExec.Mode): List<File?> {
        if (File(termuxRoot(ctx), LD_PRELOAD_BROKEN).isFile) return listOf(null)
        val lib = File(usrDir(ctx), "lib")
        val list = mutableListOf<File?>()
        for (name in listOf(
            TermuxExec.primaryPreload(mode),
            TermuxExec.fallbackPreload(mode),
            "libtermux-exec-ld-preload.so",
        )) {
            val f = File(lib, name)
            if (f.isFile && list.none { it?.absolutePath == f.absolutePath }) list.add(f)
        }
        list.add(null) // 最后兜底：不带预加载（builtin 与 linker 通道仍可用，子命令会受影响）
        return list
    }

    private fun attempt(
        ctx: Context,
        mode: TermuxExec.Mode,
        command: String,
        timeoutMs: Long,
        execLogLevel: Int = 0,
    ): Pair<Int, String> {
        var last: Pair<Int, String> = -1 to "无可用执行通道"
        var failedPreloads = 0
        for (preload in preloadChain(ctx, mode)) {
            val r = execOnce(ctx, mode, preload, command, timeoutMs, execLogLevel)
            if (!TermuxExec.isLaunchFailure(r.second)) {
                // 预加载库全都加载不起来、唯独不带预加载能跑：只有 direct 通道可以这样退化
                // （system_linker_exec 通道没有预加载就没法改写子进程 exec，退化等于半残，不落标记）
                if (preload == null && failedPreloads > 0 && mode == TermuxExec.Mode.DIRECT) {
                    Log.w(TAG, "termux-exec 预加载不可用（$failedPreloads 次尝试），后续改用无预加载模式")
                    runCatching { File(termuxRoot(ctx), LD_PRELOAD_BROKEN).writeText("1") }
                }
                return r
            }
            failedPreloads++
            last = r
            Log.w(TAG, "启动失败（mode=${mode.id}, preload=${preload?.name ?: "none"}）：${r.second.take(300)}")
        }
        return last
    }

    private fun execOnce(
        ctx: Context,
        mode: TermuxExec.Mode,
        preload: File?,
        command: String,
        timeoutMs: Long,
        execLogLevel: Int,
    ): Pair<Int, String> {
        val usr = usrDir(ctx)
        val bash = File(usr, "bin/bash")
        if (!bash.isFile) return -1 to "bash 缺失（${bash.absolutePath}）"

        val env = exportLines(ctx)
        // 🔴 system_linker_exec 的开关（官方 termux-exec 2.5.0）：
        //  - MODE=enable：按官方规则「需要时才用」，本机规则判定为需要（见 TermuxExec）
        //  - PROC_SELF_EXE：走 linker 后 /proc/self/exe 会显示 linker64，官方用它补偿
        //  - `TERMUX_EXEC__EXECVE_CALL__INTERCEPT` **故意不设**：官方默认值就是启用（头文件
        //    `ENV_DEF_VAL__...INTERCEPT = 1`），而它的取值是 `disable`/`enable` 字符串，
        //    设错反而会把拦截关掉 —— 关掉就等于所有子命令又回到 EACCES。
        env["TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"] = "enable"
        env["TERMUX_EXEC__PROC_SELF_EXE"] = bash.absolutePath
        // 日志默认关掉：它写 stderr，会混进命令输出被 AI 当结果读；排障时按需打开
        env["TERMUX_EXEC__LOG_LEVEL"] = execLogLevel.toString()
        if (preload != null) env["LD_PRELOAD"] = preload.absolutePath

        val mode0 = TermuxExec.launchArgv(
            mode = mode,
            linker = TermuxExec.linkerPath(Process.is64Bit()),
            executable = bash.absolutePath,
            command = wrapWithPidFile(ctx, command),
        )
        return exec(ctx, mode0, env, timeoutMs)
    }

    /** 命令前面塞一句 `echo $$ > pidfile`：超时要按 pid 递归杀树（Android 拿不到子进程 pid）。 */
    private fun wrapWithPidFile(ctx: Context, command: String): String {
        val pidFile = File(usrDir(ctx), "tmp/quro_run.pid")
        runCatching { pidFile.parentFile?.mkdirs(); pidFile.delete() }
        return "echo \$\$ > '${pidFile.absolutePath}'; $command"
    }

    /**
     * 在 Termux 环境里跑一条命令。
     *
     * 执行方式：`bash -lc "<command>"`（走 [resolveMode] 定下的通道），环境变量按 [exportLines] 给全，
     * 并 `LD_PRELOAD` termux-exec（见类注释第 5 点）。
     *
     * 为什么不直接 exec 官方 `bin/login`：`login` 会做 motd、`$SHELL` 探测、
     * `$LD_PRELOAD` 自检等一堆**面向交互式 tty** 的事，非交互场景只会把噪声塞进
     * 工具返回值里，AI 读到的输出会被污染。这里用等价环境 + 直接 bash，
     * 语义上与 `login -c` 一致而输出干净。
     *
     * @return (exitCode, 合并后的输出)
     */
    fun run(ctx: Context, command: String, timeoutMs: Long = 120_000): Pair<Int, String> {
        if (!isReady(ctx)) return -1 to "Termux 环境未就绪（先执行安装）"

        applyRuntimeFixes(ctx)?.let { return -1 to "Termux 运行时修正失败：$it" }

        val primary = resolveMode(ctx)
        val first = attempt(ctx, primary, command, timeoutMs)
        if (!TermuxExec.isLaunchFailure(first.second)) return first

        // 通道级失败才换另一条重试；命令自己报错（exit!=0）不动，避免副作用命令跑两遍
        val other = if (primary == TermuxExec.Mode.DIRECT) {
            TermuxExec.Mode.SYSTEM_LINKER
        } else {
            TermuxExec.Mode.DIRECT
        }
        val second = attempt(ctx, other, command, timeoutMs)
        if (!TermuxExec.isLaunchFailure(second.second)) {
            modeCache = other
            runCatching { File(termuxRoot(ctx), MODE_STAMP).writeText(other.id) }
            return second
        }
        lastExecError = (first.second + "\n" + second.second).takeLast(1200)
        return first
    }

    /**
     * 真正拉起子进程并收集输出。
     *
     * 超时必须**连子进程一起杀**：`bash` 只是壳，`pkg install` / `apt` 才是干活的那个，
     * 只 `destroy` bash 会留下孤儿进程继续占着 dpkg 锁，后续所有安装都报
     * 「dpkg was interrupted」。
     *
     * Android 的 `java.lang.Process` **没有** `pid()` / `toHandle()`（那是 OpenJDK 的 API），
     * 所以拿不到子进程 pid。这里用一个小技巧：命令前面加一句 `echo $$ > <pidfile>`，
     * 让 bash 自己把 pid 写进文件，超时就照这个 pid 递归杀树（读 /proc 下每个 task 的 children）。
     */
    private fun exec(
        ctx: Context,
        argv: List<String>,
        env: Map<String, String>,
        timeoutMs: Long,
    ): Pair<Int, String> {
        val pidFile = File(usrDir(ctx), "tmp/quro_run.pid")
        val pb = ProcessBuilder(argv)
        pb.directory(homeDir(ctx))
        pb.redirectErrorStream(true)
        // 🔴 逐个 put + 逐个净化，绝不 putAll：
        // `ProcessBuilder.environment()` 只要有一个值含 NUL 就抛 IllegalArgumentException，
        // 且是**整批失败**（命令一次都起不来）。一个脏变量不该让整条通道报废。
        val pbEnv = pb.environment()
        val dropped = ArrayList<String>(2)
        for ((k, v) in env) {
            val clean = TermuxExec.sanitizeEnvValue(v)
            if (clean == null) {
                dropped.add("$k=空值")
                continue
            }
            if (clean != v) dropped.add(k)
            runCatching { pbEnv[k] = clean }.onFailure { dropped.add("$k=非法") }
        }
        if (dropped.isNotEmpty()) Log.w(TAG, "环境变量已净化或丢弃：${dropped.joinToString()}")
        return try {
            val p = pb.start()
            val out = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val done = AtomicBoolean(false)
            Thread {
                runCatching {
                    reader.forEachLine { line -> synchronized(out) { out.appendLine(line) } }
                }
                done.set(true)
            }.apply { isDaemon = true; start() }

            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                // 连子进程一起杀：杀不干净会留下正在跑 dpkg 的孤儿进程，之后所有 pkg 安装
                // 都报「dpkg was interrupted」。
                val shellPid = runCatching { pidFile.readText().trim().toLongOrNull() }.getOrNull()
                if (shellPid != null) runCatching { killTree(shellPid) }
                runCatching { p.destroyForcibly() }
                runCatching { pidFile.delete() }
                return -1 to (synchronized(out) { out.toString() } + "\n[超时 ${timeoutMs}ms，已终止（含子进程）]")
            }
            val deadline = System.currentTimeMillis() + 2000
            while (!done.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            p.exitValue() to synchronized(out) { out.toString() }
        } catch (t: Throwable) {
            Log.e(TAG, "termux exec 失败（${argv.firstOrNull()}）", t)
            -1 to "执行失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * 递归杀掉 [rootPid] 及其所有子进程。
     *
     * Android 不提供 `java.lang.ProcessHandle`（那是 OpenJDK 的类），所以走 `/proc`：
     * `task/<tid>/children` 给出该线程的直接子进程 pid，广度递归下去即可。
     * 只对本进程的子孙（同 uid）发信号，`sendSignal` 对别的 uid 会被内核拒绝，安全。
     */
    private fun killTree(rootPid: Long) {
        val seen = HashSet<Long>()
        val stack = ArrayDeque<Long>()
        stack.addLast(rootPid)
        while (stack.isNotEmpty()) {
            val pid = stack.removeLast()
            if (pid <= 0L || !seen.add(pid)) continue
            childPids(pid).forEach { stack.addLast(it) }
            runCatching { Process.sendSignal(pid.toInt(), Process.SIGNAL_KILL) }
        }
    }

    private fun childPids(pid: Long): List<Long> = runCatching {
        File("/proc/$pid/task").listFiles()?.flatMap { tid ->
            runCatching {
                val f = File(tid, "children")
                if (!f.isFile) emptyList() else {
                    f.readText().trim().split(' ').mapNotNull { it.trim().toLongOrNull() }
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 探针：真跑一次 `true`，返回 0 才算「能执行」。
     *
     * 这是唯一的就绪判据 —— `bash.canExecute()` 只看权限位，`access(X_OK)` 也不反映
     * SELinux 对 `app_data_file` 的 exec 禁令，所以旧实现会「看着就绪、一跑 EACCES」。
     */
    fun probe(ctx: Context): Boolean {
        if (!isReady(ctx)) return false
        return runCatching { run(ctx, "true").first == 0 }.getOrDefault(false)
    }

    /** = [isReady] 且探针通过（能真正执行）。用于判定要不要重建。 */
    fun isActuallyReady(ctx: Context): Boolean = isReady(ctx) && probe(ctx)

    /**
     * 通道不通时的**归因**（给 status / 工具返回值用），不做任何修复动作。
     *
     * 关键点：把「结构性限制」与「文件损坏」分开说 —— 前者 repair 无效，
     * 旧实现把两者混为一谈，于是 repair 无限循环还永远修不好。
     */
    fun diagnose(ctx: Context): String = buildString {
        val filesReady = isReady(ctx)
        appendLine("文件层：${if (filesReady) "✅ bootstrap 已解压（${usrDir(ctx).absolutePath}）" else "❌ 未解压/不完整"}")
        if (!filesReady) {
            appendLine("→ 调 termux(repair) 或重跑 run（会自动安装）。")
            return@buildString
        }
        val decided = decideMode()
        val actual = resolveMode(ctx)
        appendLine("SELinux 域：${seProcessContext() ?: "读不到（视为豁免）"}；uid=${Process.myUid()}；SDK=${Build.VERSION.SDK_INT}")
        appendLine("执行通道：${actual.id}${if (actual != decided) "（政策判定是 ${decided.id}，实测覆盖）" else ""}")
        appendLine("归因：${TermuxExec.reason(actual, Build.VERSION.SDK_INT, seProcessContext())}")
        appendLine("termux-exec 主库：${TermuxExec.primaryPreload(actual)}（该通道官方要求的变体）")
        // 打开 termux-exec 自己的日志再探一次：它会把「为什么不走 linker / 哪个库没加载上」打到 stderr
        val (code, out) = attempt(ctx, actual, "true", 20_000, execLogLevel = 1)
        if (code == 0) {
            appendLine("探针：✅ bash 可执行")
        } else {
            appendLine("探针：❌ 失败（exit=$code）")
            appendLine((lastExecError ?: out).lineSequence().take(6).joinToString("\n") { "    $it" })
            appendLine("说明：这条是结构性限制或 bootstrap 不完整，**repair 不会改善**；")
            appendLine("     若确属包缺失/CANNOT LINK，可显式调 termux(repair) 重解压一次。")
        }
    }

    /**
     * 交互式 shell 的启动参数（PTY 与管道两种模式都适用）。
     *
     * 返回 `cmd` / `argv` / `envp`：
     *  - `cmd`：交给 `execvp(2)` 的第一个参数；
     *  - `argv`：**完整**数组，含 `argv[0]`。Termux 的 `createSubprocess` 不会替你补
     *    `argv[0]`（它把 Java 传来的 args 原样当 argv），所以调用方必须给全；
     *  - `envp`：`K=V` 形式的数组。
     *
     * 🔴 为什么 `argv` 的形态按通道不同：
     * `system_linker_exec` 通道下真正被 exec 的是 `linker64`，而 linker 规定
     * **`argv[1]` 才是要执行的 ELF**，所以 argv 必须是 `[linker64, bash, -l]`；
     * direct 通道被 exec 的就是 bash 自己，argv 是 `[bash, -l]`。
     * 调用方（PTY / 管道）对 `argv[0]` 的处理不同，见 [ShellLaunch] 注释。
     *
     * 这条路是「界面终端默认进 Termux」的基础：以前界面只能进 proot Ubuntu，
     * 是因为没人把 linker64 前缀接到 PTY 的 execvp 上。
     */
    fun shellLaunch(ctx: Context): ShellLaunch? {
        if (!isReady(ctx)) return null
        val bash = File(usrDir(ctx), "bin/bash")
        if (!bash.isFile) return null
        val mode = resolveMode(ctx)
        val env = exportLines(ctx).toMutableMap()
        // 与一次性通道同源的三件套 + 环境标识（zorv 切换命令靠它判断自己在哪一侧）
        env["TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"] = "enable"
        env["TERMUX_EXEC__PROC_SELF_EXE"] = bash.absolutePath
        env["TERMUX_EXEC__LOG_LEVEL"] = "0"
        env["ZORV_ENV"] = ZorvSwitch.ENV_TERMUX
        preloadChain(ctx, mode).firstOrNull()?.let { env["LD_PRELOAD"] = it.absolutePath }
        val exe = when (mode) {
            TermuxExec.Mode.DIRECT -> bash.absolutePath
            TermuxExec.Mode.SYSTEM_LINKER -> TermuxExec.linkerPath(Process.is64Bit())
        }
        // argv[0] 恒为「真正被 exec 的那个程序」自己；linker 通道下 argv[1] 才是 bash。
        val argv = if (mode == TermuxExec.Mode.SYSTEM_LINKER) {
            listOf(exe, bash.absolutePath, "-l")
        } else {
            listOf(exe, "-l")
        }
        return ShellLaunch(exe, argv, env.map { (k, v) -> "$k=$v" }.toTypedArray(), mode)
    }

    /**
     * 交互式启动参数。
     *
     * [argv] **始终包含 `argv[0]`**。两种调用方式各自取用：
     *
     *  - PTY（`QuroTerminalJNI.createSubprocess`）：它把 Java 传来的 args 原样当 argv，
     *    因此调用方要传 `[cmd] + argv`（第一项既当 `cmd` 又当 `argv[0]`）；
     *  - 管道（`ProcessBuilder`）：它把列表第一项当程序、并自动把 `argv[0]` 设成它，
     *    因此调用方要传 `[cmd] + argv.drop(1)`。
     */
    data class ShellLaunch(
        val cmd: String,
        val argv: List<String>,
        val envp: Array<String>,
        val mode: TermuxExec.Mode,
    )

    /** 给 UI/AI 诊断用的环境摘要（AI 问「你是什么环境」时能报出真实值）。 */
    fun describe(ctx: Context): String = buildString {
        val filesReady = isReady(ctx)
        val canExec = if (filesReady) probe(ctx) else false
        appendLine(
            "Termux 环境：${
                when {
                    canExec -> "✅ 已就绪（bootstrap 完整，执行通道实测通过）"
                    filesReady -> "⚠️ 文件已解压但执行通道不通（不是权限位问题，repair 无效；见下方归因）"
                    else -> "❌ 未安装（bootstrap 未解压或不完整）"
                }
            }",
        )
        appendLine("PREFIX=${usrDir(ctx).absolutePath}（官方语义前缀=$GUEST_PREFIX）")
        appendLine("HOME=${homeDir(ctx).absolutePath}（官方语义前缀=$GUEST_HOME）")
        appendLine("TERMUX_VERSION=$BOOTSTRAP_VERSION  TERMUX__PREFIX=${usrDir(ctx).absolutePath}")
        appendLine("包管理器=pkg（apt 后端，TERMUX_APP_PACKAGE_MANAGER=apt）")
        val mode = if (filesReady) resolveMode(ctx) else decideMode()
        appendLine(
            "执行通道=${mode.id}" +
                if (mode == TermuxExec.Mode.SYSTEM_LINKER) "（${TermuxExec.linkerPath(Process.is64Bit())}）"
                else "（原生 execve）",
        )
        appendLine("  归因：${TermuxExec.reason(mode, Build.VERSION.SDK_INT, seProcessContext())}")
        appendLine(
            "termux-exec=" + when {
                File(termuxRoot(ctx), LD_PRELOAD_BROKEN).isFile -> "已禁用（本机不兼容，已退回无预加载模式）"
                File(usrDir(ctx), "lib/libtermux-exec-ld-preload.so").isFile ->
                    "已启用（LD_PRELOAD=libtermux-exec-ld-preload.so，主库=${TermuxExec.primaryPreload(mode)}）"
                else -> "库缺失（pkg 新装的包可能因 shebang 前缀不符跑不起来，建议 repair）"
            },
        )
        appendLine(
            "执行方式=" + when (mode) {
                TermuxExec.Mode.SYSTEM_LINKER ->
                    "${TermuxExec.linkerPath(Process.is64Bit())} 加载私有目录 ELF（官方 system_linker_exec）"
                TermuxExec.Mode.DIRECT -> "原生 exec Bionic bash（PT_INTERP=/system/bin/linker64），不走 proot"
            },
        )
        appendLine("体检：" + health(ctx))
    }

    /**
     * 环境体检：`pkg` / `apt` 是否就位、脚本 shebang 的前缀是否已重写。
     *
     * 「pkg: command not found」有两类完全不同的成因，用这一行就能分开：
     *  - `pkg=❌缺失` → 解压不全，该 repair；
     *  - `pkg=✅` 但仍 not found → PATH 没指到这个前缀（另一条终端链路用的是别的前缀），
     *    这种 repair 一万次也没用，得改那条链路。
     *
     * `未重写前缀残留` 同理：bootstrap 里所有脚本的 shebang 都写着官方
     * `/data/data/com.termux/files/usr`，解压后必须重写成本机前缀，残留不为 0 就说明
     * 有一批脚本的解释器路径是坏的（表现正是「command not found」或 bad interpreter）。
     */
    fun health(ctx: Context): String {
        val usr = usrDir(ctx)
        val bin = File(usr, "bin")
        if (!bin.isDirectory) return "❌ ${bin.absolutePath} 不存在（未解压）"
        val pkg = File(bin, "pkg")
        val apt = File(bin, "apt")
        val stray = countStrayPrefix(usr)
        return buildString {
            append("pkg=").append(if (pkg.isFile) "✅" else "❌缺失")
            append(" apt=").append(if (apt.isFile) "✅" else "❌缺失")
            append(" bash=").append(if (File(bin, "bash").isFile) "✅" else "❌缺失")
            append(" 未重写前缀残留=").append(stray).append("个")
            if (stray > 0) append("（这些脚本的解释器路径是坏的，需 repair 重解压）")
        }
    }

    /** 数一数 `bin/` 下还有多少文本脚本残留官方前缀（只读每个文件开头 256 字节，很便宜）。 */
    private fun countStrayPrefix(usr: File): Int {
        val marker = GUEST_PREFIX.toByteArray(Charsets.UTF_8)
        var n = 0
        File(usr, "bin").listFiles()?.take(300)?.forEach { f ->
            if (!f.isFile || f.length() > 512 * 1024) return@forEach
            runCatching {
                val head = ByteArray(minOf(f.length().toInt(), 256))
                f.inputStream().use { it.read(head) }
                if (indexOfBytes(head, marker) >= 0) n++
            }
        }
        return n
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /**
     * 清空整个 termux 根目录（usr + home 一起删），为干净重解压做准备。
     *
     * 注意：若旧树是 root 属主、某层目录权限过严（如 `drwx------`），App 作为 other 删不动，
     * 此时必须靠 Shizuku/root 的 `rm -rf`（见 TermuxTool 的 repair），这里只做 best-effort。
     */
    fun wipeRoot(ctx: Context): Boolean {
        modeCache = null
        return runCatching { termuxRoot(ctx).deleteRecursively() }.getOrDefault(false)
    }

    /**
     * 强制重装：先清根目录，再解压官方 bootstrap。
     *
     * 注意：重装**不能**解决「SELinux 禁止 exec 私有目录 ELF」——那是策略限制，
     * 与文件属主/权限无关（见 [TermuxExec]）。这里只用于真正的文件损坏/半套树。
     *
     * @return null 成功，否则失败原因
     */
    fun forceReinstall(ctx: Context, onProgress: ((Int, String) -> Unit)? = null): String? {
        wipeRoot(ctx)
        return install(ctx, onProgress)
    }
}
