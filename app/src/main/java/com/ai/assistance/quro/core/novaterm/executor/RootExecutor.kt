package com.ai.assistance.quro.core.novaterm.executor

import com.ai.assistance.quro.core.novaterm.command.CommandResult
import java.io.DataOutputStream

/**
 * Root 执行器
 * 通过 su 执行需要 root 权限的命令
 */
object RootExecutor {

    private var suProcess: Process? = null
    private var suOutputStream: DataOutputStream? = null
    private var isRootAvailable: Boolean? = null

    /**
     * 检测 Root 是否可用
     */
    fun checkRoot(): Boolean {
        if (isRootAvailable != null) return isRootAvailable!!

        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            isRootAvailable = output.contains("uid=0")
            isRootAvailable!!
        } catch (e: Exception) {
            isRootAvailable = false
            false
        }
    }

    /**
     * 获取持久化的 su shell
     */
    private fun getSuShell(): DataOutputStream? {
        if (suProcess == null || suOutputStream == null) {
            try {
                val process = Runtime.getRuntime().exec("su")
                suOutputStream = DataOutputStream(process.outputStream)
                suProcess = process
            } catch (e: Exception) {
                return null
            }
        }
        return suOutputStream
    }

    /**
     * 执行 root 命令
     */
    fun execute(command: String, timeoutMs: Long = 15000): CommandResult {
        if (!checkRoot()) {
            return CommandResult.err("Root not available. Device is not rooted.")
        }

        return try {
            val process = Runtime.getRuntime().exec("su -c $command")
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            process.waitFor()

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                CommandResult.ok(stdout.trim())
            } else {
                CommandResult.err(stderr.trim().ifEmpty { "Command failed with exit code $exitCode" })
            }
        } catch (e: Exception) {
            CommandResult.err("Root exec error: ${e.message}")
        }
    }

    /**
     * 在持久 su shell 中执行（适合多条命令）
     */
    fun executeInShell(command: String): CommandResult {
        val out = getSuShell() ?: return CommandResult.err("Failed to open su shell")

        return try {
            out.writeBytes("$command\necho NOVA_ROOT_DONE_$?\n")
            out.flush()

            val process = suProcess!!
            val reader = process.inputStream.bufferedReader()
            val lines = mutableListOf<String>()
            var done = false

            while (!done) {
                val line = reader.readLine() ?: break
                if (line.contains("NOVA_ROOT_DONE_")) {
                    done = true
                } else {
                    lines.add(line)
                }
            }

            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("Su shell error: ${e.message}")
        }
    }

    fun closeShell() {
        try {
            suOutputStream?.writeBytes("exit\n")
            suOutputStream?.flush()
            suOutputStream?.close()
            suProcess?.destroy()
        } catch (e: Exception) {}
        suOutputStream = null
        suProcess = null
    }
}
