package com.ai.assistance.quro.core.terminal

import android.content.Context
import com.ai.assistance.quro.core.linux.LinuxDistro
import com.ai.assistance.quro.core.linux.PackageManagerSpec
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.terminal.TerminalManager
import java.io.File

/**
 * 终端统一门面（终端架构统一 · 核心）。
 *
 * 背景：此前「CMS / 工具中心包管理 / 设置文件管理 / ACI 服务」各自直接伸手到四个后端：
 *  [QuroLinuxEnv.run]（一次性 proot）、[QuroTerminalController.runCommand]（一次性 proot + 守卫）、
 *  [TerminalManager]（PTY 可见终端）、以及 java.io.File 直操作——没有统一抽象层，调用点散落、
 *  行为不一致，ACI 服务也缺文件/包管理能力。
 *
 * 本门面把「执行命令 / 发到可见终端 / 环境探测 / rootfs 文件读写 / 包管理」收敛成**单一入口**：
 *  - [exec]：带命令副作用分级守卫的一次性执行（AI / ACI / 用户可见路径）。
 *  - [run]：兼容旧 [QuroLinuxEnv.run] 的 `(退出码, 输出)` 形态，供 CMS / 工具机械迁移，行为与原实现一致。
 *  - [sendToTerminal]：把命令发到可见终端（TerminalManager PTY），用户能实时看到进度。
 *  - [readFile]/[writeFile]/[deleteFile]/[listDir]：rootfs 内文件读写删列（guest 路径 → 宿主路径映射）。
 *  - [pkgInstall]/[pkgRemove]/…：包管理（发行版探测 → 命令生成 → 执行）。
 *
 * 设计原则：门面只做**路由与收敛**，不重写已跑通的底层链路——执行仍走成熟后端，
 * 避免破坏 proot / 会话等已验证的路径。
 */
object QuroTerminalBridge {

    // ───────────────────────── 非交互执行 ─────────────────────────

    /** 带命令副作用分级守卫的一次性执行（AI / ACI / 用户可见路径）。返回结构化 [ShellResult]。
     *  参数顺序与 [QuroTerminalController.runCommand] 完全一致，可做无脑 drop-in 替换。 */
    fun exec(
        command: String,
        timeoutMs: Long = 30_000L,
        context: Context? = null,
        confirmed: Boolean = false,
    ): ShellResult = QuroTerminalController.runCommand(command, timeoutMs, context, confirmed)

    /** 兼容旧 [QuroLinuxEnv.run] 的 `(退出码, 输出)` 形态（CMS / 工具机械迁移用，行为一致）。 */
    fun run(context: Context, command: String, timeoutMs: Long = 30_000L): Pair<Int, String> =
        QuroLinuxEnv.run(context, command, timeoutMs)

    /** 带实时日志回调的执行（CMS 部署/配置日志用）。 */
    fun runWithLog(
        context: Context,
        command: String,
        timeoutMs: Long = 30_000L,
        onLine: (String) -> Unit = {},
    ): Pair<Int, String> = QuroLinuxEnv.runWithLog(context, command, timeoutMs, onLine)

    // ───────────────────────── 可见终端会话 ─────────────────────────

    /**
     * 把命令发到可见终端（TerminalManager PTY）。适用于「用户需要实时看到进度」的场景，
     * 如包管理源切换、长耗时安装。返回命令派发器的响应文本。
     */
    suspend fun sendToTerminal(
        context: Context,
        sessionId: String = "default",
        command: String,
    ): String = TerminalManager.getInstance(context).sendCommandToSession(sessionId, command)

    // ───────────────────────── 环境探测 ─────────────────────────

    /** 环境是否就绪（proot/rootfs 可用）。 */
    fun envReady(context: Context): Boolean = QuroLinuxEnv.probeLenient(context).available

    /** 探测环境内的 Linux 发行版。 */
    fun distro(context: Context): LinuxDistro = QuroLinuxEnv.detectDistro(context)

    /** 探测发行版对应的包管理器。 */
    fun packageManager(context: Context): PackageManagerSpec = QuroLinuxEnv.detectPackageManager(context)

    // ───────────────────────── 包管理 ─────────────────────────

    /** 包管理统一入口：给定已解析的包管理器与命令串，走守卫执行。 */
    fun pkg(context: Context, command: String, timeoutMs: Long = 300_000L): ShellResult =
        exec(command, timeoutMs, context)

    fun pkgInstall(context: Context, packages: List<String>, timeoutMs: Long = 300_000L): ShellResult =
        pkg(context, packageManager(context).install(packages), timeoutMs)

    fun pkgRemove(context: Context, packages: List<String>, timeoutMs: Long = 300_000L): ShellResult =
        pkg(context, packageManager(context).remove(packages), timeoutMs)

    fun pkgUpdate(context: Context, timeoutMs: Long = 300_000L): ShellResult =
        pkg(context, packageManager(context).update(), timeoutMs)

    fun pkgUpgrade(context: Context, timeoutMs: Long = 600_000L): ShellResult =
        pkg(context, packageManager(context).upgrade(), timeoutMs)

    fun pkgSearch(context: Context, query: String, timeoutMs: Long = 60_000L): ShellResult =
        pkg(context, packageManager(context).search(query), timeoutMs)

    fun pkgList(context: Context, filter: String? = null, timeoutMs: Long = 60_000L): ShellResult =
        pkg(context, packageManager(context).listInstalled(filter), timeoutMs)

    fun pkgInfo(context: Context, pkgName: String, timeoutMs: Long = 60_000L): ShellResult =
        pkg(context, packageManager(context).info(pkgName), timeoutMs)

    fun pkgClean(context: Context, timeoutMs: Long = 120_000L): ShellResult =
        pkg(context, packageManager(context).clean(), timeoutMs)

    // ───────────────────────── rootfs 文件操作 ─────────────────────────

    /** 单文件读取上限（默认 256 KiB），防止把大文件整段读进内存。 */
    private const val DEFAULT_MAX_BYTES = 256 * 1024

    /**
     * guest 路径 → 宿主路径映射。
     *
     * proot 内与宿主侧的路径对应关系（与 [QuroLinuxEnv] 完全一致，避免读写错位）：
     *  - `/root/…`、`/home/…` → `homePath`（容器内 /root 与宿主 sandbox-home 同一目录）
     *  - `/sdcard/…`、`/storage/emulated/0/…` → 共享存储（无全文件访问权限时返回 null）
     *  - 其余绝对路径 → `rootfsPath`
     *  - 相对路径 → 以 /root（home）为基准
     */
    fun toHostFile(context: Context, guestPath: String): File? {
        val p = guestPath.trim()
        if (p.isEmpty()) return null
        val home = QuroLinuxEnv.homePath(context)
        val rootfs = QuroLinuxEnv.rootfsPath(context)

        // 相对路径 → 以 /root（home）为基准
        if (!p.startsWith("/")) return File(home, p)

        // /root/** 或 /home/** → home
        if (p == "/root" || p.startsWith("/root/")) {
            val rel = p.removePrefix("/root").removePrefix("/")
            return File(home, rel)
        }
        if (p == "/home" || p.startsWith("/home/")) {
            val rel = p.removePrefix("/home").removePrefix("/")
            return File(home, rel)
        }

        // 共享存储
        if (p == "/sdcard" || p.startsWith("/sdcard/") ||
            p == "/storage/emulated/0" || p.startsWith("/storage/emulated/0/")
        ) {
            val shared = QuroLinuxEnv.sharedStorageHostDir(context) ?: return null
            val rel = p.removePrefix("/storage/emulated/0").removePrefix("/sdcard").removePrefix("/")
            return File(shared, rel)
        }

        // 其余绝对路径 → rootfs
        val rel = p.removePrefix("/")
        return File(rootfs, rel)
    }

    /** guest 路径是否存在。 */
    fun exists(context: Context, guestPath: String): Boolean =
        toHostFile(context, guestPath)?.exists() == true

    /** 读取 rootfs 内文本文件。 */
    fun readFile(context: Context, guestPath: String, maxBytes: Int = DEFAULT_MAX_BYTES): ShellResult {
        val f = toHostFile(context, guestPath)
            ?: return ShellResult("", -1, error = "共享存储不可用（未授予「所有文件访问」权限）")
        if (!f.exists()) return ShellResult("", -1, error = "文件不存在：$guestPath")
        if (!f.isFile) return ShellResult("", -1, error = "不是文件：$guestPath")
        return try {
            if (f.length() > maxBytes) {
                // 截取前 maxBytes 字节并提示
                val head = f.inputStream().use { ins ->
                    val buf = ByteArray(maxBytes)
                    val n = ins.read(buf)
                    String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
                }
                ShellResult(head, 0, error = "文件过大（${f.length()} 字节），已截取前 $maxBytes 字节")
            } else {
                ShellResult(f.readText(), 0)
            }
        } catch (e: Exception) {
            ShellResult("", -1, error = e.message ?: e.toString())
        }
    }

    /** 写入 rootfs 内文本文件（父目录不存在则自动创建）。 */
    fun writeFile(context: Context, guestPath: String, content: String): ShellResult {
        val f = toHostFile(context, guestPath)
            ?: return ShellResult("", -1, error = "共享存储不可用（未授予「所有文件访问」权限）")
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content)
            ShellResult("", 0)
        } catch (e: Exception) {
            ShellResult("", -1, error = e.message ?: e.toString())
        }
    }

    /** 删除 rootfs 内文件（或空目录）。 */
    fun deleteFile(context: Context, guestPath: String): ShellResult {
        val f = toHostFile(context, guestPath)
            ?: return ShellResult("", -1, error = "共享存储不可用（未授予「所有文件访问」权限）")
        if (!f.exists()) return ShellResult("", -1, error = "不存在：$guestPath")
        return try {
            val ok = f.deleteRecursively()
            if (ok) ShellResult("", 0) else ShellResult("", -1, error = "删除失败：$guestPath")
        } catch (e: Exception) {
            ShellResult("", -1, error = e.message ?: e.toString())
        }
    }

    /** 列出 rootfs 内目录内容（目录名带 `/` 后缀，按名称排序）。 */
    fun listDir(context: Context, guestPath: String): ShellResult {
        val f = toHostFile(context, guestPath)
            ?: return ShellResult("", -1, error = "共享存储不可用（未授予「所有文件访问」权限）")
        if (!f.exists()) return ShellResult("", -1, error = "目录不存在：$guestPath")
        if (!f.isDirectory) return ShellResult("", -1, error = "不是目录：$guestPath")
        return try {
            val entries = f.listFiles()?.sortedBy { it.name } ?: emptyList<File>()
            val out = entries.joinToString("\n") {
                it.name + if (it.isDirectory) "/" else ""
            }
            ShellResult(out, 0)
        } catch (e: Exception) {
            ShellResult("", -1, error = e.message ?: e.toString())
        }
    }
}
