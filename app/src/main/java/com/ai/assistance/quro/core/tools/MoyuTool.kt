package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.terminal.moyu.MoyuPkg
import com.ai.assistance.quro.terminal.moyu.MoyuRuntime
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
            "dep_fetch 按 mo.toml 拉全部、dep_list/dep_tree/dep_verify；依赖装到 deps/<名字>，编译时自动 -I。"

    override val parametersJson: String = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作","enum":["setup","compile","run","build_run","exec","list","read","write","diagnose","pkg_init","dep_install","dep_fetch","dep_list","dep_tree","dep_verify","graph","net_diag"]},
            "path":{"type":"string","description":"compile/run/read 的文件路径（.mo 源码或已编译的 .elf）"},
            "code":{"type":"string","description":"write 时写入的源码内容；配合 name 使用"},
            "name":{"type":"string","description":"write 时的文件名（如 hello.mo）"},
            "tool":{"type":"string","description":"exec 时要调用的工具链程序：moc/molink/mols/modb/dwmo/mo2c/mo2x/seed"},
            "args":{"type":"string","description":"exec 时传给工具的参数，空格分隔"},
            "stdin":{"type":"string","description":"可选：作为程序标准输入"},
            "timeout_seconds":{"type":"number","description":"超时秒数，默认 180"},
            "git":{"type":"string","description":"dep_install 时的 git 仓库地址"},"ref":{"type":"string","description":"dep_install 时的分支或标签，默认 main"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = args.optString("action", "").trim().lowercase()
        if (action.isBlank()) return "缺少 action。可用：setup/compile/run/build_run/exec/list/read/write/diagnose"

        val rt = MoyuRuntime.get(context)
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
}
