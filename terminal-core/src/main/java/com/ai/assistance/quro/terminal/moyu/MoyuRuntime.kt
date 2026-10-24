package com.ai.assistance.quro.terminal.moyu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 墨语言（Mo）独立运行时 —— **不依赖 Ubuntu / proot**。
 *
 * 为什么可以独立跑（2026-10-23 实测数据）：
 *  - 墨语言工具链（moc / molink / mols / modb / dwmo / mo2c / mo2x / seed）全部是
 *    **x86_64 静态可执行**，ELF 的 DT_NEEDED 为 **0 项**，不需要任何 x86_64 运行库；
 *  - 随包内置的 `qemu-x86_64-static` 是 **aarch64 静态可执行**，同样零依赖，可直接被
 *    Android 进程 execve。
 *  两者组合 = 在 App 私有目录里直接 ProcessBuilder 调用，无需 rootfs、无需 proot、
 *  无需联网装包。这也是用户明确要求的「独立运行而不是运行在 Ubuntu 里」的实现方式。
 *
 * 内置资产 `assets/moyu-runtime.tar.xz` 的结构（约 2.3MB，原 27.4MB）：
 * ```
 * bin/qemu-x86_64-static   aarch64 静态 qemu（把 x86_64 指令翻译到 aarch64）
 * mo/...                   语言本体：bin/ 工具链、src/ 编译器源码、lib/ 标准库、
 *                          tests/、docs/、Makefile、CHANGELOG —— AI 可据此诊断并修 bug
 * ```
 */
class MoyuRuntime(private val context: Context) {

    companion object {
        private const val TAG = "MoyuRuntime"

        /** 内置资产文件名。 */
        const val ASSET = "mo-pkg.pack"

        /** 运行时版本：改了内置包内容就 +1，强制重新解压。 */
        private const val RUNTIME_VERSION = 5
        private const val VERSION_MARK = ".moyu_v"

        /** 可用工具（都在 mo/bin 下，经 qemu 执行）。 */
        val TOOLS = listOf("moc", "molink", "mols", "modb", "dwmo", "mo2c", "mo2x", "seed")

        @Volatile
        private var instance: MoyuRuntime? = null

        fun get(context: Context): MoyuRuntime =
            instance ?: synchronized(this) {
                instance ?: MoyuRuntime(context.applicationContext).also { instance = it }
            }
    }

    /** 运行时根目录：App 私有目录，可执行文件放这里（Android 允许对私有目录 exec）。 */
    val root: File get() = File(context.filesDir, "moyu")
    /**
     * x86_64 指令翻译器（qemu-user）。
     *
     * **必须来自 nativeLibraryDir**：实测（2026-10-24 真机）这条 ROM 的 SELinux 拒绝
     * memfd 执行，App 私有目录里的文件也无法 execve —— 只有随 APK 安装到
     * nativeLibraryDir 的文件才能被拉起。所以它以 libqemu-x86_64.so 的名字打进 jniLibs。
     */
    /**
     * x86_64 用户态模拟器（**aarch64 宿主版**）。
     *
     * 为什么不是 qemu-x86_64.so：ZorvAI 只装 arm64-v8a，而 arm64 内核**不能 exec x86_64 的 ELF**
     * （实测三条路径 memfd / /proc/self/fd / 私有目录全是 Permission denied）。所以需要一个
     * **跑在 aarch64 上、去执行 x86_64 程序**的模拟器 —— box64（NDK 交叉编译，静态需求仅
     * libc/libm/libdl，随 jniLibs 安装即可 exec）。
     */
    val qemu: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libbox64.so")
    val moHome: File get() = File(root, "mo")

    /** 用户/项目工作区（编译产物、临时 .mo 都放这）。 */
    val workspace: File get() = File(root, "workspace")

    fun tool(name: String): File = File(moHome, "bin/$name")

    fun isInstalled(): Boolean = qemu.exists() && tool("moc").exists()

    /** 是否已安装且版本匹配（不存在版本标记的旧解压也视为可复用）。 */
    fun isCurrent(): Boolean {
        if (!isInstalled()) return false
        val cur = runCatching { File(root, VERSION_MARK).readText().trim() }.getOrDefault("")
        return cur.isEmpty() || cur == RUNTIME_VERSION.toString()
    }

    /** 一次执行的结果。 */
    data class ExecResult(
        val code: Int,
        val output: String,
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean get() = code == 0 && !timedOut
    }

    /**
     * 确保运行时已就绪：从 APK assets 解压到私有目录（幂等，已就绪直接返回）。
     *
     * 解压用宿主 busybox 的 tar（支持 .tar.xz），与 rootfs 资产同一套工具，避免为 xz
     * 额外引入 Java 库。
     */
    suspend fun ensureInstalled(onProgress: ((String) -> Unit)? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (isCurrent()) return@runCatching
                root.mkdirs()
                workspace.mkdirs()
                File(root, "tmp").mkdirs()

                onProgress?.invoke("正在解压墨语言运行时（27MB，约 10-20 秒）…")
                // 🔴 2026-10-23 排查修复：这里原先调用宿主 busybox 解压，而 busybox 是终端环境
                // 初始化时才创建的符号链接 —— 用户没开过终端就来点墨语言初始化必然失败，
                // 与"墨语言独立运行"的定位矛盾。现改为**纯 Java 解压**（GZIPInputStream + 内置
                // tar 解析），不依赖任何外部二进制，也不依赖终端环境是否已初始化。
                val extracted = context.assets.open(ASSET).use { input ->
                    extractTarGz(input, root)
                }
                if (extracted < 1) error("解压后没有任何文件落盘（资产可能损坏）")
                Log.i(TAG, "解压完成，$extracted 个条目")

                // 确保可执行位（tar 里带 0755，但保险起见再设一次）
                listOf(qemu, tool("moc"), tool("seed"), tool("mols")).forEach { f ->
                    if (f.exists()) f.setExecutable(true, false)
                }
                if (!isInstalled()) error("解压后仍找不到 ${qemu.absolutePath} 或 moc")
                File(root, VERSION_MARK).writeText(RUNTIME_VERSION.toString())
                Log.i(TAG, "墨语言运行时就绪：${root.absolutePath}")
                onProgress?.invoke("墨语言运行时已就绪")
            }.onFailure { Log.e(TAG, "墨语言运行时安装失败", it) }
        }

    /**
     * 用 qemu 执行一个 x86_64 程序（编译产物或工具链本身）。
     *
     * @param binary 被执行的 x86_64 ELF（绝对路径）
     * @param args 参数
     * @param workDir 工作目录（默认 workspace；源码编译时传源文件所在目录）
     */
    suspend fun exec(
        binary: File,
        args: List<String> = emptyList(),
        workDir: File? = null,
        timeoutMs: Long = 180_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        if (!qemu.exists()) {
            return@withContext ExecResult(-1, "墨语言运行时未安装（缺少 ${qemu.absolutePath}），请先在设置里初始化")
        }
        // 🔴 一律经 MoyuExec：targetSdk 34 下 App 私有目录禁止 execve（SELinux），
        // 把 filesDir 里的 qemu 直接交给 ProcessBuilder 会拿到 EACCES —— 与 chmod 无关。
        // MoyuExec 经 nativeLibraryDir 的 libmoexec.so（memfd + execveat）拉起它，
        // 失败时还会自动退化到直接 exec 并带回每一步的真实错误。
        val r = prootRunX86(
            binary = binary,
            args = args,
            workDir = workDir ?: workspace.apply { mkdirs() },
            env = mapOf(
                "QEMU_LD_PREFIX" to "/",                              // 静态程序不需要，但避免 qemu 探测告警
                "MO_HOME" to moHome.absolutePath,                     // 工具查标准库/资源用
                // 系统层适配：Android 上进程栈/虚拟地址空间与桌面 Linux 不同，
                // 显式给出 qemu 的栈大小与保留地址空间，避免大程序编译时栈溢出/地址耗尽。
                "QEMU_STACK_SIZE" to (8 * 1024 * 1024).toString(),    // 8MB guest 栈
                "QEMU_RESERVED_VA" to "0",                            // 0 = 让 qemu 自动选择
            ),
            timeoutMs = timeoutMs,
        )
        val hint = signalHint(r.code)
        ExecResult(
            r.code,
            if (hint == null) r.output else r.output + "\n" + hint,
            r.timedOut,
        )
    }

    /**
     * proot 承载墨语言用的最小 rootfs（guest 内即 /mo）。
     *
     * 刻意**不用 Ubuntu**：墨语言自带系统（uspace2 那套用户空间），proot 只是用户态模拟器，
     * 它只需要一个能落地的目录 —— 墨语言的 VFS 就在 /mo 里长出来。
     */
    val moRootfs: File get() = File(context.filesDir, "mo_rootfs").apply { mkdirs() }

    /**
     * proot **自己**也要能启动：它是 bionic 动态可执行文件，`DT_NEEDED` 里有 `libtalloc.so.2`。
     *
     * AGP 的 jniLibs 只收严格 `lib` 前缀 + `.so` 结尾的文件名，`libtalloc.so.2` 这种带版本号
     * 结尾的名字会被**静默排除**（APK 里查不到，不是打包失败）。所以运行期从 nativeLibraryDir
     * 里已有的 talloc 复制一份、按 `libtalloc.so.2` 命名落到 rootfs，靠 `LD_LIBRARY_PATH`
     * 让 linker 按**文件名**找到它（linker 只认文件名，不认 soname）。
     *
     * 这一步跟 box64 无关：**任何**一次 proot 调用之前都必须先有它，否则报
     * `CANNOT LINK EXECUTABLE ".../libproot.so": library "libtalloc.so.2" not found`。
     */
    internal fun ensureProotLibs(rootfs: File = moRootfs) {
        rootfs.mkdirs()
        val dst = File(rootfs, "libtalloc.so.2")
        if (dst.isFile && dst.length() > 0L) return
        for (name in listOf("libtalloc.so", "liblibtalloc.so.2.so", "libtalloc.so.2.5.0")) {
            val f = File(context.applicationInfo.nativeLibraryDir, name)
            if (f.isFile && f.length() > 0L) {
                runCatching { f.copyTo(dst, overwrite = true) }
                return
            }
        }
        Log.w(TAG, "找不到可用的 talloc（libtalloc.so 等），proot 可能起不来")
    }

    /**
     * 每一次 proot 调用都必须带的环境。
     *
     * - `LD_LIBRARY_PATH` → rootfs：让 linker 按文件名找到 `libtalloc.so.2`（见 [ensureProotLibs]）。
     * - `PROOT_TMP_DIR`   → App 私有可写目录：proot 要建 glue rootfs 的临时目录，
     *   不指定就会去试 `/tmp`（App 域不可写）→ `can't create temp directory: Permission denied`
     *   → `can't create glue rootfs`，整条链路第一步就死。
     */
    internal fun prootBaseEnv(): Map<String, String> {
        val rootfs = moRootfs
        ensureProotLibs(rootfs)
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        return mapOf(
            "LD_LIBRARY_PATH" to rootfs.absolutePath,
            "PROOT_TMP_DIR" to tmp.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
        )
    }

    /**
     * 把 x86_64 模拟器（box64）与它的依赖装进 proot 的最小 rootfs。
     *
     * 为什么必须进 rootfs：宿主 app 域**不能**直接 exec box64 —— jniLibs 里放不下
     * `libtalloc.so.2`（AGP 只收严格 `lib*.so` 文件名），宿主直连必然 CANNOT LINK。
     * 进 rootfs 后由 proot exec，同目录依赖按 LD_LIBRARY_PATH 就能找到。
     */
    internal suspend fun ensureBox64InGuest(): Result<File> = withContext(Dispatchers.IO) {
        val rootfs = moRootfs
        val mark = File(rootfs, ".box64_v")
        val src = File(context.applicationInfo.nativeLibraryDir, "libbox64.so")
        if (!src.isFile) {
            return@withContext Result.failure(
                IllegalStateException("缺少 box64（${src.absolutePath}）")
            )
        }
        runCatching {
            val dst = File(rootfs, "box64")
            if (!mark.isFile || !dst.isFile) {
                src.copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
                // libtalloc.so.2 —— box64 的依赖；宿主 jniLibs 放不下这个名字（AGP 只收 lib*.so）
                for (cand in listOf(
                    File(context.applicationInfo.nativeLibraryDir, "libtalloc.so"),
                    File(context.applicationInfo.nativeLibraryDir, "liblibtalloc.so.2.so"),
                )) {
                    if (cand.isFile) {
                        File(rootfs, "libtalloc.so.2").writeBytes(cand.readBytes())
                        break
                    }
                }
                mark.writeText("1")
            }
            dst
        }
    }

    /**
     * 在 proot 里执行一个 **x86_64** 程序（经 box64）。
     *
     * 这是墨语言工具链（moc/mo2x/mols/seed，全是 x86_64 静态 ELF）的唯一执行路径 ——
     * 设备是 arm64，内核不能 exec x86_64，所以必须经 box64；而 box64 自己也在 proot 里跑。
     */
    internal suspend fun prootRunX86(
        binary: File,
        args: List<String>,
        workDir: File,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap(),
        stdoutTo: File? = null,
    ): MoyuExec.Result {
        val box64 = ensureBox64InGuest().getOrElse { e ->
            return MoyuExec.Result(-1, "box64 未就绪：${e.message}")
        }
        val rootfs = moRootfs
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (!proot.isFile) {
            return MoyuExec.Result(-1, "缺少 proot（${proot.absolutePath}）")
        }
        // 目标程序也要在 rootfs 里（proot 只能 exec 它看得见的路径）
        File(rootfs, "run_" + binary.name).apply {
            binary.copyTo(this, overwrite = true)
            setExecutable(true, false)
        }
        // workDir 也要让 guest 看见：mo2x 在**当前目录**解析 import（标准库/依赖都放在源文件目录），
        // guest 看不见就 "cannot open import"。用同路径 bind，最直接。
        val work = runCatching { workDir.canonicalPath }.getOrNull() ?: workDir.absolutePath
        // guest 里能看见的挂载点：工作目录、Android 自己的 linker/libc、以及墨语言标准库目录。
        val binds = ArrayList<String>()
        binds += "--bind=$work:/work"
        // box64 是 **bionic 动态可执行文件**（PT_INTERP=/system/bin/linker64，NEEDED 里有 libc.so），
        // 在 guest 里必须看得见 Android 自己的 linker 与 libc，否则 execve 直接 ENOSYS
        // （真机报的是 `execve('/box64'): Function not implemented`）。
        for (p in listOf("/system", "/apex", "/vendor")) {
            if (File(p).isDirectory) binds += "--bind=$p"
        }
        if (moHome.isDirectory) binds += "--bind=${moHome.absolutePath}:/mo-home"
        binds += "--bind=/dev"
        binds += "--bind=/proc"
        return MoyuExec.run(
            context = context,
            binary = proot,
            args = listOf(
                "--rootfs=${rootfs.absolutePath}",
                "--link2symlink",
            ) + binds + listOf(
                "-0",
                // 必须用 **guest 内路径**：宿主绝对路径在 guest 里不存在，
                // 直接 `-w <宿主路径>` 会 "can't chdir ... in the guest rootfs"。
                "-w", "/work",
                "/box64",
                "/run_" + binary.name,
            ) + args,
            workDir = workDir,
            env = prootBaseEnv() + mapOf(
                "MO_HOME" to (if (moHome.isDirectory) "/mo-home" else moHome.absolutePath),
            ) + env,
            timeoutMs = timeoutMs,
            stdoutTo = stdoutTo,
        )
    }

    /**
     * 编译一个 .mo 源文件 → 产出 **AArch64** 可执行文件（真机原生）。
     *
     * 路线与 Ubuntu 用户空间一致：**交叉编译**（mo2x cfs-aarch64 → freestanding C →
     * guest 内 zig 编成 ARM64 静态 ELF）→ **在 proot（Ubuntu guest）里执行**。
     * 墨语言自带运行时（base.s 汇编的 libc 子集 + VFS + shell + 依赖），产物不依赖宿主任何 libc。
     *
     * 宿主 app 域为什么不能直接跑这个产物：私有目录受 SELinux 禁止 execve（memfd 与
     * /proc/self/fd 同样被拒），而 AArch64 静态 ELF 没有 PT_INTERP、/system/bin/linker64
     * 也加载不了它 —— 所以只能借 proot（它模拟的就是本机 aarch64 内核，指令零翻译）。
     *
     * 需要 x86_64 产物时用 [compileX86]（那条走 qemu 逐条翻译，慢一个量级）。
     */
    suspend fun compile(
        source: File,
        output: File = File(source.parentFile, source.nameWithoutExtension + ".elf"),
        timeoutMs: Long = 180_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        val native = MoyuNative(context, this@MoyuRuntime)
        val r = native.build(source, MoyuNative.Target.AARCH64, timeoutMs = maxOf(timeoutMs, 600_000))
        r.fold(
            onSuccess = { br ->
                if (br.elf.absolutePath != output.absolutePath) {
                    runCatching { br.elf.copyTo(output, overwrite = true) }
                }
                ExecResult(0, br.log)
            },
            onFailure = { ExecResult(-1, it.message ?: it.stackTraceToString()) }
        )
    }

    /**
     * 编译成 x86_64 产物（经 qemu 逐条指令翻译执行，慢一个量级；只在需要 x86_64 时用）。
     *
     * moc 的命令行是 `moc <输入.mo> [输出名]`；它会在**当前目录**查找 `import` 的标准库，
     * 所以先把 mo/lib 下的标准库源码同步到源文件目录（已存在同名文件则不覆盖用户改过的版本）。
     */
    suspend fun compileX86(
        source: File,
        output: File = File(source.parentFile, source.nameWithoutExtension + ".elf"),
        timeoutMs: Long = 180_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        val dir = source.parentFile ?: workspace
        ensureLibsIn(dir)
        // 🔴 2026-10-23 排查修正：moc **不支持 -I 参数**（二进制里只有 `import "x.mo"`，
        // 找不到时直接 "cannot open import"）。mopkg.sh 注释里说的「用 -I 指过去」是**假的**，
        // 传 -I 会被 moc 当成输入文件名 → 编译直接失败。
        // moc 的真实语义是「相对当前文件目录找 import」，所以正确做法是把依赖里的 .mo
        // 同步到源文件目录（与标准库 ensureLibsIn 同理），而不是拼 -I。
        ensureDepsIn(dir)
        exec(tool("moc"), listOf(source.name, output.name), dir, timeoutMs)
    }

    /** 编译并立即运行（AArch64 产物在 proot guest 里执行）。 */
    suspend fun compileAndRun(
        source: File,
        programArgs: List<String> = emptyList(),
        timeoutMs: Long = 180_000,
    ): Pair<ExecResult, ExecResult?> = withContext(Dispatchers.IO) {
        val compiled = compile(source, timeoutMs = timeoutMs)
        if (!compiled.ok) return@withContext compiled to null
        val native = MoyuNative(context, this@MoyuRuntime)
        val bin = File(source.parentFile, source.nameWithoutExtension + ".elf")
        val ran = native.run(bin, programArgs, MoyuNative.Target.AARCH64, timeoutMs)
        compiled to ExecResult(ran.code, ran.output, ran.timedOut)
    }

    /** 把标准库 .mo 复制到目标目录（moc 在当前目录找 import）。 */
    fun ensureLibsIn(dir: File) {
        val libDir = File(moHome, "lib")
        if (!libDir.isDirectory) return
        dir.mkdirs()
        libDir.listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.forEach { lib ->
            val dest = File(dir, lib.name)
            if (!dest.exists() || dest.length() != lib.length()) {
                runCatching { lib.copyTo(dest, overwrite = true) }
            }
        }
    }

    /**
     * 把已安装依赖里的 .mo 同步到目标目录 —— moc 只在「当前文件所在目录」找 import，
     * 所以依赖必须落到源目录旁边才 import 得到（这是 moc 的真实机制，不是 -I）。
     */
    fun ensureDepsIn(dir: File) {
        val depsDir = File(workspace, "deps")
        if (!depsDir.isDirectory) return
        dir.mkdirs()
        depsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { dep ->
            dep.listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.forEach { lib ->
                val dest = File(dir, lib.name)
                if (!dest.exists() || dest.length() != lib.length()) {
                    runCatching { lib.copyTo(dest, overwrite = true) }
                }
            }
            // 依赖自带的子目录（如 lib/）也铺平一层，覆盖常见布局
            dep.listFiles { f -> f.isDirectory && f.name != ".git" }?.forEach { sub ->
                sub.listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.forEach { lib ->
                    val dest = File(dir, lib.name)
                    if (!dest.exists() || dest.length() != lib.length()) {
                        runCatching { lib.copyTo(dest, overwrite = true) }
                    }
                }
            }
        }
    }

    /**
     * 把「被信号杀死」的退出码翻译成可读诊断。
     *
     * Android 上最需要解释的三种：
     *  · 137 (SIGKILL)：多为内存不足被 LMK 回收（qemu 翻译缓存较吃内存）
     *  · 159 (SIGSYS)：seccomp 拦了某个系统调用（qemu-user 在定制 ROM 上会中招）
     *  · 139 (SIGSEGV)：段错误，多为被测程序自身越界
     */
    private fun signalHint(code: Int): String? = when (code) {
        137 -> "[诊断] 进程被 SIGKILL（exit 137）：通常内存不足被系统回收。可关掉其他应用后重试，或用更小的程序。"
        159 -> "[诊断] 进程被 SIGSYS（exit 159）：系统 seccomp 拦截了 qemu 需要的系统调用。属设备 ROM 限制，非墨语言问题。"
        139 -> "[诊断] 进程被 SIGSEGV（exit 139）：段错误，多为被测程序自身越界（可用 modb 排查）。"
        134 -> "[诊断] 进程被 SIGABRT（exit 134）：断言失败或主动 abort。"
        else -> null
    }

    /**
     * 解析域名 → IPv4 地址（同时给出墨语言 tcp_connect 需要的整数形式）。
     *
     * 为什么必须由 App 侧来做：墨语言标准库 `lib/net.mo` **完全没有 DNS** ——
     * `tcp_connect(ip: i64, port)` 收的是 4 字节整数地址（如 127.0.0.1 写作 0x0100007f）。
     * 而它用的是裸 syscall，不经过 libc resolver，所以也不存在「静态链接程序读不到
     * /etc/resolv.conf」的问题（Android 本来就没有那个文件，DNS 走 netd）。
     * 结论：**域名必须在 App 侧解析好，再把 IP 整数当参数传给墨语言程序**。
     *
     * @return "host -> 1.2.3.4 (int=0x01020304)" 或错误说明
     */
    suspend fun resolveHost(host: String): String = withContext(Dispatchers.IO) {
        val h = host.trim()
        if (h.isEmpty()) return@withContext "需要 host（域名或 IP）"
        // 已经是点分 IPv4 就直接换算
        val dotted = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        dotted.find(h)?.let { m ->
            val o = m.groupValues.drop(1).map { it.toIntOrNull() ?: -1 }
            if (o.all { it in 0..255 }) {
                val intVal = (o[3] shl 24) or (o[2] shl 16) or (o[1] shl 8) or o[0]
                return@withContext h + " 已是 IPv4 → int=0x" + Integer.toHexString(intVal) +
                    "（小端写法：0x%08x）".format(intVal)
            }
        }
        runCatching {
            val addrs = java.net.InetAddress.getAllByName(h)
            if (addrs.isEmpty()) error("解析结果为空")
            val v4 = addrs.filterIsInstance<java.net.Inet4Address>().firstOrNull()
            if (v4 == null) {
                "❌ " + h + " 只解析到 IPv6（" + addrs.joinToString { it.hostAddress ?: "?" } +
                    "）。墨语言 lib/net.mo 只支持 AF_INET（IPv4），IPv6 不可用。"
            } else {
                val b = v4.address
                val intVal = ((b[3].toInt() and 0xFF) shl 24) or ((b[2].toInt() and 0xFF) shl 16) or
                    ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
                buildString {
                    appendLine(h + " → " + v4.hostAddress +
                        (if (v4.canonicalHostName != v4.hostAddress) "（canonical " + v4.canonicalHostName + "）" else ""))
                    appendLine("tcp_connect 用整数：" + intVal + "  即 0x" + Integer.toHexString(intVal) +
                        "（小端：0x%08x）".format(intVal))
                    val others = addrs.size - 1
                    if (others > 0) append("另有 " + others + " 个地址：" + addrs.mapNotNull { it.hostAddress }.joinToString(", "))
                }.trim()
            }
        }.getOrElse { e ->
            "❌ 解析 " + h + " 失败：" + (e.message ?: e.javaClass.simpleName) +
                "\n提示：设备无网络/DNS 被拦截时会这样；可先用 IP 直连，或用 net_diag 看外网可达性。"
        }
    }

    /** 墨语言版本（读 mo/VERSION）。 */
    fun version(): String =
        runCatching { File(moHome, "VERSION").readText().trim() }.getOrDefault("未安装")

    /** 把用户写的 .mo 源码放进工作区，返回落盘后的文件。 */
    fun writeSource(name: String, code: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9_.\\-\\u4e00-\\u9fa5]"), "_")
            .let { if (it.endsWith(".mo")) it else "$it.mo" }
        val f = File(workspace, safe)
        f.parentFile?.mkdirs()
        f.writeText(code)
        return f
    }

    /**
     * 纯 Java 解压 tar.gz —— 不依赖 busybox、不依赖终端环境是否初始化。
     *
     * 只处理本资产用到的类型：普通文件（'0' 或 0）、目录（'5'）、符号链接（'2'）。
     * 路径做穿越防护：解析后必须仍落在 destDir 内。
     *
     * @return 落盘的文件/链接数
     */
    internal fun extractTarGz(input: java.io.InputStream, destDir: File): Int {
        val destCanonical = destDir.canonicalFile
        var count = 0
        // GNU tar 对超过 100 字符的路径，会先写一个 `././@LongLink`（type 'L'）条目携带完整路径，
        // 紧接的真正条目里 name 只留前 100 字符。不认这个约定，长路径就会被截断 ——
        // 实测 @aws-sdk/.../token-providers/dist-types 就是这样把目录和文件搞混，撞出 EISDIR。
        var pendingLongName: String? = null
        java.util.zip.GZIPInputStream(input, 64 * 1024).use { gz ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(gz, header)) break
                var allZero = true
                for (b in header) { if (b.toInt() != 0) { allZero = false; break } }
                if (allZero) break

                val rawName = tarString(header, 0, 100)
                val prefix = tarString(header, 345, 155)
                var name = (if (prefix.isNotEmpty()) prefix + "/" + rawName else rawName).trim()
                val mode = tarOctal(header, 100, 8).toInt()
                val size = tarOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val linkName = tarString(header, 157, 100)

                // GNU 长名条目：把内容读出来当"下一条目的真名"
                if (type == 'L') {
                    val buf = java.io.ByteArrayOutputStream()
                    copyExactly(gz, buf, size)
                    pendingLongName = String(buf.toByteArray(), Charsets.UTF_8).trimEnd('\u0000').trim()
                    continue
                }
                // PAX 扩展头（type 'x'/'g'）：同样只是元数据，跳过内容
                if (type == 'x' || type == 'g' || type == 'K') {
                    skip(gz, size)
                    continue
                }
                if (pendingLongName != null) {
                    name = pendingLongName!!
                    pendingLongName = null
                }
                if (name.isEmpty() || name == ".") { continue }

                val target = File(destDir, name).canonicalFile
                if (!target.path.startsWith(destCanonical.path + File.separator) &&
                    target.path != destCanonical.path
                ) {
                    Log.w(TAG, "跳过越界路径：" + name)
                    skip(gz, size)
                    continue
                }

                when (type) {
                    '5' -> {
                        target.mkdirs()
                        applyMode(target, mode, true)
                        skip(gz, size)
                    }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        runCatching {
                            java.nio.file.Files.deleteIfExists(target.toPath())
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(), java.nio.file.Paths.get(linkName)
                            )
                        }
                        skip(gz, size)
                        count++
                    }
                    else -> {
                        // 有些打包器会把目录条目写成普通文件类型（type '0'、名字以 / 结尾，
                        // 或 size 为 0）。若照 else 一律 outputStream()，打开已存在的目录会抛
                        // EISDIR —— 实测 @aws-sdk/token-providers/dist-types 就踩了这个。
                        if (target.isDirectory || name.endsWith("/")) {
                            target.mkdirs()
                            skip(gz, size)
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> copyExactly(gz, out, size) }
                            applyMode(target, mode, false)
                            count++
                        }
                    }
                }
            }
        }
        return count
    }

    private fun applyMode(f: File, mode: Int, isDir: Boolean) {
        val exec = isDir || (mode and 0x49) != 0
        runCatching {
            f.setReadable(true, false)
            f.setWritable(true, true)
            if (exec) f.setExecutable(true, false)
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off != 0
            off += n
        }
        return true
    }

    private fun copyExactly(input: java.io.InputStream, out: java.io.OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skip(input, pad)
    }

    private fun skip(input: java.io.InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val r = input.read(buf, 0, want)
            if (r < 0) return
            left -= r
        }
    }

    private fun tarString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun tarOctal(b: ByteArray, off: Int, len: Int): Long {
        if ((b[off].toInt() and 0x80) != 0) {
            var v = 0L
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        var seen = false
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) { if (seen) break else continue }
            val d = c - '0'.code
            if (d !in 0..7) break
            v = v * 8 + d
            seen = true
        }
        return v
    }

    /** 依赖图中的一个源文件。 */
    data class GraphNode(
        val rel: String,            // 展示用相对路径
        val file: File,
        val imports: List<String>,  // 它 import 的文件名
        val resolved: Boolean,      // 依赖是否全部能找到
    )

    /**
     * 递归收集 import 依赖并做拓扑排序 —— 等价官方 tools/mobuild.sh 的"打印依赖图"部分。
     *
     * 为什么要在 App 里重做：官方脚本是 bash，Android 没有 bash，144 个 tools 脚本全跑不了。
     * 而 moc 本身能递归处理 import，脚本的价值在于**提前暴露缺失文件与循环依赖**，
     * 所以这里只做静态分析，编译仍交给 moc。
     *
     * 搜索顺序与 moc 一致：源文件同目录 → workspace/deps/各依赖 → mo/lib（标准库）。
     *
     * @return Pair(拓扑序列表, 诊断文本)
     */
    fun analyzeGraph(entry: File): Pair<List<File>, String> {
        val importRe = Regex("""^\s*import\s+"([^"]+)"""", RegexOption.MULTILINE)
        val searchDirs = mutableListOf<File>()
        entry.parentFile?.let { searchDirs.add(it) }
        File(workspace, "deps").listFiles { f -> f.isDirectory }?.forEach { searchDirs.add(it) }
        val libDir = File(moHome, "lib")
        if (libDir.isDirectory) searchDirs.add(libDir)

        val visited = LinkedHashMap<String, GraphNode>()
        val order = mutableListOf<File>()
        val missing = mutableListOf<String>()
        val cycles = mutableListOf<String>()

        fun resolve(name: String, fromDir: File?): File? {
            fromDir?.let { File(it, name).takeIf { f -> f.isFile } }?.let { return it }
            searchDirs.forEach { d -> File(d, name).takeIf { f -> f.isFile }?.let { return it } }
            return null
        }

        fun visit(file: File, stack: MutableList<String>) {
            val key = file.absolutePath
            if (key in stack) {
                cycles.add(stack.dropWhile { it != key }.joinToString(" -> ") { File(it).name } + " -> " + file.name)
                return
            }
            if (key in visited) return
            val text = runCatching { file.readText() }.getOrDefault("")
            val imports = importRe.findAll(text).map { it.groupValues[1] }.toList()
            stack.add(key)
            imports.forEach { imp ->
                val target = resolve(imp, file.parentFile)
                if (target == null) {
                    missing.add("${file.name} -> $imp")
                } else {
                    visit(target, stack)
                }
            }
            stack.removeAt(stack.lastIndex)
            visited[key] = GraphNode(
                rel = file.absolutePath.removePrefix(workspace.absolutePath).trimStart('/'),
                file = file,
                imports = imports,
                resolved = imports.all { resolve(it, file.parentFile) != null },
            )
            order.add(file)
        }

        visit(entry, mutableListOf())

        val sb = StringBuilder()
        sb.appendLine("入口：${entry.name}")
        sb.appendLine("依赖文件：${order.size - 1} 个（含入口共 ${order.size}）")
        if (missing.isNotEmpty()) {
            sb.appendLine("⚠ 缺失 ${missing.size} 处 import：")
            missing.take(15).forEach { sb.appendLine("   $it") }
        }
        if (cycles.isNotEmpty()) {
            sb.appendLine("⚠ 检测到循环依赖：")
            cycles.distinct().take(5).forEach { sb.appendLine("   $it") }
        }
        if (missing.isEmpty() && cycles.isEmpty()) sb.appendLine("✅ 依赖完整，无循环")
        sb.appendLine()
        sb.appendLine("构建顺序（拓扑序）：")
        order.forEachIndexed { i, f -> sb.appendLine("  ${i + 1}. ${f.name}") }
        return order to sb.toString().trim()
    }

    /** 运行时体检信息（给 UI / 诊断用）。 */
    fun diagnose(): String = buildString {
        appendLine("运行时目录：${root.absolutePath}")
        appendLine("qemu：${if (qemu.exists()) "有 (${qemu.length() / 1024} KB)" else "缺"}")
        appendLine("语言本体：${if (moHome.isDirectory) "有" else "缺"}")
        appendLine("工具链：" + TOOLS.joinToString(" ") { t -> if (tool(t).exists()) "✓$t" else "✗$t" })
        append("版本标记：" + runCatching { File(root, VERSION_MARK).readText().trim() }.getOrDefault("无"))
    }
}
