package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*

/**
 * 命令分发器
 * 路由命令到对应的执行器，处理权限检查和管道
 */
object CommandDispatcher {

    private val builtins = mutableMapOf<String, BuiltinCommand>()

    init {
        // 注册所有内置命令
        // 注：port 后各命令文件只暴露散装子命令 object（无聚合 object），
        // 故此处逐条注册，确保 cd/pwd/cat 等文件命令也能被分发。
        register(FileCommands)        // ls
        register(CdCommand)           // cd
        register(PwdCommand)          // pwd
        register(CatCommand)          // cat
        register(MkdirCommand)        // mkdir
        register(RmCommand)           // rm
        register(CpCommand)           // cp
        register(MvCommand)           // mv
        register(TouchCommand)        // touch
        register(FindCommand)         // find
        register(TreeCommand)         // tree

        register(PsCommand)           // ps
        register(TopCommand)          // top
        register(MemCommand)          // mem
        register(CpuInfoCommand)      // cpuinfo
        register(BatteryCommand)      // battery
        register(NetStatCommand)      // netstat
        register(GetpropCommand)      // getprop

        register(EchoCommand)         // echo
        register(GrepCommand)         // grep
        register(HeadCommand)         // head
        register(TailCommand)         // tail
        register(WcCommand)           // wc
        register(SortCommand)         // sort
        register(UniqCommand)         // uniq

        register(PingCommand)         // ping
        register(CurlCommand)         // curl
        register(DnsCommand)          // dns

        register(ClearCommand)        // clear
        register(HistoryCommand)      // history
        register(HelpCommand)         // help
        register(ManCommand)          // man
        register(ThemeCommand)        // theme
        register(AliasCommand)        // alias
        register(ExportCommand)       // export
        register(ExitCommand)         // exit
        register(SuCommand)           // su

        register(ScriptCommands)      // run（脚本引擎）
        register(EncryptCommand)      // encrypt
        register(Base64Command)       // base64
        register(CompressCommand)     // compress
        register(SandboxCommand)      // sandbox
        register(PkgCommand)          // pkg
    }

    private fun register(cmd: BuiltinCommand) {
        builtins[cmd.name] = cmd
        cmd.aliases.forEach { builtins[it] = cmd }
    }

    /**
     * 执行命令（入口）
     */
    fun execute(sessionId: String, input: String): CommandResult {
        // 解析
        val parsed = CommandParser.parse(input)

        return when (parsed.type) {
            CommandType.SIMPLE -> executeSimple(sessionId, parsed.mainCommand)
            CommandType.PIPELINE -> executePipeline(sessionId, parsed.pipeline)
            CommandType.REDIRECTION -> executeRedirection(sessionId, parsed)
        }
    }

    private fun executeSimple(sessionId: String, cmd: Command): CommandResult {
        // 权限检查
        val (allowed, error) = PermissionController.checkCommand(sessionId, cmd.name)
        if (!allowed) return CommandResult.err(error ?: "Permission denied")

        // 查找内置命令
        val builtin = builtins[cmd.name]
        if (builtin != null) {
            return try {
                builtin.execute(sessionId, cmd)
            } catch (e: Exception) {
                CommandResult.err("${cmd.name}: ${e.message}")
            }
        }

        // 尝试执行外部脚本（.nv 文件）
        val scriptPath = cmd.name + if (cmd.name.endsWith(".nv")) "" else ""
        if (FileSystem.exists(sessionId, scriptPath)) {
            return ScriptCommands.runScript(sessionId, scriptPath, cmd.args)
        }

        return CommandResult.err("${cmd.name}: command not found. Type 'help' for available commands.")
    }

    private fun executePipeline(sessionId: String, commands: List<Command>): CommandResult {
        var output = ""
        for (cmd in commands) {
            // 将前一个命令的输出作为管道输入
            val result = executeSimple(sessionId, cmd)
            output = when (result) {
                is CommandResult.Text -> result.output
                else -> output
            }
        }
        return CommandResult.ok(output)
    }

    private fun executeRedirection(sessionId: String, parsed: ParsedCommand): CommandResult {
        val result = executeSimple(sessionId, parsed.mainCommand)
        val text = when (result) {
            is CommandResult.Text -> result.output
            else -> ""
        }

        when (parsed.redirectType) {
            RedirectType.OVERWRITE -> {
                FileSystem.writeFile(sessionId, parsed.redirectTarget, text)
            }
            RedirectType.APPEND -> {
                FileSystem.writeFile(sessionId, parsed.redirectTarget, text, append = true)
            }
            else -> {}
        }
        return CommandResult.ok("redirected to ${parsed.redirectTarget}")
    }

    fun getHelp(command: String): String {
        return builtins[command]?.help() ?: "No help available for '$command'"
    }

    fun listAllCommands(): List<String> {
        return builtins.keys.distinct().sorted()
    }
}
