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
 *       aarch64 的系统调用号与 Linux 一致 —— 产物可以直接 execve，**零翻译、零 libc、零 Ubuntu**。
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
            "AArch64 原生",
            "Android 直接执行 · 零翻译 · 不链 libc",
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
                val pb = ProcessBuilder(
                    MoyuExec.command(context, rt.qemu, listOf(mo2x.absolutePath, src.name, target.id))
                )
                pb.directory(dir)
                pb.redirectErrorStream(false)
                pb.redirectOutput(out) // stdout 就是 C 源码
                pb.environment().putAll(
                    MoyuExec.baseEnv(context) + mapOf(
                        "MO_HOME" to rt.moHome.absolutePath,
                        "QEMU_STACK_SIZE" to (8 * 1024 * 1024).toString(),
                    )
                )
                val p = pb.start()
                val err = p.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val done = p.waitFor(180_000, TimeUnit.MILLISECONDS)
                if (!done) {
                    p.destroyForcibly()
                    return@withContext Result.failure(IllegalStateException("mo2x 超时（180s）"))
                }
                val code = p.exitValue()
                if (code != 0 || !out.isFile || out.length() == 0L) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "mo2x 转译失败（exit=$code）\n" + err.trim().take(1500)
                        )
                    )
                }
                Result.success(TranspileResult(out, out.length(), err.trim()))
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
        if (!isCcReady()) {
            val r = ensureCc()
            if (r.isFailure) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "AArch64 编译器未就绪：缺少 $CC_ASSET（或 libzig.so）。\n" +
                            "诊断：${diagnose()}"
                    )
                )
            }
        }
        val t0 = System.currentTimeMillis()
        val tr = transpile(src, target)
        tr.fold(
            onSuccess = { t ->
                val elf = File(t.cFile.parentFile, src.nameWithoutExtension + ".arm64")
                elf.delete()
                val ztarget = when (target) {
                    Target.AARCH64 -> "aarch64-linux-musl"
                    Target.X86_64 -> "x86_64-linux-musl"
                    Target.KERNEL -> "aarch64-freestanding"
                }
                val args = buildList {
                    add("cc")
                    add("-target"); add(ztarget)
                    if (target != Target.KERNEL) add("-static")
                    add("-nostdlib")
                    add("-fno-builtin")
                    add(optimize)
                    if (target == Target.KERNEL) add("-mgeneral-regs-only")
                    add("-o"); add(elf.absolutePath)
                    add(t.cFile.absolutePath)
                }
                val cc = ccBinary()
                val cmd = if (cc.name == CC_LIB_NAME) {
                    // zig 在 nativeLibraryDir，直接执行即可
                    listOf(cc.absolutePath) + args
                } else {
                    MoyuExec.command(context, cc, args)
                }
                try {
                    val pb = ProcessBuilder(cmd)
                    pb.directory(t.cFile.parentFile)
                    pb.redirectErrorStream(true)
                    pb.environment().putAll(
                        MoyuExec.baseEnv(context) + mapOf(
                            // zig 必须能找到自己的 lib，且缓存要写在可写目录
                            "ZIG_LIB_DIR" to ccLibDir.absolutePath,
                            "ZIG_GLOBAL_CACHE_DIR" to File(rt.root, "cc/cache-global")
                                .apply { mkdirs() }.absolutePath,
                            "ZIG_LOCAL_CACHE_DIR" to File(rt.root, "cc/cache-local")
                                .apply { mkdirs() }.absolutePath,
                        )
                    )
                    val p = pb.start()
                    val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!done) {
                        p.destroyForcibly()
                        return@withContext Result.failure(
                            IllegalStateException("C 编译超时（${timeoutMs / 1000}s）。大程序请分片或改用 -O0。")
                        )
                    }
                    val code = p.exitValue()
                    if (code != 0 || !elf.isFile) {
                        return@withContext Result.failure(
                            IllegalStateException("C 编译失败（exit=$code）\n" + out.trim().takeLast(2000))
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
                } catch (e: Exception) {
                    Log.e(TAG, "zig cc 失败", e)
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
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
            MoyuExec.run(
                context = context,
                binary = elf,
                args = args,
                workDir = elf.parentFile,
                timeoutMs = timeoutMs,
            )
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
