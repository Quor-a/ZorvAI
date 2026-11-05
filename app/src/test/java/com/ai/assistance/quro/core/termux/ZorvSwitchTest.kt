package com.ai.assistance.quro.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `zorv` 切换命令的生成逻辑。
 *
 * 这些用例盯的是「脚本生成后真的能跑」这件事：shebang 指向哪个 bash、
 * 占位符有没有漏、proot 参数有没有正确 shell 引号化。
 * 生成错了在真机上表现是 `command not found` / `bad interpreter`，
 * 排查成本远高于在这里断言一次。
 */
class ZorvSwitchTest {

    private val p = ZorvSwitch.Paths(
        termuxPrefix = "/data/user/0/com.ai.assistance.quro/files/termux/usr",
        termuxHome = "/data/user/0/com.ai.assistance.quro/files/termux/home",
        ubuntuRoot = "/data/user/0/com.ai.assistance.quro/files/linux-sandbox/rootfs",
        proot = "/data/app/~~abc==/base.apk!/lib/arm64-v8a/libproot.so",
        prootArgs = listOf(
            "--rootfs=/data/user/0/com.ai.assistance.quro/files/linux-sandbox/rootfs",
            "--bind=/dev",
            "-0",
            "-w",
            "/root",
        ),
        termuxVersion = "2026.11.01-r1",
    )

    /** 用真实模板跑，避免「测试里通过、assets 里其实是旧版」这种假绿。 */
    private fun template(): String {
        val f = File("src/main/assets/zorv/zorv.sh.tmpl")
        assertTrue("模板缺失：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `termux side shebang points at its own bash`() {
        val s = ZorvSwitch.fill(template(), p, forUbuntu = false)
        assertTrue(
            "Termux 侧必须用 \$PREFIX/bin/bash 起（PATH 里没有 /bin/bash）",
            s.startsWith("#!${p.termuxPrefix}/bin/bash\n"),
        )
        // 兜底默认值用 bash 的 := 语法（未设置时才赋值）
        assertTrue(s.contains("ZORV_ENV:=termux"))
    }

    @Test
    fun `ubuntu side shebang is the sandbox bash`() {
        val s = ZorvSwitch.fill(template(), p, forUbuntu = true)
        assertTrue(s.startsWith("#!/bin/bash\n"))
        assertTrue(s.contains("ZORV_ENV:=ubuntu"))
    }

    @Test
    fun `no placeholder survives rendering`() {
        for (forUbuntu in listOf(true, false)) {
            val s = ZorvSwitch.fill(template(), p, forUbuntu)
            assertFalse(
                "还有未替换的占位符：${Regex("\\{\\{[A-Z_]+}}").findAll(s).map { it.value }.toSet()}",
                Regex("\\{\\{[A-Z_]+}}").containsMatchIn(s),
            )
        }
    }

    @Test
    fun `proot args are shell quoted and the sandbox entry is real exec`() {
        val s = ZorvSwitch.fill(template(), p, forUbuntu = false)
        // 参数里有 ~ / ! / =，必须整体单引号包裹，否则 shell 会解析成别的意思
        assertTrue(s.contains("'--rootfs=/data/user/0/com.ai.assistance.quro/files/linux-sandbox/rootfs'"))
        assertTrue(s.contains("'-w'"))
        // 进沙箱必须是 exec（不留残壳），并显式把 ZORV_ENV 传进沙箱
        // 进沙箱必须 exec（不留残壳），并显式把 ZORV_ENV 传进沙箱
        assertTrue(s.contains("zorv_proot_exec \"\${ZORV_PROOT_ARGS[@]}\" /usr/bin/env ZORV_ENV=ubuntu /bin/bash -l"))
        // 私有目录的 ELF 不能直接 execve（Android 10+ W^X），必须经 system_linker_exec
        assertTrue(s.contains("/system/bin/linker64"))
    }

    @Test
    fun `script only dispatches when executed directly`() {
        val s = ZorvSwitch.fill(template(), p, forUbuntu = false)
        // 被 .bashrc source 时只能定义函数，不能立刻执行（否则一开终端就自动进沙箱）
        assertTrue(s.contains("if [ \"\${BASH_SOURCE[0]:-\$0}\" = \"\$0\" ]; then"))
        assertTrue(s.contains("zorv_main \"\$@\""))
    }

    @Test
    fun `which command covers both ecosystems`() {
        val s = ZorvSwitch.fill(template(), p, forUbuntu = false)
        assertTrue("要能查 Termux 侧的 \$PREFIX/bin", s.contains("\$tp/bin/\$c"))
        assertTrue("要能查 Android 的 /system/bin", s.contains("/system/bin/\$c"))
        assertTrue("要能查沙箱的 usr/local/bin", s.contains("usr/local/bin"))
        // 提示语必须点明「pkg 属于 Termux」，这正是用户踩的坑
        assertTrue(s.contains("只在 Termux 侧"))
        assertTrue(s.contains("只在 zorvAI 沙箱里"))
    }

    @Test
    fun `host mount and command name stay in sync with the sandbox`() {
        // zorv 里的 /quro 必须等于 proot 的私有目录挂载点，否则沙箱内查不到 Termux 的路径
        assertEquals("/quro", ZorvSwitch.HOST_MOUNT)
        assertEquals("zorv", ZorvSwitch.CMD)
    }
}
