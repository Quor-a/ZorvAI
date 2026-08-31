package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.activity.QuroApplication
import com.ai.assistance.quro.core.linux.DETECT_DISTRO_CMD
import com.ai.assistance.quro.core.linux.QuroLinuxDistroDetector
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.core.FileSystem
import com.ai.assistance.quro.core.novaterm.core.SessionManager
import java.security.MessageDigest
import java.util.Base64

/**
 * 实用工具命令集
 */
object ScriptCommands : BuiltinCommand {
    override val name = "run"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val scriptPath = cmd.getArg(0)
        if (scriptPath.isEmpty()) return CommandResult.err("run: missing script path")

        return runScript(sessionId, scriptPath, cmd.args.drop(1))
    }

    fun runScript(sessionId: String, path: String, args: List<String>): CommandResult {
        val content = FileSystem.readFile(sessionId, path).getOrNull()
            ?: return CommandResult.err("run: script not found: $path")

        val lines = content.lines().filter { it.isNotBlank() && !it.trim().startsWith("#") }
        val outputs = mutableListOf<String>()

        for (line in lines) {
            val result = CommandDispatcher.execute(sessionId, line)
            when (result) {
                is CommandResult.Text -> if (result.output.isNotEmpty()) outputs.add(result.output)
                else -> {}
            }
        }
        return CommandResult.ok(outputs.joinToString("\n"))
    }

    override fun help() = "run <script.nv>  - 执行 QuroTerm 脚本"
}

object EncryptCommand : BuiltinCommand {
    override val name = "encrypt"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val input = cmd.getArg(0)
        if (input.isEmpty()) return CommandResult.err("encrypt: missing input")

        val algorithm = cmd.getOption("-a", "sha256")
        return try {
            val bytes = input.toByteArray()
            val digest = MessageDigest.getInstance(algorithm.uppercase())
            val hash = digest.digest(bytes)
            val hex = hash.joinToString("") { "%02x".format(it) }
            CommandResult.ok("$algorithm($input) = $hex")
        } catch (e: Exception) {
            CommandResult.err("encrypt: ${e.message}")
        }
    }
    override fun help() = "encrypt [-a sha256|md5|sha1] <text>  - 计算哈希"
}

object Base64Command : BuiltinCommand {
    override val name = "base64"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val input = cmd.getArg(0)
        val decode = cmd.hasFlag("-d") || cmd.hasFlag("--decode")

        return if (decode) {
            try {
                val decoded = Base64.getDecoder().decode(input)
                CommandResult.ok(String(decoded))
            } catch (e: Exception) {
                CommandResult.err("base64: decode failed")
            }
        } else {
            val encoded = Base64.getEncoder().encodeToString(input.toByteArray())
            CommandResult.ok(encoded)
        }
    }
    override fun help() = "base64 [-d] <text>  - Base64 编码/解码"
}

object CompressCommand : BuiltinCommand {
    override val name = "compress"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val filePath = cmd.getArg(0)
        if (filePath.isEmpty()) return CommandResult.err("compress: missing file")

        val content = FileSystem.readFile(sessionId, filePath).getOrNull()
            ?: return CommandResult.err("compress: file not found")

        val bytes = content.toByteArray()
        val compressed = java.util.zip.GZIPOutputStream(
            java.io.ByteArrayOutputStream().apply { }
        ).let { gzip ->
            val baos = java.io.ByteArrayOutputStream()
            val gz = java.util.zip.GZIPOutputStream(baos)
            gz.write(bytes)
            gz.close()
            baos.toByteArray()
        }

        val ratio = String.format("%.1f%%", (1 - compressed.size.toDouble() / bytes.size) * 100)
        return CommandResult.ok("Original: ${bytes.size} bytes\nCompressed: ${compressed.size} bytes\nRatio: $ratio")
    }
    override fun help() = "compress <file>  - GZIP 压缩文件"
}

object SandboxCommand : BuiltinCommand {
    override val name = "sandbox"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val subCmd = cmd.getArg(0)

        return when (subCmd) {
            "status" -> {
                val perm = PermissionController.getLevel(sessionId)
                CommandResult.ok("Sandbox Status\n  Permission: $perm\n  CWD: ${FileSystem.getCwd(sessionId)}")
            }
            "reset" -> {
                FileSystem.setCwd(sessionId, "/")
                PermissionController.deescalate(sessionId)
                CommandResult.ok("Sandbox reset to default state")
            }
            else -> CommandResult.ok("sandbox [status|reset]")
        }
    }
    override fun help() = "sandbox [status|reset]  - 沙盒管理"
}

object PkgCommand : BuiltinCommand {
    override val name = "pkg"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val sub = cmd.getArg(0)
        val ctx = QuroApplication.appCtx

        // 探测 Linux 环境发行版 → 选对应包管理器（apk/apt/dnf/pacman）
        val spec = runCatching {
            val osRelease = ctx?.let { c -> QuroLinuxEnv.run(c, DETECT_DISTRO_CMD, 15000).second } ?: ""
            QuroLinuxDistroDetector.packageManagerFor(osRelease)
        }.getOrElse { QuroLinuxDistroDetector.packageManagerFor("") }

        // 在 Linux 环境内执行生成的命令；无环境/执行失败时回退为「返回命令串」供手动跑。
        val run: (String) -> CommandResult = { command ->
            if (ctx == null) {
                CommandResult.ok("(在 Linux 终端执行) $command")
            } else {
                runCatching {
                    val (code, out) = QuroLinuxEnv.run(ctx, command, 180000)
                    if (code == 0) CommandResult.ok(out.ifBlank { "✓ 执行成功：$command" })
                    else CommandResult.ok("exit=$code\n$out")
                }.getOrElse { CommandResult.ok("(命令已生成，请在 Linux 环境执行) $command") }
            }
        }

        return when (sub) {
            "install" -> {
                val pkgs = cmd.args.drop(1)
                if (pkgs.isEmpty()) return CommandResult.err("用法: pkg install <包名...>")
                run(spec.install(pkgs))
            }
            "remove", "uninstall" -> {
                val pkgs = cmd.args.drop(1)
                if (pkgs.isEmpty()) return CommandResult.err("用法: pkg remove <包名...>")
                run(spec.remove(pkgs))
            }
            "update" -> run(spec.update())
            "upgrade" -> run(spec.upgrade())
            "search" -> {
                val q = cmd.getArg(1)
                if (q.isBlank()) return CommandResult.err("用法: pkg search <关键词>")
                run(spec.search(q))
            }
            "list" -> run(spec.listInstalled(cmd.getArg(1).ifBlank { null }))
            "info" -> {
                val p = cmd.getArg(1)
                if (p.isBlank()) return CommandResult.err("用法: pkg info <包名>")
                run(spec.info(p))
            }
            "clean" -> run(spec.clean())
            "detect" -> CommandResult.ok("当前包管理器：${spec.displayName}（${spec.binary}）")
            else -> CommandResult.ok(
                "pkg <子命令> [参数]  —— 基于 Linux 环境 /etc/os-release 自动选择包管理器\n" +
                "  install <包...>   安装软件包\n" +
                "  remove  <包...>   卸载软件包\n" +
                "  update            更新软件源索引\n" +
                "  upgrade           升级全部已装软件\n" +
                "  search <关键词>   搜索可用包\n" +
                "  list [过滤]       列出已安装包\n" +
                "  info <包名>       查看包详情\n" +
                "  clean             清理包缓存\n" +
                "  detect            探测当前发行版与包管理器"
            )
        }
    }
    override fun help() = "pkg <install|remove|update|upgrade|search|list|info|clean|detect>  - Linux 包管理"
}
