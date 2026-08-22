package com.ai.assistance.quro.core.terminal

import java.util.UUID

/**
 * 终端「命令完成哨兵」协议（E-8）。
 *
 * ## 为什么要随机化
 *
 * 旧实现用**固定**哨兵 `QURO_DONE`，[QuroShellSession.drain] 判定条件是
 * `raw.contains("QURO_DONE")`。于是任何一条输出里带这个词的命令都会被误判成
 * 「命令已结束」，例如：
 *
 * ```
 * echo QURO_DONE          # 直接提前复位 busy，真正的哨兵回来时又复位一次 → 多打一个提示符
 * grep -r QURO_DONE .     # 每命中一行就"结束"一次
 * cat 本文件               # 自举踩雷
 * ```
 *
 * 更糟的是误判发生后 `parseDone` 解析不出 `exit:cwd`，`lastExit` / `cwdState`
 * 保持旧值，用户看到的退出码是**上一条**命令的——静默错误。
 *
 * 现在每个会话在创建时生成一个随机 token（[newToken]），命令输出里撞上的概率
 * 可以忽略；且解析严格要求 `:<exit>:<cwd>` 结构，解析失败不复位 busy。
 *
 * ## 线路格式
 *
 * ```
 * <RS>QURO_DONE_<16位十六进制>:<exitCode>:<cwd><RS>
 * ```
 * `<RS>` = `\u001e`（ASCII Record Separator，正常命令输出里几乎不会出现）。
 *
 * 哨兵经 `printf ... >&2` 写到 stderr（C 库对 stderr 不做行缓冲），
 * 再由 `redirectErrorStream(true)` 并回同一个读取流，保证完成信号立即到达。
 *
 * 本对象为**纯 JVM 逻辑**，无 Android 依赖，可直接单元测试。
 */
object QuroTerminalSentinel {

    /** 记录分隔符，用于把哨兵和普通输出区分开。 */
    const val RS: Char = '\u001e'

    /** token 前缀，便于人肉排查日志。 */
    const val PREFIX: String = "QURO_DONE_"

    /** 一次命令完成的解析结果。 */
    data class Done(
        /** 命令的退出码。 */
        val exitCode: Int,
        /** 命令执行完毕后 shell 的工作目录；shell 未提供时为空串。 */
        val cwd: String,
    )

    /**
     * 生成一个新的随机哨兵 token。
     *
     * 取 UUID 的 16 位十六进制（64 bit），碰撞概率可忽略；
     * 全大写 + 下划线前缀，肉眼一看就知道是哨兵而不是命令输出。
     */
    fun newToken(): String =
        PREFIX + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()

    /**
     * 构造要写进 shell stdin 的哨兵发射命令（**不含**结尾换行，调用方自行补）。
     *
     * 用 POSIX 八进制转义 `\036` 而不是 `\x1e`：
     * `\xHH` 是 bash/GNU 扩展，dash / 部分 BusyBox printf 会原样输出 "x1e"；
     * 而 `\ddd` 八进制是 POSIX `printf` 明确要求支持的，proot 里的 Alpine ash
     * 和设备上的 Toybox 都能正确解释。
     *
     * `"$?"` 必须在**紧接着**上一条命令的下一行求值，否则拿到的是别的命令的退出码。
     */
    fun emitCommand(token: String): String =
        "printf '\\n\\036$token:%d:%s\\036\\n' \"\$?\" \"\$PWD\" >&2"

    /**
     * 判断某行输出是否**可能**是哨兵行（快速预筛，避免每行都走完整解析）。
     */
    fun looksLikeSentinel(line: String, token: String): Boolean = line.contains(token)

    /**
     * 从一行输出中解析哨兵。
     *
     * 严格要求 `<token>:<整数>:<cwd>` 结构；任何一环对不上都返回 `null`，
     * 由调用方当作普通输出处理——**宁可多打一行，也不要用错误的退出码复位状态**。
     *
     * 容错点：
     *  - 哨兵前后可能粘着 RS、空白、甚至同一行的残留输出（命令没以换行结尾时）；
     *  - cwd 里允许出现 `:`（取第二个分隔符之后的**全部**内容）。
     *
     * @param line 一整行原始输出
     * @param token 本会话的哨兵 token（[newToken] 的返回值）
     * @return 解析成功返回 [Done]，否则 `null`
     */
    fun parse(line: String, token: String): Done? {
        val start = line.indexOf(token)
        if (start < 0) return null

        // 去掉 token 之前的内容，以及尾部的 RS / 空白
        var rest = line.substring(start + token.length).trimEnd(RS, '\n', '\r', ' ', '\t')
        if (!rest.startsWith(":")) return null
        rest = rest.substring(1)

        val sep = rest.indexOf(':')
        if (sep < 0) return null

        val exitCode = rest.substring(0, sep).trim().toIntOrNull() ?: return null
        // cwd 取剩余全部（允许包含 ':'），只剥掉包裹用的 RS 与空白
        val cwd = rest.substring(sep + 1).trim(RS, ' ', '\t')
        return Done(exitCode, cwd)
    }

    /**
     * 把一行输出里**残留的哨兵片段**剥掉，返回应当展示给用户的部分。
     *
     * 用于哨兵与命令输出粘在同一行的情况：`hello<RS>QURO_DONE_xxx:0:/root<RS>`
     * 应当只展示 `hello`。
     */
    fun stripSentinel(line: String, token: String): String {
        val start = line.indexOf(token)
        if (start < 0) return line
        // token 前面可能有一个 RS，一并去掉
        val head = line.substring(0, start).trimEnd(RS)
        return head
    }
}
