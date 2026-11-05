package com.ai.assistance.quro.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Termux 执行通道判定（对照官方 termux-exec 2.5.0 的 `isEnabled()`）。
 *
 * 背景：Android 10 起 SELinux 删掉了 `untrusted_app` 域对 `app_data_file` 的 `execute`，
 * 只豁免 `untrusted_app_25` / `untrusted_app_27`。本应用 targetSdk 34 → 必须走
 * system_linker_exec；旧实现直接 execve 私有目录 bash，EACCES 后被误诊成「可执行位异常」，
 * repair 无限循环。这些用例把判定规则钉住，避免以后又退回「直接 exec」。
 */
class TermuxExecTest {

    private val domain34 = "u:r:untrusted_app_34:s0:c512,c768"

    @Test
    fun `android 9 and below executes directly`() {
        assertEquals(
            "SDK 28 没有 W^X 限制，直接 exec 才是对的",
            TermuxExec.Mode.DIRECT,
            TermuxExec.decideMode(sdk = 28, uid = 10456, seProcessContext = domain34),
        )
    }

    @Test
    fun `target sdk 34 app must use system linker`() {
        assertEquals(
            "untrusted_app_34 非豁免域，必须走 linker64",
            TermuxExec.Mode.SYSTEM_LINKER,
            TermuxExec.decideMode(sdk = 34, uid = 10456, seProcessContext = domain34),
        )
    }

    @Test
    fun `legacy untrusted app domains are exempted`() {
        for (ctx in listOf("u:r:untrusted_app_25:s0", "u:r:untrusted_app_27:s0:c1,c2")) {
            assertEquals(
                "官方豁免域（targetSdk<=28）可直接 exec：$ctx",
                TermuxExec.Mode.DIRECT,
                TermuxExec.decideMode(sdk = 34, uid = 10456, seProcessContext = ctx),
            )
        }
    }

    @Test
    fun `privileged uids are not restricted`() {
        assertEquals(
            "root 域不受 W^X 限制",
            TermuxExec.Mode.DIRECT,
            TermuxExec.decideMode(sdk = 34, uid = 0, seProcessContext = "u:r:su:s0"),
        )
        assertEquals(
            "shell(2000)/adb 域不受限制",
            TermuxExec.Mode.DIRECT,
            TermuxExec.decideMode(sdk = 34, uid = 2000, seProcessContext = "u:r:shell:s0"),
        )
    }

    @Test
    fun `unreadable se context falls back to direct`() {
        assertEquals(
            "读不到 /proc/self/attr/current 时官方按「已豁免」处理",
            TermuxExec.Mode.DIRECT,
            TermuxExec.decideMode(sdk = 34, uid = 10456, seProcessContext = null),
        )
        assertEquals(
            "空白上下文同样按豁免处理",
            TermuxExec.Mode.DIRECT,
            TermuxExec.decideMode(sdk = 34, uid = 10456, seProcessContext = "  "),
        )
    }

    @Test
    fun `linker exec keeps bash as argv 1 so it keeps its own argv0`() {
        val argv = TermuxExec.launchArgv(
            mode = TermuxExec.Mode.SYSTEM_LINKER,
            linker = TermuxExec.LINKER64,
            executable = "/data/user/0/pkg/files/termux/usr/bin/bash",
            command = "pkg install -y git",
        )
        assertEquals(
            "必须是 linker64 <bash> <args>：bionic linker 把 argv[1] 当程序，bash 拿到的 argv[0] 仍是自己",
            listOf(
                "/system/bin/linker64",
                "/data/user/0/pkg/files/termux/usr/bin/bash",
                "-lc",
                "pkg install -y git",
            ),
            argv,
        )
    }

    @Test
    fun `direct mode argv has no linker prefix`() {
        assertEquals(
            listOf("/usr/bin/bash", "-lc", "true"),
            TermuxExec.launchArgv(TermuxExec.Mode.DIRECT, TermuxExec.LINKER64, "/usr/bin/bash", "true"),
        )
    }

    @Test
    fun `linker path depends on process bitness`() {
        assertEquals("/system/bin/linker64", TermuxExec.linkerPath(is64Bit = true))
        assertEquals("/system/bin/linker", TermuxExec.linkerPath(is64Bit = false))
    }

    @Test
    fun `preload variant follows execution mode`() {
        assertEquals(
            "system_linker_exec 场景官方要求 linker 变体",
            "libtermux-exec-linker-ld-preload.so",
            TermuxExec.primaryPreload(TermuxExec.Mode.SYSTEM_LINKER),
        )
        assertEquals(
            "direct 场景用 direct 变体",
            "libtermux-exec-direct-ld-preload.so",
            TermuxExec.primaryPreload(TermuxExec.Mode.DIRECT),
        )
        assertEquals(
            "两条通道的兜底变体互为对方",
            "libtermux-exec-direct-ld-preload.so",
            TermuxExec.fallbackPreload(TermuxExec.Mode.SYSTEM_LINKER),
        )
    }

    @Test
    fun `launch failures are recognized`() {
        // ProcessBuilder.start() 的 IOException 文案（execve EACCES）
        assertTrue(
            TermuxExec.isLaunchFailure(
                "执行失败：Cannot run program \"/data/user/0/x/usr/bin/bash\": error=13, Permission denied",
            ),
        )
        // linker64 拒收 ET_EXEC / 依赖库找不到
        assertTrue(TermuxExec.isLaunchFailure("CANNOT LINK EXECUTABLE \"/data/x/bash\": library \"libreadline.so\" not found"))
        assertTrue(TermuxExec.isLaunchFailure("not found: needed by main executable"))
        assertTrue(TermuxExec.isLaunchFailure("unexpected e_type"))
    }

    @Test
    fun `command runtime errors are not launch failures`() {
        // 🔴 关键：普通命令失败（含子命令自己打 Permission denied）不能被当成通道失败，
        // 否则会换通道重试 → 有副作用的命令（pkg install / rm）跑两遍。
        assertFalse(
            "命令自己报错不该触发换通道重试",
            TermuxExec.isLaunchFailure("exit=1\ncat: /data/x: Permission denied"),
        )
        assertFalse(TermuxExec.isLaunchFailure("pkg: command not found"))
        assertFalse(TermuxExec.isLaunchFailure(""))
        assertFalse(TermuxExec.isLaunchFailure("E: Unable to locate package foo"))
    }

    @Test
    fun `mode ids round trip through the cache stamp`() {
        for (m in TermuxExec.Mode.entries) {
            assertEquals(m, TermuxExec.Mode.of(m.id))
        }
        assertEquals(null, TermuxExec.Mode.of("garbage"))
    }

    // ───────────── 环境变量净化（「Invalid environment variable value」回归） ─────────────

    @Test
    fun `env values drop NUL padding from procfs reads`() {
        // 🔴 真机上的原始值：定长 buffer 读回来后面全是 \0，trim() 去不掉。
        // Android ProcessEnvironment#validateValue 一见 \0 就抛 IllegalArgumentException，
        // 整批环境变量失效 → 命令一次都起不来。
        val raw = "u:r:untrusted_app_34:s0:c512,c768\u0000\u0000\u0000\u0000"
        assertEquals("u:r:untrusted_app_34:s0:c512,c768", TermuxExec.sanitizeEnvValue(raw))
        assertFalse(TermuxExec.sanitizeEnvValue(raw)!!.contains('\u0000'))
    }

    @Test
    fun `env values drop control chars and blank out`() {
        assertEquals("a b", TermuxExec.sanitizeEnvValue("  a b\n"))
        assertEquals(null, TermuxExec.sanitizeEnvValue(null))
        assertEquals(null, TermuxExec.sanitizeEnvValue("\u0000\u0000"))
        assertEquals(null, TermuxExec.sanitizeEnvValue("   "))
    }

    @Test
    fun `se context accepts real domain and rejects junk`() {
        assertEquals(domain34, TermuxExec.sanitizeSeContext(domain34))
        // NUL 填充是常态，净化后仍要认
        assertEquals(domain34, TermuxExec.sanitizeSeContext("$domain34\u0000\u0000"))
        // 不是 SELinux 上下文：宁可不设，也不让脏值炸掉整条命令
        assertEquals(null, TermuxExec.sanitizeSeContext("enforcing"))
        assertEquals(null, TermuxExec.sanitizeSeContext("u:r:onlythreesegments"))
        assertEquals(null, TermuxExec.sanitizeSeContext("u:r:untrusted_app:s0;rm -rf /"))
        assertEquals(null, TermuxExec.sanitizeSeContext("u:r:" + "a".repeat(200) + ":s0"))
        assertEquals(null, TermuxExec.sanitizeSeContext(null))
    }

    @Test
    fun `sanitized se context still drives the linker decision`() {
        // 净化不能把「需要 linker」洗成「可以直接 exec」，否则又回到 EACCES + repair 死循环
        val padded = "$domain34\u0000\u0000"
        assertEquals(
            TermuxExec.Mode.SYSTEM_LINKER,
            TermuxExec.decideMode(34, 10123, TermuxExec.sanitizeSeContext(padded)),
        )
    }
}
