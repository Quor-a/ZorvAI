package com.ai.assistance.quro.termux.app

import android.content.Context
import com.ai.assistance.quro.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment

/**
 * 把官方 TermuxSession 的 shell 启动替换为 proot Ubuntu 启动。
 *
 * 官方 `TermuxSession.execute()` 默认查找 `$PREFIX/bin/login`（bootstrap 路径），
 * 在 Termux中该路径不存在。通过 [TermuxSession.setProotLaunchProvider]
 * 注入本实现，让会话直接使用 [TerminalEnvironment.buildProotLaunch] 生成的
 * proot 命令（`--rootfs=... --bind=... -0 -w /root /bin/bash -lc ...`）。
 */
class ProotLaunchProviderImpl(context: Context) : TermuxSession.ProotLaunchProvider {

    private val environment: TerminalEnvironment = TerminalEnvironment(context)

    /** 返回 proot 启动命令：{prootBin, --rootfs=..., ..., -0, -w, /root, /bin/bash, -lc, ...} */
    override fun getProotLaunchCommand(): Array<String>? {
        return runCatching {
            val spec = environment.buildProotLaunch()
            // command 已是完整的可执行命令列表
            spec.command.toTypedArray()
        }.getOrNull()
    }

    /** 返回 proot 启动必需的环境变量。 */
    override fun getProotLaunchEnvironment(): java.util.HashMap<String, String>? {
        return runCatching {
            val spec = environment.buildProotLaunch()
            val map = java.util.HashMap<String, String>()
            spec.env.forEach { (k, v) -> map[k] = v }
            map
        }.getOrNull()
    }
}