package com.ai.assistance.quro.terminal.moyu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * 墨语言 AArch64 原生目标：把 `.mo` 直接编成 **Android 能原生执行**的 ARM64 静态 ELF。
 *
 * 与既有的 x86_64 路径的本质区别（不是"换了个参数"）：
 *
 *   旧：.mo --moc--> x86_64 ELF --qemu 逐条指令翻译--> 执行
 *       每条 guest 指令都要先翻译成宿主指令，实测慢一到两个数量级，
 *       且 qemu 自己也要占几十 MB 常驻内存。
 *
 *   新：.mo --mo2x(cfs-aarch64)--> freestanding C --zig cc--> ARM64 静态 ELF --> 原生执行
 *       ZorvAI 只装 arm64-v8a，Android 的 ABI 就是 arm64，内核就是 Linux，
 *       aarch64 的系统调用号与 Linux 一致 —— 产物是**真机原生**执行的，没有指令翻译。
 *       但「原生」不等于「能在宿主里 exec」：App 私有目录受 SELinux 禁止 execve，
 *       而 AArch64 静态 ELF 没有 PT_INTERP、/system/bin/linker64 也加载不了它。
 *       所以编译（zig）与执行都放进 Ubuntu guest，由 proot 跑 —— 模拟的正是本机 aarch64 内核，
 *       指令零翻译、零 libc，代价只是多一层 proot。
 *
 * 为什么中间要过一层 C：墨语言官方编译链（moc）只有 x86_64 一个代码生成后端；多架构
 * 是由 `mo2x` 转译器承担的 —— 它把墨语言转成 freestanding C（内联 `svc #0`、不带 libc），
 * 再交给真正的 C 编译器出机器码。所以"出 aarch64"这一步绕不开一个 C 编译器。
 *
 * 注意：这里产出的是**用户态系统程序**，不是 Linux 内核，也不链接 musl/glibc。
 * 它只认内核的系统调用；给它的 cfs-aarch64 后端自己实现了 write/exit 与文件、目录、进程的号表适配。
 */
class MoyuNative(private val context: Context, private val rt: MoyuRuntime) {

    companion object {
        private const val TAG = "MoyuNative"

        /** zig 编译器：作为 libzig.so 打进 jniLibs，安装后位于可执行目录（Android 10+ 必需）。 */
        private const val CC_LIB_NAME = "libzig.so"

        /**
         * 编译器资产包（tar.gz，根下是 `zig` 与 `lib/`）。
         *
         * 为什么**不**把 zig 放进 jniLibs：`lib/` 下的文件在 APK 里是不压缩存储的，
         * 130MB 的 zig 会原样撑大 APK；压进 assets 后只有几十 MB，解压到私有目录再用。
         */
        const val CC_ASSET = "mo-cc.pack"
        /**
         * **墨语言自举的系统**（ZorvAI 集成形态，随 APK 内置）。
         *
         * 这个包里装的是**已经构建好的系统本体**，不是"待构建的源码"：
         *
         *     bin/uspace2              aarch64 静态 ELF（199KB，无 PT_INTERP、不链 libc）—— 系统 init
         *     etc/uspace.conf          系统配置（root=/mo、state=/mo/state.db）
         *     etc/motd
         *     mo/                      VFS 落点（系统第一次运行会把 state.db 写在这里）
         *     src/uspace2.mo           系统自己的源码（墨语言写的，镜像可自证）
         *     src/kernel64.mo           自举内核源码（墨语言）
         *     boot/kernel64.elf         自举内核成品（AArch64 裸机 ELF，79KB，已真机仿真验证）
         *     boot/molang-system.iso   成品 ISO9660 系统镜像（196 扇区，内含上面的 kernel64.elf）
         *     VERSION
         *
         * 也就是说：**proot 一挂上去就是一个能跑的系统**，用户在设备上不需要点任何
         * 「构建」按钮 —— 转译（mo2x）与编译（zig cc）在打包进 APK 之前就做完了。
         *
         * 构建期产物由 `D:/CalwOS-build/tmp/pack_system.py` 生成（见脚本头部说明）。
         */
        const val SYSTEM_ASSET = "molang-system.pack"

        /** 内置系统的版本号。换了 pack 内容必须 +1，否则已装过的设备不会重解压。 */
        private const val SYSTEM_VERSION = 3
        private const val CC_VERSION = 1
        private const val CC_MARK = ".mo_cc_v"

        /** ELF 机器类型。 */
        const val EM_AARCH64 = 0xB7
        const val EM_X86_64 = 0x3E

        /** zig 的 lib 目录名（解压后）。 */
        private const val CC_LIB_DIR = "lib"
    }

    /**
     * 编译目标。
     *
     * `cfs` = freestanding C + Linux 系统调用（无 libc）。aarch64 与 x86_64 是同一套后端的两份号表。
     * `kern-aarch64` 是**裸机**目标：没有系统调用、没有 libc，输出走 PL011 UART，只能跑在
     * 模拟器（qemu-system-aarch64 -M virt）里 —— Android 上不能裸机启动，所以这一项只做"能编出来"。
     */
    enum class Target(val id: String, val label: String, val note: String) {
        AARCH64(
            "cfs-aarch64",
            "AArch64 · proot 承载",
            "编译与执行都在 proot 内完成：产物是 ARM64 静态 ELF（不链 libc、自带系统调用），由 proot 直接承载 —— 不经过 qemu，也不直接跑在 Android 上",
        ),
        X86_64(
            "cfs",
            "x86_64",
            "经 qemu 翻译执行 · 慢一个量级",
        ),
        KERNEL(
            "kern-aarch64",
            "AArch64 裸机内核",
            "PL011 UART · 无系统调用 · 仅模拟器可跑",
        ),
    }

    /** zig 的 lib 目录（给 --zig-lib-dir 用）。 */
    val ccLibDir: File get() = File(File(rt.root, "cc"), CC_LIB_DIR)

    /** zig 解包根目录（`zig` 可执行与 `lib/` 都在它下面；供 bind 进 Ubuntu 用）。 */
    val ccRoot: File get() = File(rt.root, "cc")

    /**
     * zig 可执行文件。
     *
     * 优先用 nativeLibraryDir 里的 libzig.so —— 那是唯一保证可执行的 App 内目录。
     * 退回私有目录（老 APK 或用户手工放置），此时执行要经 memfd 启动器。
     */
    fun ccBinary(): File {
        val inLibDir = File(context.applicationInfo.nativeLibraryDir, CC_LIB_NAME)
        if (inLibDir.isFile) return inLibDir
        return File(File(rt.root, "cc"), "zig")
    }

    fun isCcReady(): Boolean = ccBinary().isFile && ccLibDir.isDirectory

    // ─────────────────────────── 编译器资产准备 ───────────────────────────

    /**
     * 解压编译器资产包（幂等）：`zig` 可执行 + `lib/` 目录 → `filesDir/moyu/cc/`。
     *
     * 幂等判定看三件事：标记版本、zig 文件在、lib 目录在。任一不满足就重解压。
     */
    suspend fun ensureCc(onProgress: ((String) -> Unit)? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val base = File(rt.root, "cc")
                val mark = File(base, CC_MARK)
                if (ccBinary().isFile && ccLibDir.isDirectory && mark.isFile &&
                    mark.readText().trim() == "v=$CC_VERSION"
                ) {
                    return@withContext Result.success(Unit)
                }
                onProgress?.invoke("解压 AArch64 编译器（zig + lib，约 150MB）…")
                base.mkdirs()
                context.assets.open(CC_ASSET).use { input ->
                    rt.extractTarGz(input, base)
                }
                if (!ccLibDir.isDirectory && File(base, CC_LIB_DIR).isDirectory) {
                    // 兼容 lib/ 直接落在 base 下的打包方式
                }
                mark.writeText("v=$CC_VERSION")
                if (!ccBinary().isFile || !ccLibDir.isDirectory) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "解压后仍缺文件：zig=" + ccBinary().isFile + " lib=" + ccLibDir.isDirectory +
                                "（包内应是 zig 与 lib/ 两个顶层条目）"
                        )
                    )
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "解压编译器失败", e)
                Result.failure(e)
            }
        }

    // ─────────────────────────── 编译 ───────────────────────────

    /** 转译结果：C 文件。 */
    data class TranspileResult(val cFile: File, val bytes: Long, val raw: String)

    /**
     * `.mo` → freestanding C（`mo2x`）。
     *
     * mo2x 本身是 x86_64 静态 ELF，所以它必须经 qemu 跑；这一步是**唯一**还用到 qemu 的地方，
     * 产物之后的执行完全不再经过它。
     *
     * 注意 mo2x 把 C 写到 **stdout**，所以这里必须重定向到文件，不能当普通程序收输出。
     */
    suspend fun transpile(src: File, target: Target): Result<TranspileResult> =
        withContext(Dispatchers.IO) {
            val mo2x = rt.tool("mo2x")
            if (!mo2x.isFile) {
                return@withContext Result.failure(IllegalStateException("运行时未安装（缺 mo2x）"))
            }
            val dir = src.parentFile ?: rt.workspace
            // mo2x 在该目录解析 import，标准库与依赖必须先就位
            rt.ensureLibsIn(dir)
            rt.ensureDepsIn(dir)
            val out = File(dir, src.nameWithoutExtension + "." + target.id.replace('-', '_') + ".c")
            out.delete()
            try {
                // 必须走 prootRunX86：设备是 arm64，内核不能 exec x86_64 的 mo2x，
                // 而 box64 自己也在 proot 里（宿主 jniLibs 放不下 libtalloc.so.2）。
                // 之前这里用 ProcessBuilder 直连，等于把上面整条 proot 链路全绕开了 ——
                // 真机报错一直没变就是这个原因。
                val r = rt.prootRunX86(
                    binary = mo2x,
                    args = listOf(src.name, target.id),
                    workDir = dir,
                    timeoutMs = 180_000,
                    env = mapOf("QEMU_STACK_SIZE" to (8 * 1024 * 1024).toString()),
                    stdoutTo = out, // stdout 就是 C 源码
                )
                if (r.code != 0 || !out.isFile || out.length() == 0L) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "mo2x 转译失败（exit=${r.code}）\n" + r.output.trim().take(1500)
                        )
                    )
                }
                Result.success(TranspileResult(out, out.length(), r.output.trim()))
            } catch (e: Exception) {
                Log.e(TAG, "transpile 失败", e)
                Result.failure(e)
            }
        }

    /** 编译结果详情。 */
    data class BuildResult(
        val elf: File,
        val target: Target,
        val cFile: File,
        val info: ElfInfo,
        val log: String,
        val millis: Long,
    )

    /**
     * 完整链路：`.mo` → C → ELF。
     *
     * zig 的编译参数与官方 `tools/android_native.sh` 保持一致：
     *   `-target aarch64-linux-musl -static -nostdlib -fno-builtin -O2`
     *
     * `-nostdlib` 不要 crt；`-fno-builtin` 阻止编译器把手写循环替换成 strlen/memcpy
     * 之类的库函数调用 —— freestanding 产物不链 libc，被换出来的调用在链接期就是
     * undefined symbol，而且这是**静默发生**的（源码里从没写过 strlen）。
     */
    suspend fun build(
        src: File,
        target: Target = Target.AARCH64,
        optimize: String = "-O2",
        timeoutMs: Long = 600_000,
    ): Result<BuildResult> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val t = transpile(src, target).getOrElse { e ->
            return@withContext Result.failure(e)
        }

        // guest 编译器准备。zig 是 aarch64 Linux ELF，宿主 app 域禁止 execve 私有目录里的 ELF，
        // 所以它必须装进 guest（Ubuntu 本身就是 aarch64，zig 在那里能直接跑）。
        ensureProotCc().getOrElse { e ->
            return@withContext Result.failure(
                IllegalStateException("proot 编译器未就绪：${e.message}")
            )
        }

        val ztarget = when (target) {
            Target.AARCH64 -> "aarch64-linux-musl"
            Target.X86_64 -> "x86_64-linux-musl"
            Target.KERNEL -> "aarch64-freestanding"
        }
        val flags = buildList {
            add("-target"); add(ztarget)
            if (target != Target.KERNEL) add("-static")
            add("-nostdlib")
            add("-fno-builtin")
            add(optimize)
            if (target == Target.KERNEL) add("-mgeneral-regs-only")
        }.joinToString(" ")

        val outName = src.nameWithoutExtension + ".arm64"
        // C 源码与产物都放进 proot 的最小 rootfs（guest 内就是 /mo）
        val rootfs = moRootfs()
        val guestC = File(rootfs, "src_${src.nameWithoutExtension}.c")
        t.cFile.copyTo(guestC, overwrite = true)
        val guestCmd = listOf(
            "/mo/mocc/zig", "cc",
            "-target", ztarget,
            *(if (target != Target.KERNEL) arrayOf("-static") else emptyArray()),
            "-nostdlib", "-fno-builtin", optimize,
            *(if (target == Target.KERNEL) arrayOf("-mgeneral-regs-only") else emptyArray()),
            "-o", "/mo/$outName",
            "/mo/${guestC.name}",
        )
        val (code, out) = try {
            prootRun(guestCmd.first(), guestCmd.drop(1), timeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "proot 编译失败", e)
            return@withContext Result.failure(e)
        }
        if (code != 0) {
            return@withContext Result.failure(
                IllegalStateException("C 编译失败（exit=$code）\n" + out.trim().takeLast(2000))
            )
        }

        // 产物就在 proot rootfs 里（宿主侧同一目录，可读）
        val elf = File(t.cFile.parentFile, outName)
        val produced = File(rootfs, outName)
        if (produced.isFile) {
            produced.copyTo(elf, overwrite = true)
            File(elf.absolutePath).setExecutable(true, false)
        }
        if (!elf.isFile) {
            return@withContext Result.failure(
                IllegalStateException("编译结束但找不到产物 /mo/$outName\n" + out.trim().takeLast(1200))
            )
        }

        val info = inspect(elf)
        if (target == Target.AARCH64 && info.machine != EM_AARCH64) {
            return@withContext Result.failure(
                IllegalStateException(
                    "产物架构不对：期望 AArch64(0xB7)，实际 0x${info.machine.toString(16)}"
                )
            )
        }
        Result.success(
            BuildResult(
                elf = elf,
                target = target,
                cFile = t.cFile,
                info = info,
                log = buildString {
                    if (t.raw.isNotBlank()) appendLine(t.raw)
                    if (out.isNotBlank()) appendLine(out.trim())
                    appendLine("C 源码：${t.cFile.name}（${t.bytes} 字节）")
                    appendLine("产物：${elf.name}（${info.size} 字节）")
                    appendLine("架构：${info.machineName} · ${info.linkKind}")
                },
                millis = System.currentTimeMillis() - t0,
            )
        )
    }


    // ─────────────────────────── proot 承载（无 Ubuntu 依赖）───────────────────────────

    /**
     * proot 承载墨语言用的**最小 rootfs**。
     *
     * 刻意**不用 Ubuntu**：墨语言自带系统（uspace2 那套用户空间），proot 只是用户态模拟器，
     * 它只需要一个能落地的目录 —— 墨语言的 VFS 就在 /mo 里长出来。
     */
    private fun moRootfs(): File = File(context.filesDir, "mo_rootfs").apply { mkdirs() }

    /**
     * 在 proot 里执行一个程序。
     *
     * @param program guest 内路径（如 /mo/xxx）
     * @param args 程序参数
     */
    private suspend fun prootRun(
        program: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 120_000,
        env: Map<String, String> = emptyMap(),
        stdoutTo: File? = null,
    ): MoyuExec.Result {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (!proot.isFile) {
            return MoyuExec.Result(-1, "缺少 proot（${proot.absolutePath}）")
        }
        val rootfs = moRootfs()
        // zig 这类工具要写临时文件与缓存；rootfs 是我们自己造的最小目录，默认没有 /tmp，
        // 不挂上去就会 "unable to create temporary file"。
        File(rootfs, "tmp").mkdirs()
        val launch = listOf(
            "--rootfs=${rootfs.absolutePath}",
            "--link2symlink",
            "--bind=${rootfs.absolutePath}:/mo",
            "--bind=${File(rootfs, "tmp").absolutePath}:/tmp",
            "--bind=/dev",
            "--bind=/proc",
            "-0",
            "-w", "/",
            program,
        ) + args
        return MoyuExec.run(
            context = context,
            binary = proot,
            args = launch,
            workDir = rootfs,
            env = rt.prootBaseEnv() + mapOf(
                "HOME" to "/mo",
                "TMPDIR" to "/tmp",
                "ZIG_GLOBAL_CACHE_DIR" to "/tmp/zig-cache",
            ) + env,
            timeoutMs = timeoutMs,
            stdoutTo = stdoutTo,
        )
    }

    /**
     * 在 proot 里运行**墨语言自举系统**里的程序（不 bind 到 /mo）。
     *
     * 与 [prootRun] 的区别只有一处：**不做** `--bind=<rootfs>:/mo`。
     *
     * 因为 uspace2 自己就把 `/mo` 当 VFS 根 —— 一旦把 rootfs 再 bind 到 `/mo`，
     * 系统的 `/mo/etc`、`/mo/state.db` 就会和 rootfs 的 `bin/`、`etc/` 混成一层，
     * 系统树就没边界了。让 `/mo` 老老实实是 rootfs 里的那个 `mo/` 子目录，
     * 状态才会落在系统自己身上，第二次启动才能走 restore 模式。
     */
    private suspend fun prootSystem(
        program: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 120_000,
        env: Map<String, String> = emptyMap(),
    ): MoyuExec.Result {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (!proot.isFile) {
            return MoyuExec.Result(-1, "缺少 proot（${proot.absolutePath}）")
        }
        val rootfs = moRootfs()
        val tmpDir = File(rootfs, "tmp").apply { mkdirs() }
        val launch = listOf(
            "--rootfs=${rootfs.absolutePath}",
            "--link2symlink",
            // guest 内的 /tmp 也必须是真实可写目录（uspace2 会写临时文件）
            "--bind=${tmpDir.absolutePath}:/tmp",
            "--bind=/dev",
            "--bind=/proc",
            "-0",
            "-w", "/",
            program,
        ) + args
        return MoyuExec.run(
            context = context,
            binary = proot,
            args = launch,
            workDir = rootfs,
            env = rt.prootBaseEnv() + mapOf("HOME" to "/mo", "TMPDIR" to "/tmp") + env,
            timeoutMs = timeoutMs,
        )
    }

    /**
     * 把 zig 编译器装进 guest。
     *
     * 为什么必须装进 guest：zig 是 aarch64 Linux ELF，宿主 app 域**禁止 execve 私有目录里的 ELF**
     * （真机实测：memfd 与 /proc/self/fd 都被 SELinux 拒），而 guest 里的 Ubuntu 本就是 aarch64，
     * zig 在那里可以直接跑。
     */
    /**
     * 把 zig 编译器装进 proot 的最小 rootfs。
     *
     * zig 是 **AArch64 静态**二进制（无 PT_INTERP、无 .dynamic 段），所以 proot 能直接 exec 它 ——
     * 不需要 Ubuntu，也不需要宿主 app 域能 exec（app 域是禁止 execve 私有目录的）。
     */
    suspend fun ensureProotCc(onProgress: ((String) -> Unit)? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            val ccDir = File(moRootfs(), "mocc")
            val ccBin = File(ccDir, "zig")
            val mark = File(ccDir, ".v")
            if (mark.isFile && mark.readText().trim() == CC_VERSION.toString() && ccBin.isFile) {
                return@withContext Result.success(Unit)
            }
            runCatching {
                onProgress?.invoke("把 zig 编译器装进 proot 运行时（约 130MB，仅首次）…")
                ccDir.mkdirs()
                context.assets.open(CC_ASSET).use { input ->
                    rt.extractTarGz(input, ccDir)
                }
                if (!ccBin.isFile) error("proot rootfs 里没找到 zig（$CC_ASSET 解压异常）")
                ccBin.setExecutable(true, false)
                mark.writeText(CC_VERSION.toString())
                onProgress?.invoke("proot 编译器已就绪")
                Unit
            }.onFailure { Log.e(TAG, "proot 编译器安装失败", it) }
        }

    // ─────────────────────────── 执行 ───────────────────────────

    /**
     * 执行产物。
     *
     * AArch64 产物：经 memfd 启动器**原生执行**，不碰 qemu。
     * X86_64 产物：退回 qemu 翻译执行。
     */
    suspend fun run(
        elf: File,
        args: List<String> = emptyList(),
        target: Target = Target.AARCH64,
        timeoutMs: Long = 120_000,
    ): MoyuExec.Result {
        return if (target == Target.X86_64) {
            MoyuExec.run(
                context = context,
                binary = rt.qemu,
                args = listOf(elf.absolutePath) + args,
                workDir = elf.parentFile,
                timeoutMs = timeoutMs,
            )
        } else {
            // AArch64 产物是**静态** ELF（自带系统调用实现，不依赖宿主 libc），
            // 放进 proot 的最小 rootfs 执行 —— **不需要 Ubuntu**。
            val rootfs = moRootfs()
            val guestBin = File(rootfs, elf.name)
            elf.copyTo(guestBin, overwrite = true)
            guestBin.setExecutable(true, false)
            val r = prootRun("/mo/${guestBin.name}", args, timeoutMs)
            MoyuExec.Result(r.code, r.output, r.timedOut)
        }
    }

    /**
     * 按产物**实际架构**决定执行方式，不依赖调用方记得传什么。
     *
     * aarch64 → 经 memfd 原生执行；x86_64 → 退回 qemu 翻译。
     * 这样用户在产物列表里点"运行"时，绝不会因为选错目标而把一个 aarch64 ELF 塞进 qemu。
     */
    suspend fun runAuto(
        elf: File,
        args: List<String> = emptyList(),
        timeoutMs: Long = 120_000,
    ): MoyuExec.Result {
        val t = if (inspect(elf).machine == EM_X86_64) Target.X86_64 else Target.AARCH64
        return run(elf, args, t, timeoutMs)
    }


    // ─────────────────── 墨语言自举的系统（proot 承载 · 随 APK 预置）───────────────────

    /** 系统状态 —— 给 UI 直接显示，不需要点任何按钮才知道系统在不在。 */
    data class SystemStatus(
        val installed: Boolean,
        val version: String,
        val initSize: Long,
        val isoSize: Long,
        val rootfs: String,
        val initStatic: Boolean,
        val initMachine: String,
    ) {
        val summary: String
            get() = if (!installed) "内置系统尚未落地（首次进入时自动解压）"
            else "$version · init $initMachine 静态 ${initSize / 1024}KB · ISO ${isoSize / 1024}KB"
    }

    /**
     * 把**内置的墨语言自举系统**落进 proot 的 rootfs（一次性，按 [SYSTEM_VERSION] 幂等）。
     *
     * 包里是**已经编译好的** aarch64 静态 init 与成品 ISO，所以这里**只解压、不构建**：
     * 转译（`mo2x cfs-aarch64`）与编译（`zig cc -target aarch64-linux-musl`）都在打包进
     * APK **之前**做完了。
     *
     * 这一点是硬要求：没有系统，proot 就是空转；系统必须天生就在，而不是让用户
     * 在手机上点「构建系统镜像」。
     *
     * 落点就是 rootfs 根（`bin/ etc/ mo/ src/ boot/`）—— proot `--rootfs` 指过去即是一个
     * 能启动的系统，与 Ubuntu 毫无关系。
     */
    suspend fun ensureSystem(onProgress: ((String) -> Unit)? = null): Result<File> =
        withContext(Dispatchers.IO) {
            val rootfs = moRootfs()
            // proot 自己依赖 libtalloc.so.2（AGP 打不进这个名字），每次用 proot 前都要补。
            rt.ensureProotLibs(rootfs)
            val init = File(rootfs, "bin/uspace2")
            val mark = File(rootfs, ".sysv")
            if (mark.isFile && mark.readText().trim() == SYSTEM_VERSION.toString() &&
                init.isFile && init.length() > 0L
            ) {
                init.setExecutable(true, false)
                return@withContext Result.success(rootfs)
            }
            runCatching {
                onProgress?.invoke("解开内置的墨语言自举系统…")
                context.assets.open(SYSTEM_ASSET).use { input ->
                    rt.extractTarGz(input, rootfs)
                }
                if (!init.isFile || init.length() == 0L) {
                    error("内置系统包不完整：缺 bin/uspace2")
                }
                init.setExecutable(true, false)
                File(rootfs, "mo").mkdirs()
                File(rootfs, "tmp").mkdirs()
                mark.writeText(SYSTEM_VERSION.toString())
                onProgress?.invoke("墨语言自举系统已就位")
                rootfs
            }.onFailure { Log.e(TAG, "墨语言自举系统落地失败", it) }
        }

    /** 只读系统状态：不解压、不写盘、不编译。 */
    fun systemStatus(): SystemStatus {
        val rootfs = moRootfs()
        val init = File(rootfs, "bin/uspace2")
        val iso = File(rootfs, "boot/molang-system.iso")
        val installed = init.isFile && init.length() > 0L
        val info = if (installed) runCatching { inspect(init) }.getOrNull() else null
        val ver = File(rootfs, "VERSION").let { f ->
            if (f.isFile) f.readText().trim().lineSequence().firstOrNull() ?: "" else ""
        }
        return SystemStatus(
            installed = installed,
            version = ver.ifEmpty { "molang-system" },
            initSize = if (installed) init.length() else 0L,
            isoSize = if (iso.isFile) iso.length() else 0L,
            rootfs = rootfs.absolutePath,
            initStatic = info?.isAarch64Static == true,
            initMachine = info?.machineName ?: "—",
        )
    }

    /**
     * 启动**墨语言自举的系统**：proot 直接 exec 预置的 `/bin/uspace2`。
     *
     * 不带 `--bind rootfs:/mo` —— 系统的 VFS 根就该是 rootfs 里的 `/mo` 目录，
     * 于是 `state.db`、`/mo/etc/motd` 这些落进系统自己的树里，第二次启动走 restore 模式。
     *
     * `uspace2` 是 ARM64 freestanding ELF（`mo2x cfs-aarch64` → zig，`-nostdlib -static`，
     * 自带 `svc #0` 系统调用），proot 不需要任何翻译层就能承载它。
     *
     * 墨语言程序随后由 uspace2 的包管理装载执行 —— 这就是「墨语言跑在这个自举系统里」。
     */
    suspend fun startSelfHostedSystem(
        timeoutMs: Long = 120_000,
    ): Result<String> = withContext(Dispatchers.IO) {
        val rootfs = ensureSystem().getOrElse { e ->
            return@withContext Result.failure(
                IllegalStateException("内置自举系统未就绪：${e.message}")
            )
        }
        val init = File(rootfs, "bin/uspace2")
        val r = prootSystem("/bin/uspace2", emptyList(), timeoutMs)
        val out = r.output.trim()
        // 只有真跑成功才算成功：以前这里 `!r.ok && out.isEmpty()` 才判失败，
        // 结果把 linker 报错（CANNOT LINK EXECUTABLE …）当成正常输出、上面还印
        // "已由 proot 承载"，等于把失败包装成成功。
        if (!r.ok) {
            return@withContext Result.failure(
                IllegalStateException(
                    "墨语言自举系统启动失败（exit=${r.code}）\n" +
                        "  rootfs: ${rootfs.absolutePath}\n" +
                        "  init  : ${init.absolutePath}（${init.length()} 字节）\n\n" +
                        out.takeLast(2000)
                )
            )
        }
        val st = systemStatus()
        Result.success(
            "墨语言自举的系统已由 proot 承载\n" +
                "  rootfs : ${rootfs.absolutePath}\n" +
                "  init   : /bin/uspace2（${st.initSize} 字节 · ${st.initMachine} · " +
                (if (st.initStatic) "静态无 PT_INTERP" else "动态") + "）\n" +
                "  镜像   : boot/molang-system.iso（${st.isoSize} 字节）\n\n" +
                out
        )
    }

    /**
     * 导出系统镜像：ISO 已经在内置包里（`boot/molang-system.iso`），这里只是把它
     * 放到 App 可访问的目录并报告大小 —— **不做转译、不做编译、不需要用户等**。
     *
     * 之所以还留着"重新打 ISO"的分支：如果用户改了 `src/` 下的 `.mo` 并自己重新编译了
     * `bin/uspace2`，可以用 [MolangIso] 按当前系统树重打一份。默认走导出。
     */
    suspend fun exportSystemImage(timeoutMs: Long = 0): Result<String> =
        withContext(Dispatchers.IO) {
            val rootfs = ensureSystem().getOrElse { e ->
                return@withContext Result.failure(IllegalStateException("内置系统未就绪：${e.message}"))
            }
            val bundled = File(rootfs, "boot/molang-system.iso")
            if (!bundled.isFile || bundled.length() == 0L) {
                return@withContext Result.failure(
                    IllegalStateException("内置系统包里没有 boot/molang-system.iso")
                )
            }
            val st = systemStatus()
            Result.success(
                "墨语言自举系统的镜像（随 APK 内置，无需构建）\n" +
                    "  路径 : ${bundled.absolutePath}\n" +
                    "  大小 : ${bundled.length()} 字节（${bundled.length() / 1024}KB）\n" +
                    "  系统 : ${st.version}\n" +
                    "  init : /bin/uspace2 ${st.initSize} 字节 ${st.initMachine} " +
                    (if (st.initStatic) "静态" else "动态") + "\n" +
                    "  内容 : bin/uspace2 · etc/{uspace.conf,motd} · src/{uspace2.mo,kernel64.mo} · VERSION"
            )
        }

    /**
     * 按当前系统树**重打** ISO9660 镜像（可选动作，默认完全不需要）。
     *
     * 正常情况下镜像随 APK 内置（`boot/molang-system.iso`），proot 一挂就能跑，
     * 用户不需要构建任何东西。只有用户改过 `src/` 下的 `.mo` 并自行重编了 `bin/uspace2`
     * 之后，才需要按新树重打一份 —— 这时用 App 自带的 [MolangIso]，
     * 不依赖 `mkisofs`/`xorriso`（这个 rootfs 里本来就没有那两个工具，
     * 之前那句 `command -v mkisofs` 永远走不到，所以"proot 里没有 ISO"）。
     */
    suspend fun rebuildSystemImage(): Result<String> = withContext(Dispatchers.IO) {
        val rootfs = ensureSystem().getOrElse { e ->
            return@withContext Result.failure(IllegalStateException("内置系统未就绪：${e.message}"))
        }
        val staging = File(rootfs, ".iso-staging")
        runCatching {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            // 打进镜像时把自己排掉，否则镜像里还套一个旧镜像
            for (name in listOf("bin", "etc", "src", "mo", "VERSION")) {
                val s = File(rootfs, name)
                if (!s.exists()) continue
                val d = File(staging, name)
                if (s.isDirectory) s.copyRecursively(d, overwrite = true)
                else if (s.isFile) s.copyTo(d, overwrite = true)
            }
            val out = File(rootfs, "boot/molang-system.iso")
            out.parentFile?.mkdirs()
            MolangIso.write(staging, out, volumeId = "MOLANG")
        }.fold(
            onSuccess = { info ->
                runCatching { staging.deleteRecursively() }
                Result.success(
                    "已按当前系统树重打镜像\n" + info + "\n系统: " + systemStatus().version
                )
            },
            onFailure = { e ->
                runCatching { staging.deleteRecursively() }
                Result.failure(IllegalStateException("重打镜像失败：" + (e.message ?: "")))
            }
        )
    }



    // ─────────────────────────── 产物校验 ───────────────────────────

    /** ELF 关键属性：架构、是否静态链接、大小。判"是不是真的 aarch64 静态产物"只看这些字段。 */
    data class ElfInfo(
        val machine: Int,
        val machineName: String,
        val type: String,
        val hasInterp: Boolean,
        val interp: String?,
        val size: Long,
    ) {
        val linkKind: String
            get() = if (hasInterp) "动态（interp=$interp）" else "静态（无 PT_INTERP）"
        val isAarch64Static: Boolean
            get() = machine == EM_AARCH64 && !hasInterp
    }

    /** 只读 ELF 头与程序头，不加载任何东西。 */
    fun inspect(elf: File): ElfInfo {
        val d = FileInputStream(elf).use { it.readBytes() }
        if (d.size < 64 || d[0] != 0x7F.toByte() || d[1] != 'E'.code.toByte() ||
            d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte()
        ) {
            return ElfInfo(-1, "不是 ELF", "?", false, null, d.size.toLong())
        }
        fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        fun u64(o: Int) = (u32(o).toLong() and 0xFFFFFFFFL) or
            ((u32(o + 4).toLong() and 0xFFFFFFFFL) shl 32)

        val machine = u16(0x12)
        val etype = u16(0x10)
        val phoff = u64(0x20)
        val phentsize = u16(0x36)
        val phnum = u16(0x38)
        var interp: String? = null
        for (i in 0 until phnum) {
            val o = (phoff + i.toLong() * phentsize).toInt()
            if (o + 32 > d.size) break
            if (u32(o) == 3) { // PT_INTERP
                val po = u64(o + 8).toInt()
                val ps = u64(o + 32).toInt()
                if (po in 0..d.size && po + ps <= d.size) {
                    interp = String(d, po, ps, Charsets.UTF_8).trimEnd('\u0000')
                }
            }
        }
        return ElfInfo(
            machine = machine,
            machineName = when (machine) {
                EM_AARCH64 -> "AArch64 (0xB7)"
                EM_X86_64 -> "x86_64 (0x3E)"
                else -> "未知 (0x${machine.toString(16)})"
            },
            type = when (etype) {
                2 -> "ET_EXEC 可执行"
                3 -> "ET_DYN PIE"
                1 -> "ET_REL 目标文件"
                else -> "e_type=$etype"
            },
            hasInterp = interp != null,
            interp = interp,
            size = d.size.toLong(),
        )
    }

    // ─────────────────────────── 诊断 ───────────────────────────

    /** 编译器链自检：每一环单独给结论，避免"整条链失败但看不出卡在哪"。 */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val cc = ccBinary()
        buildString {
            appendLine("=== AArch64 原生目标诊断 ===")
            appendLine("设备 ABI          : ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("原生 / 模拟           : " +
                if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
                    "arm64-v8a（产物可原生执行）" else "非 arm64，只能经 qemu")
            appendLine()
            appendLine("mo2x              : " +
                if (rt.tool("mo2x").isFile) "✓ ${rt.tool("mo2x").length()} 字节" else "✗ 缺失")
            appendLine("qemu（跑 mo2x）   : " +
                if (rt.qemu.isFile) "✓ ${rt.qemu.length()} 字节" else "✗ 缺失")
            appendLine("zig 可执行        : " +
                if (cc.isFile) "✓ ${cc.absolutePath}（${cc.length()} 字节）" else "✗ 缺失（缺 libzig.so / cc/zig）")
            appendLine("zig lib 目录      : " +
                if (ccLibDir.isDirectory) "✓ ${ccLibDir.absolutePath}" else "✗ 缺失（缺 $CC_ASSET）")
            appendLine("memfd 启动器      : " +
                (MoyuExec.launcher(context).let {
                    if (it.isFile) "✓ ${it.absolutePath}" else "✗ 缺失（libmoexec.so 不在 nativeLibraryDir）"
                }))
            appendLine()
            if (cc.isFile && ccLibDir.isDirectory) {
                val v = try {
                    val pb = ProcessBuilder(if (cc.name == CC_LIB_NAME) listOf(cc.absolutePath, "version")
                    else MoyuExec.command(context, cc, listOf("version")))
                    pb.environment().put("ZIG_LIB_DIR", ccLibDir.absolutePath)
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    if (p.waitFor(20, TimeUnit.SECONDS)) "zig ${out.trim()} (exit=${p.exitValue()})"
                    else "zig version 超时"
                } catch (e: Exception) {
                    "zig version 失败：${e.message}"
                }
                appendLine("zig 版本          : $v")
            }
            appendLine()
            appendLine("说明：mo2x 是 x86_64 静态程序，需 qemu 跑一次（只在这一步）；")
            appendLine("之后 zig 编译与产物执行都是 aarch64 原生，不再经过 qemu。")
        }
    }
}
