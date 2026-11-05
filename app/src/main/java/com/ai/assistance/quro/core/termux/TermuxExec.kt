package com.ai.assistance.quro.core.termux

/**
 * Termux **执行通道**判定：能不能直接 exec 应用私有目录里的 ELF，不能的话怎么走。
 *
 * ## 这不是「权限没设对」，是 SELinux 的硬规则
 *
 * Android 10（API 29）起，`system/sepolicy` 的 `0dd738d8` 提交把 `untrusted_app`
 * 域对 **`app_data_file`（即 `/data/data/<pkg>` 下所有文件）的 `execute` 权限删掉了**，
 * 只给两个向后兼容域留了口子：
 *
 *  - `untrusted_app_25`（targetSdk ≤ 25）
 *  - `untrusted_app_27`（targetSdk 26~28）
 *
 * 本应用 targetSdk 34 → 落在 `untrusted_app` 域 → **`/data/data/<pkg>/files/termux/usr/bin/bash`
 * 无论 chmod 0755 还是重装多少次，`execve()` 都是 EACCES**。这正是官方 Termux 至今
 * 把 targetSdk 钉在 28 的原因；`repair`（重新解压、补可执行位）对这条限制完全无效。
 *
 * ## 官方解法：system_linker_exec（bootstrap 里已经自带）
 *
 * 内核只认「被 exec 的那个文件」，而动态链接器 `/system/bin/linker64` 属于
 * `system_linker_exec` 域，允许被 app exec；它自己被 exec 之后可以**用读权限**
 * 把任意 app 私有目录里的 ELF `mmap` 进来执行。官方文档原文：
 *
 * > An ELF file at `/data/data/com.foo/executable` can be executed with:
 * > `/system/bin/linker64 /data/data/com.foo/executable [args]`
 *
 * 官方 `termux-exec` 2.5.0 把这条包成 `system_linker_exec` 模式，由 `$LD_PRELOAD`
 * 拦截 `exec()` 家族自动改写，判定规则就在 `termux-exec-system-linker-exec is-enabled`
 * 与 `TermuxExecLDPreload.h#isSystemLinkerExecEnabled()` 里，[decideMode] 与之逐条对齐：
 *
 *  - SDK < 29 → 限制不存在 → `direct`
 *  - uid 是 root(0) / shell(2000) → 特权域不受限 → `direct`
 *  - `/proc/self/attr/current` 读不到 → 官方按「已豁免」处理 → `direct`
 *  - SE 域是 `u:r:untrusted_app_25:` / `u:r:untrusted_app_27:` → 豁免 → `direct`
 *  - 其余（本应用） → `system_linker`
 *
 * 🔴 唯一的缺口：`$LD_PRELOAD` 只能作用于**已经跑起来的进程**之后的 exec。
 * **第一个进程（bash 本体）是 App 自己 fork/exec 的**，必须我们自己加 linker 前缀，
 * 否则连 bash 都起不来 —— 旧实现就是死在这一步（然后把它误诊成「可执行位异常，要 repair」）。
 *
 * 代价（官方已列明）：
 *  - 静态链接的 ELF 不能跑（没有 PT_INTERP，linker64 加载不了）；Termux 官方包几乎全是 PIE。
 *  - `/proc/<pid>/exe` 会显示成 linker64；官方用 `$TERMUX_EXEC__PROC_SELF_EXE` 补偿。
 */
object TermuxExec {

    const val LINKER64 = "/system/bin/linker64"
    const val LINKER32 = "/system/bin/linker"

    enum class Mode(val id: String) {
        /** 直接 execve 目标 ELF：只有 SDK < 29 或 SE 域被豁免时可行。 */
        DIRECT("direct"),

        /** 官方 system_linker_exec：`execve(/system/bin/linker64, [目标ELF, ...])`。 */
        SYSTEM_LINKER("system_linker"),
        ;

        companion object {
            fun of(id: String): Mode? = Mode.entries.firstOrNull { it.id == id }
        }
    }

    /**
     * 主 LD_PRELOAD 库名选择：官方 `termux-exec-ld-preload-lib setup` 在
     * `system_linker_exec` 启用时把 **linker 变体** 装成主库，否则用 direct 变体。
     * 两个变体差了「system_linker 场景才需要」的那批函数拦截，装错会有古怪行为。
     */
    fun primaryPreload(mode: Mode): String =
        if (mode == Mode.SYSTEM_LINKER) "libtermux-exec-linker-ld-preload.so"
        else "libtermux-exec-direct-ld-preload.so"

    fun fallbackPreload(mode: Mode): String =
        if (mode == Mode.SYSTEM_LINKER) "libtermux-exec-direct-ld-preload.so"
        else "libtermux-exec-linker-ld-preload.so"

    /** 32/64 位选不同 linker（官方按 `UINTPTR_MAX` 选，这里按进程位数选）。 */
    fun linkerPath(is64Bit: Boolean): String = if (is64Bit) LINKER64 else LINKER32

    /**
     * 构造第一个进程的 argv。
     *
     * `linker64 <elf> <args...>`：bionic 的 `linker_main` 把 argv[1] 当要执行的程序，
     * 于是 bash 收到的 argv[0] 仍是它自己的路径，行为与直接 exec 一致。
     */
    fun launchArgv(mode: Mode, linker: String, executable: String, command: String): List<String> =
        if (mode == Mode.DIRECT) listOf(executable, "-lc", command)
        else listOf(linker, executable, "-lc", command)

    /**
     * 输出是否属于「**根本没起来**」而不是「命令自己失败了」。
     *
     * 只有前者才值得换通道/换预加载重试；后者是业务错误，重试只会多跑一遍副作用命令。
     */
    fun isLaunchFailure(output: String): Boolean = LAUNCH_FAILURE_MARKERS.any { output.contains(it) }

    private val LAUNCH_FAILURE_MARKERS = listOf(
        // ProcessBuilder.start() 抛 IOException 的文案：execve 被 SELinux 拒（EACCES）等
        "Cannot run program",
        "error=13", // EACCES
        "error=8", // ENOEXEC
        // linker64 的报错：目标不是 ET_DYN / 依赖库找不到 / 前缀没重定位好
        "CANNOT LINK EXECUTABLE",
        "not found: needed by main executable",
        "cannot locate symbol",
        "unexpected e_type",
    )

    // ───────────────────────── 环境变量净化 ─────────────────────────

    /**
     * 净化环境变量**值**：剔除 NUL 与控制字符后 trim；净化后为空则返回 `null`（该变量干脆不设）。
     *
     * 🔴 为什么必须做（这是「Invalid environment variable value」的唯一来源）：
     * Android 的 `java.lang.ProcessEnvironment#validateValue()` 只查一条 —— 值里含 `\u0000`
     * 就抛 `IllegalArgumentException("Invalid environment variable value: ...")`；而
     * `ProcessBuilder.environment()` 是**整批生效**，只要有一个变量脏，命令连一次都起不来，
     * 异常文案又把「值」原样带出来，看着很像「SELinux 上下文格式被系统拒了」。
     *
     * 本机踩的就是这条：`/proc/self/attr/current` 在部分内核上 read 回来是**定长 NUL 填充**的
     * 上下文（`u:r:untrusted_app:s0:c512,c768\0\0\0...`），`trim()` 去不掉 `\0`，
     * 一带进 `TERMUX__SE_PROCESS_CONTEXT` 就整条通道失败。**不是 SELinux 策略变了**。
     */
    fun sanitizeEnvValue(raw: String?): String? {
        if (raw == null) return null
        val s = raw.filterNot { it == '\u0000' || it.code < 0x20 || it.code == 0x7F }.trim()
        return s.ifEmpty { null }
    }

    /** SELinux 上下文的合法字符集（`u:r:untrusted_app:s0:c512,c768` 这种）。 */
    private const val SE_CTX_ALLOWED =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_:,.-"

    /** 官方读上下文用定长 buffer，超长的一定是脏数据。 */
    private const val SE_CTX_MAX = 128

    /**
     * 净化 SELinux 上下文；不合法就返回 `null`（宁可不设，也不让脏值炸掉整条命令）。
     *
     * termux-exec 拿不到这个变量时会自己读 `/proc/self/attr/current`
     * （`se_process_context_from_file`），所以「不设」是安全的降级，不是丢功能。
     */
    fun sanitizeSeContext(raw: String?): String? {
        val s = sanitizeEnvValue(raw) ?: return null
        if (s.length > SE_CTX_MAX) return null
        if (!s.startsWith("u:r:")) return null
        // 至少 user:role:type:level 四段 → 三个冒号
        if (s.count { it == ':' } < 3) return null
        if (s.any { it !in SE_CTX_ALLOWED }) return null
        return s
    }

    /** 纯逻辑：与官方 `is-enabled` 逐条一致（见类注释）。 */
    fun decideMode(sdk: Int, uid: Int, seProcessContext: String?): Mode {
        if (sdk < 29) return Mode.DIRECT
        if (uid == 0 || uid == 2000) return Mode.DIRECT
        val se = seProcessContext?.trim().orEmpty()
        // 读不到 SE 域：部分 ROM / 内核不支持 SELinux，官方按「已豁免」处理
        if (se.isEmpty()) return Mode.DIRECT
        if (se.startsWith("u:r:untrusted_app_25:") || se.startsWith("u:r:untrusted_app_27:")) {
            return Mode.DIRECT
        }
        return Mode.SYSTEM_LINKER
    }

    /** 给 UI/AI 的一句话归因（不含命令输出）。 */
    fun reason(mode: Mode, sdk: Int, seProcessContext: String?): String = when (mode) {
        Mode.DIRECT ->
            if (sdk < 29) "Android $sdk < 10，没有 W^X 限制，直接 exec 即可"
            else "SE 域 ${seProcessContext ?: "未知"} 属官方豁免域（untrusted_app_25/27），可直接 exec"
        Mode.SYSTEM_LINKER ->
            "Android $sdk ≥ 10 且 SE 域 ${seProcessContext ?: "未知"} 非豁免域：" +
                "SELinux 禁止 untrusted_app exec 应用私有目录 ELF（chmod/repair 无效），" +
                "改走官方 system_linker_exec（linker64 以读权限 mmap）"
    }
}
