package com.ai.assistance.quro.terminal.moyu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 特权 shell 通道抽象。
 *
 * 由 **app 模块**用 Shizuku 实现并注入 [MoyuPrivileged]（terminal-core 不依赖
 * Shizuku，也不想依赖 —— 这条通道是可选增强，缺了就整体降级，不影响墨语言主链路）。
 *
 * 为什么需要它而不是直接 `Runtime.exec`：targetSdk 34 下 **App 私有目录禁止 execve**
 * （SELinux untrusted_app 域），把 filesDir 里的 qemu 交给 ProcessBuilder 会拿到
 * EACCES —— 与 chmod 无关。`/data/local/tmp` + shell 域才是官方推荐的落地方式
 * （`adb push` + `adb shell` 的同一条路）。
 */
interface PrivilegedShell {
    /** Shizuku 是否已授权且服务可用。 */
    val isReady: Boolean

    /** 以 shell/root 身份执行命令，返回合并的 stdout+stderr（无超时保护）。 */
    fun exec(command: String): String

    /** 带超时执行；超时会强杀并回收孙子进程。 */
    fun execTimed(command: String, timeoutMs: Long): String

    /** 以特权身份把字节写入远端 path（父目录自动创建）。返回累计字节数，失败 -1。 */
    fun writeChunk(path: String, data: ByteArray, append: Boolean): Int

    /** 远端文件字节数；不存在 -1。 */
    fun fileSize(path: String): Long

    /** 删除远端文件/目录。 */
    fun remove(path: String): Boolean
}

/**
 * 注入点。app 启动时（Shizuku 就绪后）赋值；未注入时原生通道整体不可用。
 *
 * 用 object 而不是构造函数参数，是为了不改动 [com.ai.assistance.quro.terminal.ui.MoyuScreen]
 * 等一票调用方的签名 —— 这条通道是加出来的，不该让旧调用点全部返工。
 */
object MoyuPrivileged {
    @Volatile
    var shell: PrivilegedShell? = null
}

/**
 * 墨语言 **原生通道**：把工具链复制一份到 `/data/local/tmp`，ARM64 产物原生跑。
 *
 * ## 与 Ubuntu 通道的分工
 *
 * | | Ubuntu 通道（[MoyuUbuntu]） | 原生通道（本类） |
 * |---|---|---|
 * | 落点 | App 沙箱内 rootfs 的 `/opt/molang` | `/data/local/tmp/quro-mo` |
 * | 执行域 | untrusted_app（App 沙箱） | shell / root（Shizuku） |
 * | execve | 受 SELinux 限制，需 memfd+execveat 绕 | 直接 exec，无限制 |
 * | 隔离 | 与 Ubuntu rootfs **共用** `/opt/molang` | **独立复制**，互不影响 |
 * | 产物运行 | proot 内（aarch64→aarch64，无需翻译） | 原生 exec，连 proot 都不经过 |
 *
 * 「是复制不是共用」：本通道从 **APK 资产**重新解一份，不读 Ubuntu rootfs 里那份。
 * 所以删掉/重装 Ubuntu 融合不影响它，反之亦然。
 *
 * ## 为什么产物能原生跑
 *
 * 墨语言的 `cfs-aarch64` 后端产出 **freestanding** 静态 ELF：不链 libc，系统调用用
 * 内联汇编 `svc #0` 直接陷入内核，自带 `_start`。Android 内核就是 Linux，AArch64
 * 系统调用号一致，所以这类产物可以直接在设备上跑 —— 官方 `tools/android_native.sh`
 * 做的就是这件事（`adb push && adb shell`）。
 *
 * 唯一的麻烦是工具链本身（`mo2x` 等）是 **x86_64** 静态 ELF，在 ARM64 上要经
 * `qemu-x86_64-static` 翻译；而 zig 是 aarch64 静态，原生跑。于是：
 * ```
 * mo2x(x86_64) --qemu翻译--> C --zig(aarch64原生)--> aarch64 静态 ELF --原生跑-->
 * ```
 * 只有翻译那一跳是模拟，产物本身零模拟。
 *
 * ## 传输为什么是分块的
 *
 * App 写不进 `/data/local/tmp`，shell 也读不了 App 私有目录，两边 UID 不一致。
 * 唯一不依赖任何额外权限的办法是让特权进程代写（[PrivilegedShell.writeChunk]），
 * 而 Binder 单次事务约 1MB 上限，所以按 [CHUNK] 分块追加。
 */
class MoyuLocal(private val context: Context) {

    companion object {
        private const val TAG = "MoyuLocal"

        /** 远端根目录。`/data/local/tmp` 是 adb push 可执行文件的标准位置。 */
        const val REMOTE_ROOT = "/data/local/tmp/quro-mo"

        /** 远端安装标记（改部署内容时必须 +1，否则老设备不刷新）。 */
        const val NATIVE_VERSION = "v=1"

        /** Binder 单次事务约 1MB，留足头部余量。 */
        const val CHUNK = 512 * 1024

        /** 工具链来源资产（与 Ubuntu 通道同一个包，但是**重新解一份**，不共用）。 */
        const val ASSET_ADDON = "molang-ubuntu-addon.pack"

        /** zig 来源资产。 */
        const val ASSET_CC = "mo-cc.pack"

        /** 远端 runner 脚本。 */
        const val REMOTE_RUNNER = "$REMOTE_ROOT/mo.sh"

        /**
         * 核心层（约 6.4MB，自动部署）：翻译器 + mo2x + 标准库 + 自举系统 + 基准程序。
         *
         * 左侧 = 资产包内成员名，右侧 = 远端相对路径。以 `/` 结尾的按前缀匹配。
         */
        val CORE_FILES: List<Pair<String, String>> = listOf(
            "opt/molang/qemu-x86_64-static" to "qemu-x86_64-static",
            "opt/molang/bin/mo2x" to "bin/mo2x",
            "opt/molang/lib/" to "lib/",
            "opt/molang/system/bin/uspace2" to "system/bin/uspace2",
            "opt/molang/examples/andromo" to "bin/andromo",
            "opt/molang/examples/andromo.mo" to "examples/andromo.mo",
            "opt/molang/VERSION" to "VERSION",
        )

        /** 编译层（约 176MB，按需部署）：zig + lib。 */
        val ZIG_FILES: List<Pair<String, String>> = listOf(
            "zig" to "zig/zig",
            "lib/" to "zig/lib/",
        )

        /** 远端需要可执行位的文件（相对 [REMOTE_ROOT]）。 */
        private val REMOTE_EXEC = listOf(
            "qemu-x86_64-static",
            "bin/mo2x",
            "bin/andromo",
            "system/bin/uspace2",
            "zig/zig",
            "mo.sh",
        )

        /**
         * 远端 runner 脚本模板。
         *
         * 🔴 Kotlin 字符串模板会把 `$` 当变量前缀，而 shell 里满地都是 `$`。
         * 早期写法用 `@@` 占位，但 `"$@"` 会变成 `"@@@"`、`$(...)` 会变成 `@@(...)@@`，
         * 相邻占位符一连就歧义（`@@@@` 到底该切成 `$$` 还是 `$` + `$`？）。
         * 所以改用**不会与 shell 语法碰撞的 `#D#`**：一个 `#D#` 恰好换一个 `$`，
         * 无歧义、可肉眼审。替代在写文件时做一次 `replace("#D#", "$")`。
         */
        /**
         * 远端 runner 脚本模板。
         *
         * 🔴 Kotlin 字符串模板会把 `$` 当变量前缀，而 shell 里满地都是 `$`。
         * 早期写法用 `@@` 占位，但 `"$@"` 会变成 `"@@@"`、`$(...)` 会变成 `@@(...)@@`，
         * 相邻占位符一连就歧义（`@@@@` 到底该切成 `$$` 还是 `$` + `$`？）。
         *
         * 所以改用 `#D#`，并且它是**前缀标记、没有闭合符**：
         * ```
         * #D#ROOT      → $ROOT
         * #D#1         → $1
         * #D#(cat ...) → $(cat ...)
         * #D#@         → $@
         * ```
         * 🔴 千万别在结尾再补一个 `#`（`#D#ROOT#` → `$ROOT#`，shell 里那是注释符，
         * 整行后半截全被吃掉）。老模板犯过这个错，渲染出来 `ROOT=$ROOT#`，
         * `MO_HOME` 直接变空 —— 全通道静默失效。
         *
         * 根目录另有专用占位符 `#R#`（见 [runnerScript]）：`#D#ROOT` 是「读 shell 变量」，
         * 而首行的 `ROOT=` 要的是字面量路径，写成 `#D#ROOT` 会变成 `ROOT=$ROOT`，自引用永假。
         */
        private val RUNNER_TEMPLATE = """
            #!/system/bin/sh
            # 墨语言原生通道 runner —— 由 ZorvAI 生成，勿手改
            # 运行域：shell/root（Shizuku），不经 App 沙箱、不经 proot。
            # 删掉整个 #R# 即可卸载本通道，不影响 Ubuntu 内的墨语言。
            ROOT=#R#
            MO_HOME=#D#ROOT
            MO_LIB=#D#ROOT/lib
            QEMU=#D#ROOT/qemu-x86_64-static
            MO2X=#D#ROOT/bin/mo2x
            ZIG=#D#ROOT/zig/zig
            ZLIB=#D#ROOT/zig/lib
            WORK=#D#ROOT/work
            export MO_HOME MO_LIB
            export ZIG_GLOBAL_CACHE_DIR=#D#ROOT/.zig-cache

            cmd="#D#1"
            shift 2>/dev/null
            case "#D#cmd" in
              version)
                echo "Mo #D#(cat #D#ROOT/VERSION 2>/dev/null)"
                echo "root   #D#ROOT"
                echo "runner #D#QEMU"
                echo "zig    #D#(#D#ZIG version 2>/dev/null || echo '(未部署编译层)')"
                ;;
              bench)
                # 预编译的安卓原生示例：pi(n) 计数，默认 200000（= 17984）
                N="#D#1"
                [ -z "#D#N" ] && N=200000
                exec #D#ROOT/bin/andromo "#D#N"
                ;;
              run)
                # run <远端程序> [args...]
                exec "#D#@"
                ;;
              system)
                exec #D#ROOT/system/bin/uspace2
                ;;
              xlate)
                # xlate <远端 .mo> -> stdout C
                S="#D#1"
                ( cd "#D#(dirname "#D#S")"
                  #D#QEMU #D#MO2X "#D#(basename "#D#S")" cfs-aarch64 )
                ;;
              build)
                # build <远端 .mo> [输出路径] -> 打印产物路径
                S="#D#1"
                OUT="#D#2"
                [ -z "#D#OUT" ] && OUT=#D#WORK/#D#(basename "#D#S" .mo)
                mkdir -p #D#WORK
                ( cd "#D#(dirname "#D#S")"
                  #D#QEMU #D#MO2X "#D#(basename "#D#S")" cfs-aarch64 ) > #D#WORK/x.c || exit 1
                # -nostdlib：后端自带 _start，与 musl 的 crt 冲突
                # -fno-builtin：否则编译器把手写循环换成 strlen/memset，freestanding
                #              产物不链 libc，那些符号到链接期才炸（静默，且报错指链接）
                #D#ZIG cc -target aarch64-linux-musl -static -nostdlib -fno-builtin -O2 \
                  --zig-lib-dir #D#ZLIB -o "#D#OUT" #D#WORK/x.c 2>&1 | head -20
                [ -f "#D#OUT" ] || { echo "build failed" >&2; exit 1; }
                echo "#D#OUT"
                ;;
              *)
                echo "usage: mo.sh {version|bench [n]|run <bin> [args]|system|xlate <f.mo>|build <f.mo> [out]}" >&2
                exit 1
                ;;
            esac
        """.trimIndent()

        /**
         * runner 脚本落盘内容。
         *
         * 两个占位符分工：`#D#` → 一个 `$`（shell 变量/替换），`#R#` → 远端根目录字面量。
         * 🔴 别把根目录也写成 `#D#ROOT#`：那会变成 `ROOT=$ROOT`，自引用，永假。
         */
        fun runnerScript(): String =
            RUNNER_TEMPLATE.replace("#D#", "$").replace("#R#", REMOTE_ROOT)
    }

    /** 本地暂存目录（App 私有，仅作传输中转）。 */
    private val stage: File get() = File(context.cacheDir, "quro-native-stage").apply { mkdirs() }

    private val shell: PrivilegedShell? get() = MoyuPrivileged.shell

    // ══════════════════════════════════════════════════════════════
    // 状态
    // ══════════════════════════════════════════════════════════════

    data class Status(
        val shellReady: Boolean,
        val coreReady: Boolean,
        val benchReady: Boolean,
        val zigReady: Boolean,
        val remoteBytes: Long,
    ) {
        val deployed: Boolean get() = coreReady
        val usable: Boolean get() = shellReady && coreReady
    }

    /** 只读远端状态（不传文件，可频繁调用）。 */
    fun status(): Status {
        val sh = shell
        if (sh == null || !sh.isReady) {
            return Status(false, false, false, false, 0L)
        }
        val mark = sh.fileSize("$REMOTE_ROOT/.quro-native-v") > 0
        val qemu = sh.fileSize("$REMOTE_ROOT/qemu-x86_64-static")
        val mo2x = sh.fileSize("$REMOTE_ROOT/bin/mo2x")
        val lib = sh.fileSize("$REMOTE_ROOT/lib/io.mo")
        val uspace = sh.fileSize("$REMOTE_ROOT/system/bin/uspace2")
        val bench = sh.fileSize("$REMOTE_ROOT/bin/andromo")
        val zig = sh.fileSize("$REMOTE_ROOT/zig/zig")
        val coreReady = mark && qemu > 0 && mo2x > 0 && lib > 0 && uspace > 0
        val total = listOf(qemu, mo2x, lib, uspace, bench, zig).filter { it > 0 }.sum()
        return Status(
            shellReady = true,
            coreReady = coreReady,
            benchReady = bench > 0,
            zigReady = zig > 0,
            remoteBytes = total,
        )
    }

    /** 给人看的一行状态（UI / 工具输出共用）。 */
    fun describe(): String {
        val st = status()
        if (!st.shellReady) return "Shizuku 未就绪（原生通道不可用）"
        return buildString {
            append("远端 $REMOTE_ROOT")
            append(if (st.coreReady) " · 核心层就绪" else " · 核心层未部署")
            append(if (st.benchReady) " · 基准程序在" else " · 基准程序缺")
            append(if (st.zigReady) " · 编译层(zig)在" else " · 编译层(zig)未部署")
            append(" · 共 ${st.remoteBytes / 1024} KB")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 部署
    // ══════════════════════════════════════════════════════════════

    /**
     * 部署核心层（约 6.4MB）。幂等：已部署则直接返回。
     *
     * @param onProgress 进度回调（已是人话，可直接上屏）
     */
    suspend fun deployCore(onProgress: (String) -> Unit = {}): Result<Status> =
        withContext(Dispatchers.IO) {
            deploy("核心层（约 6.4MB）", ASSET_ADDON, CORE_FILES, onProgress)
        }

    /**
     * 部署编译层（zig + lib，约 176MB）。按需触发，**必须由用户显式确认**，
     * 因为它又大又慢（分块传输几十秒）。
     */
    suspend fun deployZig(onProgress: (String) -> Unit = {}): Result<Status> =
        withContext(Dispatchers.IO) {
            deploy("编译层（约 176MB）", ASSET_CC, ZIG_FILES, onProgress)
        }

    private suspend fun deploy(
        label: String,
        asset: String,
        wanted: List<Pair<String, String>>,
        onProgress: (String) -> Unit,
    ): Result<Status> = withContext(Dispatchers.IO) {
        val sh = shell ?: return@withContext Result.failure(
            IllegalStateException("Shizuku 未就绪：请到 系统权限 → L2 Shizuku 请求授权")
        )
        runCatching {
            val cur = status()
            if (label.startsWith("核心层") && cur.coreReady) {
                onProgress("原生通道已就绪（$REMOTE_ROOT）")
                return@runCatching cur
            }
            if (label.startsWith("编译层") && cur.zigReady) {
                onProgress("编译层已在位")
                return@runCatching cur
            }

            onProgress("建立远端目录 …")
            sh.exec(
                "mkdir -p $REMOTE_ROOT/bin $REMOTE_ROOT/lib $REMOTE_ROOT/system/bin " +
                    "$REMOTE_ROOT/examples $REMOTE_ROOT/work"
            )

            onProgress("从 APK 资产解出$label（独立复制，不读 Ubuntu 那份）…")
            val picked = stageFromAsset(asset, wanted)

            var done = 0L
            val total = picked.sumOf { it.first.length() }
            for ((local, rel) in picked) {
                pushFile(sh, local, rel) { sent ->
                    val pct = if (total > 0) ((done + sent) * 100 / total).toInt() else 0
                    onProgress("传输 $rel … $pct%")
                }
                done += local.length()
            }

            onProgress("写 runner 脚本与安装标记 …")
            writeRemoteText(sh, REMOTE_RUNNER, runnerScript())
            writeRemoteText(sh, "$REMOTE_ROOT/.quro-native-v", NATIVE_VERSION + "\n")
            writeRemoteText(
                sh,
                "$REMOTE_ROOT/README.txt",
                "墨语言原生通道（ZorvAI 生成）。\n" +
                    "运行域：shell/root（Shizuku），不经 App 沙箱、不经 proot。\n" +
                    "与 Ubuntu 通道相互独立，删除本目录不影响 Ubuntu 内的墨语言。\n" +
                    "用法：sh $REMOTE_RUNNER {version|bench|run|system|xlate|build}\n",
            )
            chmodRemote(sh)

            val after = status()
            if (label.startsWith("核心层") && !after.coreReady) {
                error("部署后自检失败：远端文件不完整")
            }
            if (label.startsWith("编译层") && !after.zigReady) {
                error("编译层部署后自检失败")
            }
            onProgress("$label 就绪：${after.remoteBytes / 1024} KB 已落盘")
            after
        }.onFailure { Log.e(TAG, "deploy($label) 失败", it) }
    }

    /** 清掉远端整个目录（不影响 Ubuntu 通道）。 */
    suspend fun cleanup(): Result<Unit> = withContext(Dispatchers.IO) {
        val sh = shell ?: return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
        runCatching {
            if (!sh.remove(REMOTE_ROOT)) error("rm -rf $REMOTE_ROOT 失败")
            stage.deleteRecursively()   // 本地暂存也清掉，别占缓存
            Unit
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 运行
    // ══════════════════════════════════════════════════════════════

    /**
     * 跑基准：预编译的 andromo（π 计数），返回带用时的输出。
     *
     * 这个数字是「ARM64 产物原生跑」的直接证据 —— 不经过 proot、不经过翻译，
     * 就是内核直接调度一个 AArch64 静态 ELF。真机上 π(200000) 约几十毫秒。
     *
     * @param n 筛法上限，默认 200000（π = 17984，已知值）
     */
    suspend fun benchmark(n: Int = 200_000, timeoutMs: Long = 120_000): Result<String> =
        withContext(Dispatchers.IO) {
            val sh = shell ?: return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
            if (!status().benchReady) {
                return@withContext Result.failure(IllegalStateException("基准程序未部署，请先部署原生通道"))
            }
            runCatching {
            // 计时在 shell 侧做（date +%s%N），避免把 Binder 往返算进去。
            // 🔴 Kotlin 字符串模板会把 $ 当变量前缀，这里沿用 runner 的 #D# 占位，
            //    写完再一次性替换；#P#/#N# 分别代表 runner 路径与筛法上限。
            val cmd = ("T0=#D#(date +%s%N 2>/dev/null); " +
                "out=#D#(sh #P# bench #N# 2>&1); rc=#D#?; " +
                "T1=#D#(date +%s%N 2>/dev/null); " +
                "echo \"#D#out\"; echo '----'; echo \"exit=#D#rc\"; " +
                "if [ -n \"#D#T0\" ] && [ -n \"#D#T1\" ] && [ \"#D#T0\" != \"#D#T1\" ]; then " +
                "echo \"wall=#D#(( (T1 - T0) / 1000000 )) ms (shell 计时，含进程创建)\"; " +
                "else echo 'wall=(date 不支持 %N)'; fi")
                .replace("#D#", "$")
                .replace("#P#", REMOTE_RUNNER)
                .replace("#N#", n.toString())
            val r = sh.execTimed(cmd, timeoutMs)
            if (r.contains("exit=timeout")) error("基准超时（${timeoutMs}ms）\n$r")
            r
        }
    }

    /** 运行远端已部署的程序（原生 exec）。 */
    suspend fun runArtifact(
        relPath: String,
        args: String = "",
        timeoutMs: Long = 120_000,
    ): Result<String> = withContext(Dispatchers.IO) {
        val sh = shell ?: return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
        runCatching {
            val cmd = "sh $REMOTE_RUNNER run $relPath $args"
            val r = sh.execTimed(cmd, timeoutMs)
            if (r.contains("exit=timeout")) error("执行超时（${timeoutMs}ms）\n$r")
            r
        }
    }

    /** 进入墨语言自举系统 uspace2（原生跑，不经过 proot）。 */
    suspend fun enterSystem(
        input: String = "help\nexit\n",
        timeoutMs: Long = 60_000,
    ): Result<String> = withContext(Dispatchers.IO) {
        val sh = shell ?: return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
        runCatching {
            // exec 型接口没有 stdin，用 printf 灌一段输入进去
            val safe = input.replace("'", "'\\''")
            val r = sh.execTimed("printf '%s' '$safe' | sh $REMOTE_RUNNER system", timeoutMs)
            if (r.contains("exit=timeout")) error("执行超时（${timeoutMs}ms）\n$r")
            r
        }
    }

    /**
     * 在设备上把 .mo 编译成 ARM64 原生 ELF 并运行。
     *
     * 需要编译层（zig）。源码先从宿主推进远端 `work/`，再走官方
     * `tools/android_native.sh` 的同一条配方（见 runner 的 build 分支注释）。
     */
    suspend fun compileAndRun(
        source: File,
        args: String = "",
        timeoutMs: Long = 300_000,
    ): Result<String> = withContext(Dispatchers.IO) {
        val sh = shell ?: return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
        if (!source.isFile) {
            return@withContext Result.failure(IllegalStateException("源文件不存在: ${source.absolutePath}"))
        }
        runCatching {
            if (!status().coreReady) deployCore {}.getOrElse { throw it }
            if (!status().zigReady) {
                error("编译层（zig，约 176MB）未部署：它很大，请先在界面上显式确认后再传")
            }
            val remoteSrc = "$REMOTE_ROOT/work/${source.name}"
            pushFile(sh, source, remoteSrc.removePrefix("$REMOTE_ROOT/"))
            val r = sh.execTimed("sh $REMOTE_RUNNER build $remoteSrc $args", timeoutMs)
            if (r.contains("exit=timeout")) error("编译超时（${timeoutMs}ms）\n$r")
            r
        }
    }

    /** 只翻译不编译（不需要 zig），返回 C 源码。用于快速验证 mo2x 通路。 */
    suspend fun translate(
        source: File,
        timeoutMs: Long = 120_000,
    ): Result<String> = withContext(Dispatchers.IO) {
        val sh = shell ?: return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
        if (!source.isFile) {
            return@withContext Result.failure(IllegalStateException("源文件不存在"))
        }
        runCatching {
            if (!status().coreReady) deployCore {}.getOrElse { throw it }
            val remoteSrc = "$REMOTE_ROOT/work/${source.name}"
            pushFile(sh, source, remoteSrc.removePrefix("$REMOTE_ROOT/"))
            val r = sh.execTimed("sh $REMOTE_RUNNER xlate $remoteSrc", timeoutMs)
            if (r.contains("exit=timeout")) error("翻译超时（${timeoutMs}ms）\n$r")
            r
        }
    }

    /** 远端自检：一次问清翻译器、产物、自举系统、zig 四件事。 */
    suspend fun probe(timeoutMs: Long = 60_000): Result<String> = withContext(Dispatchers.IO) {
        val sh = shell ?: return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
        runCatching {
            val cmd = "sh $REMOTE_RUNNER version; " +
                "echo '== bench =='; ls -l $REMOTE_ROOT/bin/andromo 2>/dev/null || echo '(缺)'; " +
                "echo '== uspace2 =='; ls -l $REMOTE_ROOT/system/bin/uspace2 2>/dev/null || echo '(缺)'; " +
                "echo '== zig =='; ls -l $REMOTE_ROOT/zig/zig 2>/dev/null || echo '(未部署编译层)'; " +
                "echo '== mo2x =='; " +
                "$REMOTE_ROOT/qemu-x86_64-static $REMOTE_ROOT/bin/mo2x 2>&1 | head -3"
            val r = sh.execTimed(cmd, timeoutMs)
            if (r.contains("exit=timeout")) error("自检超时")
            r
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 内部：资产解包 / 分块传输
    // ══════════════════════════════════════════════════════════════

    /**
     * 从 APK 资产里取出一个 `.mo` 源文件到本地暂存区，供 [compileAndRun] / [translate] 用。
     *
     * 例如 `"examples/andromo.mo"`。取不到返回 null（资产里没有这个成员）。
     *
     * 注意：解出来的只是「源」，跟 Ubuntu 里那份 `examples/andromo.mo` 是同一个字节，
     * 但走的是本通道自己读 APK 资产的路径 —— 这条通道从头到尾不碰 Ubuntu rootfs。
     */
    fun sourceFromAsset(relPath: String): File? {
        val name = File(relPath).name
        val picked = stageFromAsset(
            ASSET_ADDON,
            listOf("opt/molang/$relPath" to "src/$name"),
        )
        return picked.firstOrNull()?.first
    }

    /**
     * 从 APK 资产里**选择性**解出文件到本地暂存区。
     *
     * 为什么不整包解：`mo-cc.pack` 解压后约 176MB，整包落缓存既慢又可能撞配额。
     * 这里按白名单流式读，只落要的。
     *
     * @return (本地暂存文件, 远端相对路径) 列表
     */
    private fun stageFromAsset(
        asset: String,
        wanted: List<Pair<String, String>>,
    ): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        val prefixes = wanted.filter { it.first.endsWith("/") }
            .map { it.first.removeSuffix("/") to it.second.removeSuffix("/") }
        val exact = wanted.filterNot { it.first.endsWith("/") }.toMap()

        context.assets.open(asset).use { raw ->
            val gz = GZIPInputStream(raw, 256 * 1024)
            val header = ByteArray(512)
            var pendingLongName: String? = null
            while (readFully(gz, header)) {
                if (header.all { it.toInt() == 0 }) break
                val typeFlag = header[156].toInt().toChar()
                var name = tarString(header, 0, 100)
                val prefix = tarString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                name = name.trim()

                // GNU 长名约定：typeflag 'L' 的块内容是下一个条目的完整路径
                if (typeFlag == 'L') {
                    val size = tarOctal(header, 124, 12).toInt()
                    val buf = ByteArray(size)
                    if (!readFully(gz, buf)) error("资产 $asset 损坏：长名块不完整")
                    pendingLongName = String(buf, Charsets.US_ASCII).trimEnd('\u0000')
                    skipPadding(gz, size)
                    continue
                }
                if (typeFlag == 'x' || typeFlag == 'g') {
                    skipPadding(gz, tarOctal(header, 124, 12).toInt())
                    continue
                }
                val full = pendingLongName ?: name
                pendingLongName = null
                if (typeFlag == '5') continue              // 目录，size 恒 0
                if (typeFlag != '0' && typeFlag != '\u0000') {
                    skipPadding(gz, tarOctal(header, 124, 12).toInt())
                    continue
                }

                val size = tarOctal(header, 124, 12)
                var rel: String? = exact[full]
                if (rel == null) {
                    for ((p, target) in prefixes) {
                        if (full.startsWith("$p/")) {
                            rel = "$target/${full.removePrefix("$p/")}"
                            break
                        }
                    }
                }
                if (rel == null) {
                    skipPadding(gz, size.toInt())
                    continue
                }
                val dest = File(stage, rel.replace('/', File.separatorChar))
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { fos ->
                    var left = size
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) {
                        val want = if (left < buf.size) left.toInt() else buf.size
                        val got = gz.read(buf, 0, want)
                        if (got <= 0) error("资产 $asset 损坏：$full 数据不足")
                        fos.write(buf, 0, got)
                        left -= got
                    }
                }
                skipPadding(gz, size.toInt())
                out += dest to rel
            }
        }
        if (out.isEmpty()) error("资产 $asset 里没解出任何目标文件（白名单写错了？）")
        return out
    }

    /**
     * 分块把本地文件推到你端路径。
     *
     * 🔴 必须流式读，不能 `readBytes()`：zig 单体 141MB，一次性读进内存必 OOM。
     */
    private fun pushFile(
        sh: PrivilegedShell,
        local: File,
        rel: String,
        onChunk: (Long) -> Unit = {},
    ) {
        val remote = if (rel.startsWith("/")) rel else "$REMOTE_ROOT/$rel"
        val total = local.length()
        var sent = 0L
        var reported = 0L
        local.inputStream().use { ins ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val want = minOf(CHUNK.toLong(), total - sent).toInt()
                if (want <= 0) break
                val got = readUpTo(ins, buf, want)
                if (got <= 0) break
                val n = sh.writeChunk(remote, if (got == buf.size) buf else buf.copyOf(got), sent > 0)
                if (n < 0) error("写入远端失败: $remote")
                sent += got
                if (sent - reported >= 256 * 1024 || sent >= total) {
                    reported = sent
                    onChunk(sent)
                }
            }
        }
        if (total == 0L) sh.writeChunk(remote, ByteArray(0), false)
        if (sent != total) {
            Log.w(TAG, "体积不一致 $remote: 本地 $total 实际推 $sent")
        }
    }

    private fun writeRemoteText(sh: PrivilegedShell, remote: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var off = 0
        while (off < bytes.size) {
            val len = minOf(CHUNK, bytes.size - off)
            val n = sh.writeChunk(remote, bytes.copyOfRange(off, off + len), off > 0)
            if (n < 0) error("写远端文本失败: $remote")
            off += len
        }
    }

    private fun chmodRemote(sh: PrivilegedShell) {
        val list = REMOTE_EXEC.joinToString(" ") { "$REMOTE_ROOT/$it" }
        sh.exec(
            "chmod 755 $list 2>/dev/null; " +
                "chmod 755 $REMOTE_ROOT $REMOTE_ROOT/bin $REMOTE_ROOT/lib " +
                "$REMOTE_ROOT/system $REMOTE_ROOT/system/bin $REMOTE_ROOT/examples " +
                "$REMOTE_ROOT/work 2>/dev/null; " +
                "find $REMOTE_ROOT/lib -type f -name '*.mo' -exec chmod 644 {} + 2>/dev/null; " +
                "true"
        )
    }

    // ── tar 读取小工具（与 MoyuRuntime.extractTarGz 同一套约定）──

    /** 读满 buf；干净 EOF（一个字节都没读到）返回 false，读到一半 EOF 视为损坏抛异常。 */
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                error("流意外结束：还差 ${buf.size - off} 字节")
            }
            off += n
        }
        return true
    }

    private fun readUpTo(input: InputStream, buf: ByteArray, want: Int): Int =
        input.read(buf, 0, want)

    private fun skipPadding(input: InputStream, size: Int) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0 && !readFully(input, ByteArray(pad))) {
            error("流意外结束：跳过补齐字节失败")
        }
    }

    private fun tarString(header: ByteArray, off: Int, len: Int): String {
        val end = minOf(off + len, header.size)
        var e = off
        while (e < end && header[e].toInt() != 0) e++
        return String(header, off, e - off, Charsets.US_ASCII).trim()
    }

    private fun tarOctal(header: ByteArray, off: Int, len: Int): Long {
        val s = tarString(header, off, len)
        if (s.isEmpty()) return 0
        return runCatching { s.toLong(8) }.getOrDefault(0L)
    }
}
