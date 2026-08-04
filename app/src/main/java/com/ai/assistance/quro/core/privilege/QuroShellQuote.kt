package com.ai.assistance.quro.core.privilege

/**
 * POSIX shell 引号转义（零依赖工具）。
 *
 * 单独成文件而不是塞进 [QuroRootGateway]，是为了避免
 * `QuroRootGateway → QuroShizuku → QuroRootGateway` 的循环 import：
 * [QuroShizuku][com.ai.assistance.quro.core.shizuku.QuroShizuku] 只需要转义能力，
 * 不该为此反向依赖整个 root 网关。
 */
object QuroShellQuote {

    /**
     * 把任意字符串包成一个 POSIX shell 单引号字面量。
     *
     * 单引号内除 `'` 自身外一切字符都是字面量（含空格、`$`、`*`、`;` 等），
     * 故只需把内部的 `'` 替换为 `'\''`：闭合引号 → 转义单引号 → 重新开引号。
     *
     * 用于命令需要**二次**经 `sh -c` / `su -c` 转发的场景，例如 Shizuku AIDL 路径：
     * `shellService.exec("su -c " + quote("ls -la /sdcard"))`
     * 若不转义，`su -c ls -la /sdcard` 会被 `su` 按 argv 拆开，`-c` 只吃到 `ls`。
     *
     * 例：
     * - `ls -la /sdcard` → `'ls -la /sdcard'`
     * - `echo it's` → `'echo it'\''s'`
     * - `` → `''`
     */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
