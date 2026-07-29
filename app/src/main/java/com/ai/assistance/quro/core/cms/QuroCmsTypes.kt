package com.ai.assistance.quro.core.cms

/**
 * CMS v2 能力模块系统 — 类型定义。
 *
 * 核心概念：
 * - [QuroCmsModule] 能力模块（替代旧「插件/工具包/技能/MCP」的统一单元）
 * - [PermissionLevel] 权限级别：普通 / 特殊(悬浮窗) / 高级(Shizuku) / 最高(Root)
 * - [AuthorizationLevel] 4 级授权：临时(L1) / 会话(L2) / 永久(L3) / 全局(L4) + 拒绝
 * - [PermissionConstraints] 细粒度约束：路径 / 命令 / 域名 / 超时 / 内存
 */
enum class ModuleState {
    Loaded, Ready, Running, Stopped, Error
}

/**
 * 运行时宿主（对应元宝「能力可前后端互换」架构的 Runtime Host）。
 * - APP：前端宿主，指 Android 应用进程内（intent/js/api 通道）。
 * - TERMINAL：后端宿主，指 proot/Alpine 应用内 Linux 沙箱（terminal 通道），可跑 python3/node/任意二进制。
 * 一个能力声明 [QuroCmsCapability.runOn] 标明可在哪些宿主运行；调用时经 [CmsHostRouter]
 * 按 target(auto/app/terminal) + 运行时上下文（电量/锁屏/proot 可用性）选宿主，实现「互为主从」。
 */
enum class RuntimeHost(val label: String) {
    APP("应用内"),
    TERMINAL("终端(proot)");
    companion object {
        /** actionType → 默认宿主集合（向后兼容：未显式声明 runOn 时按此推导）。 */
        fun hostsFor(actionType: String): Set<RuntimeHost> = when (actionType) {
            "terminal" -> setOf(TERMINAL)
            else -> setOf(APP) // intent / js / api 均运行于应用内
        }
        /** 从持久化名称解析（"APP"/"TERMINAL"），未知返回 null。 */
        fun fromName(name: String): RuntimeHost? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 能力调用目标宿主（CIP · Capability Invocation Protocol 的 target 字段）。
 * - AUTO：由系统按运行时上下文自动选择（推荐）。
 * - APP：强制在前端应用内宿主执行。
 * - TERMINAL：强制在后端 proot 终端宿主执行。
 */
enum class InvocationTarget(val label: String) {
    AUTO("auto"),
    APP("app"),
    TERMINAL("terminal");
    companion object {
        fun parse(s: String?): InvocationTarget = when ((s ?: "auto").trim().lowercase()) {
            "app", "frontend", "应用", "前端" -> APP
            "terminal", "backend", "后端", "终端" -> TERMINAL
            else -> AUTO
        }
    }
}

enum class PermissionLevel {
    Normal,   // 普通
    Special,  // 特殊（如悬浮窗）
    Elevated, // 高级（如 Shizuku）
    Critical  // 最高（如 Root）
}

enum class AuthorizationLevel {
    Temporary,  // L1 临时令牌：高危使用后回收
    Session,    // L2 会话授权：本次开机期间有效
    Permanent,  // L3 永久授权
    Global,     // L4 全局授权（危险）
    Denied      // 拒绝
}

/** 细粒度执行约束。 */
data class PermissionConstraints(
    val allowedPaths: List<String> = emptyList(),
    val allowedCommands: List<String> = emptyList(),
    val allowedDomains: List<String> = emptyList(),
    val maxExecutionTimeSecs: Int = 30,
    val maxMemoryMb: Int = 64,
)

/** 模块声明的一项权限需求。 */
data class QuroCmsPermission(
    val id: String,
    val level: PermissionLevel,
    val rationale: String,
    val scope: String,
    val authorization: AuthorizationLevel,
)

/** 模块暴露的一项能力（可由用户或系统调用，类似「技能/功能单元」）。 */
data class QuroCmsCapability(
    val id: String,
    val summary: String,
    val schema: String,
    val requiresPermissions: List<String>,
    val constraints: PermissionConstraints,
    /** 执行类型：intent=启动 Activity(应用内派发) / js=应用内 QuickJS 沙箱执行 / api=应用内 Android API(只读/轻量) / terminal=proot 终端 / shell=已弃用(真实命令，已禁用)。 */
    val actionType: String,
    /** 执行内容：shell/terminal 为命令模板(${arg} 替换) / intent 为 JSON / js 为脚本。 */
    val action: String,
    /** 可运行宿主集合（元宝「能力可前后端互换」）。缺省按 actionType 推导；可显式声明同时支持 APP+TERMINAL。 */
    val runOn: Set<RuntimeHost> = RuntimeHost.hostsFor(actionType),
    /** 当宿主选为 TERMINAL 时使用的命令模板（默认 null → 退回应用内 action；仅 terminal 类能力本身即在终端跑）。 */
    val terminalAction: String? = null,
) {
    /** 从默认 action 模板解析参数名（${name}）。 */
    fun argNames(): List<String> = argNamesOf(action)

    /** 把参数代入指定模板（${name} → args）。 */
    fun resolveTemplate(tpl: String, args: Map<String, String>): String {
        var cmd = tpl
        argNamesOf(tpl).forEach { name -> cmd = cmd.replace("\${$name}", args[name] ?: "") }
        return cmd.trim()
    }

    /** 把参数代入默认 action 模板（向后兼容）。 */
    fun resolveAction(args: Map<String, String>): String = resolveTemplate(action, args)

    /** 选定宿主后，返回实际应执行的 (模板, 通道类型)。TERMINAL 且有 terminalAction 时切换。 */
    fun effectiveFor(host: RuntimeHost): Pair<String, String> =
        if (host == RuntimeHost.TERMINAL && terminalAction != null) terminalAction to "terminal"
        else action to actionType

    private companion object {
        private fun argNamesOf(tpl: String): List<String> =
            "\\$\\{([^}]+)\\}".toRegex().findAll(tpl).map { it.groupValues[1] }.toList()
    }
}

/** 依赖种类（CMS v2 原创运行时：4 类依赖 + 旧能力依赖兼容）。 */
enum class DepKind {
    /** 另一个 CMS 模块 id。 */
    MODULE,
    /** MCP 别名。 */
    MCP,
    /** SKILL id。 */
    SKILL,
    /** Linux 包（apk add / pip install，终端沙箱内）。 */
    LINUX,
    /** 旧格式：本模块内声明的能力 id。 */
    CAPABILITY,
    /** 终端开发环境栈（Node/Python/SSH/Java/Rust/Go，部署时由 CmsEnvProvisioner 装配）。 */
    ENV,
}

/** 能力模块依赖声明（CMS v2：支持 module/mcp/skill/linuxPkg 四类）。 */
data class QuroCmsDependency(
    val kind: DepKind = DepKind.CAPABILITY,
    /** 目标：MODULE=模块id / MCP=别名 / SKILL=id / LINUX=包名 / CAPABILITY=能力id / ENV=环境档名(NODE/PYTHON/SSH/JAVA/RUST/GO)。 */
    val spec: String = "",
    /** 旧格式兼容：能力 id（kind==CAPABILITY 且 spec 为空时回退用）。 */
    val capability: String = "",
    val version: String = "",
    val optional: Boolean = false,
) {
    /** 实际目标字符串（spec 优先，否则回退 capability）。 */
    fun target(): String = if (spec.isNotBlank()) spec else capability
}

/** 能力模块（CMS v2 核心单元）。 */
data class QuroCmsModule(
    val id: String,
    val name: String = id,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val license: String = "",
    val state: ModuleState = ModuleState.Stopped,
    val permissions: List<QuroCmsPermission> = emptyList(),
    val capabilities: List<QuroCmsCapability> = emptyList(),
    val dependencies: List<QuroCmsDependency> = emptyList(),
    val lifecycle: String = "onDemand",
    val catalog: String = "",
    val signature: String = "",
    /** 终端(proot)真实入口脚本：非空时由 CmsDeployPackage.fromModule 直接作为 entry.sh 部署，
     *  实现「一键部署 CMS v2 系统构架到终端的包」（区别于仅部署 manifest 的空壳）。手机模块留空。 */
    val terminalEntry: String = "",
) {
    /** 找到某权限声明（按 id）。 */
    fun findPermission(id: String): QuroCmsPermission? = permissions.firstOrNull { it.id == id }

    /** 所需权限中的最高级别（用于选择执行通道）。 */
    fun maxRequiredLevel(): PermissionLevel {
        val levels = capabilities.flatMap { cap ->
            cap.requiresPermissions.mapNotNull { findPermission(it)?.level }
        }
        if (levels.isEmpty()) return PermissionLevel.Normal
        return levels.maxOf { it.ordinal }.let { ord -> PermissionLevel.values()[ord] }
    }
}
