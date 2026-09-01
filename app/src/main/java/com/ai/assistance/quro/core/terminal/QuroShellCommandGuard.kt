package com.ai.assistance.quro.core.terminal

import android.util.Log

/**
 * 命令副作用分级（路线图标 P3「命令副作用分级」）。
 *
 * 这是把"AI 能执行"升级为"AI 能**安全**执行"的分界线：
 *  - [Risk.SAFE]：只读类（ls / cat / echo / pwd / grep / find / ps / git status …），
 *    AI 与用户均可直接执行，无副作用。
 *  - [Risk.WRITABLE]：有副作用但基本可逆（mkdir / cp / mv / touch / chmod / sed -i /
 *    apt install / pip install / git commit / kill …），AI 允许执行，交互终端直接执行。
 *  - [Risk.DESTRUCTIVE]：不可逆 / 高危（rm -rf / dd / mkfs / curl|sh / shutdown /
 *    fork bomb / chmod -R 000 / fdisk …）。AI / 自动化路径**默认拦截**，除非显式
 *    [confirmed]=true；交互终端里用户主动敲的则放行，但先打印醒目警告。
 *
 * 设计原则：纯函数、零副作用、不依赖具体后端（proot / 设备 shell 都适用）。
 * 仅在 [QuroTerminalController.runCommand] 与 [QuroShellSession.sendCommand] 入口调用，
 * 不改变任何已跑通的管道会话链路。
 */
object QuroShellCommandGuard {
    enum class Risk { SAFE, WRITABLE, DESTRUCTIVE }

    private const val TAG = "QuroShellCmdGuard"

    /** 破坏性命令特征（小写匹配，覆盖常见绕过写法：sudo rm -rf / RM -RF / rm -fr …）。 */
    private val DESTRUCTIVE = listOf(
        // 递归强制删除
        Regex("""\brm\s+.*-[a-z]*r[a-z]*f"""),
        Regex("""\brm\s+.*-[a-z]*f[a-z]*r"""),
        Regex("""\brm\s+-r\b"""),
        // 直接对根 / 家目录等高危目标删除
        Regex("""\brm\s+(-[a-z]*\s+)*/\s*$"""),
        Regex("""\brm\s+(-[a-z]*\s+)*~/?\s*$"""),
        // 磁盘写入 / 覆写
        Regex("""\bdd\s+.*\bof="""),
        Regex("""\bdd\s+if="""),
        // 文件系统格式化
        Regex("""\bmkfs"""),
        Regex("""\b(format|mke2fs|mkfs\.\w+)\b"""),
        // 设备写入
        Regex("""(>|>>)\s*/dev/"""),
        // 远程代码注入执行
        Regex("""\b(curl|wget)\b[^\n]*\|\s*(sh|bash|zsh)"""),
        Regex("""\|\s*(sh|bash|zsh)\s*$"""),
        // 关机和电源
        Regex("""\b(shutdown|reboot|halt|poweroff|init\s+0|init\s+6)\b"""),
        // fork bomb
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""),
        // 权限递归清零
        Regex("""\bchmod\s+.*-[a-z]*r[a-z]*\s+0+"""),
        Regex("""\bchmod\s+-R\s+0+"""),
        // 分区操作
        Regex("""\b(fdisk|parted|sgdisk|cfdisk|sfdisk)\b"""),
        // 数据擦除
        Regex("""\b(shred|truncate|wipefs|blkdiscard)\b"""),
        // 危险 mv 到根
        Regex("""\bmv\s+.*\s+/\s*$"""),
    )

    /** 有副作用但基本可逆（AI 允许）。 */
    private val WRITABLE = listOf(
        Regex("""\b(mkdir|touch|cp|mv|ln|tee|install)\b"""),
        Regex("""\bchmod\b"""),
        Regex("""\bchown\b"""),
        Regex("""\bsed\s+-i"""),
        Regex("""\b(apt|apt-get|dpkg|apk|pacman|yum|dnf|brew)\b.*\b(install|remove|purge|update|upgrade)"""),
        Regex("""\b(pip|pip3|npm|yarn|pnpm|gem|cargo)\b.*\b(install|uninstall|add|remove|global)"""),
        Regex("""\bgit\s+(commit|push|checkout|reset|clean|amend|rm)"""),
        Regex("""\b(kill|pkill|killall)\b"""),
        Regex("""\b(export|alias|set|unset)\b"""),
        Regex("""\b(service|systemctl|rc-service)\b"""),
        Regex("""\b(crontab|mount|umount)\b"""),
        Regex(""">\s"""),
        Regex(""">>\s"""),
    )

    /**
     * 归一化：去前后空白、抽掉常见前缀（sudo/time/nohup/env/command/setsid/ionice/nice，可叠堆）、合并多余空格。
     * 不展开变量（保留原样，避免误判）。
     */
    private fun normalize(cmd: String): String {
        var s = cmd.trim()
        val prefix = Regex("""^(sudo|doas|time|nohup|env|command|setsid|ionice|nice)\s+""")
        repeat(5) { s = prefix.replace(s, "") }
        return s.replace(Regex("""\s+"""), " ")
    }

    /** 对单条命令分级（纯函数）。 */
    fun classify(command: String): Risk {
        val c = normalize(command).lowercase()
        if (c.isEmpty()) return Risk.SAFE
        for (p in DESTRUCTIVE) {
            if (p.containsMatchIn(c)) {
                Log.d(TAG, "命令判为 DESTRUCTIVE: '$command' (命中 ${p.pattern})")
                return Risk.DESTRUCTIVE
            }
        }
        for (p in WRITABLE) {
            if (p.containsMatchIn(c)) return Risk.WRITABLE
        }
        return Risk.SAFE
    }

    /** 是否应当拦截（AI / 工具 / 自动化非交互路径，未确认时拦截破坏性命令）。 */
    fun shouldBlock(command: String, confirmed: Boolean): Boolean =
        classify(command) == Risk.DESTRUCTIVE && !confirmed

    /** 给 AI / 工具返回的人类可读拦截说明。 */
    fun blockReason(command: String): String =
        "⛔ 破坏性命令被拦截（命令副作用分级 · P3）：\n  $command\n" +
            "该命令可能不可逆（删除 / 覆写 / 格式化 / 远程代码执行等）。" +
            "如需执行，请先向用户明确说明风险，再在调用处设置 confirmed=true。"
}
