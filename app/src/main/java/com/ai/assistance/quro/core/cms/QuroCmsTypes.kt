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
    /** 执行类型：intent=启动 Activity(应用内派发) / js=应用内 QuickJS 沙箱执行 / api=应用内 Android API(只读/轻量) / shell=已弃用(真实命令，已禁用)。 */
    val actionType: String,
    /** 执行内容：shell 为命令模板(${arg} 替换) / intent 为 JSON。 */
    val action: String,
) {
    /** 从动作模板中解析出参数名（${name}）。所有 actionType 通用（intent/json/js/api 一并支持 ${arg} 代入）。 */
    fun argNames(): List<String> {
        return "\\$\\{([^}]+)\\}".toRegex().findAll(action).map { it.groupValues[1] }.toList()
    }

    /** 把参数代入命令模板。 */
    fun resolveAction(args: Map<String, String>): String {
        var cmd = action
        argNames().forEach { name ->
            cmd = cmd.replace("\${$name}", args[name] ?: "")
        }
        return cmd.trim()
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
