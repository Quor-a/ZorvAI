package com.ai.assistance.quro.terminal.moyu

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.terminal.runtime.Distro
import com.ai.assistance.quro.terminal.runtime.GuestExecResult
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 墨语言 ⇢ Ubuntu 融合通道。
 *
 * ## 架构（2026-10-24 定稿，真机实测后重写）
 *
 * ```
 * proot（用户态模拟器，与 Ubuntu 无绑定关系）
 *   └── Ubuntu（aarch64 rootfs，APK 内置）
 *         └── 墨语言工具链 + 翻译器 + zig + 自举系统 + 自举内核，
 *             全部装在 Ubuntu 里（/opt/molang、/opt/zig、/usr/local/bin）
 * ```
 *
 * 「自举」在这里有两层，都在 Ubuntu 内：
 *  - **自举系统**：`uspace2`（AArch64 静态 ELF）+ `molang-system.iso`，
 *    自己在 `/mo` 里长出层次化 VFS（fmkdir + vmkdir_p），不依赖宿主。
 *  - **自举内核**：`kernel64.elf`（ARM64 **裸机** ELF，墨语言写的），
 *    入口 0x40000000、控制台 PL011 UART @0x09000000、无 PT_INTERP、无系统调用。
 *    由 `mo kernel <file.mo>` 在 Ubuntu 内现编（mo2x kern-aarch64 → zig → entry64.S → linker64.ld）。
 *    🔴 它**不能**在 proot/Ubuntu 里跑 —— 它自己就是提供系统调用的那一层；
 *       验证靠 `qemu-system-aarch64 -M virt -kernel`，或读 ELF 头自证结构。
 *
 * ## 为什么从「bind 挂载」改成「装进 rootfs」
 *
 * 真机日志证明：宿主目录 bind 进 guest 这条路在本机 ROM 上不可靠
 * （box64 的 execve ENOENT、/quro/mo_rootfs 700、App 沙箱系统性 ENOSYS）。
 * 而 **增量解压进 rootfs** 是终端环境自己验证过的路（molang-ubuntu-addon 同一套机制）：
 * 文件就在 guest 里，路径、权限、可执行位全部真实存在，不依赖任何 bind 是否被静默跳过。
 *
 * ## 铁律
 *
 * - **绝不整包覆盖解压 rootfs**：`prepareGuestRootfs` 有 180s 硬上限，整包重解必被 SIGKILL。
 *   墨语言的一切都走「增量叠加包」——只写 `/opt/molang`、`/opt/zig`、`/usr/local/bin`、`/etc/profile.d`，
 *   不碰 rootfs 主体，因此是秒级/分钟级，且可重复执行（幂等）。
 * - **不加诊断 UI、不加「构建」按钮**：工具链、编译器、自举系统都在打进 APK 之前准备好，
 *   设备侧只剩「解压落地」。
 * - proot 不是 Ubuntu：换发行版不影响墨语言（通道固定 [Distro.UBUNTU]）。
 */
class MoyuUbuntu(
    private val context: Context,
    private val env: TerminalEnvironment,
    private val rt: MoyuRuntime,
) {

    companion object {
        private const val TAG = "MoyuUbuntu"

        /**
         * 墨语言增量包（tar.gz 内容）：工具链 + qemu 翻译器 + 自举系统 + 包装脚本 + 登录环境。
         *
         * 🔴 **必须用 .pack 后缀，不能用 .tar.gz**：aapt 打包时会把 `.gz` 结尾的资产名
         * 抹掉后缀（实测 APK 里变成 `assets/molang-ubuntu-addon.tar`），
         * 代码再按 `molang-ubuntu-addon.tar.gz` 打开就是 FileNotFoundException。
         * `.pack` 无此行为（mo-cc.pack / mo-pkg.pack 同理）。
         *
         * 内容用 gz 不用 xz：宿主侧纯 Java 解压（[MoyuRuntime.extractTarGz]，GZIPInputStream 是 JDK 自带），
         * 不引第三方库、不依赖 guest 里有没有 xz 二进制。体积差 ~0.7MB，换来零依赖。
         */
        const val ADDON_ASSET = "molang-ubuntu-addon.pack"

        /** zig 编译器资产包（tar.gz，根下是 zig 与 lib/）。 */
        private val CC_ASSET = MoyuNative.CC_ASSET

        /**
         * 构建戳。
         *
         * versionName/versionCode 是锁死的，用户无法从版本号判断手上这个包有没有
         * 「墨语言装进 Ubuntu」这套新通道 —— 界面上必须显示它，装上以后一眼可验。
         * **每次出包必改**（改包不改戳 = 用户装错包白跑一轮）。
         */
        const val BUILD_STAMP = "B20261027-0226"

        /** 安装标记内容（改内置资产字节时必须 +1，否则老设备不重装）。 */
        private const val INSTALL_VERSION = "v=4"

        /** guest 内路径（全部装在 Ubuntu rootfs 里，不是 bind 进来的）。 */
        const val GUEST_MOLANG = "/opt/molang"
        const val GUEST_ZIG = "/opt/zig"
        const val GUEST_BIN = "/usr/local/bin"

        /** 自举内核（ARM64 裸机 ELF，QEMU virt / 真机可启动；proot 内不能跑）。 */
        const val GUEST_KERNEL = "$GUEST_MOLANG/system/boot/kernel64.elf"

        /** 内核构建输入（`mo kernel` 在设备上重建内核用）。 */
        const val GUEST_KERNEL_BOOT = "$GUEST_MOLANG/boot"

        /** 需要补可执行位的 guest 文件（相对 rootfs）。 */
        private val EXEC_TARGETS = listOf(
            "opt/molang/qemu-x86_64-static",
            "opt/molang/bin/moc",
            "opt/molang/bin/mo2x",
            "opt/molang/bin/mo2c",
            "opt/molang/bin/mols",
            "opt/molang/bin/molink",
            "opt/molang/bin/dwmo",
            "opt/molang/bin/modb",
            "opt/molang/bin/seed",
            "opt/molang/bin/lsptest",
            "opt/molang/system/bin/uspace2",
            "opt/zig/zig",
            "usr/local/bin/mo",
        )

        /** guest 内可用的墨语言工具链二进制名（/opt/molang/bin 下，x86_64 静态，经 qemu 翻译）。 */
        private val TOOL_NAMES = listOf(
            "moc", "mo2x", "mo2c", "mols", "molink", "dwmo", "modb", "seed", "lsptest"
        )

        /** 一条自检命令：翻译器、工具链、编译器、自举系统、自举内核各问一句。 */
        private const val PROBE =
            "echo '== mo =='; mo version; " +
                "echo '== qemu =='; /opt/molang/qemu-x86_64-static --version 2>&1 | head -2; " +
                "echo '== zig =='; zig version; " +
                "echo '== uspace2 =='; ls -l /opt/molang/system/bin/uspace2; " +
                "echo '== kernel =='; ls -l $GUEST_KERNEL; " +
                "echo '== kernel build inputs =='; ls -l /opt/molang/boot/entry64.S /opt/molang/boot/linker64.ld; " +
                "echo '== iso =='; ls -l /opt/molang/system/boot/molang-system.iso"
    }

    /** Ubuntu rootfs 目录（墨语言的落点）。 */
    private val rootfs: File get() = env.rootfsDir(Distro.UBUNTU)

    /** 安装标记：rootfs 内 opt/molang/.molang_v。 */
    private val installMark: File get() = File(rootfs, "opt/molang/.molang_v")

    /** 融合状态（宿主侧文件体检，不开 proot，可频繁调用）。 */
    data class Status(
        val ubuntuReady: Boolean,
        val installed: Boolean,
        val pkgReady: Boolean,
        val ccReady: Boolean,
        val systemReady: Boolean,
        val kernelReady: Boolean,
        val adapted: Boolean,
    )

    fun status(): Status {
        val ubuntuReady = env.isRootfsReady(Distro.UBUNTU)
        val pkgReady = File(rootfs, "opt/molang/bin/mo2x").isFile &&
            File(rootfs, "opt/molang/bin/moc").isFile
        val ccReady = File(rootfs, "opt/zig/zig").isFile && File(rootfs, "opt/zig/lib").isDirectory
        val systemReady = File(rootfs, "opt/molang/system/bin/uspace2").isFile
        val kernelReady = File(rootfs, "opt/molang/system/boot/kernel64.elf").isFile &&
            File(rootfs, "opt/molang/boot/entry64.S").isFile &&
            File(rootfs, "opt/molang/boot/linker64.ld").isFile
        val adapted = File(rootfs, "usr/local/bin/mo").isFile &&
            File(rootfs, "etc/profile.d/molang.sh").isFile
        val mark = runCatching { installMark.readText().trim() }.getOrDefault("")
        val installed = mark == INSTALL_VERSION && pkgReady && ccReady &&
            systemReady && kernelReady && adapted
        return Status(ubuntuReady, installed, pkgReady, ccReady, systemReady, kernelReady, adapted)
    }

    /** 安装互斥：首页与原生页可能同时触发 ensure，避免两份解压写坏同一批文件。 */
    private val installMutex = Mutex()

    /**
     * 把墨语言完整融合进 Ubuntu（幂等）。
     *
     * 三步，全部宿主侧文件操作，**不开 proot、不整包重解 rootfs**：
     *  1. Ubuntu 未就绪 → 先解压内置 rootfs（与终端同一条 install_rootfs 链路）；
     *  2. 增量叠加包 → rootfs 根（opt/molang、usr/local/bin、etc/profile.d）；
     *  3. zig 编译器 → rootfs/opt/zig。
     */
    suspend fun ensure(onProgress: (String) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        installMutex.withLock {
            runCatching {
            // ① Ubuntu 是唯一落点：没有它，墨语言无处可融
            if (!env.isRootfsReady(Distro.UBUNTU)) {
                // 宿主侧基础环境（原生库链接、busybox、rootfs 安装包落盘）——幂等，
                // 用户没开过终端就直接走墨语言时也必须先把它拉起来。
                if (!env.initializeEnvironment()) {
                    error("本地终端运行环境初始化失败（原生库未链接？）")
                }
                onProgress("Ubuntu 环境未就绪，正在解压内置 rootfs（首次约 1-3 分钟）…")
                if (!env.prepareGuestRootfs(Distro.UBUNTU)) {
                    error("Ubuntu rootfs 解压失败（见 Logcat：TerminalEnvironment）")
                }
            }
            // 幂等判定：标记版本 + 关键文件都在，才跳过。
            // 🔴 必须查标记：只查文件的话，「换了 addon 字节并 +1 INSTALL_VERSION」不会触发重装，
            // 设备上跑的还是旧工具链（本机制存在的唯一理由就是能强制刷新）。
            val mark = runCatching { installMark.readText().trim() }.getOrDefault("")
            val st = status()
            val stale = mark != INSTALL_VERSION
            val needAddon = stale || !st.pkgReady || !st.systemReady || !st.kernelReady || !st.adapted
            val needCc = stale || !st.ccReady
            if (!needAddon && !needCc) {
                onProgress("墨语言已完整融合在 Ubuntu 内，无需重复安装")
                return@runCatching
            }

            // ② 增量叠加包（约 2.4MB）：工具链 / qemu 翻译器 / 自举系统 / mo 命令 / 登录环境
            if (needAddon) {
                onProgress("正在把墨语言工具链装进 Ubuntu（$GUEST_MOLANG，约 2.4MB）…")
                File(rootfs, "opt").mkdirs()
                File(rootfs, "usr/local/bin").mkdirs()
                File(rootfs, "etc/profile.d").mkdirs()
                val n = context.assets.open(ADDON_ASSET).use { rt.extractTarGz(it, rootfs) }
                if (n < 1) error("增量包解压后没有任何文件落盘（资产可能损坏）")
                Log.i(TAG, "增量包落地：$n 个条目 → ${rootfs.absolutePath}")
            }

            // ③ zig 编译器（约 51MB → 解压后约 150MB）：只在缺它时才解
            if (needCc) {
                onProgress("正在把 AArch64 编译器装进 Ubuntu（$GUEST_ZIG，约 150MB，首次较久）…")
                val base = File(rootfs, "opt/zig").apply { mkdirs() }
                val n = context.assets.open(CC_ASSET).use { rt.extractTarGz(it, base) }
                if (n < 1) error("编译器包解压后没有任何文件落盘（资产可能损坏）")
                Log.i(TAG, "zig 落地：$n 个条目 → ${base.absolutePath}")
            }

            // ④ 可执行位兜底（tar 里带 0755；Android 侧再显式设一次，双保险）
            EXEC_TARGETS.forEach { rel ->
                val f = File(rootfs, rel)
                if (f.isFile) f.setExecutable(true, false)
            }
            // 目录也必须可遍历，否则 guest 里 ls/cd 都进不去
            listOf("opt", "opt/molang", "opt/zig", "usr", "usr/local", "usr/local/bin").forEach { rel ->
                val d = File(rootfs, rel)
                if (d.isDirectory) {
                    d.setReadable(true, false)
                    d.setExecutable(true, false)
                }
            }

            // ⑤ 写标记（rootfs 内 + 宿主镜像各一份）
            installMark.parentFile?.mkdirs()
            installMark.writeText(INSTALL_VERSION + "\n")
            runCatching {
                File(rt.root, "molang-ubuntu-installed")
                    .writeText(INSTALL_VERSION + " @ " + System.currentTimeMillis() + "\n")
            }
            onProgress("墨语言已完整融合进 Ubuntu")
                Unit
            }.onFailure { Log.e(TAG, "融合进 Ubuntu 失败", it) }
        }
    }

    /** [ensure] 的别名（兼容旧调用点）。 */
    suspend fun install(onProgress: (String) -> Unit = {}): Result<Unit> = ensure(onProgress)

    // ─────────────────────────── 编译 / 运行（唯一入口）───────────────────────────

    /**
     * 宿主文件在 guest 内的可见路径。
     *
     * [TerminalEnvironment.execGuest] 把 filesDir 整个 bind 成 guest 的 `/quro`，
     * 所以工作区里的 .mo 不需要拷贝进 rootfs，直接在 guest 里按 `/quro/...` 引用。
     * 返回 null = 文件不在 App 私有目录内（guest 看不见）。
     */
    fun guestPath(hostFile: File): String? {
        val base = env.filesDirectory.canonicalPath.trimEnd('/', '\\')
        val p = hostFile.canonicalPath
        if (!p.startsWith(base + File.separator) && p != base) return null
        return "/quro/" + p.removePrefix(base).trimStart('/', '\\').replace('\\', '/')
    }

    /** 宿主目录在 guest 内的可见路径（同上）。 */
    fun guestDir(hostDir: File): String? = guestPath(hostDir)

    /**
     * guest 内相对路径对应的**宿主文件**（rootfs 里的真实文件，可直接读/列目录）。
     *
     * 资源页（示例/文档/标准库）现在读的是这里，不是宿主 rt.moHome ——
     * 墨语言已完整装进 Ubuntu，guest rootfs 里的 /opt/molang 才是唯一真源。
     * 只是列目录/读文本，不开 proot；要执行仍然走 [runTool]/[runSource]。
     */
    fun rootfsFile(relPath: String): File = File(rootfs, relPath.trimStart('/'))

    /**
     * 编译一个 .mo：`mo build <file>` —— 转译（qemu）+ zig 静态编译，**全程在 Ubuntu 内**。
     *
     * 产物：guest `/tmp/mo-build/<name>`（= 宿主 `filesDir/tmp/mo-build/<name>`，AArch64 静态 ELF）。
     *
     * @param optimize 仅为兼容旧签名保留：Ubuntu 内的 `mo` 主命令固定用 `-O2`
     *   （要换优化级别请改 addon 里的 `/usr/local/bin/mo`，随包分发，不在设备上拼参数）。
     */
    suspend fun buildSource(src: File, optimize: String = "-O2"): Result<String> = runCatching {
        val g = guestPath(src) ?: error("文件不在 App 私有目录内，guest 不可见：${src.absolutePath}")
        if (!src.isFile) error("源文件不存在：${src.absolutePath}")
        val r = exec("mo build $g", 300_000)
        if (r.error != null) error("通道异常：" + r.error)
        val name = src.nameWithoutExtension
        val hostOut = File(env.filesDirectory, "tmp/mo-build/$name")
        buildString {
            appendLine("── mo build $g（墨语言 → cfs-aarch64 → zig 静态编译，全程在 Ubuntu 内）──")
            append(r.output.trim())
            appendLine()
            appendLine("产物（guest）　/tmp/mo-build/$name")
            appendLine("产物（宿主）　${hostOut.absolutePath}" + if (hostOut.isFile) "（${hostOut.length()} 字节）" else "（未找到）")
        }
    }

    /**
     * 编译并运行一个 .mo：`mo <file> [args...]` —— 一条命令从墨语言源码到 ARM64 机器码，
     * 不离开 Ubuntu。这是「墨语言完整运行在 Ubuntu 里」的主路径。
     */
    suspend fun runSource(src: File, args: List<String> = emptyList()): Result<String> = runCatching {
        val g = guestPath(src) ?: error("文件不在 App 私有目录内，guest 不可见：${src.absolutePath}")
        if (!src.isFile) error("源文件不存在：${src.absolutePath}")
        val cmd = if (args.isEmpty()) "mo $g" else "mo $g " + args.joinToString(" ") { shellQuote(it) }
        val r = exec(cmd, 300_000)
        if (r.error != null) error("通道异常：" + r.error)
        buildString {
            appendLine("── $cmd（转译 → zig 静态编译 → Ubuntu 内执行）──")
            append(r.output.trim())
            if (r.timedOut) append("\n（超时被中断）")
        }
    }

    /**
     * 在 Ubuntu 内执行一个墨语言工具链二进制（moc/mols/molink/…）。
     *
     * 工具是 x86_64 静态 ELF，由 `/opt/molang/qemu-x86_64-static`（aarch64 静态）翻译执行；
     * 工作目录默认设成宿主工作区的 guest 路径，保持「相对路径参数」的旧语义。
     */
    suspend fun runTool(
        tool: String,
        args: List<String> = emptyList(),
        workDir: File? = null,
    ): Result<String> = runCatching {
        if (tool !in TOOL_NAMES) error("不支持的工具：$tool。可用：${TOOL_NAMES.joinToString("/")}")
        val wd = guestPath(workDir ?: rt.workspace) ?: "/root"
        val cmd = buildString {
            append("cd ")
            append(shellQuote(wd))
            append(" 2>/dev/null || cd /root; ")
            append("$GUEST_MOLANG/qemu-x86_64-static $GUEST_MOLANG/bin/$tool")
            args.forEach { append(' '); append(shellQuote(it)) }
        }
        val r = exec(cmd, 300_000)
        if (r.error != null) error("通道异常：" + r.error)
        buildString {
            appendLine("── $tool ${args.joinToString(" ")}（qemu 翻译，运行在 Ubuntu 内）──")
            append(r.output.trim())
            if (r.timedOut) append("\n（超时被中断）")
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 在 Ubuntu guest 内执行一段脚本。
     *
     * 与 [TerminalEnvironment.execGuest] 的差别只有两件：
     *  - 发行版**固定** [Distro.UBUNTU]（墨语言融在 Ubuntu 里，不受全局切换影响）；
     *  - 自动导出墨语言环境变量并 source 登录环境（不依赖调用方记得 export）。
     *
     * **不用 extraBinds**：工具链/编译器是装进 rootfs 的真实文件，不需要挂载。
     */
    suspend fun exec(
        script: String,
        timeoutMs: Long = 180_000,
        onOutput: ((String) -> Unit)? = null,
    ): GuestExecResult {
        if (!env.isRootfsReady(Distro.UBUNTU)) {
            return GuestExecResult(false, -1, "", false, "Ubuntu 环境未就绪")
        }
        val full =
            "export MO_HOME=$GUEST_MOLANG MO_LIB=$GUEST_MOLANG/lib " +
                "MO_SYSTEM=$GUEST_MOLANG/system/bin/uspace2; " +
                "export ZIG_GLOBAL_CACHE_DIR=/tmp/zig-cache; " +
                ". /etc/profile.d/molang.sh 2>/dev/null; " +
                script
        return env.execGuest(
            script = full,
            timeoutMs = timeoutMs,
            onOutput = onOutput,
        )
    }

    /** 自检：宿主侧文件体检 + guest 内实测（mo / qemu / zig / uspace2 各问一句）。 */
    suspend fun diagnose(): String {
        val st = status()
        val sb = StringBuilder()
        sb.appendLine("墨语言 ⇢ Ubuntu 融合状态")
        sb.appendLine("  Ubuntu rootfs  " + mark(st.ubuntuReady) + "  " + rootfs.absolutePath)
        sb.appendLine("  安装标记       " + mark(st.installed) + "  " +
            runCatching { installMark.readText().trim() }.getOrDefault("（无）"))
        sb.appendLine("  工具链         " + mark(st.pkgReady) + "  $GUEST_MOLANG/bin")
        sb.appendLine("  zig 编译器     " + mark(st.ccReady) + "  $GUEST_ZIG")
        sb.appendLine("  自举系统       " + mark(st.systemReady) + "  $GUEST_MOLANG/system/bin/uspace2")
        sb.appendLine("  自举内核       " + mark(st.kernelReady) + "  $GUEST_KERNEL")
        sb.appendLine("  mo 命令        " + mark(st.adapted) + "  $GUEST_BIN/mo")
        if (!st.ubuntuReady) {
            sb.appendLine()
            sb.appendLine("Ubuntu 未就绪：点「融合进 Ubuntu」会先解压内置 rootfs。")
            return sb.toString()
        }
        if (!st.installed) {
            sb.appendLine()
            sb.appendLine("尚未安装：点「融合进 Ubuntu」。")
            return sb.toString()
        }
        sb.appendLine()
        sb.appendLine("── guest 实测 ──")
        val r = exec(PROBE, 120_000)
        sb.appendLine(
            if (r.error != null) "通道异常：" + r.error
            else r.output.trim().ifEmpty { "（无输出，退出码 " + r.code + "）" }
        )
        return sb.toString()
    }

    /**
     * 跑一个内置示例：写 /root/hello.mo → `mo` 转译 cfs-aarch64 → zig 静态编译 → 在 Ubuntu 内执行。
     *
     * 这是「完整运行在 Ubuntu 里」的最小证明：一条命令，从墨语言源码到 ARM64 机器码，
     * 全程不离开 guest。
     */
    suspend fun runSample(): Result<String> = runCatching {
        val src = """
            fn main() -> i64 {
                print("Hello from Mo, running inside Ubuntu\n");
                let i: i64 = 1;
                let s: i64 = 0;
                while i <= 10 {
                    s = s + i * i;
                    i = i + 1;
                }
                print("sum of squares 1..10 = ");
                print_i64(s);
                print_nl();
                return 0;
            }
        """.trimIndent()
        val script = """
            cat > /root/hello.mo <<'MOEOF'
            $src
            MOEOF
            echo "[mo] source written:"; wc -l /root/hello.mo
            mo /root/hello.mo
        """.trimIndent()
        val r = exec(script, 300_000)
        if (r.error != null) error("通道异常：" + r.error)
        val out = r.output.trim()
        if (r.code != 0 && out.isEmpty()) error("执行失败（退出码 " + r.code + "）")
        buildString {
            appendLine("── mo /root/hello.mo（转译 → zig → Ubuntu 内执行）──")
            append(out)
            if (r.timedOut) append("\n（超时被中断）")
        }
    }

    /** 进入墨语言自举系统（uspace2）：喂 help + exit，拿到回执就退出，不挂住界面。 */
    suspend fun enterSystem(): Result<String> = runCatching {
        val st = status()
        if (!st.systemReady) error("自举系统未安装（$GUEST_MOLANG/system/bin/uspace2 缺失）")
        val r = exec("printf 'help\\nexit\\n' | mo system", 120_000)
        if (r.error != null) error("通道异常：" + r.error)
        val out = r.output.trim()
        buildString {
            appendLine("── 墨语言自举系统（uspace2，运行在 Ubuntu 内）──")
            append(out.ifEmpty { "（无输出，退出码 " + r.code + "）" })
            if (r.timedOut) append("\n（超时被中断）")
        }
    }

    /** 自举系统镜像/配置信息。 */
    suspend fun imageInfo(): Result<String> = runCatching {
        val st = status()
        if (!st.systemReady) error("自举系统未安装（$GUEST_MOLANG/system/bin/uspace2 缺失）")
        val r = exec(
            "cat $GUEST_MOLANG/system/VERSION 2>/dev/null; " +
                "echo '--- uspace.conf ---'; cat $GUEST_MOLANG/system/etc/uspace.conf 2>/dev/null; " +
                "echo '--- iso ---'; ls -l $GUEST_MOLANG/system/boot/molang-system.iso 2>/dev/null; " +
                "echo '--- kernel ---'; ls -l $GUEST_KERNEL 2>/dev/null; " +
                "echo '--- motd ---'; cat $GUEST_MOLANG/system/etc/motd 2>/dev/null",
            60_000,
        )
        if (r.error != null) error("通道异常：" + r.error)
        r.output.trim().ifEmpty { "（无输出，退出码 " + r.code + "）" }
    }

    // ─────────────────────────── 自举内核 ────────────────────────────

    /**
     * 在 Ubuntu 内构建 ARM64 裸机内核：`mo kernel <file.mo>`。
     *
     * 四步链与官方 `tools/kernel64.sh` 一致，全程不离开 guest：
     *   墨语言 → mo2x kern-aarch64（PL011 UART MMIO 前导）
     *          → zig cc -target aarch64-freestanding
     *          → boot/entry64.S + boot/linker64.ld → 裸机 ELF
     *
     * 🔴 产物**不能**在 proot / Ubuntu 里执行：它没有系统调用、没有 libc、
     *    没有 PT_INTERP，它自己就是提供系统调用的那一层。要验证它只能
     *    （a）`qemu-system-aarch64 -M virt -kernel xxx.elf`，或
     *    （b）看 ELF 结构：machine=0xB7、type=EXEC、entry=0x40000000、无 PT_INTERP。
     *    这里两条都做：构建后立刻在 guest 里读 ELF 头自证。
     *
     * @param src 宿主 .mo 文件（默认用内置的 `examples/kernel64.mo` 副本）
     */
    suspend fun buildKernel(src: File? = null): Result<String> = runCatching {
        val st = status()
        if (!st.installed) error("墨语言尚未融合进 Ubuntu（先点「融合进 Ubuntu」）")
        val target = src ?: File(env.filesDirectory, "moyu/workspace/kernel64.mo")
        val g = if (src != null) {
            guestPath(src) ?: error("文件不在 App 私有目录内，guest 不可见：${src.absolutePath}")
        } else {
            // 没给源文件：用内置示例（增量包里带 examples/kernel64.mo）
            if (!target.isFile) {
                val r0 = exec("cp $GUEST_MOLANG/examples/kernel64.mo /root/kernel64.mo && echo copied", 30_000)
                if (r0.error != null) error("通道异常：" + r0.error)
                "/root/kernel64.mo"
            } else {
                guestPath(target) ?: error("工作区文件不可见：${target.absolutePath}")
            }
        }
        val r = exec("mo kernel $g", 300_000)
        if (r.error != null) error("通道异常：" + r.error)
        val name = File(g).nameWithoutExtension
        val out = "/tmp/mo-build/$name.elf"
        // 构建完当场自证产物结构：不接受「命令成功但产物不对」。
        // 裸机判据：machine=0x00b7(AArch64) · type=0x0002(EXEC) · entry=0x0000000040000000
        // 注意 Kotlin 字符串里的 $ 必须写成 ${'$'}，否则会被当成模板变量。
        val d = "$"
        val probe = exec(
            "F=$out; " +
                "if [ ! -f \"$d F\" ]; then echo 'NO-OUTPUT'; exit 0; fi; " +
                "echo \"size=$(stat -c%s \"$d F\")\"; " +
                "printf 'machine='; od -An -tx1 -j18 -N2 \"$d F\" | tr -d ' \\n'; echo; " +
                "printf 'type='; od -An -tx1 -j16 -N2 \"$d F\" | tr -d ' \\n'; echo; " +
                "printf 'entry='; od -An -tx1 -j24 -N8 \"$d F\" | tr -d ' \\n'; echo; " +
                "echo 'expect machine=b700 type=0200 entry=0000000040000000'",
            60_000,
        )
        buildString {
            appendLine("── mo kernel $g（墨语言 → ARM64 裸机内核，全程在 Ubuntu 内）──")
            append(r.output.trim())
            if (r.timedOut) append("\n（超时被中断）")
            appendLine()
            appendLine("── 产物自证（guest 内读 ELF 头）──")
            appendLine(
                if (probe.error != null) "通道异常：" + probe.error
                else probe.output.trim().ifEmpty { "（无输出）" }
            )
            appendLine()
            appendLine("产物（guest）　$out")
            appendLine("启动方式　　　qemu-system-aarch64 -M virt -kernel $out")
            appendLine("注意　　　　　裸机内核无系统调用/无 libc，proot 与 Ubuntu 内都不能执行。")
        }
    }

    /** 内置自举内核的信息（大小 + ELF 头字段），宿主侧直读，不开 proot。 */
    fun kernelInfo(): String {
        val f = File(rootfs, "opt/molang/system/boot/kernel64.elf")
        if (!f.isFile) return "自举内核未安装（$GUEST_KERNEL 缺失）"
        val d = runCatching { f.readBytes() }.getOrNull() ?: return "读取失败"
        if (d.size < 64 || d.copyOfRange(0, 4).toList() != listOf<Byte>(0x7f, 0x45, 0x4c, 0x46)) {
            return "内置内核不是合法 ELF（${d.size} 字节）—— 资产损坏"
        }
        val eType = ((d[17].toInt() and 0xff) shl 8) or (d[16].toInt() and 0xff)
        val eMachine = ((d[19].toInt() and 0xff) shl 8) or (d[18].toInt() and 0xff)
        val entry = (0..7).fold(0L) { acc, i -> acc or ((d[24 + i].toLong() and 0xff) shl (8 * i)) }
        val machine = when (eMachine) {
            0xB7 -> "AArch64"
            0x3E -> "x86_64"
            else -> "0x" + eMachine.toString(16)
        }
        val type = when (eType) {
            2 -> "EXEC（非 PIE，裸机）"
            3 -> "DYN（PIE）"
            else -> eType.toString()
        }
        val sha = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(d).joinToString("") { "%02x".format(it) }.take(16)
        }.getOrDefault("?")
        return buildString {
            appendLine("自举内核　$GUEST_KERNEL")
            appendLine("大小　　　${d.size} 字节")
            appendLine("架构　　　$machine（0x${eMachine.toString(16)}）")
            appendLine("类型　　　$type")
            appendLine("入口　　　0x${entry.toString(16)}（QEMU virt RAM 基址）")
            appendLine("解释器　　无 PT_INTERP —— 不依赖任何 loader")
            appendLine("SHA-256　$sha…")
            appendLine("控制台　　PL011 UART MMIO 0x09000000")
            appendLine("启动　　　qemu-system-aarch64 -M virt -kernel kernel64.elf")
        }
    }

    private fun mark(ok: Boolean) = if (ok) "[OK]" else "[--]"
}
