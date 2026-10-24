package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.terminal.moyu.MoyuLocal
import com.ai.assistance.quro.terminal.moyu.MoyuNative
import com.ai.assistance.quro.terminal.moyu.MoyuPkg
import com.ai.assistance.quro.terminal.moyu.MoyuRuntime
import com.ai.assistance.quro.terminal.moyu.MoyuVerify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * 墨语言（Mo）工具 —— 让 AI 能编译、运行、诊断、修复墨语言程序。
 *
 * 架构（2026-10-24 定稿，真机实测后重写）：
 * ```
 * proot（用户态模拟器）
 *   └── Ubuntu（aarch64 rootfs，APK 内置）
 *         └── 墨语言工具链 + qemu 翻译器 + zig 编译器 + uspace2 自举系统，全部装在 Ubuntu 里
 * ```
 * 真机证明：宿主 App 沙箱里 execve 私有目录 ELF 被 SELinux 拒、box64 缺非标 libc 起不来；
 * 而 **增量解压进 Ubuntu rootfs** 是终端环境验证过的路。所以墨语言不再有「第二套运行时」——
 * 工具链、编译器、自举系统都是打进 APK 之前准备好的，设备侧只剩解压落地。
 *
 * 覆盖的需求：
 *  · 运行执行开发的包 → action=build_run / run / compile
 *  · AI 直接修复墨语言 bug → action=read / write + compile + run（读源码→改→编译→验证）
 *  · 自带运行和执行环境 → 随 APK 内置，setup 一次落地（自动先备好 Ubuntu）
 *  · 包管理/工具链扩展 → action=exec 可直接调 moc/mols/molink 等任意工具链程序（在 Ubuntu 内）
 */
class MoyuTool : QuroTool {
    override val name: String = "moyu"

    override val description: String =
        "墨语言（Mo）—— 完整运行在 Ubuntu 内的系统级语言：编译/运行/调试 .mo 程序，读写语言源码以修复 bug，调用内置工具链。" +
            "架构：proot 承载 Ubuntu（APK 内置 aarch64 rootfs），墨语言工具链（moc/mo2x/mols/molink/dwmo/modb/seed）、" +
            "x86_64 翻译器 qemu-x86_64-static、zig 交叉编译器、uspace2 自举系统**全部装进 Ubuntu**" +
            "（/opt/molang、/opt/zig、/usr/local/bin/mo）——设备上不构建、不转译工具链、不需要点构建按钮，也没有第二套分散的运行时。" +
            "主命令 `mo <file.mo>`：转译成 freestanding C → zig 交叉编译成 AArch64 静态 ELF → 在 Ubuntu 内执行，全程不离开 guest；" +
            "compile 只编译，build_run 编译并运行，run 跑已产出的 ELF。首次使用先 action=setup（自动备好 Ubuntu 并解压落地）。" +
            "包管理（对应官方 tools/mopkg.sh 协议、但不需要 git）：pkg_init 建项目、dep_install 按 git 地址装依赖、" +
            "dep_fetch 按 mo.toml 拉全部、dep_list/dep_tree/dep_verify；依赖装到 deps/<名字>，编译前自动同步到源目录。" +
            "测试与修复闭环：test 跑内置用例（判定口径与官方 tests/run.sh 一致），aifix 执行「应用补丁→测试通过→撤回补丁→测试必须失败」的硬校验。" +
            "墨语言自举系统（selfhosted / system_image）：uspace2（层次化 VFS + 24 条命令 shell + 包管理 + 状态持久化）" +
            "与成品 ISO 都在 /opt/molang/system，selfhosted 在 Ubuntu 内起它，system_image 只导出镜像与文件信息。\n" +
            "墨语言自举内核（kernel_build / kernel_info）：kernel64.elf 是**用墨语言写的 ARM64 裸机内核**" +
            "（入口 0x40000000、控制台 PL011 UART @0x09000000、无系统调用、无 libc、无 PT_INTERP）。" +
            "kernel_build 在 Ubuntu 内用 `mo kernel` 现编一个（mo2x kern-aarch64 → zig aarch64-freestanding → entry64.S + linker64.ld），" +
            "kernel_info 导出内置内核的 ELF 结构。🔴 裸机内核**不能**在 proot/Ubuntu 里执行 —— 它自己就是提供系统调用的那一层；" +
            "验证只能靠 qemu-system-aarch64 -M virt -kernel，或读 ELF 头自证。\n" +
            "AArch64 原生目标（native_build / native_run / native_targets / cc_diag / native_setup）：" +
            "把 .mo 编成 **ARM64 静态 ELF**（cfs-aarch64 后端 + freestanding C + Ubuntu 内的 zig 编译器），" +
            "产物不链 libc、**不经 qemu 翻译**，由 Android 原生执行 —— 这是墨语言在手机上的最快路径。" +
            "它是用户态系统程序，不是 Linux 内核；要裸机内核请用 kernel_build。" +
            "墨语言原生通道（native_status/native_deploy/native_bench/native_exec/native_compile/native_system/native_cleanup）：一条独立复制的通道，`/data/local/tmp/quro-mo`，经 Shizuku 特权 shell 域执行，避开 App 沙箱 SELinux 对 execve 的限制。" +
            "它从 APK 资产重新解一份工具链，与 Ubuntu rootfs 内的 `/opt/molang` 毫无关系，删一边不影响另一边。" +
            "cfs-aarch64 后端产出的 freestanding 静态 ELF（不链 libc、`svc #0` 直接陷入内核）在这条路上 **原生跑**，连 proot 都不经过。" +
            "native_bench 跑的 andromo（筛法数 π）就是直接证据：产物由内核直接调度，毫秒级出结果。"

    override val parametersJson: String = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作","enum":["setup","compile","run","build_run","exec","list","read","write","diagnose","pkg_init","dep_install","dep_fetch","dep_list","dep_tree","dep_verify","graph","net_diag","test","aifix","resolve","native_build","native_run","native_targets","cc_diag","native_setup","selfhosted","system_image","system_build","kernel_build","kernel_info","native_status","native_deploy","native_bench","native_exec","native_compile","native_system","native_cleanup"]},
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
            "target":{"type":"string","description":"native_build 的编译目标：aarch64（默认，在 proot 的 Ubuntu 用户空间里原生执行）/ x86_64（经 qemu 逐条翻译，慢）/ kern-aarch64（裸机，仅模拟器）"},
            "layer":{"type":"string","description":"native_deploy 部署哪一层：core（核心层，约6.4MB，默认）/ zig（编译层，约176MB）"},
            "n":{"type":"number","description":"native_bench 的筛法上限，默认 200000"},
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
        // 墨语言 ⇢ Ubuntu 融合通道：工具链/编译器/自举系统全部装进 Ubuntu rootfs
        val env = com.ai.assistance.quro.terminal.runtime.TerminalEnvironment(context)
        val ubuntu = com.ai.assistance.quro.terminal.moyu.MoyuUbuntu(context, env, rt)
        val timeoutMs = (args.optDouble("timeout_seconds", 180.0).toLong()) * 1000L

        // setup / diagnose / list 不需要运行时已就绪
        when (action) {
            "setup" -> {
                // 墨语言完整融合在 Ubuntu 里：先备好 Ubuntu，再把工具链/编译器/自举系统解压落地
                val steps = StringBuilder()
                val r = runBlocking { ubuntu.ensure { steps.appendLine(it) } }
                val diag = runBlocking { ubuntu.diagnose() }
                return r.fold(
                    onSuccess = { "✅ 墨语言已完整融合进 Ubuntu\n${steps.toString().trim()}\n\n$diag" },
                    onFailure = { "❌ 融合失败：${it.message}\n\n$diag" }
                )
            }
            "diagnose" -> return runBlocking { ubuntu.diagnose() }

            // ── AArch64 原生目标 ──
            "native_targets" -> return buildString {
                appendLine("墨语言可出的编译目标：")
                MoyuNative.Target.entries.forEach { t ->
                    appendLine("· ${t.id}　${t.label}")
                    appendLine("　${t.note}")
                }
                appendLine()
                appendLine("aarch64 产物由 Android 原生 execve 执行，不经过 qemu；")
                appendLine("x86_64 产物仍要 qemu 翻译；kern-aarch64 是**裸机内核**（无系统调用、输出走 PL011 UART、")
                appendLine("入口 0x40000000、无 PT_INTERP），Android 上无法裸机启动，只能生成镜像后在模拟器里跑 ——")
                appendLine("在 Ubuntu 内用 action=kernel_build 现编，action=kernel_info 看 ELF 结构。")
            }

            "cc_diag" -> return runBlocking { ubuntu.diagnose() }

            // 编译并用 proot 承载**墨语言自举的用户空间**（examples/uspace2.mo）：
            // 它自带层次化 VFS、24 条命令的 shell、包管理（依赖拓扑/签名/升级/拒绝降级）、
            // 墨语言自举的系统（uspace2）与工具链已**完整融合进 Ubuntu**：proot 承载 Ubuntu，
            // 墨语言工具链装在 Ubuntu 的 /opt/molang，zig 在 /opt/zig，全程不离开 guest。
            // selfhosted = 在 Ubuntu 内起自举系统；system_image = 导出镜像信息。
            "system_image" -> {
                val r = runBlocking { ubuntu.imageInfo() }
                r.fold(
                    onSuccess = { it },
                    onFailure = { "墨语言自举系统未就绪：${it.message}" }
                )
            }

            // 兼容旧名字
            "system_build" -> {
                val r = runBlocking { ubuntu.imageInfo() }
                r.fold(
                    onSuccess = { it },
                    onFailure = { "墨语言自举系统未就绪：${it.message}" }
                )
            }

            "selfhosted" -> {
                val r = runBlocking { ubuntu.enterSystem() }
                r.fold(
                    onSuccess = { it },
                    onFailure = { "启动墨语言自举系统失败：${it.message}" }
                )
            }

            // ── 自举内核 ──
            "kernel_build" -> {
                val key = args.optString("path", "").trim().ifBlank { args.optString("name", "").trim() }
                // 不给 path 就用内置示例 examples/kernel64.mo（自带三个自检）
                val src = if (key.isBlank()) null else resolveMoyuFile(rt, key)
                if (key.isNotBlank() && src == null) return "找不到源码：$key"
                val r = runBlocking { ubuntu.buildKernel(src) }
                r.fold(
                    onSuccess = { it },
                    onFailure = { "内核构建失败：${it.message}" }
                )
            }

            "kernel_info" -> {
                // 宿主侧直读 rootfs 里的 ELF，不开 proot
                ubuntu.kernelInfo()
            }

            // ── 原生通道：/data/local/tmp + Shizuku，ARM64 产物原生跑 ──
            // 🔴 这套动作必须留在 ubuntuActions 门禁**之前**：它跟 Ubuntu 一点关系都没有，
            //    是从 APK 资产重新解一份工具链落到 /data/local/tmp 的独立通道。
            "native_status" -> return runBlocking {
                val local = MoyuLocal(context)
                local.describe() + "\n\n" + local.probe().fold({ it }, { "自检失败：${it.message}" })
            }

            "native_deploy" -> {
                val layer = args.optString("layer", "core").trim().lowercase()
                val local = MoyuLocal(context)
                val steps = StringBuilder()
                val r = if (layer == "zig" || layer == "cc") {
                    runBlocking { local.deployZig { steps.appendLine(it) } }
                } else {
                    runBlocking { local.deployCore { steps.appendLine(it) } }
                }
                return r.fold(
                    onSuccess = { "✅ " + local.describe() + "\n\n" + steps.toString().trim() },
                    onFailure = { "❌ 部署失败：${it.message}" }
                )
            }

            "native_bench" -> {
                // π(n) 计数。这个数字就是「ARM64 产物原生跑」的直接证据：
                // 一个 AArch64 静态 ELF，不经 proot、不经 qemu，内核直接调度。
                val local = MoyuLocal(context)
                val n = args.optDouble("n", 200000.0).toInt().coerceIn(1, 20_000_000)
                return runBlocking { local.benchmark(n, timeoutMs) }
                    .fold({ it }, { "失败：${it.message}" })
            }

            "native_run" -> {
                val local = MoyuLocal(context)
                val rel = args.optString("path", "").trim().ifBlank { "bin/andromo" }
                val a = args.optString("args", "").trim()
                return runBlocking { local.runArtifact(rel, a, timeoutMs) }
                    .fold({ it }, { "失败：${it.message}" })
            }

            "native_compile" -> {
                // 在设备上把 .mo 走官方配方（mo2x cfs-aarch64 → zig cc -nostdlib -fno-builtin）
                // 现编成 ARM64 静态 ELF 并原生运行。需要编译层（zig），由 native_deploy layer=zig 部署。
                val local = MoyuLocal(context)
                val key = args.optString("path", "").trim().ifBlank { args.optString("name", "").trim() }
                val src = if (key.isNotBlank()) {
                    resolveMoyuFile(rt, key) ?: File(key)
                } else {
                    local.sourceFromAsset("examples/andromo.mo")
                }
                if (src == null || !src.isFile) return "找不到源码：${key.ifBlank { "（未提供 path）" }}"
                val a = args.optString("args", "").trim()
                return runBlocking { local.compileAndRun(src, a, timeoutMs) }
                    .fold({ it }, { "失败：${it.message}" })
            }

            "native_system" -> {
                val local = MoyuLocal(context)
                val input = args.optString("stdin", "").ifBlank { "help\nexit\n" }
                return runBlocking { local.enterSystem(input, timeoutMs) }
                    .fold({ it }, { "失败：${it.message}" })
            }

            "native_cleanup" -> {
                val local = MoyuLocal(context)
                return runBlocking { local.cleanup() }
                    .fold({ "已清空 " + MoyuLocal.REMOTE_ROOT }, { "失败：${it.message}" })
            }

            "native_setup" -> {
                val r = runBlocking { ubuntu.ensure { } }
                return r.fold(
                    onSuccess = { "AArch64 编译器已装进 Ubuntu（/opt/zig）\n" + runBlocking { ubuntu.diagnose() } },
                    onFailure = { "融合失败：${it.message}\n" + runBlocking { ubuntu.diagnose() } }
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
                // aarch64（默认）与 kern-aarch64 都走 Ubuntu 内的 mo 命令 —— 唯一编译路径。
                // kern-aarch64 以前走的是旧的最小 rootfs 通道（真机已证伪：box64 execve ENOENT），
                // 现在由 `mo kernel` 在 Ubuntu 内完成，产物自证 ELF 头。
                // 只有 x86_64 保留原生目标实现。
                if (tgt == MoyuNative.Target.AARCH64) {
                    val r = runBlocking { ubuntu.buildSource(src, opt) }
                    return r.fold(
                        onSuccess = { it },
                        onFailure = { "构建失败：${it.message}" }
                    )
                }
                if (tgt == MoyuNative.Target.KERNEL) {
                    val r = runBlocking { ubuntu.buildKernel(src) }
                    return r.fold(
                        onSuccess = { it },
                        onFailure = { "内核构建失败：${it.message}" }
                    )
                }
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

            "native_exec" -> {
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
                appendLine("墨语言完整运行在 Ubuntu 内（proot → Ubuntu → /opt/molang）：")
                appendLine("  工具链　/opt/molang/bin（x86_64 静态，经 qemu-x86_64-static 翻译）")
                appendLine("  主命令　/usr/local/bin/mo（转译 → zig 静态编译 → Ubuntu 内执行）")
                appendLine("  编译器　/opt/zig（zig cc，aarch64 静态）")
                appendLine("  自举系统 /opt/molang/system/bin/uspace2（层次化 VFS，自建 /mo）")
                appendLine("  自举内核 /opt/molang/system/boot/kernel64.elf（ARM64 裸机，mo kernel 可现编）")
                appendLine()
                appendLine("宿主工作区（.mo 源码在这写，guest 内经 /quro 可见）：")
                appendLine("  ${rt.workspace.absolutePath}")
                appendLine()
                append(runBlocking { ubuntu.diagnose() })
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

        // 两类动作，两套就绪条件，别再混：
        //  · Ubuntu 融合类（compile/run/build_run/exec/native_*/selfhosted/system_*/kernel_*）→ 要求墨语言已装进 Ubuntu；
        //  · 宿主辅助类（graph/resolve/test/aifix/list/read/write）→ 要求宿主运行时已解压。
        val ubuntuActions = setOf(
            "compile", "run", "build_run", "exec",
            "native_build", "native_run", "native_setup",
            "selfhosted", "system_image", "system_build",
            "kernel_build", "kernel_info",
        )
        if (action in ubuntuActions) {
            val r0 = runBlocking { ubuntu.ensure { } }
            if (r0.isFailure) {
                return "墨语言融合进 Ubuntu 失败：${r0.exceptionOrNull()?.message}\n先用 action=setup 查看详情。"
            }
        } else if (action != "read" && action != "write") {
            if (!rt.isInstalled()) {
                val r = runBlocking { rt.ensureInstalled() }
                if (r.isFailure) return "墨语言宿主运行时未就绪且自动安装失败：${r.exceptionOrNull()?.message}\n先用 action=setup 查看详情。"
            }
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
                val r = runBlocking { ubuntu.buildSource(src) }
                return r.fold({ it }, { "编译失败：${it.message}" })
            }

            "run" -> {
                val p = args.optString("path", "").trim()
                if (p.isBlank()) return "run 需要 path（.mo 源文件或已编译的 .elf/.arm64）"
                val bin = File(p)
                if (!bin.exists()) return "文件不存在：$p"
                // .mo → 在 Ubuntu 内编译并运行；已产出的 AArch64 静态 ELF → Android 原生执行
                if (bin.name.endsWith(".mo")) {
                    val extra = args.optString("args", "").trim()
                        .split(' ').filter { it.isNotBlank() }
                    val r = runBlocking { ubuntu.runSource(bin, extra) }
                    return r.fold({ it }, { "运行失败：${it.message}" })
                }
                val extra = args.optString("args", "").trim()
                    .split(' ').filter { it.isNotBlank() }
                val r = runBlocking { native.runAuto(bin, extra, timeoutMs) }
                return buildString {
                    appendLine("退出码 ${r.code}" +
                        (if (r.viaLauncher == true) "（memfd 原生执行，不经 qemu）" else "（直接执行）") +
                        (if (r.timedOut) " · 超时" else ""))
                    if (r.diag) appendLine()
                    append(r.output.trim())
                }
            }

            "build_run" -> {
                val p = args.optString("path", "").trim()
                if (p.isBlank()) {
                    // 允许直接给代码 + 文件名
                    val code = args.optString("code", "")
                    val nm = args.optString("name", "main.mo")
                    if (code.isBlank()) return "build_run 需要 path，或提供 code + name"
                    val src = rt.writeSource(nm, code)
                    val r = runBlocking { ubuntu.runSource(src) }
                    return r.fold({ it }, { "失败：${it.message}" })
                }
                val src = File(p)
                if (!src.exists()) return "源文件不存在：$p"
                val r = runBlocking { ubuntu.runSource(src) }
                return r.fold({ it }, { "失败：${it.message}" })
            }

            "exec" -> {
                val tool = args.optString("tool", "").trim()
                if (tool.isBlank()) return "exec 需要 tool（moc/mo2x/mo2c/mols/molink/dwmo/modb/seed/lsptest）"
                val a = args.optString("args", "").split(Regex("\\s+")).filter { it.isNotBlank() }
                val r = runBlocking { ubuntu.runTool(tool, a) }
                return r.fold({ it }, { "执行失败：${it.message}" })
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
