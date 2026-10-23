package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.terminal.moyu.MoyuNative
import com.ai.assistance.quro.terminal.moyu.MoyuPkg
import com.ai.assistance.quro.terminal.moyu.MoyuRuntime
import com.ai.assistance.quro.terminal.moyu.MoyuVerify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * 墨语言（Mo）工具 —— 让 AI 能独立编译、运行、诊断、修复墨语言程序。
 *
 * 关键前提（2026-10-23 实测）：墨语言工具链是 **x86_64 静态可执行**（DT_NEEDED=0），
 * 随包内置的 qemu-x86_64-static 是 **aarch64 静态**，因此整套运行时**不依赖 Ubuntu/proot**，
 * 直接在 App 私有目录里进程调用。用户明确要求「独立运行而不是运行在 Ubuntu」。
 *
 * 覆盖的需求：
 *  · 运行执行开发的包 → action=build_run / run
 *  · AI 直接修复墨语言 bug → action=read / write + compile + run（读源码→改→编译→验证）
 *  · 自带运行和执行环境 → 运行时随 APK 内置，setup 一次性解压即可
 *  · 包管理/工具链扩展 → action=exec 可直接调 mols/modb/molink 等任意工具链程序
 */
class MoyuTool : QuroTool {
    override val name: String = "moyu"

    override val description: String =
        "墨语言（Mo）独立运行时：编译/运行/调试 .mo 程序，读写语言源码以修复 bug，调用内置工具链。" +
            "墨语言是编译到原生机器码的系统级语言，工具链（moc 编译器/molink 链接器/mols 符号工具/" +
            "modb 调试/molink 等）随 APK 内置，经 qemu 在设备上直接运行，**不需要 Ubuntu 或任何 Linux 环境**。" +
            "首次使用先 action=setup（解压运行时，约 10-20 秒），之后可直接 compile/build_run。" +
            "包管理（对应官方 tools/mopkg.sh 协议、但不需要 git）：pkg_init 建项目、dep_install 按 git 地址装依赖、" +
            "dep_fetch 按 mo.toml 拉全部、dep_list/dep_tree/dep_verify；依赖装到 deps/<名字>，编译前自动同步到源目录。" +
            "测试与修复闭环：test 跑内置用例（判定口径与官方 tests/run.sh 一致），aifix 执行「应用补丁→测试通过→撤回补丁→测试必须失败」的硬校验。" +
            "AArch64 原生目标（native_build / native_run / native_targets / cc_diag / native_setup）：" +
            "把 .mo 编成 **ARM64 静态 ELF**（cfs-aarch64 后端 + freestanding C + 内置 zig 编译器），" +
            "产物不链 libc、不进 proot、**不经 qemu 翻译**，由 Android 原生执行 —— 这是墨语言在手机上的最快路径。" +
            "注意它是用户态系统程序，不是 Linux 内核。X86_64 目标仍走 qemu；kern-aarch64 是裸机内核，只能在模拟器里跑。"

    override val parametersJson: String = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作","enum":["setup","compile","run","build_run","exec","list","read","write","diagnose","pkg_init","dep_install","dep_fetch","dep_list","dep_tree","dep_verify","graph","net_diag","test","aifix","resolve","native_build","native_run","native_targets","cc_diag","native_setup"]},
            "path":{"type":"string","description":"compile/run/read 的文件路径（.mo 源码或已编译的 .elf）"},
            "code":{"type":"string","description":"write 时写入的源码内容；配合 name 使用"},
            "name":{"type":"string","description":"write 时的文件名（如 hello.mo）"},
            "tool":{"type":"string","description":"exec 时要调用的工具链程序：moc/molink/mols/modb/dwmo/mo2c/mo2x/seed"},
            "args":{"type":"string","description":"exec 时传给工具的参数，空格分隔"},
            "stdin":{"type":"string","description":"可选：作为程序标准输入"},
            "timeout_seconds":{"type":"number","description":"超时秒数，默认 180"},
            "git":{"type":"string","description":"dep_install 时的 git 仓库地址"},"ref":{"type":"string","description":"dep_install 时的分支或标签，默认 main"},
            "old":{"type":"string","description":"aifix 时要被替换的原文（必须在文件里唯一出现）"},
            "new":{"type":"string","description":"aifix 时的替换文本"},
            "filter":{"type":"string","description":"test/aifix 只跑匹配的用例名（如 01_ 或 errors/），空=全部"},
            "max_cases":{"type":"number","description":"test 最多跑前 N 个用例（快速冒烟），0=不限"},
            "host":{"type":"string","description":"resolve 时的域名或 IPv4（墨语言 lib/net.mo 无 DNS，域名要靠它先解析成 IP 整数）"},
            "target":{"type":"string","description":"native_build 的编译目标：aarch64（默认，Android 原生执行）/ x86_64（经 qemu）/ kern-aarch64（裸机，仅模拟器）"},
            "opt":{"type":"string","description":"native_build 的优化级别：-O2（默认）/-O1/-O0（-O0 更快更省内存）"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = args.optString("action", "").trim().lowercase()
        if (action.isBlank()) return "缺少 action。可用：setup/compile/run/build_run/exec/list/read/write/diagnose"

        val rt = MoyuRuntime.get(context)
        // AArch64 原生目标（产物由 Android 直接执行，不经 qemu）
        val native = MoyuNative(context, rt)
        val timeoutMs = (args.optDouble("timeout_seconds", 180.0).toLong()) * 1000L

        // setup / diagnose / list 不需要运行时已就绪
        when (action) {
            "setup" -> {
                val steps = StringBuilder()
                val r = runBlocking { rt.ensureInstalled { steps.appendLine(it) } }
                return r.fold(
                    onSuccess = {
                        "✅ 墨语言运行时就绪\n${steps.toString().trim()}\n\n${rt.diagnose()}"
                    },
                    onFailure = {
                        "❌ 运行时安装失败：${it.message}\n\n${rt.diagnose()}"
                    }
                )
            }
            "diagnose" -> return rt.diagnose()

            // ── AArch64 原生目标 ──
            "native_targets" -> return buildString {
                appendLine("墨语言可出的编译目标：")
                MoyuNative.Target.entries.forEach { t ->
                    appendLine("· ${t.id}　${t.label}")
                    appendLine("　${t.note}")
                }
                appendLine()
                appendLine("aarch64 产物由 Android 原生 execve 执行，不经过 qemu；")
                appendLine("x86_64 产物仍要 qemu 翻译；kern-aarch64 是裸机内核（无系统调用、输出走 PL011 UART），")
                appendLine("Android 上无法裸机启动，只能用于生成镜像后在模拟器里跑。")
            }

            "cc_diag" -> return runBlocking { native.diagnose() }

            "native_setup" -> {
                val r = runBlocking { native.ensureCc { } }
                return r.fold(
                    onSuccess = { "AArch64 编译器库已就绪\n" + runBlocking { native.diagnose() } },
                    onFailure = { "解压失败：${it.message}\n" + runBlocking { native.diagnose() } }
                )
            }

            "native_build" -> {
                val key = args.optString("path", "").trim().ifBlank { args.optString("name", "").trim() }
                val src = resolveMoyuFile(rt, key)
                    ?: return "找不到源码：${key.ifBlank { "（未提供 path）" }}"
                val tgt = when (args.optString("target", "aarch64").trim().lowercase()) {
                    "x86_64", "x86", "cfs" -> MoyuNative.Target.X86_64
                    "kern", "kernel", "kern-aarch64", "bare" -> MoyuNative.Target.KERNEL
                    else -> MoyuNative.Target.AARCH64
                }
                val opt = args.optString("opt", "-O2").trim().ifBlank { "-O2" }
                val r = runBlocking { native.build(src, tgt, opt) }
                return r.fold(
                    onSuccess = { b ->
                        buildString {
                            appendLine("构建成功")
                            appendLine("目标　${b.target.id} · 用时 ${b.millis / 1000}s")
                            appendLine("架构　${b.info.machineName} · ${b.info.linkKind}")
                            appendLine("产物　${b.elf.name}（${b.info.size} 字节）")
                            appendLine("路径　${b.elf.absolutePath}")
                            appendLine("C 源　${b.cFile.name}")
                            if (b.info.isAarch64Static) {
                                appendLine("校验　AArch64 且无 PT_INTERP：可在 Android 原生执行 ✓")
                            }
                            appendLine()
                            append(b.log)
                        }
                    },
                    onFailure = { "构建失败：${it.message}" }
                )
            }

            "native_run" -> {
                val key = args.optString("path", "").trim()
                    .ifBlank { args.optString("name", "").trim().removeSuffix(".mo") + ".arm64" }
                val f = resolveMoyuFile(rt, key) ?: return "找不到产物：$key"
                val extra = args.optString("args", "").trim()
                    .split(' ').filter { it.isNotBlank() }
                val r = runBlocking { native.runAuto(f, extra, timeoutMs) }
                return buildString {
                    appendLine("退出码 ${r.code}" +
                        (if (r.viaLauncher == true) "（memfd 原生执行，未经 qemu）" else "（直接执行）") +
                        (if (r.timedOut) " · 超时" else ""))
                    if (r.diag) appendLine()
                    append(r.output.trim())
                }
            }
            "list" -> return buildString {
                appendLine("墨语言工具链（mo/bin，全部 x86_64 静态，经 qemu 执行）：")
                MoyuRuntime.TOOLS.forEach { t ->
                    val f = rt.tool(t)
                    appendLine("  ${if (f.exists()) "✓" else "✗"} $t  ${if (f.exists()) "${f.length() / 1024} KB" else "缺失"}")
                }
                appendLine()
                appendLine("运行目录：${rt.root.absolutePath}")
                append("工作区：${rt.workspace.absolutePath}")
            }
        }

        // 包管理动作：需要运行时（下载 + 解压都借用它的 busybox/qemu 环境）
        if (action.startsWith("dep_") || action == "pkg_init" || action == "net_diag") {
            if (!rt.isInstalled()) {
                val r0 = runBlocking { rt.ensureInstalled() }
                if (r0.isFailure) return "墨语言运行时未就绪且自动安装失败：" + (r0.exceptionOrNull()?.message ?: "")
            }
            val pkg = MoyuPkg(context, rt)
            return when (action) {
                "pkg_init" -> pkg.init(args.optString("name", "demo").trim().ifBlank { "demo" })
                "dep_install" -> {
                    val git = args.optString("git", "").trim()
                    if (git.isBlank()) return "dep_install 需要 git 参数，例如 https://github.com/user/mo-lib"
                    val ref = args.optString("ref", "main").trim().ifBlank { "main" }
                    val r = runBlocking { pkg.install(git, ref) }
                    r.fold({ "✅ " + it }, { "❌ 安装失败：" + (it.message ?: "") })
                }
                "dep_fetch" -> {
                    val r = runBlocking { pkg.fetch() }
                    r.fold({ "按 mo.toml 拉取结果：\n" + it }, { "❌ " + (it.message ?: "") })
                }
                "dep_list" -> {
                    val list = pkg.listInstalled()
                    if (list.isEmpty()) "deps/ 为空（还没有安装任何依赖）"
                    else buildString {
                        appendLine("已安装 " + list.size + " 个依赖：")
                        list.forEach { appendLine("  " + it.first.name + "  " + it.second) }
                        append("依赖目录：" + File(rt.workspace, "deps").absolutePath)
                    }
                }
                "dep_tree" -> pkg.tree()
                "dep_verify" -> pkg.verify()
                "net_diag" -> runBlocking { pkg.netDiag() }
                else -> "未知包管理动作：" + action
            }
        }

        // 其余动作要求运行时已就绪
        if (!rt.isInstalled()) {
            val r = runBlocking { rt.ensureInstalled() }
            if (r.isFailure) return "墨语言运行时未就绪且自动安装失败：${r.exceptionOrNull()?.message}\n先用 action=setup 查看详情。"
        }

        when (action) {
            "graph" -> {
                val p = args.optString("path", "").trim()
                if (p.isBlank()) return "graph 需要 path（入口 .mo 文件）"
                val src = File(p)
                if (!src.exists()) return "文件不存在：$p"
                val (order, report) = rt.analyzeGraph(src)
                return report + "\n\n（共 ${order.size} 个文件参与构建）"
            }

            "resolve" -> {
                val host = args.optString("host", args.optString("path", "")).trim()
                if (host.isBlank()) return "resolve 需要 host（域名或 IPv4）"
                return runBlocking { rt.resolveHost(host) }
            }

            "test" -> {
                val filter = args.optString("filter", "").trim()
                val maxCases = args.optDouble("max_cases", 0.0).toInt()
                val v = MoyuVerify(rt)
                val r = runBlocking { v.run(filter, maxCases) }
                return r.log
            }

            "aifix" -> {
                val file = args.optString("path", "").trim()
                val oldT = args.optString("old", "")
                val newT = args.optString("new", "")
                val filter = args.optString("filter", "").trim()
                if (file.isBlank()) return "aifix 需要 path（相对 mo 根目录的路径，如 lib/str.mo）"
                if (oldT.isEmpty() || newT.isEmpty()) return "aifix 需要 old 与 new（原文与新文，需完全一致且旧文唯一）"
                val v = MoyuVerify(rt)
                val r = runBlocking { v.aifix(file, oldT, newT, filter) }
                return r.log
            }

            "compile" -> {
                val p = args.optString("path", "").trim()
                if (p.isBlank()) return "compile 需要 path（.mo 源文件路径）"
                val src = File(p)
                if (!src.exists()) return "源文件不存在：$p"
                val r = runBlocking { rt.compile(src, timeoutMs = timeoutMs) }
                return format("编译 ${src.name}", r.code, r.output, r.timedOut,
                    extra = if (r.ok) "产物：${File(src.parentFile, src.nameWithoutExtension + ".elf").absolutePath}" else null)
            }

            "run" -> {
                val p = args.optString("path", "").trim()
                if (p.isBlank()) return "run 需要 path（可执行的 .elf 或任意 x86_64 程序）"
                val bin = File(p)
                if (!bin.exists()) return "文件不存在：$p"
                val r = runBlocking {
                    rt.exec(bin, bin.parentFile?.let { emptyList() } ?: emptyList(), bin.parentFile, timeoutMs)
                }
                return format("运行 ${bin.name}", r.code, r.output, r.timedOut)
            }

            "build_run" -> {
                val p = args.optString("path", "").trim()
                if (p.isBlank()) {
                    // 允许直接给代码 + 文件名
                    val code = args.optString("code", "")
                    val nm = args.optString("name", "main.mo")
                    if (code.isBlank()) return "build_run 需要 path，或提供 code + name"
                    val src = rt.writeSource(nm, code)
                    val (c, r) = runBlocking { rt.compileAndRun(src, emptyList(), timeoutMs) }
                    return buildString {
                        appendLine("=== 编译 ${src.name} ===")
                        appendLine(format("", c.code, c.output, c.timedOut))
                        appendLine("=== 运行 ===")
                        appendLine(if (r == null) "（编译失败，未运行）" else format("", r.code, r.output, r.timedOut))
                    }.trim()
                }
                val src = File(p)
                if (!src.exists()) return "源文件不存在：$p"
                val (c, r) = runBlocking { rt.compileAndRun(src, emptyList(), timeoutMs) }
                return buildString {
                    appendLine("=== 编译 ${src.name} ===")
                    appendLine(format("", c.code, c.output, c.timedOut))
                    appendLine("=== 运行 ===")
                    appendLine(if (r == null) "（编译失败，未运行）" else format("", r.code, r.output, r.timedOut))
                }.trim()
            }

            "exec" -> {
                val tool = args.optString("tool", "").trim()
                if (tool.isBlank()) return "exec 需要 tool（${MoyuRuntime.TOOLS.joinToString("/")}）"
                if (tool !in MoyuRuntime.TOOLS) return "不支持的工具：$tool。可用：${MoyuRuntime.TOOLS.joinToString("/")}"
                val exe = rt.tool(tool)
                if (!exe.exists()) return "工具缺失：${exe.absolutePath}"
                val a = args.optString("args", "").split(Regex("\\s+")).filter { it.isNotBlank() }
                val r = runBlocking { rt.exec(exe, a, timeoutMs = timeoutMs) }
                return format("$tool ${a.joinToString(" ")}", r.code, r.output, r.timedOut)
            }

            "read" -> {
                val p = args.optString("path", "").trim()
                if (p.isBlank()) return "read 需要 path"
                val f = File(p)
                if (!f.exists()) return "文件不存在：$p"
                val text = runCatching { f.readText() }.getOrElse { return "读取失败：${it.message}" }
                val max = 20000
                return if (text.length <= max) {
                    "=== ${f.absolutePath}（${text.length} 字符）===\n$text"
                } else {
                    "=== ${f.absolutePath}（${text.length} 字符，仅显示前 $max）===\n${text.take(max)}\n…（已截断）"
                }
            }

            "write" -> {
                val nm = args.optString("name", "").trim()
                val code = args.optString("code", "")
                if (nm.isBlank() || code.isBlank()) return "write 需要 name 与 code"
                val target = if (nm.startsWith("/")) File(nm) else File(rt.workspace, nm)
                target.parentFile?.mkdirs()
                return runCatching {
                    target.writeText(code)
                    "✅ 已写入 ${target.absolutePath}（${code.length} 字符）"
                }.getOrElse { "写入失败：${it.message}" }
            }

            else -> return "未知 action：$action"
        }
    }

    private fun format(label: String, code: Int, output: String, timedOut: Boolean, extra: String? = null): String {
        val status = when {
            timedOut -> "⏱ 超时被终止"
            code == 0 -> "✅ 成功（退出码 0）"
            else -> "⚠️ 退出码 $code"
        }
        return buildString {
            if (label.isNotBlank()) appendLine("$label → $status") else appendLine(status)
            if (extra != null) appendLine(extra)
            val body = output.trim()
            if (body.isNotEmpty()) {
                appendLine("--- 输出 ---")
                append(if (body.length > 4000) body.takeLast(4000) + "\n…（输出过长，保留末尾）" else body)
            } else {
                append("（无输出）")
            }
        }.trim()
    }

    /**
     * 解析 AI 传来的路径：绝对路径直接用，相对路径依次在 workspace、mo 语言本体目录里找。
     *
     * 为什么不写死 workspace：AI 常把 examples/uspace2.mo 这种相对路径直接传进来，
     * 只认 workspace 会导致"文件明明在，却说找不到"。
     */
    private fun resolveMoyuFile(rt: MoyuRuntime, p: String): File? {
        if (p.isBlank()) return null
        val f = File(p)
        if (f.isAbsolute) return f.takeIf { it.isFile }
        for (base in listOf(rt.workspace, rt.moHome, File(rt.moHome, "examples"))) {
            val c = File(base, p)
            if (c.isFile) return c
        }
        // 也允许只给名字，在 workspace 里按前缀匹配（AI 常用省略扩展名的写法）
        val guess = rt.workspace.listFiles { g -> g.isFile && g.name.startsWith(p) }
        return guess?.firstOrNull()
    }
}
