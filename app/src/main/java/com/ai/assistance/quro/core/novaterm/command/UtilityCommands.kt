package com.ai.assistance.quro.core.novaterm.command

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
        val subCmd = cmd.getArg(0)

        return when (subCmd) {
            "list" -> {
                // 列出已安装的 App（需要相应权限）
                try {
                    val pm = android.content.Context::class.java
                    CommandResult.ok("Package manager not available in standalone mode")
                } catch (e: Exception) {
                    CommandResult.ok("(simulated) com.ai.assistance.quro.core.novaterm\n(simulated) com.example.app")
                }
            }
            "info" -> {
                val pkg = cmd.getArg(1)
                CommandResult.ok("Package: $pkg\nVersion: (simulated)\nPermissions: (simulated)")
            }
            else -> CommandResult.ok("pkg [list|info <name>]")
        }
    }
    override fun help() = "pkg [list|info <name>]  - 包管理"
}
