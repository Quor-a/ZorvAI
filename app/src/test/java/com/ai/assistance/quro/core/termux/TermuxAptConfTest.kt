package com.ai.assistance.quro.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `pkg` / `apt` 配置的回归测试（纯逻辑，不需要 Context，也不依赖真机）。
 *
 * 背景（真机报障）：`pkg install python` 失败于
 * 「apt 的 https method 路径不匹配」。真实成因有三层，缺一层都白搭：
 *  1. apt / apt-helper 的 ELF 把 method 目录编译死成
 *     `/data/data/com.termux/files/usr/lib/apt/methods`，而本应用前缀是
 *     `/data/user/0/<pkg>/files/termux/usr` → 找 method 必扑空；
 *  2. 这套 bootstrap 的 `lib/apt/methods/` 里**没有 https**，而仓库地址全是 https；
 *  3. `etc/apt/trusted.gpg.d/` 是空的（公钥躺在 `share/termux-keyring`），
 *     即使前两层通了，`apt update` 也会 NO_PUBKEY。
 *
 * 这三层都无法在本机用 aarch64 的 apt 实跑验证，所以用断言把关键行钉住 ——
 * 写错一个字符，整条包管理链路就断，而症状只会出现在真机上。
 */
class TermuxAptConfTest {

    /** 真机上真实前缀长这样（应用私有目录，非 `com.termux`）。 */
    private val realPrefix = "/data/user/0/com.ai.assistance.quro/files/termux/usr"

    @Test
    fun `method dir points at the real prefix`() {
        val conf = TermuxEnvironment.buildAptConf(realPrefix)
        // 这一行是「method 路径不匹配」的解药，缺了它 apt 会去 com.termux 下找
        assertTrue(
            "必须显式指定 method 目录，否则 apt 用编译期写死的官方路径",
            conf.contains("""Dir::Bin::methods "$realPrefix/lib/apt/methods";"""),
        )
    }

    @Test
    fun `no official prefix leaks into the config`() {
        val conf = TermuxEnvironment.buildAptConf(realPrefix)
        assertFalse(
            "配置里不能出现官方前缀，否则 apt 会去一个不可达的目录",
            conf.contains("/data/data/com.termux/files/usr"),
        )
    }

    @Test
    fun `state etc and cache all follow the real prefix`() {
        val conf = TermuxEnvironment.buildAptConf(realPrefix)
        // 逐条钉：漏一条就会出现「能装但不能查」「能查但状态写错地方」这类偏门症状
        for (line in listOf(
            """Dir::State::status "$realPrefix/var/lib/dpkg/status";""",
            """Dir::State::lists "$realPrefix/var/lib/apt/lists";""",
            """Dir::Cache::archives "$realPrefix/var/cache/apt/archives";""",
            """Dir::Etc::sourcelist "$realPrefix/etc/apt/sources.list";""",
            """Dir::Etc::trustedparts "$realPrefix/etc/apt/trusted.gpg.d";""",
            """Dir::Bin::dpkg "$realPrefix/bin/dpkg";""",
        )) {
            assertTrue("缺少配置行：$line", conf.contains(line))
        }
    }

    @Test
    fun `android specific switches are present`() {
        val conf = TermuxEnvironment.buildAptConf(realPrefix)
        // uid 不是 0，也没有 _apt 用户
        assertTrue(conf.contains("""APT::Sandbox::User "root";"""))
        // Termux stable 通道的 Release 常过期
        assertTrue(conf.contains("""Acquire::Check-Valid-Until "false";"""))
    }

    @Test
    fun `signature verification is never disabled`() {
        val conf = TermuxEnvironment.buildAptConf(realPrefix)
        // 降级到 http 之后，签名校验是唯一的安全边界，绝不能顺手关掉
        assertFalse(
            "不允许放开未认证仓库",
            conf.contains("AllowUnauthenticated") || conf.contains("AllowInsecureRepositories"),
        )
    }

    @Test
    fun `https downgrade keeps host and path intact`() {
        val mirrors = "MAIN=\"https://packages-cf.termux.dev/apt/termux-main\"\n" +
            "ROOT=\"https://packages-cf.termux.dev/apt/termux-root\""
        val out = TermuxEnvironment.downgradeHttpsToHttp(mirrors)
        assertTrue(out.contains("""MAIN="http://packages-cf.termux.dev/apt/termux-main""""))
        assertTrue(out.contains("""ROOT="http://packages-cf.termux.dev/apt/termux-root""""))
        assertFalse(out.contains("https://"))
        // 幂等：再跑一次不变（修正函数会在每次 run 时被调用）
        assertEquals(out, TermuxEnvironment.downgradeHttpsToHttp(out))
    }

    // ───────────────────────── apt 包装层（-o 强制覆盖） ─────────────────────────

    @Test
    fun `shim passes every override on the command line`() {
        val shim = TermuxEnvironment.buildAptShim(realPrefix, "apt.real")
        // 真机实测 APT_CONFIG / -c 都读不到，只有 -o 一定生效 —— 所以每一条都必须出现在命令行上
        for ((k, v) in TermuxEnvironment.aptDirOverrides(realPrefix)) {
            assertTrue("shim 缺少 -o $k=$v", shim.contains("""-o "$k=$v""""))
        }
    }

    @Test
    fun `shim execs the real binary and forwards args`() {
        val shim = TermuxEnvironment.buildAptShim(realPrefix, "apt-get.real")
        // shim 里用 $P 变量（路径只在 P= 那一行出现一次，改前缀不必改多处）
        assertTrue(shim.contains("exec \"\$P/bin/apt-get.real\""))
        // 结尾必须把原参数透传，否则 `pkg install python` 的 install/python 会丢
        assertTrue(shim.trimEnd().endsWith("\"\$@\""))
    }

    @Test
    fun `shim keeps the library path so methods can load libapt-pkg`() {
        val shim = TermuxEnvironment.buildAptShim(realPrefix, "apt.real")
        // method 的 RUNPATH 硬编码成官方前缀，只能靠 LD_LIBRARY_PATH 兜住
        assertTrue(shim.contains("export LD_LIBRARY_PATH=\"\$P/lib"))
        assertTrue(shim.contains("export APT_CONFIG=\"\$P/etc/apt/apt-quro.conf\""))
    }

    @Test
    fun `shim carries the idempotency mark and uses the termux bash`() {
        val shim = TermuxEnvironment.buildAptShim(realPrefix, "apt.real")
        assertTrue("必须带标记，否则每次修正都会把 .real 再改一次名", shim.contains(TermuxEnvironment.APT_SHIM_MARK))
        // 私有目录的 bash 必须经 termux-exec（LD_PRELOAD）才能真正 exec，shebang 因此指向它
        assertTrue(shim.lines().first() == "#!/$realPrefix/bin/bash")
    }

    @Test
    fun `conf and shim stay in sync from one source`() {
        // 同一份覆盖表渲染两遍：题面不同（配置文件 vs 命令行），内容不能有偏差
        val overrides = TermuxEnvironment.aptDirOverrides(realPrefix)
        val conf = TermuxEnvironment.buildAptConf(realPrefix)
        val shim = TermuxEnvironment.buildAptShim(realPrefix, "apt.real")
        for ((k, v) in overrides) {
            assertTrue("conf 缺 $k", conf.contains("""$k "$v";"""))
            assertTrue("shim 缺 $k", shim.contains("""-o "$k=$v""""))
        }
        // 反过来也不许多出来：conf 的行数必须等于覆盖表条数
        assertEquals(overrides.size, conf.trim().lines().size)
    }

    @Test
    fun `method sandbox env reset is explicitly disabled`() {
        val overrides = TermuxEnvironment.aptDirOverrides(realPrefix).toMap()
        // apt 默认会重置 method 子进程的环境，那样 LD_LIBRARY_PATH 就丢了
        assertEquals("false", overrides["APT::Sandbox::ResetEnvironment"])
    }
}
