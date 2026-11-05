package com.ai.assistance.quro.core.termux

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.File

/**
 * `zorv` —— zorvAI 双环境切换命令（原创）。
 *
 * ## 为什么需要它
 *
 * 本机同时存在两套**互相隔离**的 Linux，命令还不通用：
 *
 * | 环境 | 实现 | 包管理器 | 特点 |
 * |---|---|---|---|
 * | `termux` | Termux 官方 bootstrap（`files/termux/usr`） | `pkg` | Android 原生，无 systemd / 无 root |
 * | `ubuntu` | proot + Ubuntu 24.04（`files/linux-sandbox`） | `apt` | 桌面工具链齐全，跑在沙箱里 |
 *
 * 用户看到的「`pkg: command not found`」「`apt` 能装但跑不了」全部来自这里：
 * 他当时在 Ubuntu 沙箱里敲 Termux 的命令，反之亦然。`zerov which` / `zorv status`
 * 就是为了让这类问题一眼可见，而不是靠猜。
 *
 * ## 命令怎么实现的
 *
 * 不是 `proot-distro` 的翻版：脚本由 App 生成（模板在 `assets/zorv/zorv.sh.tmpl`），
 * 每次会话启动时按**本机真实路径**与**当前 proot 参数**重写，因此：
 *
 *  - 沙箱的 `--bind` 与交互终端完全一致（共享存储、`/quro` 私有目录、`/proc/net` 都绑好）；
 *  - 换了 rootfs 位置、改了挂载策略，脚本自动跟着变，不需要手工同步；
 *  - Termux 侧与沙箱侧是**同一份脚本**，靠 `ZORV_ENV` 判断自己在哪一侧。
 *
 * 进入沙箱用真 `exec`（不留残壳），退出沙箱用 `exit`（proot 无法反向脱壳，
 * 这是 proot 的机制限制，不是偷懒）。
 */
object ZorvSwitch {

    private const val TAG = "ZorvSwitch"

    /** 命令名。放 `$PREFIX/bin` 后 Termux 里直接敲 `zorv`。 */
    const val CMD = "zorv"

    const val ENV_TERMUX = "termux"
    const val ENV_UBUNTU = "ubuntu"

    /** proot 内挂载 filesDir 的位置，与 [QuroLinuxEnv.APP_PRIVATE_MOUNT] 保持一致。 */
    const val HOST_MOUNT = QuroLinuxEnv.APP_PRIVATE_MOUNT

    private const val TEMPLATE = "zorv/zorv.sh.tmpl"

    data class Paths(
        val termuxPrefix: String,
        val termuxHome: String,
        val ubuntuRoot: String,
        val proot: String,
        val prootArgs: List<String>,
        val termuxVersion: String,
        /** proot 的 ptrace loader，缺它 proot 起不来（见 [QuroLinuxEnv.loaderPath]）。 */
        val prootLoader: String = "",
        /** proot 的临时目录，缺它会在 /tmp 下建（Android 上通常不可写）。 */
        val prootTmp: String = "",
        /** 沙箱宿主根（`files/linux-sandbox`），proot 进程的 LD_LIBRARY_PATH 基址。 */
        val sandboxDir: String = "",
    )

    /**
     * 取生成脚本所需的真实路径与 proot 参数。
     *
     * proot 参数直接复用 [QuroLinuxEnv.shellLaunch]，避免「命令进的沙箱」与
     * 「终端进的沙箱」绑定不一致 —— 那种不一致会让 `zorv` 里看到的文件和终端里看到的对不上。
     */
    fun paths(ctx: Context): Paths {
        val root = runCatching { QuroLinuxEnv.rootfsPath(ctx) }.getOrDefault("")
        // 只有沙箱真装了才去算 proot 参数：shellLaunch 会刷运行期资产（DNS / getprop 垫片），
        // 没装的时候白跑一遍纯属拖慢终端启动。
        val args = if (root.isNotBlank() && File(root, "etc").isDirectory) {
            runCatching { QuroLinuxEnv.shellLaunch(ctx)?.second }.getOrNull()
                ?.toMutableList() ?: mutableListOf()
        } else {
            mutableListOf()
        }
        // shellLaunch 末尾固定是 `/bin/sh`（交互 sh）；切过去要的是登录 bash，由脚本自己追加
        while (args.isNotEmpty() && args.last() in setOf("/bin/sh", "sh", "-i")) {
            args.removeAt(args.lastIndex)
        }
        return Paths(
            termuxPrefix = TermuxEnvironment.usrDir(ctx).absolutePath,
            termuxHome = TermuxEnvironment.homeDir(ctx).absolutePath,
            ubuntuRoot = root,
            proot = runCatching { QuroLinuxEnv.prootPath(ctx) }.getOrDefault(""),
            prootArgs = args,
            termuxVersion = TermuxEnvironment.bootstrapVersion,
            // 这三样是 proot 能在 Termux 的 bash 里起来的必要条件：
            // 没有 loader → proot 直接退出；没有 tmp → 建不了 tracee 的临时文件；
            // 没有库路径 → guest 里的 /usr/bin 二进制找不到 .so。
            prootLoader = runCatching { QuroLinuxEnv.loaderPath(ctx) }.getOrDefault(""),
            prootTmp = runCatching { QuroLinuxEnv.tmpPath(ctx) }.getOrDefault(""),
            sandboxDir = runCatching { QuroLinuxEnv.sandboxDirPath(ctx) }.getOrDefault(""),
        )
    }

    /**
     * 把 `zorv` 装到两侧：Termux 的 `$PREFIX/bin` 与沙箱的 `/usr/local/bin`。
     *
     * 幂等：每次终端会话启动都可以调，几 KB 的写入，代价可忽略。
     * Termux 侧失败会返回错误（那是主路径，必须成功）；沙箱侧失败只记日志
     * （沙箱可能还没装，或 rootfs 只读），不影响主流程。
     */
    fun ensureCommands(ctx: Context): String? {
        val p = paths(ctx)
        val termuxCmd = File(p.termuxPrefix, "bin/$CMD")
        runCatching {
            termuxCmd.parentFile?.mkdirs()
            termuxCmd.writeText(template(ctx, p, forUbuntu = false))
            termuxCmd.setExecutable(true, false)
        }.onFailure {
            return "写入 ${termuxCmd.absolutePath} 失败：${it.message}"
        }

        val ubuntuBin = File(p.ubuntuRoot, "usr/local/bin")
        if (p.ubuntuRoot.isNotBlank() && File(p.ubuntuRoot, "etc").isDirectory) {
            runCatching {
                ubuntuBin.mkdirs()
                val f = File(ubuntuBin, CMD)
                f.writeText(template(ctx, p, forUbuntu = true))
                f.setExecutable(true, false)
            }.onFailure { Log.w(TAG, "沙箱侧 zorv 写入失败（不影响主流程）：${it.message}") }
        }
        runCatching { installShellHook(p) }.onFailure { Log.w(TAG, "shell 引导写入失败：${it.message}") }
        return null
    }

    /**
     * 把 `zorv` 挂成 bash 函数（写 `~/.bashrc` 与 `~/.profile`，幂等）。
     *
     * 为什么不能只靠 `$PREFIX/bin/zorv` 这个脚本：脚本要靠 shebang 起 `bash`，
     * 而那个 `bash` 同样躺在 app 私有目录里，必须由 termux-exec 改写 execve 才不 EACCES。
     * 函数是 bash 内部对象，**完全不经过 exec**，交互场景最稳；脚本留给非交互 / 其他 shell。
     */
    private fun installShellHook(p: Paths) {
        val marker = "# >>> zorv >>>"
        val block = buildString {
            appendLine()
            appendLine(marker)
            appendLine("if [ -r '${p.termuxPrefix}/bin/$CMD' ]; then")
            appendLine("    . '${p.termuxPrefix}/bin/$CMD'")
            appendLine("    zorv() { zorv_main \"\$@\"; }")
            appendLine("fi")
            appendLine("# <<< zorv <<<")
        }
        // 登录 shell 读 ~/.profile，交互 shell 读 ~/.bashrc：两边都挂，避免「-l 起来就没有 zorv」
        for (name in listOf(".bashrc", ".profile")) {
            val rc = File(p.termuxHome, name)
            rc.parentFile?.mkdirs()
            val cur = runCatching { if (rc.isFile) rc.readText() else "" }.getOrDefault("")
            if (cur.contains(marker)) continue
            rc.appendText(block)
        }
    }

    /** 渲染脚本（纯函数，单测可直接调）。 */
    fun template(ctx: Context, p: Paths, forUbuntu: Boolean): String {
        val raw = runCatching {
            ctx.assets.open(TEMPLATE).bufferedReader().use { it.readText() }
        }.getOrElse { return fallbackScript(p, forUbuntu) }
        return fill(raw, p, forUbuntu)
    }

    /** 纯替换逻辑，抽出来给单测（不需要 Context）。 */
    fun fill(raw: String, p: Paths, forUbuntu: Boolean): String {
        val shebang = if (forUbuntu) "/bin/bash" else "${p.termuxPrefix}/bin/bash"
        val defaultEnv = if (forUbuntu) ENV_UBUNTU else ENV_TERMUX
        // 🔴 先规范行尾：模板在 Windows 构建机上可能是 CRLF，而 shebang 行尾的 \r
        // 会让内核把解释器路径解析成 ".../bash\r" → `bad interpreter: No such file or directory`。
        return raw.replace("\r\n", "\n").replace("\r", "\n")
            .replace("{{SHEBANG}}", shebang)
            .replace("{{DEFAULT_ENV}}", defaultEnv)
            .replace("{{PREFIX}}", p.termuxPrefix)
            .replace("{{HOME}}", p.termuxHome)
            .replace("{{ROOTFS}}", p.ubuntuRoot)
            .replace("{{HOST}}", HOST_MOUNT)
            .replace("{{PROOT}}", p.proot)
            .replace("{{PROOT_LOADER}}", p.prootLoader)
            .replace("{{PROOT_TMP}}", p.prootTmp)
            .replace("{{SANDBOX_DIR}}", p.sandboxDir)
            .replace("{{TVERSION}}", p.termuxVersion)
            .replace("{{PROOT_ARGS}}", p.prootArgs.joinToString(" ") { shQuote(it) })
    }

    /** 单引号包裹 + `'\''` 转义，POSIX 安全。 */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * assets 读不到时的兜底（正常构建不会走到；只保证命令存在且提示清楚，
     * 避免「终端里没有 zorv」这种更难排查的次生问题）。
     */
    private fun fallbackScript(p: Paths, forUbuntu: Boolean): String {
        val shebang = if (forUbuntu) "/bin/bash" else "${p.termuxPrefix}/bin/bash"
        return buildString {
            appendLine("#!$shebang")
            appendLine("# zorv（兜底版本：assets 模板缺失，功能不完整）")
            appendLine("echo 'zorv: 脚本模板缺失，请重装应用以恢复'")
            appendLine("echo \"termux=$ENV_TERMUX  ubuntu=$ENV_UBUNTU  rootfs=${p.ubuntuRoot}\"")
        }
    }
}
